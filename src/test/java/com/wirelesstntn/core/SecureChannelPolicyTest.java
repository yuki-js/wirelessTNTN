package com.wirelesstntn.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecureChannelPolicyTest {

    @Test
    void acceptsOnlyScFFFF() {
        assertTrue(SecureChannelPolicy.isCompliant("FFFF"));
        assertFalse(SecureChannelPolicy.isCompliant("ffff"));
        assertFalse(SecureChannelPolicy.isCompliant("0000"));
        assertFalse(SecureChannelPolicy.isCompliant(null));
    }
}
