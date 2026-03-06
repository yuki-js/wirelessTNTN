package app.aoki.yuki.wirelesstntn;

import android.annotation.SuppressLint;
import android.content.ComponentName;
import android.content.Intent;
import android.nfc.NfcAdapter;
import android.nfc.cardemulation.CardEmulation;
import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.util.Arrays;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private static final List<String> SE_READERS = Arrays.asList("SIM1", "SIM2", "eSE1");
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

        seSpinner = findViewById(R.id.se_spinner);
        startStopButton = findViewById(R.id.start_stop_button);
        logTextView = findViewById(R.id.log_text_view);
        logScrollView = findViewById(R.id.log_scroll_view);

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, SE_READERS);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        seSpinner.setAdapter(adapter);

        startStopButton.setOnClickListener(v -> {
            if (sessionActive) {
                stopSession();
            } else {
                startSession();
            }
        });

        AppLog.setListener(msg -> runOnUiThread(() -> appendLog(msg)));

        // Start service so PassthroughHceService.getInstance() becomes available
        startService(new Intent(this, PassthroughHceService.class));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        AppLog.setListener(null);
    }

    private void startSession() {
        PassthroughHceService svc = PassthroughHceService.getInstance();
        if (svc == null) {
            AppLog.e("MainActivity: HCE service not running; tap NFC or wait a moment");
            return;
        }

        String readerName = (String) seSpinner.getSelectedItem();
        AppLog.i("MainActivity: starting session, reader=" + readerName);

        registerAids();
        svc.startPassthrough(readerName);

        sessionActive = true;
        seSpinner.setEnabled(false);
        startStopButton.setText(R.string.stop);
    }

    private void stopSession() {
        AppLog.i("MainActivity: stopping session");

        unregisterAids();

        PassthroughHceService svc = PassthroughHceService.getInstance();
        if (svc != null) {
            svc.stopPassthrough();
        }

        sessionActive = false;
        seSpinner.setEnabled(true);
        startStopButton.setText(R.string.start);
    }

    @SuppressLint("NewApi")
    private void registerAids() {
        NfcAdapter nfcAdapter = NfcAdapter.getDefaultAdapter(this);
        if (nfcAdapter == null) {
            AppLog.e("MainActivity: NFC not available");
            return;
        }
        CardEmulation cardEmulation = CardEmulation.getInstance(nfcAdapter);
        ComponentName component = new ComponentName(this, PassthroughHceService.class);
        boolean result = cardEmulation.registerAidsForService(component,
                CardEmulation.CATEGORY_OTHER, AID_FILTERS);
        AppLog.i("MainActivity: registerAidsForService result=" + result);
    }

    @SuppressLint("NewApi")
    private void unregisterAids() {
        NfcAdapter nfcAdapter = NfcAdapter.getDefaultAdapter(this);
        if (nfcAdapter == null) return;
        CardEmulation cardEmulation = CardEmulation.getInstance(nfcAdapter);
        ComponentName component = new ComponentName(this, PassthroughHceService.class);
        boolean result = cardEmulation.removeAidsForService(component, CardEmulation.CATEGORY_OTHER);
        AppLog.i("MainActivity: removeAidsForService result=" + result);
    }

    private void appendLog(String msg) {
        logTextView.append(msg + "\n");
        logScrollView.post(() -> logScrollView.fullScroll(ScrollView.FOCUS_DOWN));
    }
}
