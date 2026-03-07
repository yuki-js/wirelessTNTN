package app.aoki.yuki.wirelesstntn;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.nfc.NfcAdapter;
import android.nfc.cardemulation.CardEmulation;
import android.nfc.cardemulation.HostApduService;
import android.nfc.cardemulation.PollingFrame;
import android.os.Bundle;
import android.se.omapi.Reader;
import android.se.omapi.SEService;
import android.se.omapi.Session;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// processPollingFrames and CardEmulation.setShouldDefaultToObserveModeForService require API 35
@SuppressLint("NewApi")
public class PassthroughHceService extends HostApduService {

    static final String ACTION_START  = "app.aoki.yuki.wirelesstntn.START_PASSTHROUGH";
    static final String ACTION_STOP   = "app.aoki.yuki.wirelesstntn.STOP_PASSTHROUGH";
    static final String EXTRA_READER  = "reader_name";

    private static final byte[] SW_INTERNAL_ERROR = {0x6F, 0x00};

    // Static instance — HostApduService.onBind() is final, cannot use ServiceConnection.
    private static volatile PassthroughHceService instance;
    public static PassthroughHceService getInstance() { return instance; }

    // OMAPI objects — created on start, torn down on stop.
    private SEService seService;
    private ApduPassthroughController passthroughController;

    private PollingFrameObserver pollingFrameObserver;
    // Single-thread executor for OMAPI I/O (avoids blocking the NFC main thread).
    private final ExecutorService apduExecutor = Executors.newSingleThreadExecutor();
    // Executor for SEService callbacks; stored so it can be shut down with the session.
    private ExecutorService seExecutor;
    private volatile boolean active = false;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        pollingFrameObserver = new PollingFrameObserver(new PollingFrameObserver.Callback() {
            @Override
            public void onReaderDetected() {
                // Exit observe mode only after the OMAPI session is ready to handle APDUs.
                if (active && passthroughController != null) {
                    AppLog.i("PassthroughHceService: reader detected, exiting observe mode");
                    setObserveMode(false);
                }
            }
            @Override public void onFrameLog(String description) {}
        });
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

    private void startPassthrough(String readerName) {
        active = true;
        // Enter observe mode first; PollingFrameObserver exits it once a reader is detected
        // and the OMAPI session is ready.
        setObserveMode(true);
        AppLog.i("PassthroughHceService: connecting OMAPI, reader=" + readerName);

        // Use a dedicated executor for SEService callbacks (required by SEService constructor).
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

    private void stopPassthrough() {
        active = false;
        tearDownOmapi();
        setObserveMode(true);
        AppLog.i("PassthroughHceService: stopped");
    }

    /** Toggle observe mode via the API 35 CardEmulation method. */
    private void setObserveMode(boolean enable) {
        NfcAdapter nfcAdapter = NfcAdapter.getDefaultAdapter(this);
        if (nfcAdapter == null) return;
        CardEmulation ce = CardEmulation.getInstance(nfcAdapter);
        android.content.ComponentName cn =
                new android.content.ComponentName(this, PassthroughHceService.class);
        ce.setShouldDefaultToObserveModeForService(cn, enable);
    }

    @Override
    public void processPollingFrames(List<PollingFrame> frames) {
        pollingFrameObserver.onPollingFrames(frames);
    }

    /**
     * Returns null to signal asynchronous processing; the actual response is sent via
     * sendResponseApdu() from the apduExecutor thread to avoid blocking the NFC thread
     * during potentially slow SE I/O.
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
        return null; // async — response sent via sendResponseApdu()
    }

    private byte[] dispatchApdu(byte[] apdu) {
        if (passthroughController == null) {
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

    @Override
    public void onDeactivated(int reason) {
        AppLog.i("PassthroughHceService: deactivated reason=" + reason);
        // Close channel but keep the session alive for the next transaction.
        if (passthroughController != null) {
            passthroughController.closeCurrentChannel();
        }
        if (active) {
            // Re-enter observe mode so we can detect the next reader approach.
            setObserveMode(true);
        }
    }

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

