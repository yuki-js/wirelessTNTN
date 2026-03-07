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

@SuppressLint("NewApi")
public class MainActivity extends AppCompatActivity {

    // Catch-all AID prefixes; registered dynamically so the service is never active unintentionally.
    private static final List<String> AID_FILTERS = Arrays.asList("A0*", "F0*");

    private Spinner seSpinner;
    private Button startStopButton;
    private TextView logTextView;
    private ScrollView logScrollView;

    private boolean sessionActive = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        seSpinner      = findViewById(R.id.se_spinner);
        startStopButton = findViewById(R.id.start_stop_button);
        logTextView    = findViewById(R.id.log_text_view);
        logScrollView  = findViewById(R.id.log_scroll_view);

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
            // SEService is created here only to list reader names; both it and its executor
            // are shut down inside the callback once the names have been collected.
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

        Intent intent = new Intent(this, PassthroughHceService.class);
        intent.setAction(PassthroughHceService.ACTION_STOP);
        startService(intent); // delivers ACTION_STOP via onStartCommand

        sessionActive = false;
        seSpinner.setEnabled(true);
        startStopButton.setText(R.string.start);
    }

    private void registerAids() {
        NfcAdapter nfcAdapter = NfcAdapter.getDefaultAdapter(this);
        if (nfcAdapter == null) { AppLog.e("NFC adapter not available"); return; }
        CardEmulation ce = CardEmulation.getInstance(nfcAdapter);
        ComponentName cn = new ComponentName(this, PassthroughHceService.class);
        boolean ok = ce.registerAidsForService(cn, CardEmulation.CATEGORY_OTHER, AID_FILTERS);
        AppLog.i("registerAidsForService=" + ok);
    }

    private void unregisterAids() {
        NfcAdapter nfcAdapter = NfcAdapter.getDefaultAdapter(this);
        if (nfcAdapter == null) return;
        CardEmulation ce = CardEmulation.getInstance(nfcAdapter);
        ComponentName cn = new ComponentName(this, PassthroughHceService.class);
        ce.removeAidsForService(cn, CardEmulation.CATEGORY_OTHER);
    }

    private void appendLog(String msg) {
        logTextView.append(msg + "\n");
        logScrollView.post(() -> logScrollView.fullScroll(ScrollView.FOCUS_DOWN));
    }
}

