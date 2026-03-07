package android.nfc.cts;

import static android.nfc.cts.NfcUtils.assumeObserveModeSupported;
import static android.nfc.cts.NfcUtils.assumeVsrApiGreaterThanUdc;
import static android.nfc.cts.WalletRoleTestUtils.CTS_PACKAGE_NAME;
import static android.nfc.cts.WalletRoleTestUtils.WALLET_HOLDER_PACKAGE_NAME;
import static android.nfc.cts.WalletRoleTestUtils.WALLET_HOLDER_SERVICE_DESC;
import static android.nfc.cts.WalletRoleTestUtils.getWalletRoleHolderService;
import static android.nfc.cts.WalletRoleTestUtils.runWithRole;
import static android.nfc.cts.WalletRoleTestUtils.runWithRoleNone;

import static com.android.compatibility.common.util.SystemUtil.runShellCommand;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeFalse;
import static org.junit.Assume.assumeTrue;

import android.annotation.NonNull;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.Instrumentation;
import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.ContentObserver;
import android.net.Uri;
import android.nfc.Flags;
import android.nfc.NfcAdapter;
import android.nfc.cardemulation.AidGroup;
import android.nfc.cardemulation.ApduServiceInfo;
import android.nfc.cardemulation.CardEmulation;
import android.nfc.cardemulation.PollingFrame;
import android.nfc.cardemulation.PollingFrame.PollingFrameType;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.UserManager;
import android.platform.test.annotations.RequiresFlagsDisabled;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;
import android.provider.Settings;
import android.testing.PollingCheck;
import android.util.Log;
import android.view.KeyEvent;

import androidx.test.InstrumentationRegistry;
import androidx.test.core.app.ApplicationProvider;

import com.android.compatibility.common.util.ApiTest;
import com.android.compatibility.common.util.CommonTestUtils;
import com.android.compatibility.common.util.SystemUtil;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@RunWith(JUnit4.class)
public class CardEmulationTest {
    private NfcAdapter mAdapter;

    private static final ComponentName mService =
        new ComponentName("android.nfc.cts", "android.nfc.cts.CtsMyHostApduService");
    private static final String PAYMENT_AID_1 = "A000000004101011";
    private static final String PAYMENT_AID_2 = "A000000004101012";
    private static final String PAYMENT_AID_3 = "A000000004101013";
    private static final List<String> PAYMENT_AIDS =
        List.of(PAYMENT_AID_1, PAYMENT_AID_2, PAYMENT_AID_3);
    private static final int SUBSCRIPTION_ID_UICC= 0x100000;

    private Context mContext;

    @Rule
    public final CheckFlagsRule mCheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule();

    private boolean supportsHardware() {
        final PackageManager pm = InstrumentationRegistry.getContext().getPackageManager();
        return pm.hasSystemFeature(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION);
    }

    private boolean supportsHardwareForEse() {
        final PackageManager pm = InstrumentationRegistry.getContext().getPackageManager();
        return pm.hasSystemFeature(PackageManager.FEATURE_NFC_OFF_HOST_CARD_EMULATION_ESE);
    }

    @Before
    public void setUp() throws NoSuchFieldException, RemoteException, InterruptedException {
        assumeTrue("Device must support NFC HCE", supportsHardware());
        mContext = InstrumentationRegistry.getContext();
        mAdapter = NfcAdapter.getDefaultAdapter(mContext);
        assertNotNull("NFC Adapter is null", mAdapter);
        assertTrue("NFC Adapter could not be enabled", NfcUtils.enableNfc(mAdapter, mContext));
    }

    @After
    public void tearDown() throws Exception {
        if (mAdapter != null && mContext != null) {
            Assert.assertTrue("Failed to enable NFC in test cleanup",
                NfcUtils.enableNfc(mAdapter, mContext));
        } else {
            Log.w("CardEmulationTest", "mAdapter or mContext is null");
        }
        sCurrentPollLoopReceiver = null;
    }

    @Test
    public void getNonNullInstance() {
        CardEmulation instance = CardEmulation.getInstance(mAdapter);
        assertNotNull("CardEmulation instance is null", instance);
    }

    @Test
    public void testCategoryAllowsForegroundPreference() {
        CardEmulation instance = CardEmulation.getInstance(mAdapter);
        assertTrue(instance.categoryAllowsForegroundPreference(CardEmulation.CATEGORY_PAYMENT));
        assertTrue(instance.categoryAllowsForegroundPreference(CardEmulation.CATEGORY_OTHER));
    }

    @Test
    public void testGetSelectionModeForCategory() throws RemoteException {
        CardEmulation instance = CardEmulation.getInstance(mAdapter);
        ArrayList<Integer> validResults = new ArrayList<>();
        validResults.add(CardEmulation.SELECTION_MODE_ALWAYS_ASK);
        validResults.add(CardEmulation.SELECTION_MODE_PREFER_DEFAULT);

        int result = instance.getSelectionModeForCategory(CardEmulation.CATEGORY_PAYMENT);

        assertTrue(validResults.contains(result));
    }

    @Test
    public void testGetSelectionModeForCategoryWithCategoryOther() {
        CardEmulation instance = CardEmulation.getInstance(mAdapter);
        int result = instance.getSelectionModeForCategory(CardEmulation.CATEGORY_OTHER);
        assertEquals(CardEmulation.SELECTION_MODE_ASK_IF_CONFLICT, result);
    }

    @Test
    public void testSetAndUnsetOffHostForService() throws RemoteException {
        assumeTrue("Device must support eSE off-host HCE", supportsHardwareForEse());
        CardEmulation instance = CardEmulation.getInstance(mAdapter);
        ComponentName offHostService = new ComponentName(mContext, CtsMyOffHostApduService.class);

        try {
            assertTrue(instance.setOffHostForService(offHostService, "eSE"));
            assertTrue(instance.setShouldDefaultToObserveModeForService(offHostService, true));
            assertTrue(instance.unsetOffHostForService(offHostService));
        } finally {
            assertTrue(instance.setShouldDefaultToObserveModeForService(offHostService, false));
        }
    }

    @Test
    public void testRegisterAndGetAids() throws RemoteException {
        CardEmulation instance = CardEmulation.getInstance(mAdapter);

        assertTrue(instance.registerAidsForService(
                mService, CardEmulation.CATEGORY_PAYMENT, PAYMENT_AIDS));
        List<String> result = instance.getAidsForService(mService, CardEmulation.CATEGORY_PAYMENT);
        assertEquals(result, PAYMENT_AIDS);

        // Unregister AIDs from service
        assertTrue(instance.removeAidsForService(mService, CardEmulation.CATEGORY_PAYMENT));
        assertNull(instance.getAidsForService(mService, CardEmulation.CATEGORY_PAYMENT));
    }

    @Test
    public void testSetAndUnsetPreferredService() throws RemoteException {
        CardEmulation instance = CardEmulation.getInstance(mAdapter);
        Activity activity = createAndResumeActivity();

        assertTrue(instance.setPreferredService(activity, mService));
        assertTrue(instance.unsetPreferredService(activity));
    }

    @Test
    public void testSupportsAidPrefixRegistration() throws RemoteException {
        CardEmulation instance = CardEmulation.getInstance(mAdapter);
        boolean result = instance.supportsAidPrefixRegistration();
        assertTrue(result);
    }

    @Test
    public void testGetAidsForPreferredPaymentService() throws RemoteException {
        CardEmulation instance = CardEmulation.getInstance(mAdapter);
        Activity activity = createAndResumeActivity();
        assertTrue(instance.setPreferredService(activity, mService));

        List<String> result = instance.getAidsForPreferredPaymentService();

        assertEquals(result, PAYMENT_AIDS);
    }

    @Test
    public void testGetRouteDestinationForHostService() throws RemoteException {
        CardEmulation instance = CardEmulation.getInstance(mAdapter);
        Activity activity = createAndResumeActivity();
        assertTrue(instance.setPreferredService(activity, mService));

        String result = instance.getRouteDestinationForPreferredPaymentService();

        assertEquals("Host", result);
    }

    @Test
    public void testGetRouteDestinationForOffHostService() throws RemoteException {
        CardEmulation instance = CardEmulation.getInstance(mAdapter);
        Activity activity = createAndResumeActivity();
        assertTrue(instance.setPreferredService(activity,
                new ComponentName(mContext, CtsMyOffHostApduService.class)));

        String result = instance.getRouteDestinationForPreferredPaymentService();

        assertEquals("OffHost", result);

        // Unset preferred service
        assertTrue(instance.unsetPreferredService(activity));
    }

    @Test
    public void testGetDescriptionForPreferredPaymentService() throws RemoteException {
        CardEmulation instance = CardEmulation.getInstance(mAdapter);
        Activity activity = createAndResumeActivity();
        assertTrue(instance.setPreferredService(activity, mService));

        CharSequence result = instance.getDescriptionForPreferredPaymentService();

        assertEquals(result.toString(),
            mContext.getResources().getString(getResIdForServiceClass(CtsMyHostApduService.class)));
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_NFC_MAINLINE)
    public void testGetPaymentServices() throws RemoteException {
        CardEmulation instance = CardEmulation.getInstance(mAdapter);

        List<ApduServiceInfo> result =
            instance.getServices(
                CardEmulation.CATEGORY_PAYMENT, mContext.getUser().getIdentifier());

        assertNotNull(result);
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_NFC_MAINLINE)
    public void testGetNonPaymentServices() throws RemoteException {
        CardEmulation instance = CardEmulation.getInstance(mAdapter);

        List<ApduServiceInfo> result =
            instance.getServices(CardEmulation.CATEGORY_OTHER, mContext.getUser().getIdentifier());

        assertNotNull(result);
    }

    @Test
    @RequiresFlagsEnabled(android.permission.flags.Flags.FLAG_WALLET_ROLE_ENABLED)
    public void testGetPreferredPaymentService() {
        final String expectedPaymentService = "foo.bar/foo.bar.baz.Service";
        Settings.Secure.putString(ApplicationProvider.getApplicationContext().getContentResolver(),
                Settings.Secure.NFC_PAYMENT_DEFAULT_COMPONENT, expectedPaymentService);

        ComponentName paymentService = CardEmulation.getPreferredPaymentService(
                ApplicationProvider.getApplicationContext());

        assertEquals(paymentService, ComponentName.unflattenFromString(expectedPaymentService));
    }

    @Test
    @RequiresFlagsDisabled(android.permission.flags.Flags.FLAG_WALLET_ROLE_ENABLED)
    public void testTypeAPollingLoopToDefault() {
        assumeVsrApiGreaterThanUdc();
        ComponentName originalDefault = null;
        mAdapter.notifyHceDeactivated();
        try {
            originalDefault = setDefaultPaymentService(CustomHostApduService.class);
            ArrayList<PollingFrame> frames = new ArrayList<PollingFrame>(6);
            frames.add(createFrame(PollingFrame.POLLING_LOOP_TYPE_ON));
            frames.add(createFrame(PollingFrame.POLLING_LOOP_TYPE_A));
            frames.add(createFrame(PollingFrame.POLLING_LOOP_TYPE_OFF));
            frames.add(createFrame(PollingFrame.POLLING_LOOP_TYPE_ON));
            frames.add(createFrame(PollingFrame.POLLING_LOOP_TYPE_A));
            frames.add(createFrame(PollingFrame.POLLING_LOOP_TYPE_OFF));
            ensurePreferredService(CustomHostApduService.class);
            notifyPollingLoopAndWait(new ArrayList<PollingFrame>(frames),
                    CustomHostApduService.class.getName());
        } finally {
            setDefaultPaymentService(originalDefault);
            mAdapter.notifyHceDeactivated();
        }
    }

    @Test
    @RequiresFlagsEnabled({android.permission.flags.Flags.FLAG_WALLET_ROLE_ENABLED})
    public void testTypeAPollingLoopToWalletHolder() {
        assumeVsrApiGreaterThanUdc();
        WalletRoleTestUtils.runWithRole(mContext, WalletRoleTestUtils.WALLET_HOLDER_PACKAGE_NAME,
                () -> {
                    NfcAdapter adapter = NfcAdapter.getDefaultAdapter(mContext);
                    adapter.notifyHceDeactivated();
                    ArrayList<PollingFrame> frames = new ArrayList<PollingFrame>(6);
                    frames.add(createFrame(PollingFrame.POLLING_LOOP_TYPE_ON));
                    frames.add(createFrame(PollingFrame.POLLING_LOOP_TYPE_A));
                    frames.add(createFrame(PollingFrame.POLLING_LOOP_TYPE_OFF));
                    frames.add(createFrame(PollingFrame.POLLING_LOOP_TYPE_ON));
                    frames.add(createFrame(PollingFrame.POLLING_LOOP_TYPE_A));
                    frames.add(createFrame(PollingFrame.POLLING_LOOP_TYPE_OFF));
                    ensurePreferredService(WalletRoleTestUtils.WALLET_HOLDER_SERVICE_DESC);
                    notifyPollingLoopAndWait(new ArrayList<PollingFrame>(frames),
                            WalletRoleTestUtils.getWalletRoleHolderService().getClassName());
                    adapter.notifyHceDeactivated();
                });
    }

    @Test
    @RequiresFlagsEnabled(android.permission.flags.Flags.FLAG_WALLET_ROLE_ENABLED)
    public void testCustomFrameToCustomInTwoFullLoops() {
        assumeVsrApiGreaterThanUdc();
        WalletRoleTestUtils.runWithRole(mContext, WalletRoleTestUtils.WALLET_HOLDER_PACKAGE_NAME,
                () -> {
                    NfcAdapter adapter = NfcAdapter.getDefaultAdapter(mContext);
                    adapter.notifyHceDeactivated();
                    CardEmulation cardEmulation = CardEmulation.getInstance(adapter);
                    ComponentName customServiceName = new ComponentName(mContext,
                            CustomHostApduService.class);
                    String testName = new Object() {
                    }.getClass().getEnclosingMethod().getName();
                    String annotationStringHex = HexFormat.of().toHexDigits(testName.hashCode());
                    assertTrue(cardEmulation.registerPollingLoopFilterForService(
                            customServiceName,
                            annotationStringHex, false));
                    ArrayList<PollingFrame> frames = new ArrayList<PollingFrame>(6);
                    frames.add(createFrame(PollingFrame.POLLING_LOOP_TYPE_ON));
                    frames.add(createFrame(PollingFrame.POLLING_LOOP_TYPE_A));
                    frames.add(createFrameWithData(PollingFrame.POLLING_LOOP_TYPE_UNKNOWN,
                            HexFormat.of().parseHex(annotationStringHex)));
                    frames.add(createFrame(PollingFrame.POLLING_LOOP_TYPE_OFF));
                    frames.add(createFrame(PollingFrame.POLLING_LOOP_TYPE_ON));
                    frames.add(createFrame(PollingFrame.POLLING_LOOP_TYPE_A));
                    frames.add(createFrameWithData(PollingFrame.POLLING_LOOP_TYPE_UNKNOWN,
                            HexFormat.of().parseHex(annotationStringHex)));
                    frames.add(createFrame(PollingFrame.POLLING_LOOP_TYPE_OFF));
                    ensurePreferredService(WalletRoleTestUtils.WALLET_HOLDER_SERVICE_DESC);
                    // Only the frames matching the filter should be delivered.
                    ArrayList<PollingFrame> framesToReceive = new ArrayList<PollingFrame>(
                        Arrays.asList(frames.get(2), frames.get(6)));
                    notifyPollingLoopAndWait(/* framesToSend = */ frames, framesToReceive,
                        CustomHostApduService.class.getName());
                    assertTrue(cardEmulation.removePollingLoopFilterForService(
                        customServiceName, annotationStringHex));
                    adapter.notifyHceDeactivated();
                });
    }

