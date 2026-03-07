/*
 * Copyright (C) 2025 The Android Open Source Project
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

package android.nfc.cardemulation;

import static android.nfc.cardemulation.HostApduService.MSG_RESPONSE_APDU;
import static android.nfc.cardemulation.HostApduService.MSG_UNHANDLED;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.only;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Intent;
import android.nfc.Flags;
import android.os.Bundle;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;

import com.android.dx.mockito.inline.extended.ExtendedMockito;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockitoAnnotations;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;

public class HostApduServiceTest {
    private byte[] sampleApdu = "EO00001800".getBytes();
    private HostApduService.MsgHandler mHandler;
    private SampleHostApduService mSampleService;
    private MockitoSession mMockitoSession;

    class SampleHostApduService extends HostApduService {

        @Override
        public byte[] processCommandApdu(byte[] commandApdu, Bundle extras) {
            return new byte[0];
        }

        @Override
        public void onDeactivated(int reason) {

        }
    }

    @Before
    public void setUp() {
        mMockitoSession = ExtendedMockito.mockitoSession()
                .mockStatic(Flags.class)
                .mockStatic(com.android.nfc.module.flags.Flags.class)
                .strictness(Strictness.LENIENT)
                .startMocking();
        MockitoAnnotations.initMocks(this);
        if (Looper.myLooper() == null) {
            Looper.prepare();
        }
        mSampleService = mock(SampleHostApduService.class, CALLS_REAL_METHODS);
        mHandler = mSampleService.getMsgHandler();
    }

    @After
    public void tearDown() {
        mMockitoSession.finishMocking();
    }

    @Test
    public void testHandleMessageWithCmdApdu() {
        Bundle bundle = mock(Bundle.class);
        Message msg = mock(Message.class);
        Messenger mNfcService = mock(Messenger.class);
        msg.what = HostApduService.MSG_COMMAND_APDU;
        msg.replyTo = mNfcService;
        when(msg.getData()).thenReturn(bundle);
        when(bundle.getByteArray(HostApduService.KEY_DATA)).thenReturn(sampleApdu);

        mHandler.post(() -> {
            mHandler.handleMessage(msg);
            verify(msg).getData();
        });
    }

    @Test
    public void testHandleMessageWithResponseApduWhenServiceDeactivated() {
        Message msg = mock(Message.class);
        msg.what = MSG_RESPONSE_APDU;

        when(mSampleService.getNfcService()).thenReturn(null);
        mHandler.post(() -> {
            mHandler.handleMessage(msg);
            verify(mSampleService).getNfcService();
        });
    }

    @Test
    public void testHandleMessageWithResponseApdu() {
        Message msg = mock(Message.class);
        Messenger mNfcService = mock(Messenger.class);
        msg.what = MSG_RESPONSE_APDU;
        msg.replyTo = mNfcService;

        when(mSampleService.getNfcService()).thenReturn(mNfcService);
        mHandler.post(() -> {
            mHandler.handleMessage(msg);
            verify(mSampleService).getMessenger();
            try {
                verify(mNfcService).send(msg);
            } catch (RemoteException e) {
                throw new RuntimeException(e);
            }
        });
    }

    @Test
    public void testHandleMessageWithApduAck() {
        Message msg = mock(Message.class);
        msg.what = HostApduService.MSG_RESPONSE_APDU_ACK;

        mHandler.post(() -> {
            mHandler.handleMessage(msg);
            verify(mSampleService, never()).getNfcService();
        });
    }

    @Test
    public void testHandleMessageWithMsgDeactivated() {
        Message msg = Message.obtain();
        msg.what = HostApduService.MSG_DEACTIVATED;
        msg.arg1 = 1;

        mHandler.post(() -> {
            mHandler.handleMessage(msg);
            verify(mSampleService).setNfcService(null);
            verify(mSampleService).onDeactivated(1);
        });
    }

    @Test
    public void testHandleMessageWithMsgUnhandledWhenServiceDeactivated() {
        Message msg = mock(Message.class);
        msg.what = MSG_UNHANDLED;
        when(mSampleService.getNfcService()).thenReturn(null);

        mHandler.post(() -> {
            mHandler.handleMessage(msg);
            verify(mSampleService, only()).getNfcService();
        });
    }

    @Test
    public void testHandleMessageWithMsgUnhandled() {
        Message msg = mock(Message.class);
        msg.what = MSG_UNHANDLED;
        Messenger mNfcService = mock(Messenger.class);
        when(mSampleService.getNfcService()).thenReturn(mNfcService);

        mHandler.post(() -> {
            mHandler.handleMessage(msg);
            verify(mSampleService).getMessenger();
        });
    }

    @Test
    public void testHandleMessageWithPollingLoop() {
        Message msg = mock(Message.class);
        msg.what = HostApduService.MSG_POLLING_LOOP;
        when(com.android.nfc.module.flags.Flags.nfcHceLatencyEvents()).thenReturn(true);

        mHandler.post(() -> {
            mHandler.handleMessage(msg);
            verify(mSampleService).getMessenger();
        });
    }

    @Test
    public void testOnBinder() {
        Messenger messenger = mock(Messenger.class);
        when(mSampleService.getMessenger()).thenReturn(messenger);

        mSampleService.onBind(mock(Intent.class));
        verify(messenger).getBinder();
    }

    @Test
    public void testNfcService() {
        Messenger nfcService = mock(Messenger.class);
        mSampleService.setNfcService(nfcService);

        assertEquals(nfcService, mSampleService.getNfcService());
    }
}
