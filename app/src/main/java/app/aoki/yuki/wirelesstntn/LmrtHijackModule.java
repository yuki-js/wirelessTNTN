package app.aoki.yuki.wirelesstntn;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * LSPosed module that hijacks NFC routing at two levels so that PassthroughHceService
 * receives EVERY AID SELECT, including completely unknown AIDs like "X1145141919".
 *
 * ──────────────────────────────────────────────────────────────────────────────────
 *  WHY THE PREVIOUS APPROACH (apduservice.xml + Observer Mode) FAILED
 * ──────────────────────────────────────────────────────────────────────────────────
 *
 * The previous implementation listed specific AID prefixes in apduservice.xml.
 * Those prefixes were loaded into the NFC controller's hardware routing table (LMRT).
 * Any AID that did NOT appear in the table was NOT routed to the Device Host (DH) —
 * it silently went to the Secure Element's default route.  Observer Mode was used to
 * work around this, but that only senses non-ISO-DEP frames (Apple ECP, Type-F …),
 * not unknown payment AIDs.  An unknown AID like "X1145141919" would simply never
 * arrive at PassthroughHceService.
 *
 * ──────────────────────────────────────────────────────────────────────────────────
 *  THE LMRT DEFAULT ROUTE — THE CORRECT SOLUTION
 * ──────────────────────────────────────────────────────────────────────────────────
 *
 * The NCI (NFC Controller Interface) specification defines a fallback mechanism:
 *
 *   • In NCI 2.0+ the LMRT accepts an EMPTY AID entry ("") with the PREFIX qualifier.
 *     This entry acts as the catch-all: any AID that does NOT match an explicit entry
 *     is routed to the destination specified in the empty-AID entry.
 *
 *   • If we set this catch-all destination to ROUTE_HOST (0x00 = Device Host) and
 *     REMOVE all other explicit AID entries, then EVERY AID SELECT the NFC controller
 *     receives — no matter how exotic — is forwarded to the Android Host stack.
 *
 * This module enforces that configuration by hooking the method that commits the
 * routing table to the NFC controller firmware.
 *
 * ──────────────────────────────────────────────────────────────────────────────────
 *  HOW THIS MODULE WORKS  (three Xposed hooks inside com.android.nfc)
 * ──────────────────────────────────────────────────────────────────────────────────
 *
 *  HOOK 1 — RegisteredServicesCache.getInstalledServices(int userId)
 *    Purpose : registers PassthroughHceService with the NFC stack WITHOUT needing
 *              an apduservice.xml meta-data file.
 *    Why     : Without a valid apduservice.xml the NFC framework throws
 *              XmlPullParserException and silently skips the service, so it never
 *              appears in RegisteredAidCache.mUserApduServiceInfo.  This hook
 *              injects a synthetic ApduServiceInfo for our service using the
 *              non-XML 13-parameter constructor.
 *
 *  HOOK 2 — AidRoutingManager.commit(HashMap routeCache, boolean isOverrideOrRecover)
 *    Purpose : overwrites the LMRT just before it is committed to the NFC firmware.
 *    Why     : Without this hook, the LMRT would be empty (no explicit AID entries,
 *              since we have no apduservice.xml), but the NCI default route would
 *              still point to the Secure Element (device OEM default).  This hook
 *              replaces routeCache with a single entry:
 *                "" (empty AID) → ROUTE_HOST, AID_ROUTE_QUAL_PREFIX, POWER_STATE_ALL
 *              This single entry is the NCI 2.0 catch-all that routes every AID to DH.
 *
 *  HOOK 3 — RegisteredAidCache.resolveAid(String aid)
 *    Purpose : decides WHICH HCE service receives each AID that arrives at DH.
 *    Why     : After Hook 2, all AID SELECTs reach the Device Host.  Android's
 *              HostEmulationManager calls RegisteredAidCache.resolveAid() to pick
 *              the Java HCE service.  Without our apduservice.xml the mAidCache map
 *              is empty, so resolveAid() returns EMPTY_RESOLVE_INFO and the NFC
 *              stack sends AID_NOT_FOUND (6A 82) to the reader.  This hook intercepts
 *              that call and returns an AidResolveInfo pointing to our service,
 *              regardless of which AID was selected.
 *
 * ──────────────────────────────────────────────────────────────────────────────────
 *  ANDROID 15 SPECIFICS
 * ──────────────────────────────────────────────────────────────────────────────────
 *
 *  • RegisteredServicesCache   — getInstalledServices() is package-private; hookable
 *    via getDeclaredMethod().  mContext field gives us the system Context.
 *
 *  • AidRoutingManager.commit() — private; hookable.  AidEntry is a non-static inner
 *    class whose implicit constructor parameter is the AidRoutingManager instance
 *    (param.thisObject in the Xposed hook).
 *
 *  • RegisteredAidCache  —  mUserApduServiceInfo = Map<Integer userId, List<ApduServiceInfo>>
 *    contains all registered HCE services (injected by Hook 1 even without XML).
 *    This is where Hook 3 finds our service instead of scanning mAidCache.
 *
 *  • ApduServiceInfo is @hide / @FlaggedApi and absent from the API-35 stub SDK.
 *    All references are via Object + reflection.  The 13-param constructor signature
 *    (type-erased) is:
 *      (ResolveInfo, boolean, String, List, List, boolean, boolean, int, int,
 *       String, String, String, boolean)
 *
 * ──────────────────────────────────────────────────────────────────────────────────
 *  REQUIREMENTS
 * ──────────────────────────────────────────────────────────────────────────────────
 *
 *   • Root access + LSPosed (Zygisk) installed
 *   • This app enabled as an LSPosed module with scope → com.android.nfc
 *   • PassthroughHceService declared in AndroidManifest with HOST_APDU_SERVICE
 *     intent filter and BIND_NFC_SERVICE permission requirement
 *
 * @see ref_aosp/NfcNci/src/com/android/nfc/cardemulation/RegisteredServicesCache.java
 * @see ref_aosp/NfcNci/src/com/android/nfc/cardemulation/AidRoutingManager.java
 * @see ref_aosp/NfcNci/src/com/android/nfc/cardemulation/RegisteredAidCache.java
 * @see ref_aosp/NfcNci/src/com/android/nfc/cardemulation/HostEmulationManager.java
 * @see ref_aosp/framework/src/android/nfc/cardemulation/ApduServiceInfo.java
 */
