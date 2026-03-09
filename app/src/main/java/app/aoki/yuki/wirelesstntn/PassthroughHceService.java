package app.aoki.yuki.wirelesstntn;

import android.content.Intent;
import android.nfc.cardemulation.HostApduService;
import android.os.Bundle;
import android.se.omapi.Reader;
import android.se.omapi.SEService;
import android.se.omapi.Session;

import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * NFC Host Card Emulation service that passes every APDU through to the Secure Element via OMAPI.
 *
 * How it fits into the system
 * ---------------------------
 * The NFC controller's Listener Mode Routing Table (LMRT) routes incoming AID SELECTs to the
 * Device Host (DH). Android's HostEmulationManager then calls RegisteredAidCache.resolveAid()
 * to pick which HCE service receives the APDUs.
 *
 * LmrtHijackModule (an LSPosed/Xposed module running inside com.android.nfc) intercepts
 * resolveAid() and always returns this service as the target. As a result, every AID that
 * reaches the DH is delivered here, regardless of what is registered in apduservice.xml.
 *
 * apduservice.xml still lists many AID prefixes — those are the AIDs for which the LMRT has
 * a DH route, so they are the AIDs that can be intercepted by the hook in the first place.
 *
 * APDU flow
 * ---------
 *   1. NFC reader taps → NFC controller → DH via LMRT
 *   2. HostEmulationManager calls resolveAid() → (hooked) → this service
 *   3. processCommandApdu() receives the SELECT AID
 *   4. ApduPassthroughController opens an OMAPI logical channel for that AID
 *   5. Subsequent APDUs forwarded through the same channel
 *   6. Field lost → onDeactivated() → channel closed, ready for next reader
 *
 * This service is started (via ACTION_START) only when the user presses Start in MainActivity.
 * It is a foreground-preferred service via CardEmulation.setPreferredService() so that
 * standard AID routing (without the hook) also prefers this service while the activity is open.
 *
 * No Observer Mode is used. The Xposed hook replaces the role that observe mode served before.
 */
public class PassthroughHceService extends HostApduService {

    static final String ACTION_START = "app.aoki.yuki.wirelesstntn.START_PASSTHROUGH";
    static final String ACTION_STOP  = "app.aoki.yuki.wirelesstntn.STOP_PASSTHROUGH";
    static final String EXTRA_READER = "reader_name";

    private static final byte[] SW_INTERNAL_ERROR = {0x6F, 0x00};

    // Static instance — HostApduService.onBind() is final; cannot use ServiceConnection.
    private static volatile PassthroughHceService instance;
    public static PassthroughHceService getInstance() { return instance; }

    // OMAPI objects — created on start, torn down on stop.
    private SEService seService;
    private ApduPassthroughController passthroughController;

    // Single-thread executor for OMAPI I/O — avoids blocking the NFC thread during SE I/O.
    private final ExecutorService apduExecutor = Executors.newSingleThreadExecutor();
    // Executor used for SEService callbacks; stored so it can be shut down with the session.
    private ExecutorService seExecutor;

    private volatile boolean active = false;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        AppLog.i("PassthroughHceService: created");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            if (ACTION_START.equals(intent.getAction())) {
                String readerName = intent.getStringExtra(EXTRA_READER);
                startPassthrough(readerName);
            } else if (ACTION_STOP.equals(intent.getAction())) {
                stopPassthrough();
            }
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        instance = null;
        apduExecutor.shutdownNow();
        tearDownOmapi();
        AppLog.i("PassthroughHceService: destroyed");
        super.onDestroy();
    }

    // -------------------------------------------------------------------------
    // Session lifecycle
    // -------------------------------------------------------------------------

    /**
     * Opens the OMAPI session on the chosen Secure Element reader.
     * Called when the user presses Start. APDUs arriving before the session is
     * ready return SW_INTERNAL_ERROR (6F 00).
     */
    private void startPassthrough(String readerName) {
        active = true;
        AppLog.i("PassthroughHceService: connecting OMAPI, reader=" + readerName);

        seExecutor = Executors.newSingleThreadExecutor();
        seService = new SEService(getApplicationContext(), seExecutor, () -> {
            Reader[] readers = seService.getReaders();
            for (Reader r : readers) {
                if (r.getName().equalsIgnoreCase(readerName)) {
                    try {
                        Session session = r.openSession();
                        passthroughController = new ApduPassthroughController(session);
                        AppLog.i("PassthroughHceService: OMAPI session ready on " + readerName);
                    } catch (IOException e) {
                        AppLog.e("PassthroughHceService: failed to open session", e);
                        active = false;
                    }
                    return;
                }
            }
            AppLog.e("PassthroughHceService: reader not found: " + readerName);
            active = false;
        });
    }

    /** Tears down the OMAPI session. Called when the user presses Stop. */
    private void stopPassthrough() {
        active = false;
        tearDownOmapi();
        AppLog.i("PassthroughHceService: stopped");
    }

    // -------------------------------------------------------------------------
    // HCE callbacks
    // -------------------------------------------------------------------------

    /**
     * Receives every AID SELECT and subsequent APDU from the NFC stack.
     *
     * Returns null to signal asynchronous processing; the actual response is
     * delivered via sendResponseApdu() from apduExecutor to avoid blocking the
     * NFC thread during potentially slow SE I/O.
     */
    @Override
    public byte[] processCommandApdu(byte[] apdu, Bundle extras) {
        if (apdu == null || apdu.length < 4) return SW_INTERNAL_ERROR;
        AppLog.d("PassthroughHceService: APDU -> " + bytesToHex(apdu));

        final byte[] copy = Arrays.copyOf(apdu, apdu.length);
        apduExecutor.execute(() -> {
            byte[] response = dispatchApdu(copy);
            sendResponseApdu(response);
        });
        return null; // async — response will be sent via sendResponseApdu()
    }

    private byte[] dispatchApdu(byte[] apdu) {
        if (!active || passthroughController == null) {
            AppLog.e("PassthroughHceService: APDU received but OMAPI session not ready");
            return SW_INTERNAL_ERROR;
        }
        try {
            return passthroughController.process(apdu);
        } catch (IOException e) {
            AppLog.e("PassthroughHceService: APDU error", e);
            return SW_INTERNAL_ERROR;
        }
    }

    /**
     * Called by the NFC stack when the reader field is lost or the transaction
     * ends. We close the current logical channel so the SE is ready for the next
     * SELECT, but keep the OMAPI session open for reuse.
     */
    @Override
    public void onDeactivated(int reason) {
        AppLog.i("PassthroughHceService: deactivated reason=" + reason);
        if (passthroughController != null) {
            passthroughController.closeCurrentChannel();
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void tearDownOmapi() {
        if (passthroughController != null) {
            passthroughController.close();
            passthroughController = null;
        }
        if (seService != null && seService.isConnected()) {
            seService.shutdown();
            seService = null;
        }
        if (seExecutor != null) {
            seExecutor.shutdown();
            seExecutor = null;
        }
    }

    private static String bytesToHex(byte[] bytes) {
        if (bytes == null) return "null";
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02X", b));
        return sb.toString();
    }
}
