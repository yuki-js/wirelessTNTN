package io.github.yukijs.wirelesstntn;

import android.nfc.cardemulation.HostApduService;
import android.os.Bundle;
import android.se.omapi.Channel;

import java.io.IOException;

/**
 * NFC Host Card Emulation service that bridges incoming NFC APDU commands to
 * the Secure Element via OMAPI.
 *
 * <h3>Observe-mode integration (Android 15+)</h3>
 * The Android 15 NFC observe mode ({@code NfcAdapter.setObserveModeEnabled})
 * is managed by {@link MainActivity}:
 * <ul>
 *   <li><em>Passthrough disabled / app in background</em> – observe mode ON
 *       (device invisible to readers).
 *   <li><em>Passthrough enabled / app in foreground</em> – observe mode OFF
 *       (device visible as HCE card).
 * </ul>
 *
 * <h3>Per-transaction flow</h3>
 * <ol>
 *   <li>NFC reader sends {@code SELECT AID} (ISO 7816-4, INS=0xA4, P1=0x04).
 *   <li>{@link #processCommandApdu} extracts the AID, asks
 *       {@link OmapiManager} to open a logical channel, and returns the SE's
 *       {@code getSelectResponse()} to the reader.
 *   <li>Every subsequent APDU is forwarded to the open channel via
 *       {@link Channel#transmit(byte[])}.
 *   <li>On {@link #onDeactivated} the session is closed so the SE is free for
 *       the next transaction.
 * </ol>
 *
 * <h3>AID-not-found handling</h3>
 * If OMAPI rejects the AID (throws an exception – which typically means the SE
 * returned 6A82 or the AID simply isn't present) this service returns SW 6A82
 * to the NFC reader.  The app stays in observe mode so the reader can retry
 * with a different AID.
 */
public class PassthroughHceService extends HostApduService {

    private static final String LOG_TAG = "HCE-Passthrough";

    /** SW 6A 82 – File or application not found. */
    private static final byte[] SW_NOT_FOUND    = {0x6A, (byte) 0x82};
    /** SW 6F 00 – No precise diagnosis. */
    private static final byte[] SW_UNKNOWN_ERR  = {0x6F, 0x00};
    /** SW 69 85 – Conditions of use not satisfied (no open channel). */
    private static final byte[] SW_CONDITIONS   = {0x69, (byte) 0x85};

    private final OmapiManager mOmapi = OmapiManager.getInstance();

    /** OMAPI channel opened for the current NFC transaction. */
    private Channel mOpenChannel;

    // ── HostApduService callbacks ──────────────────────────────────────────

    @Override
    public void onCreate() {
        super.onCreate();
        // Ensure OMAPI is connected even when the service is started before
        // MainActivity (edge-case: system starts the service directly).
        if (!mOmapi.isConnected()) {
            mOmapi.connect(this);
        }
    }

    /**
     * Called by the Android NFC stack on a worker thread.
     *
     * <p>The NFC reader expects a response within 5 seconds; OMAPI operations
     * are blocking and complete well within that budget under normal
     * conditions.</p>
     */
    @Override
    public byte[] processCommandApdu(byte[] commandApdu, Bundle extras) {
        String hex = bytesToHex(commandApdu);
        AppLog.log("NFC << " + hex);

        if (isSelectAid(commandApdu)) {
            return handleSelectAid(commandApdu);
        }

        return forwardApdu(commandApdu);
    }

    @Override
    public void onDeactivated(int reason) {
        String reasonStr = (reason == DEACTIVATION_LINK_LOSS)
                ? "link-loss" : "deselected";
        AppLog.log("NFC deactivated (" + reasonStr + ")");
        closeChannelAndSession();
    }

    // ── APDU handling ──────────────────────────────────────────────────────

    /**
     * Opens an OMAPI logical channel for the AID carried in the SELECT
     * command, and returns the SE's SELECT response to the NFC reader.
     */
    private byte[] handleSelectAid(byte[] selectApdu) {
        byte[] aid = extractAid(selectApdu);
        AppLog.log("SELECT AID: " + bytesToHex(aid));

        // Close any channel left over from a previous SELECT in this session.
        closeChannelAndSession();

        try {
            Channel ch = mOmapi.openLogicalChannel(aid);
            mOpenChannel = ch;
            byte[] resp = ch.getSelectResponse();
            AppLog.log("SE >> " + bytesToHex(resp));
            return resp;
        } catch (IOException e) {
            AppLog.log("AID rejected (" + e.getMessage() + ") – staying in observe mode");
            return SW_NOT_FOUND;
        }
    }

    /**
     * Forwards a non-SELECT APDU to the open OMAPI channel.
     *
     * <p>The SE's OMAPI implementation adjusts the CLA logical-channel bits
     * internally (per the SIMalliance Open Mobile API spec §5.4), so we
     * forward the command as received from the NFC reader.</p>
     */
    private byte[] forwardApdu(byte[] commandApdu) {
        if (mOpenChannel == null || mOpenChannel.isClosed()) {
            AppLog.log("No open channel for APDU " + bytesToHex(commandApdu));
            return SW_CONDITIONS;
        }

        try {
            byte[] response = mOpenChannel.transmit(commandApdu);
            AppLog.log("SE >> " + bytesToHex(response));
            return response;
        } catch (IOException e) {
            AppLog.log("Transmit failed: " + e.getMessage());
            return SW_UNKNOWN_ERR;
        }
    }

    // ── Session / channel lifecycle ────────────────────────────────────────

    private void closeChannelAndSession() {
        if (mOpenChannel != null) {
            try {
                if (!mOpenChannel.isClosed()) {
                    mOpenChannel.close();
                }
            } catch (Exception ignored) {
            }
            mOpenChannel = null;
        }
        mOmapi.closeCurrentSession();
    }

    // ── APDU parsing helpers ───────────────────────────────────────────────

    /**
     * Returns {@code true} iff {@code apdu} is an ISO 7816-4 SELECT by AID:
     * CLA=0x0X  INS=0xA4  P1=0x04.
     *
     * <p>We accept any CLA lower-nibble so that SELECT commands on logical
     * channels (CLA 0x01..0x03) are also recognised correctly.</p>
     */
    private static boolean isSelectAid(byte[] apdu) {
        if (apdu == null || apdu.length < 6) return false;
        byte cla = apdu[0];
        // Mask away secure-messaging and channel bits, keep only class-family
        boolean validCla = (cla & 0xE0) == 0x00;   // first-class APDU family
        return validCla
                && apdu[1] == (byte) 0xA4     // INS: SELECT
                && apdu[2] == 0x04;           // P1: by name (AID)
    }

    /**
     * Extracts the AID bytes from a SELECT APDU.
     * Format: CLA INS P1 P2 Lc [AID...] [Le]
     */
    private static byte[] extractAid(byte[] selectApdu) {
        int lc = selectApdu[4] & 0xFF;
        byte[] aid = new byte[lc];
        System.arraycopy(selectApdu, 5, aid, 0, lc);
        return aid;
    }

    private static String bytesToHex(byte[] bytes) {
        return OmapiManager.bytesToHex(bytes);
    }
}