public class LmrtHijackModule implements IXposedHookLoadPackage {

    /** Package name of the NFC system service process we hook into. */
    private static final String NFC_PACKAGE = "com.android.nfc";

    /** Our application package and the full class name of our HCE service. */
    private static final String OUR_PACKAGE = "app.aoki.yuki.wirelesstntn";
    private static final String OUR_SERVICE  = OUR_PACKAGE + ".PassthroughHceService";

    // ──────────────────────────────────────────────────────────────────────────────
    //  NCI / routing constants  (from AidRoutingManager and RegisteredAidCache)
    // ──────────────────────────────────────────────────────────────────────────────

    /**
     * Destination: Device Host (the Android CPU running HCE services).
     * NCI spec §7: route destination 0x00 = DH.
     * Source: AidRoutingManager.java  static final int ROUTE_HOST = 0x00
     */
    private static final int ROUTE_HOST = 0x00;

    /**
     * AID qualifier flag: PREFIX routing.
     * When an AID entry has this flag its value is treated as a prefix — any AID
     * that starts with this prefix (including an empty prefix = every AID) matches.
     * Source: RegisteredAidCache.java  static final int AID_ROUTE_QUAL_PREFIX = 0x10
     */
    private static final int AID_ROUTE_QUAL_PREFIX = 0x10;

    /**
     * All power states combined — the routing entry is active across every power mode
     * (screen on, screen off, locked, unlocked, battery-off, switch-off …).
     * Source: RegisteredAidCache.java  POWER_STATE_ALL = 0x3F (bits 0-5 set)
     */
    private static final int POWER_STATE_ALL = 0x3F;

    /**
     * NCI version 2.0 identifier.
     * The empty-AID catch-all entry is only valid for NCI 2.0+.
     * Source: NfcService.java  public static final int NCI_VERSION_2_0 = 0x20
     */
    private static final int NCI_VERSION_2_0 = 0x20;

