package app.aoki.yuki.wirelesstntn;

import android.annotation.SuppressLint;
import android.content.ComponentName;
import android.content.Intent;
import android.nfc.NfcAdapter;
import android.nfc.cardemulation.CardEmulation;
import android.os.Bundle;
import android.se.omapi.Reader;
import android.se.omapi.SEService;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executors;

/**
 * Main activity: SE reader selector, Start/Stop toggle, log viewer.
 *
 * Foreground-only operation:
 *   - setPreferredService() is called in onResume() when a session is active,
 *     making our HCE service the preferred target for NFC routing while this
 *     activity is in the foreground.
 *   - unsetPreferredService() is called in onPause() so the service is no longer
 *     preferred when the app goes to the background.
 *
 * @see ref_aosp/NfcNci/testutils/src/com/android/nfc/emulator/BaseEmulatorActivity.java
 */
@SuppressLint("NewApi")
public class MainActivity extends AppCompatActivity {

    // AID prefix filters registered dynamically when the user presses Start, so the service is
    // never active unintentionally.
    //
    // Android requires prefix AIDs to be at least 5 bytes (10 hex chars) before the trailing '*'.
    // Shorter prefixes such as "A0*" are rejected by android.nfc.cardemulation.AidGroup with
    // IllegalArgumentException, and would also never match in the NFC AID-routing table even
    // if they were syntactically accepted (because '*' sorts before '0'-'F' in the NavigableMap
    // range query used by RegisteredAidCache.resolveAid).
    //
    // The list below covers the major ISO 7816 application families found on Secure Elements:
    //   A0 xx xx xx xx  – ISO-registered application identifiers (payment, transit, identity …)
    //   F0 xx xx xx xx  – Proprietary application identifiers
    //
    // AIDs that do not match any of these prefixes will not be routed to this service.
    private static final List<String> AID_FILTERS = Arrays.asList(
            // --- Payment networks ---
            "A000000003*",  // Visa
            "A000000004*",  // Mastercard / Maestro
            "A000000025*",  // American Express
            "A000000029*",  // American Express (alternate RID)
            "A000000032*",  // Visa Electron
            "A000000045*",  // Maestro (UK Domestic)
            "A000000065*",  // JCB
            "A000000152*",  // Discover / Diners Club
            "A000000277*",  // Interac
            "A000000333*",  // China UnionPay
            "A000000632*",  // eftpos Australia
            "A000000658*",  // MIR (Russia)
            "A000000724*",  // RuPay (India / NPCI)
            "A000000006*",  // Bancontact / Mister Cash
            "A000000042*",  // Carte Bancaire (CB)
            "A000000172*",  // Girocard (Germany)
            // --- Transit / transport ---
            "A000000031*",  // Visa Transit
            "A000000046*",  // Visa Transit (alternate)
            // --- Proprietary F0 range ---
            "F000000000*",  // Proprietary (F0 00 00 00 00)
            "F000000001*",  // Proprietary (F0 00 00 00 01)
            "F000000002*",  // Proprietary (F0 00 00 00 02)
            "F000000003*"   // Proprietary (F0 00 00 00 03)
    );

    private Spinner seSpinner;
    private Button startStopButton;
    private TextView logTextView;
    private ScrollView logScrollView;

    private CardEmulation cardEmulation;
    private boolean sessionActive = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        seSpinner      = findViewById(R.id.se_spinner);
        startStopButton = findViewById(R.id.start_stop_button);
        logTextView    = findViewById(R.id.log_text_view);
        logScrollView  = findViewById(R.id.log_scroll_view);

        NfcAdapter nfcAdapter = NfcAdapter.getDefaultAdapter(this);
        if (nfcAdapter != null) {
            cardEmulation = CardEmulation.getInstance(nfcAdapter);
        }

        startStopButton.setOnClickListener(v -> {
            if (sessionActive) stopSession();
            else               startSession();
        });

        AppLog.setListener(msg -> runOnUiThread(() -> appendLog(msg)));
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Refresh the SE reader list every time the user returns to the app.
        loadSeReaders();

