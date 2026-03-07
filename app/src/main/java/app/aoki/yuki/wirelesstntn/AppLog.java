package app.aoki.yuki.wirelesstntn;

import android.util.Log;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

public final class AppLog {
    private static final String TAG = "WirelessTNTN";
    private static final int MAX_ENTRIES = 200;
    private static final ArrayDeque<String> buffer = new ArrayDeque<>(MAX_ENTRIES);
    private static volatile OnLogListener listener;

    public interface OnLogListener {
        void onLog(String message);
    }

    public static void setListener(OnLogListener l) {
        listener = l;
    }

    public static void i(String msg) {
        Log.i(TAG, msg);
        append(msg);
    }

    public static void d(String msg) {
        Log.d(TAG, msg);
        append(msg);
    }

    public static void e(String msg) {
        Log.e(TAG, msg);
        append(msg);
    }

    public static void e(String msg, Throwable t) {
        Log.e(TAG, msg, t);
        append(msg + ": " + t.getMessage());
    }

    private static synchronized void append(String msg) {
        if (buffer.size() >= MAX_ENTRIES) {
            buffer.pollFirst();
        }
        buffer.addLast(msg);
        OnLogListener l = listener;
        if (l != null) {
            l.onLog(msg);
        }
    }

    public static synchronized List<String> getEntries() {
        return new ArrayList<>(buffer);
    }

    private AppLog() {}
}
