package app.aoki.yuki.wirelesstntn;

import android.content.ComponentName;
import android.nfc.cardemulation.CardEmulation;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * LSPosed module that hijacks the NFC Listener Mode Routing Table (LMRT).
 *
 * ---  BACKGROUND  ---
 *
 * When a contactless reader presents to an Android phone, the NFC controller
 * consults its hardware Listener Mode Routing Table (LMRT). Each row in that
 * table maps an AID (ISO 7816-4 Application Identifier) to a destination:
 *   - ROUTE_HOST (0x00)  → Device Host: delivered to an Android HCE service
 *   - ROUTE_ESE / UICC  → Off-host Secure Element (not relevant here)
 *
 * The LMRT is built by AidRoutingManager.commit() from the AID registrations
 * declared in HCE services' apduservice.xml. When an AID SELECT arrives that
 * routes to the host, Android's HostEmulationManager calls:
 *
 *   RegisteredAidCache.resolveAid(String aid)
 *
 * to decide *which* Java HCE service to deliver the APDU to. That is the
 * single Java-layer chokepoint we exploit here.
 *
 * ---  WHAT THIS MODULE DOES  ---
 *
 * We hook RegisteredAidCache.resolveAid() inside the com.android.nfc process
 * and replace its return value so that every AID routed to the host is
 * redirected to our PassthroughHceService, regardless of what other HCE
 * services are installed.
 *
 * PassthroughHceService forwards every APDU to the physical Secure Element
 * via OMAPI, turning the phone into a transparent NFC ↔ SE bridge.
 *
 * ---  ANDROID 15 SPECIFICS  ---
 *
 * The class/method we hook (RegisteredAidCache.resolveAid) has been present
 * since Android 4.4; its signature is unchanged in Android 15:
 *
 *   public AidResolveInfo resolveAid(String aid)
 *
 * AidResolveInfo is a *non-static* inner class of RegisteredAidCache.
 * Its fields on Android 15 (platform/packages/apps/Nfc, android-15 branch):
 *
 *   List<ApduServiceInfo>      services
 *   ApduServiceInfo            defaultService
 *   String                     category
 *   ResolvedPrefixConflictAid  prefixInfo                    (nullable)
 *   List<String>               unCheckedOffHostSecureElement (empty list)
 *
 * (The 'aid' field that existed in some intermediate versions is absent.)
 *
 * Because AidResolveInfo is non-static, its constructor takes the enclosing
 * RegisteredAidCache instance as its first (implicit) argument in reflection.
 *
 * Note: android.nfc.cardemulation.ApduServiceInfo is a @hide / @SystemApi
 * class not present in the SDK android.jar. We reference it only as Object
 * at compile time; the NFC process's classloader provides the real class at
 * runtime.
 *
 * ---  REQUIREMENTS  ---
 *
 *   - Root access (to install LSPosed)
 *   - LSPosed framework (Zygisk-based, Android 8+, tested on Android 15)
 *   - This app declared in LSPosed scope → com.android.nfc
 *   - apduservice.xml must register at least a few AID prefixes so that the
 *     NFC controller's LMRT routes those AIDs to the device host in the first
 *     place. The hook then takes over the Java-layer routing decision.
 *
 * @see "https://github.com/johnzweng/XposedModifyAidRouting Original (Android 4-7) reference"
 * @see ref_aosp/NfcNci/src/com/android/nfc/cardemulation/RegisteredAidCache.java
 * @see ref_aosp/NfcNci/src/com/android/nfc/cardemulation/HostEmulationManager.java
 */
public class LmrtHijackModule implements IXposedHookLoadPackage {

    /** Package name of the NFC system service process we want to hook. */
    private static final String NFC_PACKAGE = "com.android.nfc";

    /** Our app package and the fully-qualified class name of our HCE service. */
    private static final String OUR_PACKAGE = "app.aoki.yuki.wirelesstntn";
    private static final String OUR_SERVICE = OUR_PACKAGE + ".PassthroughHceService";

    // -------------------------------------------------------------------------
    // Entry point: called by LSPosed for every loaded package
    // -------------------------------------------------------------------------

