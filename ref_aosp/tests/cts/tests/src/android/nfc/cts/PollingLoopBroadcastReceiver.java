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

package android.nfc.cts;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.nfc.cardemulation.PollingFrame;

import java.util.List;

public class PollingLoopBroadcastReceiver extends BroadcastReceiver {

    private static final String CLASS_NAME_KEY = "class_name";
    private static final String FRAMES_KEY = "frames";
    private static final String POLLING_LOOP_FIRED = "com.cts.PollingLoopFired";
    private static final String OBSERVE_MODE_CHANGED = "com.cts.ObserveModeChanged";
    private static final String PREFERRED_SERVISE_CHANGED = "com.cts.PreferredServiceChanged";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (CardEmulationTest.sWalletRolePollLoopReceiver != null) {
            String className = intent.getStringExtra(CLASS_NAME_KEY);
            switch (intent.getAction()) {
                case POLLING_LOOP_FIRED:
                    List<PollingFrame> frames =
                            intent.getParcelableArrayListExtra(FRAMES_KEY, PollingFrame.class);
                    CardEmulationTest.sWalletRolePollLoopReceiver.notifyPollingLoop(
                            className, frames);
                    break;
                case OBSERVE_MODE_CHANGED:
                    if (intent.hasExtra("enabled")) {
                        boolean isEnabled = intent.getBooleanExtra("enabled", false);
                        CardEmulationTest.sWalletRolePollLoopReceiver.onObserveModeStateChanged(
                                className, isEnabled);
                    }
                    break;
                case PREFERRED_SERVISE_CHANGED:
                    if (intent.hasExtra("preferred")) {
                        boolean isPreferred = intent.getBooleanExtra("preferred", false);
                        CardEmulationTest.sWalletRolePollLoopReceiver.onPreferredServiceChanged(
                                className, isPreferred);
                    }
                    break;
            }
        }
    }
}
