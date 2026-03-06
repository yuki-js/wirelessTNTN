package io.github.yukijs.wirelesstntn;

import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Lightweight in-process log store.
 *
 * <p>Every log entry is written to Logcat (tag "WTNTN") <em>and</em> kept in
 * an in-memory ring-buffer so the MainActivity can display a live log view
 * without needing a separate IPC mechanism.</p>
 *
 * <p>Thread-safe: can be called from the HCE worker thread and the main
 * thread simultaneously.</p>
 */
public final class AppLog {

    private static final String TAG = "WTNTN";
    private static final int MAX_ENTRIES = 200;

    private static final ArrayDeque<String> sEntries = new ArrayDeque<>();

    /** Listener notified on the calling thread when a new entry arrives. */
    public interface OnLogListener {
        void onLog(String entry);
    }

    private static volatile OnLogListener sListener;

    private AppLog() {}

    /** Register a listener.  Pass {@code null} to clear. */
    public static void setListener(OnLogListener listener) {
        sListener = listener;
    }

    /** Append a new log entry. */
    public static void log(String message) {
        String timestamp = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date());
        String entry = "[" + timestamp + "] " + message;

        Log.d(TAG, message);

        synchronized (sEntries) {
            sEntries.addLast(entry);
            if (sEntries.size() > MAX_ENTRIES) {
                sEntries.removeFirst();
            }
        }

        OnLogListener l = sListener;
        if (l != null) {
            l.onLog(entry);
        }
    }

    /** Returns a snapshot of all buffered entries (oldest first). */
    public static List<String> getEntries() {
        synchronized (sEntries) {
            return new ArrayList<>(sEntries);
        }
    }

    /** Clears all buffered entries. */
    public static void clear() {
        synchronized (sEntries) {
            sEntries.clear();
        }
    }
}
