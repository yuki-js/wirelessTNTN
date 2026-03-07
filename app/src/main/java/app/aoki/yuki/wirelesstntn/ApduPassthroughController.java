package app.aoki.yuki.wirelesstntn;

import android.se.omapi.Channel;
import android.se.omapi.Session;

import java.io.IOException;
import java.util.Arrays;

/**
 * Domain logic for APDU passthrough.
 *
 * Owns a single OMAPI Session (already opened on the chosen Secure Element reader).
 * Implements the passthrough state machine:
 *  - SELECT AID → close any open channel, open a new logical channel for that AID
 *  - Any other APDU → forward through the current channel
 *
 * OMAPI's own Session/Channel API is already a clean abstraction; this class adds domain
 * knowledge on top (AID routing, channel lifecycle, error mapping) rather than re-wrapping
 * the same calls 1:1.
 */
public class ApduPassthroughController {

    private static final byte[] SW_OK                         = {(byte) 0x90, 0x00};
    private static final byte[] SW_FILE_NOT_FOUND             = {0x6A, (byte) 0x82};
    private static final byte[] SW_CONDITIONS_NOT_SATISFIED   = {0x69, (byte) 0x85};

    private final Session session;
    private Channel currentChannel;

    public ApduPassthroughController(Session session) {
        this.session = session;
    }

    /**
     * Process one APDU from the NFC reader and return the response to send back.
     * SELECT AID opens a new logical channel; all other commands are forwarded through
     * the currently open channel.
     */
    public byte[] process(byte[] apdu) throws IOException {
        if (apdu == null || apdu.length < 4) {
            throw new IOException("Malformed APDU");
        }
        if (isSelectAid(apdu)) {
            return handleSelectAid(apdu);
        }
        return forwardToCurrentChannel(apdu);
    }

    /** Returns true for SELECT by AID (CLA=00, INS=A4, P1=04). */
    private static boolean isSelectAid(byte[] apdu) {
        return apdu[0] == 0x00 && apdu[1] == (byte) 0xA4 && apdu[2] == 0x04;
    }

    private byte[] handleSelectAid(byte[] apdu) throws IOException {
        if (apdu.length < 6) return SW_FILE_NOT_FOUND;
        int aidLen = apdu[4] & 0xFF;
        if (apdu.length < 5 + aidLen) return SW_FILE_NOT_FOUND;

        byte[] aid = Arrays.copyOfRange(apdu, 5, 5 + aidLen);
        AppLog.i("Passthrough: SELECT AID=" + bytesToHex(aid));

        // Close previous channel before opening a new one for the new AID.
        closeCurrentChannel();
        currentChannel = session.openLogicalChannel(aid);
        if (currentChannel == null) {
            return SW_FILE_NOT_FOUND;
        }
        byte[] resp = currentChannel.getSelectResponse();
        AppLog.d("Passthrough: SELECT resp=" + bytesToHex(resp));
        return (resp != null && resp.length > 0) ? resp : SW_OK;
    }

    private byte[] forwardToCurrentChannel(byte[] apdu) throws IOException {
        if (currentChannel == null || !currentChannel.isOpen()) {
            AppLog.e("Passthrough: APDU received but no open channel");
            return SW_CONDITIONS_NOT_SATISFIED;
        }
        byte[] resp = currentChannel.transmit(apdu);
        AppLog.d("Passthrough: APDU <- " + bytesToHex(resp));
        return resp;
    }

    /** Close the current logical channel (e.g. after NFC field loss). */
    public void closeCurrentChannel() {
        if (currentChannel != null) {
            try { currentChannel.close(); } catch (Exception ignored) {}
            currentChannel = null;
        }
    }

    /** Full teardown: close channel and the underlying session. */
    public void close() {
        closeCurrentChannel();
        try { session.close(); } catch (Exception e) {
            AppLog.e("Passthrough: error closing session", e);
        }
    }

    private static String bytesToHex(byte[] bytes) {
        if (bytes == null) return "null";
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02X", b));
        return sb.toString();
    }
}
