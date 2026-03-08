package app.aoki.yuki.wirelesstntn;

import android.content.Intent;
import android.nfc.cardemulation.HostApduService;
import android.os.Bundle;

import java.util.Arrays;

/**
 * NFC Host Card Emulation service.
 *
 * Accepts incoming SELECT AID commands for any AID that has been registered dynamically
 * by MainActivity via CardEmulation.registerAidsForService().  Observer Mode and OMAPI
 * passthrough are not used.
 */
public class PassthroughHceService extends HostApduService {

    static final String ACTION_START = "app.aoki.yuki.wirelesstntn.START_PASSTHROUGH";
    static final String ACTION_STOP  = "app.aoki.yuki.wirelesstntn.STOP_PASSTHROUGH";

    private static final byte[] SW_OK                       = {(byte) 0x90, 0x00};
    private static final byte[] SW_FILE_NOT_FOUND           = {0x6A, (byte) 0x82};
    private static final byte[] SW_CONDITIONS_NOT_SATISFIED = {0x69, (byte) 0x85};
    private static final byte[] SW_INTERNAL_ERROR           = {0x6F, 0x00};

    private volatile boolean active = false;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            if (ACTION_START.equals(intent.getAction())) {
                active = true;
                AppLog.i("PassthroughHceService: started");
            } else if (ACTION_STOP.equals(intent.getAction())) {
                active = false;
                AppLog.i("PassthroughHceService: stopped");
            }
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        AppLog.i("PassthroughHceService: destroyed");
        super.onDestroy();
    }

    /**
     * Handle an incoming APDU.
     * SELECT AID (CLA=00 INS=A4 P1=04) is accepted with SW 9000.
     * Any other APDU is returned with SW 6985 (conditions not satisfied) when no channel is open.
     */
    @Override
    public byte[] processCommandApdu(byte[] apdu, Bundle extras) {
        if (apdu == null || apdu.length < 4) return SW_INTERNAL_ERROR;
        AppLog.d("PassthroughHceService: APDU -> " + bytesToHex(apdu));

        if (!active) {
            AppLog.e("PassthroughHceService: APDU received but session not active");
            return SW_CONDITIONS_NOT_SATISFIED;
        }

        if (isSelectAid(apdu)) {
            String aid = extractAidHex(apdu);
            AppLog.i("PassthroughHceService: SELECT AID=" + aid);
            return SW_OK;
        }

        AppLog.d("PassthroughHceService: non-SELECT APDU, no channel open");
        return SW_CONDITIONS_NOT_SATISFIED;
    }

    @Override
    public void onDeactivated(int reason) {
        AppLog.i("PassthroughHceService: deactivated reason=" + reason);
    }

    /** Returns true for SELECT by AID (CLA=00, INS=A4, P1=04). */
    private static boolean isSelectAid(byte[] apdu) {
        return apdu[0] == 0x00 && apdu[1] == (byte) 0xA4 && apdu[2] == 0x04;
    }

    private static String extractAidHex(byte[] apdu) {
        if (apdu.length < 6) return "";
        int aidLen = apdu[4] & 0xFF;
        if (apdu.length < 5 + aidLen) return "";
        return bytesToHex(Arrays.copyOfRange(apdu, 5, 5 + aidLen));
    }

    private static String bytesToHex(byte[] bytes) {
        if (bytes == null) return "null";
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02X", b));
        return sb.toString();
    }
}
