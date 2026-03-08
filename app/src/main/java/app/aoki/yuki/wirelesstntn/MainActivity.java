package app.aoki.yuki.wirelesstntn;

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
import java.util.List;
import java.util.concurrent.Executors;

/**
 * Main activity: SE reader selector, Start/Stop toggle, log viewer.
 *
 * Requirements
 * ------------
 *   - Root access and LSPosed framework installed.
 *   - This app enabled as an LSPosed module with scope set to com.android.nfc.
 *   - LmrtHijackModule then hooks RegisteredAidCache.resolveAid() inside the
 *     NFC process, redirecting all AID SELECTs to PassthroughHceService.
 *
 * Foreground preferred service
 * ----------------------------
 *   setPreferredService() is called in onResume() when a session is active, so
 *   that standard AID routing (without relying on the Xposed hook) also prefers
 *   our service while this activity is in the foreground.
 *   unsetPreferredService() is called in onPause() to release the preference.
 *
 * No Observer Mode is used. The Xposed hook replaces the role that observe mode
 * served in the previous implementation.
 */
public class MainActivity extends AppCompatActivity {

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

        seSpinner       = findViewById(R.id.se_spinner);
        startStopButton = findViewById(R.id.start_stop_button);
        logTextView     = findViewById(R.id.log_text_view);
        logScrollView   = findViewById(R.id.log_scroll_view);

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
        // Refresh the SE reader list whenever the user returns to the app.
        loadSeReaders();

        // While the activity is in the foreground, make our service the preferred
        // foreground service so standard HCE routing (no hook) also works.
        if (sessionActive && cardEmulation != null) {
            cardEmulation.setPreferredService(this,
                    new ComponentName(this, PassthroughHceService.class));
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Release the preferred-service preference when leaving the foreground.
        if (cardEmulation != null) {
            cardEmulation.unsetPreferredService(this);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        AppLog.setListener(null);
    }

    // -------------------------------------------------------------------------
    // Session helpers
    // -------------------------------------------------------------------------

    /**
     * Queries the SEService for available readers, populates the spinner, then
     * immediately shuts down the transient SEService (we only needed the names).
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

        // Register as preferred foreground service so the standard HCE routing
        // (without the Xposed hook) also prefers our service.
        if (cardEmulation != null) {
            cardEmulation.setPreferredService(this,
                    new ComponentName(this, PassthroughHceService.class));
        }

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

        if (cardEmulation != null) {
            cardEmulation.unsetPreferredService(this);
        }

        Intent intent = new Intent(this, PassthroughHceService.class);
        intent.setAction(PassthroughHceService.ACTION_STOP);
        startService(intent);

        sessionActive = false;
        seSpinner.setEnabled(true);
        startStopButton.setText(R.string.start);
    }

    // -------------------------------------------------------------------------
    // Log display
    // -------------------------------------------------------------------------

    private void appendLog(String msg) {
        logTextView.append(msg + "\n");
        logScrollView.post(() -> logScrollView.fullScroll(ScrollView.FOCUS_DOWN));
    }
}