    class EventPollLoopReceiver extends PollLoopReceiver implements CardEmulation.NfcEventCallback {
        static final int OBSERVE_MODE = 1;
        static final int PREFERRED_SERVICE = 2;
        static final int AID_CONFLICT_OCCURRED = 3;
        static final int AID_NOT_ROUTED = 4;
        static final int NFC_STATE_CHANGED = 5;
        static final int REMOTE_FIELD_CHANGED = 6;
        static final int INTERNAL_ERROR_REPORTED = 7;
        CountDownLatch mLatch = null;
        CountDownLatch[] mLatches = new CountDownLatch[8];


        Context mContext;

        class EventLogEntry {
            String mServicePackageName;
            int mEventType;
            Object mState;

            EventLogEntry(String servicePackageName, int eventType, Object state) {
                mServicePackageName = servicePackageName;
                mEventType = eventType;
                mState = state;
            }
        }

        ArrayList<EventLogEntry> mEvents = new ArrayList<EventLogEntry>();
        ArrayList<EventLogEntry>[] mSpecificEvents = new ArrayList[8];

        EventPollLoopReceiver(Context context) {
          this(context, false);
        }

        EventPollLoopReceiver(Context context, boolean shouldBroadcastToRemoteEventListener) {
            super(new ArrayList<>(), null);
            mContext = context;

            if (shouldBroadcastToRemoteEventListener) {
                broadcastToRemoteEventListener();
            } else {
                ExecutorService pool = Executors.newFixedThreadPool(2);
                NfcAdapter adapter = NfcAdapter.getDefaultAdapter(context);
                CardEmulation cardEmulation = CardEmulation.getInstance(adapter);
                cardEmulation.registerNfcEventCallback(pool, this);
            }
        }

        private void broadcastToRemoteEventListener() {
            CountDownLatch latch = new CountDownLatch(1);

            final Intent intent = new Intent();
            intent.setAction("com.cts.RegisterEventListener");
            intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
            intent.setComponent(
                    new ComponentName("com.android.test.walletroleholder",
                            "com.android.test.walletroleholder.WalletRoleBroadcastReceiver"));

            HandlerThread handlerThread = new HandlerThread("broadcast_receiving_thread");
            handlerThread.start();
            Looper looper = handlerThread.getLooper();
            Handler handler = new Handler(looper);
            mContext.sendOrderedBroadcast(intent, null,
                    new BroadcastReceiver() {
                        @Override
                        public void onReceive(Context context, Intent intent) {
                            latch.countDown();
                        }
                    },
                    handler, Activity.RESULT_OK, null, null);
            try {
                if (!latch.await(5, TimeUnit.SECONDS)) {
                    fail("Did not receive the expected broadcast within the elapsed time");
                }
            } catch (InterruptedException ie) {
            }
            handlerThread.quit();
        }

        void cleanup() {
            NfcAdapter adapter = NfcAdapter.getDefaultAdapter(mContext);
            CardEmulation cardEmulation = CardEmulation.getInstance(adapter);
            cardEmulation.unregisterNfcEventCallback(this);
            final Intent intent = new Intent();
            intent.setAction("com.cts.UnregisterEventListener");
            intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
            intent.setComponent(
                    new ComponentName("com.android.test.walletroleholder",
                            "com.android.test.walletroleholder.WalletRoleBroadcastReceiver"));
            mContext.sendBroadcast(intent);
        }

        @Override
        public void onObserveModeStateChanged(boolean isEnabled) {
            onObserveModeStateChanged(mContext.getPackageName(), isEnabled);
        }

        @Override
        public void onPreferredServiceChanged(boolean isPreferred) {
            onPreferredServiceChanged(mContext.getPackageName(), isPreferred);
        }

        void countDownSpecificEvent(int type) {
            if (mSpecificEvents[type] == null) {
                mSpecificEvents[type] = new ArrayList<EventLogEntry>();
            }
            mSpecificEvents[type].add(mEvents.getLast());
            if (mLatches[type] != null) {
                mLatches[type].countDown();
            }
        }

        @Override
        public void onObserveModeStateChanged(String pkgName, boolean isEnabled) {
            synchronized (this) {
                mEvents.add(new EventLogEntry(pkgName, OBSERVE_MODE, isEnabled));
                countDownSpecificEvent(OBSERVE_MODE);
                if (mLatch != null) {
                    mLatch.countDown();
                }
            }
        }

        @Override
        public void onPreferredServiceChanged(String pkgName, boolean isPreferred) {
            synchronized (this) {
                mEvents.add(new EventLogEntry(pkgName, PREFERRED_SERVICE, isPreferred));
                countDownSpecificEvent(PREFERRED_SERVICE);
                if (mLatch != null) {
                    mLatch.countDown();
                }
            }
        }

        public void onListenersRegistered() {
            if (mLatch != null) {
                mLatch.countDown();
            }
        }

        void setNumEventsToWaitFor(int numEvents) {
            synchronized (this) {
                mLatch = new CountDownLatch(numEvents);
            }
        }

        void setNumEventsToWaitFor(int numEvents, int type) {
            synchronized (this) {
                mLatches[type] = new CountDownLatch(numEvents);
            }
        }

        void waitForEvents() {
            try {
                if (!mLatch.await(5, TimeUnit.SECONDS)) {
                    fail("Did not receive all events within the elapsed time");
                }
            } catch (InterruptedException ie) {
            }
        }

        void waitForEvents(int type) {
            try {
                if (!mLatches[type].await(5, TimeUnit.SECONDS)) {
                    fail("Did not receive all events within the elapsed time");
                }
            } catch (InterruptedException ie) {
            }
        }

        @Override
        public void onAidConflictOccurred(@NonNull String aid) {
            synchronized (this) {
                mEvents.add(new EventLogEntry(mContext.getPackageName(), AID_CONFLICT_OCCURRED,
                        aid));
                if (mLatch != null) {
                    mLatch.countDown();
                }
            }
        }

        @Override
        public void onAidNotRouted(@NonNull String aid) {
            synchronized (this) {
                mEvents.add(new EventLogEntry(mContext.getPackageName(), AID_NOT_ROUTED, aid));
                if (mLatch != null) {
                    mLatch.countDown();
                }
            }
        }

        @Override
        public void onNfcStateChanged(int state) {
            synchronized (this) {
                mEvents.add(new EventLogEntry(mContext.getPackageName(), NFC_STATE_CHANGED, state));
                if (mLatch != null) {
                    mLatch.countDown();
                }
            }
        }

        @Override
        public void onRemoteFieldChanged(boolean isDetected) {
            synchronized (this) {
                mEvents.add(
                        new EventLogEntry(
                                mContext.getPackageName(), REMOTE_FIELD_CHANGED, isDetected));
                if (mLatch != null) {
                    mLatch.countDown();
                }
            }
        }

        @Override
        public void onInternalErrorReported(@CardEmulation.NfcInternalErrorType int errorType) {
            synchronized (this) {
                mEvents.add(
                        new EventLogEntry(
                                mContext.getPackageName(), INTERNAL_ERROR_REPORTED, errorType));
                if (mLatch != null) {
                    mLatch.countDown();
                }
            }
        }
    }

    @Test
    @RequiresFlagsEnabled({
        android.nfc.Flags.FLAG_NFC_OBSERVE_MODE,
        android.nfc.Flags.FLAG_NFC_EVENT_LISTENER
    })
    @ApiTest(
            apis = {
                "android.nfc.cardemulation.CardEmulation.NfcEventCallback#onObserveModeStateChanged",
                "android.nfc.cardemulation.CardEmulation.NfcEventCallback#onPreferredServiceChanged"
            })
    public void testEventListener() throws InterruptedException, NoSuchFieldException {
        NfcAdapter adapter = NfcAdapter.getDefaultAdapter(mContext);
        assumeObserveModeSupported(adapter);
        adapter.notifyHceDeactivated();
        final CardEmulation cardEmulation = CardEmulation.getInstance(adapter);
        EventPollLoopReceiver eventPollLoopReceiver = new EventPollLoopReceiver(mContext);
        sCurrentPollLoopReceiver = eventPollLoopReceiver;
        Activity activity = createAndResumeActivity();
        try {
            assertTrue(cardEmulation.setPreferredService(
                            activity, new ComponentName(mContext, CustomHostApduService.class)));
            ensurePreferredService(CustomHostApduService.class);
            eventPollLoopReceiver.setNumEventsToWaitFor(1);
            assertTrue(cardEmulation.setPreferredService(
                            activity, new ComponentName(mContext, CtsMyHostApduService.class)));
            eventPollLoopReceiver.waitForEvents();
            ensurePreferredService(CtsMyHostApduService.class);

            EventPollLoopReceiver.EventLogEntry event = eventPollLoopReceiver.mEvents.getLast();
            assertEquals(CtsMyHostApduService.class.getPackageName(), event.mServicePackageName);
            assertEquals(EventPollLoopReceiver.PREFERRED_SERVICE, event.mEventType);
            assertTrue((boolean)event.mState);

            assertFalse(adapter.isObserveModeEnabled());
            eventPollLoopReceiver.setNumEventsToWaitFor(1);

            assertTrue(adapter.setObserveModeEnabled(true));
            eventPollLoopReceiver.waitForEvents();
            event = eventPollLoopReceiver.mEvents.getLast();
            assertEquals(CtsMyHostApduService.class.getPackageName(), event.mServicePackageName);
            assertEquals(EventPollLoopReceiver.OBSERVE_MODE, event.mEventType);
            assertTrue((boolean)event.mState);
            assertTrue(adapter.isObserveModeEnabled());
            eventPollLoopReceiver.setNumEventsToWaitFor(1);

            assertTrue(adapter.setObserveModeEnabled(false));
            eventPollLoopReceiver.waitForEvents();
            event = eventPollLoopReceiver.mEvents.getLast();
            assertEquals(CtsMyHostApduService.class.getPackageName(), event.mServicePackageName);
            assertEquals(EventPollLoopReceiver.OBSERVE_MODE, event.mEventType);
            assertFalse((boolean)event.mState);
            assertFalse(adapter.isObserveModeEnabled());
            eventPollLoopReceiver.setNumEventsToWaitFor(1);
            assertTrue(cardEmulation.unsetPreferredService(activity));
            eventPollLoopReceiver.waitForEvents();
            event = eventPollLoopReceiver.mEvents.getLast();
            assertEquals(CtsMyHostApduService.class.getPackageName(), event.mServicePackageName);
            assertEquals(EventPollLoopReceiver.PREFERRED_SERVICE, event.mEventType);
            assertFalse((boolean)event.mState);
        } finally {
            cardEmulation.unsetPreferredService(activity);
            activity.finish();
            sCurrentPollLoopReceiver = null;
            adapter.notifyHceDeactivated();
            eventPollLoopReceiver.cleanup();
        }
    }

    @Test
    @RequiresFlagsEnabled({
        android.nfc.Flags.FLAG_NFC_OBSERVE_MODE,
        android.nfc.Flags.FLAG_NFC_EVENT_LISTENER,
        android.permission.flags.Flags.FLAG_WALLET_ROLE_ENABLED
    })
    @ApiTest(
            apis = {
                "android.nfc.cardemulation.CardEmulation.NfcEventCallback#onObserveModeStateChanged",
                "android.nfc.cardemulation.CardEmulation.NfcEventCallback#onPreferredServiceChanged"
            })
    public void testEventListener_WalletHolderToForegroundAndBack() throws InterruptedException {
        NfcAdapter adapter = NfcAdapter.getDefaultAdapter(mContext);
        assumeObserveModeSupported(adapter);
        adapter.notifyHceDeactivated();
        final CardEmulation cardEmulation = CardEmulation.getInstance(adapter);
        EventPollLoopReceiver eventPollLoopReceiver = new EventPollLoopReceiver(mContext);
        sCurrentPollLoopReceiver = eventPollLoopReceiver;
        EventPollLoopReceiver walletRolePollLoopReceiver =
                new EventPollLoopReceiver(mContext, true);
        sWalletRolePollLoopReceiver = walletRolePollLoopReceiver;

        final int startingEvents = eventPollLoopReceiver.mEvents.size();
        eventPollLoopReceiver.setNumEventsToWaitFor(1);

        WalletRoleTestUtils.runWithRole(
                mContext,
                WalletRoleTestUtils.WALLET_HOLDER_PACKAGE_NAME,
                () -> {
                    eventPollLoopReceiver.waitForEvents();
                    assertTrue("Didn't receive any events",
                            startingEvents < eventPollLoopReceiver.mEvents.size());
                    int numEvents = eventPollLoopReceiver.mEvents.size();
                    int numWalletEvents =
                            walletRolePollLoopReceiver.mEvents.size();
                    Activity activity = createAndResumeActivity();

                    eventPollLoopReceiver.setNumEventsToWaitFor(1);
                    walletRolePollLoopReceiver.setNumEventsToWaitFor(1);
                    assertTrue(cardEmulation.setPreferredService(
                                    activity,
                                    new ComponentName(mContext, CtsMyHostApduService.class)));

                    try {
                        eventPollLoopReceiver.waitForEvents();
                        walletRolePollLoopReceiver.waitForEvents();
                        assertTrue("Didn't receive event",
                                numEvents < eventPollLoopReceiver.mEvents.size());
                        assertTrue("Didn't receive event",
                                numWalletEvents < walletRolePollLoopReceiver.mEvents.size());

                        EventPollLoopReceiver.EventLogEntry lostEvent =
                                walletRolePollLoopReceiver.mEvents.getLast();
                        EventPollLoopReceiver.EventLogEntry gainedEvent =
                                eventPollLoopReceiver.mEvents.getLast();

                        assertEquals(WALLET_HOLDER_PACKAGE_NAME, lostEvent.mServicePackageName);
                        assertEquals(EventPollLoopReceiver.PREFERRED_SERVICE, lostEvent.mEventType);
                        assertFalse((boolean)lostEvent.mState);

                        assertEquals(
                                CtsMyHostApduService.class.getPackageName(),
                                gainedEvent.mServicePackageName);
                        assertEquals(
                                EventPollLoopReceiver.PREFERRED_SERVICE, gainedEvent.mEventType);
                        assertTrue((boolean)gainedEvent.mState);

                        assertFalse(adapter.isObserveModeEnabled());
                        eventPollLoopReceiver.setNumEventsToWaitFor(1);
                        assertTrue(adapter.setObserveModeEnabled(true));
                        eventPollLoopReceiver.waitForEvents();
                        EventPollLoopReceiver.EventLogEntry event =
                                eventPollLoopReceiver.mEvents.getLast();
                        assertEquals(CtsMyHostApduService.class.getPackageName(),
                                event.mServicePackageName);
                        assertEquals(EventPollLoopReceiver.OBSERVE_MODE, event.mEventType);
                        assertTrue((boolean)event.mState);
                        assertTrue(adapter.isObserveModeEnabled());

                        eventPollLoopReceiver.setNumEventsToWaitFor(1,
                                EventPollLoopReceiver.OBSERVE_MODE);
                        assertTrue(adapter.setObserveModeEnabled(false));
                        eventPollLoopReceiver.waitForEvents(EventPollLoopReceiver.OBSERVE_MODE);
                        event = eventPollLoopReceiver
                                .mSpecificEvents[EventPollLoopReceiver.OBSERVE_MODE].getLast();
                        assertEquals(CtsMyHostApduService.class.getPackageName(),
                                event.mServicePackageName);
                        assertEquals(EventPollLoopReceiver.OBSERVE_MODE, event.mEventType);
                        assertFalse((boolean)event.mState);
                        assertFalse(adapter.isObserveModeEnabled());
                        numEvents = eventPollLoopReceiver.mEvents.size();
                        numWalletEvents =
                        walletRolePollLoopReceiver
                                .mSpecificEvents[EventPollLoopReceiver.PREFERRED_SERVICE].size();
                        eventPollLoopReceiver.setNumEventsToWaitFor(1);
                        walletRolePollLoopReceiver
                                .setNumEventsToWaitFor(1, EventPollLoopReceiver.PREFERRED_SERVICE);
                        assertTrue(cardEmulation.unsetPreferredService(activity));
                        eventPollLoopReceiver.waitForEvents();
                        walletRolePollLoopReceiver
                                .waitForEvents(EventPollLoopReceiver.PREFERRED_SERVICE);
                        assertTrue("Didn't receive event",
                                numEvents < eventPollLoopReceiver.mEvents.size());
                        assertTrue("Didn't receive event",
                                numWalletEvents < walletRolePollLoopReceiver
                                        .mSpecificEvents[EventPollLoopReceiver.PREFERRED_SERVICE]
                                                .size());

                        gainedEvent = walletRolePollLoopReceiver
                                .mSpecificEvents[EventPollLoopReceiver.PREFERRED_SERVICE].getLast();
                        lostEvent = eventPollLoopReceiver.mEvents.getLast();

                        assertEquals(CtsMyHostApduService.class.getPackageName(),
                                lostEvent.mServicePackageName);
                        assertEquals(EventPollLoopReceiver.PREFERRED_SERVICE, lostEvent.mEventType);
                        assertFalse((boolean)lostEvent.mState);

                        assertEquals(WALLET_HOLDER_PACKAGE_NAME, gainedEvent.mServicePackageName);
                        assertEquals(
                            EventPollLoopReceiver.PREFERRED_SERVICE, gainedEvent.mEventType);
                    } finally {
                        if (activity != null) {
                            cardEmulation.unsetPreferredService(activity);
                            activity.finish();
                        }
                        sCurrentPollLoopReceiver = null;
                        adapter.notifyHceDeactivated();
                    }
                });
    }

