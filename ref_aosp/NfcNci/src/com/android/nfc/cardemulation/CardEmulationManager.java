/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.nfc.cardemulation;

import static android.content.pm.PackageManager.FEATURE_TELEPHONY_SUBSCRIPTION;
import static android.nfc.cardemulation.CardEmulation.SET_SERVICE_ENABLED_STATUS_FAILURE_FEATURE_UNSUPPORTED;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.role.RoleManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.nfc.ComponentNameAndUser;
import android.nfc.Constants;
import android.nfc.INfcCardEmulation;
import android.nfc.INfcEventCallback;
import android.nfc.INfcFCardEmulation;
import android.nfc.INfcOemExtensionCallback;
import android.nfc.NfcAdapter;
import android.nfc.NfcOemExtension;
import android.nfc.PackageAndUser;
import android.nfc.cardemulation.AidGroup;
import android.nfc.cardemulation.ApduServiceInfo;
import android.nfc.cardemulation.CardEmulation;
import android.nfc.cardemulation.NfcFServiceInfo;
import android.nfc.cardemulation.PollingFrame;
import android.os.Binder;
import android.os.Build;
import android.os.Looper;
import android.os.PowerManager;
import android.os.Process;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.sysprop.NfcProperties;
import android.telephony.SubscriptionInfo;
import android.text.TextUtils;
import android.util.Log;
import android.util.proto.ProtoOutputStream;

import com.android.internal.annotations.VisibleForTesting;
import com.android.nfc.DeviceConfigFacade;
import com.android.nfc.ExitFrame;
import com.android.nfc.ForegroundUtils;
import com.android.nfc.NfcEventLog;
import com.android.nfc.NfcInjector;
import com.android.nfc.NfcPermissions;
import com.android.nfc.NfcService;
import com.android.nfc.R;
import com.android.nfc.cardemulation.util.StatsdUtils;
import com.android.nfc.cardemulation.util.TelephonyUtils;
import com.android.nfc.flags.Flags;
import com.android.nfc.proto.NfcEventProto;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * CardEmulationManager is the central entity
 * responsible for delegating to individual components
 * implementing card emulation:
 * - RegisteredServicesCache keeping track of HCE and SE services on the device
 * - RegisteredNfcFServicesCache keeping track of HCE-F services on the device
 * - RegisteredAidCache keeping track of AIDs registered by those services and manages
 *   the routing table in the NFCC.
 * - RegisteredT3tIdentifiersCache keeping track of T3T Identifier registered by
 *   those services and manages the routing table in the NFCC.
 * - HostEmulationManager handles incoming APDUs for the host and forwards to HCE
 *   services as necessary.
 * - HostNfcFEmulationManager handles incoming NFC-F packets for the host and
 *   forwards to HCE-F services as necessary.
 */
