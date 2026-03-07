/*
 * Copyright (C) 2024 The Android Open Source Project
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

import static android.nfc.cardemulation.CardEmulation.SET_SERVICE_ENABLED_STATUS_FAILURE_FEATURE_UNSUPPORTED;
import static android.nfc.cardemulation.CardEmulation.SET_SERVICE_ENABLED_STATUS_OK;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.nfc.ComponentNameAndUser;
import android.nfc.Constants;
import android.nfc.INfcCardEmulation;
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
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.util.proto.ProtoOutputStream;

import com.android.dx.mockito.inline.extended.ExtendedMockito;
import com.android.nfc.DeviceConfigFacade;
import com.android.nfc.ExitFrame;
import com.android.nfc.ForegroundUtils;
import com.android.nfc.NfcEventLog;
import com.android.nfc.NfcInjector;
import com.android.nfc.NfcPermissions;
import com.android.nfc.NfcService;
import com.android.nfc.cardemulation.util.StatsdUtils;
import com.android.nfc.cardemulation.util.TelephonyUtils;
import com.android.nfc.flags.Flags;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

public class CardEmulationManagerTest {

    private static final int USER_ID = 0;
    private static final UserHandle USER_HANDLE = UserHandle.of(USER_ID);
    private static final byte[] TEST_DATA_1 = new byte[]{(byte) 0xd2};
    private static final byte[] TEST_DATA_2 = new byte[]{(byte) 0xd3};
    private static final byte[] PROPER_SKIP_DATA_NDF1_HEADER =
            new byte[]{
                    0x00,
                    (byte) 0xa4,
                    0x04,
                    0x00,
                    (byte) 0x07,
                    (byte) 0xd2,
                    0x76,
                    0x00,
                    0x00,
                    (byte) 0x85,
                    0x01,
                    0x00
            };
    private static final byte[] PROPER_SKIP_DATA_NDF2_HEADER =
            new byte[]{
                    0x00,
                    (byte) 0xa4,
                    0x04,
                    0x00,
                    (byte) 0x07,
                    (byte) 0xd2,
                    0x76,
                    0x00,
                    0x00,
                    (byte) 0x85,
                    0x01,
                    0x01
            };
    private static final String WALLET_HOLDER_PACKAGE_NAME = "com.android.test.walletroleholder";
    private static final List<PollingFrame> POLLING_LOOP_FRAMES = List.of();
    private static final List<ApduServiceInfo> UPDATED_SERVICES = List.of();
    private static final List<NfcFServiceInfo> UPDATED_NFC_SERVICES = List.of();
    private static final ComponentName WALLET_PAYMENT_SERVICE =
            new ComponentName(
                    WALLET_HOLDER_PACKAGE_NAME,
                    "com.android.test.walletroleholder.WalletRoleHolderApduService");
    private static final String PAYMENT_AID_1 = "A000000004101012";

    @Mock
    private Context mContext;
    @Mock
    private Resources mResources;
    @Mock
    private ForegroundUtils mForegroundUtils;
    @Mock
    private WalletRoleObserver mWalletRoleObserver;
    @Mock
    private RegisteredAidCache mRegisteredAidCache;
    @Mock
    private RegisteredT3tIdentifiersCache mRegisteredT3tIdentifiersCache;
    @Mock
    private HostEmulationManager mHostEmulationManager;
    @Mock
    private HostNfcFEmulationManager mHostNfcFEmulationManager;
    @Mock
    private RegisteredServicesCache mRegisteredServicesCache;
    @Mock
    private RegisteredNfcFServicesCache mRegisteredNfcFServicesCache;
    @Mock
    private PreferredServices mPreferredServices;
    @Mock
    private EnabledNfcFServices mEnabledNfcFServices;
    @Mock
    private RoutingOptionManager mRoutingOptionManager;
    @Mock
    private PowerManager mPowerManager;
    @Mock
    private NfcService mNfcService;
    @Mock
    private UserManager mUserManager;
    @Mock
    private NfcAdapter mNfcAdapter;
    @Mock
    private NfcEventLog mNfcEventLog;
    @Mock
    private PreferredSubscriptionService mPreferredSubscriptionService;
    @Mock
    private StatsdUtils mStatsdUtils;
    @Mock
    private DeviceConfigFacade mDeviceConfigFacade;
    @Captor
    private ArgumentCaptor<List<PollingFrame>> mPollingLoopFrameCaptor;
    @Captor
    private ArgumentCaptor<byte[]> mDataCaptor;
    @Captor
    private ArgumentCaptor<List<ApduServiceInfo>> mServiceListCaptor;
    @Captor
    private ArgumentCaptor<List<NfcFServiceInfo>> mNfcServiceListCaptor;
    private MockitoSession mStaticMockSession;
    private CardEmulationManager mCardEmulationManager;

    @Before
    public void setUp() {
        mStaticMockSession =
                ExtendedMockito.mockitoSession()
                        .mockStatic(ActivityManager.class)
                        .mockStatic(NfcPermissions.class)
                        .mockStatic(android.nfc.Flags.class)
                        .mockStatic(Flags.class)
                        .mockStatic(Settings.Secure.class)
                        .strictness(Strictness.LENIENT)
                        .mockStatic(NfcService.class)
                        .mockStatic(Binder.class)
                        .mockStatic(UserHandle.class)
                        .mockStatic(NfcAdapter.class)
                        .mockStatic(NfcInjector.NfcProperties.class)
                        .startMocking();
        MockitoAnnotations.initMocks(this);
        when(NfcAdapter.getDefaultAdapter(mContext)).thenReturn(mNfcAdapter);
        when(NfcService.getInstance()).thenReturn(mNfcService);
        when(ActivityManager.getCurrentUser()).thenReturn(USER_ID);
        when(UserHandle.getUserHandleForUid(anyInt())).thenReturn(USER_HANDLE);
        when(mContext.createContextAsUser(any(), anyInt())).thenReturn(mContext);
        when(mContext.getResources()).thenReturn(mResources);
        when(mContext.getSystemService(eq(UserManager.class))).thenReturn(mUserManager);
        when(mDeviceConfigFacade.getIndicateUserActivityForHce()).thenReturn(true);
        when(android.nfc.Flags.nfcEventListener()).thenReturn(true);
        when(android.nfc.Flags.enableCardEmulationEuicc()).thenReturn(true);
        mCardEmulationManager = createInstanceWithMockParams();
    }

    @After
    public void tearDown() {
        mStaticMockSession.finishMocking();
    }

    @Test
    public void testConstructor() {
        assertConstructorMethodCalls();
    }

    private void assertConstructorMethodCalls() {
        verify(mRoutingOptionManager).getOffHostRouteEse();
        verify(mRoutingOptionManager).getOffHostRouteUicc();
        verify(mRegisteredServicesCache).initialize();
        verify(mRegisteredNfcFServicesCache).initialize();
        verify(mWalletRoleObserver).isWalletRoleFeatureEnabled();
        verify(mWalletRoleObserver).getDefaultWalletRoleHolder(eq(USER_ID));
        verify(mPreferredServices)
                .onWalletRoleHolderChanged(eq(WALLET_HOLDER_PACKAGE_NAME), eq(USER_ID));
        verify(mRegisteredAidCache)
                .onWalletRoleHolderChanged(eq(WALLET_HOLDER_PACKAGE_NAME), eq(USER_ID));
    }

    @Test
    public void testGetters() {
        assertNotNull(mCardEmulationManager.getNfcCardEmulationInterface());
        assertNotNull(mCardEmulationManager.getNfcFCardEmulationInterface());
    }

    @Test
    public void testPollingLoopDetected() {
        mCardEmulationManager.onPollingLoopDetected(POLLING_LOOP_FRAMES);

        verify(mHostEmulationManager).onPollingLoopDetected(mPollingLoopFrameCaptor.capture());
        assertEquals(POLLING_LOOP_FRAMES, mPollingLoopFrameCaptor.getValue());
    }

    @Test
    public void testOnHostCardEmulationActivated_technologyApdu() {
        mCardEmulationManager.onHostCardEmulationActivated(CardEmulationManager.NFC_HCE_APDU);

        verify(mPowerManager)
                .userActivity(
                        anyLong(),
                        eq(PowerManager.USER_ACTIVITY_EVENT_TOUCH),
                        eq(PowerManager.USER_ACTIVITY_FLAG_INDIRECT));
        verify(mHostEmulationManager).onHostEmulationActivated();
        verify(mPreferredServices).onHostEmulationActivated();
        assertFalse(mCardEmulationManager.mNotSkipAid);
        verifyZeroInteractions(mHostNfcFEmulationManager);
        verifyZeroInteractions(mEnabledNfcFServices);
    }

    @Test
    public void testOnHostCardEmulationActivated_technologyNfcf() {
        mCardEmulationManager.onHostCardEmulationActivated(CardEmulationManager.NFC_HCE_NFCF);

        assertConstructorMethodCalls();
        verify(mPowerManager)
                .userActivity(
                        anyLong(),
                        eq(PowerManager.USER_ACTIVITY_EVENT_TOUCH),
                        eq(PowerManager.USER_ACTIVITY_FLAG_INDIRECT));
        verify(mHostNfcFEmulationManager).onHostEmulationActivated();
        verify(mRegisteredNfcFServicesCache).onHostEmulationActivated();
        verify(mEnabledNfcFServices).onHostEmulationActivated();
        verify(mHostEmulationManager).setAidRoutingListener(any());
        verifyZeroInteractions(mHostEmulationManager);
        verifyZeroInteractions(mPreferredServices);
    }

    @Test
    public void testSkipAid_nullData_isFalse() {
        mCardEmulationManager.mNotSkipAid = false;
        assertFalse(mCardEmulationManager.isSkipAid(null));
    }

    @Test
    public void testSkipAid_notSkipTrue_isFalse() {
        mCardEmulationManager.mNotSkipAid = true;
        assertFalse(mCardEmulationManager.isSkipAid(TEST_DATA_1));
    }

    @Test
    public void testSkipAid_wrongData_isFalse() {
        mCardEmulationManager.mNotSkipAid = false;
        assertFalse(mCardEmulationManager.isSkipAid(TEST_DATA_1));
    }

    @Test
    public void testSkipAid_ndf1_isTrue() {
        mCardEmulationManager.mNotSkipAid = false;
        assertTrue(mCardEmulationManager.isSkipAid(PROPER_SKIP_DATA_NDF1_HEADER));
    }

    @Test
    public void testSkipAid_ndf2_isTrue() {
        mCardEmulationManager.mNotSkipAid = false;
        assertTrue(mCardEmulationManager.isSkipAid(PROPER_SKIP_DATA_NDF2_HEADER));
    }

    @Test
    public void testOnHostCardEmulationData_technologyApdu_skipData() {
        mCardEmulationManager.onHostCardEmulationData(
                CardEmulationManager.NFC_HCE_APDU, PROPER_SKIP_DATA_NDF1_HEADER);

        verify(mHostEmulationManager).onHostEmulationData(mDataCaptor.capture());
        assertEquals(PROPER_SKIP_DATA_NDF1_HEADER, mDataCaptor.getValue());
        verifyZeroInteractions(mHostNfcFEmulationManager);
        verifyZeroInteractions(mPowerManager);
    }

    @Test
    public void testOnHostCardEmulationData_technologyNfcf_DontSkipData() {
        mCardEmulationManager.onHostCardEmulationData(
                CardEmulationManager.NFC_HCE_NFCF, PROPER_SKIP_DATA_NDF1_HEADER);

        verify(mHostNfcFEmulationManager).onHostEmulationData(mDataCaptor.capture());
        assertEquals(PROPER_SKIP_DATA_NDF1_HEADER, mDataCaptor.getValue());
        verify(mHostEmulationManager).setAidRoutingListener(any());
        verifyZeroInteractions(mHostEmulationManager);
        verify(mPowerManager)
                .userActivity(anyLong(), eq(PowerManager.USER_ACTIVITY_EVENT_TOUCH), eq(0));
    }

    @Test
    public void testOnHostCardEmulationDeactivated_technologyApdu() {
        mCardEmulationManager.onHostCardEmulationDeactivated(CardEmulationManager.NFC_HCE_APDU);

        assertConstructorMethodCalls();
        verify(mHostEmulationManager).onHostEmulationDeactivated();
        verify(mPreferredServices).onHostEmulationDeactivated();
        verifyZeroInteractions(mHostNfcFEmulationManager);
        verifyZeroInteractions(mRegisteredNfcFServicesCache);
        verifyZeroInteractions(mEnabledNfcFServices);
    }

    @Test
    public void testOnHostCardEmulationDeactivated_technologyNfcf() {
        mCardEmulationManager.onHostCardEmulationDeactivated(CardEmulationManager.NFC_HCE_NFCF);

        assertConstructorMethodCalls();
        verify(mHostNfcFEmulationManager).onHostEmulationDeactivated();
        verify(mRegisteredNfcFServicesCache).onHostEmulationDeactivated();
        verify(mEnabledNfcFServices).onHostEmulationDeactivated();
        verify(mHostEmulationManager).setAidRoutingListener(any());
        verifyZeroInteractions(mHostEmulationManager);
        verifyZeroInteractions(mPreferredServices);
    }

    @Test
    public void testOnOffHostAidSelected() {
        mCardEmulationManager.onOffHostAidSelected();

        assertConstructorMethodCalls();
        verify(mHostEmulationManager).onOffHostAidSelected();
    }

    @Test
    public void testOnUserSwitched() {
        mCardEmulationManager.onUserSwitched(USER_ID);

        assertConstructorMethodCalls();
        verify(mWalletRoleObserver).onUserSwitched(eq(USER_ID));
        verify(mRegisteredServicesCache).onUserSwitched();
        verify(mPreferredServices).onUserSwitched(eq(USER_ID));
        verify(mHostNfcFEmulationManager).onUserSwitched();
        verify(mRegisteredT3tIdentifiersCache).onUserSwitched();
        verify(mEnabledNfcFServices).onUserSwitched(eq(USER_ID));
        verify(mRegisteredNfcFServicesCache).onUserSwitched();
    }

    @Test
    public void testOnManagedProfileChanged() {
        mCardEmulationManager.onManagedProfileChanged();

        assertConstructorMethodCalls();
        verify(mRegisteredServicesCache).onManagedProfileChanged();
        verify(mRegisteredNfcFServicesCache).onManagedProfileChanged();
    }

    @Test
    public void testOnNfcEnabled() {
        mCardEmulationManager.onNfcEnabled();

        assertConstructorMethodCalls();
        verify(mRegisteredAidCache).onNfcEnabled();
        verify(mRegisteredT3tIdentifiersCache).onNfcEnabled();
    }

    @Test
    public void testOnNfcDisabled() {
        mCardEmulationManager.onNfcDisabled();

        assertConstructorMethodCalls();
        verify(mRegisteredAidCache).onNfcDisabled();
        verify(mHostNfcFEmulationManager).onNfcDisabled();
        verify(mRegisteredNfcFServicesCache).onNfcDisabled();
        verify(mEnabledNfcFServices).onNfcDisabled();
        verify(mRegisteredT3tIdentifiersCache).onNfcDisabled();
    }

    @Test
    public void testOnSecureNfcToggled() {
        mCardEmulationManager.onTriggerRoutingTableUpdate();

        verify(mRegisteredAidCache).onTriggerRoutingTableUpdate();
        verify(mRegisteredT3tIdentifiersCache).onTriggerRoutingTableUpdate();
    }

    @Test
    public void testOnServicesUpdated_walletEnabledPollingLoopEnabled() {
        when(mWalletRoleObserver.isWalletRoleFeatureEnabled()).thenReturn(true);
        when(Flags.exitFrames()).thenReturn(true);
        when(mNfcService.isFirmwareExitFramesSupported()).thenReturn(true);
        when(mNfcService.getNumberOfFirmwareExitFramesSupported()).thenReturn(5);

        mCardEmulationManager.onServicesUpdated(USER_ID, UPDATED_SERVICES, false);

        verify(mWalletRoleObserver, times(2)).isWalletRoleFeatureEnabled();
        verify(mRegisteredAidCache).onServicesUpdated(eq(USER_ID), mServiceListCaptor.capture());
        verify(mPreferredServices).onServicesUpdated();
        verify(mHostEmulationManager)
                .updatePollingLoopFilters(eq(USER_ID), mServiceListCaptor.capture());
        verify(mNfcService).setFirmwareExitFrameTable(any(), anyInt());
        verify(mNfcService).onPreferredPaymentChanged(eq(NfcAdapter.PREFERRED_PAYMENT_UPDATED));
        assertEquals(UPDATED_SERVICES, mServiceListCaptor.getAllValues().getFirst());
        assertEquals(UPDATED_SERVICES, mServiceListCaptor.getAllValues().getLast());
    }

    @Test
    public void testOnNfcFServicesUpdated() {
        mCardEmulationManager.onNfcFServicesUpdated(USER_ID, UPDATED_NFC_SERVICES);

        verify(mRegisteredT3tIdentifiersCache)
                .onServicesUpdated(eq(USER_ID), mNfcServiceListCaptor.capture());
        assertEquals(UPDATED_NFC_SERVICES, mNfcServiceListCaptor.getValue());
    }

    @Test
    public void testIsServiceRegistered_serviceExists() {
        when(mRegisteredServicesCache.hasService(eq(USER_ID), any())).thenReturn(true);

        assertTrue(mCardEmulationManager.isServiceRegistered(USER_ID, WALLET_PAYMENT_SERVICE));

        verify(mRegisteredServicesCache, times(2))
                .hasService(eq(USER_ID), eq(WALLET_PAYMENT_SERVICE));
    }

    @Test
    public void testIsServiceRegistered_serviceDoesNotExists() {
        when(mRegisteredServicesCache.hasService(eq(USER_ID), any())).thenReturn(false);

        assertFalse(mCardEmulationManager.isServiceRegistered(USER_ID, WALLET_PAYMENT_SERVICE));

        verify(mRegisteredServicesCache).invalidateCache(eq(USER_ID), eq(true));
        verify(mRegisteredServicesCache, times(2))
                .hasService(eq(USER_ID), eq(WALLET_PAYMENT_SERVICE));
    }

    @Test
    public void testIsNfcServiceInstalled_serviceExists() {
        when(mRegisteredNfcFServicesCache.hasService(eq(USER_ID), any())).thenReturn(true);

        assertTrue(mCardEmulationManager.isNfcFServiceInstalled(USER_ID, WALLET_PAYMENT_SERVICE));

        verify(mRegisteredNfcFServicesCache, times(2))
                .hasService(eq(USER_ID), eq(WALLET_PAYMENT_SERVICE));
    }

    @Test
    public void testIsNfcServiceInstalled_serviceDoesNotExists() {
        when(mRegisteredNfcFServicesCache.hasService(eq(USER_ID), any())).thenReturn(false);

        assertFalse(mCardEmulationManager.isNfcFServiceInstalled(USER_ID, WALLET_PAYMENT_SERVICE));

        verify(mRegisteredNfcFServicesCache).invalidateCache(eq(USER_ID));
        verify(mRegisteredNfcFServicesCache, times(2))
                .hasService(eq(USER_ID), eq(WALLET_PAYMENT_SERVICE));
    }

    @Test
    public void testPackageHasPreferredService() {
        when(mPreferredServices.packageHasPreferredService(eq(WALLET_HOLDER_PACKAGE_NAME)))
                .thenReturn(true);

        assertTrue(mCardEmulationManager.packageHasPreferredService(WALLET_HOLDER_PACKAGE_NAME));

        verify(mPreferredServices).packageHasPreferredService(eq(WALLET_HOLDER_PACKAGE_NAME));
    }

    @Test
    public void testCardEmulationIsDefaultServiceForCategory_serviceExistsWalletEnabled()
            throws RemoteException {
        when(mRegisteredServicesCache.hasService(eq(USER_ID), any())).thenReturn(true);
        when(mWalletRoleObserver.isWalletRoleFeatureEnabled()).thenReturn(true);
        when(mWalletRoleObserver.getDefaultWalletRoleHolder(eq(USER_ID)))
                .thenReturn(new PackageAndUser(USER_ID, WALLET_HOLDER_PACKAGE_NAME));

        assertTrue(
                mCardEmulationManager
                        .getNfcCardEmulationInterface()
                        .isDefaultServiceForCategory(
                                USER_ID, WALLET_PAYMENT_SERVICE, CardEmulation.CATEGORY_PAYMENT));

        ExtendedMockito.verify(
                () -> {
                    NfcPermissions.validateUserId(USER_ID);
                });
        ExtendedMockito.verify(
                () -> {
                    NfcPermissions.enforceUserPermissions(mContext);
                });
        verify(mRegisteredServicesCache, times(2))
                .hasService(eq(USER_ID), eq(WALLET_PAYMENT_SERVICE));
    }

    @Test
    public void testCardEmulationIsDefaultServiceForCategory_serviceDoesNotExists()
            throws RemoteException {
        when(mRegisteredServicesCache.hasService(eq(USER_ID), any())).thenReturn(false);

        assertConstructorMethodCalls();
        assertFalse(
                mCardEmulationManager
                        .getNfcCardEmulationInterface()
                        .isDefaultServiceForCategory(
                                USER_ID, WALLET_PAYMENT_SERVICE, CardEmulation.CATEGORY_PAYMENT));

        ExtendedMockito.verify(
                () -> {
                    NfcPermissions.validateUserId(USER_ID);
                });
        ExtendedMockito.verify(
                () -> {
                    NfcPermissions.enforceUserPermissions(mContext);
                });
        verifyZeroInteractions(mWalletRoleObserver);
        verify(mRegisteredServicesCache).invalidateCache(eq(USER_ID), eq(true));
        verify(mRegisteredServicesCache, times(2))
                .hasService(eq(USER_ID), eq(WALLET_PAYMENT_SERVICE));
    }

    @Test
    public void testCardEmulationIsDefaultServiceForAid_serviceExists() throws RemoteException {
        when(mRegisteredServicesCache.hasService(eq(USER_ID), any())).thenReturn(true);
        when(mRegisteredAidCache.isDefaultServiceForAid(eq(USER_ID), any(), eq(PAYMENT_AID_1)))
                .thenReturn(true);

        assertTrue(
                mCardEmulationManager
                        .getNfcCardEmulationInterface()
                        .isDefaultServiceForAid(USER_ID, WALLET_PAYMENT_SERVICE, PAYMENT_AID_1));

        ExtendedMockito.verify(
                () -> {
                    NfcPermissions.validateUserId(USER_ID);
                });
        ExtendedMockito.verify(
                () -> {
                    NfcPermissions.validateUserId(USER_ID);
                });
        verify(mRegisteredAidCache)
                .isDefaultServiceForAid(eq(USER_ID), eq(WALLET_PAYMENT_SERVICE), eq(PAYMENT_AID_1));
        verify(mRegisteredServicesCache, times(2))
                .hasService(eq(USER_ID), eq(WALLET_PAYMENT_SERVICE));
    }

    @Test
    public void testCardEmulationIsDefaultServiceForAid_serviceDoesNotExists()
            throws RemoteException {
        when(mRegisteredServicesCache.hasService(eq(USER_ID), any())).thenReturn(false);

        assertFalse(
                mCardEmulationManager
                        .getNfcCardEmulationInterface()
                        .isDefaultServiceForAid(USER_ID, WALLET_PAYMENT_SERVICE, PAYMENT_AID_1));

        ExtendedMockito.verify(
                () -> {
                    NfcPermissions.validateUserId(USER_ID);
                });
        ExtendedMockito.verify(
                () -> {
                    NfcPermissions.enforceUserPermissions(mContext);
                });
        verify(mRegisteredAidCache)
                .onWalletRoleHolderChanged(eq(WALLET_HOLDER_PACKAGE_NAME), eq(USER_ID));
        verify(mRegisteredServicesCache).invalidateCache(eq(USER_ID), eq(true));
        verify(mRegisteredServicesCache, times(2))
                .hasService(eq(USER_ID), eq(WALLET_PAYMENT_SERVICE));
        verifyZeroInteractions(mRegisteredAidCache);
    }

    @Test
    public void testCardEmulationSetDefaultForNextTap_serviceExists() throws RemoteException {
        when(mRegisteredServicesCache.hasService(eq(USER_ID), any())).thenReturn(true);
        when(mPreferredServices.setDefaultForNextTap(anyInt(), any())).thenReturn(true);

        assertTrue(
                mCardEmulationManager
                        .getNfcCardEmulationInterface()
                        .setDefaultForNextTap(USER_ID, WALLET_PAYMENT_SERVICE));

        ExtendedMockito.verify(
                () -> {
                    NfcPermissions.validateProfileId(mContext, USER_ID);
                });
        ExtendedMockito.verify(
                () -> {
                    NfcPermissions.enforceAdminPermissions(mContext);
                });
        verify(mPreferredServices).setDefaultForNextTap(eq(USER_ID), eq(WALLET_PAYMENT_SERVICE));
        verify(mRegisteredServicesCache, times(2))
                .hasService(eq(USER_ID), eq(WALLET_PAYMENT_SERVICE));
    }

    @Test
    public void testCardEmulationSetDefaultForNextTap_serviceDoesNotExists()
            throws RemoteException {
        when(mRegisteredServicesCache.hasService(eq(USER_ID), any())).thenReturn(false);

        assertFalse(
                mCardEmulationManager
                        .getNfcCardEmulationInterface()
                        .setDefaultForNextTap(USER_ID, WALLET_PAYMENT_SERVICE));

        ExtendedMockito.verify(
                () -> {
                    NfcPermissions.validateProfileId(mContext, USER_ID);
                });
        ExtendedMockito.verify(
                () -> {
                    NfcPermissions.enforceAdminPermissions(mContext);
                });
        verify(mRegisteredServicesCache).invalidateCache(eq(USER_ID), eq(true));
        verify(mRegisteredServicesCache, times(2))
                .hasService(eq(USER_ID), eq(WALLET_PAYMENT_SERVICE));
        verify(mPreferredServices)
                .onWalletRoleHolderChanged(eq(WALLET_HOLDER_PACKAGE_NAME), eq(USER_ID));
        verifyZeroInteractions(mPreferredServices);
    }

    @Test
    public void testCardEmulationSetShouldDefaultToObserveModeForService_serviceExists()
            throws RemoteException {
        when(mRegisteredServicesCache.hasService(eq(USER_ID), any())).thenReturn(true);
        when(mRegisteredServicesCache.setShouldDefaultToObserveModeForService(
                anyInt(), anyInt(), any(), anyBoolean()))
                .thenReturn(true);
        when(mRegisteredServicesCache.doesServiceShouldDefaultToObserveMode(anyInt(), any()))
                .thenReturn(false);

        assertTrue(
                mCardEmulationManager
                        .getNfcCardEmulationInterface()
                        .setShouldDefaultToObserveModeForService(
                                USER_ID, WALLET_PAYMENT_SERVICE, true));

        ExtendedMockito.verify(
                () -> {
                    NfcPermissions.validateUserId(USER_ID);
                });
        ExtendedMockito.verify(
                () -> {
                    NfcPermissions.enforceUserPermissions(mContext);
                });
        verify(mRegisteredServicesCache).initialize();
        verify(mRegisteredServicesCache).doesServiceShouldDefaultToObserveMode(anyInt(), any());
        verify(mRegisteredServicesCache)
                .setShouldDefaultToObserveModeForService(
                        eq(USER_ID), anyInt(), eq(WALLET_PAYMENT_SERVICE), eq(true));
        verify(mRegisteredServicesCache, times(2))
                .hasService(eq(USER_ID), eq(WALLET_PAYMENT_SERVICE));
        verifyNoMoreInteractions(mRegisteredServicesCache);
    }

    @Test
    public void testCardEmulationSetShouldDefaultToObserveModeForService_ignoreNoopStateChange()
            throws RemoteException {
        when(mRegisteredServicesCache.hasService(eq(USER_ID), any())).thenReturn(true);
        when(mRegisteredServicesCache.setShouldDefaultToObserveModeForService(
                anyInt(), anyInt(), any(), anyBoolean()))
                .thenReturn(true);
        when(mRegisteredServicesCache.doesServiceShouldDefaultToObserveMode(anyInt(), any()))
                .thenReturn(false);

        assertTrue(
                mCardEmulationManager
                        .getNfcCardEmulationInterface()
                        .setShouldDefaultToObserveModeForService(
                                USER_ID, WALLET_PAYMENT_SERVICE, true));

        when(mRegisteredServicesCache.doesServiceShouldDefaultToObserveMode(anyInt(), any()))
                .thenReturn(true);

        // Called twice with the same value. Calls to update should be ignored.
        assertTrue(
                mCardEmulationManager
                        .getNfcCardEmulationInterface()
                        .setShouldDefaultToObserveModeForService(
                                USER_ID, WALLET_PAYMENT_SERVICE, true));

        ExtendedMockito.verify(() -> NfcPermissions.validateUserId(USER_ID), times(2));
        ExtendedMockito.verify(() -> NfcPermissions.enforceUserPermissions(mContext), times(2));
        verify(mRegisteredServicesCache).initialize();
        verify(mRegisteredServicesCache, times(2))
                .doesServiceShouldDefaultToObserveMode(anyInt(), any());
        verify(mRegisteredServicesCache, times(4))
                .hasService(eq(USER_ID), eq(WALLET_PAYMENT_SERVICE));

        // Importantly this should only be called once.
        verify(mRegisteredServicesCache, times(1))
                .setShouldDefaultToObserveModeForService(
                        eq(USER_ID), anyInt(), eq(WALLET_PAYMENT_SERVICE), eq(true));
        verifyNoMoreInteractions(mRegisteredServicesCache);
    }

    @Test
    public void testCardEmulationSetShouldDefaultToObserveModeForService_serviceDoesNotExists()
            throws RemoteException {
        when(mRegisteredServicesCache.hasService(eq(USER_ID), any())).thenReturn(false);

        assertFalse(
                mCardEmulationManager
                        .getNfcCardEmulationInterface()
                        .setShouldDefaultToObserveModeForService(
                                USER_ID, WALLET_PAYMENT_SERVICE, false));

        ExtendedMockito.verify(
                () -> {
                    NfcPermissions.validateUserId(USER_ID);
                });
        ExtendedMockito.verify(
                () -> {
                    NfcPermissions.enforceUserPermissions(mContext);
                });
        verify(mRegisteredServicesCache).initialize();
        verify(mRegisteredServicesCache).invalidateCache(eq(USER_ID), eq(true));
        verify(mRegisteredServicesCache, times(2))
                .hasService(eq(USER_ID), eq(WALLET_PAYMENT_SERVICE));
        verifyNoMoreInteractions(mRegisteredServicesCache);
    }

    @Test
    public void testCardEmulationRegisterAidGroupForService_serviceExists() throws RemoteException {
        when(mRegisteredServicesCache.hasService(eq(USER_ID), any())).thenReturn(true);
        when(mRegisteredServicesCache.registerAidGroupForService(
                eq(USER_ID), anyInt(), any(), any()))
                .thenReturn(true);
        AidGroup aidGroup = Mockito.mock(AidGroup.class);

        assertTrue(
                mCardEmulationManager
                        .getNfcCardEmulationInterface()
                        .registerAidGroupForService(USER_ID, WALLET_PAYMENT_SERVICE, aidGroup));

        ExtendedMockito.verify(
                () -> {
                    NfcPermissions.validateUserId(USER_ID);
                });
        ExtendedMockito.verify(
                () -> {
                    NfcPermissions.enforceUserPermissions(mContext);
                });
        verify(mRegisteredServicesCache).initialize();
        verify(mRegisteredServicesCache, times(2))
                .hasService(eq(USER_ID), eq(WALLET_PAYMENT_SERVICE));
        verify(mRegisteredServicesCache)
                .registerAidGroupForService(
                        eq(USER_ID), anyInt(), eq(WALLET_PAYMENT_SERVICE), eq(aidGroup));
        verifyNoMoreInteractions(mRegisteredServicesCache);
    }

    @Test
    public void testCardEmulationRegisterAidGroupForService_serviceDoesNotExists()
            throws RemoteException {
        when(mRegisteredServicesCache.hasService(eq(USER_ID), any())).thenReturn(false);
        when(mRegisteredServicesCache.registerAidGroupForService(
                eq(USER_ID), anyInt(), any(), any()))
                .thenReturn(true);
        AidGroup aidGroup = Mockito.mock(AidGroup.class);

        assertFalse(
                mCardEmulationManager
                        .getNfcCardEmulationInterface()
                        .registerAidGroupForService(USER_ID, WALLET_PAYMENT_SERVICE, aidGroup));

        ExtendedMockito.verify(
                () -> {
                    NfcPermissions.validateUserId(USER_ID);
                });
        ExtendedMockito.verify(
                () -> {
                    NfcPermissions.enforceUserPermissions(mContext);
                });
        verify(mRegisteredAidCache)
                .onWalletRoleHolderChanged(eq(WALLET_HOLDER_PACKAGE_NAME), eq(USER_ID));
        verify(mRegisteredServicesCache).invalidateCache(eq(USER_ID), eq(true));
        verify(mRegisteredServicesCache, times(2))
                .hasService(eq(USER_ID), eq(WALLET_PAYMENT_SERVICE));
        verifyNoMoreInteractions(mRegisteredAidCache);
    }

    @Test
    public void testCardEmulationRegisterPollingLoopFilterForService_serviceExists()
            throws RemoteException {
        when(mRegisteredServicesCache.hasService(eq(USER_ID), any())).thenReturn(true);
        when(mRegisteredServicesCache.registerPollingLoopFilterForService(
                eq(USER_ID), anyInt(), any(), any(), anyBoolean()))
                .thenReturn(true);
        String pollingLoopFilter = "filter";

        assertTrue(
                mCardEmulationManager
                        .getNfcCardEmulationInterface()
                        .registerPollingLoopFilterForService(
                                USER_ID, WALLET_PAYMENT_SERVICE, pollingLoopFilter, true));

        verify(mRegisteredServicesCache).initialize();
        ExtendedMockito.verify(
                () -> {
                    NfcPermissions.enforceUserPermissions(mContext);
                });
        ExtendedMockito.verify(
                () -> {
                    NfcPermissions.validateUserId(USER_ID);
                });
        verify(mRegisteredServicesCache, times(2))
                .hasService(eq(USER_ID), eq(WALLET_PAYMENT_SERVICE));
        verify(mRegisteredServicesCache)
                .registerPollingLoopFilterForService(
                        eq(USER_ID),
                        anyInt(),
                        eq(WALLET_PAYMENT_SERVICE),
                        eq(pollingLoopFilter),
                        eq(true));
        verifyNoMoreInteractions(mRegisteredServicesCache);
    }

    @Test
    public void testCardEmulationRegisterPollingLoopFilterForService_serviceDoesNotExists()
            throws RemoteException {
        when(mRegisteredServicesCache.hasService(eq(USER_ID), any())).thenReturn(false);
        when(mRegisteredServicesCache.registerPollingLoopFilterForService(
                eq(USER_ID), anyInt(), any(), any(), anyBoolean()))
                .thenReturn(true);
        String pollingLoopFilter = "filter";

        assertFalse(
                mCardEmulationManager
                        .getNfcCardEmulationInterface()
                        .registerPollingLoopFilterForService(
                                USER_ID, WALLET_PAYMENT_SERVICE, pollingLoopFilter, true));

        ExtendedMockito.verify(
                () -> {
                    NfcPermissions.validateUserId(USER_ID);
                });
        ExtendedMockito.verify(
                () -> {
                    NfcPermissions.enforceUserPermissions(mContext);
                });
        verify(mRegisteredServicesCache).initialize();
        verify(mRegisteredServicesCache).invalidateCache(eq(USER_ID), eq(true));
        verify(mRegisteredServicesCache, times(2))
                .hasService(eq(USER_ID), eq(WALLET_PAYMENT_SERVICE));
        verifyNoMoreInteractions(mRegisteredServicesCache);
    }

    @Test
    public void testCardEmulationRemovePollingLoopFilterForService_serviceExists()
            throws RemoteException {
        when(mRegisteredServicesCache.hasService(eq(USER_ID), any())).thenReturn(true);
        when(mRegisteredServicesCache.removePollingLoopFilterForService(
                eq(USER_ID), anyInt(), any(), any()))
                .thenReturn(true);
        String pollingLoopFilter = "filter";

        assertTrue(
                mCardEmulationManager
                        .getNfcCardEmulationInterface()
                        .removePollingLoopFilterForService(
                                USER_ID, WALLET_PAYMENT_SERVICE, pollingLoopFilter));

        ExtendedMockito.verify(
                () -> {
                    NfcPermissions.enforceUserPermissions(mContext);
                });
        ExtendedMockito.verify(
                () -> {
                    NfcPermissions.validateUserId(USER_ID);
                });
        verify(mRegisteredServicesCache).initialize();
        verify(mRegisteredServicesCache, times(2))
                .hasService(eq(USER_ID), eq(WALLET_PAYMENT_SERVICE));
        verify(mRegisteredServicesCache)
                .removePollingLoopFilterForService(
                        eq(USER_ID), anyInt(), eq(WALLET_PAYMENT_SERVICE), eq(pollingLoopFilter));
        verifyNoMoreInteractions(mRegisteredServicesCache);
    }

    @Test
    public void testCardEmulationRemovePollingLoopFilterForService_serviceDoesNotExists()
            throws RemoteException {
        when(mRegisteredServicesCache.hasService(eq(USER_ID), any())).thenReturn(false);
        when(mRegisteredServicesCache.removePollingLoopFilterForService(
                eq(USER_ID), anyInt(), any(), any()))
                .thenReturn(true);
        String pollingLoopFilter = "filter";

        assertFalse(
                mCardEmulationManager
                        .getNfcCardEmulationInterface()
                        .removePollingLoopFilterForService(
                                USER_ID, WALLET_PAYMENT_SERVICE, pollingLoopFilter));

        ExtendedMockito.verify(
                () -> {
                    NfcPermissions.enforceUserPermissions(mContext);
                });
        ExtendedMockito.verify(
                () -> {
                    NfcPermissions.validateUserId(USER_ID);
                });
        verify(mRegisteredServicesCache).initialize();
        verify(mRegisteredServicesCache).invalidateCache(eq(USER_ID), eq(true));
        verify(mRegisteredServicesCache, times(2))
                .hasService(eq(USER_ID), eq(WALLET_PAYMENT_SERVICE));
        verifyNoMoreInteractions(mRegisteredServicesCache);
    }

    @Test
    public void testCardEmulationRegisterPollingLoopPatternFilterForService_serviceExists()
            throws RemoteException {
        when(mRegisteredServicesCache.hasService(eq(USER_ID), any())).thenReturn(true);
        when(mRegisteredServicesCache.registerPollingLoopPatternFilterForService(
                eq(USER_ID), anyInt(), any(), any(), anyBoolean()))
                .thenReturn(true);
        String pollingLoopFilter = "filter";

        assertTrue(
                mCardEmulationManager
                        .getNfcCardEmulationInterface()
                        .registerPollingLoopPatternFilterForService(
                                USER_ID, WALLET_PAYMENT_SERVICE, pollingLoopFilter, true));

        ExtendedMockito.verify(
                () -> {
                    NfcPermissions.enforceUserPermissions(mContext);
                });
        ExtendedMockito.verify(
                () -> {
                    NfcPermissions.validateUserId(USER_ID);
                });
        verify(mRegisteredServicesCache).initialize();
        verify(mRegisteredServicesCache, times(2))
                .hasService(eq(USER_ID), eq(WALLET_PAYMENT_SERVICE));
        verify(mRegisteredServicesCache)
                .registerPollingLoopPatternFilterForService(
                        eq(USER_ID),
                        anyInt(),
                        eq(WALLET_PAYMENT_SERVICE),
                        eq(pollingLoopFilter),
                        eq(true));
        verifyNoMoreInteractions(mRegisteredServicesCache);
    }

    @Test
    public void testCardEmulationRegisterPollingLoopPatternFilterForService_serviceDoesNotExists()
            throws RemoteException {
        when(mRegisteredServicesCache.hasService(eq(USER_ID), any())).thenReturn(false);
        when(mRegisteredServicesCache.registerPollingLoopPatternFilterForService(
                eq(USER_ID), anyInt(), any(), any(), anyBoolean()))
                .thenReturn(true);
        String pollingLoopFilter = "filter";

        assertFalse(
                mCardEmulationManager
                        .getNfcCardEmulationInterface()
                        .registerPollingLoopPatternFilterForService(
                                USER_ID, WALLET_PAYMENT_SERVICE, pollingLoopFilter, true));

        ExtendedMockito.verify(
                () -> {
                    NfcPermissions.enforceUserPermissions(mContext);
                });
        ExtendedMockito.verify(
                () -> {
                    NfcPermissions.validateUserId(USER_ID);
                });
        verify(mRegisteredServicesCache).initialize();
        verify(mRegisteredServicesCache).invalidateCache(eq(USER_ID), eq(true));
        verify(mRegisteredServicesCache, times(2))
                .hasService(eq(USER_ID), eq(WALLET_PAYMENT_SERVICE));
        verifyNoMoreInteractions(mRegisteredServicesCache);
    }

    @Test
    public void testCardEmulationRemovePollingLoopPatternFilterForService_serviceExists()
            throws RemoteException {
        when(mRegisteredServicesCache.hasService(eq(USER_ID), any())).thenReturn(true);
        when(mRegisteredServicesCache.removePollingLoopPatternFilterForService(
                eq(USER_ID), anyInt(), any(), any()))
                .thenReturn(true);
        String pollingLoopFilter = "filter";

        assertTrue(
                mCardEmulationManager
                        .getNfcCardEmulationInterface()
                        .removePollingLoopPatternFilterForService(
                                USER_ID, WALLET_PAYMENT_SERVICE, pollingLoopFilter));

        ExtendedMockito.verify(
                () -> {
                    NfcPermissions.enforceUserPermissions(mContext);
                });
        ExtendedMockito.verify(
                () -> {
                    NfcPermissions.validateUserId(USER_ID);
                });
        verify(mRegisteredServicesCache).initialize();
        verify(mRegisteredServicesCache, times(2))
                .hasService(eq(USER_ID), eq(WALLET_PAYMENT_SERVICE));
        verify(mRegisteredServicesCache)
                .removePollingLoopPatternFilterForService(
                        eq(USER_ID), anyInt(), eq(WALLET_PAYMENT_SERVICE), eq(pollingLoopFilter));
        verifyNoMoreInteractions(mRegisteredServicesCache);
    }

    @Test
    public void testCardEmulationRemovePollingLoopPatternFilterForService_serviceDoesNotExists()
            throws RemoteException {
        when(mRegisteredServicesCache.hasService(eq(USER_ID), any())).thenReturn(false);
        when(mRegisteredServicesCache.removePollingLoopPatternFilterForService(
                eq(USER_ID), anyInt(), any(), any()))
                .thenReturn(true);
        String pollingLoopFilter = "filter";

        assertFalse(
                mCardEmulationManager
                        .getNfcCardEmulationInterface()
                        .removePollingLoopPatternFilterForService(
                                USER_ID, WALLET_PAYMENT_SERVICE, pollingLoopFilter));

        ExtendedMockito.verify(
                () -> {
                    NfcPermissions.enforceUserPermissions(mContext);
                });
        ExtendedMockito.verify(
                () -> {
                    NfcPermissions.validateUserId(USER_ID);
                });
        verify(mRegisteredServicesCache).initialize();
        verify(mRegisteredServicesCache).invalidateCache(eq(USER_ID), eq(true));
        verify(mRegisteredServicesCache, times(2))
                .hasService(eq(USER_ID), eq(WALLET_PAYMENT_SERVICE));
        verifyNoMoreInteractions(mRegisteredServicesCache);
    }

    @Test
    public void testCardEmulationSetOffHostForService_serviceExists() throws RemoteException {
        when(mRegisteredServicesCache.hasService(eq(USER_ID), any())).thenReturn(true);
        when(mRegisteredServicesCache.setOffHostSecureElement(eq(USER_ID), anyInt(), any(), any()))
                .thenReturn(true);
        String offhostse = "offhostse";

        assertTrue(
                mCardEmulationManager
                        .getNfcCardEmulationInterface()
                        .setOffHostForService(USER_ID, WALLET_PAYMENT_SERVICE, offhostse));

        ExtendedMockito.verify(
                () -> {
                    NfcPermissions.enforceUserPermissions(mContext);
                });
        ExtendedMockito.verify(
                () -> {
                    NfcPermissions.validateUserId(USER_ID);
                });
        verify(mRegisteredServicesCache).initialize();
        verify(mRegisteredServicesCache, times(2))
                .hasService(eq(USER_ID), eq(WALLET_PAYMENT_SERVICE));
        verify(mRegisteredServicesCache)
                .setOffHostSecureElement(
                        eq(USER_ID), anyInt(), eq(WALLET_PAYMENT_SERVICE), eq(offhostse));
        verifyNoMoreInteractions(mRegisteredServicesCache);
    }

    @Test
    public void testCardEmulationSetOffHostForService_serviceDoesNotExists()
            throws RemoteException {
        when(mRegisteredServicesCache.hasService(eq(USER_ID), any())).thenReturn(false);
        when(mRegisteredServicesCache.setOffHostSecureElement(eq(USER_ID), anyInt(), any(), any()))
                .thenReturn(true);
        String offhostse = "offhostse";

        assertFalse(
                mCardEmulationManager
                        .getNfcCardEmulationInterface()
                        .setOffHostForService(USER_ID, WALLET_PAYMENT_SERVICE, offhostse));

        ExtendedMockito.verify(
                () -> {
                    NfcPermissions.enforceUserPermissions(mContext);
                });
        ExtendedMockito.verify(
                () -> {
                    NfcPermissions.validateUserId(USER_ID);
                });
        verify(mRegisteredServicesCache).initialize();
        verify(mRegisteredServicesCache).invalidateCache(eq(USER_ID), eq(true));
        verify(mRegisteredServicesCache, times(2))
                .hasService(eq(USER_ID), eq(WALLET_PAYMENT_SERVICE));
        verifyNoMoreInteractions(mRegisteredServicesCache);
    }

    @Test
    public void testCardEmulationUnsetOffHostForService_serviceExists() throws RemoteException {
        when(mRegisteredServicesCache.hasService(eq(USER_ID), any())).thenReturn(true);
        when(mRegisteredServicesCache.resetOffHostSecureElement(eq(USER_ID), anyInt(), any()))
                .thenReturn(true);

        assertTrue(
                mCardEmulationManager
                        .getNfcCardEmulationInterface()
                        .unsetOffHostForService(USER_ID, WALLET_PAYMENT_SERVICE));

        ExtendedMockito.verify(
                () -> {
                    NfcPermissions.enforceUserPermissions(mContext);
                });
        ExtendedMockito.verify(
                () -> {
                    NfcPermissions.validateUserId(USER_ID);
                });
        verify(mRegisteredServicesCache).initialize();
        verify(mRegisteredServicesCache, times(2))
                .hasService(eq(USER_ID), eq(WALLET_PAYMENT_SERVICE));
        verify(mRegisteredServicesCache)
                .resetOffHostSecureElement(eq(USER_ID), anyInt(), eq(WALLET_PAYMENT_SERVICE));
        verifyNoMoreInteractions(mRegisteredServicesCache);
        verify(mNfcService).onPreferredPaymentChanged(eq(NfcAdapter.PREFERRED_PAYMENT_UPDATED));
    }

    @Test
    public void testCardEmulationUnsetOffHostForService_serviceDoesNotExists()
            throws RemoteException {
        when(mRegisteredServicesCache.hasService(eq(USER_ID), any())).thenReturn(false);
        when(mRegisteredServicesCache.resetOffHostSecureElement(eq(USER_ID), anyInt(), any()))
                .thenReturn(true);

        assertFalse(
                mCardEmulationManager
                        .getNfcCardEmulationInterface()
                        .unsetOffHostForService(USER_ID, WALLET_PAYMENT_SERVICE));

        ExtendedMockito.verify(
                () -> {
                    NfcPermissions.enforceUserPermissions(mContext);
                });
        ExtendedMockito.verify(
                () -> {
                    NfcPermissions.validateUserId(USER_ID);
                });
        verify(mRegisteredServicesCache).initialize();
        verify(mRegisteredServicesCache).invalidateCache(eq(USER_ID), eq(true));
        verify(mRegisteredServicesCache, times(2))
                .hasService(eq(USER_ID), eq(WALLET_PAYMENT_SERVICE));
        verifyNoMoreInteractions(mRegisteredServicesCache);
    }

    @Test
    public void testCardEmulationGetAidGroupForService_serviceExists() throws RemoteException {
        AidGroup aidGroup = Mockito.mock(AidGroup.class);
        when(mRegisteredServicesCache.hasService(eq(USER_ID), any())).thenReturn(true);
        when(mRegisteredServicesCache.getAidGroupForService(
                eq(USER_ID), anyInt(), any(), eq(CardEmulation.CATEGORY_PAYMENT)))
                .thenReturn(aidGroup);

        assertEquals(
                aidGroup,
                mCardEmulationManager
                        .getNfcCardEmulationInterface()
                        .getAidGroupForService(
                                USER_ID, WALLET_PAYMENT_SERVICE, CardEmulation.CATEGORY_PAYMENT));

        ExtendedMockito.verify(
                () -> {
                    NfcPermissions.enforceUserPermissions(mContext);
                });
        ExtendedMockito.verify(
                () -> {
                    NfcPermissions.validateUserId(USER_ID);
                });
        verify(mRegisteredServicesCache).initialize();
        verify(mRegisteredServicesCache, times(2))
                .hasService(eq(USER_ID), eq(WALLET_PAYMENT_SERVICE));
        verify(mRegisteredServicesCache)
                .getAidGroupForService(
                        eq(USER_ID),
                        anyInt(),
                        eq(WALLET_PAYMENT_SERVICE),
                        eq(CardEmulation.CATEGORY_PAYMENT));
        verifyNoMoreInteractions(mRegisteredServicesCache);
    }

    @Test
    public void testCardEmulationGetAidGroupForService_serviceDoesNotExists()
            throws RemoteException {
        AidGroup aidGroup = Mockito.mock(AidGroup.class);
        when(mRegisteredServicesCache.hasService(eq(USER_ID), any())).thenReturn(false);
        when(mRegisteredServicesCache.getAidGroupForService(
                eq(USER_ID), anyInt(), any(), eq(CardEmulation.CATEGORY_PAYMENT)))
                .thenReturn(aidGroup);

        assertNull(
                mCardEmulationManager
                        .getNfcCardEmulationInterface()
                        .getAidGroupForService(
                                USER_ID, WALLET_PAYMENT_SERVICE, CardEmulation.CATEGORY_PAYMENT));

        ExtendedMockito.verify(
                () -> {
                    NfcPermissions.enforceUserPermissions(mContext);
                });
        ExtendedMockito.verify(
                () -> {
                    NfcPermissions.validateUserId(USER_ID);
                });
        verify(mRegisteredServicesCache).initialize();
        verify(mRegisteredServicesCache).invalidateCache(eq(USER_ID), eq(true));
        verify(mRegisteredServicesCache, times(2))
                .hasService(eq(USER_ID), eq(WALLET_PAYMENT_SERVICE));
        verifyNoMoreInteractions(mRegisteredServicesCache);
    }

    @Test
    public void testCardEmulationRemoveAidGroupForService_serviceExists() throws RemoteException {
        AidGroup aidGroup = Mockito.mock(AidGroup.class);
        when(mRegisteredServicesCache.hasService(eq(USER_ID), any())).thenReturn(true);
        when(mRegisteredServicesCache.removeAidGroupForService(
                eq(USER_ID), anyInt(), any(), eq(CardEmulation.CATEGORY_PAYMENT)))
                .thenReturn(true);
        assertTrue(
                mCardEmulationManager
                        .getNfcCardEmulationInterface()
                        .removeAidGroupForService(
                                USER_ID, WALLET_PAYMENT_SERVICE, CardEmulation.CATEGORY_PAYMENT));

        ExtendedMockito.verify(
                () -> {
                    NfcPermissions.enforceUserPermissions(mContext);
                });
        ExtendedMockito.verify(
                () -> {
                    NfcPermissions.validateUserId(USER_ID);
                });
        verify(mRegisteredServicesCache).initialize();
        verify(mRegisteredServicesCache, times(2))
                .hasService(eq(USER_ID), eq(WALLET_PAYMENT_SERVICE));
        verify(mRegisteredServicesCache)
                .removeAidGroupForService(
                        eq(USER_ID),
                        anyInt(),
                        eq(WALLET_PAYMENT_SERVICE),
                        eq(CardEmulation.CATEGORY_PAYMENT));
        verifyNoMoreInteractions(mRegisteredServicesCache);
        verify(mNfcService).onPreferredPaymentChanged(eq(NfcAdapter.PREFERRED_PAYMENT_UPDATED));
    }

    @Test
    public void testCardEmulationRemoveAidGroupForService_serviceDoesNotExists()
            throws RemoteException {
        when(mRegisteredServicesCache.hasService(eq(USER_ID), any())).thenReturn(false);
        when(mRegisteredServicesCache.removeAidGroupForService(
                eq(USER_ID), anyInt(), any(), eq(CardEmulation.CATEGORY_PAYMENT)))
                .thenReturn(true);

        assertFalse(
                mCardEmulationManager
                        .getNfcCardEmulationInterface()
                        .removeAidGroupForService(
                                USER_ID, WALLET_PAYMENT_SERVICE, CardEmulation.CATEGORY_PAYMENT));

        ExtendedMockito.verify(
                () -> {
                    NfcPermissions.enforceUserPermissions(mContext);
                });
        ExtendedMockito.verify(
                () -> {
                    NfcPermissions.validateUserId(USER_ID);
                });
        verify(mRegisteredServicesCache).initialize();
        verify(mRegisteredServicesCache).invalidateCache(eq(USER_ID), eq(true));
        verify(mRegisteredServicesCache, times(2))
                .hasService(eq(USER_ID), eq(WALLET_PAYMENT_SERVICE));
        verifyNoMoreInteractions(mRegisteredServicesCache);
    }

    @Test
    public void testCardEmulationGetServices() throws RemoteException {
        when(mRegisteredServicesCache.hasService(eq(USER_ID), any())).thenReturn(false);
        when(mRegisteredServicesCache.getServicesForCategory(
                eq(USER_ID), eq(CardEmulation.CATEGORY_PAYMENT)))
                .thenReturn(UPDATED_SERVICES);

        assertEquals(
                UPDATED_SERVICES,
                mCardEmulationManager
                        .getNfcCardEmulationInterface()
                        .getServices(USER_ID, CardEmulation.CATEGORY_PAYMENT));

        ExtendedMockito.verify(
                () -> {
                    NfcPermissions.validateProfileId(mContext, USER_ID);
                });
        ExtendedMockito.verify(
                () -> {
                    NfcPermissions.enforceAdminPermissions(mContext);
                });
        verify(mRegisteredServicesCache).initialize();
        verify(mRegisteredServicesCache)
                .getServicesForCategory(eq(USER_ID), eq(CardEmulation.CATEGORY_PAYMENT));
        verifyNoMoreInteractions(mRegisteredServicesCache);
    }

    @Test
    public void testCardEmulationSetPreferredService_serviceExists() throws RemoteException {
        when(mRegisteredServicesCache.hasService(eq(USER_ID), any())).thenReturn(true);
        when(mPreferredServices.registerPreferredForegroundService(
                eq(WALLET_PAYMENT_SERVICE), anyInt()))
                .thenReturn(true);

        assertTrue(
                mCardEmulationManager
                        .getNfcCardEmulationInterface()
                        .setPreferredService(WALLET_PAYMENT_SERVICE));

        ExtendedMockito.verify(
                () -> {
                    NfcPermissions.enforceUserPermissions(mContext);
                });
        verify(mRegisteredServicesCache).initialize();
        verify(mRegisteredServicesCache, times(2))
                .hasService(eq(USER_ID), eq(WALLET_PAYMENT_SERVICE));
        verifyNoMoreInteractions(mRegisteredServicesCache);
        verify(mPreferredServices)
                .onWalletRoleHolderChanged(eq(WALLET_HOLDER_PACKAGE_NAME), eq(USER_ID));
        verify(mPreferredServices)
                .registerPreferredForegroundService(eq(WALLET_PAYMENT_SERVICE), anyInt());
        verifyNoMoreInteractions(mPreferredServices);
    }

    @Test
    public void testCardEmulationSetPreferredService_serviceDoesNotExists() throws RemoteException {
        when(mRegisteredServicesCache.hasService(eq(USER_ID), any())).thenReturn(false);
        when(mPreferredServices.registerPreferredForegroundService(
                eq(WALLET_PAYMENT_SERVICE), anyInt()))
                .thenReturn(false);

        assertFalse(
                mCardEmulationManager
                        .getNfcCardEmulationInterface()
                        .setPreferredService(WALLET_PAYMENT_SERVICE));

        ExtendedMockito.verify(
                () -> {
                    NfcPermissions.enforceUserPermissions(mContext);
                });
        verify(mRegisteredServicesCache).initialize();
        verify(mRegisteredServicesCache).invalidateCache(eq(USER_ID), eq(true));
        verify(mRegisteredServicesCache, times(2))
                .hasService(eq(USER_ID), eq(WALLET_PAYMENT_SERVICE));
        verifyNoMoreInteractions(mRegisteredServicesCache);
        verify(mPreferredServices)
                .onWalletRoleHolderChanged(eq(WALLET_HOLDER_PACKAGE_NAME), eq(USER_ID));
        verifyNoMoreInteractions(mPreferredServices);
    }

    @Test
    public void testCardEmulationUnsetPreferredService_serviceExists() throws RemoteException {
        when(mRegisteredServicesCache.hasService(eq(USER_ID), any())).thenReturn(true);
        when(mPreferredServices.unregisteredPreferredForegroundService(anyInt())).thenReturn(true);

        assertTrue(mCardEmulationManager.getNfcCardEmulationInterface().unsetPreferredService());

        ExtendedMockito.verify(
                () -> {
                    NfcPermissions.enforceUserPermissions(mContext);
                });
        verify(mPreferredServices)
                .onWalletRoleHolderChanged(eq(WALLET_HOLDER_PACKAGE_NAME), eq(USER_ID));
        verify(mPreferredServices).unregisteredPreferredForegroundService(anyInt());
        verifyNoMoreInteractions(mPreferredServices);
    }

    @Test
    public void testCardEmulationUnsetPreferredService_serviceDoesNotExists()
            throws RemoteException {
        when(mPreferredServices.unregisteredPreferredForegroundService(anyInt())).thenReturn(false);

        assertFalse(mCardEmulationManager.getNfcCardEmulationInterface().unsetPreferredService());

        ExtendedMockito.verify(
                () -> {
                    NfcPermissions.enforceUserPermissions(mContext);
                });
        verify(mPreferredServices).unregisteredPreferredForegroundService(anyInt());
    }

    @Test
    public void testCardEmulationSupportsAidPrefixRegistration_doesSupport()
            throws RemoteException {
        when(mRegisteredAidCache.supportsAidPrefixRegistration()).thenReturn(true);

        assertTrue(
                mCardEmulationManager
                        .getNfcCardEmulationInterface()
                        .supportsAidPrefixRegistration());

        verify(mRegisteredAidCache).supportsAidPrefixRegistration();
    }

    @Test
    public void testCardEmulationSupportsAidPrefixRegistration_doesNotSupport()
            throws RemoteException {
        when(mRegisteredAidCache.supportsAidPrefixRegistration()).thenReturn(false);

        assertFalse(
                mCardEmulationManager
                        .getNfcCardEmulationInterface()
                        .supportsAidPrefixRegistration());

        verify(mRegisteredAidCache)
                .onWalletRoleHolderChanged(eq(WALLET_HOLDER_PACKAGE_NAME), eq(USER_ID));
        verify(mRegisteredAidCache).supportsAidPrefixRegistration();
        verifyNoMoreInteractions(mRegisteredAidCache);
    }

    @Test
    public void testCardEmulationGetPreferredPaymentService() throws RemoteException {
        ApduServiceInfo apduServiceInfo = Mockito.mock(ApduServiceInfo.class);
        when(mRegisteredAidCache.getPreferredService())
                .thenReturn(new ComponentNameAndUser(USER_ID, WALLET_PAYMENT_SERVICE));
        when(mRegisteredServicesCache.getService(eq(USER_ID), eq(WALLET_PAYMENT_SERVICE)))
                .thenReturn(apduServiceInfo);

        assertEquals(
                apduServiceInfo,
                mCardEmulationManager
                        .getNfcCardEmulationInterface()
                        .getPreferredPaymentService(USER_ID));

        ExtendedMockito.verify(
                () -> {
                    NfcPermissions.validateUserId(USER_ID);
                });
        ExtendedMockito.verify(
                () -> {
                    NfcPermissions.enforceUserPermissions(mContext);
                });
        ExtendedMockito.verify(
                () -> {
                    NfcPermissions.enforcePreferredPaymentInfoPermissions(mContext);
                });
        verify(mRegisteredServicesCache).initialize();
        verify(mRegisteredAidCache)
                .onWalletRoleHolderChanged(eq(WALLET_HOLDER_PACKAGE_NAME), eq(USER_ID));
        verify(mRegisteredAidCache).getPreferredService();
        verify(mRegisteredServicesCache).getService(eq(USER_ID), eq(WALLET_PAYMENT_SERVICE));
        verifyNoMoreInteractions(mRegisteredAidCache);
        verifyNoMoreInteractions(mRegisteredServicesCache);
    }

    @Test
    public void testCardEmulationSetServiceEnabledForCategoryOther_resourceTrue()
            throws RemoteException {
        when(mDeviceConfigFacade.getEnableServiceOther()).thenReturn(true);
        when(mRegisteredServicesCache.registerOtherForService(anyInt(), any(), anyBoolean()))
                .thenReturn(SET_SERVICE_ENABLED_STATUS_OK);

        assertEquals(SET_SERVICE_ENABLED_STATUS_OK,
                mCardEmulationManager
                        .getNfcCardEmulationInterface()
                        .setServiceEnabledForCategoryOther(USER_ID, WALLET_PAYMENT_SERVICE, true)
        );

        ExtendedMockito.verify(
                () -> {
                    NfcPermissions.enforceUserPermissions(mContext);
                });
        verify(mRegisteredServicesCache).initialize();
        verify(mRegisteredServicesCache)
                .registerOtherForService(eq(USER_ID), eq(WALLET_PAYMENT_SERVICE), eq(true));
        verifyNoMoreInteractions(mRegisteredServicesCache);
    }

    @Test
    public void testCardEmulationSetServiceEnabledForCategoryOther_resourceFalse()
            throws RemoteException {
        when(mDeviceConfigFacade.getEnableServiceOther()).thenReturn(false);
        when(mRegisteredServicesCache.registerOtherForService(anyInt(), any(), anyBoolean()))
                .thenReturn(SET_SERVICE_ENABLED_STATUS_OK);

        assertEquals(SET_SERVICE_ENABLED_STATUS_FAILURE_FEATURE_UNSUPPORTED,
                mCardEmulationManager
                        .getNfcCardEmulationInterface()
                        .setServiceEnabledForCategoryOther(USER_ID, WALLET_PAYMENT_SERVICE, true)
        );

        verify(mRegisteredServicesCache).initialize();
        verifyNoMoreInteractions(mRegisteredServicesCache);
    }

    @Test
    public void testCardEmulationIsDefaultPaymentRegistered_walletRoleEnabledWalletSet()
            throws RemoteException {
        when(mWalletRoleObserver.isWalletRoleFeatureEnabled()).thenReturn(true);
        when(mWalletRoleObserver.getDefaultWalletRoleHolder(anyInt()))
                .thenReturn(new PackageAndUser(USER_ID, WALLET_HOLDER_PACKAGE_NAME));
        when(Binder.getCallingUserHandle()).thenReturn(USER_HANDLE);

        assertTrue(
                mCardEmulationManager.getNfcCardEmulationInterface().isDefaultPaymentRegistered());

        verify(mWalletRoleObserver, times(2)).isWalletRoleFeatureEnabled();
        verify(mWalletRoleObserver, times(2)).getDefaultWalletRoleHolder(eq(USER_ID));
    }

    @Test
    public void testCardEmulationIsDefaultPaymentRegistered_walletRoleEnabledWalletNone()
            throws RemoteException {
        when(mWalletRoleObserver.isWalletRoleFeatureEnabled()).thenReturn(true);
        when(mWalletRoleObserver.getDefaultWalletRoleHolder(anyInt())).thenReturn(
                new PackageAndUser(USER_ID, null));
        when(Binder.getCallingUserHandle()).thenReturn(USER_HANDLE);

        assertFalse(
                mCardEmulationManager.getNfcCardEmulationInterface().isDefaultPaymentRegistered());

        verify(mWalletRoleObserver, times(2)).isWalletRoleFeatureEnabled();
        verify(mWalletRoleObserver, times(2)).getDefaultWalletRoleHolder(eq(USER_ID));
    }

    @Test
    public void testCardEmulationOverrideRoutingTable_callerNotForeground() throws RemoteException {
        when(mForegroundUtils.registerUidToBackgroundCallback(any(), anyInt())).thenReturn(false);
        String protocol = "DH";
        String technology = "DH";

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        mCardEmulationManager
                                .getNfcCardEmulationInterface()
                                .overrideRoutingTable(
                                        USER_ID, protocol, technology, WALLET_HOLDER_PACKAGE_NAME));

        verify(mRegisteredAidCache)
                .onWalletRoleHolderChanged(eq(WALLET_HOLDER_PACKAGE_NAME), eq(USER_ID));
        verify(mRoutingOptionManager).getOffHostRouteEse();
        verify(mRoutingOptionManager).getOffHostRouteUicc();
        verifyNoMoreInteractions(mRoutingOptionManager);
        verifyNoMoreInteractions(mRegisteredAidCache);
    }

    @Test
    public void testCardEmulationOverrideRoutingTable_callerForegroundRouteNull()
            throws RemoteException {
        when(mForegroundUtils.registerUidToBackgroundCallback(any(), anyInt())).thenReturn(true);

        mCardEmulationManager
                .getNfcCardEmulationInterface()
                .overrideRoutingTable(USER_ID, null, null, WALLET_HOLDER_PACKAGE_NAME);

        verify(mRegisteredAidCache)
                .onWalletRoleHolderChanged(eq(WALLET_HOLDER_PACKAGE_NAME), eq(USER_ID));
        verify(mRoutingOptionManager).overrideDefaultIsoDepRoute(eq(-1));
        verify(mRoutingOptionManager).overrideDefaultOffHostRoute(eq(-1));
        verify(mRoutingOptionManager).getOffHostRouteEse();
        verify(mRoutingOptionManager).getOffHostRouteUicc();
        verify(mRegisteredAidCache).onRoutingOverridedOrRecovered();
        verifyNoMoreInteractions(mRoutingOptionManager);
        verifyNoMoreInteractions(mRegisteredAidCache);
    }

    @Test
    public void testCardEmulationOverrideRoutingTable_callerForegroundRouteDH()
            throws RemoteException {
        when(mForegroundUtils.registerUidToBackgroundCallback(any(), anyInt())).thenReturn(true);
        String protocol = "DH";
        String technology = "DH";

        mCardEmulationManager
                .getNfcCardEmulationInterface()
                .overrideRoutingTable(USER_ID, protocol, technology, WALLET_HOLDER_PACKAGE_NAME);

        verify(mRegisteredAidCache)
                .onWalletRoleHolderChanged(eq(WALLET_HOLDER_PACKAGE_NAME), eq(USER_ID));
        verify(mRoutingOptionManager).overrideDefaultIsoDepRoute(eq(0));
        verify(mRoutingOptionManager).overrideDefaultOffHostRoute(eq(0));
        verify(mRoutingOptionManager).getOffHostRouteEse();
        verify(mRoutingOptionManager).getOffHostRouteUicc();
        verify(mRegisteredAidCache).onRoutingOverridedOrRecovered();
        verifyNoMoreInteractions(mRoutingOptionManager);
        verifyNoMoreInteractions(mRegisteredAidCache);
    }

    @Test
    public void testCardEmulationOverrideRoutingTable_callerForegroundRouteeSE()
            throws RemoteException {
        when(mForegroundUtils.registerUidToBackgroundCallback(any(), anyInt())).thenReturn(true);
        String protocol = "eSE1";
        String technology = "eSE1";

        mCardEmulationManager
                .getNfcCardEmulationInterface()
                .overrideRoutingTable(USER_ID, protocol, technology, WALLET_HOLDER_PACKAGE_NAME);

        verify(mRegisteredAidCache)
                .onWalletRoleHolderChanged(eq(WALLET_HOLDER_PACKAGE_NAME), eq(USER_ID));
        verify(mRoutingOptionManager).overrideDefaultIsoDepRoute(eq(TEST_DATA_1[0] & 0xFF));
        verify(mRoutingOptionManager).overrideDefaultOffHostRoute(eq(TEST_DATA_1[0] & 0xFF));
        verify(mRoutingOptionManager).getOffHostRouteEse();
        verify(mRoutingOptionManager).getOffHostRouteUicc();
        verify(mRoutingOptionManager, times(2)).getRouteForSecureElement(eq("eSE1"));
        verify(mRegisteredAidCache).onRoutingOverridedOrRecovered();
        verifyNoMoreInteractions(mRoutingOptionManager);
        verifyNoMoreInteractions(mRegisteredAidCache);
    }

    @Test
    public void testCardEmulationOverrideRoutingTable_callerForegroundRouteSIM()
            throws RemoteException {
        when(mForegroundUtils.registerUidToBackgroundCallback(any(), anyInt())).thenReturn(true);
        String protocol = "SIM1";
        String technology = "SIM1";

        mCardEmulationManager
                .getNfcCardEmulationInterface()
                .overrideRoutingTable(USER_ID, protocol, technology, WALLET_HOLDER_PACKAGE_NAME);

        verify(mRegisteredAidCache)
                .onWalletRoleHolderChanged(eq(WALLET_HOLDER_PACKAGE_NAME), eq(USER_ID));
        verify(mRoutingOptionManager).overrideDefaultIsoDepRoute(eq(TEST_DATA_2[0] & 0xFF));
        verify(mRoutingOptionManager).overrideDefaultOffHostRoute(eq(TEST_DATA_2[0] & 0xFF));
        verify(mRoutingOptionManager).getOffHostRouteEse();
        verify(mRoutingOptionManager).getOffHostRouteUicc();
        verify(mRoutingOptionManager, times(2)).getRouteForSecureElement(eq("SIM1"));
        verify(mRegisteredAidCache).onRoutingOverridedOrRecovered();
        verifyNoMoreInteractions(mRoutingOptionManager);
        verifyNoMoreInteractions(mRegisteredAidCache);
    }

    @Test
    public void testCardEmulationRecoverRoutingTable_callerForeground() throws RemoteException {
        when(mForegroundUtils.isInForeground(anyInt())).thenReturn(true);

        mCardEmulationManager.getNfcCardEmulationInterface().recoverRoutingTable(USER_ID);

        verify(mRegisteredAidCache)
                .onWalletRoleHolderChanged(eq(WALLET_HOLDER_PACKAGE_NAME), eq(USER_ID));
        verify(mRoutingOptionManager).recoverOverridedRoutingTable();
        verify(mRoutingOptionManager).getOffHostRouteEse();
        verify(mRoutingOptionManager).getOffHostRouteUicc();
        verify(mRegisteredAidCache).onRoutingOverridedOrRecovered();
        verifyNoMoreInteractions(mRoutingOptionManager);
        verifyNoMoreInteractions(mRegisteredAidCache);
    }

    @Test
    public void testCardEmulationRecoverRoutingTable_callerNotForeground() throws RemoteException {
        when(mForegroundUtils.isInForeground(anyInt())).thenReturn(false);

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        mCardEmulationManager
                                .getNfcCardEmulationInterface()
                                .recoverRoutingTable(USER_ID));

        verify(mRegisteredAidCache)
                .onWalletRoleHolderChanged(eq(WALLET_HOLDER_PACKAGE_NAME), eq(USER_ID));
        verify(mRoutingOptionManager).getOffHostRouteEse();
        verify(mRoutingOptionManager).getOffHostRouteUicc();
        verifyNoMoreInteractions(mRoutingOptionManager);
        verifyNoMoreInteractions(mRegisteredAidCache);
    }

    @Test
    public void testNfcFCardEmulationGetSystemCodeForService_serviceExists()
            throws RemoteException {
        String systemCode = "systemCode";
        when(mRegisteredNfcFServicesCache.hasService(eq(USER_ID), any())).thenReturn(true);
        when(mRegisteredNfcFServicesCache.getSystemCodeForService(anyInt(), anyInt(), any()))
                .thenReturn(systemCode);

        assertEquals(
                systemCode,
                mCardEmulationManager
                        .getNfcFCardEmulationInterface()
                        .getSystemCodeForService(USER_ID, WALLET_PAYMENT_SERVICE));

        ExtendedMockito.verify(
                () -> {
                    NfcPermissions.validateUserId(USER_ID);
                });
        ExtendedMockito.verify(
                () -> {
                    NfcPermissions.enforceUserPermissions(mContext);
                });
        verify(mRegisteredNfcFServicesCache).initialize();
        verify(mRegisteredNfcFServicesCache, times(2))
                .hasService(eq(USER_ID), eq(WALLET_PAYMENT_SERVICE));
        verify(mRegisteredNfcFServicesCache)
                .getSystemCodeForService(eq(USER_ID), anyInt(), eq(WALLET_PAYMENT_SERVICE));
        verifyNoMoreInteractions(mRegisteredNfcFServicesCache);
    }

    @Test
    public void testNfcFCardEmulationGetSystemCodeForService_serviceDoesNotExists()
            throws RemoteException {
        String systemCode = "systemCode";
        when(mRegisteredNfcFServicesCache.hasService(eq(USER_ID), any())).thenReturn(false);
        when(mRegisteredNfcFServicesCache.getSystemCodeForService(anyInt(), anyInt(), any()))
                .thenReturn(systemCode);

        assertNull(
                mCardEmulationManager
                        .getNfcFCardEmulationInterface()
                        .getSystemCodeForService(USER_ID, WALLET_PAYMENT_SERVICE));

        ExtendedMockito.verify(
                () -> {
                    NfcPermissions.validateUserId(USER_ID);
                });
        ExtendedMockito.verify(
                () -> {
                    NfcPermissions.enforceUserPermissions(mContext);
                });
        verify(mRegisteredNfcFServicesCache).initialize();
        verify(mRegisteredNfcFServicesCache).invalidateCache(eq(USER_ID));
        verify(mRegisteredNfcFServicesCache, times(2))
                .hasService(eq(USER_ID), eq(WALLET_PAYMENT_SERVICE));
        verifyNoMoreInteractions(mRegisteredNfcFServicesCache);
    }

    @Test
    public void testNfcFCardEmulationRegisterSystemCodeForService_serviceExists()
            throws RemoteException {
        String systemCode = "systemCode";
        when(mRegisteredNfcFServicesCache.hasService(eq(USER_ID), any())).thenReturn(true);
        when(mRegisteredNfcFServicesCache.registerSystemCodeForService(
                anyInt(), anyInt(), any(), anyString()))
                .thenReturn(true);

        assertTrue(
                mCardEmulationManager
                        .getNfcFCardEmulationInterface()
                        .registerSystemCodeForService(USER_ID, WALLET_PAYMENT_SERVICE, systemCode));

        ExtendedMockito.verify(
                () -> {
                    NfcPermissions.validateUserId(USER_ID);
                });
        ExtendedMockito.verify(
                () -> {
                    NfcPermissions.enforceUserPermissions(mContext);
                });
        verify(mRegisteredNfcFServicesCache).initialize();
        verify(mRegisteredNfcFServicesCache, times(2))
                .hasService(eq(USER_ID), eq(WALLET_PAYMENT_SERVICE));
        verify(mRegisteredNfcFServicesCache)
                .registerSystemCodeForService(
                        eq(USER_ID), anyInt(), eq(WALLET_PAYMENT_SERVICE), eq(systemCode));
        verifyNoMoreInteractions(mRegisteredNfcFServicesCache);
    }

    @Test
    public void testNfcFCardEmulationRegisterSystemCodeForService_serviceDoesNotExists()
            throws RemoteException {
        String systemCode = "systemCode";
        when(mRegisteredNfcFServicesCache.hasService(eq(USER_ID), any())).thenReturn(false);
        when(mRegisteredNfcFServicesCache.registerSystemCodeForService(
                anyInt(), anyInt(), any(), anyString()))
                .thenReturn(true);

        assertFalse(
                mCardEmulationManager
                        .getNfcFCardEmulationInterface()
                        .registerSystemCodeForService(USER_ID, WALLET_PAYMENT_SERVICE, systemCode));

        ExtendedMockito.verify(
                () -> {
                    NfcPermissions.validateUserId(USER_ID);
                });
        ExtendedMockito.verify(
                () -> {
                    NfcPermissions.enforceUserPermissions(mContext);
                });
        verify(mRegisteredNfcFServicesCache).initialize();
        verify(mRegisteredNfcFServicesCache).invalidateCache(eq(USER_ID));
        verify(mRegisteredNfcFServicesCache, times(2))
                .hasService(eq(USER_ID), eq(WALLET_PAYMENT_SERVICE));
        verifyNoMoreInteractions(mRegisteredNfcFServicesCache);
    }

    @Test
    public void testNfcFCardEmulationRemoveSystemCodeForService_serviceExists()
            throws RemoteException {
        when(mRegisteredNfcFServicesCache.hasService(eq(USER_ID), any())).thenReturn(true);
        when(mRegisteredNfcFServicesCache.removeSystemCodeForService(anyInt(), anyInt(), any()))
                .thenReturn(true);

        assertTrue(
                mCardEmulationManager
                        .getNfcFCardEmulationInterface()
                        .removeSystemCodeForService(USER_ID, WALLET_PAYMENT_SERVICE));

        ExtendedMockito.verify(
                () -> {
                    NfcPermissions.validateUserId(USER_ID);
                });
        ExtendedMockito.verify(
                () -> {
                    NfcPermissions.enforceUserPermissions(mContext);
                });
        verify(mRegisteredNfcFServicesCache).initialize();
        verify(mRegisteredNfcFServicesCache, times(2))
                .hasService(eq(USER_ID), eq(WALLET_PAYMENT_SERVICE));
        verify(mRegisteredNfcFServicesCache)
                .removeSystemCodeForService(eq(USER_ID), anyInt(), eq(WALLET_PAYMENT_SERVICE));
        verifyNoMoreInteractions(mRegisteredNfcFServicesCache);
    }

    @Test
    public void testNfcFCardEmulationRemoveSystemCodeForService_serviceDoesNotExists()
            throws RemoteException {
        when(mRegisteredNfcFServicesCache.hasService(eq(USER_ID), any())).thenReturn(false);
        when(mRegisteredNfcFServicesCache.removeSystemCodeForService(anyInt(), anyInt(), any()))
                .thenReturn(true);

        assertFalse(
                mCardEmulationManager
                        .getNfcFCardEmulationInterface()
                        .removeSystemCodeForService(USER_ID, WALLET_PAYMENT_SERVICE));

        ExtendedMockito.verify(
                () -> {
                    NfcPermissions.validateUserId(USER_ID);
                });
        ExtendedMockito.verify(
                () -> {
                    NfcPermissions.enforceUserPermissions(mContext);
                });
        verify(mRegisteredNfcFServicesCache).initialize();
        verify(mRegisteredNfcFServicesCache).invalidateCache(eq(USER_ID));
        verify(mRegisteredNfcFServicesCache, times(2))
                .hasService(eq(USER_ID), eq(WALLET_PAYMENT_SERVICE));
        verifyNoMoreInteractions(mRegisteredNfcFServicesCache);
    }

    @Test
    public void testNfcFCardEmulationGetNfcid2ForService_serviceExists() throws RemoteException {
        String nfcid2 = "nfcid2";
        when(mRegisteredNfcFServicesCache.hasService(eq(USER_ID), any())).thenReturn(true);
        when(mRegisteredNfcFServicesCache.getNfcid2ForService(anyInt(), anyInt(), any()))
                .thenReturn(nfcid2);

        assertEquals(
                nfcid2,
                mCardEmulationManager
                        .getNfcFCardEmulationInterface()
                        .getNfcid2ForService(USER_ID, WALLET_PAYMENT_SERVICE));

        ExtendedMockito.verify(
                () -> {
                    NfcPermissions.validateUserId(USER_ID);
                });
        ExtendedMockito.verify(
                () -> {
                    NfcPermissions.enforceUserPermissions(mContext);
                });
        verify(mRegisteredNfcFServicesCache).initialize();
        verify(mRegisteredNfcFServicesCache, times(2))
                .hasService(eq(USER_ID), eq(WALLET_PAYMENT_SERVICE));
        verify(mRegisteredNfcFServicesCache)
                .getNfcid2ForService(eq(USER_ID), anyInt(), eq(WALLET_PAYMENT_SERVICE));
        verifyNoMoreInteractions(mRegisteredNfcFServicesCache);
    }

    @Test
    public void testNfcFCardEmulationGetNfcid2ForService_serviceDoesNotExists()
            throws RemoteException {
        String nfcid2 = "nfcid2";
        when(mRegisteredNfcFServicesCache.hasService(eq(USER_ID), any())).thenReturn(false);
        when(mRegisteredNfcFServicesCache.getNfcid2ForService(anyInt(), anyInt(), any()))
                .thenReturn(nfcid2);

        assertNull(
                mCardEmulationManager
                        .getNfcFCardEmulationInterface()
                        .getNfcid2ForService(USER_ID, WALLET_PAYMENT_SERVICE));

        ExtendedMockito.verify(
                () -> {
                    NfcPermissions.validateUserId(USER_ID);
                });
        ExtendedMockito.verify(
                () -> {
                    NfcPermissions.enforceUserPermissions(mContext);
                });
        verify(mRegisteredNfcFServicesCache).initialize();
        verify(mRegisteredNfcFServicesCache).invalidateCache(eq(USER_ID));
        verify(mRegisteredNfcFServicesCache, times(2))
                .hasService(eq(USER_ID), eq(WALLET_PAYMENT_SERVICE));
        verifyNoMoreInteractions(mRegisteredNfcFServicesCache);
    }

    @Test
    public void testNfcFCardEmulationSetNfcid2ForService_serviceExists() throws RemoteException {
        String nfcid2 = "nfcid2";
        when(mRegisteredNfcFServicesCache.hasService(eq(USER_ID), any())).thenReturn(true);
        when(mRegisteredNfcFServicesCache.setNfcid2ForService(
                anyInt(), anyInt(), any(), anyString()))
                .thenReturn(true);

        assertTrue(
                mCardEmulationManager
                        .getNfcFCardEmulationInterface()
                        .setNfcid2ForService(USER_ID, WALLET_PAYMENT_SERVICE, nfcid2));

        ExtendedMockito.verify(
                () -> {
                    NfcPermissions.validateUserId(USER_ID);
                });
        ExtendedMockito.verify(
                () -> {
                    NfcPermissions.enforceUserPermissions(mContext);
                });
        verify(mRegisteredNfcFServicesCache).initialize();
        verify(mRegisteredNfcFServicesCache, times(2))
                .hasService(eq(USER_ID), eq(WALLET_PAYMENT_SERVICE));
        verify(mRegisteredNfcFServicesCache)
                .setNfcid2ForService(eq(USER_ID), anyInt(), eq(WALLET_PAYMENT_SERVICE), eq(nfcid2));
        verifyNoMoreInteractions(mRegisteredNfcFServicesCache);
    }

    @Test
    public void testNfcFCardEmulationSetNfcid2ForService_serviceDoesNotExists()
            throws RemoteException {
        String nfcid2 = "nfcid2";
        when(mRegisteredNfcFServicesCache.hasService(eq(USER_ID), any())).thenReturn(false);
        when(mRegisteredNfcFServicesCache.setNfcid2ForService(
                anyInt(), anyInt(), any(), anyString()))
                .thenReturn(true);

        assertFalse(
                mCardEmulationManager
                        .getNfcFCardEmulationInterface()
                        .setNfcid2ForService(USER_ID, WALLET_PAYMENT_SERVICE, nfcid2));

        ExtendedMockito.verify(
                () -> {
                    NfcPermissions.validateUserId(USER_ID);
                });
        ExtendedMockito.verify(
                () -> {
                    NfcPermissions.enforceUserPermissions(mContext);
                });
        verify(mRegisteredNfcFServicesCache).initialize();
        verify(mRegisteredNfcFServicesCache).invalidateCache(eq(USER_ID));
        verify(mRegisteredNfcFServicesCache, times(2))
                .hasService(eq(USER_ID), eq(WALLET_PAYMENT_SERVICE));
        verifyNoMoreInteractions(mRegisteredNfcFServicesCache);
    }

    @Test
    public void testNfcFCardEmulationEnableNfcFForegroundService_serviceExists()
            throws RemoteException {
        when(mRegisteredNfcFServicesCache.hasService(eq(USER_ID), any())).thenReturn(true);
        when(mEnabledNfcFServices.registerEnabledForegroundService(any(), anyInt()))
                .thenReturn(true);
        when(Binder.getCallingUserHandle()).thenReturn(USER_HANDLE);

        assertTrue(
                mCardEmulationManager
                        .getNfcFCardEmulationInterface()
                        .enableNfcFForegroundService(WALLET_PAYMENT_SERVICE));

        ExtendedMockito.verify(
                () -> {
                    NfcPermissions.enforceUserPermissions(mContext);
                });
        verify(mRegisteredNfcFServicesCache).initialize();
        verify(mRegisteredNfcFServicesCache, times(2))
                .hasService(eq(USER_ID), eq(WALLET_PAYMENT_SERVICE));
        verify(mEnabledNfcFServices)
                .registerEnabledForegroundService(eq(WALLET_PAYMENT_SERVICE), anyInt());
        verifyNoMoreInteractions(mRegisteredNfcFServicesCache);
    }

    @Test
    public void testNfcFCardEmulationEnableNfcFForegroundService_serviceDoesNotExists()
            throws RemoteException {
        when(mRegisteredNfcFServicesCache.hasService(eq(USER_ID), any())).thenReturn(false);
        when(mEnabledNfcFServices.registerEnabledForegroundService(any(), anyInt()))
                .thenReturn(true);

        assertFalse(
                mCardEmulationManager
                        .getNfcFCardEmulationInterface()
                        .enableNfcFForegroundService(WALLET_PAYMENT_SERVICE));

        ExtendedMockito.verify(
                () -> {
                    NfcPermissions.enforceUserPermissions(mContext);
                });
        verify(mRegisteredNfcFServicesCache).initialize();
        verify(mRegisteredNfcFServicesCache).invalidateCache(eq(USER_ID));
        verify(mRegisteredNfcFServicesCache, times(2))
                .hasService(eq(USER_ID), eq(WALLET_PAYMENT_SERVICE));
        verifyNoMoreInteractions(mRegisteredNfcFServicesCache);
        verifyNoMoreInteractions(mEnabledNfcFServices);
    }

    @Test
    public void testNfcFCardEmulationDisableNfcFForegroundService_serviceDoesNotExists()
            throws RemoteException {
        when(mEnabledNfcFServices.unregisteredEnabledForegroundService(anyInt())).thenReturn(true);

        assertTrue(
                mCardEmulationManager
                        .getNfcFCardEmulationInterface()
                        .disableNfcFForegroundService());

        ExtendedMockito.verify(
                () -> {
                    NfcPermissions.enforceUserPermissions(mContext);
                });
        verify(mEnabledNfcFServices).unregisteredEnabledForegroundService(anyInt());
        verifyNoMoreInteractions(mEnabledNfcFServices);
    }

    @Test
    public void testNfcFCardEmulationGetServices() throws RemoteException {
        when(mRegisteredNfcFServicesCache.getServices(anyInt())).thenReturn(UPDATED_NFC_SERVICES);

        assertEquals(
                UPDATED_NFC_SERVICES,
                mCardEmulationManager.getNfcFCardEmulationInterface().getNfcFServices(USER_ID));

        ExtendedMockito.verify(
                () -> {
                    NfcPermissions.validateProfileId(mContext, USER_ID);
                });
        ExtendedMockito.verify(
                () -> {
                    NfcPermissions.enforceUserPermissions(mContext);
                });
        verify(mRegisteredNfcFServicesCache).initialize();
        verify(mRegisteredNfcFServicesCache).getServices(eq(USER_ID));
        verifyNoMoreInteractions(mRegisteredNfcFServicesCache);
    }

    @Test
    public void testNfcFCardEmulationGetMaxNumOfRegisterableSystemCodes() throws RemoteException {
        int MAX = 3;
        when(mNfcService.getLfT3tMax()).thenReturn(MAX);

        assertEquals(
                MAX,
                mCardEmulationManager
                        .getNfcFCardEmulationInterface()
                        .getMaxNumOfRegisterableSystemCodes());

        ExtendedMockito.verify(
                () -> {
                    NfcPermissions.enforceUserPermissions(mContext);
                });
        verify(mNfcService).getLfT3tMax();
    }

    @Test
    public void testOnPreferredPaymentServiceChanged_observeModeEnabled() {
        when(mRegisteredAidCache.getPreferredService())
                .thenReturn(new ComponentNameAndUser(-1, null));
        mCardEmulationManager.onPreferredPaymentServiceChanged(new ComponentNameAndUser(0, null));

        when(mRegisteredServicesCache.doesServiceShouldDefaultToObserveMode(anyInt(), any()))
                .thenReturn(true);
        ComponentNameAndUser componentNameAndUser =
                new ComponentNameAndUser(USER_ID, WALLET_PAYMENT_SERVICE);
        mCardEmulationManager.onPreferredPaymentServiceChanged(componentNameAndUser);
        when(mRegisteredAidCache.getPreferredService()).thenReturn(componentNameAndUser);

        verify(mHostEmulationManager).onPreferredPaymentServiceChanged(eq(componentNameAndUser));
        verify(mRegisteredAidCache)
                .onWalletRoleHolderChanged(eq(WALLET_HOLDER_PACKAGE_NAME), eq(USER_ID));
        verify(mRegisteredAidCache).onPreferredPaymentServiceChanged(eq(componentNameAndUser));
        verify(mRegisteredServicesCache).initialize();
        verify(mNfcService, times(2))
                .onPreferredPaymentChanged(eq(NfcAdapter.PREFERRED_PAYMENT_CHANGED));
    }


    @Test
    public void testOnPreferredForegroundServiceChanged_observeModeEnabled() {
        when(mRegisteredServicesCache.doesServiceShouldDefaultToObserveMode(anyInt(), any()))
                .thenReturn(true);
        when(mRegisteredAidCache.getPreferredService())
                .thenReturn(new ComponentNameAndUser(USER_ID, WALLET_PAYMENT_SERVICE));

        mCardEmulationManager.onPreferredForegroundServiceChanged(
                new ComponentNameAndUser(USER_ID, WALLET_PAYMENT_SERVICE));

        verify(mRegisteredAidCache)
                .onWalletRoleHolderChanged(eq(WALLET_HOLDER_PACKAGE_NAME), eq(USER_ID));
        verify(mHostEmulationManager)
                .onPreferredForegroundServiceChanged(
                        eq(new ComponentNameAndUser(USER_ID, WALLET_PAYMENT_SERVICE)));
        verify(mRegisteredServicesCache).initialize();
        verify(mNfcService).onPreferredPaymentChanged(eq(NfcAdapter.PREFERRED_PAYMENT_CHANGED));
    }

    @Test
    public void testOnPreferredPaymentServiceChanged_toNull_dontUpdateObserveMode() {
        when(mRegisteredServicesCache.doesServiceShouldDefaultToObserveMode(anyInt(), any()))
                .thenReturn(true);
        when(mRegisteredAidCache.getPreferredService())
                .thenReturn(new ComponentNameAndUser(USER_ID, WALLET_PAYMENT_SERVICE));

        mCardEmulationManager.onPreferredPaymentServiceChanged(
                new ComponentNameAndUser(USER_ID, null));

        verify(mRegisteredServicesCache).initialize();
        assertUpdateForShouldDefaultToObserveMode(false);
    }

    @Test
    public void testOnPreferredForegroundServiceChanged_toNull_dontUpdateObserveMode() {
        when(mRegisteredServicesCache.doesServiceShouldDefaultToObserveMode(anyInt(), any()))
                .thenReturn(true);
        when(mRegisteredAidCache.getPreferredService())
                .thenReturn(new ComponentNameAndUser(USER_ID, WALLET_PAYMENT_SERVICE));

        mCardEmulationManager.onPreferredForegroundServiceChanged(
                new ComponentNameAndUser(USER_ID, null));

        verify(mRegisteredServicesCache).initialize();
        assertUpdateForShouldDefaultToObserveMode(false);
    }

    @Test
    public void testOnWalletRoleHolderChanged() {
        mCardEmulationManager.onWalletRoleHolderChanged(WALLET_HOLDER_PACKAGE_NAME, USER_ID);

        verify(mPreferredServices, times(2))
                .onWalletRoleHolderChanged(eq(WALLET_HOLDER_PACKAGE_NAME), eq(USER_ID));
        verify(mRegisteredAidCache, times(2))
                .onWalletRoleHolderChanged(eq(WALLET_HOLDER_PACKAGE_NAME), eq(USER_ID));
        verifyNoMoreInteractions(mPreferredServices);
        verifyNoMoreInteractions(mRegisteredAidCache);
    }

    @Test
    public void testOnEnabledForegroundNfcFServiceChanged() {
        mCardEmulationManager.onEnabledForegroundNfcFServiceChanged(
                USER_ID, WALLET_PAYMENT_SERVICE);

        verify(mRegisteredT3tIdentifiersCache)
                .onEnabledForegroundNfcFServiceChanged(eq(USER_ID), eq(WALLET_PAYMENT_SERVICE));
        verify(mHostNfcFEmulationManager)
                .onEnabledForegroundNfcFServiceChanged(eq(USER_ID), eq(WALLET_PAYMENT_SERVICE));
        verifyNoMoreInteractions(mRegisteredT3tIdentifiersCache);
        verifyNoMoreInteractions(mHostNfcFEmulationManager);
    }

    @Test
    public void testGetRegisteredAidCategory() {
        RegisteredAidCache.AidResolveInfo aidResolveInfo =
                Mockito.mock(RegisteredAidCache.AidResolveInfo.class);
        when(aidResolveInfo.getCategory()).thenReturn(CardEmulation.CATEGORY_PAYMENT);

        when(mRegisteredAidCache.resolveAid(anyString())).thenReturn(aidResolveInfo);

        assertEquals(
                CardEmulation.CATEGORY_PAYMENT,
                mCardEmulationManager.getRegisteredAidCategory(PAYMENT_AID_1));

        verify(mRegisteredAidCache).resolveAid(eq(PAYMENT_AID_1));
        verify(aidResolveInfo).getCategory();
    }

    @Test
    public void testIsRequiresScreenOnServiceExist() {
        when(mRegisteredAidCache.isRequiresScreenOnServiceExist()).thenReturn(true);

        assertTrue(mCardEmulationManager.isRequiresScreenOnServiceExist());
    }

    private void assertUpdateForShouldDefaultToObserveMode(boolean flagEnabled) {
        if (flagEnabled) {
            ExtendedMockito.verify(
                    () -> {
                        NfcAdapter.getDefaultAdapter(mContext);
                    });
            verify(mRegisteredAidCache).getPreferredService();
            verify(mRegisteredServicesCache)
                    .doesServiceShouldDefaultToObserveMode(eq(USER_ID), eq(WALLET_PAYMENT_SERVICE));
            verify(mNfcAdapter).setObserveModeEnabled(eq(true));
        }
        verifyNoMoreInteractions(mNfcAdapter);
        verifyNoMoreInteractions(mRegisteredServicesCache);
    }

    private CardEmulationManager createInstanceWithMockParams() {
        when(mRoutingOptionManager.getOffHostRouteEse()).thenReturn(TEST_DATA_1);
        when(mRoutingOptionManager.getOffHostRouteUicc()).thenReturn(TEST_DATA_2);
        when(mRoutingOptionManager.getRouteForSecureElement("eSE1")).thenReturn(
                TEST_DATA_1[0] & 0xFF);
        when(mRoutingOptionManager.getRouteForSecureElement("SIM1")).thenReturn(
                TEST_DATA_2[0] & 0xFF);
        when(mWalletRoleObserver.isWalletRoleFeatureEnabled()).thenReturn(true);
        when(mWalletRoleObserver.getDefaultWalletRoleHolder(eq(USER_ID)))
                .thenReturn(new PackageAndUser(USER_ID, WALLET_HOLDER_PACKAGE_NAME));

        return new CardEmulationManager(
                mContext,
                mForegroundUtils,
                mWalletRoleObserver,
                mRegisteredAidCache,
                mRegisteredT3tIdentifiersCache,
                mHostEmulationManager,
                mHostNfcFEmulationManager,
                mRegisteredServicesCache,
                mRegisteredNfcFServicesCache,
                mPreferredServices,
                mEnabledNfcFServices,
                mRoutingOptionManager,
                mPowerManager,
                mNfcEventLog,
                mPreferredSubscriptionService,
                mStatsdUtils,
                mDeviceConfigFacade);
    }

    @Test
    public void testIsDefaultServiceForCategory() throws RemoteException {
        INfcCardEmulation iNfcCardEmulation = mCardEmulationManager.getNfcCardEmulationInterface();
        assertThat(iNfcCardEmulation).isNotNull();
        ComponentName componentName = ComponentName
                .unflattenFromString("com.android.test.component/.Component");
        when(mRegisteredServicesCache.hasService(anyInt(), any())).thenReturn(true);
        when(mWalletRoleObserver.isWalletRoleFeatureEnabled()).thenReturn(false);
        when(Settings.Secure.getString(any(), anyString()))
                .thenReturn("com.android.test.component/.Component");
        boolean result = iNfcCardEmulation
                .isDefaultServiceForCategory(1, componentName, "payment");
        assertThat(result).isTrue();
    }

    @Test
    public void testIsDefaultServiceForAid() throws RemoteException {
        INfcCardEmulation iNfcCardEmulation = mCardEmulationManager.getNfcCardEmulationInterface();
        assertThat(iNfcCardEmulation).isNotNull();
        when(mRegisteredServicesCache.hasService(anyInt(), any())).thenReturn(true);
        ComponentName componentName = ComponentName
                .unflattenFromString("com.android.test.component/.Component");
        when(mRegisteredAidCache.isDefaultServiceForAid(1, componentName, "test"))
                .thenReturn(true);
        boolean result = iNfcCardEmulation
                .isDefaultServiceForAid(1, componentName, "test");
        assertThat(result).isTrue();
    }

    @Test
    public void testSetDefaultServiceForCategory() throws RemoteException {
        INfcCardEmulation iNfcCardEmulation = mCardEmulationManager.getNfcCardEmulationInterface();
        assertThat(iNfcCardEmulation).isNotNull();
        when(mRegisteredServicesCache.hasService(anyInt(), any())).thenReturn(true);
        ComponentName componentName = ComponentName
                .unflattenFromString("com.android.test.component/.Component");
        when(mRegisteredAidCache.isDefaultServiceForAid(1, componentName, "test"))
                .thenReturn(true);
        boolean result = iNfcCardEmulation
                .setDefaultServiceForCategory(1, componentName, "payment");
        assertThat(result).isTrue();
    }

    @Test
    public void testSetDefaultForNextTap() throws RemoteException {
        INfcCardEmulation iNfcCardEmulation = mCardEmulationManager.getNfcCardEmulationInterface();
        assertThat(iNfcCardEmulation).isNotNull();
        when(mRegisteredServicesCache.hasService(anyInt(), any())).thenReturn(true);
        ComponentName componentName = ComponentName
                .unflattenFromString("com.android.test.component/.Component");
        when(mRegisteredAidCache.isDefaultServiceForAid(1, componentName, "test"))
                .thenReturn(true);
        iNfcCardEmulation.setDefaultForNextTap(1, componentName);
        verify(mPreferredServices).setDefaultForNextTap(1, componentName);
    }

    @Test
    public void testSetShouldDefaultToObserveModeForService() throws RemoteException {
        INfcCardEmulation iNfcCardEmulation = mCardEmulationManager.getNfcCardEmulationInterface();
        assertThat(iNfcCardEmulation).isNotNull();
        when(mRegisteredServicesCache.hasService(anyInt(), any())).thenReturn(true);
        ComponentName componentName = ComponentName
                .unflattenFromString("com.android.test.component/.Component");
        when(mRegisteredAidCache.isDefaultServiceForAid(1, componentName, "test"))
                .thenReturn(true);
        when(mRegisteredServicesCache.doesServiceShouldDefaultToObserveMode(1, componentName))
                .thenReturn(true);
        boolean result = iNfcCardEmulation
                .setShouldDefaultToObserveModeForService(1, componentName, true);
        assertThat(result).isTrue();
    }

    @Test
    public void testRegisterAidGroupForService() throws RemoteException {
        INfcCardEmulation iNfcCardEmulation = mCardEmulationManager.getNfcCardEmulationInterface();
        assertThat(iNfcCardEmulation).isNotNull();
        when(mRegisteredServicesCache.hasService(anyInt(), any())).thenReturn(true);
        when(mRegisteredServicesCache.registerAidGroupForService(anyInt(), anyInt(), any(), any()))
                .thenReturn(true);
        ComponentName componentName = ComponentName
                .unflattenFromString("com.android.test.component/.Component");
        AidGroup aidGroup = mock(AidGroup.class);
        boolean result = iNfcCardEmulation
                .registerAidGroupForService(1, componentName, aidGroup);
        assertThat(result).isTrue();
    }

    @Test
    public void testRegisterPollingLoopFilterForService() throws RemoteException {
        INfcCardEmulation iNfcCardEmulation = mCardEmulationManager.getNfcCardEmulationInterface();
        assertThat(iNfcCardEmulation).isNotNull();
        when(mRegisteredServicesCache.hasService(anyInt(), any())).thenReturn(true);
        when(mRegisteredServicesCache.registerPollingLoopFilterForService(anyInt(),
                anyInt(), any(), anyString(), anyBoolean())).thenReturn(true);
        ComponentName componentName = ComponentName
                .unflattenFromString("com.android.test.component/.Component");
        boolean result = iNfcCardEmulation.registerPollingLoopFilterForService(1,
                componentName, "test", true);
        assertThat(true).isTrue();
    }

    @Test
    public void testRemovePollingLoopPatternFilterForService() throws RemoteException {
        INfcCardEmulation iNfcCardEmulation = mCardEmulationManager.getNfcCardEmulationInterface();
        assertThat(iNfcCardEmulation).isNotNull();
        when(mRegisteredServicesCache.hasService(anyInt(), any())).thenReturn(true);
        when(mRegisteredServicesCache.removePollingLoopFilterForService(anyInt(),
                anyInt(), any(), anyString())).thenReturn(true);
        ComponentName componentName = ComponentName
                .unflattenFromString("com.android.test.component/.Component");
        boolean result = iNfcCardEmulation.removePollingLoopPatternFilterForService(1,
                componentName, "test");
        assertThat(true).isTrue();
    }

    @Test
    public void testSetOffHostForService() throws RemoteException {
        INfcCardEmulation iNfcCardEmulation = mCardEmulationManager.getNfcCardEmulationInterface();
        assertThat(iNfcCardEmulation).isNotNull();
        when(mRegisteredServicesCache.hasService(anyInt(), any())).thenReturn(true);
        when(mRegisteredServicesCache.setOffHostSecureElement(anyInt(),
                anyInt(), any(), anyString())).thenReturn(true);
        ComponentName componentName = ComponentName
                .unflattenFromString("com.android.test.component/.Component");
        boolean result = iNfcCardEmulation
                .setOffHostForService(1, componentName, "test");
        assertThat(result).isTrue();
        verify(mNfcService).onPreferredPaymentChanged(NfcAdapter.PREFERRED_PAYMENT_UPDATED);
    }

    @Test
    public void testUnsetOffHostForService() throws RemoteException {
        INfcCardEmulation iNfcCardEmulation = mCardEmulationManager.getNfcCardEmulationInterface();
        assertThat(iNfcCardEmulation).isNotNull();
        when(mRegisteredServicesCache.hasService(anyInt(), any())).thenReturn(true);
        when(mRegisteredServicesCache.resetOffHostSecureElement(anyInt(),
                anyInt(), any())).thenReturn(true);
        ComponentName componentName = ComponentName
                .unflattenFromString("com.android.test.component/.Component");
        boolean result = iNfcCardEmulation.unsetOffHostForService(1, componentName);
        assertThat(result).isTrue();
        verify(mNfcService).onPreferredPaymentChanged(NfcAdapter.PREFERRED_PAYMENT_UPDATED);
    }

    @Test
    public void testGetAidGroupForService() throws RemoteException {
        INfcCardEmulation iNfcCardEmulation = mCardEmulationManager.getNfcCardEmulationInterface();
        assertThat(iNfcCardEmulation).isNotNull();
        when(mRegisteredServicesCache.hasService(anyInt(), any())).thenReturn(true);
        AidGroup aidGroup = mock(AidGroup.class);
        when(mRegisteredServicesCache.getAidGroupForService(anyInt(),
                anyInt(), any(), anyString())).thenReturn(aidGroup);
        ComponentName componentName = ComponentName
                .unflattenFromString("com.android.test.component/.Component");
        AidGroup result = iNfcCardEmulation
                .getAidGroupForService(1, componentName, "test");
        assertThat(result).isEqualTo(aidGroup);
    }

    @Test
    public void testRemoveAidGroupForService() throws RemoteException {
        INfcCardEmulation iNfcCardEmulation = mCardEmulationManager.getNfcCardEmulationInterface();
        assertThat(iNfcCardEmulation).isNotNull();
        when(mRegisteredServicesCache.hasService(anyInt(), any())).thenReturn(true);
        when(mRegisteredServicesCache.removeAidGroupForService(anyInt(),
                anyInt(), any(), anyString())).thenReturn(true);
        ComponentName componentName = ComponentName
                .unflattenFromString("com.android.test.component/.Component");
        boolean result = iNfcCardEmulation
                .removeAidGroupForService(1, componentName, "payment");
        assertThat(result).isTrue();
        verify(mNfcService).onPreferredPaymentChanged(NfcAdapter.PREFERRED_PAYMENT_UPDATED);
        verify(mNfcEventLog).logEvent(any());
    }

    @Test
    public void testSetServices() throws RemoteException {
        INfcCardEmulation iNfcCardEmulation = mCardEmulationManager.getNfcCardEmulationInterface();
        assertThat(iNfcCardEmulation).isNotNull();
        when(mRegisteredServicesCache.hasService(anyInt(), any())).thenReturn(true);
        ComponentName componentName = ComponentName
                .unflattenFromString("com.android.test.component/.Component");
        when(mPreferredServices.registerPreferredForegroundService(any(), anyInt()))
                .thenReturn(true);
        boolean result = iNfcCardEmulation.setPreferredService(componentName);
        assertThat(result).isTrue();
    }

    @Test
    public void testGetServices() throws RemoteException {
        INfcCardEmulation iNfcCardEmulation = mCardEmulationManager.getNfcCardEmulationInterface();
        assertThat(iNfcCardEmulation).isNotNull();
        ApduServiceInfo apduServiceInfo = mock(ApduServiceInfo.class);
        List<ApduServiceInfo> apduServiceInfoList = new ArrayList<>();
        apduServiceInfoList.add(apduServiceInfo);
        when(mRegisteredServicesCache.getServicesForCategory(1, "payment"))
                .thenReturn(apduServiceInfoList);
        List<ApduServiceInfo> result = iNfcCardEmulation
                .getServices(1, "payment");
        assertThat(result).isEqualTo(apduServiceInfoList);
    }

    @Test
    public void testUnsetPreferredService() throws RemoteException {
        INfcCardEmulation iNfcCardEmulation = mCardEmulationManager.getNfcCardEmulationInterface();
        assertThat(iNfcCardEmulation).isNotNull();
        when(mPreferredServices
                .unregisteredPreferredForegroundService(anyInt())).thenReturn(true);
        boolean result = iNfcCardEmulation.unsetPreferredService();
        assertThat(result).isTrue();
    }

    @Test
    public void testSupportsAidPrefixRegistration() throws RemoteException {
        INfcCardEmulation iNfcCardEmulation = mCardEmulationManager.getNfcCardEmulationInterface();
        assertThat(iNfcCardEmulation).isNotNull();
        when(mRegisteredAidCache
                .supportsAidPrefixRegistration()).thenReturn(true);
        boolean result = iNfcCardEmulation.supportsAidPrefixRegistration();
        assertThat(result).isTrue();
    }

    @Test
    public void testGetPreferredPaymentService() throws RemoteException {
        INfcCardEmulation iNfcCardEmulation = mCardEmulationManager.getNfcCardEmulationInterface();
        assertThat(iNfcCardEmulation).isNotNull();
        ComponentNameAndUser componentNameAndUser = mock(ComponentNameAndUser.class);
        ComponentName componentName = ComponentName
                .unflattenFromString("com.android.test.component/.Component");
        when(componentNameAndUser.getComponentName()).thenReturn(componentName);
        when(mRegisteredAidCache.getPreferredService()).thenReturn(componentNameAndUser);
        ApduServiceInfo apduServiceInfo = mock(ApduServiceInfo.class);
        when(mRegisteredServicesCache.getService(1, componentName))
                .thenReturn(apduServiceInfo);
        ApduServiceInfo result = iNfcCardEmulation.getPreferredPaymentService(1);
        assertThat(result).isNotNull();
        assertThat(result).isEqualTo(apduServiceInfo);
        verify(mRegisteredAidCache).getPreferredService();
    }

    @Test
    public void testSetServiceEnabledForCategoryOther() throws RemoteException {
        INfcCardEmulation iNfcCardEmulation = mCardEmulationManager.getNfcCardEmulationInterface();
        assertThat(iNfcCardEmulation).isNotNull();
        ComponentName componentName = ComponentName
                .unflattenFromString("com.android.test.component/.Component");
        when(mDeviceConfigFacade.getEnableServiceOther()).thenReturn(true);
        when(mRegisteredServicesCache.registerOtherForService(1,
                componentName, true)).thenReturn(1);
        int result = iNfcCardEmulation
                .setServiceEnabledForCategoryOther(1, componentName, true);
        assertThat(result).isEqualTo(1);
        verify(mRegisteredServicesCache)
                .registerOtherForService(1, componentName, true);
    }

    @Test
    public void testIsDefaultPaymentRegistered() throws RemoteException {
        INfcCardEmulation iNfcCardEmulation = mCardEmulationManager.getNfcCardEmulationInterface();
        assertThat(iNfcCardEmulation).isNotNull();
        when(mWalletRoleObserver.isWalletRoleFeatureEnabled()).thenReturn(true);
        when(Binder.getCallingUserHandle()).thenReturn(USER_HANDLE);
        when(mWalletRoleObserver.getDefaultWalletRoleHolder(USER_ID))
                .thenReturn(new PackageAndUser(USER_ID, "com.android.test"));
        boolean result = iNfcCardEmulation.isDefaultPaymentRegistered();
        assertThat(result).isTrue();

        when(mWalletRoleObserver.isWalletRoleFeatureEnabled()).thenReturn(false);
        when(Settings.Secure.getString(any(), anyString())).thenReturn("com.android.test");
        result = iNfcCardEmulation.isDefaultPaymentRegistered();
        assertThat(result).isTrue();
    }

    @Test
    public void testOverrideRoutingTable() throws RemoteException {
        INfcCardEmulation iNfcCardEmulation = mCardEmulationManager.getNfcCardEmulationInterface();
        assertThat(iNfcCardEmulation).isNotNull();
        when(android.nfc.Flags.nfcOverrideRecoverRoutingTable()).thenReturn(false);
        when(mForegroundUtils.registerUidToBackgroundCallback(any(), anyInt()))
                .thenReturn(true);
        iNfcCardEmulation.overrideRoutingTable(1,
                "eSE", "SIM", "com.android.test");
        verify(mRoutingOptionManager).overrideDefaultIsoDepRoute(anyInt());
        verify(mRoutingOptionManager).overrideDefaultOffHostRoute(anyInt());
        verify(mRegisteredAidCache).onRoutingOverridedOrRecovered();
    }

    @Test
    public void testRecoverRoutingTable() throws RemoteException {
        INfcCardEmulation iNfcCardEmulation = mCardEmulationManager.getNfcCardEmulationInterface();
        assertThat(iNfcCardEmulation).isNotNull();
        when(mForegroundUtils.isInForeground(anyInt())).thenReturn(true);
        iNfcCardEmulation.recoverRoutingTable(1);
        verify(mRoutingOptionManager).recoverOverridedRoutingTable();
        verify(mRegisteredAidCache).onRoutingOverridedOrRecovered();
    }

    @Test
    public void testGetRoutingStatus() throws RemoteException {
        INfcCardEmulation iNfcCardEmulation = mCardEmulationManager.getNfcCardEmulationInterface();
        assertThat(iNfcCardEmulation).isNotNull();
        when(mRoutingOptionManager.isRoutingTableOverrided()).thenReturn(true);
        when(mRoutingOptionManager.getOverrideDefaultRoute()).thenReturn(0x01);
        when(mRoutingOptionManager.getOverrideDefaultIsoDepRoute()).thenReturn(0x02);
        when(mRoutingOptionManager.getOverrideDefaultOffHostRoute()).thenReturn(0x03);
        when(mRoutingOptionManager.getSecureElementForRoute(anyInt()))
                .thenReturn("DH", "SIM", "eSE");
        List<String> result = iNfcCardEmulation.getRoutingStatus();
        assertThat(result).isNotNull();
        assertThat(result.size()).isGreaterThan(0);
        assertThat(result.get(0)).isEqualTo("DH");
    }

    @Test
    public void testSetAutoChangeStatus() throws RemoteException {
        INfcCardEmulation iNfcCardEmulation = mCardEmulationManager.getNfcCardEmulationInterface();
        assertThat(iNfcCardEmulation).isNotNull();
        iNfcCardEmulation.setAutoChangeStatus(true);
        verify(mRoutingOptionManager).setAutoChangeStatus(true);
    }

    @Test
    public void testIsAutoChangeEnabled() throws RemoteException {
        INfcCardEmulation iNfcCardEmulation = mCardEmulationManager.getNfcCardEmulationInterface();
        assertThat(iNfcCardEmulation).isNotNull();
        when(mRoutingOptionManager.isAutoChangeEnabled()).thenReturn(true);
        boolean result = iNfcCardEmulation.isAutoChangeEnabled();
        assertThat(result).isTrue();
        verify(mRoutingOptionManager).isAutoChangeEnabled();
    }

    @Test
    public void testIsEuiccSupported() throws RemoteException {
        INfcCardEmulation iNfcCardEmulation = mCardEmulationManager.getNfcCardEmulationInterface();
        assertThat(iNfcCardEmulation).isNotNull();
        when(mResources.getBoolean(anyInt())).thenReturn(true);
        when(NfcInjector.NfcProperties.isEuiccSupported()).thenReturn(true);
        boolean result = iNfcCardEmulation.isEuiccSupported();
        assertThat(result).isTrue();
    }

    @Test
    public void testSetDefaultNfcSubscriptionId() throws RemoteException {
        INfcCardEmulation iNfcCardEmulation = mCardEmulationManager.getNfcCardEmulationInterface();
        assertThat(iNfcCardEmulation).isNotNull();
        PackageManager packageManager = mock(PackageManager.class);
        when(packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_SUBSCRIPTION))
                .thenReturn(true);
        when(mContext.getPackageManager()).thenReturn(packageManager);
        int result = iNfcCardEmulation.setDefaultNfcSubscriptionId(1, "com.android.test");
        assertThat(result)
                .isEqualTo(CardEmulation.SET_SUBSCRIPTION_ID_STATUS_SUCCESS);
    }

    @Test
    public void testGetDefaultNfcSubscriptionId() throws RemoteException {
        INfcCardEmulation iNfcCardEmulation = mCardEmulationManager.getNfcCardEmulationInterface();
        assertThat(iNfcCardEmulation).isNotNull();
        PackageManager packageManager = mock(PackageManager.class);
        when(packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_SUBSCRIPTION))
                .thenReturn(true);
        when(mContext.getPackageManager()).thenReturn(packageManager);
        when(mPreferredSubscriptionService.getPreferredSubscriptionId())
                .thenReturn(SubscriptionManager.INVALID_SUBSCRIPTION_ID);
        int result = iNfcCardEmulation.getDefaultNfcSubscriptionId("com.android.test");
        assertThat(result)
                .isEqualTo(SubscriptionManager.INVALID_SUBSCRIPTION_ID);
    }

    @Test
    public void onWalletRoleHolderChanged_exitFramesNoSupported_doesNothing() {
        when(Flags.exitFrames()).thenReturn(true);
        when(mNfcService.isFirmwareExitFramesSupported()).thenReturn(false);
        when(mNfcService.getNumberOfFirmwareExitFramesSupported()).thenReturn(0);

        mCardEmulationManager.onWalletRoleHolderChanged("com.android.test", 0);

        verify(mNfcService, never()).setFirmwareExitFrameTable(any(), anyInt());
    }

    @Test
    public void onWalletRoleHolderChanged_setsExitFrames() {
        when(Flags.exitFrames()).thenReturn(true);
        when(mNfcService.isFirmwareExitFramesSupported()).thenReturn(true);
        when(mNfcService.getNumberOfFirmwareExitFramesSupported()).thenReturn(5);
        ApduServiceInfo service1 = mock(ApduServiceInfo.class);
        ApduServiceInfo service2 = mock(ApduServiceInfo.class);
        // Service 1 is role associated, service 2 is not
        when(mRegisteredServicesCache.getServices(anyInt())).thenReturn(
                List.of(service1, service2));
        when(mRegisteredAidCache.isDefaultOrAssociatedWalletService(eq(service1),
                anyInt())).thenReturn(true);
        when(mRegisteredAidCache.isDefaultOrAssociatedWalletService(eq(service2),
                anyInt())).thenReturn(false);

        // Setup filters,
        when(service1.getPollingLoopFilters()).thenReturn(List.of("aa", "bb", "ee"));
        when(service1.getShouldAutoTransact(any())).thenReturn(true);
        // Second filter should not become exit frame, but should also not throw
        when(service1.getPollingLoopPatternFilters()).thenReturn(
                List.of(Pattern.compile("1122.*"), Pattern.compile("1?")));
        // Set a filter that should not be included because it doesn't autotransact.
        when(service1.getShouldAutoTransact(eq("ee"))).thenReturn(false);

        when(service2.getPollingLoopFilters()).thenReturn(List.of("cc", "dd"));
        when(service2.getShouldAutoTransact(any())).thenReturn(true);

        mCardEmulationManager.onWalletRoleHolderChanged("com.android.test", 0);

        ArgumentCaptor<List<ExitFrame>> frameCaptor = ArgumentCaptor.forClass(List.class);
        verify(mNfcService).setFirmwareExitFrameTable(frameCaptor.capture(), anyInt());
        List<ExitFrame> frames = frameCaptor.getValue();
        assertThat(frames).hasSize(3);
        assertThat(frames.get(0).getData()).isEqualTo(HexFormat.of().parseHex("aa"));
        assertThat(frames.get(1).getData()).isEqualTo(HexFormat.of().parseHex("bb"));
        assertThat(frames.get(2).getData()).isEqualTo(HexFormat.of().parseHex("1122"));
        assertTrue(frames.get(2).isPrefixMatchingAllowed());
    }

    @Test
    public void onWalletRoleHolderChanged_setsExitFramesToCorrectSize() {
        when(Flags.exitFrames()).thenReturn(true);
        when(mNfcService.isFirmwareExitFramesSupported()).thenReturn(true);
        when(mNfcService.getNumberOfFirmwareExitFramesSupported()).thenReturn(5);
        ApduServiceInfo service1 = mock(ApduServiceInfo.class);
        // Service 1 is role associated, service 2 is not
        when(mRegisteredServicesCache.getServices(anyInt())).thenReturn(
                List.of(service1));
        when(mRegisteredAidCache.isDefaultOrAssociatedWalletService(eq(service1),
                anyInt())).thenReturn(true);

        when(service1.getPollingLoopFilters()).thenReturn(
                List.of("aa", "bb", "cc", "dd", "ee", "ff"));
        when(service1.getShouldAutoTransact(any())).thenReturn(true);

        mCardEmulationManager.onWalletRoleHolderChanged("com.android.test", 0);

        ArgumentCaptor<List<ExitFrame>> frameCaptor = ArgumentCaptor.forClass(List.class);
        verify(mNfcService).setFirmwareExitFrameTable(frameCaptor.capture(), anyInt());
        List<ExitFrame> frames = frameCaptor.getValue();
        assertThat(frames).hasSize(5);
    }

    @Test
    public void testDump() {
        FileDescriptor fd = mock(FileDescriptor.class);
        PrintWriter pw = mock(PrintWriter.class);
        String[] args = new String[]{"test"};

        mCardEmulationManager.dump(fd, pw, args);
        verify(mRegisteredServicesCache).dump(fd, pw, args);
    }

    @Test
    public void testDumpDebug() {
        ProtoOutputStream proto = mock(ProtoOutputStream.class);
        when(proto.start(CardEmulationManagerProto.REGISTERED_SERVICES_CACHE)).thenReturn((long) 1);

        mCardEmulationManager.dumpDebug(proto);
        verify(mRegisteredServicesCache).dumpDebug(proto);
    }

    @Test
    public void testOnPreferredSubscriptionChangedWithSimEuicc1()
            throws NoSuchFieldException, IllegalAccessException {
        int subscriptionId = 1;
        boolean isActive = true;
        TelephonyUtils telephonyUtils = mock(TelephonyUtils.class);
        SubscriptionInfo subscriptionInfo = mock(SubscriptionInfo.class);
        Optional<SubscriptionInfo> optionalInfo = Optional.of(subscriptionInfo);
        Field field = CardEmulationManager.class.getDeclaredField("mTelephonyUtils");
        field.setAccessible(true);
        field.set(mCardEmulationManager, telephonyUtils);
        when(subscriptionInfo.isEmbedded()).thenReturn(true);
        when(subscriptionInfo.getPortIndex()).thenReturn(0);
        when(telephonyUtils.getActiveSubscriptionInfoById(subscriptionId)).thenReturn(optionalInfo);
        when(mRoutingOptionManager.getSecureElementForRoute(anyInt())).thenReturn("");

        mCardEmulationManager.onPreferredSubscriptionChanged(subscriptionId, isActive);
        verify(mRoutingOptionManager).onPreferredSimChanged(TelephonyUtils.SIM_TYPE_EUICC_1);
        verify(mRegisteredAidCache).onPreferredSimChanged(TelephonyUtils.SIM_TYPE_EUICC_1);
    }

    @Test
    public void testOnPreferredSubscriptionChangedWithSimEuicc2()
            throws NoSuchFieldException, IllegalAccessException {
        int subscriptionId = 1;
        boolean isActive = true;
        TelephonyUtils telephonyUtils = mock(TelephonyUtils.class);
        SubscriptionInfo subscriptionInfo = mock(SubscriptionInfo.class);
        Optional<SubscriptionInfo> optionalInfo = Optional.of(subscriptionInfo);
        Field field = CardEmulationManager.class.getDeclaredField("mTelephonyUtils");
        field.setAccessible(true);
        field.set(mCardEmulationManager, telephonyUtils);
        when(subscriptionInfo.isEmbedded()).thenReturn(true);
        when(subscriptionInfo.getPortIndex()).thenReturn(1);
        when(telephonyUtils.getActiveSubscriptionInfoById(subscriptionId)).thenReturn(optionalInfo);
        when(mRoutingOptionManager.getSecureElementForRoute(anyInt())).thenReturn("");

        mCardEmulationManager.onPreferredSubscriptionChanged(subscriptionId, isActive);
        verify(mRoutingOptionManager).onPreferredSimChanged(TelephonyUtils.SIM_TYPE_EUICC_2);
        verify(mRegisteredAidCache).onPreferredSimChanged(TelephonyUtils.SIM_TYPE_EUICC_2);
    }

    @Test
    public void testOnPreferredSubscriptionChangedWithSimUicc()
            throws NoSuchFieldException, IllegalAccessException {
        int subscriptionId = 1;
        boolean isActive = true;
        TelephonyUtils telephonyUtils = mock(TelephonyUtils.class);
        SubscriptionInfo subscriptionInfo = mock(SubscriptionInfo.class);
        Optional<SubscriptionInfo> optionalInfo = Optional.of(subscriptionInfo);
        Field field = CardEmulationManager.class.getDeclaredField("mTelephonyUtils");
        field.setAccessible(true);
        field.set(mCardEmulationManager, telephonyUtils);
        when(subscriptionInfo.isEmbedded()).thenReturn(false);
        when(telephonyUtils.getActiveSubscriptionInfoById(subscriptionId)).thenReturn(optionalInfo);
        when(mRoutingOptionManager.getSecureElementForRoute(anyInt())).thenReturn("");

        mCardEmulationManager.onPreferredSubscriptionChanged(subscriptionId, isActive);
        verify(mRoutingOptionManager).onPreferredSimChanged(TelephonyUtils.SIM_TYPE_UICC);
        verify(mRegisteredAidCache).onPreferredSimChanged(TelephonyUtils.SIM_TYPE_UICC);
    }

    @Test
    public void testOnPreferredSubscriptionChangedWithSimUnknown()
            throws NoSuchFieldException, IllegalAccessException {
        int subscriptionId = 1;
        boolean isActive = true;
        TelephonyUtils telephonyUtils = mock(TelephonyUtils.class);
        Optional<SubscriptionInfo> optionalInfo = Optional.empty();
        Field field = CardEmulationManager.class.getDeclaredField("mTelephonyUtils");
        field.setAccessible(true);
        field.set(mCardEmulationManager, telephonyUtils);
        when(telephonyUtils.getActiveSubscriptionInfoById(subscriptionId)).thenReturn(optionalInfo);

        mCardEmulationManager.onPreferredSubscriptionChanged(subscriptionId, isActive);
        verify(mRoutingOptionManager).onPreferredSimChanged(TelephonyUtils.SIM_TYPE_UNKNOWN);
        verify(mRegisteredAidCache).onPreferredSimChanged(TelephonyUtils.SIM_TYPE_UNKNOWN);
    }

    @Test
    public void testOnPreferredSubscriptionChangedWithSimInActive()
            throws NoSuchFieldException, IllegalAccessException {
        int subscriptionId = 1;
        boolean isActive = false;
        TelephonyUtils telephonyUtils = mock(TelephonyUtils.class);
        Field field = CardEmulationManager.class.getDeclaredField("mTelephonyUtils");
        field.setAccessible(true);
        field.set(mCardEmulationManager, telephonyUtils);

        mCardEmulationManager.onPreferredSubscriptionChanged(subscriptionId, isActive);
        verify(mRoutingOptionManager).onPreferredSimChanged(TelephonyUtils.SIM_TYPE_UNKNOWN);
        verify(mRegisteredAidCache).onPreferredSimChanged(TelephonyUtils.SIM_TYPE_UNKNOWN);
    }

    @Test
    public void testOnEeListenActivated() {
        mCardEmulationManager.onEeListenActivated(false);

        verify(mPreferredServices).onHostEmulationDeactivated();
    }

    @Test
    public void testOnFieldChangeDetected() {
        mCardEmulationManager.onFieldChangeDetected(true);
        verify(mHostEmulationManager).onFieldChangeDetected(true);
    }

    @Test
    public void testOnHostCardEmulationDataWithApdu() throws RemoteException {
        INfcOemExtensionCallback nfcOemExtensionCallback = mock(INfcOemExtensionCallback.class);
        mCardEmulationManager.setOemExtension(nfcOemExtensionCallback);

        mCardEmulationManager.onHostCardEmulationData(
                CardEmulationManager.NFC_HCE_APDU, PROPER_SKIP_DATA_NDF1_HEADER);
        verify(nfcOemExtensionCallback).onHceEventReceived(NfcOemExtension.HCE_DATA_TRANSFERRED);
        verify(mHostEmulationManager).onHostEmulationData(PROPER_SKIP_DATA_NDF1_HEADER);
    }

    @Test
    public void testOnHostCardEmulationDataWithNfcf() throws RemoteException {
        INfcOemExtensionCallback nfcOemExtensionCallback = mock(INfcOemExtensionCallback.class);
        mCardEmulationManager.setOemExtension(nfcOemExtensionCallback);

        mCardEmulationManager.onHostCardEmulationData(
                CardEmulationManager.NFC_HCE_NFCF, PROPER_SKIP_DATA_NDF1_HEADER);
        verify(nfcOemExtensionCallback).onHceEventReceived(NfcOemExtension.HCE_DATA_TRANSFERRED);
        verify(mHostNfcFEmulationManager).onHostEmulationData(PROPER_SKIP_DATA_NDF1_HEADER);
        verify(mPowerManager).userActivity(anyLong(), eq(PowerManager.USER_ACTIVITY_EVENT_TOUCH),
                eq(0));
    }

    @Test
    public void testOnHostCardEmulationDeactivatedWithApdu() throws RemoteException {
        INfcOemExtensionCallback nfcOemExtensionCallback = mock(INfcOemExtensionCallback.class);
        mCardEmulationManager.setOemExtension(nfcOemExtensionCallback);
        ArgumentCaptor<Integer> captor = ArgumentCaptor.forClass(Integer.class);

        mCardEmulationManager.onHostCardEmulationDeactivated(CardEmulationManager.NFC_HCE_APDU);
        verify(mHostEmulationManager).onHostEmulationDeactivated();
        verify(mPreferredServices).onHostEmulationDeactivated();
        verify(nfcOemExtensionCallback).onHceEventReceived(captor.capture());
        assertEquals(NfcOemExtension.HCE_DEACTIVATE, captor.getValue().intValue());
    }

    @Test
    public void testSetDefaultServiceForCategoryChecked() {
        int userId = 1;
        ComponentName componentName = new ComponentName("com.example.nfc", "ExampleNfcClass");
        String category = CardEmulation.CATEGORY_PAYMENT;
        Context context = mock(Context.class);
        ContentResolver contentResolver = mock(ContentResolver.class);
        UserHandle userHandle = mock(UserHandle.class);
        when(UserHandle.of(userId)).thenReturn(userHandle);
        when(mRegisteredServicesCache.hasService(userId, componentName)).thenReturn(true);
        when(mContext.createContextAsUser(userHandle, 0)).thenReturn(context);
        when(context.getContentResolver()).thenReturn(contentResolver);

        assertTrue(mCardEmulationManager.setDefaultServiceForCategoryChecked(userId, componentName,
                category));
        verify(mRegisteredServicesCache).hasService(userId, componentName);
    }

    @Test
    public void testSetDefaultServiceForCategoryCheckedWithOtherCategory() {
        assertFalse(mCardEmulationManager.setDefaultServiceForCategoryChecked(1,
                new ComponentName("com.example.nfc", "ExampleNfcClass"),
                CardEmulation.CATEGORY_OTHER));
    }

    @Test
    public void testUpdateForDefaultSwpToEuicc() throws NoSuchFieldException,
            IllegalAccessException {
        int subscriptionId = 1;
        when(android.nfc.Flags.enableCardEmulationEuicc()).thenReturn(true);
        Resources resources = mock(Resources.class);
        when(mContext.getResources()).thenReturn(resources);
        when(resources.getBoolean(anyInt())).thenReturn(true);
        when(NfcInjector.NfcProperties.isEuiccSupported()).thenReturn(true);
        when(mPreferredSubscriptionService.getPreferredSubscriptionId()).thenReturn(subscriptionId);
        TelephonyUtils telephonyUtils = mock(TelephonyUtils.class);
        SubscriptionInfo subscriptionInfo = mock(SubscriptionInfo.class);
        Optional<SubscriptionInfo> optionalInfo = Optional.of(subscriptionInfo);
        Field field = CardEmulationManager.class.getDeclaredField("mTelephonyUtils");
        field.setAccessible(true);
        field.set(mCardEmulationManager, telephonyUtils);
        when(subscriptionInfo.isEmbedded()).thenReturn(true);
        when(subscriptionInfo.getPortIndex()).thenReturn(0);
        when(telephonyUtils.getActiveSubscriptionInfoById(subscriptionId)).thenReturn(optionalInfo);
        when(telephonyUtils.updateSwpStatusForEuicc(TelephonyUtils.SIM_TYPE_EUICC_1)).thenReturn(
                "6F02839000");

        mCardEmulationManager.updateForDefaultSwpToEuicc();
        verify(mPreferredSubscriptionService).getPreferredSubscriptionId();
        verify(telephonyUtils).updateSwpStatusForEuicc(TelephonyUtils.SIM_TYPE_EUICC_1);
        verify(mContext).getResources();
    }

    @Test
    public void testUpdateForDefaultSwpToEuiccWithCmdFail() throws NoSuchFieldException,
            IllegalAccessException {
        int subscriptionId = 3;
        when(android.nfc.Flags.enableCardEmulationEuicc()).thenReturn(true);
        Resources resources = mock(Resources.class);
        when(mContext.getResources()).thenReturn(resources);
        when(resources.getBoolean(anyInt())).thenReturn(true);
        when(NfcInjector.NfcProperties.isEuiccSupported()).thenReturn(true);
        when(mPreferredSubscriptionService.getPreferredSubscriptionId()).thenReturn(subscriptionId);
        TelephonyUtils telephonyUtils = mock(TelephonyUtils.class);
        SubscriptionInfo subscriptionInfo = mock(SubscriptionInfo.class);
        Optional<SubscriptionInfo> optionalInfo = Optional.of(subscriptionInfo);
        Field field = CardEmulationManager.class.getDeclaredField("mTelephonyUtils");
        field.setAccessible(true);
        field.set(mCardEmulationManager, telephonyUtils);
        when(subscriptionInfo.isEmbedded()).thenReturn(true);
        when(subscriptionInfo.getPortIndex()).thenReturn(0);
        when(telephonyUtils.getActiveSubscriptionInfoById(subscriptionId)).thenReturn(optionalInfo);
        when(telephonyUtils.updateSwpStatusForEuicc(TelephonyUtils.SIM_TYPE_EUICC_1)).thenReturn(
                "6F0283FFFF");

        mCardEmulationManager.updateForDefaultSwpToEuicc();
        verify(mPreferredSubscriptionService).getPreferredSubscriptionId();
        verify(telephonyUtils).updateSwpStatusForEuicc(TelephonyUtils.SIM_TYPE_EUICC_1);
        verify(mContext).getResources();
        verify(resources).getBoolean(anyInt());
    }

    @Test
    public void testUpdateForDefaultSwpToEuiccWithWrongLength() throws NoSuchFieldException,
            IllegalAccessException {
        int subscriptionId = 3;
        when(android.nfc.Flags.enableCardEmulationEuicc()).thenReturn(true);
        Resources resources = mock(Resources.class);
        when(mContext.getResources()).thenReturn(resources);
        when(resources.getBoolean(anyInt())).thenReturn(true);
        when(NfcInjector.NfcProperties.isEuiccSupported()).thenReturn(true);
        when(mPreferredSubscriptionService.getPreferredSubscriptionId()).thenReturn(subscriptionId);
        TelephonyUtils telephonyUtils = mock(TelephonyUtils.class);
        SubscriptionInfo subscriptionInfo = mock(SubscriptionInfo.class);
        Optional<SubscriptionInfo> optionalInfo = Optional.of(subscriptionInfo);
        Field field = CardEmulationManager.class.getDeclaredField("mTelephonyUtils");
        field.setAccessible(true);
        field.set(mCardEmulationManager, telephonyUtils);
        when(subscriptionInfo.isEmbedded()).thenReturn(true);
        when(subscriptionInfo.getPortIndex()).thenReturn(0);
        when(telephonyUtils.getActiveSubscriptionInfoById(subscriptionId)).thenReturn(optionalInfo);
        when(telephonyUtils.updateSwpStatusForEuicc(TelephonyUtils.SIM_TYPE_EUICC_1)).thenReturn(
                "6FF");

        mCardEmulationManager.updateForDefaultSwpToEuicc();
        verify(mPreferredSubscriptionService).getPreferredSubscriptionId();
        verify(telephonyUtils).updateSwpStatusForEuicc(TelephonyUtils.SIM_TYPE_EUICC_1);
        verify(mContext).getResources();
        verify(resources).getBoolean(anyInt());
    }

    @Test
    public void testUpdateForDefaultSwpToEuiccWithEmulationDisabled() {
        when(android.nfc.Flags.enableCardEmulationEuicc()).thenReturn(false);

        mCardEmulationManager.updateForDefaultSwpToEuicc();
        verify(mContext, never()).getResources();
    }

    @Test
    public void testUpdateForDefaultSwpToEuiccWithEmulationNotSupport() {
        when(android.nfc.Flags.enableCardEmulationEuicc()).thenReturn(true);
        Resources resources = mock(Resources.class);
        when(mContext.getResources()).thenReturn(resources);
        when(resources.getBoolean(anyInt())).thenReturn(false);
        when(NfcInjector.NfcProperties.isEuiccSupported()).thenReturn(false);

        mCardEmulationManager.updateForDefaultSwpToEuicc();
        verify(mContext).getResources();
        verify(mPreferredSubscriptionService, never()).getPreferredSubscriptionId();
    }

    @Test
    public void testWasServicePreInstalled() throws PackageManager.NameNotFoundException {
        PackageManager packageManager = mock(PackageManager.class);
        ComponentName service = new ComponentName("com.example.nfc", "NfcClass");
        ApplicationInfo ai = mock(ApplicationInfo.class);
        ai.flags = 1;
        when(packageManager.getApplicationInfo("com.example.nfc", 0)).thenReturn(ai);

        assertTrue(mCardEmulationManager.wasServicePreInstalled(packageManager, service));
    }

    @Test
    public void testWasServicePreInstalledWithoutService()
            throws PackageManager.NameNotFoundException {
        PackageManager packageManager = mock(PackageManager.class);
        ComponentName service = new ComponentName("com.example.nfc", "NfcClass");
        when(packageManager.getApplicationInfo("com.example.nfc", 0)).thenThrow(
                PackageManager.NameNotFoundException.class);

        assertFalse(mCardEmulationManager.wasServicePreInstalled(packageManager, service));
    }

    @Test
    public void testWasServicePreInstalledWithServiceNotPreInstalled()
            throws PackageManager.NameNotFoundException {
        PackageManager packageManager = mock(PackageManager.class);
        ComponentName service = new ComponentName("com.example.nfc", "NfcClass");
        ApplicationInfo ai = mock(ApplicationInfo.class);
        ai.flags = 0;
        when(packageManager.getApplicationInfo("com.example.nfc", 0)).thenReturn(ai);

        assertFalse(mCardEmulationManager.wasServicePreInstalled(packageManager, service));
    }

    @Test
    public void testVerifyDefaults() {
        int userId = 1;
        List<ApduServiceInfo> services = new ArrayList<>();
        List<UserHandle> luh = new ArrayList<>();
        boolean validateInstalled = true;
        UserHandle userHandle = mock(UserHandle.class);
        UserHandle secondUserHandle = mock(UserHandle.class);
        Context context = mock(Context.class);
        UserManager um = mock(UserManager.class);
        ContentResolver contentResolver = mock(ContentResolver.class);
        luh.add(userHandle);
        luh.add(secondUserHandle);
        String compName = "com.nfc/.NfcClass";
        ComponentName service = ComponentName.unflattenFromString(compName);
        //"com.nfc/.NfcClass" becomes package="com.nfc" class="com.nfc.NfcClass".
        when(UserHandle.of(userId)).thenReturn(userHandle);
        when(mContext.createContextAsUser(userHandle, 0)).thenReturn(context);
        when(context.getSystemService(UserManager.class)).thenReturn(um);
        when(um.getEnabledProfiles()).thenReturn(luh);
        when(userHandle.getIdentifier()).thenReturn(userId);
        when(secondUserHandle.getIdentifier()).thenReturn(userId);
        when(context.getContentResolver()).thenReturn(contentResolver);
        when(Settings.Secure.getString(contentResolver,
                Constants.SETTINGS_SECURE_NFC_PAYMENT_DEFAULT_COMPONENT)).thenReturn(
                compName);
        when(mRegisteredServicesCache.hasService(userId, service)).thenReturn(true);

        mCardEmulationManager.verifyDefaults(userId, services, validateInstalled);
        verify(um).getEnabledProfiles();
        verify(context).getSystemService(UserManager.class);
        verify(mRegisteredServicesCache, times(2)).hasService(userId, service);
    }

    @Test
    public void testVerifyDefaultsWithMorePaymentService()
            throws PackageManager.NameNotFoundException {
        int userId = 1;
        List<ApduServiceInfo> services = new ArrayList<>();
        List<UserHandle> luh = new ArrayList<>();
        boolean validateInstalled = true;
        UserHandle userHandle = mock(UserHandle.class);
        UserHandle secondUserHandle = mock(UserHandle.class);
        Context context = mock(Context.class);
        UserManager um = mock(UserManager.class);
        ContentResolver contentResolver = mock(ContentResolver.class);
        luh.add(userHandle);
        luh.add(secondUserHandle);
        String compName = "com.nfc/.NfcClass";
        ComponentName service = ComponentName.unflattenFromString(compName);
        PackageManager pm = mock(PackageManager.class);
        ApduServiceInfo apduService = mock(ApduServiceInfo.class);
        ApduServiceInfo apduService2 = mock(ApduServiceInfo.class);
        services.add(apduService);
        services.add(apduService2);
        ApplicationInfo ai = mock(ApplicationInfo.class);
        ai.flags = 1;

        when(UserHandle.of(userId)).thenReturn(userHandle);
        when(mContext.createContextAsUser(userHandle, 0)).thenReturn(context);
        when(context.getSystemService(UserManager.class)).thenReturn(um);
        when(um.getEnabledProfiles()).thenReturn(luh);
        when(userHandle.getIdentifier()).thenReturn(userId);
        when(secondUserHandle.getIdentifier()).thenReturn(userId);
        when(context.getContentResolver()).thenReturn(contentResolver);
        when(Settings.Secure.getString(contentResolver,
                Constants.SETTINGS_SECURE_NFC_PAYMENT_DEFAULT_COMPONENT)).thenReturn(
                "");
        when(mContext.createPackageContextAsUser("android", 0, userHandle)).thenReturn(context);
        when(context.getPackageManager()).thenReturn(pm);
        when(apduService.hasCategory(CardEmulation.CATEGORY_PAYMENT)).thenReturn(true);
        when(apduService2.hasCategory(CardEmulation.CATEGORY_PAYMENT)).thenReturn(true);
        when(apduService.getComponent()).thenReturn(service);
        when(apduService2.getComponent()).thenReturn(service);
        when(pm.getApplicationInfo("com.nfc", 0)).thenReturn(ai);
        when(mRegisteredServicesCache.hasService(userId, service)).thenReturn(true);

        mCardEmulationManager.verifyDefaults(userId, services, validateInstalled);
        verify(um).getEnabledProfiles();
        verify(mContext).createPackageContextAsUser("android", 0, userHandle);
        verify(pm, times(2)).getApplicationInfo("com.nfc", 0);
    }

    @Test
    public void testVerifyDefaultsWithSinglePaymentService()
            throws PackageManager.NameNotFoundException {
        int userId = 1;
        List<ApduServiceInfo> services = new ArrayList<>();
        List<UserHandle> luh = new ArrayList<>();
        boolean validateInstalled = true;
        UserHandle userHandle = mock(UserHandle.class);
        UserHandle secondUserHandle = mock(UserHandle.class);
        Context context = mock(Context.class);
        UserManager um = mock(UserManager.class);
        ContentResolver contentResolver = mock(ContentResolver.class);
        luh.add(userHandle);
        luh.add(secondUserHandle);
        String compName = "com.nfc/.NfcClass";
        ComponentName service = ComponentName.unflattenFromString(compName);
        PackageManager pm = mock(PackageManager.class);
        ApduServiceInfo apduService = mock(ApduServiceInfo.class);
        services.add(apduService);
        ApplicationInfo ai = mock(ApplicationInfo.class);
        ai.flags = 1;
        when(UserHandle.of(userId)).thenReturn(userHandle);
        when(mContext.createContextAsUser(userHandle, 0)).thenReturn(context);
        when(context.getSystemService(UserManager.class)).thenReturn(um);
        when(um.getEnabledProfiles()).thenReturn(luh);
        when(userHandle.getIdentifier()).thenReturn(userId);
        when(secondUserHandle.getIdentifier()).thenReturn(userId);
        when(context.getContentResolver()).thenReturn(contentResolver);
        when(Settings.Secure.getString(contentResolver,
                Constants.SETTINGS_SECURE_NFC_PAYMENT_DEFAULT_COMPONENT)).thenReturn(
                "");
        when(mContext.createPackageContextAsUser("android", 0, userHandle)).thenReturn(context);
        when(context.getPackageManager()).thenReturn(pm);
        when(apduService.hasCategory(CardEmulation.CATEGORY_PAYMENT)).thenReturn(true);
        when(apduService.getComponent()).thenReturn(service);
        when(pm.getApplicationInfo("com.nfc", 0)).thenReturn(ai);
        when(mRegisteredServicesCache.hasService(userId, service)).thenReturn(true);

        mCardEmulationManager.verifyDefaults(userId, services, validateInstalled);
        verify(um).getEnabledProfiles();
        verify(mContext).createPackageContextAsUser("android", 0, userHandle);
        verify(pm, times(1)).getApplicationInfo("com.nfc", 0);
    }

    @Test
    public void testVerifyDefaultsWithNoPaymentService()
            throws PackageManager.NameNotFoundException {
        int userId = 1;
        List<ApduServiceInfo> services = new ArrayList<>();
        List<UserHandle> luh = new ArrayList<>();
        boolean validateInstalled = true;
        UserHandle userHandle = mock(UserHandle.class);
        UserHandle secondUserHandle = mock(UserHandle.class);
        Context context = mock(Context.class);
        UserManager um = mock(UserManager.class);
        ContentResolver contentResolver = mock(ContentResolver.class);
        luh.add(userHandle);
        luh.add(secondUserHandle);
        String compName = "com.nfc/.NfcClass";
        ComponentName service = ComponentName.unflattenFromString(compName);
        PackageManager pm = mock(PackageManager.class);
        ApplicationInfo ai = mock(ApplicationInfo.class);
        ai.flags = 1;
        when(UserHandle.of(userId)).thenReturn(userHandle);
        when(mContext.createContextAsUser(userHandle, 0)).thenReturn(context);
        when(context.getSystemService(UserManager.class)).thenReturn(um);
        when(um.getEnabledProfiles()).thenReturn(luh);
        when(userHandle.getIdentifier()).thenReturn(userId);
        when(secondUserHandle.getIdentifier()).thenReturn(userId);
        when(context.getContentResolver()).thenReturn(contentResolver);
        when(Settings.Secure.getString(contentResolver,
                Constants.SETTINGS_SECURE_NFC_PAYMENT_DEFAULT_COMPONENT)).thenReturn(
                "");
        when(mContext.createPackageContextAsUser("android", 0, userHandle)).thenReturn(context);
        when(context.getPackageManager()).thenReturn(pm);
        when(mRegisteredServicesCache.hasService(userId, service)).thenReturn(true);

        mCardEmulationManager.verifyDefaults(userId, services, validateInstalled);
        verify(um).getEnabledProfiles();
        verify(mContext).createPackageContextAsUser("android", 0, userHandle);
        verify(pm, never()).getApplicationInfo(anyString(), eq(0));
    }

    @Test
    public void testOnObserveModeDisabledInFirmware() {
        PollingFrame exitFrame = new PollingFrame(
                PollingFrame.POLLING_LOOP_TYPE_UNKNOWN,
                HexFormat.of().parseHex("42123456"),
                0,
                0,
                true);

        mCardEmulationManager.onObserveModeDisabledInFirmware(exitFrame);

        verify(mHostEmulationManager).onObserveModeDisabledInFirmware(exitFrame);
        verify(mStatsdUtils).logAutoTransactReported(StatsdUtils.PROCESSOR_NFCC,
            exitFrame.getData());
    }
}