    private void runAndWaitForNfcAdapterStateChange(Runnable runnable, int desiredState)
            throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        mContext.registerReceiver(
                new BroadcastReceiver() {
                    public void onReceive(Context context, Intent intent) {
                        if (intent.getIntExtra(NfcAdapter.EXTRA_ADAPTER_STATE, -1)
                                == desiredState) {
                            latch.countDown();
                        }
                    }
                },
                new IntentFilter(NfcAdapter.ACTION_ADAPTER_STATE_CHANGED));
        runnable.run();
        latch.await(10, TimeUnit.SECONDS);
    }

    @Test
    @RequiresFlagsEnabled({android.nfc.Flags.FLAG_NFC_EVENT_LISTENER})
    @ApiTest(
        apis = {
            "android.nfc.cardemulation.CardEmulation.NfcEventCallback#onNfcStateChanged"
        })
    public void testEventListener_stateChange() throws InterruptedException {
        NfcAdapter adapter = NfcAdapter.getDefaultAdapter(mContext);
        adapter.notifyHceDeactivated();
        EventPollLoopReceiver eventPollLoopReceiver = new EventPollLoopReceiver(mContext, false);
        sCurrentPollLoopReceiver = eventPollLoopReceiver;
        Activity activity = createAndResumeActivity();
        try {
            eventPollLoopReceiver.setNumEventsToWaitFor(2);

            runAndWaitForNfcAdapterStateChange(
                    () -> {
                        assertTrue(adapter.disable());
                    },
                    NfcAdapter.STATE_OFF);

            eventPollLoopReceiver.waitForEvents();
            assertFalse(adapter.isEnabled());
            EventPollLoopReceiver.EventLogEntry event = eventPollLoopReceiver.mEvents.getLast();
            assertEquals(EventPollLoopReceiver.NFC_STATE_CHANGED, event.mEventType);
            assertEquals(NfcAdapter.STATE_OFF, event.mState);

            eventPollLoopReceiver.setNumEventsToWaitFor(2);

            runAndWaitForNfcAdapterStateChange(
                    () -> {
                        assertTrue(adapter.enable());
                    },
                    NfcAdapter.STATE_ON);

            eventPollLoopReceiver.waitForEvents();
            assertTrue(adapter.isEnabled());
            event = eventPollLoopReceiver.mEvents.getLast();
            assertEquals(EventPollLoopReceiver.NFC_STATE_CHANGED, event.mEventType);
            assertEquals(NfcAdapter.STATE_ON, event.mState);
        } finally {
            adapter.enable();
            activity.finish();
            sCurrentPollLoopReceiver = null;
            adapter.notifyHceDeactivated();
            eventPollLoopReceiver.cleanup();
        }
    }

    @Test
    @RequiresFlagsEnabled({android.nfc.Flags.FLAG_NFC_EVENT_LISTENER})
    @ApiTest(apis = {
            "android.nfc.cardemulation.CardEmulation.NfcEventCallback#onInternalErrorReported"
    })
    public void testEventListener_hardwareError() throws InterruptedException {
        NfcAdapter adapter = NfcAdapter.getDefaultAdapter(mContext);
        adapter.notifyHceDeactivated();
        CardEmulation cardEmulation = CardEmulation.getInstance(adapter);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        class InternalErrorCallback implements CardEmulation.NfcEventCallback {
            final CountDownLatch mErrorLatch = new CountDownLatch(1);
            final CountDownLatch mStateOnLatch = new CountDownLatch(1);
            int mErrorType = -1;

            @Override
            public void onNfcStateChanged(int state) {
                if (state == NfcAdapter.STATE_ON && mStateOnLatch != null) {
                    mStateOnLatch.countDown();
                }
            }

            @Override
            public void onInternalErrorReported(@CardEmulation.NfcInternalErrorType int errorType) {
                synchronized (this) {
                    mErrorType = errorType;
                    mErrorLatch.countDown();
                }
            }
        }
        InternalErrorCallback callback = new InternalErrorCallback();
        cardEmulation.registerNfcEventCallback(pool, callback);
        Activity activity = createAndResumeActivity();
        IntentFilter filter = new IntentFilter(NfcAdapter.ACTION_ADAPTER_STATE_CHANGED);
        CountDownLatch adapterStateLatch = new CountDownLatch(1);
        BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (intent.getAction() == NfcAdapter.ACTION_ADAPTER_STATE_CHANGED
                            && intent.hasExtra(NfcAdapter.EXTRA_ADAPTER_STATE)
                            && intent.getIntExtra(NfcAdapter.EXTRA_ADAPTER_STATE, 0)
                                    == NfcAdapter.STATE_ON) {
                        adapterStateLatch.countDown();
                    }
                }
            };
        mContext.registerReceiver(receiver, filter);
        try {
            /* nfc_ncif_proc_proprietary_rsp() marks the data response for this GID
             * and OID as not a VS response, so this will cause a hardware error */
            adapter.sendVendorNciMessage(0x00, 0x03, 0x00, new byte[0]);
            if (!callback.mErrorLatch.await(5, TimeUnit.SECONDS)) {
                fail("Did not receive internal error event within the elapsed time");
            }
            // ToDo: can we query the recovery_option from the NfcConfig to make sure
            // the error matches the config?
            switch (callback.mErrorType) {
                case CardEmulation.NFC_INTERNAL_ERROR_COMMAND_TIMEOUT:
                {
                    // A timeout error indicates that we will crash the NFC service and restart it.
                    // Give the adapter state a chance to bubble up.
                    Thread.currentThread().sleep(300);

                    // The NFC service has died, so we should wait for it to come back up.
                    if (!adapterStateLatch.await(20, TimeUnit.SECONDS)) {
                        fail("NFC service did not come back up within the elapsed time");
                    }

                    assertEquals(adapter.getAdapterState(), NfcAdapter.STATE_ON);
                    assertTrue(NfcUtils.enableNfc(adapter, mContext));
                    assertTrue(cardEmulation.setPreferredService(
                                    activity, new ComponentName(mContext,
                                    CustomHostApduService.class)));
                }
                break;
                case CardEmulation.NFC_INTERNAL_ERROR_NFC_HARDWARE_ERROR:
                    // If the recovery option config is set to 1, we will reset the NFC service and
                    // send a hardware error. Wait for the adapter to come back up to prevent
                    // other tests from failing.
                    if (!adapterStateLatch.await(20, TimeUnit.SECONDS)) {
                        fail("NFC service did not come back up within the elapsed time");
                    }
                    break;
                default:
                    fail("Expected a hardware error or timeout error but got: "
                                    + callback.mErrorType);
            }
        } finally {
            mContext.unregisterReceiver(receiver);
            activity.finish();
            adapter.notifyHceDeactivated();
            cardEmulation.unregisterNfcEventCallback(callback);
        }
    }

    @Test
    public void testTypeAPollingLoopToForeground() {
        assumeVsrApiGreaterThanUdc();
        NfcAdapter adapter = NfcAdapter.getDefaultAdapter(mContext);
        adapter.notifyHceDeactivated();
        Activity activity = createAndResumeActivity();
        final CardEmulation cardEmulation = CardEmulation.getInstance(adapter);
        try {
            assertTrue(cardEmulation.setPreferredService(activity,
                    new ComponentName(mContext,
                            CtsMyHostApduService.class)));
            ArrayList<PollingFrame> frames = new ArrayList<PollingFrame>(6);
            frames.add(createFrame(PollingFrame.POLLING_LOOP_TYPE_ON));
            frames.add(createFrame(PollingFrame.POLLING_LOOP_TYPE_A));
            frames.add(createFrame(PollingFrame.POLLING_LOOP_TYPE_OFF));
            frames.add(createFrame(PollingFrame.POLLING_LOOP_TYPE_ON));
            frames.add(createFrame(PollingFrame.POLLING_LOOP_TYPE_A));
            frames.add(createFrame(PollingFrame.POLLING_LOOP_TYPE_OFF));
            ensurePreferredService(CtsMyHostApduService.class);
            notifyPollingLoopAndWait(frames, CtsMyHostApduService.class.getName());
        } finally {
            assertTrue(cardEmulation.unsetPreferredService(activity));
            activity.finish();
            adapter.notifyHceDeactivated();
        }
    }

    @Test
    public void testSetShouldDefaultToObserveModeShouldDefaultToObserveModeDynamic()
            throws InterruptedException {
        NfcAdapter adapter = NfcAdapter.getDefaultAdapter(mContext);
        assumeObserveModeSupported(adapter);
        adapter.notifyHceDeactivated();
        Activity activity = createAndResumeActivity();
        final CardEmulation cardEmulation = CardEmulation.getInstance(adapter);
        try {
            ComponentName backgroundService =
                    new ComponentName(mContext, BackgroundHostApduService.class);
            assertTrue(cardEmulation.setShouldDefaultToObserveModeForService(
                            backgroundService, false));

            assertTrue(cardEmulation.setPreferredService(activity, backgroundService));
            ensurePreferredService(BackgroundHostApduService.class);

            assertFalse(adapter.isObserveModeEnabled());
            assertTrue(
                cardEmulation.setShouldDefaultToObserveModeForService(backgroundService, true));
            // Observe mode is set asynchronously, so just wait a bit to let it happen.
            try {
                CommonTestUtils.waitUntil(
                        "Observe mode hasn't been set", 1, () -> adapter.isObserveModeEnabled());
            } catch (InterruptedException|AssertionError e) {
            }
            assertTrue(adapter.isObserveModeEnabled());
        } finally {
            assertTrue(cardEmulation.unsetPreferredService(activity));
            ComponentName backgroundService =
                    new ComponentName(mContext, BackgroundHostApduService.class);
            assertTrue(cardEmulation.setShouldDefaultToObserveModeForService(
                            backgroundService, false));
            activity.finish();
            adapter.notifyHceDeactivated();
        }
    }

    @Test
    public void testSetShouldDefaultToObserveModeFalseShouldNotDefaultToObserveMode()
            throws InterruptedException {
        NfcAdapter adapter = NfcAdapter.getDefaultAdapter(mContext);
        assumeObserveModeSupported(adapter);
        adapter.notifyHceDeactivated();
        Activity activity = createAndResumeActivity();
        final CardEmulation cardEmulation = CardEmulation.getInstance(adapter);
        try {
            ComponentName ctsService = new ComponentName(mContext, CtsMyHostApduService.class);
            assertTrue(cardEmulation.setPreferredService(activity, ctsService));
            ensurePreferredService(CtsMyHostApduService.class);

            assertFalse(adapter.isObserveModeEnabled());
        } finally {
            assertTrue(cardEmulation.unsetPreferredService(activity));
            activity.finish();
            adapter.notifyHceDeactivated();
        }
    }

    @Test
    public void testSetShouldDefaultToObserveModeShouldDefaultToObserveMode()
            throws InterruptedException {
        NfcAdapter adapter = NfcAdapter.getDefaultAdapter(mContext);
        assumeObserveModeSupported(adapter);
        adapter.notifyHceDeactivated();
        Activity activity = createAndResumeActivity();
        final CardEmulation cardEmulation = CardEmulation.getInstance(adapter);
        try {
            ComponentName backgroundService =
                    new ComponentName(mContext, BackgroundHostApduService.class);
            assertTrue(cardEmulation.setShouldDefaultToObserveModeForService(
                            backgroundService, true));
            assertTrue(cardEmulation.setPreferredService(activity, backgroundService));
            ensurePreferredService(BackgroundHostApduService.class);

            assertTrue(adapter.isObserveModeEnabled());
        } finally {
            assertTrue(cardEmulation.unsetPreferredService(activity));
            activity.finish();
            adapter.notifyHceDeactivated();
        }
    }

    @Test
    public void testSetShouldDefaultToObserveModeFalseShouldNotDefaultToObserveModeOffHost()
            throws InterruptedException {
        NfcAdapter adapter = NfcAdapter.getDefaultAdapter(mContext);
        assumeObserveModeSupported(adapter);
        adapter.notifyHceDeactivated();
        Activity activity = createAndResumeActivity();
        final CardEmulation cardEmulation = CardEmulation.getInstance(adapter);
        try {
            ComponentName ctsService = new ComponentName(mContext, CtsMyOffHostApduService.class);
            assertTrue(cardEmulation.setPreferredService(activity, ctsService));
            ensurePreferredService(CtsMyOffHostApduService.class);

            assertFalse(adapter.isObserveModeEnabled());
        } finally {
            assertTrue(cardEmulation.unsetPreferredService(activity));
            activity.finish();
            adapter.notifyHceDeactivated();
        }
    }

    @Test
    public void testSetShouldDefaultToObserveModeShouldDefaultToObserveModeOffHost()
            throws InterruptedException {
        NfcAdapter adapter = NfcAdapter.getDefaultAdapter(mContext);
        assumeObserveModeSupported(adapter);
        adapter.notifyHceDeactivated();
        Activity activity = createAndResumeActivity();
        final CardEmulation cardEmulation = CardEmulation.getInstance(adapter);
        try {
            ComponentName offhostService =
                    new ComponentName(mContext, CtsMyOffHostDefaultToObserveApduService.class);
            assertTrue(cardEmulation.setPreferredService(activity, offhostService));
            ensurePreferredService(CtsMyOffHostDefaultToObserveApduService.class);

            assertTrue(adapter.isObserveModeEnabled());
        } finally {
            assertTrue(cardEmulation.unsetPreferredService(activity));
            activity.finish();
            adapter.notifyHceDeactivated();
        }
    }

    @Test
    public void testTypeAOneLoopPollingLoopToForeground() {
        assumeVsrApiGreaterThanUdc();
        NfcAdapter adapter = NfcAdapter.getDefaultAdapter(mContext);
        adapter.notifyHceDeactivated();
        Activity activity = createAndResumeActivity();
        final CardEmulation cardEmulation = CardEmulation.getInstance(adapter);
        try {
            assertTrue(cardEmulation.setPreferredService(activity,
                    new ComponentName(mContext,
                            CtsMyHostApduService.class)));
            ArrayList<PollingFrame> frames = new ArrayList<PollingFrame>(4);
            frames.add(createFrame(PollingFrame.POLLING_LOOP_TYPE_ON));
            frames.add(createFrame(PollingFrame.POLLING_LOOP_TYPE_A));
            frames.add(createFrame(PollingFrame.POLLING_LOOP_TYPE_B));
            frames.add(createFrame(PollingFrame.POLLING_LOOP_TYPE_OFF));
            ensurePreferredService(CtsMyHostApduService.class);
            sCurrentPollLoopReceiver = new PollLoopReceiver(new ArrayList<PollingFrame>(0), null);
            for (PollingFrame frame : frames) {
                adapter.notifyPollingLoop(frame);
            }
            synchronized (sCurrentPollLoopReceiver) {
                try {
                    sCurrentPollLoopReceiver.wait(5000);
                } catch (InterruptedException ie) {
                    assertNull(ie);
                }
            }
            sCurrentPollLoopReceiver.test();
        } finally {
            sCurrentPollLoopReceiver = null;
            cardEmulation.unsetPreferredService(activity);
            activity.finish();
            adapter.notifyHceDeactivated();
        }
    }

    @Test
    @RequiresFlagsDisabled(android.permission.flags.Flags.FLAG_WALLET_ROLE_ENABLED)
    public void testTypeABNoOffPollingLoopToDefault() {
        assumeVsrApiGreaterThanUdc();
        ComponentName originalDefault = null;
        NfcAdapter adapter = NfcAdapter.getDefaultAdapter(mContext);
        adapter.notifyHceDeactivated();
        try {
            originalDefault = setDefaultPaymentService(CustomHostApduService.class);
            ArrayList<PollingFrame> frames = new ArrayList<PollingFrame>(7);
            frames.add(createFrame(PollingFrame.POLLING_LOOP_TYPE_ON));
            frames.add(createFrame(PollingFrame.POLLING_LOOP_TYPE_A));
            frames.add(createFrame(PollingFrame.POLLING_LOOP_TYPE_B));
            frames.add(createFrame(PollingFrame.POLLING_LOOP_TYPE_A));
            frames.add(createFrame(PollingFrame.POLLING_LOOP_TYPE_B));
            frames.add(createFrame(PollingFrame.POLLING_LOOP_TYPE_A));
            frames.add(createFrame(PollingFrame.POLLING_LOOP_TYPE_B));
            ensurePreferredService(CustomHostApduService.class);
            notifyPollingLoopAndWait(new ArrayList<PollingFrame>(frames),
                    CustomHostApduService.class.getName());
        } finally {
            setDefaultPaymentService(originalDefault);
            adapter.notifyHceDeactivated();
        }
    }

    @Test
    @RequiresFlagsEnabled(android.permission.flags.Flags.FLAG_WALLET_ROLE_ENABLED)
    public void testTypeAPollingLoopToForegroundWithWalletHolder() {
        assumeVsrApiGreaterThanUdc();
        NfcAdapter adapter = NfcAdapter.getDefaultAdapter(mContext);
        adapter.notifyHceDeactivated();
        Activity activity = createAndResumeActivity();
        final CardEmulation cardEmulation = CardEmulation.getInstance(adapter);
        WalletRoleTestUtils.runWithRole(mContext, WalletRoleTestUtils.WALLET_HOLDER_PACKAGE_NAME,
                () -> {
                    assertTrue(cardEmulation.setPreferredService(activity,
                            new ComponentName(mContext,
                                    CtsMyHostApduService.class)));
                    ArrayList<PollingFrame> frames = new ArrayList<PollingFrame>(6);
                    frames.add(createFrame(PollingFrame.POLLING_LOOP_TYPE_ON));
                    frames.add(createFrame(PollingFrame.POLLING_LOOP_TYPE_A));
                    frames.add(createFrame(PollingFrame.POLLING_LOOP_TYPE_OFF));
                    frames.add(createFrame(PollingFrame.POLLING_LOOP_TYPE_ON));
                    frames.add(createFrame(PollingFrame.POLLING_LOOP_TYPE_A));
                    frames.add(createFrame(PollingFrame.POLLING_LOOP_TYPE_OFF));
                    ensurePreferredService(CtsMyHostApduService.class);
                    notifyPollingLoopAndWait(frames, CtsMyHostApduService.class.getName());
                    assertTrue(cardEmulation.unsetPreferredService(activity));
                    activity.finish();
                    adapter.notifyHceDeactivated();
                });
    }

    void ensurePreferredService(Class serviceClass) {
        ensurePreferredService(serviceClass, mContext);
    }

    static int getResIdForServiceClass(Class serviceClass) {
        if (CtsMyHostApduService.class.equals(serviceClass)) {
            return R.string.CtsPaymentService;
        } else if (CustomHostApduService.class.equals(serviceClass)) {
            return R.string.CtsCustomPaymentService;
        } else if (BackgroundHostApduService.class.equals(serviceClass)) {
            return R.string.CtsBackgroundPaymentService;
        } else if (CtsMyOffHostApduService.class.equals(serviceClass)) {
            return R.string.CtsOffHostPaymentService;
        } else if (CtsMyOffHostDefaultToObserveApduService.class.equals(serviceClass)) {
            return R.string.CtsOffHostDefaultToObservePaymentService;
        } else {
            throw new IllegalArgumentException("no mapping from class to description string");
        }
    }

    static void ensurePreferredService(Class serviceClass, Context context) {
        NfcAdapter adapter = NfcAdapter.getDefaultAdapter(context);
        final CardEmulation cardEmulation = CardEmulation.getInstance(adapter);
        int resId = getResIdForServiceClass(serviceClass);
        final String desc = context.getResources().getString(resId);
        DefaultPaymentProviderTestUtils.ensurePreferredService(desc, context);
    }

    void ensurePreferredService(String serviceDesc) {
        DefaultPaymentProviderTestUtils.ensurePreferredService(serviceDesc, mContext);
    }

    @Test
    public void testTwoCustomPollingLoopToPreferredCustomAndBackgroundDynamic() {
        assumeVsrApiGreaterThanUdc();
        NfcAdapter adapter = NfcAdapter.getDefaultAdapter(mContext);
        assumeObserveModeSupported(adapter);
        adapter.notifyHceDeactivated();
        CardEmulation cardEmulation = CardEmulation.getInstance(adapter);
        Activity activity = createAndResumeActivity();
        try {
            assertTrue(cardEmulation.setPreferredService(activity,
                    new ComponentName(mContext, CustomHostApduService.class)));

            ensurePreferredService(CustomHostApduService.class);
            assertTrue(adapter.setObserveModeEnabled(true));

            ComponentName backgroundServiceName = new ComponentName(mContext,
                    BackgroundHostApduService.class);
            String testName = new Object() {
            }.getClass().getEnclosingMethod().getName();
            String annotationStringHex1 =
                    HexFormat.of().toHexDigits((testName + "background").hashCode());
            assertTrue(cardEmulation.registerPollingLoopFilterForService(
                    backgroundServiceName, annotationStringHex1, false));
            ArrayList<PollingFrame> frames = new ArrayList<PollingFrame>(2);
            frames.add(createFrameWithData(PollingFrame.POLLING_LOOP_TYPE_UNKNOWN,
                    HexFormat.of().parseHex(annotationStringHex1)));

            ComponentName customServiceName = new ComponentName(mContext,
                    CustomHostApduService.class);

            String annotationStringHex2 =
                    HexFormat.of().toHexDigits((testName + "custom").hashCode());
            assertTrue(cardEmulation.registerPollingLoopFilterForService(
                    customServiceName, annotationStringHex2, false));
            frames.add(createFrameWithData(PollingFrame.POLLING_LOOP_TYPE_UNKNOWN,
                    HexFormat.of().parseHex(annotationStringHex2)));

            notifyPollingLoopAndWait(frames, /* serviceName = */ null);
            assertTrue(cardEmulation.removePollingLoopFilterForService(
                backgroundServiceName, annotationStringHex1));
            assertTrue(cardEmulation.removePollingLoopFilterForService(
                customServiceName, annotationStringHex2));
        } finally {
            cardEmulation.unsetPreferredService(activity);
            activity.finish();
            adapter.notifyHceDeactivated();
        }
    }
    @Test
    public void testTwoCustomPollingLoopToCustomAndBackgroundDynamic() {
        assumeVsrApiGreaterThanUdc();
        NfcAdapter adapter = NfcAdapter.getDefaultAdapter(mContext);
        assumeObserveModeSupported(adapter);
        adapter.notifyHceDeactivated();
        CardEmulation cardEmulation = CardEmulation.getInstance(adapter);
        Activity activity = createAndResumeActivity();
        try {
            assertTrue(cardEmulation.setPreferredService(activity,
                    new ComponentName(mContext, CtsMyHostApduService.class)));

            ensurePreferredService(CtsMyHostApduService.class);
            assertTrue(adapter.setObserveModeEnabled(true));

            ComponentName backgroundServiceName = new ComponentName(mContext,
                    BackgroundHostApduService.class);
            String testName = new Object() {
            }.getClass().getEnclosingMethod().getName();
            String annotationStringHex1 =
                    HexFormat.of().toHexDigits((testName + "background").hashCode());
            assertTrue(cardEmulation.registerPollingLoopFilterForService(
                    backgroundServiceName, annotationStringHex1, false));
            ArrayList<PollingFrame> frames = new ArrayList<PollingFrame>(2);
            frames.add(createFrameWithData(PollingFrame.POLLING_LOOP_TYPE_UNKNOWN,
                    HexFormat.of().parseHex(annotationStringHex1)));

            ComponentName customServiceName = new ComponentName(mContext,
                    CustomHostApduService.class);

            String annotationStringHex2 =
                    HexFormat.of().toHexDigits((testName + "custom").hashCode());
            assertTrue(cardEmulation.registerPollingLoopFilterForService(
                    customServiceName, annotationStringHex2, false));
            frames.add(createFrameWithData(PollingFrame.POLLING_LOOP_TYPE_UNKNOWN,
                    HexFormat.of().parseHex(annotationStringHex2)));

            notifyPollingLoopAndWait(frames, /* serviceName = */ null);
            assertTrue(cardEmulation.removePollingLoopFilterForService(
                backgroundServiceName, annotationStringHex1));
            assertTrue(cardEmulation.removePollingLoopFilterForService(
                customServiceName, annotationStringHex2));
        } finally {
            cardEmulation.unsetPreferredService(activity);
            activity.finish();
            adapter.notifyHceDeactivated();
        }
    }

    @Test
    public void testCustomPollingLoopToCustomDynamic() {
        NfcAdapter adapter = NfcAdapter.getDefaultAdapter(mContext);
        assumeVsrApiGreaterThanUdc();
        adapter.notifyHceDeactivated();
        CardEmulation cardEmulation = CardEmulation.getInstance(adapter);
        ComponentName customServiceName = new ComponentName(mContext, CustomHostApduService.class);
        String testName = new Object() {
        }.getClass().getEnclosingMethod().getName();
        String annotationStringHex = HexFormat.of().toHexDigits(testName.hashCode());
        assertTrue(cardEmulation.registerPollingLoopFilterForService(customServiceName,
                annotationStringHex, false));
        ArrayList<PollingFrame> frames = new ArrayList<PollingFrame>(1);
        frames.add(createFrameWithData(PollingFrame.POLLING_LOOP_TYPE_UNKNOWN,
                HexFormat.of().parseHex(annotationStringHex)));
        // add additional frame to ensure that only non-matching data is filtered out
        frames.add(createFrameWithData(PollingFrame.POLLING_LOOP_TYPE_UNKNOWN,
                HexFormat.of().parseHex("1234567890")));
        notifyPollingLoopAndWait(/* framesToSend = */ frames,
            /* framesToReceive = */ new ArrayList<PollingFrame>(Arrays.asList(frames.get(0))),
            CustomHostApduService.class.getName());
        assertTrue(cardEmulation.removePollingLoopFilterForService(customServiceName,
            annotationStringHex));
        adapter.notifyHceDeactivated();
    }

    @Test
    public void testCustomPollingLoopToCustomWithPrefixDynamic() {
        assumeVsrApiGreaterThanUdc();
        NfcAdapter adapter = NfcAdapter.getDefaultAdapter(mContext);
        adapter.notifyHceDeactivated();
        CardEmulation cardEmulation = CardEmulation.getInstance(adapter);
        ComponentName customServiceName = new ComponentName(mContext, CustomHostApduService.class);
        String testName = new Object() {
        }.getClass().getEnclosingMethod().getName();
        String annotationStringHexPrefix = HexFormat.of().toHexDigits(testName.hashCode());
        String annotationStringHex = annotationStringHexPrefix + "123456789ABCDF";
        String annotationStringHexPattern = annotationStringHexPrefix + ".*";
        assertTrue(cardEmulation.registerPollingLoopPatternFilterForService(
                customServiceName, annotationStringHexPattern, false));
        ArrayList<PollingFrame> frames = new ArrayList<PollingFrame>(1);
        frames.add(createFrameWithData(PollingFrame.POLLING_LOOP_TYPE_UNKNOWN,
                HexFormat.of().parseHex(annotationStringHex)));
        frames.add(createFrameWithData(PollingFrame.POLLING_LOOP_TYPE_UNKNOWN,
                HexFormat.of().parseHex("123456789ABCDF")));
        notifyPollingLoopAndWait(/* framesToSend = */ frames,
                /* framesToReceive = */ new ArrayList<PollingFrame>(Arrays.asList(frames.get(0))),
                CustomHostApduService.class.getName());
        assertTrue(cardEmulation.removePollingLoopPatternFilterForService(customServiceName,
                annotationStringHexPrefix));
        adapter.notifyHceDeactivated();
    }

    @Test
    @RequiresFlagsDisabled(android.permission.flags.Flags.FLAG_WALLET_ROLE_ENABLED)
    public void testThreeWayConflictPollingLoopToForegroundDynamic() {
        assumeVsrApiGreaterThanUdc();
        ComponentName originalDefault = null;
        NfcAdapter adapter = NfcAdapter.getDefaultAdapter(mContext);
        adapter.notifyHceDeactivated();
        Activity activity = createAndResumeActivity();
        CardEmulation cardEmulation = CardEmulation.getInstance(adapter);
        try {
            originalDefault = setDefaultPaymentService(CustomHostApduService.class);
            ComponentName ctsMyServiceName = new ComponentName(mContext,
                    CtsMyHostApduService.class);
            assertTrue(cardEmulation.setPreferredService(activity, ctsMyServiceName));
            ComponentName customServiceName = new ComponentName(mContext,
                    CustomHostApduService.class);
            ComponentName backgroundServiceName = new ComponentName(mContext,
                    BackgroundHostApduService.class);
            String testName = new Object() {
            }.getClass().getEnclosingMethod().getName();
            String annotationStringHex = HexFormat.of().toHexDigits(testName.hashCode());
            assertTrue(cardEmulation.registerPollingLoopFilterForService(customServiceName,
                    annotationStringHex, false));
            assertTrue(cardEmulation.registerPollingLoopFilterForService(
                    backgroundServiceName, annotationStringHex, false));
            assertTrue(cardEmulation.registerPollingLoopFilterForService(ctsMyServiceName,
                    annotationStringHex, false));
            ArrayList<PollingFrame> frames = new ArrayList<PollingFrame>(1);
            frames.add(createFrameWithData(PollingFrame.POLLING_LOOP_TYPE_UNKNOWN,
                    HexFormat.of().parseHex(annotationStringHex)));
            ensurePreferredService(CtsMyHostApduService.class);
            notifyPollingLoopAndWait(frames, CtsMyHostApduService.class.getName());
        } finally {
            assertTrue(cardEmulation.unsetPreferredService(activity));
            activity.finish();
            setDefaultPaymentService(originalDefault);
            adapter.notifyHceDeactivated();
        }
    }

    @Test
    public void testBackgroundForegroundConflictPollingLoopToForegroundDynamic() {
        assumeVsrApiGreaterThanUdc();
        NfcAdapter adapter = NfcAdapter.getDefaultAdapter(mContext);
        adapter.notifyHceDeactivated();
        CardEmulation cardEmulation = CardEmulation.getInstance(adapter);
        Activity activity = createAndResumeActivity();
        ComponentName ctsServiceName = new ComponentName(mContext,
                CtsMyHostApduService.class);
        try {
            assertTrue(cardEmulation.setPreferredService(activity, ctsServiceName));
            ComponentName backgroundServiceName = new ComponentName(mContext,
                    BackgroundHostApduService.class);
            String testName = new Object() {
            }.getClass().getEnclosingMethod().getName();
            String annotationStringHex = HexFormat.of().toHexDigits(testName.hashCode());
            assertTrue(cardEmulation.registerPollingLoopFilterForService(ctsServiceName,
                    annotationStringHex, false));
            assertTrue(cardEmulation.registerPollingLoopFilterForService(
                    backgroundServiceName, annotationStringHex, false));
            ArrayList<PollingFrame> frames = new ArrayList<PollingFrame>(1);
            frames.add(createFrameWithData(PollingFrame.POLLING_LOOP_TYPE_UNKNOWN,
                    HexFormat.of().parseHex(annotationStringHex)));
            ensurePreferredService(CtsMyHostApduService.class);
            notifyPollingLoopAndWait(frames, CtsMyHostApduService.class.getName());
            assertTrue(cardEmulation.removePollingLoopFilterForService(ctsServiceName,
                    annotationStringHex));
        } finally {
            assertTrue(cardEmulation.unsetPreferredService(activity));
            activity.finish();
            adapter.notifyHceDeactivated();
        }
    }

    @Test
    @RequiresFlagsDisabled(android.permission.flags.Flags.FLAG_WALLET_ROLE_ENABLED)
    public void testBackgroundPaymentConflictPollingLoopToPaymentDynamic() {
        assumeVsrApiGreaterThanUdc();
        NfcAdapter adapter = NfcAdapter.getDefaultAdapter(mContext);
        adapter.notifyHceDeactivated();
        ComponentName originalDefault = null;
        try {
            ComponentName customServiceName = new ComponentName(mContext,
                    CustomHostApduService.class);
            originalDefault = setDefaultPaymentService(customServiceName);
            CardEmulation cardEmulation = CardEmulation.getInstance(adapter);

            assertTrue(cardEmulation.isDefaultServiceForCategory(customServiceName,
                    CardEmulation.CATEGORY_PAYMENT));
            ComponentName backgroundServiceName = new ComponentName(mContext,
                    BackgroundHostApduService.class);
            String testName = new Object() {
            }.getClass().getEnclosingMethod().getName();
            String annotationStringHex = HexFormat.of().toHexDigits(testName.hashCode());
            assertTrue(cardEmulation.registerPollingLoopFilterForService(customServiceName,
                    annotationStringHex, false));
            assertTrue(cardEmulation.registerPollingLoopFilterForService(
                    backgroundServiceName, annotationStringHex, false));
            ArrayList<PollingFrame> frames = new ArrayList<PollingFrame>(1);
            frames.add(createFrameWithData(PollingFrame.POLLING_LOOP_TYPE_UNKNOWN,
                    HexFormat.of().parseHex(annotationStringHex)));
            ensurePreferredService(CustomHostApduService.class);
            notifyPollingLoopAndWait(frames, CustomHostApduService.class.getName());
            assertTrue(cardEmulation.removePollingLoopFilterForService(customServiceName,
                    annotationStringHex));
        } finally {
            setDefaultPaymentService(originalDefault);
            adapter.notifyHceDeactivated();
        }
    }

    @Test
    public void testCustomPollingLoopToCustom() {
        assumeVsrApiGreaterThanUdc();
        NfcAdapter adapter = NfcAdapter.getDefaultAdapter(mContext);
        adapter.notifyHceDeactivated();
        String testName = new Object() {
        }.getClass().getEnclosingMethod().getName();
        String annotationStringHex = HexFormat.of().toHexDigits(testName.hashCode());
        ArrayList<PollingFrame> frames = new ArrayList<PollingFrame>(1);
        frames.add(createFrameWithData(PollingFrame.POLLING_LOOP_TYPE_UNKNOWN,
                HexFormat.of().parseHex(annotationStringHex)));
        notifyPollingLoopAndWait(frames, CustomHostApduService.class.getName());
        adapter.notifyHceDeactivated();
    }

    @Test
    @RequiresFlagsDisabled(android.permission.flags.Flags.FLAG_WALLET_ROLE_ENABLED)
    public void testThreeWayConflictPollingLoopToForeground() {
        assumeVsrApiGreaterThanUdc();
        ComponentName originalDefault = null;
        NfcAdapter adapter = NfcAdapter.getDefaultAdapter(mContext);
        adapter.notifyHceDeactivated();
        Activity activity = createAndResumeActivity();
        CardEmulation cardEmulation = CardEmulation.getInstance(adapter);
        try {
            originalDefault = setDefaultPaymentService(CustomHostApduService.class);
            assertTrue(cardEmulation.setPreferredService(activity,
                    new ComponentName(mContext, CtsMyHostApduService.class)));
            String testName = new Object() {
            }.getClass().getEnclosingMethod().getName();
            String annotationStringHex = HexFormat.of().toHexDigits(testName.hashCode());
            ArrayList<PollingFrame> frames = new ArrayList<PollingFrame>(1);
            frames.add(createFrameWithData(PollingFrame.POLLING_LOOP_TYPE_UNKNOWN,
                    HexFormat.of().parseHex(annotationStringHex)));
            ensurePreferredService(CtsMyHostApduService.class);
            notifyPollingLoopAndWait(frames, CtsMyHostApduService.class.getName());
        } finally {
            assertTrue(cardEmulation.unsetPreferredService(activity));
            activity.finish();
            setDefaultPaymentService(originalDefault);
            adapter.notifyHceDeactivated();
        }
    }

    @Test
    @RequiresFlagsEnabled(android.permission.flags.Flags.FLAG_WALLET_ROLE_ENABLED)
    public void testThreeWayConflictPollingLoopToForegroundWithWalletHolder() {
        assumeVsrApiGreaterThanUdc();
        NfcAdapter adapter = NfcAdapter.getDefaultAdapter(mContext);
        adapter.notifyHceDeactivated();
        Activity activity = createAndResumeActivity();
        CardEmulation cardEmulation = CardEmulation.getInstance(adapter);
        WalletRoleTestUtils.runWithRole(mContext, WalletRoleTestUtils.WALLET_HOLDER_PACKAGE_NAME,
                () -> {
                    assertTrue(cardEmulation.setPreferredService(activity,
                            new ComponentName(mContext, CtsMyHostApduService.class)));
                    String testName = new Object() {
                    }.getClass().getEnclosingMethod().getName();
                    String annotationStringHex = HexFormat.of().toHexDigits(testName.hashCode());
                    ArrayList<PollingFrame> frames = new ArrayList<PollingFrame>(1);
                    frames.add(createFrameWithData(PollingFrame.POLLING_LOOP_TYPE_UNKNOWN,
                            HexFormat.of().parseHex(annotationStringHex)));
                    ensurePreferredService(CtsMyHostApduService.class);
                    notifyPollingLoopAndWait(frames, CtsMyHostApduService.class.getName());
                    assertTrue(cardEmulation.unsetPreferredService(activity));
                    activity.finish();
                    adapter.notifyHceDeactivated();
                });
    }

    @Test
    public void testBackgroundForegroundConflictPollingLoopToForeground() {
        assumeVsrApiGreaterThanUdc();
        NfcAdapter adapter = NfcAdapter.getDefaultAdapter(mContext);
        adapter.notifyHceDeactivated();
        CardEmulation cardEmulation = CardEmulation.getInstance(adapter);
        Activity activity = createAndResumeActivity();
        try {
            assertTrue(cardEmulation.setPreferredService(activity,
                    new ComponentName(mContext, CtsMyHostApduService.class)));
            String testName = new Object() {
            }.getClass().getEnclosingMethod().getName();
            String annotationStringHex = HexFormat.of().toHexDigits(testName.hashCode());
            ArrayList<PollingFrame> frames = new ArrayList<PollingFrame>(1);
            frames.add(createFrameWithData(PollingFrame.POLLING_LOOP_TYPE_UNKNOWN,
                    HexFormat.of().parseHex(annotationStringHex)));
            ensurePreferredService(CtsMyHostApduService.class);
            notifyPollingLoopAndWait(frames, CtsMyHostApduService.class.getName());
        } finally {
            assertTrue(cardEmulation.unsetPreferredService(activity));
            activity.finish();
            adapter.notifyHceDeactivated();
        }
    }

    @Test
    @RequiresFlagsDisabled(android.permission.flags.Flags.FLAG_WALLET_ROLE_ENABLED)
    public void testBackgroundPaymentConflictPollingLoopToPayment() {
        assumeVsrApiGreaterThanUdc();
        NfcAdapter adapter = NfcAdapter.getDefaultAdapter(mContext);
        adapter.notifyHceDeactivated();
        ComponentName originalDefault = null;
        try {
            originalDefault = setDefaultPaymentService(CustomHostApduService.class);
            String testName = new Object() {
            }.getClass().getEnclosingMethod().getName();
            String annotationStringHex = HexFormat.of().toHexDigits(testName.hashCode());
            ArrayList<PollingFrame> frames = new ArrayList<PollingFrame>(1);
            frames.add(createFrameWithData(PollingFrame.POLLING_LOOP_TYPE_UNKNOWN,
                    HexFormat.of().parseHex(annotationStringHex)));
            ensurePreferredService(CustomHostApduService.class);
            notifyPollingLoopAndWait(frames, CustomHostApduService.class.getName());
        } finally {
            setDefaultPaymentService(originalDefault);
            adapter.notifyHceDeactivated();
        }
    }

    @Test
    @RequiresFlagsEnabled({Flags.FLAG_NFC_OBSERVE_MODE,
            android.permission.flags.Flags.FLAG_WALLET_ROLE_ENABLED})
    public void testBackgroundWalletConflictPollingLoopToWallet_walletRoleEnabled() {
        assumeVsrApiGreaterThanUdc();
        runWithRole(mContext, WALLET_HOLDER_PACKAGE_NAME, () -> {
            NfcAdapter adapter = NfcAdapter.getDefaultAdapter(mContext);
            adapter.notifyHceDeactivated();
            String testName = new Object() {
            }.getClass().getEnclosingMethod().getName();
            String annotationStringHex = HexFormat.of().toHexDigits(testName.hashCode());
            ArrayList<PollingFrame> frames = new ArrayList<PollingFrame>(1);
            frames.add(createFrameWithData(PollingFrame.POLLING_LOOP_TYPE_UNKNOWN,
                    HexFormat.of().parseHex(annotationStringHex)));
            ensurePreferredService(WALLET_HOLDER_SERVICE_DESC);
            notifyPollingLoopAndWait(frames, getWalletRoleHolderService().getClassName());
            adapter.notifyHceDeactivated();
        });
    }

    @Test
    @RequiresFlagsEnabled({com.android.nfc.flags.Flags.FLAG_AUTO_DISABLE_OBSERVE_MODE,
                           Flags.FLAG_NFC_OBSERVE_MODE,
                           android.permission.flags.Flags.FLAG_WALLET_ROLE_ENABLED})
    public void testAutoDisableObserveMode() throws Exception {
        assumeVsrApiGreaterThanUdc();
        runWithRole(mContext, CTS_PACKAGE_NAME, () -> {
            NfcAdapter adapter = NfcAdapter.getDefaultAdapter(mContext);
            assertTrue(NfcUtils.enableNfc(adapter, mContext));
            assumeObserveModeSupported(adapter);
            adapter.notifyHceDeactivated();
            String testName = new Object() {
            }.getClass().getEnclosingMethod().getName();
            String annotationStringHex = HexFormat.of().toHexDigits(testName.hashCode());
            ArrayList<PollingFrame> frames = new ArrayList<PollingFrame>(1);
            frames.add(createFrameWithData(PollingFrame.POLLING_LOOP_TYPE_UNKNOWN,
                    HexFormat.of().parseHex(annotationStringHex)));
            final CardEmulation cardEmulation = CardEmulation.getInstance(adapter);
            try {
                ensurePreferredService(CtsMyHostApduService.class);
                assertTrue(adapter.setObserveModeEnabled(true));
                assertTrue(adapter.isObserveModeEnabled());
                List<PollingFrame> receivedFrames =
                        notifyPollingLoopAndWait(frames, CtsMyHostApduService.class.getName());
                assertFalse(receivedFrames.get(0).getTriggeredAutoTransact());
                PollingCheck.check("Observe mode not disabled", 4000,
                        () -> !adapter.isObserveModeEnabled());
                adapter.notifyHceDeactivated();
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            } finally {
                adapter.setObserveModeEnabled(false);
            }
        });
    }

    @Test
    @RequiresFlagsEnabled({com.android.nfc.flags.Flags.FLAG_AUTO_DISABLE_OBSERVE_MODE,
                           Flags.FLAG_NFC_OBSERVE_MODE})
    public void testDontAutoDisableObserveModeInForeground() throws Exception {
        assumeVsrApiGreaterThanUdc();
        NfcAdapter adapter = NfcAdapter.getDefaultAdapter(mContext);
        assertTrue(NfcUtils.enableNfc(adapter, mContext));
        assumeObserveModeSupported(adapter);
        adapter.notifyHceDeactivated();
        String testName = new Object() {
        }.getClass().getEnclosingMethod().getName();
        String annotationStringHex = HexFormat.of().toHexDigits(testName.hashCode());
        ArrayList<PollingFrame> frames = new ArrayList<PollingFrame>(1);
        frames.add(createFrameWithData(PollingFrame.POLLING_LOOP_TYPE_UNKNOWN,
                HexFormat.of().parseHex(annotationStringHex)));
        final CardEmulation cardEmulation = CardEmulation.getInstance(adapter);
        final Activity activity = createAndResumeActivity();
        try {
            assertTrue(cardEmulation.setPreferredService(activity,
                new ComponentName(mContext, CtsMyHostApduService.class)));
            ensurePreferredService(CtsMyHostApduService.class);
            assertTrue(adapter.setObserveModeEnabled(true));
            assertTrue(adapter.isObserveModeEnabled());
            List<PollingFrame> receivedFrames =
                    notifyPollingLoopAndWait(frames, CtsMyHostApduService.class.getName());
            assertFalse(receivedFrames.get(0).getTriggeredAutoTransact());
            Thread.currentThread().sleep(4000);
            assertTrue(adapter.isObserveModeEnabled());
            adapter.notifyHceDeactivated();
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        } finally {
            adapter.setObserveModeEnabled(false);
        }
    }

    @Test
    @RequiresFlagsEnabled({com.android.nfc.flags.Flags.FLAG_AUTO_DISABLE_OBSERVE_MODE,
                           Flags.FLAG_NFC_OBSERVE_MODE})
    public void testDontAutoDisableObserveModeInForegroundTwoServices() throws Exception {
        assumeVsrApiGreaterThanUdc();
        NfcAdapter adapter = NfcAdapter.getDefaultAdapter(mContext);
        assertTrue(NfcUtils.enableNfc(adapter, mContext));
        assumeObserveModeSupported(adapter);
        adapter.notifyHceDeactivated();
        String testName = new Object() {
        }.getClass().getEnclosingMethod().getName();
        final CardEmulation cardEmulation = CardEmulation.getInstance(adapter);
        String annotationStringHex1 = "5cadc10f";
        ArrayList<PollingFrame> frames1 = new ArrayList<PollingFrame>(1);
        frames1.add(createFrameWithData(PollingFrame.POLLING_LOOP_TYPE_UNKNOWN,
                HexFormat.of().parseHex(annotationStringHex1)));
        ComponentName walletServiceName = WalletRoleTestUtils.getWalletRoleHolderService();
        String annotationStringHex2 = HexFormat.of().toHexDigits((testName).hashCode());
        ComponentName ctsComponentName = new ComponentName(mContext, CtsMyHostApduService.class);
        assertTrue(cardEmulation.registerPollingLoopFilterForService(ctsComponentName,
                        annotationStringHex2, false));
        ArrayList<PollingFrame> frames2 = new ArrayList<PollingFrame>(1);
        frames2.add(createFrameWithData(PollingFrame.POLLING_LOOP_TYPE_UNKNOWN,
                        HexFormat.of().parseHex(annotationStringHex2)));
        final Activity activity = createAndResumeActivity();
        try {
            assertTrue(cardEmulation.setPreferredService(activity, ctsComponentName));
            ensurePreferredService(CtsMyHostApduService.class);
            assertTrue(adapter.setObserveModeEnabled(true));
            assertTrue(adapter.isObserveModeEnabled());
            List<PollingFrame> receivedFrames =
                    notifyPollingLoopAndWait(frames1,
                            WalletRoleTestUtils.getWalletRoleHolderService().getClassName());
            assertFalse(receivedFrames.get(0).getTriggeredAutoTransact());
            receivedFrames =
                    notifyPollingLoopAndWait(frames2, CtsMyHostApduService.class.getName());
            assertFalse(receivedFrames.get(0).getTriggeredAutoTransact());
            Thread.currentThread().sleep(5000);
            assertTrue(adapter.isObserveModeEnabled());
            assertTrue(cardEmulation.removePollingLoopFilterForService(ctsComponentName,
                    annotationStringHex2));
            adapter.notifyHceDeactivated();
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        } finally {
            adapter.setObserveModeEnabled(false);
        }
    }

    @Test
    public void testAutoTransact() throws Exception {
        assumeVsrApiGreaterThanUdc();
        NfcAdapter adapter = NfcAdapter.getDefaultAdapter(mContext);
        assertTrue(NfcUtils.enableNfc(adapter, mContext));
        assumeObserveModeSupported(adapter);
        adapter.notifyHceDeactivated();
        final Activity activity = createAndResumeActivity();
        String testName = new Object() {
        }.getClass().getEnclosingMethod().getName();
        String annotationStringHex = HexFormat.of().toHexDigits(testName.hashCode());
        ArrayList<PollingFrame> frames = new ArrayList<PollingFrame>(1);
        frames.add(createFrameWithData(PollingFrame.POLLING_LOOP_TYPE_UNKNOWN,
                HexFormat.of().parseHex(annotationStringHex)));
        final CardEmulation cardEmulation = CardEmulation.getInstance(adapter);
        try {
            assertTrue(cardEmulation.setPreferredService(activity,
                    new ComponentName(mContext, CtsMyHostApduService.class)));
            ensurePreferredService(CtsMyHostApduService.class);
            assertTrue(adapter.setObserveModeEnabled(true));
            assertTrue(adapter.isObserveModeEnabled());
            List<PollingFrame> receivedFrames =
                    notifyPollingLoopAndWait(frames, CustomHostApduService.class.getName());
            assertTrue(receivedFrames.get(0).getTriggeredAutoTransact());
            PollingCheck.check("Observe mode not disabled", 200,
                    () -> !adapter.isObserveModeEnabled());
            adapter.notifyHceDeactivated();
            PollingCheck.check("Observe mode not enabled", 3000, adapter::isObserveModeEnabled);
        } finally {
            adapter.setObserveModeEnabled(false);
            cardEmulation.unsetPreferredService(activity);
            activity.finish();
        }
    }

    @Test
    @RequiresFlagsEnabled(android.permission.flags.Flags.FLAG_WALLET_ROLE_ENABLED)
    public void testAutoTransact_walletRoleEnabled() throws Exception {
        assumeVsrApiGreaterThanUdc();
        runWithRole(mContext, CTS_PACKAGE_NAME, () -> {
            NfcAdapter adapter = NfcAdapter.getDefaultAdapter(mContext);
            assertTrue(NfcUtils.enableNfc(adapter, mContext));
            assumeObserveModeSupported(adapter);
            adapter.notifyHceDeactivated();
            createAndResumeActivity();
            String testName = new Object() {
            }.getClass().getEnclosingMethod().getName();
            String annotationStringHex = HexFormat.of().toHexDigits(testName.hashCode());
            ArrayList<PollingFrame> frames = new ArrayList<PollingFrame>(1);
            frames.add(createFrameWithData(PollingFrame.POLLING_LOOP_TYPE_UNKNOWN,
                    HexFormat.of().parseHex(annotationStringHex)));
            assertTrue(adapter.setObserveModeEnabled(true));
            assertTrue(adapter.isObserveModeEnabled());
            List<PollingFrame> receivedFrames =
                    notifyPollingLoopAndWait(frames, CustomHostApduService.class.getName());
            assertTrue(receivedFrames.get(0).getTriggeredAutoTransact());
            try {
                PollingCheck.check("Observe mode not disabled", 200,
                        () -> !adapter.isObserveModeEnabled());
                adapter.notifyHceDeactivated();
                PollingCheck.check("Observe mode not enabled", 3000, adapter::isObserveModeEnabled);
            } catch (Exception e) {
                throw new RuntimeException(e);
            } finally {
                adapter.setObserveModeEnabled(false);
            }
        });
    }

    @Test
    public void testAutoTransactDynamic() throws Exception {
        assumeVsrApiGreaterThanUdc();
        NfcAdapter adapter = NfcAdapter.getDefaultAdapter(mContext);
        assertTrue(NfcUtils.enableNfc(adapter, mContext));
        assumeObserveModeSupported(adapter);
        adapter.notifyHceDeactivated();
        final Activity activity = createAndResumeActivity();
        String testName = new Object() {
        }.getClass().getEnclosingMethod().getName();
        String annotationStringHex = HexFormat.of().toHexDigits(testName.hashCode());
        CardEmulation cardEmulation = CardEmulation.getInstance(adapter);
        ComponentName customServiceName = new ComponentName(mContext, CustomHostApduService.class);
        assertTrue(cardEmulation.registerPollingLoopFilterForService(customServiceName,
                annotationStringHex, true));
        ArrayList<PollingFrame> frames = new ArrayList<PollingFrame>(1);
        frames.add(createFrameWithData(PollingFrame.POLLING_LOOP_TYPE_UNKNOWN,
                HexFormat.of().parseHex(annotationStringHex)));
        ComponentName ctsComponentName = new ComponentName(mContext, CtsMyHostApduService.class);
        try {
            assertTrue(cardEmulation.setPreferredService(activity, ctsComponentName));
            ensurePreferredService(CtsMyHostApduService.class);
            assertTrue(adapter.setObserveModeEnabled(true));
            assertTrue(adapter.isObserveModeEnabled());
            List<PollingFrame> receivedFrames =
                    notifyPollingLoopAndWait(frames, CustomHostApduService.class.getName());
            assertTrue(receivedFrames.get(0).getTriggeredAutoTransact());
            PollingCheck.check("Observe mode not disabled", 200,
                    () -> !adapter.isObserveModeEnabled());
            assertTrue(cardEmulation.removePollingLoopFilterForService(customServiceName,
                    annotationStringHex));
            adapter.notifyHceDeactivated();
            PollingCheck.check("Observe mode not enabled", 3000, adapter::isObserveModeEnabled);
        } finally {
            adapter.setObserveModeEnabled(false);
            cardEmulation.unsetPreferredService(activity);
            activity.finish();
        }
    }


    @Test
    public void testOffHostAutoTransactDynamic() throws Exception {
        assumeVsrApiGreaterThanUdc();
        NfcAdapter adapter = NfcAdapter.getDefaultAdapter(mContext);
        assertTrue(NfcUtils.enableNfc(adapter, mContext));
        assumeObserveModeSupported(adapter);
        adapter.notifyHceDeactivated();
        final Activity activity = createAndResumeActivity();
        String testName = new Object() {
        }.getClass().getEnclosingMethod().getName();
        String annotationStringHex = HexFormat.of().toHexDigits(testName.hashCode());
        CardEmulation cardEmulation = CardEmulation.getInstance(adapter);
        ComponentName offhostServiceName = new ComponentName(mContext,
                CtsMyOffHostApduService.class);
        assertFalse(cardEmulation.registerPollingLoopFilterForService(offhostServiceName,
                "1234567890", false));
        assertTrue(cardEmulation.registerPollingLoopFilterForService(offhostServiceName,
                annotationStringHex, true));
        PollingFrame frame = createFrameWithData(PollingFrame.POLLING_LOOP_TYPE_UNKNOWN,
                HexFormat.of().parseHex(annotationStringHex));
        ComponentName ctsComponentName = new ComponentName(mContext, CtsMyHostApduService.class);
        try {
            assertTrue(cardEmulation.setPreferredService(activity, ctsComponentName));
            ensurePreferredService(CtsMyHostApduService.class);
            assertTrue(adapter.setObserveModeEnabled(true));
            assertTrue(adapter.isObserveModeEnabled());
            adapter.notifyPollingLoop(frame);
            PollingCheck.check("Observe mode not disabled", 200,
                    () -> !adapter.isObserveModeEnabled());
            adapter.notifyHceDeactivated();
            PollingCheck.check("Observe mode not enabled", 3000, adapter::isObserveModeEnabled);
        } finally {
            adapter.setObserveModeEnabled(false);
            cardEmulation.unsetPreferredService(activity);
            activity.finish();
        }
    }

    @Test
    @RequiresFlagsEnabled(android.permission.flags.Flags.FLAG_WALLET_ROLE_ENABLED)
    public void testDisallowNonDefaultSetObserveMode() throws NoSuchFieldException {
        runWithRole(mContext,  WalletRoleTestUtils.WALLET_HOLDER_PACKAGE_NAME, () -> {
            NfcAdapter adapter = NfcAdapter.getDefaultAdapter(mContext);
            assertTrue(NfcUtils.enableNfc(adapter, mContext));
            assumeObserveModeSupported(adapter);
            adapter.notifyHceDeactivated();
            assertFalse(adapter.setObserveModeEnabled(true));
            assertFalse(adapter.isObserveModeEnabled());
        });
    }

    @Test
    @RequiresFlagsEnabled(android.permission.flags.Flags.FLAG_WALLET_ROLE_ENABLED)
    public void testAutoTransactDynamic_walletRoleEnabled() throws Exception {
        assumeVsrApiGreaterThanUdc();
        runWithRole(mContext, CTS_PACKAGE_NAME, () -> {
            NfcAdapter adapter = NfcAdapter.getDefaultAdapter(mContext);
            assumeObserveModeSupported(adapter);
            assertTrue(NfcUtils.enableNfc(adapter, mContext));
            adapter.notifyHceDeactivated();
            createAndResumeActivity();
            String testName = new Object() {
            }.getClass().getEnclosingMethod().getName();
            String annotationStringHex = HexFormat.of().toHexDigits(testName.hashCode());
            CardEmulation cardEmulation = CardEmulation.getInstance(adapter);
            ComponentName customServiceName = new ComponentName(mContext,
                    CtsMyHostApduService.class);
            assertTrue(cardEmulation.registerPollingLoopFilterForService(customServiceName,
                    annotationStringHex, true));
            ArrayList<PollingFrame> frames = new ArrayList<PollingFrame>(1);
            frames.add(createFrameWithData(PollingFrame.POLLING_LOOP_TYPE_UNKNOWN,
                    HexFormat.of().parseHex(annotationStringHex)));
            assertTrue(adapter.setObserveModeEnabled(true));
            assertTrue(adapter.isObserveModeEnabled());
            List<PollingFrame> receivedFrames =
                    notifyPollingLoopAndWait(frames, CtsMyHostApduService.class.getName());
            assertTrue(receivedFrames.get(0).getTriggeredAutoTransact());
            try {
                PollingCheck.check("Observe mode not disabled", 200,
                        () -> !adapter.isObserveModeEnabled());
                adapter.notifyHceDeactivated();
                PollingCheck.check("Observe mode not enabled", 3000, adapter::isObserveModeEnabled);
            } catch (Exception e) {
                throw new RuntimeException(e);
            } finally {
                adapter.setObserveModeEnabled(false);
            }
        });
    }

    @Test
    public void testInvalidPollingLoopFilter() {
        assumeVsrApiGreaterThanUdc();
        NfcAdapter adapter = NfcAdapter.getDefaultAdapter(mContext);
        CardEmulation cardEmulation = CardEmulation.getInstance(adapter);
        ComponentName customServiceName = new ComponentName(mContext, CustomHostApduService.class);
        assertThrows(IllegalArgumentException.class,
                () -> cardEmulation.registerPollingLoopFilterForService(customServiceName,
                        "", false));
        assertThrows(IllegalArgumentException.class,
                () ->cardEmulation.registerPollingLoopFilterForService(customServiceName,
                    "????", false));
        assertThrows(IllegalArgumentException.class,
                () ->cardEmulation.registerPollingLoopFilterForService(customServiceName,
                    "123", false));

    }

    static void ensureUnlocked() {
        final Context context = InstrumentationRegistry.getInstrumentation().getContext();
        final UserManager userManager = context.getSystemService(UserManager.class);
        assumeFalse("Device must not be headless", userManager.isHeadlessSystemUserMode());
        final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        final PowerManager pm = context.getSystemService(PowerManager.class);
        final KeyguardManager km = context.getSystemService(KeyguardManager.class);
        try {
            if (pm != null && !pm.isInteractive()) {
                runShellCommand("input keyevent KEYCODE_WAKEUP");
                CommonTestUtils.waitUntil("Device does not wake up after 5 seconds", 5,
                        () -> pm != null && pm.isInteractive());
            }
            if (km != null && km.isKeyguardLocked()) {
                CommonTestUtils.waitUntil("Device does not unlock after 30 seconds", 30,
                        () -> {
                        SystemUtil.runWithShellPermissionIdentity(
                                () -> instrumentation.sendKeyDownUpSync(
                                        (KeyEvent.KEYCODE_MENU)));
                        return km != null && !km.isKeyguardLocked();
                    }
                );
            }
        } catch (InterruptedException|AssertionError e) {
        }
    }

    private PollingFrame createFrame(@PollingFrameType int type) {
        if (type == PollingFrame.POLLING_LOOP_TYPE_ON
                || type == PollingFrame.POLLING_LOOP_TYPE_OFF) {
            return new PollingFrame(type,
                    new byte[] { ((type == PollingFrame.POLLING_LOOP_TYPE_ON)
                            ? (byte) 0x01 : (byte) 0x00) }, 8, 0,
                    false);
        }
        return new PollingFrame(type, null, 8, 0, false);
    }

    private PollingFrame createFrameWithData(@PollingFrameType int type, byte[] data) {
        return new PollingFrame(type, data, 8, (long) Integer.MAX_VALUE + 1L, false);
    }

    private ComponentName setDefaultPaymentService(Class serviceClass) {
        ComponentName componentName = setDefaultPaymentService(
                new ComponentName(mContext, serviceClass));
        return componentName;
    }

    ComponentName setDefaultPaymentService(ComponentName serviceName) {
        return DefaultPaymentProviderTestUtils.setDefaultPaymentService(serviceName, mContext);
    }

    static final class SettingsObserver extends ContentObserver {
        boolean mSeenChange = false;

        SettingsObserver(Handler handler) {
            super(handler);
        }
        @Override
        public void onChange(boolean selfChange, Uri uri) {
            mSeenChange = true;
            synchronized (this) {
                this.notify();
            }
        }
    }

    static PollLoopReceiver sCurrentPollLoopReceiver = null;
    static PollLoopReceiver sWalletRolePollLoopReceiver = null;

    static class PollLoopReceiver  {
        int mFrameIndex = 0;
        ArrayList<PollingFrame> mExpectedFrames;
        String mExpectedServiceName;
        ArrayList<PollingFrame> mReceivedFrames;
        String mReceivedServiceName;
        ArrayList<String> mReceivedServiceNames;
        PollLoopReceiver(ArrayList<PollingFrame> frames, String serviceName) {
            mExpectedFrames = frames;
            mExpectedServiceName = serviceName;
            mReceivedFrames = new ArrayList<PollingFrame>();
            mReceivedServiceNames = new ArrayList<String>();
        }

        void notifyPollingLoop(String className, List<PollingFrame> receivedFrames) {
            if (receivedFrames == null || receivedFrames.isEmpty()) {
                return;
            }
            mReceivedFrames.addAll(receivedFrames);
            mReceivedServiceName = className;
            mReceivedServiceNames.add(className);
            if (mReceivedFrames.size() < mExpectedFrames.size()) {
                return;
            }
            synchronized (this) {
                this.notify();
            }
        }

        void test() {
            if (mReceivedFrames.size() > mExpectedFrames.size()) {
                fail("received more frames than sent");
            } else if (mReceivedFrames.size() < mExpectedFrames.size()) {
                fail("received fewer frames than sent");
            }
            for (PollingFrame receivedFrame : mReceivedFrames) {
                PollingFrame expectedFrame = mExpectedFrames.get(mFrameIndex);
                assertEquals(expectedFrame.getType(), receivedFrame.getType());
                assertEquals(expectedFrame.getVendorSpecificGain(),
                    receivedFrame.getVendorSpecificGain());
                assertEquals(expectedFrame.getTimestamp(), receivedFrame.getTimestamp());
                assertArrayEquals(expectedFrame.getData(), receivedFrame.getData());
                mFrameIndex++;
            }
            if (mExpectedServiceName != null) {
                assertEquals(mExpectedServiceName, mReceivedServiceName);
            }
        }
        public void onObserveModeStateChanged(String className, boolean isEnabled) {
        }

        public void onPreferredServiceChanged(String className, boolean isPreferred) {
        }
        public void onListenersRegistered() {
        }
    }

    private List<PollingFrame> notifyPollingLoopAndWait(ArrayList<PollingFrame> frames,
        String serviceName) {
        return notifyPollingLoopAndWait(/* framesToSend = */ frames,
            /* framesToReceive = */ frames, serviceName);
    }

    private List<PollingFrame> notifyPollingLoopAndWait(ArrayList<PollingFrame> framesToSend,
        ArrayList<PollingFrame> framesToReceive, String serviceName) {
        NfcAdapter adapter = NfcAdapter.getDefaultAdapter(mContext);
        PollLoopReceiver pollLoopReceiver = new PollLoopReceiver(framesToReceive, serviceName);
        boolean receiveFromWalletRoleHoder =
                getWalletRoleHolderService().getClassName().equals(serviceName);
        if (receiveFromWalletRoleHoder) {
            sWalletRolePollLoopReceiver = pollLoopReceiver;
        } else {
            sCurrentPollLoopReceiver = pollLoopReceiver;
        }
        for (PollingFrame frame : framesToSend) {
            adapter.notifyPollingLoop(frame);
        }

        synchronized (pollLoopReceiver) {
            try {
                pollLoopReceiver.wait(10000);
            } catch (InterruptedException ie) {
                assertNull(ie);
            }
        }
        pollLoopReceiver.test();
        if (receiveFromWalletRoleHoder) {
            sWalletRolePollLoopReceiver = null;
        } else {
            sCurrentPollLoopReceiver = null;
        }
        return pollLoopReceiver.mReceivedFrames;
    }

    @RequiresFlagsEnabled(android.permission.flags.Flags.FLAG_WALLET_ROLE_ENABLED)
    @Test
    public void testAidResolutionWithRoleHolder_anotherAppHoldsForeground()
            throws NoSuchFieldException {
        Activity activity = createAndResumeActivity();
        CardEmulation instance = CardEmulation.getInstance(mAdapter);
        instance.setPreferredService(activity, WalletRoleTestUtils.getForegroundService());
        runWithRole(mContext, WalletRoleTestUtils.WALLET_HOLDER_PACKAGE_NAME, ()-> {
            /*
            * Aid Mapping:
            * Wallet Holder App: Service 1:     PAYMENT_AID_1, PAYMENT_AID_2
            * Wallet Holder App: Service 2:     PAYMENT_AID_1, PAYMENT_AID_2
            * Foreground App :   Only Service:  PAYMENT_AID_1
            * Non Payment App:   Only Service:  NON_PAYMENT_AID_1
            *
            * Scenario:
            * Wallet Role Holder is WalletRoleHolderApp
            * Foreground app: ForegroundApp
            *
            * Expected Outcome:
            * Both wallet role holder and the foreground app holds PAYMENT_AID_1.
            * So the foreground app is expected to get the routing for PAYMENT_AID_1.
            *
            * The foreground app does not have NON_PAYMENT_AID_1. Neither does the role holder.
            * So an app in the background (Non Payment App) gets the routing.
            **/
            assertTrue(instance.isDefaultServiceForAid(
                    WalletRoleTestUtils.getForegroundService(),
                    WalletRoleTestUtils.PAYMENT_AID_1));
            assertFalse(instance.isDefaultServiceForAid(
                    WalletRoleTestUtils.getForegroundService(),
                    WalletRoleTestUtils.NON_PAYMENT_AID_1));
            assertFalse(instance.isDefaultServiceForAid(
                    WalletRoleTestUtils.getWalletRoleHolderService(),
                    WalletRoleTestUtils.PAYMENT_AID_1));
            assertTrue(instance.isDefaultServiceForAid(
                    WalletRoleTestUtils.getNonPaymentService(),
                    WalletRoleTestUtils.NON_PAYMENT_AID_1));
            assertTrue(instance.unsetPreferredService(activity));
            activity.finish();
        });
    }

    @RequiresFlagsEnabled({android.permission.flags.Flags.FLAG_WALLET_ROLE_ENABLED,
            android.nfc.Flags.FLAG_NFC_ASSOCIATED_ROLE_SERVICES})
    @Test
    public void testAidResolutionWithRoleHolder_associatedService()
            throws NoSuchFieldException {
        runWithRole(mContext, WalletRoleTestUtils.WALLET_HOLDER_PACKAGE_NAME, ()-> {
            /*
             * Aid Mapping:
             * Wallet Holder App: Service 1:     PAYMENT_AID_1, PAYMENT_AID_2
             * Wallet Holder App: Service 2:     PAYMENT_AID_1, PAYMENT_AID_2
             * Foreground App :   Associated Service:  PAYMENT_AID_3
             *
             * Scenario:
             * Wallet Role Holder is WalletRoleHolderApp
             * Associated app: ForegroundApp
             *
             * Expected Outcome:
             * Associated Service should be the default service for the PAYMENT_AID_3.
             * The Wallet Holder app should still be default for PAYMENT_AID_1 and
             * PAYMENT_AID_2.
             **/
            CardEmulation instance = CardEmulation.getInstance(mAdapter);
            assertTrue(instance.isDefaultServiceForAid(
                    WalletRoleTestUtils.getWalletRoleHolderService(),
                    WalletRoleTestUtils.PAYMENT_AID_1));
            assertTrue(instance.isDefaultServiceForAid(
                    WalletRoleTestUtils.getWalletRoleHolderService(),
                    WalletRoleTestUtils.PAYMENT_AID_2));
            assertTrue(instance.isDefaultServiceForAid(
                    WalletRoleTestUtils.getAssociatedService(),
                    WalletRoleTestUtils.PAYMENT_AID_3));
        });
    }

    @RequiresFlagsEnabled(android.permission.flags.Flags.FLAG_WALLET_ROLE_ENABLED)
    @RequiresFlagsDisabled(android.nfc.Flags.FLAG_NFC_ASSOCIATED_ROLE_SERVICES)
    @Test
    public void testAidResolutionWithRoleHolder_noAssociatedService()
            throws NoSuchFieldException {
        runWithRole(mContext, WalletRoleTestUtils.WALLET_HOLDER_PACKAGE_NAME, ()-> {
            /*
             * Aid Mapping:
             * Wallet Holder App: Service 1:     PAYMENT_AID_1, PAYMENT_AID_2
             * Wallet Holder App: Service 2:     PAYMENT_AID_1, PAYMENT_AID_2
             * Foreground App :   Associated Service:  PAYMENT_AID_3
             *
             * Scenario:
             * Wallet Role Holder is WalletRoleHolderApp
             * Associated app: ForegroundApp
             *
             * Expected Outcome:
             * Associated Service should NOT be the default service for the PAYMENT_AID_3, since
             * it's registered in the payment category, it is not in the foreground, and it is
             * not the role holder.
             *
             * The Wallet Holder app should still be default for PAYMENT_AID_1 and
             * PAYMENT_AID_2.
             **/
            CardEmulation instance = CardEmulation.getInstance(mAdapter);
            assertTrue(instance.isDefaultServiceForAid(
                    WalletRoleTestUtils.getWalletRoleHolderService(),
                    WalletRoleTestUtils.PAYMENT_AID_1));
            assertTrue(instance.isDefaultServiceForAid(
                    WalletRoleTestUtils.getWalletRoleHolderService(),
                    WalletRoleTestUtils.PAYMENT_AID_2));
            assertFalse(instance.isDefaultServiceForAid(
                    WalletRoleTestUtils.getAssociatedService(),
                    WalletRoleTestUtils.PAYMENT_AID_3));
        });
    }

    @RequiresFlagsEnabled(android.permission.flags.Flags.FLAG_WALLET_ROLE_ENABLED)
    @Test
    public void testAidResolutionWithRoleHolder() throws NoSuchFieldException {
        CardEmulation instance = CardEmulation.getInstance(mAdapter);
        runWithRole(mContext, WalletRoleTestUtils.WALLET_HOLDER_PACKAGE_NAME, ()-> {
            /*
             * Aid Mapping:
             * Wallet Holder App: Service 1:     PAYMENT_AID_1, PAYMENT_AID_2
             * Wallet Holder App: Service 2:     PAYMENT_AID_1, PAYMENT_AID_2
             * Foreground App :   Only Service:  PAYMENT_AID_1
             * Non Payment App:   Only Service:  NON_PAYMENT_AID_1
             *
             * Scenario:
             * Wallet Role Holder is WalletRoleHolderApp
             * Foreground app: None
             *
             * Expected Outcome:
             * Wallet role holder and a background app holds PAYMENT_AID_1.
             * So the Wallet role holder app is expected to get the routing for PAYMENT_AID_1.
             * The wallet role holder has two services holding PAYMENT_AID_1. Therefore the one
             * that has the priority based on alphabetical sorting of their names gets the routing
             * WalletRoleHolderService vs XWAlletRoleHolderService. WalletRoleHolderService gets it.
             *
             * Only the Wallet Role Holder holds PAYMENT_AID_2.
             * So the wallet role holder app gets the routing for PAYMENT_AID_2.
             *
             * A background app that is not the wallet role holder has the NON_PAYMENT_AID_1.
             * So that app gets the routing for NON_PAYMENT_AID_1.
             **/
            assertTrue(instance.isDefaultServiceForAid(
                    WalletRoleTestUtils.getWalletRoleHolderService(),
                    WalletRoleTestUtils.PAYMENT_AID_1));
            assertTrue(instance.isDefaultServiceForAid(
                    WalletRoleTestUtils.getWalletRoleHolderService(),
                    WalletRoleTestUtils.PAYMENT_AID_2));
            assertFalse(instance.isDefaultServiceForAid(
                    WalletRoleTestUtils.getWalletRoleHolderXService(),
                    WalletRoleTestUtils.PAYMENT_AID_1));
            assertFalse(instance.isDefaultServiceForAid(
                    WalletRoleTestUtils.getWalletRoleHolderXService(),
                    WalletRoleTestUtils.PAYMENT_AID_2));
            assertFalse(instance.isDefaultServiceForAid(
                    WalletRoleTestUtils.getForegroundService(),
                    WalletRoleTestUtils.PAYMENT_AID_1));
            assertFalse(instance.isDefaultServiceForAid(
                    WalletRoleTestUtils.getWalletRoleHolderService(),
                    WalletRoleTestUtils.NON_PAYMENT_AID_1));
            assertTrue(instance.isDefaultServiceForAid(
                    WalletRoleTestUtils.getNonPaymentService(),
                    WalletRoleTestUtils.NON_PAYMENT_AID_1));
        });
    }

    @RequiresFlagsEnabled(android.permission.flags.Flags.FLAG_WALLET_ROLE_ENABLED)
    @Test
    public void testAidResolutionWithRoleHolderSetToNone() throws NoSuchFieldException {
        CardEmulation instance = CardEmulation.getInstance(mAdapter);
        runWithRoleNone(mContext, ()-> {
            /*
             * Aid Mapping:
             * Wallet Holder App: Service 1:     PAYMENT_AID_1, PAYMENT_AID_2
             * Wallet Holder App: Service 2:     PAYMENT_AID_1, PAYMENT_AID_2
             * Foreground App :   Only Service:  PAYMENT_AID_1
             * Non Payment App:   Only Service:  NON_PAYMENT_AID_1
             *
             * Scenario:
             * Wallet Role Holder is Set to None
             *
             * Expected Outcome:
             * Wallet role holder does not exist therefore routing is handled on the basis of
             * supported AIDs and overlapping services.
             *
             * Non Payment App is the only map holding the NON_PAYMENT_AID_1 and will be set
             * as the default service for that AID.
             *
             *  The rest of the apps will always need to disambig and will not be set as defaults.
             *
             **/
            assertFalse(instance.isDefaultServiceForAid(
                    WalletRoleTestUtils.getWalletRoleHolderService(),
                    WalletRoleTestUtils.PAYMENT_AID_1));
            assertFalse(instance.isDefaultServiceForAid(
                    WalletRoleTestUtils.getWalletRoleHolderService(),
                    WalletRoleTestUtils.PAYMENT_AID_2));
            assertFalse(instance.isDefaultServiceForAid(
                    WalletRoleTestUtils.getWalletRoleHolderXService(),
                    WalletRoleTestUtils.PAYMENT_AID_1));
            assertFalse(instance.isDefaultServiceForAid(
                    WalletRoleTestUtils.getWalletRoleHolderXService(),
                    WalletRoleTestUtils.PAYMENT_AID_2));
            assertFalse(instance.isDefaultServiceForAid(
                    WalletRoleTestUtils.getForegroundService(),
                    WalletRoleTestUtils.PAYMENT_AID_1));
            assertFalse(instance.isDefaultServiceForAid(
                    WalletRoleTestUtils.getWalletRoleHolderService(),
                    WalletRoleTestUtils.NON_PAYMENT_AID_1));
            assertTrue(instance.isDefaultServiceForAid(
                    WalletRoleTestUtils.getNonPaymentService(),
                    WalletRoleTestUtils.NON_PAYMENT_AID_1));
        });
    }

    @RequiresFlagsEnabled(android.permission.flags.Flags.FLAG_WALLET_ROLE_ENABLED)
    @Test
    public void testAidResolutionWithRoleHolder_holderDoesNotSupportAid_overLappingAids()
            throws NoSuchFieldException {
        CardEmulation instance = CardEmulation.getInstance(mAdapter);
        runWithRole(mContext, WalletRoleTestUtils.NON_PAYMENT_NFC_PACKAGE_NAME, ()-> {
            /*
             * Aid Mapping:
             * Wallet Holder App: Service 1:     PAYMENT_AID_1, PAYMENT_AID_2
             * Wallet Holder App: Service 2:     PAYMENT_AID_1, PAYMENT_AID_2
             * Foreground App :   Only Service:  PAYMENT_AID_1
             * Non Payment App:   Only Service:  NON_PAYMENT_AID_1
             *
             * Scenario:
             * Wallet Role Holder is Non Payment App
             * Foreground app: None
             *
             * Expected Outcome:
             * Wallet role holder holds NON_PAYMENT_AID_1
             * So wallet role holders gets the routing for NON_PAYMENT_AID_1.
             * The non wallet apps have overlapping aids and therefore no default services exist
             * for those AIDs.
             *
             **/
            assertTrue(instance.isDefaultServiceForAid(
                    WalletRoleTestUtils.getNonPaymentService(),
                    WalletRoleTestUtils.NON_PAYMENT_AID_1));
            assertFalse(instance.isDefaultServiceForAid(
                    WalletRoleTestUtils.getWalletRoleHolderService(),
                    WalletRoleTestUtils.PAYMENT_AID_1));
            assertFalse(instance.isDefaultServiceForAid(
                    WalletRoleTestUtils.getWalletRoleHolderService(),
                    WalletRoleTestUtils.PAYMENT_AID_2));
            assertFalse(instance.isDefaultServiceForAid(
                    WalletRoleTestUtils.getWalletRoleHolderXService(),
                    WalletRoleTestUtils.PAYMENT_AID_2));
            assertFalse(instance.isDefaultServiceForAid(
                    WalletRoleTestUtils.getWalletRoleHolderService(),
                    WalletRoleTestUtils.PAYMENT_AID_1));
            assertFalse(instance.isDefaultServiceForAid(
                    WalletRoleTestUtils.getForegroundService(),
                    WalletRoleTestUtils.PAYMENT_AID_1));
            assertFalse(instance.isDefaultServiceForAid(
                    WalletRoleTestUtils.getForegroundService(),
                    WalletRoleTestUtils.PAYMENT_AID_2));
        });
    }

    @RequiresFlagsEnabled(Flags.FLAG_NFC_OVERRIDE_RECOVER_ROUTING_TABLE)
    @Test
    public void testOverrideRoutingTable() {
        NfcAdapter adapter = NfcAdapter.getDefaultAdapter(mContext);
        assertTrue(NfcUtils.enableNfc(adapter, mContext));
        final Activity activity = createAndResumeActivity();
        CardEmulation instance = CardEmulation.getInstance(adapter);
        assertThrows(SecurityException.class,
                () -> instance.overrideRoutingTable(activity,
                        CardEmulation.PROTOCOL_AND_TECHNOLOGY_ROUTE_DH,
                        CardEmulation.PROTOCOL_AND_TECHNOLOGY_ROUTE_UNSET));
        instance.setPreferredService(activity,
                new ComponentName(mContext, CtsMyHostApduService.class));
        instance.overrideRoutingTable(activity,
                CardEmulation.PROTOCOL_AND_TECHNOLOGY_ROUTE_DH,
                CardEmulation.PROTOCOL_AND_TECHNOLOGY_ROUTE_UNSET);
    }

    @RequiresFlagsEnabled(Flags.FLAG_NFC_OVERRIDE_RECOVER_ROUTING_TABLE)
    @Test
    public void testRecoverRoutingTable() {
        NfcAdapter adapter = NfcAdapter.getDefaultAdapter(mContext);
        assertTrue(NfcUtils.enableNfc(adapter, mContext));
        final Activity activity = createAndResumeActivity();
        CardEmulation instance = CardEmulation.getInstance(adapter);
        instance.recoverRoutingTable(activity);
    }

    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_CARD_EMULATION_EUICC)
    @Test
    public void testIsEuiccSupported() {
        NfcAdapter adapter = NfcAdapter.getDefaultAdapter(mContext);
        assertTrue(NfcUtils.enableNfc(adapter, mContext));
        CardEmulation instance = CardEmulation.getInstance(adapter);
        instance.isEuiccSupported();
    }

    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_CARD_EMULATION_EUICC)
    @Test
    public void testGetSetDefaultNfcSubscriptionId() {
        NfcAdapter adapter = NfcAdapter.getDefaultAdapter(mContext);
        assertTrue(NfcUtils.enableNfc(adapter, mContext));
        CardEmulation instance = CardEmulation.getInstance(adapter);

        instance.setDefaultNfcSubscriptionId(SUBSCRIPTION_ID_UICC);
        assertEquals(SUBSCRIPTION_ID_UICC, instance.getDefaultNfcSubscriptionId());
    }

    @RequiresFlagsEnabled(Flags.FLAG_NFC_APDU_SERVICE_INFO_CONSTRUCTOR)
    @Test
    public void testApduServiceInfoConstructor() {
        ResolveInfo ndefNfceeAppInfo = new ResolveInfo();
        List<String> ndefNfceeAid = new ArrayList<String>();
        ndefNfceeAid.add("12345678ABCDEF#");
        AidGroup ndefNfceeAidGroup = new AidGroup(ndefNfceeAid, "other");
        ArrayList<AidGroup> ndefNfceeAidStaticGroups = new ArrayList<>();
        ndefNfceeAidStaticGroups.add(ndefNfceeAidGroup);
        ArrayList<AidGroup> ndefNfceeAidDynamicGroups = new ArrayList<>();
        ApduServiceInfo apduServiceINfo =
                new ApduServiceInfo(
                        ndefNfceeAppInfo,
                        false,
                        "test service",
                        ndefNfceeAidStaticGroups,
                        ndefNfceeAidDynamicGroups,
                        false,
                        0,
                        0,
                        "test service",
                        "test",
                        "test");
    }

    @Test
    public void testDontThrashObserveMode() {
        NfcAdapter adapter = NfcAdapter.getDefaultAdapter(mContext);
        assumeObserveModeSupported(adapter);
        adapter.notifyHceDeactivated();
        Activity activity = createAndResumeActivity();
        final CardEmulation cardEmulation = CardEmulation.getInstance(adapter);
        runWithRole(mContext, WALLET_HOLDER_PACKAGE_NAME, () -> {
            try {
                final Intent intent = new Intent();
                intent.setAction("com.cts.SetShouldDefaultToObserveModeForService");
                intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
                intent.setComponent(
                        new ComponentName("com.android.test.walletroleholder",
                                "com.android.test.walletroleholder.WalletRoleBroadcastReceiver"));
                mContext.sendBroadcast(intent);
                ComponentName backgroundService =
                        new ComponentName(mContext, CustomHostApduService.class);
                assertTrue(cardEmulation.setShouldDefaultToObserveModeForService(
                                backgroundService, true));

                assertTrue(cardEmulation.setPreferredService(activity, backgroundService));
                ensurePreferredService(CustomHostApduService.class);

                assertTrue(adapter.isObserveModeEnabled());
                ArrayList<PollingFrame> frames = new ArrayList<PollingFrame>(1);
                frames.add(createFrameWithData(PollingFrame.POLLING_LOOP_TYPE_UNKNOWN,
                        HexFormat.of().parseHex("7f71156b")));
                notifyPollingLoopAndWait(frames, CustomHostApduService.class.getName());
                assertFalse(adapter.isObserveModeEnabled());
                adapter.notifyHceDeactivated();
                activity.finish();
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
                assertFalse(adapter.isObserveModeEnabled());
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
                assertTrue(adapter.isObserveModeEnabled());
            } finally {
                cardEmulation.unsetPreferredService(activity);
                activity.finish();
                adapter.notifyHceDeactivated();
                final Intent intent = new Intent();
                intent.setAction("com.cts.UnsetShouldDefaultToObserveModeForService");
                intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
                intent.setComponent(
                        new ComponentName("com.android.test.walletroleholder",
                                "com.android.test.walletroleholder.WalletRoleBroadcastReceiver"));
                mContext.sendBroadcast(intent);
            }
        });
    }

    @Test
    public void testDontOverrideObserveMode() {
        NfcAdapter adapter = NfcAdapter.getDefaultAdapter(mContext);
        assumeObserveModeSupported(adapter);
        adapter.notifyHceDeactivated();
        Activity activity = createAndResumeActivity();
        final CardEmulation cardEmulation = CardEmulation.getInstance(adapter);
        runWithRole(mContext, WALLET_HOLDER_PACKAGE_NAME, () -> {
            try {
                final Intent intent = new Intent();
                intent.setAction("com.cts.UnsetShouldDefaultToObserveModeForService");
                intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
                intent.setComponent(
                        new ComponentName("com.android.test.walletroleholder",
                                "com.android.test.walletroleholder.WalletRoleBroadcastReceiver"));
                mContext.sendBroadcast(intent);
                ComponentName backgroundService =
                        new ComponentName(mContext, CustomHostApduService.class);
                assertTrue(cardEmulation.setShouldDefaultToObserveModeForService(
                                backgroundService, true));

                assertTrue(cardEmulation.setPreferredService(activity, backgroundService));
                ensurePreferredService(CustomHostApduService.class);

                assertTrue(adapter.isObserveModeEnabled());
                ArrayList<PollingFrame> frames = new ArrayList<PollingFrame>(1);
                frames.add(createFrameWithData(PollingFrame.POLLING_LOOP_TYPE_UNKNOWN,
                        HexFormat.of().parseHex("7f71156b")));
                notifyPollingLoopAndWait(frames, CustomHostApduService.class.getName());
                assertFalse(adapter.isObserveModeEnabled());
                adapter.notifyHceDeactivated();
                activity.finish();
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
                assertFalse(adapter.isObserveModeEnabled());
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
                assertFalse(adapter.isObserveModeEnabled());
            } finally {
                cardEmulation.unsetPreferredService(activity);
                activity.finish();
                adapter.notifyHceDeactivated();
                final Intent intent = new Intent();
                intent.setAction("com.cts.UnsetShouldDefaultToObserveModeForService");
                intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
                intent.setComponent(
                        new ComponentName("com.android.test.walletroleholder",
                                "com.android.test.walletroleholder.WalletRoleBroadcastReceiver"));
                mContext.sendBroadcast(intent);
            }
        });
    }

    private Activity createAndResumeActivity() {
        ensureUnlocked();
        Intent intent
            = new Intent(ApplicationProvider.getApplicationContext(),
                NfcFCardEmulationActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Activity activity = InstrumentationRegistry.getInstrumentation().startActivitySync(intent);
        InstrumentationRegistry.getInstrumentation().callActivityOnResume(activity);
        ComponentName topComponentName = mContext.getSystemService(ActivityManager.class)
                .getRunningTasks(1).get(0).topActivity;
        Assert.assertEquals("Foreground activity not in the foreground",
                NfcFCardEmulationActivity.class.getName(), topComponentName.getClassName());
        return activity;
    }
}