    // ──────────────────────────────────────────────────────────────────────────────
    //  Entry point
    // ──────────────────────────────────────────────────────────────────────────────

    /**
     * Called by LSPosed for every process that loads a package.
     * We install all three hooks only when the NFC process is loaded.
     */
    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!NFC_PACKAGE.equals(lpparam.packageName)) {
            return;
        }
        XposedBridge.log("LmrtHijackModule: NFC process loaded — installing hooks");

        installGetInstalledServicesHook(lpparam.classLoader);
        installCommitHook(lpparam.classLoader);
        installResolveAidHook(lpparam.classLoader);
    }

    // ══════════════════════════════════════════════════════════════════════════════
    //  HOOK 1 — RegisteredServicesCache.getInstalledServices(int userId)
    // ══════════════════════════════════════════════════════════════════════════════

    /**
     * Hook RegisteredServicesCache.getInstalledServices() to inject our service even
     * though apduservice.xml has been deleted.
     *
     * Without a valid apduservice.xml meta-data resource the real implementation
     * catches XmlPullParserException and skips our service entirely, so it never
     * appears in mUserApduServiceInfo.  We add it back synthetically using the
     * non-XML ApduServiceInfo constructor.
     *
     * This hook runs AFTER the original method so we can append to whatever list
     * the original built.
     */
    private void installGetInstalledServicesHook(ClassLoader classLoader) {
        try {
            XposedHelpers.findAndHookMethod(
                    "com.android.nfc.cardemulation.RegisteredServicesCache",
                    classLoader,
                    "getInstalledServices",
                    int.class,   // userId
                    new InjectOurServiceHook()
            );
            XposedBridge.log("LmrtHijackModule: getInstalledServices hook installed");
        } catch (Throwable t) {
            XposedBridge.log("LmrtHijackModule: getInstalledServices hook failed — " + t);
            XposedBridge.log(t);
        }
    }

    private static final class InjectOurServiceHook extends XC_MethodHook {

        @Override
        @SuppressWarnings({"rawtypes", "unchecked"})
        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
            int userId = (int) param.args[0];
            Object registeredServicesCache = param.thisObject;

            try {
                // ── Get the Context stored inside RegisteredServicesCache ──────────
                // RegisteredServicesCache has field: final Context mContext;
                Field mContextField = XposedHelpers.findField(
                        registeredServicesCache.getClass(), "mContext");
                Context context = (Context) mContextField.get(registeredServicesCache);
                if (context == null) {
                    XposedBridge.log("LmrtHijackModule: mContext is null, cannot inject service");
                    return;
                }

                // ── Get our app's UID from PackageManager ─────────────────────────
                // This is needed for ApduServiceInfo.getUid() which HostEmulationManager
                // uses to derive the UserHandle for cross-user service binding.
                int ourUid;
                try {
                    ApplicationInfo appInfo =
                            context.getPackageManager().getApplicationInfo(OUR_PACKAGE, 0);
                    ourUid = appInfo.uid;
                } catch (PackageManager.NameNotFoundException e) {
                    XposedBridge.log("LmrtHijackModule: our package not found (" + OUR_PACKAGE
                            + "), cannot inject service");
                    return;
                }

                // ── Check if the returned list already contains our service ────────
                // This guards against double-injection on repeated invalidateCache() calls.
                List returnedList = (List) param.getResult();
                if (returnedList != null) {
                    ComponentName ourComponent =
                            ComponentName.unflattenFromString(OUR_PACKAGE + "/" + OUR_SERVICE);
                    for (Object svcInfo : returnedList) {
                        ComponentName c = getComponent(svcInfo);
                        if (c != null && ourComponent.equals(c)) {
                            // Already present (e.g. the user added a minimal apduservice.xml).
                            return;
                        }
                    }
                }

                // ── Build a minimal ResolveInfo describing our service ────────────
                // We cannot call pm.resolveService() here because the service has no
                // meta-data XML, which causes RegisteredServicesCache to skip it.
                // Instead we construct the ResolveInfo directly from the fields that
                // ApduServiceInfo.getComponent() reads:
                //   mService.serviceInfo.packageName  and  mService.serviceInfo.name
                ServiceInfo si = new ServiceInfo();
                si.packageName = OUR_PACKAGE;
                si.name        = OUR_SERVICE;
                si.applicationInfo              = new ApplicationInfo();
                si.applicationInfo.packageName  = OUR_PACKAGE;
                si.applicationInfo.uid          = ourUid;

                ResolveInfo ri = new ResolveInfo();
                ri.serviceInfo = si;

                // ── Construct a synthetic ApduServiceInfo via reflection ───────────
                // ApduServiceInfo is @hide / @FlaggedApi; we use its 13-param constructor
                // (type-erased for reflection) which does NOT read XML meta-data.
                //
                // Constructor signature (android-15, ApduServiceInfo.java line 177):
                //   ApduServiceInfo(ResolveInfo, boolean onHost, String description,
                //     List<AidGroup> staticAidGroups, List<AidGroup> dynamicAidGroups,
                //     boolean requiresUnlock, boolean requiresScreenOn,
                //     int bannerResource, int uid,
                //     String settingsActivityName, String offHost, String staticOffHost,
                //     boolean isEnabled)
                //
                // With type erasure:
                //   (ResolveInfo, boolean, String, List, List, boolean, boolean,
                //    int, int, String, String, String, boolean)
                ClassLoader cl = registeredServicesCache.getClass().getClassLoader();
                Class<?> apduClass =
                        cl.loadClass("android.nfc.cardemulation.ApduServiceInfo");
                Constructor<?> ctor = apduClass.getDeclaredConstructor(
                        ResolveInfo.class,   // info
                        boolean.class,       // onHost
                        String.class,        // description
                        List.class,          // staticAidGroups (List<AidGroup>, erased)
                        List.class,          // dynamicAidGroups
                        boolean.class,       // requiresUnlock
                        boolean.class,       // requiresScreenOn
                        int.class,           // bannerResource
                        int.class,           // uid
                        String.class,        // settingsActivityName
                        String.class,        // offHost
                        String.class,        // staticOffHost
                        boolean.class        // isEnabled
                );
                ctor.setAccessible(true);

                Object apduServiceInfo = ctor.newInstance(
                        ri,                  // info       — our service's ResolveInfo
                        true,                // onHost     — true: this is an HCE service
                        "NFC Passthrough",   // description
                        new ArrayList<>(),   // staticAidGroups  — intentionally empty;
                        new ArrayList<>(),   //   routing is handled by the commit() hook
                        true,                // requiresUnlock    — match apduservice.xml
                        false,               // requiresScreenOn  — allow screen-off use
                        0,                   // bannerResource    — no banner
                        ourUid,              // uid
                        null,                // settingsActivityName
                        null,                // offHost
                        null,                // staticOffHost
                        true                 // isEnabled
                );

                // ── Append to the returned list ───────────────────────────────────
                List modifiedList = returnedList != null
                        ? new ArrayList(returnedList)
                        : new ArrayList();
                modifiedList.add(apduServiceInfo);
                param.setResult(modifiedList);

                XposedBridge.log("LmrtHijackModule: injected " + OUR_SERVICE
                        + " into getInstalledServices() for userId=" + userId);

            } catch (Throwable t) {
                XposedBridge.log("LmrtHijackModule: error in getInstalledServices hook — " + t);
                XposedBridge.log(t);
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════════════
    //  HOOK 2 — AidRoutingManager.commit(HashMap routeCache, boolean isOverrideOrRecover)
    // ══════════════════════════════════════════════════════════════════════════════

    /**
     * Hook AidRoutingManager.commit() to replace the routing table with a single
     * catch-all entry: empty AID "" → Device Host with all power states.
     *
     * AidRoutingManager.commit() is a private method. It is called by configureRouting()
     * after building the routeCache from the registered AID registrations. We intercept
     * it BEFORE it runs (beforeHookedMethod) and replace param.args[0] (the routeCache)
     * with our own minimal HashMap.
     *
     * When commit() then runs with our HashMap it calls:
     *   NfcService.routeAids("", ROUTE_HOST, AID_ROUTE_QUAL_PREFIX, POWER_STATE_ALL)
     *   NfcService.commitRouting()          ← no-arg in Android 15
     * which tells the NFC controller: "for every AID (empty prefix matches all), send
     * to the Device Host."
     *
     * ANDROID 15 SPECIFIC: commit() has exactly ONE parameter (HashMap).
     * Source: AidRoutingManager.java line 487 (android15-release branch):
     *   private void commit(HashMap<String, AidEntry> routeCache)
     *
     * AidEntry is a non-static inner class of AidRoutingManager. Its implicit outer-class
     * constructor parameter is the AidRoutingManager instance (param.thisObject).
     */
    private void installCommitHook(ClassLoader classLoader) {
        try {
            XposedHelpers.findAndHookMethod(
                    "com.android.nfc.cardemulation.AidRoutingManager",
                    classLoader,
                    "commit",
                    HashMap.class,   // HashMap<String, AidEntry> — type-erased; ONLY parameter in Android 15
                    new OverrideLmrtHook()
            );
            XposedBridge.log("LmrtHijackModule: commit() hook installed");
        } catch (Throwable t) {
            XposedBridge.log("LmrtHijackModule: commit() hook failed — " + t);
            XposedBridge.log(t);
        }
    }

    private static final class OverrideLmrtHook extends XC_MethodHook {

        @Override
        @SuppressWarnings({"rawtypes", "unchecked"})
        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
            Object aidRoutingManager = param.thisObject;  // AidRoutingManager instance

            try {
                // ── Check NCI version ─────────────────────────────────────────────
                // The empty-AID catch-all mechanism (empty AID "" with PREFIX qualifier)
                // is an NCI 2.0 feature (NFC Controller Interface spec §7.4).
                // NCI 1.0 does not define this semantic — passing a null-byte-array AID
                // to the NCI hardware (which is how "" maps) has undefined behavior and
                // may crash the NFC controller driver.
                //
                // AOSP Android 15 only adds the empty-AID entry when NCI >= 2.0
                // AND (mDefaultRoute != mDefaultIsoDepRoute OR mDefaultIsoDepRoute == ROUTE_HOST)
                // (AidRoutingManager.configureRouting() lines 408-436, android15-release branch)
                //
                // We enforce the same NCI guard: if NCI < 2.0, skip the substitution and
                // let the original commit() proceed unmodified.
                try {
                    Class<?> nfcServiceClass =
                            aidRoutingManager.getClass().getClassLoader()
                                    .loadClass("com.android.nfc.NfcService");
                    Method getInstance = nfcServiceClass.getMethod("getInstance");
                    Object nfcService  = getInstance.invoke(null);
                    Method getNciVersion = nfcServiceClass.getMethod("getNciVersion");
                    int nciVersion = (int) getNciVersion.invoke(nfcService);
                    if (nciVersion < NCI_VERSION_2_0) {
                        XposedBridge.log("LmrtHijackModule: NCI " +
                                Integer.toHexString(nciVersion)
                                + " < 2.0 — empty-AID catch-all not supported;"
                                + " skipping commit() override");
                        return;  // abort: leave routing unchanged on NCI 1.0
                    }
                } catch (Throwable t) {
                    // If we cannot determine the NCI version, be conservative and proceed —
                    // Android 15 devices are overwhelmingly NCI 2.0+.
                    XposedBridge.log("LmrtHijackModule: could not determine NCI version ("
                            + t + "), proceeding with override");
                }

                // ── Load AidEntry inner class ─────────────────────────────────────
                // AidEntry is a non-static inner class: AidRoutingManager$AidEntry.
                // Its implicit constructor parameter is the outer AidRoutingManager.
                ClassLoader cl = aidRoutingManager.getClass().getClassLoader();
                Class<?> aidEntryClass = cl.loadClass(
                        "com.android.nfc.cardemulation.AidRoutingManager$AidEntry");
                Constructor<?> aidEntryCtor =
                        aidEntryClass.getDeclaredConstructor(aidRoutingManager.getClass());
                aidEntryCtor.setAccessible(true);

                // ── Create the catch-all AidEntry ─────────────────────────────────
                // Fields (from AidRoutingManager.java):
                //   boolean isOnHost — true: this entry routes to the Device Host
                //   int route        — ROUTE_HOST = 0x00
                //   int aidInfo      — AID_ROUTE_QUAL_PREFIX = 0x10 (prefix qualifier)
                //                      Combined with empty AID "" this means "match ALL"
                //   int power        — POWER_STATE_ALL = 0x3F (active in every power mode)
                //
                // NCI AID routing priority (highest to lowest):
                //   1. AID-based entries  ← our "" PREFIX entry catches everything here
                //   2. Protocol-based entries (ISO-DEP protocol route)
                //   3. Technology-based entries (NFC-A, NFC-B technology route)
                // Our catch-all overrides whatever protocol/technology routes are set.
                Object aidEntry = aidEntryCtor.newInstance(aidRoutingManager);
                XposedHelpers.findField(aidEntryClass, "isOnHost").set(aidEntry, true);
                XposedHelpers.findField(aidEntryClass, "route").set(aidEntry, ROUTE_HOST);
                XposedHelpers.findField(aidEntryClass, "aidInfo")
                        .set(aidEntry, AID_ROUTE_QUAL_PREFIX);
                XposedHelpers.findField(aidEntryClass, "power").set(aidEntry, POWER_STATE_ALL);

                // ── Replace the routing table ─────────────────────────────────────
                // param.args[0] is the routeCache HashMap that commit() would have used
                // to call NfcService.routeAids() for each entry.
                // We replace it with our single catch-all entry.
                // The original method then runs with our modified argument.
                //
                // Note: NfcService.routeAids("", ...) calls hexStringToBytes("") → null,
                // then mDeviceHost.routeAid(null, route, aidInfo, power).  AOSP itself
                // follows this path for the empty AID (NCI 2.0 catch-all), so the native
                // HAL is expected to handle null-byte-array as "empty AID".
                HashMap newRouteCache = new HashMap();
                newRouteCache.put("", aidEntry);   // empty AID "" = catch-all prefix
                param.args[0] = newRouteCache;

                XposedBridge.log("LmrtHijackModule: LMRT overridden — "
                        + "empty AID catch-all → ROUTE_HOST");

            } catch (Throwable t) {
                XposedBridge.log("LmrtHijackModule: error in commit() hook — " + t);
                XposedBridge.log(t);
                // Do NOT set result — fall through to original commit() which will
                // use whatever routeCache it had, keeping NFC functional.
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════════════
    //  HOOK 3 — RegisteredAidCache.resolveAid(String aid)
    // ══════════════════════════════════════════════════════════════════════════════

    /**
     * Hook RegisteredAidCache.resolveAid() so that every AID that arrives at the
     * Device Host is routed to PassthroughHceService.
     *
     * After Hook 2, the NFC hardware sends every AID SELECT to DH.  Android's
     * HostEmulationManager then calls resolveAid() to find the Java HCE service.
     * Without our hook, resolveAid() returns EMPTY_RESOLVE_INFO (our service has no
     * AID entries in mAidCache), HostEmulationManager sends AID_NOT_FOUND (6A 82),
     * and the reader sees a card that speaks no protocols.
     *
     * Our hook intercepts resolveAid() BEFORE it runs and returns a synthetic
     * AidResolveInfo pointing to our service.  We find the service's ApduServiceInfo
     * in mUserApduServiceInfo (populated by Hook 1) rather than in mAidCache
     * (which is empty because we have no apduservice.xml).
     */
    private void installResolveAidHook(ClassLoader classLoader) {
        try {
            XposedHelpers.findAndHookMethod(
                    "com.android.nfc.cardemulation.RegisteredAidCache",
                    classLoader,
                    "resolveAid",
                    String.class,   // the AID hex string
                    new ResolveAidHook()
            );
            XposedBridge.log("LmrtHijackModule: resolveAid() hook installed");
        } catch (Throwable t) {
            XposedBridge.log("LmrtHijackModule: resolveAid() hook failed — " + t);
            XposedBridge.log(t);
        }
    }

    private static final class ResolveAidHook extends XC_MethodHook {

        @Override
        @SuppressWarnings({"rawtypes", "unchecked"})
        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
            String aid = (String) param.args[0];
            Object aidCache = param.thisObject;   // RegisteredAidCache instance

            try {
                // ── Find our service in mUserApduServiceInfo ──────────────────────
                // mUserApduServiceInfo = Map<Integer userId, List<ApduServiceInfo>>
                // This map contains ALL registered HCE services, not just ones with
                // AID entries in mAidCache.  Hook 1 injected our service here.
                Object ourServiceInfo = findOurServiceInfoInUserMap(aidCache);

                if (ourServiceInfo == null) {
                    // Service not yet injected (e.g. system still initialising).
                    // Fall through to original resolveAid() — NFC keeps working.
                    XposedBridge.log("LmrtHijackModule: our service not in mUserApduServiceInfo "
                            + "for AID " + aid + " — falling through");
                    return;
                }

                // ── Build a redirecting AidResolveInfo ────────────────────────────
                Object resolveInfo = buildAidResolveInfo(aidCache, ourServiceInfo);
                if (resolveInfo == null) {
                    XposedBridge.log("LmrtHijackModule: could not build AidResolveInfo "
                            + "for AID " + aid);
                    return;
                }

                param.setResult(resolveInfo);
                XposedBridge.log("LmrtHijackModule: AID " + aid + " → " + OUR_SERVICE);

            } catch (Throwable t) {
                XposedBridge.log("LmrtHijackModule: error in resolveAid() hook for AID "
                        + aid + " — " + t);
                XposedBridge.log(t);
                // Fall through: never crash the NFC process.
            }
        }

        // ── Helper: scan mUserApduServiceInfo for our service ─────────────────────

        /**
         * Scans {@code RegisteredAidCache.mUserApduServiceInfo} for the ApduServiceInfo
         * whose getComponent() matches our PassthroughHceService.
         *
         * mUserApduServiceInfo is a {@code Map<Integer userId, List<ApduServiceInfo>>}.
         * ApduServiceInfo is @hide; we treat it as Object and call getComponent() via
         * reflection.
         *
         * @param aidCache the RegisteredAidCache instance (param.thisObject)
         * @return the ApduServiceInfo for our service (as Object), or null if absent
         */
        @SuppressWarnings({"rawtypes", "unchecked"})
        private Object findOurServiceInfoInUserMap(Object aidCache) throws Throwable {
            ComponentName ourComponent =
                    ComponentName.unflattenFromString(OUR_PACKAGE + "/" + OUR_SERVICE);

            Field mapField = XposedHelpers.findField(
                    aidCache.getClass(), "mUserApduServiceInfo");
            Map<Integer, List> userApduServiceInfoMap = (Map) mapField.get(aidCache);

            if (userApduServiceInfoMap == null) return null;

            for (List serviceList : userApduServiceInfoMap.values()) {
                if (serviceList == null) continue;
                for (Object serviceInfo : serviceList) {
                    if (serviceInfo == null) continue;
                    ComponentName c = getComponent(serviceInfo);
                    if (c != null && ourComponent.equals(c)) {
                        return serviceInfo;
                    }
                }
            }
            return null;
        }

        // ── Helper: also scan mAidCache (for backward compat / future use) ────────

        /**
         * If Hook 1 somehow did not run (e.g. method was inlined or removed), fall back
         * to scanning mAidCache for our service.  This replicates the previous approach
         * and is a safety net only.
         *
         * @param aidCache the RegisteredAidCache instance
         * @return ApduServiceInfo for our service, or null
         */
        @SuppressWarnings({"rawtypes", "unchecked"})
        private Object findOurServiceInfoInAidCache(Object aidCache) throws Throwable {
            ComponentName ourComponent =
                    ComponentName.unflattenFromString(OUR_PACKAGE + "/" + OUR_SERVICE);

            Field mAidCacheField = XposedHelpers.findField(aidCache.getClass(), "mAidCache");
            Map mAidCacheMap = (Map) mAidCacheField.get(aidCache);
            if (mAidCacheMap == null) return null;

            for (Object resolveInfoObj : mAidCacheMap.values()) {
                if (resolveInfoObj == null) continue;
                try {
                    Field defaultServiceField = XposedHelpers.findField(
                            resolveInfoObj.getClass(), "defaultService");
                    Object defaultService = defaultServiceField.get(resolveInfoObj);
                    if (defaultService != null
                            && ourComponent.equals(getComponent(defaultService))) {
                        return defaultService;
                    }
                    Field servicesField = XposedHelpers.findField(
                            resolveInfoObj.getClass(), "services");
                    List services = (List) servicesField.get(resolveInfoObj);
                    if (services != null) {
                        for (Object svc : services) {
                            if (svc == null) continue;
                            ComponentName c = getComponent(svc);
                            if (c != null && ourComponent.equals(c)) {
                                return svc;
                            }
                        }
                    }
                } catch (Throwable ignored) {}
            }
            return null;
        }

        // ── Helper: get ComponentName from an ApduServiceInfo instance ────────────

        /**
         * Calls getComponent() on an ApduServiceInfo instance via reflection.
         * ApduServiceInfo is @hide and cannot be imported directly.
         */
        private ComponentName getComponent(Object apduServiceInfo) {
            try {
                Method m = apduServiceInfo.getClass().getMethod("getComponent");
                return (ComponentName) m.invoke(apduServiceInfo);
            } catch (Throwable t) {
                return null;
            }
        }

        // ── Helper: build AidResolveInfo pointing to our service ─────────────────

        /**
         * Creates a new AidResolveInfo (non-static inner class of RegisteredAidCache)
         * whose {@code defaultService} and {@code services} list point to our service.
         *
         * Android 15 AidResolveInfo fields (RegisteredAidCache$AidResolveInfo):
         *   List&lt;ApduServiceInfo&gt; services               — single entry: ourService
         *   ApduServiceInfo            defaultService         — ourService
         *   String                     category               — CATEGORY_OTHER
         *   ResolvedPrefixConflictAid  prefixInfo             — null (constructor default)
         *   List&lt;String&gt;          unCheckedOffHostSE     — [] (constructor default)
         *
         * Being non-static, its constructor takes the enclosing RegisteredAidCache as
         * its first (implicit) argument in reflection.
         *
         * @param outerInstance RegisteredAidCache instance
         * @param ourServiceInfo ApduServiceInfo of PassthroughHceService (as Object)
         */
        @SuppressWarnings({"rawtypes", "unchecked"})
        private Object buildAidResolveInfo(Object outerInstance, Object ourServiceInfo)
                throws Throwable {
            ClassLoader cl = outerInstance.getClass().getClassLoader();
            Class<?> innerClass = cl.loadClass(
                    "com.android.nfc.cardemulation.RegisteredAidCache$AidResolveInfo");

            // Non-static inner class: implicit first constructor arg is the outer instance.
            Constructor<?> ctor =
                    innerClass.getDeclaredConstructor(outerInstance.getClass());
            ctor.setAccessible(true);
            Object resolveInfo = ctor.newInstance(outerInstance);

            // services: single-element list containing our service.
            List servicesList = new ArrayList();
            servicesList.add(ourServiceInfo);
            XposedHelpers.findField(innerClass, "services").set(resolveInfo, servicesList);

            // defaultService: our service (unambiguous winner in HostEmulationManager).
            XposedHelpers.findField(innerClass, "defaultService")
                    .set(resolveInfo, ourServiceInfo);

            // category: CATEGORY_OTHER avoids payment wallet-holder validation in
            // HostEmulationManager (CATEGORY_PAYMENT triggers extra checks).
            XposedHelpers.findField(innerClass, "category")
                    .set(resolveInfo, CardEmulation.CATEGORY_OTHER);

            // prefixInfo and unCheckedOffHostSecureElement are left at constructor
            // defaults (null and empty list respectively).

            return resolveInfo;
        }
    }

    // ──────────────────────────────────────────────────────────────────────────────
    //  Shared utility
    // ──────────────────────────────────────────────────────────────────────────────

    /**
     * Calls getComponent() on an ApduServiceInfo (as Object) via reflection.
     * Used by InjectOurServiceHook to check for duplicate injection.
     */
    private static ComponentName getComponent(Object apduServiceInfo) {
        try {
            return (ComponentName) apduServiceInfo.getClass()
                    .getMethod("getComponent").invoke(apduServiceInfo);
        } catch (Throwable t) {
            return null;
        }
    }
}
