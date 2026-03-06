package app.aoki.yuki.wirelesstntn;

import android.annotation.SuppressLint;
import android.nfc.NfcAdapter;
import android.nfc.cardemulation.CardEmulation;
import android.nfc.cardemulation.HostApduService;
import android.nfc.cardemulation.PollingFrame;
import android.os.Bundle;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

// processPollingFrames requires minSdk 35; CardEmulation observe methods require API 35
@SuppressLint("NewApi")
public class PassthroughHceService extends HostApduService {

    private static final byte[] SW_FILE_NOT_FOUND = {0x6A, (byte) 0x82};
    private static final byte[] SW_INTERNAL_ERROR  = {0x6F, 0x00};
    private static final byte[] SW_OK              = {(byte) 0x90, 0x00};

    // Static instance — HostApduService.onBind() is final, cannot use ServiceConnection
    private static volatile PassthroughHceService instance;

    public static PassthroughHceService getInstance() {
        return instance;
    }

    private OmapiSessionManager omapiManager;
    private PollingFrameObserver pollingFrameObserver;
    private volatile boolean active = false;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        omapiManager = new OmapiSessionManager();
        pollingFrameObserver = new PollingFrameObserver(new PollingFrameObserver.Callback() {
            @Override
            public void onReaderDetected() {
                if (active) {
                    AppLog.i("PassthroughHceService: reader detected, switching to active mode");
                    setObserveMode(false);
                }
            }

            @Override
            public void onFrameLog(String description) {
                // already logged in AppLog by PollingFrameObserver
            }
        });
        AppLog.i("PassthroughHceService: created");
    }

    @Override
    public void onDestroy() {
        instance = null;
        omapiManager.disconnect();
        AppLog.i("PassthroughHceService: destroyed");
        super.onDestroy();
    }

    public void startPassthrough(String readerName) {
        active = true;
        AppLog.i("PassthroughHceService: starting passthrough, reader=" + readerName);
        setObserveMode(true);
        omapiManager.connect(this, readerName, new OmapiSessionManager.Callback() {
            @Override
            public void onConnected() {
                AppLog.i("PassthroughHceService: OMAPI connected to " + readerName);
            }

            @Override
            public void onError(String message) {
                AppLog.e("PassthroughHceService: OMAPI connect error: " + message);
                active = false;
            }
        });
    }

    public void stopPassthrough() {
        active = false;
        omapiManager.closeChannel();
        setObserveMode(true);
        AppLog.i("PassthroughHceService: stopped passthrough");
    }

    /**
     * Toggle observe mode via CardEmulation.setShouldDefaultToObserveModeForService.
     * setObserveModeEnabled() does not exist in HostApduService; this is the correct API 35 approach.
     */
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

    @Override
    public byte[] processCommandApdu(byte[] apdu, Bundle extras) {
        if (apdu == null || apdu.length < 4) {
            return SW_INTERNAL_ERROR;
        }

        AppLog.d("PassthroughHceService: APDU -> " + bytesToHex(apdu));

        // SELECT AID: CLA=00 INS=A4 P1=04
        if (apdu[0] == 0x00 && apdu[1] == (byte) 0xA4 && apdu[2] == 0x04) {
            return handleSelect(apdu);
        }

        return forwardApdu(apdu);
    }

    private byte[] handleSelect(byte[] apdu) {
        if (apdu.length < 6) {
            return SW_FILE_NOT_FOUND;
        }
        int aidLen = apdu[4] & 0xFF;
        if (apdu.length < 5 + aidLen) {
            return SW_FILE_NOT_FOUND;
        }
        byte[] aid = Arrays.copyOfRange(apdu, 5, 5 + aidLen);
        AppLog.i("PassthroughHceService: SELECT AID=" + bytesToHex(aid));
        try {
            byte[] selectResponse = omapiManager.openChannel(aid);
            if (selectResponse == null || selectResponse.length == 0) {
                return SW_OK;
            }
            return selectResponse;
        } catch (IOException e) {
            AppLog.e("PassthroughHceService: SELECT failed", e);
            return SW_FILE_NOT_FOUND;
        }
    }

    private byte[] forwardApdu(byte[] apdu) {
        try {
            byte[] response = omapiManager.transmit(apdu);
            AppLog.d("PassthroughHceService: APDU <- " + bytesToHex(response));
            return response;
        } catch (IOException e) {
            AppLog.e("PassthroughHceService: transmit failed", e);
            return SW_INTERNAL_ERROR;
        }
    }

    @Override
    public void onDeactivated(int reason) {
        AppLog.i("PassthroughHceService: deactivated reason=" + reason);
        omapiManager.closeChannel();
        if (active) {
            setObserveMode(true);
        }
    }

    private static String bytesToHex(byte[] bytes) {
        if (bytes == null) return "null";
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02X", b));
        }
        return sb.toString();
    }
}
