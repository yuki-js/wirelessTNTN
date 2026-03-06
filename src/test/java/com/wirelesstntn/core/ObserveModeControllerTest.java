package com.wirelesstntn.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ObserveModeControllerTest {

    @Test
    void keepObserveModeWhenRouteRejected() {
        ObserveModeController controller = new ObserveModeController();

        ObserveModeController.RoutingResult result =
                controller.handleAidRequest(new byte[]{(byte) 0xA0, 0x00}, false, new byte[]{(byte) 0x90, 0x00});

        assertEquals(ObserveModeController.Flow.OBSERVE_CONTINUE, result.flow());
        assertTrue(controller.isObserveModeActive());
    }

    @Test
    void keepObserveModeWhenStatusWordIs6A82() {
        ObserveModeController controller = new ObserveModeController();

        ObserveModeController.RoutingResult result =
                controller.handleAidRequest(new byte[]{(byte) 0xA0, 0x00}, true, new byte[]{0x6A, (byte) 0x82});

        assertEquals(ObserveModeController.Flow.OBSERVE_CONTINUE, result.flow());
        assertTrue(controller.isObserveModeActive());
    }

    @Test
    void transitionToNormalFlowAfterSuccessfulRouting() {
        ObserveModeController controller = new ObserveModeController();

        ObserveModeController.RoutingResult first =
                controller.handleAidRequest(new byte[]{(byte) 0xA0, 0x01}, true, new byte[]{(byte) 0x90, 0x00});
        ObserveModeController.RoutingResult second =
                controller.handleAidRequest(new byte[]{(byte) 0xA0, 0x02}, true, new byte[]{(byte) 0x90, 0x00});

        assertEquals(ObserveModeController.Flow.TRANSITION_TO_NORMAL_HCE, first.flow());
        assertEquals(ObserveModeController.Flow.NORMAL_HCE, second.flow());
        assertEquals("a001", first.aidHex());
    }
}
