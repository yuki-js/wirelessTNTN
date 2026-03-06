package com.wirelesstntn.core;

import java.util.HexFormat;
import java.util.Objects;

public final class ObserveModeController {

    private boolean observeModeActive = true;

    public RoutingResult handleAidRequest(byte[] aid, boolean routeAllowed, byte[] seStatusWord) {
        Objects.requireNonNull(aid, "aid must not be null");
        Objects.requireNonNull(seStatusWord, "seStatusWord must not be null");

        if (!observeModeActive) {
            return RoutingResult.normalFlow(aid);
        }

        if (!routeAllowed) {
            return RoutingResult.observeContinue(aid, "AID route rejected");
        }

        if (is6A82(seStatusWord)) {
            return RoutingResult.observeContinue(aid, "SE returned 6A82");
        }

        observeModeActive = false;
        return RoutingResult.transitionToNormal(aid);
    }

    public boolean isObserveModeActive() {
        return observeModeActive;
    }

    private static boolean is6A82(byte[] sw) {
        return sw.length == 2 && (sw[0] & 0xFF) == 0x6A && (sw[1] & 0xFF) == 0x82;
    }

    public enum Flow {
        OBSERVE_CONTINUE,
        TRANSITION_TO_NORMAL_HCE,
        NORMAL_HCE
    }

    public record RoutingResult(Flow flow, String aidHex, String reason) {
        static RoutingResult observeContinue(byte[] aid, String reason) {
            return new RoutingResult(Flow.OBSERVE_CONTINUE, HexFormat.of().formatHex(aid), reason);
        }

        static RoutingResult transitionToNormal(byte[] aid) {
            return new RoutingResult(Flow.TRANSITION_TO_NORMAL_HCE, HexFormat.of().formatHex(aid), "Route established");
        }

        static RoutingResult normalFlow(byte[] aid) {
            return new RoutingResult(Flow.NORMAL_HCE, HexFormat.of().formatHex(aid), "Observe mode already detached");
        }
    }
}
