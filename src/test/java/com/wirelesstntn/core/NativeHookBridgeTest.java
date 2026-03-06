package com.wirelesstntn.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class NativeHookBridgeTest {

    @Test
    void forwardsApduBidirectionallyViaTransport() {
        byte[] command = new byte[]{0x00, (byte) 0xA4, 0x04, 0x00};
        byte[] response = new byte[]{(byte) 0x90, 0x00};

        NativeHookBridge bridge = new NativeHookBridge((apdu, target) -> {
            assertArrayEquals(command, apdu);
            assertEquals(SecureElementTarget.SIM1, target);
            return response;
        });

        byte[] actual = bridge.passThrough(command, SecureElementTarget.SIM1);
        assertArrayEquals(response, actual);
    }
}
