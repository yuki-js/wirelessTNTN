package io.github.yukijs.wirelesstntn;

import android.content.Context;
import android.se.omapi.Channel;
import android.se.omapi.Reader;
import android.se.omapi.SEService;
import android.se.omapi.Session;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;

/**
 * Manages the lifecycle of the OMAPI {@link SEService} connection and exposes
 * a simple API for the HCE passthrough service:
 *
 * <ol>
 *   <li>Call {@link #connect(Context)} once (from MainActivity).
 *   <li>Call {@link #selectReader(String)} when the user picks a reader.
 *   <li>Call {@link #openLogicalChannel(byte[])} for each NFC transaction.
 *   <li>Call {@link #closeCurrentSession()} when the NFC session ends.
 *   <li>Call {@link #disconnect()} in onDestroy.
 * </ol>
 *
 * <p>Singleton – shared between {@link MainActivity} and
 * {@link PassthroughHceService} which run in the same process.</p>
 */
public final class OmapiManager implements SEService.OnConnectedListener {

    private static final String TAG = "OmapiManager";

    // ── Singleton ──────────────────────────────────────────────────────────

    private static OmapiManager sInstance;

    public static synchronized OmapiManager getInstance() {
        if (sInstance == null) {
            sInstance = new OmapiManager();
        }
        return sInstance;
    }

    private OmapiManager() {}

    // ── State ──────────────────────────────────────────────────────────────

    private SEService mSeService;
    private String mSelectedReaderName;
    private Reader mSelectedReader;
    private Session mCurrentSession;

    /** Optional callback fired on the OMAPI executor thread when SE connects. */
    public interface OnConnectedCallback {
        void onConnected();
    }

    private volatile OnConnectedCallback mOnConnectedCallback;

    // ── Public API ─────────────────────────────────────────────────────────

    /**
     * Bind to the OMAPI {@link SEService}.  Safe to call multiple times; a
     * second call is a no-op if already connected.
     */
    public synchronized void connect(Context context) {
        if (mSeService != null) {
            return;
        }
        AppLog.log("SEService: connecting…");
        mSeService = new SEService(context.getApplicationContext(),
                Executors.newSingleThreadExecutor(), this);
    }

    /** Register a callback to be invoked once the SE service is ready. */
    public void setOnConnectedCallback(OnConnectedCallback cb) {
        mOnConnectedCallback = cb;
        // If already connected fire immediately
        if (mSeService != null && mSeService.isConnected() && cb != null) {
            cb.onConnected();
        }
    }

    @Override
    public void onConnected() {
        AppLog.log("SEService: connected – " + getReaderNames());
        updateSelectedReader();
        OnConnectedCallback cb = mOnConnectedCallback;
        if (cb != null) {
            cb.onConnected();
        }
    }

    /** Returns names of all available SE readers (SIM1, SIM2, eSE1 …). */
    public synchronized List<String> getReaderNames() {
        List<String> names = new ArrayList<>();
        if (mSeService != null && mSeService.isConnected()) {
            for (Reader r : mSeService.getReaders()) {
                names.add(r.getName());
            }
        }
        return names;
    }

    /** Selects the reader that will be used for future passthrough sessions. */
    public synchronized void selectReader(String readerName) {
        mSelectedReaderName = readerName;
        updateSelectedReader();
        AppLog.log("Reader selected: " + readerName);
    }

    /**
     * Opens a new logical channel to the currently-selected SE reader for
     * the given AID.  Closes any previously-open session first.
     *
     * @param aid Raw AID bytes extracted from the NFC SELECT command.
     * @return The open {@link Channel} whose {@code getSelectResponse()} holds
     *         the SE's response to SELECT.
     * @throws IOException if the reader is unavailable, the AID is not found,
     *                     or any other OMAPI-level error occurs.
     */
    public Channel openLogicalChannel(byte[] aid) throws IOException {
        Reader reader;
        synchronized (this) {
            if (mSelectedReader == null) {
                throw new IOException("No SE reader selected");
            }
            reader = mSelectedReader;
        }

        closeCurrentSession();

        Session session = reader.openSession();
        synchronized (this) {
            mCurrentSession = session;
        }

        try {
            Channel ch = session.openLogicalChannel(aid);
            AppLog.log("OMAPI channel opened for AID " + bytesToHex(aid)
                    + "  SELECT response: " + bytesToHex(ch.getSelectResponse()));
            return ch;
        } catch (Exception e) {
            closeCurrentSession();
            throw new IOException("openLogicalChannel failed: " + e.getMessage(), e);
        }
    }

    /** Closes the current session (and all its channels). */
    public synchronized void closeCurrentSession() {
        if (mCurrentSession != null && !mCurrentSession.isClosed()) {
            try {
                mCurrentSession.close();
            } catch (Exception ignored) {
            }
        }
        mCurrentSession = null;
    }

    /** Returns {@code true} if the SE service is connected. */
    public synchronized boolean isConnected() {
        return mSeService != null && mSeService.isConnected();
    }

    /** Returns {@code true} if a reader is selected and ready. */
    public synchronized boolean hasSelectedReader() {
        return mSelectedReader != null;
    }

    /** Shuts down the SE service connection. */
    public synchronized void disconnect() {
        closeCurrentSession();
        if (mSeService != null) {
            try {
                if (mSeService.isConnected()) {
                    mSeService.shutdown();
                }
            } catch (Exception ignored) {
            }
            mSeService = null;
        }
    }

    // ── Private helpers ────────────────────────────────────────────────────

    private synchronized void updateSelectedReader() {
        if (mSeService == null || !mSeService.isConnected() || mSelectedReaderName == null) {
            mSelectedReader = null;
            return;
        }
        mSelectedReader = null;
        for (Reader r : mSeService.getReaders()) {
            if (r.getName().equals(mSelectedReaderName)) {
                mSelectedReader = r;
                return;
            }
        }
    }

    static String bytesToHex(byte[] bytes) {
        if (bytes == null) return "(null)";
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02X", b));
        }
        return sb.toString();
    }
}
