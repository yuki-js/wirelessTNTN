package app.aoki.yuki.wirelesstntn;

import android.annotation.SuppressLint;
import android.nfc.cardemulation.PollingFrame;

import java.util.List;

// PollingFrame API requires minSdk 35
@SuppressLint("NewApi")
public class PollingFrameObserver {

    public interface Callback {
        void onReaderDetected();
        void onFrameLog(String description);
    }

    private final Callback callback;

    public PollingFrameObserver(Callback callback) {
        this.callback = callback;
    }

    public void onPollingFrames(List<PollingFrame> frames) {
        for (PollingFrame frame : frames) {
            int type = frame.getType();
            String typeStr = pollingFrameTypeToString(type);
            String desc = "PollingFrame type=" + typeStr;
            AppLog.d(desc);
            callback.onFrameLog(desc);

            if (type == PollingFrame.POLLING_LOOP_TYPE_A ||
                type == PollingFrame.POLLING_LOOP_TYPE_B) {
                AppLog.i("PollingFrameObserver: ISO-DEP reader detected");
                callback.onReaderDetected();
            }
        }
    }

    private static String pollingFrameTypeToString(int type) {
        if (type == PollingFrame.POLLING_LOOP_TYPE_A) return "TYPE_A";
        if (type == PollingFrame.POLLING_LOOP_TYPE_B) return "TYPE_B";
        if (type == PollingFrame.POLLING_LOOP_TYPE_F) return "TYPE_F";
        if (type == PollingFrame.POLLING_LOOP_TYPE_ON) return "ON";
        if (type == PollingFrame.POLLING_LOOP_TYPE_OFF) return "OFF";
        if (type == PollingFrame.POLLING_LOOP_TYPE_UNKNOWN) return "UNKNOWN";
        return "0x" + Integer.toHexString(type);
    }
}