        // If a session is active, re-register as the preferred foreground service so that
        // polling frames and APDUs continue to route to our service.
        if (sessionActive && cardEmulation != null) {
            cardEmulation.setPreferredService(this,
                    new ComponentName(this, PassthroughHceService.class));
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Foreground-only: unregister as preferred service when leaving the foreground.
        // The NFC system will no longer route traffic to our service while backgrounded.
        if (cardEmulation != null) {
            cardEmulation.unsetPreferredService(this);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        AppLog.setListener(null);
    }

    /**
     * Connect to SEService, enumerate available readers, populate the spinner, then
     * immediately shut down the transient SEService (we only needed the names).
     */
    private void loadSeReaders() {
        try {
            SEService[] holder = new SEService[1];
            java.util.concurrent.ExecutorService listExecutor = Executors.newSingleThreadExecutor();
            holder[0] = new SEService(this, listExecutor, () -> {
                Reader[] readers = holder[0].getReaders();
                List<String> names = new ArrayList<>();
                for (Reader r : readers) names.add(r.getName());
                holder[0].shutdown();
                listExecutor.shutdown();
                runOnUiThread(() -> {
                    if (names.isEmpty()) {
                        AppLog.e("No Secure Element readers found on this device");
                        return;
                    }
                    ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                            android.R.layout.simple_spinner_item, names);
                    adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                    seSpinner.setAdapter(adapter);
                    AppLog.i("SE readers available: " + names);
                });
            });
        } catch (Exception e) {
            AppLog.e("Failed to query Secure Element readers: " + e.getMessage());
        }
    }

    private void startSession() {
        if (seSpinner.getCount() == 0) {
            AppLog.e("No Secure Element reader available");
            return;
        }
        String readerName = (String) seSpinner.getSelectedItem();
        AppLog.i("Starting passthrough session, reader=" + readerName);

        // Register AID filters so Android routes NFC frames to our service.
        registerAids();

        // Set as preferred foreground service — this is what makes our service receive
        // polling frames while the activity is visible. Based on AOSP BaseEmulatorActivity.
        if (cardEmulation != null) {
            cardEmulation.setPreferredService(this,
                    new ComponentName(this, PassthroughHceService.class));
        }

        // Start the HCE service (only now, not before the user presses Start).
        Intent intent = new Intent(this, PassthroughHceService.class);
        intent.setAction(PassthroughHceService.ACTION_START);
        intent.putExtra(PassthroughHceService.EXTRA_READER, readerName);
        startService(intent);

        sessionActive = true;
        seSpinner.setEnabled(false);
        startStopButton.setText(R.string.stop);
    }

    private void stopSession() {
        AppLog.i("Stopping passthrough session");

        unregisterAids();

        // Unregister as preferred foreground service.
        if (cardEmulation != null) {
            cardEmulation.unsetPreferredService(this);
        }

        Intent intent = new Intent(this, PassthroughHceService.class);
        intent.setAction(PassthroughHceService.ACTION_STOP);
        startService(intent); // delivers ACTION_STOP via onStartCommand

        sessionActive = false;
        seSpinner.setEnabled(true);
        startStopButton.setText(R.string.start);
    }

    private void registerAids() {
        if (cardEmulation == null) { AppLog.e("NFC adapter not available"); return; }
        ComponentName cn = new ComponentName(this, PassthroughHceService.class);
        boolean ok = cardEmulation.registerAidsForService(cn, CardEmulation.CATEGORY_OTHER, AID_FILTERS);
        AppLog.i("registerAidsForService=" + ok);
    }

    private void unregisterAids() {
        if (cardEmulation == null) return;
        ComponentName cn = new ComponentName(this, PassthroughHceService.class);
        cardEmulation.removeAidsForService(cn, CardEmulation.CATEGORY_OTHER);
    }

    private void appendLog(String msg) {
        logTextView.append(msg + "\n");
        logScrollView.post(() -> logScrollView.fullScroll(ScrollView.FOCUS_DOWN));
    }
}