public class CardEmulationManager implements RegisteredServicesCache.Callback,
        RegisteredNfcFServicesCache.Callback, PreferredServices.Callback,
        EnabledNfcFServices.Callback, WalletRoleObserver.Callback,
        PreferredSubscriptionService.Callback,
        HostEmulationManager.NfcAidRoutingListener {
    static final String TAG = "CardEmulationManager";
    static final boolean DBG = NfcProperties.debug_enabled().orElse(true);

    static final int NFC_HCE_APDU = 0x01;
    static final int NFC_HCE_NFCF = 0x04;
    /** Minimum AID length as per ISO7816 */
    static final int MINIMUM_AID_LENGTH = 5;
    /** Length of Select APDU header including length byte */
    static final int SELECT_APDU_HDR_LENGTH = 5;
    /** Length of the NDEF Tag application AID */
    static final int NDEF_AID_LENGTH = 7;
    /** AID of the NDEF Tag application Mapping Version 1.0 */
    static final byte[] NDEF_AID_V1 =
            new byte[] {(byte) 0xd2, 0x76, 0x00, 0x00, (byte) 0x85, 0x01, 0x00};
    /** AID of the NDEF Tag application Mapping Version 2.0 */
    static final byte[] NDEF_AID_V2 =
            new byte[] {(byte) 0xd2, 0x76, 0x00, 0x00, (byte) 0x85, 0x01, 0x01};
    /** Select APDU header */
    static final byte[] SELECT_AID_HDR = new byte[] {0x00, (byte) 0xa4, 0x04, 0x00};
    private static final int FIRMWARE_EXIT_FRAME_TIMEOUT_MS = 5000;

    final RegisteredAidCache mAidCache;
    final RegisteredT3tIdentifiersCache mT3tIdentifiersCache;
    final RegisteredServicesCache mServiceCache;
    final RegisteredNfcFServicesCache mNfcFServicesCache;
    final HostEmulationManager mHostEmulationManager;
    final HostNfcFEmulationManager mHostNfcFEmulationManager;
    final PreferredServices mPreferredServices;

    final WalletRoleObserver mWalletRoleObserver;
    final EnabledNfcFServices mEnabledNfcFServices;
    final Context mContext;
    final CardEmulationInterface mCardEmulationInterface;
    final NfcFCardEmulationInterface mNfcFCardEmulationInterface;
    final PowerManager mPowerManager;
    boolean mNotSkipAid;

    final ForegroundUtils mForegroundUtils;
    private int mForegroundUid;

    private final RoutingOptionManager mRoutingOptionManager;
    final byte[] mOffHostRouteUicc;
    final byte[] mOffHostRouteEse;
    private INfcOemExtensionCallback mNfcOemExtensionCallback;
    private final NfcEventLog mNfcEventLog;
    private final int mVendorApiLevel;
    private PreferredSubscriptionService mPreferredSubscriptionService = null;
    private TelephonyUtils mTelephonyUtils = null;
    @Nullable
    private final StatsdUtils mStatsdUtils;
    private final DeviceConfigFacade mDeviceConfigFacade;

    // TODO: Move this object instantiation and dependencies to NfcInjector.
    public CardEmulationManager(Context context, NfcInjector nfcInjector,
        DeviceConfigFacade deviceConfigFacade) {
        mContext = context;
        mCardEmulationInterface = new CardEmulationInterface();
        mNfcFCardEmulationInterface = new NfcFCardEmulationInterface();
        mForegroundUtils = ForegroundUtils.getInstance(
            context.getSystemService(ActivityManager.class));
        mWalletRoleObserver = new WalletRoleObserver(context,
                context.getSystemService(RoleManager.class), this, nfcInjector);

        mRoutingOptionManager = RoutingOptionManager.getInstance();
        mOffHostRouteEse = mRoutingOptionManager.getOffHostRouteEse();
        mOffHostRouteUicc = mRoutingOptionManager.getOffHostRouteUicc();
        mRoutingOptionManager.readRoutingOptionsFromPrefs(mContext, deviceConfigFacade);

        mTelephonyUtils = TelephonyUtils.getInstance(mContext);
        mTelephonyUtils.setMepMode(mRoutingOptionManager.getMepMode());

        mAidCache = new RegisteredAidCache(context, mWalletRoleObserver);
        mT3tIdentifiersCache = new RegisteredT3tIdentifiersCache(context);
        mHostEmulationManager =
                new HostEmulationManager(context, Looper.getMainLooper(), mAidCache, nfcInjector);
        mHostNfcFEmulationManager = new HostNfcFEmulationManager(context, mT3tIdentifiersCache);
        mServiceCache = new RegisteredServicesCache(context, this);
        mNfcFServicesCache = new RegisteredNfcFServicesCache(context, this);
        mPreferredServices = new PreferredServices(context, mServiceCache, mAidCache,
                mWalletRoleObserver, this);
        mEnabledNfcFServices = new EnabledNfcFServices(
                context, mNfcFServicesCache, mT3tIdentifiersCache, this);
        mPowerManager = context.getSystemService(PowerManager.class);
        mNfcEventLog = nfcInjector.getNfcEventLog();
        mVendorApiLevel = SystemProperties.getInt(
                "ro.vendor.api_level", Build.VERSION.DEVICE_INITIAL_SDK_INT);
        mPreferredSubscriptionService = new PreferredSubscriptionService(mContext, this);
        mStatsdUtils = nfcInjector.getStatsdUtils();
        mDeviceConfigFacade = deviceConfigFacade;
        initialize();
    }

    @VisibleForTesting
    CardEmulationManager(Context context,
            ForegroundUtils foregroundUtils,
            WalletRoleObserver walletRoleObserver,
            RegisteredAidCache registeredAidCache,
            RegisteredT3tIdentifiersCache registeredT3tIdentifiersCache,
            HostEmulationManager hostEmulationManager,
            HostNfcFEmulationManager hostNfcFEmulationManager,
            RegisteredServicesCache registeredServicesCache,
            RegisteredNfcFServicesCache registeredNfcFServicesCache,
            PreferredServices preferredServices,
            EnabledNfcFServices enabledNfcFServices,
            RoutingOptionManager routingOptionManager,
            PowerManager powerManager,
            NfcEventLog nfcEventLog,
            PreferredSubscriptionService preferredSubscriptionService,
            StatsdUtils statsdUtils,
            DeviceConfigFacade deviceConfigFacade) {
        mContext = context;
        mCardEmulationInterface = new CardEmulationInterface();
        mNfcFCardEmulationInterface = new NfcFCardEmulationInterface();
        mForegroundUtils = foregroundUtils;
        mWalletRoleObserver = walletRoleObserver;
        mAidCache = registeredAidCache;
        mT3tIdentifiersCache = registeredT3tIdentifiersCache;
        mHostEmulationManager = hostEmulationManager;
        mHostNfcFEmulationManager = hostNfcFEmulationManager;
        mServiceCache = registeredServicesCache;
        mNfcFServicesCache = registeredNfcFServicesCache;
        mPreferredServices = preferredServices;
        mEnabledNfcFServices = enabledNfcFServices;
        mPowerManager = powerManager;
        mRoutingOptionManager = routingOptionManager;
        mOffHostRouteEse = mRoutingOptionManager.getOffHostRouteEse();
        mOffHostRouteUicc = mRoutingOptionManager.getOffHostRouteUicc();
        mNfcEventLog = nfcEventLog;
        mVendorApiLevel = SystemProperties.getInt(
                "ro.vendor.api_level", Build.VERSION.DEVICE_INITIAL_SDK_INT);
        mPreferredSubscriptionService = preferredSubscriptionService;
        mStatsdUtils = statsdUtils;
        mDeviceConfigFacade = deviceConfigFacade;
        initialize();
    }

    public void setOemExtension(@Nullable INfcOemExtensionCallback nfcOemExtensionCallback) {
        mNfcOemExtensionCallback = nfcOemExtensionCallback;
        mHostEmulationManager.setOemExtension(mNfcOemExtensionCallback);
        mAidCache.setOemExtension(nfcOemExtensionCallback);
    }

    private void initialize() {
        if (mPreferredSubscriptionService != null)
            mPreferredSubscriptionService.initialize();
        mServiceCache.initialize();
        mNfcFServicesCache.initialize();
        mForegroundUid = Process.INVALID_UID;
        if (mWalletRoleObserver.isWalletRoleFeatureEnabled()) {
            int currentUser = ActivityManager.getCurrentUser();
            PackageAndUser roleHolder =
                    mWalletRoleObserver.getDefaultWalletRoleHolder(currentUser);
            onWalletRoleHolderChanged(roleHolder.getPackage(),
                    roleHolder.getUserId());
        }

        if (android.nfc.Flags.nfcEventListener()) {
            mHostEmulationManager.setAidRoutingListener(this);
        }
    }

    public INfcCardEmulation getNfcCardEmulationInterface() {
        return mCardEmulationInterface;
    }

    public INfcFCardEmulation getNfcFCardEmulationInterface() {
        return mNfcFCardEmulationInterface;
    }

    public void onPollingLoopDetected(List<PollingFrame> pollingFrames) {
        mHostEmulationManager.onPollingLoopDetected(pollingFrames);
    }

    public void onObserveModeStateChanged(boolean enable) {
        mHostEmulationManager.onObserveModeStateChange(enable);
    }

    public void onFieldChangeDetected(boolean fieldOn) {
        mHostEmulationManager.onFieldChangeDetected(fieldOn);
    }

    public void onHostCardEmulationActivated(int technology) {
        if (mNfcOemExtensionCallback != null) {
            try {
                mNfcOemExtensionCallback.onHceEventReceived(NfcOemExtension.HCE_ACTIVATE);
            } catch (RemoteException e) {
                Log.e(TAG, "onHostCardEmulationActivated: failed", e);
            }
        }
        if (mDeviceConfigFacade.getIndicateUserActivityForHce()
                && mPowerManager != null) {
            // Use USER_ACTIVITY_FLAG_INDIRECT to applying power hints without resets
            // the screen timeout
            mPowerManager.userActivity(SystemClock.uptimeMillis(),
                    PowerManager.USER_ACTIVITY_EVENT_TOUCH,
                    PowerManager.USER_ACTIVITY_FLAG_INDIRECT);
        }
        if (technology == NFC_HCE_APDU) {
            mHostEmulationManager.onHostEmulationActivated();
            mPreferredServices.onHostEmulationActivated();
            mNotSkipAid = false;
        } else if (technology == NFC_HCE_NFCF) {
            mHostNfcFEmulationManager.onHostEmulationActivated();
            mNfcFServicesCache.onHostEmulationActivated();
            mEnabledNfcFServices.onHostEmulationActivated();
        }
    }

    public void onHostCardEmulationData(int technology, byte[] data) {
        if (mNfcOemExtensionCallback != null) {
            try {
                mNfcOemExtensionCallback.onHceEventReceived(NfcOemExtension.HCE_DATA_TRANSFERRED);
            } catch (RemoteException e) {
                Log.e(TAG, "onHostCardEmulationData: failed", e);
            }
        }

        if (technology == NFC_HCE_APDU) {
            mHostEmulationManager.onHostEmulationData(data);
        } else if (technology == NFC_HCE_NFCF) {
            mHostNfcFEmulationManager.onHostEmulationData(data);
        }
        // Don't trigger userActivity if it's selecting NDEF AID
        if (mPowerManager != null && !(technology == NFC_HCE_APDU && isSkipAid(data))) {
            // Caution!! USER_ACTIVITY_EVENT_TOUCH resets the screen timeout
            mPowerManager.userActivity(SystemClock.uptimeMillis(),
                    PowerManager.USER_ACTIVITY_EVENT_TOUCH, 0);
        }
    }

    public void onHostCardEmulationDeactivated(int technology) {
        if (technology == NFC_HCE_APDU) {
            mHostEmulationManager.onHostEmulationDeactivated();
            mPreferredServices.onHostEmulationDeactivated();
        } else if (technology == NFC_HCE_NFCF) {
            mHostNfcFEmulationManager.onHostEmulationDeactivated();
            mNfcFServicesCache.onHostEmulationDeactivated();
            mEnabledNfcFServices.onHostEmulationDeactivated();
        }
        if (mNfcOemExtensionCallback != null) {
            try {
                mNfcOemExtensionCallback.onHceEventReceived(NfcOemExtension.HCE_DEACTIVATE);
            } catch (RemoteException e) {
                Log.e(TAG, "onHostCardEmulationDeactivated: failed", e);
            }
        }
    }

    public void onOffHostAidSelected() {
        mHostEmulationManager.onOffHostAidSelected();
    }

    public void onBootCompleted() {
        mHostEmulationManager.onBootCompleted();
    }

    public void onUserSwitched(int userId) {
        mWalletRoleObserver.onUserSwitched(userId);
        // for HCE
        mServiceCache.onUserSwitched();
        mPreferredServices.onUserSwitched(userId);
        // for HCE-F
        mHostNfcFEmulationManager.onUserSwitched();
        mT3tIdentifiersCache.onUserSwitched();
        mEnabledNfcFServices.onUserSwitched(userId);
        mNfcFServicesCache.onUserSwitched();
    }

    public void migrateSettingsFilesFromCe(Context ceContext) {
        mServiceCache.migrateSettingsFilesFromCe(ceContext);
    }

    public void onManagedProfileChanged() {
        // for HCE
        mServiceCache.onManagedProfileChanged();
        // for HCE-F
        mNfcFServicesCache.onManagedProfileChanged();
    }

    public void onNfcEnabled() {
        // for HCE
        mAidCache.onNfcEnabled();
        // for HCE-F
        mT3tIdentifiersCache.onNfcEnabled();
    }

    public void onNfcDisabled() {
        // for HCE
        mAidCache.onNfcDisabled();
        // for HCE-F
        mHostNfcFEmulationManager.onNfcDisabled();
        mNfcFServicesCache.onNfcDisabled();
        mT3tIdentifiersCache.onNfcDisabled();
        mEnabledNfcFServices.onNfcDisabled();
    }

    public void onTriggerRoutingTableUpdate() {
        if (DBG) Log.d(TAG, "onTriggerRoutingTableUpdate");
        mAidCache.onTriggerRoutingTableUpdate();
        mT3tIdentifiersCache.onTriggerRoutingTableUpdate();
    }

    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        mServiceCache.dump(fd, pw, args);
        mNfcFServicesCache.dump(fd, pw ,args);
        mPreferredServices.dump(fd, pw, args);
        mEnabledNfcFServices.dump(fd, pw, args);
        mAidCache.dump(fd, pw, args);
        mT3tIdentifiersCache.dump(fd, pw, args);
        mHostEmulationManager.dump(fd, pw, args);
        mHostNfcFEmulationManager.dump(fd, pw, args);
    }

    /**
     * Dump debugging information as a CardEmulationManagerProto
     *
     * Note:
     * See proto definition in frameworks/base/core/proto/android/nfc/card_emulation.proto
     * When writing a nested message, must call {@link ProtoOutputStream#start(long)} before and
     * {@link ProtoOutputStream#end(long)} after.
     * Never reuse a proto field number. When removing a field, mark it as reserved.
     */
    public void dumpDebug(ProtoOutputStream proto) {
        long token = proto.start(CardEmulationManagerProto.REGISTERED_SERVICES_CACHE);
        mServiceCache.dumpDebug(proto);
        proto.end(token);

        token = proto.start(CardEmulationManagerProto.REGISTERED_NFC_F_SERVICES_CACHE);
        mNfcFServicesCache.dumpDebug(proto);
        proto.end(token);

        token = proto.start(CardEmulationManagerProto.PREFERRED_SERVICES);
        mPreferredServices.dumpDebug(proto);
        proto.end(token);

        token = proto.start(CardEmulationManagerProto.ENABLED_NFC_F_SERVICES);
        mEnabledNfcFServices.dumpDebug(proto);
        proto.end(token);

        token = proto.start(CardEmulationManagerProto.AID_CACHE);
        mAidCache.dumpDebug(proto);
        proto.end(token);

        token = proto.start(CardEmulationManagerProto.T3T_IDENTIFIERS_CACHE);
        mT3tIdentifiersCache.dumpDebug(proto);
        proto.end(token);

        token = proto.start(CardEmulationManagerProto.HOST_EMULATION_MANAGER);
        mHostEmulationManager.dumpDebug(proto);
        proto.end(token);

        token = proto.start(CardEmulationManagerProto.HOST_NFC_F_EMULATION_MANAGER);
        mHostNfcFEmulationManager.dumpDebug(proto);
        proto.end(token);
    }

    @Override
    public void onServicesUpdated(int userId, List<ApduServiceInfo> services,
            boolean validateInstalled) {
        if (!mWalletRoleObserver.isWalletRoleFeatureEnabled()) {
            // Verify defaults are still the same
            verifyDefaults(userId, services, validateInstalled);
        }
        // Update the AID cache
        mAidCache.onServicesUpdated(userId, services);
        // Update the preferred services list
        mPreferredServices.onServicesUpdated();
        mHostEmulationManager.updatePollingLoopFilters(userId, services);
        if (Flags.exitFrames()) {
            updateFirmwareExitFramesForWalletRole(userId);
        }
        NfcService.getInstance().onPreferredPaymentChanged(NfcAdapter.PREFERRED_PAYMENT_UPDATED);
    }

    @Override
    public void onNfcFServicesUpdated(int userId, List<NfcFServiceInfo> services) {
        // Update the T3T identifier cache
        mT3tIdentifiersCache.onServicesUpdated(userId, services);
        // Update the enabled services list
        mEnabledNfcFServices.onServicesUpdated();
    }

    public void onEeListenActivated(boolean isActivated) {
        if (!isActivated) {
            Log.d(TAG, "onEeListenActivated: Listen Mode is deactivated, "
                    + "try to recovery routing table");
            mPreferredServices.onHostEmulationDeactivated();
        }
    }

    void verifyDefaults(int userId, List<ApduServiceInfo> services, boolean validateInstalled) {
        UserManager um = mContext.createContextAsUser(
                UserHandle.of(userId), /*flags=*/0).getSystemService(UserManager.class);
        List<UserHandle> luh = um.getEnabledProfiles();

        ComponentName defaultPaymentService = null;
        int numDefaultPaymentServices = 0;
        int userIdDefaultPaymentService = userId;

        for (UserHandle uh : luh) {
            ComponentName paymentService = getDefaultServiceForCategory(uh.getIdentifier(),
                    CardEmulation.CATEGORY_PAYMENT,
                    validateInstalled && (uh.getIdentifier() == userId));
            if (DBG) Log.d(TAG, "verifyDefaults: default: " + paymentService + " for user:" + uh);
            if (paymentService != null) {
                numDefaultPaymentServices++;
                defaultPaymentService = paymentService;
                userIdDefaultPaymentService = uh.getIdentifier();
            }
        }
        if (numDefaultPaymentServices > 1) {
            Log.e(TAG, "verifyDefaults: Current default is not aligned across multiple users");
            // leave default unset
            for (UserHandle uh : luh) {
                setDefaultServiceForCategoryChecked(uh.getIdentifier(), null,
                        CardEmulation.CATEGORY_PAYMENT);
            }
        } else {
            if (DBG)  {
                Log.d(TAG, "verifyDefaults: Current default: " + defaultPaymentService
                        + " for user:" + userIdDefaultPaymentService);
            }
        }
        if (defaultPaymentService == null) {
            // A payment service may have been removed, leaving only one;
            // in that case, automatically set that app as default.
            int numPaymentServices = 0;
            ComponentName lastFoundPaymentService = null;
            PackageManager pm;
            try {
                pm = mContext.createPackageContextAsUser("android", /*flags=*/0,
                    UserHandle.of(userId)).getPackageManager();
            } catch (NameNotFoundException e) {
                Log.e(TAG, "verifyDefaults: Could not create user package context");
                return;
            }

            for (ApduServiceInfo service : services) {
                if (service.hasCategory(CardEmulation.CATEGORY_PAYMENT)
                            && wasServicePreInstalled(pm, service.getComponent())) {
                    numPaymentServices++;
                    lastFoundPaymentService = service.getComponent();
                }
            }
            if (numPaymentServices > 1) {
                // More than one service left, leave default unset
                if (DBG) Log.d(TAG, "verifyDefaults: No default set, more than one service left.");
                setDefaultServiceForCategoryChecked(userId, null, CardEmulation.CATEGORY_PAYMENT);
            } else if (numPaymentServices == 1) {
                // Make single found payment service the default
                if (DBG) {
                    Log.d(TAG,
                            "verifyDefaults: No default set, " + "making single service default.");
                }
                setDefaultServiceForCategoryChecked(userId, lastFoundPaymentService,
                        CardEmulation.CATEGORY_PAYMENT);
            } else {
                // No payment services left, leave default at null
                if (DBG) {
                    Log.d(TAG,
                            "verifyDefaults: No default set, " + "last payment service removed.");
                }
                setDefaultServiceForCategoryChecked(userId, null, CardEmulation.CATEGORY_PAYMENT);
            }
        }
    }

    boolean wasServicePreInstalled(PackageManager packageManager, ComponentName service) {
        try {
            ApplicationInfo ai = packageManager
                    .getApplicationInfo(service.getPackageName(), /*flags=*/0);
            if ((ApplicationInfo.FLAG_SYSTEM & ai.flags) != 0) {
                if (DBG) {
                    Log.d(TAG,
                            "wasServicePreInstalled: Service was " + "pre-installed on the device");
                }
                return true;
            }
        } catch (NameNotFoundException e) {
            Log.e(TAG, "wasServicePreInstalled: Service is not currently "
                    + "installed on the device.");
            return false;
        }
        if (DBG) {
            Log.d(TAG, "wasServicePreInstalled: Service was not pre-installed on the device");
        }
        return false;
    }

    ComponentName getDefaultServiceForCategory(int userId, String category,
             boolean validateInstalled) {
        if (!CardEmulation.CATEGORY_PAYMENT.equals(category)) {
            Log.e(TAG,
                    "getDefaultServiceForCategory: Not allowing defaults for category " + category);
            return null;
        }
        // Load current payment default from settings
        String name = Settings.Secure.getString(
                mContext.createContextAsUser(UserHandle.of(userId), 0).getContentResolver(),
                Constants.SETTINGS_SECURE_NFC_PAYMENT_DEFAULT_COMPONENT);
        if (name != null) {
            ComponentName service = ComponentName.unflattenFromString(name);
            if (!validateInstalled || service == null) {
                return service;
            } else {
                return mServiceCache.hasService(userId, service) ? service : null;
            }
        } else {
            return null;
        }
    }

    boolean setDefaultServiceForCategoryChecked(int userId, ComponentName service,
            String category) {
        if (!CardEmulation.CATEGORY_PAYMENT.equals(category)) {
            Log.e(TAG, "setDefaultServiceForCategoryChecked: Not allowing defaults for category "
                    + category);
            return false;
        }
        // TODO Not really nice to be writing to Settings.Secure here...
        // ideally we overlay our local changes over whatever is in
        // Settings.Secure
        if (service == null || mServiceCache.hasService(userId, service)) {
            Settings.Secure.putString(mContext
                    .createContextAsUser(UserHandle.of(userId), 0).getContentResolver(),
                    Constants.SETTINGS_SECURE_NFC_PAYMENT_DEFAULT_COMPONENT,
                    service != null ? service.flattenToString() : null);
        } else {
            Log.e(TAG,
                    "setDefaultServiceForCategoryChecked: Could not find default "
                            + "service to make default: "
                            + service);
        }
        return true;
    }

    boolean isServiceRegistered(int userId, ComponentName service) {
        boolean serviceFound = mServiceCache.hasService(userId, service);
        if (!serviceFound) {
            // If we don't know about this service yet, it may have just been enabled
            // using PackageManager.setComponentEnabledSetting(). The PackageManager
            // broadcasts are delayed by 10 seconds in that scenario, which causes
            // calls to our APIs referencing that service to fail.
            // Hence, update the cache in case we don't know about the service.
            if (DBG) {
                Log.d(TAG, "isServiceRegistered: Didn't find passed in service, "
                        + "invalidating cache");
            }
            mServiceCache.invalidateCache(userId, true);
        }
        return mServiceCache.hasService(userId, service);
    }

    boolean isNfcFServiceInstalled(int userId, ComponentName service) {
        boolean serviceFound = mNfcFServicesCache.hasService(userId, service);
        if (!serviceFound) {
            // If we don't know about this service yet, it may have just been enabled
            // using PackageManager.setComponentEnabledSetting(). The PackageManager
            // broadcasts are delayed by 10 seconds in that scenario, which causes
            // calls to our APIs referencing that service to fail.
            // Hence, update the cache in case we don't know about the service.
            if (DBG) {
                Log.d(TAG,
                        "isNfcFServiceInstalled: Didn't find passed in service, "
                                + "invalidating cache");
            }
            mNfcFServicesCache.invalidateCache(userId);
        }
        return mNfcFServicesCache.hasService(userId, service);
    }

    /**
     * Returns true if it's not selecting NDEF AIDs
     * It's used to skip userActivity if it only selects NDEF AIDs
     */
    boolean isSkipAid(byte[] data) {
        if (mNotSkipAid || data == null
                || data.length < SELECT_APDU_HDR_LENGTH + MINIMUM_AID_LENGTH
                || !Arrays.equals(SELECT_AID_HDR, 0, SELECT_AID_HDR.length,
                        data, 0, SELECT_AID_HDR.length)) {
            return false;
        }
        int aidLength = Byte.toUnsignedInt(data[SELECT_APDU_HDR_LENGTH - 1]);
        if (data.length >= SELECT_APDU_HDR_LENGTH + NDEF_AID_LENGTH
                && aidLength == NDEF_AID_LENGTH) {
            if (Arrays.equals(data, SELECT_APDU_HDR_LENGTH,
                        SELECT_APDU_HDR_LENGTH + NDEF_AID_LENGTH,
                        NDEF_AID_V1, 0, NDEF_AID_LENGTH)) {
                if (DBG) Log.d(TAG, "isSkipAid: Skip for NDEF_V1");
                return true;
            } else if (Arrays.equals(data, SELECT_APDU_HDR_LENGTH,
                        SELECT_APDU_HDR_LENGTH + NDEF_AID_LENGTH,
                        NDEF_AID_V2, 0, NDEF_AID_LENGTH)) {
                if (DBG) Log.d(TAG, "isSkipAid: Skip for NDEF_V2");
                return true;
            }
        }
        // The data payload is not selecting the skip AID.
        mNotSkipAid = true;
        return false;
    }

    /**
     * Returns whether a service in this package is preferred,
     * either because it's the default payment app or it's running
     * in the foreground.
     */
    public boolean packageHasPreferredService(String packageName) {
        return mPreferredServices.packageHasPreferredService(packageName);
    }

    @Override
    public void onPreferredSubscriptionChanged(int subscriptionId, boolean isActive) {
        int simType = isActive ?  getSimTypeById(subscriptionId) : TelephonyUtils.SIM_TYPE_UNKNOWN;
        Log.i(TAG, "onPreferredSubscriptionChanged: subscription_" + subscriptionId + "is active("
                + isActive + ")"
                + ", type(" + simType + ")");
        mRoutingOptionManager.onPreferredSimChanged(simType);
        if (simType != TelephonyUtils.SIM_TYPE_UNKNOWN) {
            updateRouteBasedOnPreferredSim();
        }
        else {
            Log.i(TAG, "onPreferredSubscriptionChanged: SIM is Invalid type");
        }
        mAidCache.onPreferredSimChanged(simType);
        // try to nfc turned off and on to swp initialize
        NfcService.getInstance().onPreferredSimChanged();
    }

    private void updateRouteBasedOnPreferredSim() {
        boolean changed = false;
        changed |= updateRouteToPreferredSim(()->mRoutingOptionManager.getDefaultRoute(),
                route->mRoutingOptionManager.overrideDefaultRoute(route));
        changed |= updateRouteToPreferredSim(()->mRoutingOptionManager.getDefaultIsoDepRoute(),
                route->mRoutingOptionManager.overrideDefaultIsoDepRoute(route));
        changed |= updateRouteToPreferredSim(()->mRoutingOptionManager.getDefaultOffHostRoute(),
                route->mRoutingOptionManager.overrideDefaultOffHostRoute(route));
        if (changed) {
            mRoutingOptionManager.overwriteRoutingTable();
        }
    }
    private boolean updateRouteToPreferredSim(Supplier<Integer> getter, Consumer<Integer> setter) {
        String se = mRoutingOptionManager.getSecureElementForRoute(getter.get());
        if (se.startsWith(RoutingOptionManager.SE_PREFIX_SIM)) {
            int route = mRoutingOptionManager.getRouteForSecureElement(
                    mRoutingOptionManager.getPreferredSim());
            setter.accept(route);
            return true;
        }
        else {
            Log.d(TAG, "updateRouteToPreferredSim: ignore");
        }
        return false;
    }

    /**
     * This class implements the application-facing APIs and are called
     * from binder. All calls must be permission-checked.
     */
    final class CardEmulationInterface extends INfcCardEmulation.Stub {
        @Override
        public boolean isDefaultServiceForCategory(int userId, ComponentName service,
                String category) {
            NfcPermissions.enforceUserPermissions(mContext);
            NfcPermissions.validateUserId(userId);
            if (!isServiceRegistered(userId, service)) {
                return false;
            }
            if (mWalletRoleObserver.isWalletRoleFeatureEnabled()) {
                PackageAndUser holder =
                        mWalletRoleObserver.getDefaultWalletRoleHolder(userId);
                if (holder.getPackage() == null) {
                    return false;
                }
                return service.getPackageName().equals(
                        holder.getPackage()) && userId == holder.getUserId();
            }
            ComponentName defaultService =
                    getDefaultServiceForCategory(userId, category, true);
            return (defaultService != null && defaultService.equals(service));
        }

        @Override
        public boolean isDefaultServiceForAid(int userId,
                ComponentName service, String aid) throws RemoteException {
            NfcPermissions.validateUserId(userId);
            NfcPermissions.enforceUserPermissions(mContext);
            if (!isServiceRegistered(userId, service)) {
                return false;
            }
            return mAidCache.isDefaultServiceForAid(userId, service, aid);
        }

        @Override
        public boolean setDefaultServiceForCategory(int userId,
                ComponentName service, String category) throws RemoteException {
            NfcPermissions.validateUserId(userId);
            NfcPermissions.enforceAdminPermissions(mContext);
            if (!isServiceRegistered(userId, service)) {
                return false;
            }
            return setDefaultServiceForCategoryChecked(userId, service, category);
        }

        @Override
        public boolean setDefaultForNextTap(int userId, ComponentName service)
                throws RemoteException {
            NfcPermissions.validateProfileId(mContext, userId);
            NfcPermissions.enforceAdminPermissions(mContext);
            if (service != null && !isServiceRegistered(userId, service)) {
                return false;
            }
            return mPreferredServices.setDefaultForNextTap(userId, service);
        }

        @Override
        public boolean setShouldDefaultToObserveModeForService(int userId,
                ComponentName service, boolean enable) {
            NfcPermissions.validateUserId(userId);
            NfcPermissions.enforceUserPermissions(mContext);
            if (!isServiceRegistered(userId, service)) {
                return false;
            }
            Log.d(TAG, "setShouldDefaultToObserveModeForService: (" + service + ") to "
                    + enable);
            boolean currentStatus = mServiceCache.doesServiceShouldDefaultToObserveMode(userId,
                    service);

            if (currentStatus != enable) {
                if (!mServiceCache.setShouldDefaultToObserveModeForService(userId,
                        Binder.getCallingUid(), service, enable)) {
                    return false;
                }
                updateForShouldDefaultToObserveMode(userId);
            }
            return true;
        }

        @Override
        public boolean registerAidGroupForService(int userId,
                ComponentName service, AidGroup aidGroup) throws RemoteException {
            NfcPermissions.validateUserId(userId);
            NfcPermissions.enforceUserPermissions(mContext);
            if (!isServiceRegistered(userId, service)) {
                Log.e(TAG, "registerAidGroupForService: service (" + service
                        + ") isn't registered for user " + userId);
                return false;
            }
            if (!mServiceCache.registerAidGroupForService(userId, Binder.getCallingUid(), service,
                    aidGroup)) {
                return false;
            }
            NfcService.getInstance().onPreferredPaymentChanged(
                    NfcAdapter.PREFERRED_PAYMENT_UPDATED);
            mNfcEventLog.logEvent(
                    NfcEventProto.EventType.newBuilder()
                            .setAidRegistration(NfcEventProto.NfcAidRegistration.newBuilder()
                                    .setAppInfo(NfcEventProto.NfcAppInfo.newBuilder()
                                            .setUid(Binder.getCallingUid())
                                            .build())
                                    .setComponentInfo(
                                        NfcEventProto.NfcComponentInfo.newBuilder()
                                            .setPackageName(
                                                service.getPackageName())
                                            .setClassName(
                                                service.getClassName())
                                            .build())
                                    .setIsRegistration(true)
                                    .addAllAids(aidGroup.getAids())
                                    .build())
                            .build());
            return true;
        }

        @Override
        public boolean registerPollingLoopFilterForService(int userId, ComponentName service,
                String pollingLoopFilter, boolean autoTransact) throws RemoteException {
            NfcPermissions.validateUserId(userId);
            NfcPermissions.enforceUserPermissions(mContext);
            if (!isServiceRegistered(userId, service)) {
                Log.e(TAG, "registerPollingLoopFilterForService: service (" + service
                        + ") isn't registered for user " + userId);
                return false;
            }
            if (!mServiceCache.registerPollingLoopFilterForService(userId, Binder.getCallingUid(),
                    service, pollingLoopFilter, autoTransact)) {
                return false;
            }
            mNfcEventLog.logEvent(
                    NfcEventProto.EventType.newBuilder()
                            .setPollingLoopRegistration(NfcEventProto.NfcPollingLoopRegistration
                                                            .newBuilder()
                                    .setAppInfo(NfcEventProto.NfcAppInfo.newBuilder()
                                            .setUid(Binder.getCallingUid())
                                            .build())
                                    .setComponentInfo(
                                        NfcEventProto.NfcComponentInfo.newBuilder()
                                            .setPackageName(
                                                service.getPackageName())
                                            .setClassName(
                                                service.getClassName())
                                            .build())
                                    .setIsRegistration(true)
                                    .setPollingLoopFilter(pollingLoopFilter)
                                    .build())
                            .build());
            return true;
        }

        @Override
        public boolean removePollingLoopFilterForService(int userId, ComponentName service,
                String pollingLoopFilter) throws RemoteException {
            NfcPermissions.validateUserId(userId);
            NfcPermissions.enforceUserPermissions(mContext);
            if (!isServiceRegistered(userId, service)) {
                Log.e(TAG, "removePollingLoopFilterForService: service (" + service
                        + ") isn't registered for user " + userId);
                return false;
            }
            if (!mServiceCache.removePollingLoopFilterForService(userId, Binder.getCallingUid(),
                    service, pollingLoopFilter)) {
                return false;
            }
            mNfcEventLog.logEvent(
                    NfcEventProto.EventType.newBuilder()
                            .setPollingLoopRegistration(NfcEventProto.NfcPollingLoopRegistration
                                                            .newBuilder()
                                    .setAppInfo(NfcEventProto.NfcAppInfo.newBuilder()
                                            .setUid(Binder.getCallingUid())
                                            .build())
                                    .setComponentInfo(
                                        NfcEventProto.NfcComponentInfo.newBuilder()
                                            .setPackageName(
                                                service.getPackageName())
                                            .setClassName(
                                                service.getClassName())
                                            .build())
                                    .setIsRegistration(false)
                                    .setPollingLoopFilter(pollingLoopFilter)
                                    .build())
                            .build());
            return true;
        }

        @Override
        public boolean registerPollingLoopPatternFilterForService(int userId, ComponentName service,
                String pollingLoopPatternFilter, boolean autoTransact) throws RemoteException {
            NfcPermissions.validateUserId(userId);
            NfcPermissions.enforceUserPermissions(mContext);
            if (!isServiceRegistered(userId, service)) {
                Log.e(TAG, "registerPollingLoopPatternFilterForService: service (" + service
                        + ") isn't registed for user " + userId);
                return false;
            }
            if (!mServiceCache.registerPollingLoopPatternFilterForService(userId,
                    Binder.getCallingUid(), service, pollingLoopPatternFilter, autoTransact)) {
                return false;
            }
            mNfcEventLog.logEvent(
                    NfcEventProto.EventType.newBuilder()
                            .setPollingLoopRegistration(NfcEventProto.NfcPollingLoopRegistration
                                                            .newBuilder()
                                    .setAppInfo(NfcEventProto.NfcAppInfo.newBuilder()
                                            .setUid(Binder.getCallingUid())
                                            .build())
                                    .setComponentInfo(
                                        NfcEventProto.NfcComponentInfo.newBuilder()
                                            .setPackageName(
                                                service.getPackageName())
                                            .setClassName(
                                                service.getClassName())
                                            .build())
                                    .setIsRegistration(true)
                                    .setPollingLoopFilter(pollingLoopPatternFilter)
                                    .build())
                            .build());
            return true;
        }

        @Override
        public boolean removePollingLoopPatternFilterForService(int userId, ComponentName service,
                String pollingLoopPatternFilter) throws RemoteException {
            NfcPermissions.validateUserId(userId);
            NfcPermissions.enforceUserPermissions(mContext);
            if (!isServiceRegistered(userId, service)) {
                Log.e(TAG, "removePollingLoopPatternFilterForService: service (" + service
                        + ") isn't registed for user " + userId);
                return false;
            }
            if (!mServiceCache.removePollingLoopPatternFilterForService(userId,
                    Binder.getCallingUid(), service, pollingLoopPatternFilter)) {
                return false;
            }
            mNfcEventLog.logEvent(
                    NfcEventProto.EventType.newBuilder()
                            .setPollingLoopRegistration(NfcEventProto.NfcPollingLoopRegistration
                                                            .newBuilder()
                                    .setAppInfo(NfcEventProto.NfcAppInfo.newBuilder()
                                            .setUid(Binder.getCallingUid())
                                            .build())
                                    .setComponentInfo(
                                        NfcEventProto.NfcComponentInfo.newBuilder()
                                            .setPackageName(
                                                service.getPackageName())
                                            .setClassName(
                                                service.getClassName())
                                            .build())
                                    .setIsRegistration(false)
                                    .setPollingLoopFilter(pollingLoopPatternFilter)
                                    .build())
                            .build());
            return true;
        }

        @Override
        public boolean setOffHostForService(int userId, ComponentName service, String offHostSE) {
            NfcPermissions.validateUserId(userId);
            NfcPermissions.enforceUserPermissions(mContext);
            if (!isServiceRegistered(userId, service)) {
                return false;
            }
            if (!mServiceCache.setOffHostSecureElement(userId, Binder.getCallingUid(), service,
                    offHostSE)) {
                return false;
            }
            NfcService.getInstance().onPreferredPaymentChanged(
                    NfcAdapter.PREFERRED_PAYMENT_UPDATED);
            return true;
        }

        @Override
        public boolean unsetOffHostForService(int userId, ComponentName service) {
            NfcPermissions.validateUserId(userId);
            NfcPermissions.enforceUserPermissions(mContext);
            if (!isServiceRegistered(userId, service)) {
                return false;
            }
            if (!mServiceCache.resetOffHostSecureElement(userId, Binder.getCallingUid(), service)) {
                return false;
            }
            NfcService.getInstance().onPreferredPaymentChanged(
                    NfcAdapter.PREFERRED_PAYMENT_UPDATED);
            return true;
        }

        @Override
        public AidGroup getAidGroupForService(int userId,
                ComponentName service, String category) throws RemoteException {
            NfcPermissions.validateUserId(userId);
            NfcPermissions.enforceUserPermissions(mContext);
            if (!isServiceRegistered(userId, service)) {
                return null;
            }
            return mServiceCache.getAidGroupForService(userId, Binder.getCallingUid(), service,
                    category);
        }

        @Override
        public boolean removeAidGroupForService(int userId,
                ComponentName service, String category) throws RemoteException {
            NfcPermissions.validateUserId(userId);
            NfcPermissions.enforceUserPermissions(mContext);
            if (!isServiceRegistered(userId, service)) {
                return false;
            }
            if (!mServiceCache.removeAidGroupForService(userId, Binder.getCallingUid(), service,
                    category)) {
                return false;
            }
            NfcService.getInstance().onPreferredPaymentChanged(
                    NfcAdapter.PREFERRED_PAYMENT_UPDATED);
            mNfcEventLog.logEvent(
                    NfcEventProto.EventType.newBuilder()
                            .setAidRegistration(NfcEventProto.NfcAidRegistration.newBuilder()
                                    .setAppInfo(NfcEventProto.NfcAppInfo.newBuilder()
                                            .setUid(Binder.getCallingUid())
                                            .build())
                                    .setComponentInfo(
                                        NfcEventProto.NfcComponentInfo.newBuilder()
                                            .setPackageName(
                                                service.getPackageName())
                                            .setClassName(
                                                service.getClassName())
                                            .build())
                                    .setIsRegistration(false)
                                    .build())
                            .build());
            return true;
        }

        @Override
        public List<ApduServiceInfo> getServices(int userId, String category)
                throws RemoteException {
            NfcPermissions.validateProfileId(mContext, userId);
            NfcPermissions.enforceAdminPermissions(mContext);
            return mServiceCache.getServicesForCategory(userId, category);
        }

        @Override
        public boolean setPreferredService(ComponentName service)
                throws RemoteException {
            NfcPermissions.enforceUserPermissions(mContext);
            if (!isServiceRegistered( UserHandle.getUserHandleForUid(
                    Binder.getCallingUid()).getIdentifier(), service)) {
                Log.e(TAG, "setPreferredService: unknown component");
                return false;
            }
            return mPreferredServices.registerPreferredForegroundService(service,
                    Binder.getCallingUid());
        }

        @Override
        public boolean unsetPreferredService() throws RemoteException {
            NfcPermissions.enforceUserPermissions(mContext);
            return mPreferredServices.unregisteredPreferredForegroundService(
                    Binder.getCallingUid());
        }

        @Override
        public boolean supportsAidPrefixRegistration() throws RemoteException {
            return mAidCache.supportsAidPrefixRegistration();
        }

        @Override
        public ApduServiceInfo getPreferredPaymentService(int userId) throws RemoteException {
            NfcPermissions.validateUserId(userId);
            NfcPermissions.enforceUserPermissions(mContext);
            NfcPermissions.enforcePreferredPaymentInfoPermissions(mContext);
            return mServiceCache.getService(userId,
                        mAidCache.getPreferredService().getComponentName());
        }

        @Override
        public int setServiceEnabledForCategoryOther(int userId,
                ComponentName app, boolean status) throws RemoteException {
            if (!mDeviceConfigFacade.getEnableServiceOther())
              return SET_SERVICE_ENABLED_STATUS_FAILURE_FEATURE_UNSUPPORTED;
            NfcPermissions.enforceUserPermissions(mContext);

            return mServiceCache.registerOtherForService(userId, app, status);
        }

        @Override
        public boolean isDefaultPaymentRegistered() throws RemoteException {
            if (mWalletRoleObserver.isWalletRoleFeatureEnabled()) {
                int callingUserId = Binder.getCallingUserHandle().getIdentifier();
                return mWalletRoleObserver.getDefaultWalletRoleHolder(
                        callingUserId).getPackage() != null;
            }
            String defaultComponent = Settings.Secure.getString(mContext.getContentResolver(),
                    Constants.SETTINGS_SECURE_NFC_PAYMENT_DEFAULT_COMPONENT);
            return defaultComponent != null ? true : false;
        }

        @Override
        public void overrideRoutingTable(int userHandle, String protocol, String technology,
                String pkg) {
            Log.d(TAG, "overrideRoutingTable: userHandle " + userHandle + ", protocol " + protocol
                    + ", technology " + technology);

            NfcPermissions.enforceAdminPermissions(mContext);

            if ((protocol != null && protocol.equals("default"))
                    || (technology != null && technology.equals("default"))) {
                Log.e(TAG, "overrideRoutingTable: override value cannot be set to default");
                throw new IllegalArgumentException("default value is not allowed.");
            }

            int callingUid = Binder.getCallingUid();
            if (android.nfc.Flags.nfcOverrideRecoverRoutingTable()) {
                if (!isPreferredServicePackageNameForUser(pkg,
                        UserHandle.getUserHandleForUid(callingUid).getIdentifier())) {
                    Log.e(TAG, "overrideRoutingTable: Caller not preferred NFC service.");
                    throw new SecurityException("Caller not preferred NFC service");
                }
            }
            if (!mForegroundUtils
                    .registerUidToBackgroundCallback(mForegroundCallback, callingUid)) {
                Log.e(TAG, "overrideRoutingTable: Caller is not in foreground.");
                throw new IllegalArgumentException("Caller is not in foreground.");
            }
            mForegroundUid = callingUid;

            int protocolRoute = getRouteForSecureElement(protocol);
            int technologyRoute = getRouteForSecureElement(technology);
            if (DBG) {
                Log.d(TAG, "overrideRoutingTable: protocolRoute " + protocolRoute
                        + ", technologyRoute " + technologyRoute);
            }

//            mRoutingOptionManager.overrideDefaultRoute(protocolRoute);
            mRoutingOptionManager.overrideDefaultIsoDepRoute(protocolRoute);
            mRoutingOptionManager.overrideDefaultOffHostRoute(technologyRoute);
            int result = mAidCache.onRoutingOverridedOrRecovered();
            switch (result) {
                case AidRoutingManager.CONFIGURE_ROUTING_SUCCESS:
                    break;
                case AidRoutingManager.CONFIGURE_ROUTING_FAILURE_TABLE_FULL:
                    throw new IllegalArgumentException("CONFIGURE_ROUTING_FAILURE_TABLE_FULL");
                case AidRoutingManager.CONFIGURE_ROUTING_FAILURE_UNKNOWN:
                    throw new IllegalArgumentException("CONFIGURE_ROUTING_FAILURE_UNKNOWN");
            }
        }

        @Override
        public void recoverRoutingTable(int userHandle) {
            Log.d(TAG, "recoverRoutingTable: userHandle " + userHandle);

            NfcPermissions.enforceAdminPermissions(mContext);

            if (!mForegroundUtils.isInForeground(Binder.getCallingUid())) {
                if (DBG) Log.d(TAG, "recoverRoutingTable : not in foreground.");
                throw new IllegalArgumentException("Caller is not in foreground.");
            }
            mForegroundUid = Process.INVALID_UID;

            mRoutingOptionManager.recoverOverridedRoutingTable();
            if (mAidCache.onRoutingOverridedOrRecovered()
                        != AidRoutingManager.CONFIGURE_ROUTING_SUCCESS) {
                throw new IllegalArgumentException(
                        "recoverRoutingTable: " + "onRoutingOverridedOrRecovered() failed");
            }
        }

        @Override
        public void overwriteRoutingTable(int userHandle, String aids,
            String protocol, String technology, String sc) {
            Log.d(TAG, "overwriteRoutingTable(): userHandle: " + userHandle
                + ", emptyAid: " + aids + ", protocol: " + protocol
                + ", technology: " + technology + ", systemCode: " + sc);

            NfcPermissions.enforceAdminPermissions(mContext);

            int aidRoute = getRouteForSecureElement(aids);
            int protocolRoute = getRouteForSecureElement(protocol);
            int technologyRoute = getRouteForSecureElement(technology);
            int scRoute = getRouteForSecureElement(sc);

            if (DBG)  {
                Log.d(TAG, "overwriteRoutingTable(): aidRoute: " + Integer.toHexString(aidRoute)
                        + ", protocolRoute: " + Integer.toHexString(protocolRoute)
                        + ", technologyRoute: " + Integer.toHexString(technologyRoute)
                        + ", scRoute: " + Integer.toHexString(scRoute));
            }
            if (aids != null) {
                mRoutingOptionManager.overrideDefaultRoute(aidRoute);
            }
            if (protocol != null) {
                mRoutingOptionManager.overrideDefaultIsoDepRoute(protocolRoute);
            }
            if (technology != null) {
                mRoutingOptionManager.overrideDefaultOffHostRoute(technologyRoute);
            }
            if (sc != null) {
                mRoutingOptionManager.overrideDefaultScRoute(scRoute);
            }
            if (aids != null || protocol != null || technology != null || sc != null) {
                mRoutingOptionManager.overwriteRoutingTable();
            }
            if (mAidCache.onRoutingOverridedOrRecovered()
                        != AidRoutingManager.CONFIGURE_ROUTING_SUCCESS) {
                throw new IllegalArgumentException("onRoutingOverridedOrRecovered() failed");
            }
        }

        @Override
        public List<String> getRoutingStatus() {
            NfcPermissions.enforceAdminPermissions(mContext);
            List<Integer> routingList = new ArrayList<>();

            if (mRoutingOptionManager.isRoutingTableOverrided()) {
                routingList.add(mRoutingOptionManager.getOverrideDefaultRoute());
                routingList.add(mRoutingOptionManager.getOverrideDefaultIsoDepRoute());
                routingList.add(mRoutingOptionManager.getOverrideDefaultOffHostRoute());
            }
            else {
                routingList.add(mRoutingOptionManager.getDefaultRoute());
                routingList.add(mRoutingOptionManager.getDefaultIsoDepRoute());
                routingList.add(mRoutingOptionManager.getDefaultOffHostRoute());
            }

            return routingList.stream()
                .map(route->mRoutingOptionManager.getSecureElementForRoute(route))
                .collect(Collectors.toList());
        }

        @Override
        public void setAutoChangeStatus(boolean state) {
            NfcPermissions.enforceAdminPermissions(mContext);
            mRoutingOptionManager.setAutoChangeStatus(state);
        }

        @Override
        public boolean isAutoChangeEnabled() {
            NfcPermissions.enforceAdminPermissions(mContext);
            return mRoutingOptionManager.isAutoChangeEnabled();
        }

        @Override
        public boolean isEuiccSupported() {
            NfcPermissions.enforceUserPermissions(mContext);
            return mContext.getResources().getBoolean(R.bool.enable_euicc_support)
                    && NfcInjector.NfcProperties.isEuiccSupported();
        }

        /**
         * Make sure the device has required telephony feature
         *
         * @throws UnsupportedOperationException if the device does not have required telephony feature
         */
        private void enforceTelephonySubscriptionFeatureWithException(
                String callingPackage, String methodName) {
            if (callingPackage == null) return;
            if (mVendorApiLevel < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                // Skip to check associated telephony feature,
                // if compatibility change is not enabled for the current process or
                // the SDK version of vendor partition is less than Android V.
                return;
            }
            if (!mContext.getPackageManager().hasSystemFeature(FEATURE_TELEPHONY_SUBSCRIPTION)) {
                throw new UnsupportedOperationException(
                        methodName + " is unsupported without " + FEATURE_TELEPHONY_SUBSCRIPTION);
            }
        }

        @Override
        public int setDefaultNfcSubscriptionId(int subscriptionId, String pkgName) {
            NfcPermissions.enforceAdminPermissions(mContext);
            if (!android.nfc.Flags.enableCardEmulationEuicc()) {
                Log.d(TAG, "setDefaultNfcSubscriptionId: Euicc CardEmulation isn't enabled");
                return CardEmulation.SET_SUBSCRIPTION_ID_STATUS_FAILED_NOT_SUPPORTED;
            }
            enforceTelephonySubscriptionFeatureWithException(pkgName, "setDefaultNfcSubscriptionId");
            mPreferredSubscriptionService.setPreferredSubscriptionId(subscriptionId, true);
            // TODO(b/321314635): Write to NFC persistent setting.
            return CardEmulation.SET_SUBSCRIPTION_ID_STATUS_SUCCESS;
        }

        @Override
        public int getDefaultNfcSubscriptionId(String pkgName) {
            NfcPermissions.enforceUserPermissions(mContext);
            enforceTelephonySubscriptionFeatureWithException(pkgName, "getDefaultNfcSubscriptionId");
            // TODO(b/321314635): Read NFC persistent setting.
            return mPreferredSubscriptionService.getPreferredSubscriptionId();
        }

        @Override
        public void registerNfcEventCallback(INfcEventCallback listener) {
            if (!android.nfc.Flags.nfcEventListener()) {
                return;
            }
            mNfcEventCallbacks.register(listener);
        }

        @Override
        public void unregisterNfcEventCallback(
                INfcEventCallback listener) {
            if (!android.nfc.Flags.nfcEventListener()) {
                return;
            }
            mNfcEventCallbacks.unregister(listener);
        }
    }

    final RemoteCallbackList<INfcEventCallback> mNfcEventCallbacks = new RemoteCallbackList<>();

    private interface ListenerCall {
        void invoke(INfcEventCallback listener) throws RemoteException;
    }

    private void callNfcEventCallbacks(ListenerCall call) {
        synchronized (mNfcEventCallbacks) {
            int numListeners = mNfcEventCallbacks.beginBroadcast();
            try {
                IntStream.range(0, numListeners).forEach(i -> {
                    try {
                        call.invoke(mNfcEventCallbacks.getBroadcastItem(i));
                    } catch (RemoteException re) {
                        Log.i(TAG, "callNfcEventCallbacks: Service died", re);
                    }
                });

            } finally {
                mNfcEventCallbacks.finishBroadcast();
            }
        }
    }

    void notifyPreferredServiceListeners(ComponentNameAndUser preferredService) {
        if (!android.nfc.Flags.nfcEventListener()) {
            return;
        }
        callNfcEventCallbacks(listener -> listener.onPreferredServiceChanged(preferredService));
    }

    @Override
    public void onAidConflict(@NonNull String aid) {
        if (android.nfc.Flags.nfcEventListener()) {
            callNfcEventCallbacks(listener -> listener.onAidConflictOccurred(aid));
        }
    }

    @Override
    public void onAidNotRouted(@NonNull String aid) {
        if (android.nfc.Flags.nfcEventListener()) {
            callNfcEventCallbacks(listener -> listener.onAidNotRouted(aid));
        }
    }

    public void onNfcStateChanged(int state) {
        if (android.nfc.Flags.nfcEventListener()) {
            callNfcEventCallbacks(listener -> listener.onNfcStateChanged(state));
        }
    }

    public void onRemoteFieldChanged(boolean isDetected) {
        if (android.nfc.Flags.nfcEventListener()) {
            callNfcEventCallbacks(listener -> listener.onRemoteFieldChanged(isDetected));
        }
    }

    public void onInternalErrorReported(@CardEmulation.NfcInternalErrorType int errorType) {
        if (android.nfc.Flags.nfcEventListener()) {
            callNfcEventCallbacks(listener -> listener.onInternalErrorReported(errorType));
        }
    }

    final ForegroundUtils.Callback mForegroundCallback = new ForegroundCallbackImpl();

    class ForegroundCallbackImpl implements ForegroundUtils.Callback {
        @Override
        public void onUidToBackground(int uid) {
            synchronized (CardEmulationManager.this) {
                if (mForegroundUid == uid) {
                    if (DBG) {
                        Log.d(TAG, "onUidToBackground: Uid " + uid + " switch to background");
                    }
                    mForegroundUid = Process.INVALID_UID;
                    mRoutingOptionManager.recoverOverridedRoutingTable();
                }
            }
        }
    }

    private int getRouteForSecureElement(String se) {
        String route = se;
        if (route == null) {
            return -1;
        }

        if (route.equals("DH")) {
            return 0;
        }

        if (route.length() == 3) {
            route = route + '1';
        }
        return mRoutingOptionManager.getRouteForSecureElement(route);
    }

    /**
     * This class implements the application-facing APIs and are called
     * from binder. All calls must be permission-checked.
     */
    final class NfcFCardEmulationInterface extends INfcFCardEmulation.Stub {
        @Override
        public String getSystemCodeForService(int userId, ComponentName service)
                throws RemoteException {
            NfcPermissions.validateUserId(userId);
            NfcPermissions.enforceUserPermissions(mContext);
            if (!isNfcFServiceInstalled(userId, service)) {
                return null;
            }
            return mNfcFServicesCache.getSystemCodeForService(
                    userId, Binder.getCallingUid(), service);
        }

        @Override
        public boolean registerSystemCodeForService(int userId, ComponentName service,
                String systemCode)
                throws RemoteException {
            NfcPermissions.validateUserId(userId);
            NfcPermissions.enforceUserPermissions(mContext);
            if (!isNfcFServiceInstalled(userId, service)) {
                return false;
            }
            return mNfcFServicesCache.registerSystemCodeForService(
                    userId, Binder.getCallingUid(), service, systemCode);
        }

        @Override
        public boolean removeSystemCodeForService(int userId, ComponentName service)
                throws RemoteException {
            NfcPermissions.validateUserId(userId);
            NfcPermissions.enforceUserPermissions(mContext);
            if (!isNfcFServiceInstalled(userId, service)) {
                return false;
            }
            return mNfcFServicesCache.removeSystemCodeForService(
                    userId, Binder.getCallingUid(), service);
        }

        @Override
        public String getNfcid2ForService(int userId, ComponentName service)
                throws RemoteException {
            NfcPermissions.validateUserId(userId);
            NfcPermissions.enforceUserPermissions(mContext);
            if (!isNfcFServiceInstalled(userId, service)) {
                return null;
            }
            return mNfcFServicesCache.getNfcid2ForService(
                    userId, Binder.getCallingUid(), service);
        }

        @Override
        public boolean setNfcid2ForService(int userId,
                ComponentName service, String nfcid2) throws RemoteException {
            NfcPermissions.validateUserId(userId);
            NfcPermissions.enforceUserPermissions(mContext);
            if (!isNfcFServiceInstalled(userId, service)) {
                return false;
            }
            return mNfcFServicesCache.setNfcid2ForService(
                    userId, Binder.getCallingUid(), service, nfcid2);
        }

        @Override
        public boolean enableNfcFForegroundService(ComponentName service)
                throws RemoteException {
            NfcPermissions.enforceUserPermissions(mContext);
            if (isNfcFServiceInstalled(UserHandle.getUserHandleForUid(
                    Binder.getCallingUid()).getIdentifier(), service)) {
                return mEnabledNfcFServices.registerEnabledForegroundService(service,
                        Binder.getCallingUid());
            }
            return false;
        }

        @Override
        public boolean disableNfcFForegroundService() throws RemoteException {
            NfcPermissions.enforceUserPermissions(mContext);
            return mEnabledNfcFServices.unregisteredEnabledForegroundService(
                    Binder.getCallingUid());
        }

        @Override
        public List<NfcFServiceInfo> getNfcFServices(int userId)
                throws RemoteException {
            NfcPermissions.validateProfileId(mContext, userId);
            NfcPermissions.enforceUserPermissions(mContext);
            return mNfcFServicesCache.getServices(userId);
        }

        @Override
        public int getMaxNumOfRegisterableSystemCodes()
                throws RemoteException {
            NfcPermissions.enforceUserPermissions(mContext);
            return NfcService.getInstance().getLfT3tMax();
        }
    }

    @Override
    public void onPreferredPaymentServiceChanged(ComponentNameAndUser service) {
        Log.i(TAG, "onPreferredPaymentServiceChanged");
        ComponentNameAndUser oldPreferredService = mAidCache.getPreferredService();
        mAidCache.onPreferredPaymentServiceChanged(service);
        mHostEmulationManager.onPreferredPaymentServiceChanged(service);
        ComponentNameAndUser newPreferredService = mAidCache.getPreferredService();

        NfcService.getInstance().onPreferredPaymentChanged(
                    NfcAdapter.PREFERRED_PAYMENT_CHANGED);
        if (!Objects.equals(oldPreferredService, newPreferredService)) {
            updateForShouldDefaultToObserveMode(newPreferredService.getUserId());
            notifyPreferredServiceListeners(newPreferredService);
        }
    }

    @Override
    public void onPreferredForegroundServiceChanged(ComponentNameAndUser service) {
        Log.i(TAG, "onPreferredForegroundServiceChanged");
        ComponentNameAndUser oldPreferredService = mAidCache.getPreferredService();
        mHostEmulationManager.onPreferredForegroundServiceChanged(service);
        ComponentNameAndUser newPreferredService = mAidCache.getPreferredService();

        NfcService.getInstance().onPreferredPaymentChanged(
                NfcAdapter.PREFERRED_PAYMENT_CHANGED);
        if (!Objects.equals(oldPreferredService, newPreferredService)) {
            updateForShouldDefaultToObserveMode(newPreferredService.getUserId());
            notifyPreferredServiceListeners(newPreferredService);
        }
    }

    public void updateForDefaultSwpToEuicc() {
        if (!android.nfc.Flags.enableCardEmulationEuicc()) {
            Log.d(TAG, "updateForDefaultSwpToEuicc: Euicc CardEmulation isn't  enabled");
            return;
        }

        if (!mContext.getResources().getBoolean(R.bool.enable_euicc_support)
                || !NfcInjector.NfcProperties.isEuiccSupported()) {
            Log.d(TAG, "updateForDefaultSwpToEuicc: Euicc CardEmulation is not support");
            return;
        }

        if (DBG) Log.d(TAG, "updateForDefaultSwpToEuicc: Assign SWP for eSIM before NFC turned on");

        int preferredSubscriptionId = mPreferredSubscriptionService.getPreferredSubscriptionId();
        String response = mTelephonyUtils
                .updateSwpStatusForEuicc(getSimTypeById(preferredSubscriptionId));
        Log.d(TAG, "updateForDefaultSwpToEuicc: response: " + response);
        if (response.length() >= 4) {
            String statusWord = response.substring(response.length() - 4);
            if (!TextUtils.equals(statusWord, "9000")) {
                Log.e(TAG, "updateForDefaultSwpToEuicc: ASSIGN_SWP(LSE) Command is fail: "
                        + statusWord);
            }
        } else {
            Log.e(TAG, "updateForDefaultSwpToEuicc: response.length() is wrong length : "
                    + response.length());
        }
    }

    private int getSimTypeById(int subscriptionId) {
        Optional<SubscriptionInfo> optionalInfo =
                mTelephonyUtils.getActiveSubscriptionInfoById(subscriptionId);
        if (optionalInfo.isPresent()) {
            SubscriptionInfo info = optionalInfo.get();
            if (info.isEmbedded()) {
                return info.getPortIndex() == 0
                        ? TelephonyUtils.SIM_TYPE_EUICC_1 : TelephonyUtils.SIM_TYPE_EUICC_2;
            }
            else {
                return TelephonyUtils.SIM_TYPE_UICC;
            }
        }

        return TelephonyUtils.SIM_TYPE_UNKNOWN;
    }

    public void updateForShouldDefaultToObserveMode(int userId) {
        long token = Binder.clearCallingIdentity();
        try {
            NfcAdapter adapter = NfcAdapter.getDefaultAdapter(mContext);
            if (adapter == null) {
                Log.e(TAG, "updateForShouldDefaultToObserveMode: adapter is null, returning");
                return;
            }
            ComponentNameAndUser preferredServiceAndUser = mAidCache.getPreferredService();
            if (preferredServiceAndUser == null) {
                return;
            }
            ComponentName preferredService = preferredServiceAndUser.getComponentName();
            boolean enableObserveMode = mServiceCache.doesServiceShouldDefaultToObserveMode(userId,
                    preferredService);
            mHostEmulationManager.updateForShouldDefaultToObserveMode(enableObserveMode);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    public void updateFirmwareExitFramesForWalletRole(int userId) {
        if (!Flags.exitFrames()) {
            return;
        }

        NfcService nfcService = NfcService.getInstance();
        if (!nfcService.isFirmwareExitFramesSupported()
                || nfcService.getNumberOfFirmwareExitFramesSupported() <= 0) {
            return;
        }

        // Get all services then find those associated with role holder
        Stream<ApduServiceInfo> roleServices = mServiceCache.getServices(userId).stream().filter(
                serviceInfo -> mAidCache.isDefaultOrAssociatedWalletService(serviceInfo, userId));

        // Not every pattern filter is a valid exit frame, so this function catches
        // exceptions that could be thrown creating an ExitFrame object from a pattern.
        Function<String, ExitFrame> toExitFrameOrNull = (s) -> {
            try {
                return new ExitFrame(s);
            } catch (Exception e) {
                return null;
            }
        };
        // Combine filters and pattern filters, slice to size of supported, build list of exit
        // frames
        List<ExitFrame> exitFrames = roleServices.flatMap(serviceInfo ->
                        Stream.concat(
                                        serviceInfo.getPollingLoopFilters().stream(),
                                        serviceInfo.getPollingLoopPatternFilters()
                                                .stream().map(Pattern::toString)
                                )
                                .filter(
                                        serviceInfo::getShouldAutoTransact))
                .distinct()
                .map(toExitFrameOrNull)
                .filter(Objects::nonNull)
                .limit(nfcService.getNumberOfFirmwareExitFramesSupported())
                .collect(Collectors.toList());
        Log.d(TAG, "updateFirmwareExitFramesForWalletRole: " + exitFrames.size() + " frames");
        nfcService.setFirmwareExitFrameTable(exitFrames, FIRMWARE_EXIT_FRAME_TIMEOUT_MS);
    }

    public void onObserveModeStateChange(boolean enabled) {
        mHostEmulationManager.onObserveModeStateChange(enabled);
        if (android.nfc.Flags.nfcEventListener()) {
            callNfcEventCallbacks(listener -> listener.onObserveModeStateChanged(enabled));
        }
    }

    public void onObserveModeDisabledInFirmware(PollingFrame exitFrame) {
        if (android.nfc.Flags.nfcEventListener()) {
            callNfcEventCallbacks(listener -> listener.onObserveModeDisabledInFirmware(exitFrame));
        }
        mHostEmulationManager.onObserveModeDisabledInFirmware(exitFrame);

        if (mStatsdUtils != null) {
            mStatsdUtils.logAutoTransactReported(StatsdUtils.PROCESSOR_NFCC, exitFrame.getData());
        }
    }

    @Override
    public void onWalletRoleHolderChanged(String holder, int userId) {
        mPreferredServices.onWalletRoleHolderChanged(holder, userId);
        mAidCache.onWalletRoleHolderChanged(holder, userId);
        if (Flags.exitFrames()) {
            updateFirmwareExitFramesForWalletRole(userId);
        }
    }

    @Override
    public void onEnabledForegroundNfcFServiceChanged(int userId, ComponentName service) {
        mT3tIdentifiersCache.onEnabledForegroundNfcFServiceChanged(userId, service);
        mHostNfcFEmulationManager.onEnabledForegroundNfcFServiceChanged(userId, service);
    }

    public String getRegisteredAidCategory(String aid) {
        RegisteredAidCache.AidResolveInfo resolvedInfo = mAidCache.resolveAid(aid);
        if (resolvedInfo != null) {
            return resolvedInfo.getCategory();
        }
        return "";
    }

    public boolean isRequiresScreenOnServiceExist() {
        return mAidCache.isRequiresScreenOnServiceExist();
    }

    public boolean isPreferredServicePackageNameForUser(String packageName, int userId) {
        return mAidCache.isPreferredServicePackageNameForUser(packageName, userId);
    }

    public boolean isHostCardEmulationActivated() {
        return mHostEmulationManager.isHostCardEmulationActivated();
    }
}