    /**
     * Called by the LSPosed framework when any package (process) is loaded.
     * We only act when the NFC package is loaded.
     */
    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!NFC_PACKAGE.equals(lpparam.packageName)) {
            // Not the NFC process — do nothing.
            return;
        }

        XposedBridge.log("LmrtHijackModule: NFC process loaded, installing resolveAid hook");

        // Hook RegisteredAidCache.resolveAid(String aid).
        // This method is the single Java-layer chokepoint that decides which
        // HCE service receives each AID SELECT command from the NFC stack.
        try {
            XposedHelpers.findAndHookMethod(
                    "com.android.nfc.cardemulation.RegisteredAidCache",
                    lpparam.classLoader,
                    "resolveAid",
                    String.class,
                    new ResolveAidHook()
            );
            XposedBridge.log("LmrtHijackModule: hook installed successfully");
        } catch (Throwable t) {
            XposedBridge.log("LmrtHijackModule: failed to install hook — " + t);
            XposedBridge.log(t);
        }
    }

    // -------------------------------------------------------------------------
    // The hook
    // -------------------------------------------------------------------------

    /**
     * Replaces the result of RegisteredAidCache.resolveAid() so that every
     * AID that arrives at the device host is routed to our service.
     *
     * We use beforeHookedMethod so the original method is never executed,
     * saving the (sometimes expensive) prefix-tree traversal.
     *
     * All NFC-internal types (ApduServiceInfo, AidResolveInfo, etc.) are
     * handled as Object because they are @hide classes not present in the SDK.
     * Their fields are accessed via reflection using XposedHelpers.findField().
     */
    private static final class ResolveAidHook extends XC_MethodHook {

        @Override
        @SuppressWarnings({"rawtypes", "unchecked"})
        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
            // param.thisObject : the RegisteredAidCache instance
            // param.args[0]    : the AID string (hex, upper-case, no separators)
            String aid = (String) param.args[0];
            Object aidCache = param.thisObject;

            try {
                // ----------------------------------------------------------
                // Step 1: Locate our service's ApduServiceInfo object.
                //
                // mAidCache is a TreeMap<String, AidResolveInfo> built during
                // RegisteredServicesCache.onUserSwitched / servicePackagesChanged.
                // It is populated from the AIDs declared in apduservice.xml.
                // Our service must appear there for at least one AID prefix so
                // the NFC controller's LMRT routes at least some AIDs to DH.
                //
                // ApduServiceInfo is a @hide class. We use Object and reflection.
                // ----------------------------------------------------------
                Field mAidCacheField = XposedHelpers.findField(aidCache.getClass(), "mAidCache");
                Map mAidCacheMap = (Map) mAidCacheField.get(aidCache);

                if (mAidCacheMap == null || mAidCacheMap.isEmpty()) {
                    // Cache not yet built (device just booted?). Fall through to
                    // original routing so NFC continues to work normally.
                    XposedBridge.log("LmrtHijackModule: mAidCache empty, skipping AID " + aid);
                    return;
                }

                // Scan the cache for an ApduServiceInfo whose getComponent()
                // matches our service.
                Object ourServiceInfo = findOurServiceInfo(mAidCacheMap);

                if (ourServiceInfo == null) {
                    // Our service is not registered yet (user hasn't opened the app).
                    // Leave routing unchanged; there is nothing to redirect to.
                    XposedBridge.log("LmrtHijackModule: our service absent from mAidCache, skipping AID " + aid);
                    return;
                }

                // ----------------------------------------------------------
                // Step 2: Construct a new AidResolveInfo that points solely
                //         to our service and set it as the method result.
                // ----------------------------------------------------------
                Object newResolveInfo = buildAidResolveInfo(aidCache, ourServiceInfo);
                if (newResolveInfo == null) {
                    // Reflection failed — leave original routing intact.
                    XposedBridge.log("LmrtHijackModule: could not build AidResolveInfo, skipping AID " + aid);
                    return;
                }

                param.setResult(newResolveInfo);
                XposedBridge.log("LmrtHijackModule: AID " + aid + " → " + OUR_SERVICE);

            } catch (Throwable t) {
                // Log and fall through; never crash the NFC process.
                XposedBridge.log("LmrtHijackModule: unexpected error in hook for AID " + aid + " — " + t);
                XposedBridge.log(t);
            }
        }

        // ------------------------------------------------------------------
        // Helper: scan mAidCache for our service's ApduServiceInfo
        // ------------------------------------------------------------------

        /**
         * Scans all AidResolveInfo entries in mAidCache and returns the first
         * ApduServiceInfo (as Object, since it is @hide) whose getComponent()
         * matches our PassthroughHceService component name.
         *
         * Search order per entry: defaultService field first (fast path), then
         * the full services list.
         *
         * @param mAidCacheMap  live mAidCache TreeMap from RegisteredAidCache
         * @return              ApduServiceInfo for our service (as Object), or null
         */
        @SuppressWarnings({"rawtypes", "unchecked"})
        private Object findOurServiceInfo(Map mAidCacheMap) {
            // ComponentName of our service.
            ComponentName ourComponent =
                    ComponentName.unflattenFromString(OUR_PACKAGE + "/" + OUR_SERVICE);

            for (Object resolveInfoObj : mAidCacheMap.values()) {
                if (resolveInfoObj == null) continue;
                try {
                    // Check the defaultService field first (faster path).
                    Field defaultServiceField =
                            XposedHelpers.findField(resolveInfoObj.getClass(), "defaultService");
                    Object defaultService = defaultServiceField.get(resolveInfoObj);
                    if (defaultService != null
                            && ourComponent.equals(getComponent(defaultService))) {
                        return defaultService;
                    }

                    // Fall back to scanning the services list.
                    Field servicesField =
                            XposedHelpers.findField(resolveInfoObj.getClass(), "services");
                    List services = (List) servicesField.get(resolveInfoObj);
                    if (services != null) {
                        for (Object svc : services) {
                            if (svc != null && ourComponent.equals(getComponent(svc))) {
                                return svc;
                            }
                        }
                    }
                } catch (Throwable ignored) {
                    // Skip entries whose fields cannot be introspected.
                }
            }
            return null;
        }

        /**
         * Calls getComponent() on an ApduServiceInfo instance via reflection.
         * We cannot call it directly because ApduServiceInfo is @hide.
         *
         * @param apduServiceInfo  an ApduServiceInfo instance (as Object)
         * @return                 the ComponentName, or null on failure
         */
        private ComponentName getComponent(Object apduServiceInfo) {
            try {
                Method getComponent = apduServiceInfo.getClass().getMethod("getComponent");
                return (ComponentName) getComponent.invoke(apduServiceInfo);
            } catch (Throwable t) {
                XposedBridge.log("LmrtHijackModule: getComponent() failed — " + t);
                return null;
            }
        }

        // ------------------------------------------------------------------
        // Helper: build a new AidResolveInfo routing to our service
        // ------------------------------------------------------------------

        /**
         * Creates a new AidResolveInfo (inner class of RegisteredAidCache)
         * with our service as the sole destination.
         *
         * Android 15 AidResolveInfo field layout
         * (RegisteredAidCache$AidResolveInfo):
         *
         *   services                     → List containing only ourServiceInfo
         *   defaultService               → ourServiceInfo
         *   category                     → CardEmulation.CATEGORY_OTHER
         *   prefixInfo                   → null  (left at constructor default)
         *   unCheckedOffHostSecureElement→ []    (left at constructor default)
         *
         * As a non-static inner class, the constructor's first argument at the
         * reflection level is the enclosing RegisteredAidCache instance.
         *
         * @param outerInstance  RegisteredAidCache instance (for the inner-class ctor)
         * @param ourServiceInfo ApduServiceInfo of PassthroughHceService (as Object)
         * @return               populated AidResolveInfo as Object, or null on failure
         */
        @SuppressWarnings({"rawtypes", "unchecked"})
        private Object buildAidResolveInfo(Object outerInstance, Object ourServiceInfo)
                throws Throwable {

            // Load the inner class via the NFC package's class loader.
            ClassLoader cl = outerInstance.getClass().getClassLoader();
            Class<?> innerClass = cl.loadClass(
                    "com.android.nfc.cardemulation.RegisteredAidCache$AidResolveInfo");

            // Non-static inner class: the implicit first constructor arg is the
            // enclosing RegisteredAidCache instance.
            Constructor<?> ctor = innerClass.getDeclaredConstructor(outerInstance.getClass());
            ctor.setAccessible(true);
            Object resolveInfo = ctor.newInstance(outerInstance);

            // services: a single-element list containing our service.
            List servicesList = new ArrayList();
            servicesList.add(ourServiceInfo);
            Field servicesField = XposedHelpers.findField(innerClass, "services");
            servicesField.set(resolveInfo, servicesList);

            // defaultService: our service (makes us the unambiguous winner).
            Field defaultServiceField = XposedHelpers.findField(innerClass, "defaultService");
            defaultServiceField.set(resolveInfo, ourServiceInfo);

            // category: CATEGORY_OTHER avoids payment-role eligibility checks.
            // (CATEGORY_PAYMENT triggers additional wallet-holder validation in
            //  HostEmulationManager that our service would not pass.)
            Field categoryField = XposedHelpers.findField(innerClass, "category");
            categoryField.set(resolveInfo, CardEmulation.CATEGORY_OTHER);

            // prefixInfo and unCheckedOffHostSecureElement are left at the
            // values set by the inner-class constructor (null and [] respectively).

            return resolveInfo;
        }
    }
}
