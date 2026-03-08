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
 * AID routing:
 *   AIDs are declared statically in res/xml/apduservice.xml.  No dynamic registration
 *   is needed: Observer Mode (shouldDefaultToObserveMode="true") captures every NFC
 *   polling frame before AID selection occurs, and the service only exits observe mode
 *   once the OMAPI session is ready.  The SE then decides per-AID whether to accept
 *   the transaction; Android's AID routing table is just the mechanism by which the
 *   NFC stack dispatches incoming SELECT commands to our service.
 *
 * @see ref_aosp/NfcNci/testutils/src/com/android/nfc/emulator/BaseEmulatorActivity.java
 */
@SuppressLint("NewApi")
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

    private void appendLog(String msg) {
        logTextView.append(msg + "\n");
        logScrollView.post(() -> logScrollView.fullScroll(ScrollView.FOCUS_DOWN));
    }
}

