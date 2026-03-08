package app.aoki.yuki.wirelesstntn;

import android.content.ComponentName;
import android.content.Intent;
import android.nfc.NfcAdapter;
import android.nfc.cardemulation.CardEmulation;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;
import java.util.List;

/**
 * Main activity: AID list editor, Start/Stop toggle, log viewer.
 *
 * The user enters one or more AIDs (exact or prefix with trailing '*') in the list.
 * When Start is pressed those AIDs are registered dynamically via
 * CardEmulation.registerAidsForService() and the HCE service is started.
 *
 * Foreground-only operation:
 *   - setPreferredService() is called in onResume() when a session is active.
 *   - unsetPreferredService() is called in onPause().
 */
public class MainActivity extends AppCompatActivity {

    private LinearLayout aidListContainer;
    private Button addAidButton;
    private Button startStopButton;
    private TextView logTextView;
    private ScrollView logScrollView;

    private CardEmulation cardEmulation;
    private boolean sessionActive = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        aidListContainer = findViewById(R.id.aid_list_container);
        addAidButton     = findViewById(R.id.add_aid_button);
        startStopButton  = findViewById(R.id.start_stop_button);
        logTextView      = findViewById(R.id.log_text_view);
        logScrollView    = findViewById(R.id.log_scroll_view);

        NfcAdapter nfcAdapter = NfcAdapter.getDefaultAdapter(this);
        if (nfcAdapter != null) {
            cardEmulation = CardEmulation.getInstance(nfcAdapter);
        }

        addAidButton.setOnClickListener(v -> addAidRow(""));
        startStopButton.setOnClickListener(v -> {
            if (sessionActive) stopSession();
            else               startSession();
        });

        AppLog.setListener(msg -> runOnUiThread(() -> appendLog(msg)));

        // Start with one empty AID row.
        addAidRow("");
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (sessionActive && cardEmulation != null) {
            cardEmulation.setPreferredService(this,
                    new ComponentName(this, PassthroughHceService.class));
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
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
     * Add one AID input row (EditText + remove button) to the list container.
     *
     * @param initialValue pre-fill value (empty string for a blank row)
     */
    private void addAidRow(String initialValue) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        EditText editText = new EditText(this);
        LinearLayout.LayoutParams etParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        editText.setLayoutParams(etParams);
        editText.setHint(R.string.aid_hint);
        editText.setSingleLine(true);
        if (!TextUtils.isEmpty(initialValue)) {
            editText.setText(initialValue);
        }
        editText.setEnabled(!sessionActive);

        Button removeButton = new Button(this);
        removeButton.setText("−");
        removeButton.setContentDescription(getString(R.string.remove_aid_button));
        removeButton.setGravity(Gravity.CENTER);
        removeButton.setOnClickListener(v -> {
            // Keep at least one row so the user always has a field to type in.
            if (aidListContainer.getChildCount() > 1) {
                aidListContainer.removeView(row);
            } else {
                editText.setText("");
            }
        });

        row.addView(editText);
        row.addView(removeButton);
        aidListContainer.addView(row);
    }

    /** Collect all non-empty AID strings from the list. */
    private List<String> collectAids() {
        List<String> aids = new ArrayList<>();
        for (int i = 0; i < aidListContainer.getChildCount(); i++) {
            View child = aidListContainer.getChildAt(i);
            if (child instanceof LinearLayout) {
                View first = ((LinearLayout) child).getChildAt(0);
                if (first instanceof EditText) {
                    String text = ((EditText) first).getText().toString().trim();
                    if (!text.isEmpty()) {
                        aids.add(text);
                    }
                }
            }
        }
        return aids;
    }

    private void setAidRowsEnabled(boolean enabled) {
        for (int i = 0; i < aidListContainer.getChildCount(); i++) {
            View child = aidListContainer.getChildAt(i);
            if (child instanceof LinearLayout) {
                View first = ((LinearLayout) child).getChildAt(0);
                if (first instanceof EditText) {
                    first.setEnabled(enabled);
                }
            }
        }
    }

    private void startSession() {
        List<String> aids = collectAids();
        if (aids.isEmpty()) {
            AppLog.e("No AIDs configured — add at least one AID before starting");
            return;
        }

        AppLog.i("Starting HCE session, AIDs=" + aids);

        // Register AIDs dynamically for the HCE service.
        if (cardEmulation != null) {
            ComponentName svc = new ComponentName(this, PassthroughHceService.class);
            boolean ok = cardEmulation.registerAidsForService(svc, CardEmulation.CATEGORY_OTHER, aids);
            AppLog.i("registerAidsForService: " + ok + "  AIDs=" + aids);
            cardEmulation.setPreferredService(this, svc);
        }

        Intent intent = new Intent(this, PassthroughHceService.class);
        intent.setAction(PassthroughHceService.ACTION_START);
        startService(intent);

        sessionActive = true;
        addAidButton.setEnabled(false);
        setAidRowsEnabled(false);
        startStopButton.setText(R.string.stop);
    }

    private void stopSession() {
        AppLog.i("Stopping HCE session");

        if (cardEmulation != null) {
            cardEmulation.unsetPreferredService(this);
        }

        Intent intent = new Intent(this, PassthroughHceService.class);
        intent.setAction(PassthroughHceService.ACTION_STOP);
        startService(intent);

        sessionActive = false;
        addAidButton.setEnabled(true);
        setAidRowsEnabled(true);
        startStopButton.setText(R.string.start);
    }

    private void appendLog(String msg) {
        logTextView.append(msg + "\n");
        logScrollView.post(() -> logScrollView.fullScroll(ScrollView.FOCUS_DOWN));
    }
}
