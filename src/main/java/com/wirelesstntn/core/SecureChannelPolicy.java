package com.wirelesstntn.core;

public final class SecureChannelPolicy {

    public static final String REQUIRED_SC = "FFFF";

    private SecureChannelPolicy() {
    }

    public static boolean isCompliant(String secureChannelId) {
        return REQUIRED_SC.equals(secureChannelId);
    }
}
