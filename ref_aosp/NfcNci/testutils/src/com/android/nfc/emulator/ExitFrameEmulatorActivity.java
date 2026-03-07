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
package com.android.nfc.emulator;

import android.content.ComponentName;
import android.nfc.cardemulation.CardEmulation;
import android.nfc.cardemulation.PollingFrame;
import android.os.Bundle;
import android.util.Log;

import com.android.nfc.service.PaymentService1;

import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;

public class ExitFrameEmulatorActivity extends BaseEmulatorActivity {
    private static final String TAG = "ExitFrameEmulatorActivity";

    public static final String EXIT_FRAME_KEY = "EXIT_FRAME";
    public static final String REGISTER_PATTERNS_KEY = "REGISTER_PATTERNS";
    public static final String WAIT_FOR_TRANSACTION_KEY = "WAIT_FOR_TRANSACTION";

    private String mReceivedExitFrame = null;
    private String mIntendedExitFrameData = null;
    private final List<String> mRegisteredPatterns = new ArrayList<>();
    private boolean mWaitForTransaction = true;
    private ComponentName mServiceName = null;

    private final CardEmulation.NfcEventCallback mEventListener =
            new CardEmulation.NfcEventCallback() {
                public void onObserveModeDisabledInFirmware(PollingFrame exitFrame) {
                    if (exitFrame != null) {
                        mReceivedExitFrame = HexFormat.of().formatHex(exitFrame.getData());
                        Log.d(TAG, "Received exit frame: " + mReceivedExitFrame);
                    }

                    if (!mWaitForTransaction) {
                        verifyExitFrameAndPassTest();
                    }
                }
            };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mIntendedExitFrameData = getIntent().getStringExtra(EXIT_FRAME_KEY);
        mWaitForTransaction = getIntent().getBooleanExtra(WAIT_FOR_TRANSACTION_KEY, true);

        setupServices(PaymentService1.COMPONENT);
        makeDefaultWalletRoleHolder();
    }

    public void onResume() {
        super.onResume();
        mServiceName =
                new ComponentName(this.getApplicationContext(), PaymentService1.class);
        mCardEmulation.setPreferredService(this, mServiceName);
        waitForPreferredService();

        mReceivedExitFrame = null;
        registerEventListener(mEventListener);

        if (getIntent().hasExtra(REGISTER_PATTERNS_KEY)) {
            getIntent().getStringArrayListExtra(REGISTER_PATTERNS_KEY).forEach(
                    plpf -> {
                        mCardEmulation.registerPollingLoopPatternFilterForService(mServiceName,
                                plpf, true);
                        mRegisteredPatterns.add(plpf);
                    }
            );
        }
    }

    @Override
    public void onPause() {
        super.onPause();

        mRegisteredPatterns.forEach(
                plpf -> mCardEmulation.removePollingLoopPatternFilterForService(mServiceName,
                        plpf));
        mCardEmulation.unsetPreferredService(this);
    }

    @Override
    public void onApduSequenceComplete(ComponentName component, long duration) {
        verifyExitFrameAndPassTest();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mCardEmulation.unregisterNfcEventCallback(mEventListener);
    }

    @Override
    public ComponentName getPreferredServiceComponent() {
        return PaymentService1.COMPONENT;
    }

    private void verifyExitFrameAndPassTest() {
        if (mIntendedExitFrameData == null || mReceivedExitFrame == null) {
            return;
        }

        boolean success =
                Pattern.compile(mIntendedExitFrameData).matcher(mReceivedExitFrame).matches();

        if (success) {
            setTestPassed();
        }
    }
}