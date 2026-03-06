package io.github.yukijs.wirelesstntn;

import android.content.ComponentName;
import android.nfc.NfcAdapter;
import android.nfc.cardemulation.CardEmulation;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.util.List;

/**
 * Main (and only) activity for WirelessTNTN.
 *
 * <h3>UI layout</h3>
 * <ul>
 *   <li>Status chip – shows INACTIVE / OBSERVING / ACTIVE.
 *   <li>Spinner – lists available OMAPI SE readers (SIM1, SIM2, eSE1 …).
 *   <li>Toggle button – enables / disables the NFC passthrough.
 *   <li>Log view – monospace, scrollable, displays a live log of all NFC and
 *       OMAPI events (also written to Logcat as tag "WTNTN").
 * </ul>
 *
 * <h3>NFC observe mode (Android 15, API 35)</h3>
 * <ul>
 *   <li><em>Passthrough disabled</em> – {@code setObserveModeEnabled(true)}:
 *       the NFC radio is passive; the device is invisible to readers.  This
 *       prevents accidental/background SE access.
 *   <li><em>Passthrough enabled, app in foreground</em> –
 *       {@code setObserveModeEnabled(false)} + {@code setPreferredService()}:
 *       the device becomes an HCE card and all non-payment AID selections are
 *       routed to {@link PassthroughHceService}.
 *   <li>When the activity pauses (goes to background) the passthrough is
 *       automatically suspended to honour the OS foreground-only restriction.
 * </ul>
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";

    // ── Views ──────────────────────────────────────────────────────────────
    private Spinner mReaderSpinner;
    private Button mToggleButton;
    private Button mClearLogButton;
    private TextView mStatusText;
    private TextView mLogText;
    private ScrollView mLogScroll;

    // ── NFC ────────────────────────────────────────────────────────────────
    private NfcAdapter mNfcAdapter;
    private CardEmulation mCardEmulation;
    private ComponentName mHceComponent;

    // ── OMAPI ──────────────────────────────────────────────────────────────
    private final OmapiManager mOmapi = OmapiManager.getInstance();

    // ── State ──────────────────────────────────────────────────────────────
    /** Whether the user has explicitly requested passthrough to be active. */
    private boolean mUserEnabled = false;
    /** Whether the passthrough is currently live (foreground + user-enabled). */
    private boolean mPassthroughActive = false;

    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    // ── Activity lifecycle ─────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        bindViews();
        initNfc();
        initOmapi();
        setupListeners();
        updateUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Re-assert preferred service whenever we come to the foreground.
        if (mUserEnabled) {
            activatePassthrough();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Suspend passthrough: app is no longer in foreground.
        if (mPassthroughActive) {
            suspendPassthrough();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        AppLog.setListener(null);
        mOmapi.disconnect();
    }

    // ── Initialisation ─────────────────────────────────────────────────────

    private void bindViews() {
        mReaderSpinner  = findViewById(R.id.reader_spinner);
        mToggleButton   = findViewById(R.id.toggle_button);
        mClearLogButton = findViewById(R.id.clear_log_button);
        mStatusText     = findViewById(R.id.status_text);
        mLogText        = findViewById(R.id.log_text);
        mLogScroll      = findViewById(R.id.log_scroll);
    }

    private void initNfc() {
        mNfcAdapter = NfcAdapter.getDefaultAdapter(this);
        if (mNfcAdapter == null) {
            AppLog.log("ERROR: NFC adapter not found");
            showFatal(getString(R.string.nfc_unavailable));
            return;
        }
        mCardEmulation = CardEmulation.getInstance(mNfcAdapter);
        mHceComponent  = new ComponentName(this, PassthroughHceService.class);

        // Start with observe mode ON so the device is invisible until the
        // user explicitly enables the passthrough.
        setObserveMode(true);
    }

    private void initOmapi() {
        AppLog.log(getString(R.string.omapi_connecting));
        mOmapi.setOnConnectedCallback(() -> mMainHandler.post(this::refreshReaderSpinner));
        mOmapi.connect(this);
    }

    private void setupListeners() {
        // Restore buffered entries FIRST, then set listener to avoid duplicates
        // from concurrent background threads posting between the two operations.
        for (String entry : AppLog.getEntries()) {
            mLogText.append(entry + "\n");
        }

        // Live log listener (from here on new entries append directly)
        AppLog.setListener(entry -> mMainHandler.post(() -> {
            mLogText.append(entry + "\n");
            // Auto-scroll to bottom
            mLogScroll.post(() -> mLogScroll.fullScroll(View.FOCUS_DOWN));
        }));

        mToggleButton.setOnClickListener(v -> onToggleClicked());
        mClearLogButton.setOnClickListener(v -> {
            AppLog.clear();
            mLogText.setText("");
        });

        mReaderSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent,
                                       View view, int pos, long id) {
                String name = (String) parent.getItemAtPosition(pos);
                if (!getString(R.string.no_readers_found).equals(name)) {
                    mOmapi.selectReader(name);
                }
            }
            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });
    }

    // ── Reader spinner ─────────────────────────────────────────────────────

    private void refreshReaderSpinner() {
        List<String> readers = mOmapi.getReaderNames();
        if (readers.isEmpty()) {
            readers.add(getString(R.string.no_readers_found));
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, readers);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        mReaderSpinner.setAdapter(adapter);

        // Auto-select the first real reader
        if (!readers.isEmpty()
                && !getString(R.string.no_readers_found).equals(readers.get(0))) {
            mOmapi.selectReader(readers.get(0));
        }
    }

    // ── Toggle logic ───────────────────────────────────────────────────────

    private void onToggleClicked() {
        if (mNfcAdapter == null) return;

        if (!mNfcAdapter.isEnabled()) {
            Toast.makeText(this, R.string.nfc_not_enabled, Toast.LENGTH_SHORT).show();
            return;
        }

        if (mUserEnabled) {
            // User wants to disable
            mUserEnabled = false;
            suspendPassthrough();
            setObserveMode(true);   // go back to invisible
        } else {
            // User wants to enable
            if (!mOmapi.hasSelectedReader()) {
                Toast.makeText(this, R.string.no_reader_selected, Toast.LENGTH_SHORT).show();
                return;
            }
            mUserEnabled = true;
            activatePassthrough();
        }

        updateUi();
    }

    /**
     * Makes the device an active NFC HCE card and sets this service as the
     * preferred handler for all non-payment AIDs.
     */
    private void activatePassthrough() {
        // Disable observe mode → device becomes visible to NFC readers.
        setObserveMode(false);

        // Request NFC routing priority for this service while in foreground.
        boolean ok = mCardEmulation.setPreferredService(this, mHceComponent);
        if (!ok) {
            AppLog.log("WARN: setPreferredService returned false");
        }

        mPassthroughActive = true;
        AppLog.log("Passthrough ACTIVE – SE: " + currentReaderName());
        updateUi();
    }

    /**
     * Suspends the passthrough without changing the user's intent flag.
     * Called when the app goes to the background.
     */
    private void suspendPassthrough() {
        mCardEmulation.unsetPreferredService(this);
        mPassthroughActive = false;
        AppLog.log("Passthrough SUSPENDED (background)");
        updateUi();
    }

    // ── Observe-mode helper ────────────────────────────────────────────────

    /**
     * Enables or disables Android 15 NFC observe mode.
     *
     * <p>When observe mode is <em>enabled</em> the NFC controller monitors
     * the RF field passively and the device is invisible to readers (no HCE
     * responses).  When <em>disabled</em> the device responds normally as an
     * HCE card.</p>
     *
     * <p>If the hardware does not support observe mode this is silently
     * ignored; the passthrough still works, it just cannot suppress RF
     * responses while idle.</p>
     */
    private void setObserveMode(boolean enable) {
        if (mNfcAdapter == null) return;
        if (!NfcAdapter.isObserveModeSupported(this)) {
            if (enable) {
                AppLog.log(getString(R.string.observe_not_supported));
            }
            return;
        }
        mNfcAdapter.setObserveModeEnabled(enable);
        AppLog.log("Observe mode: " + (enable ? "ON" : "OFF"));
    }

    // ── UI update ──────────────────────────────────────────────────────────

    private void updateUi() {
        if (mPassthroughActive) {
            mStatusText.setText(R.string.status_active);
            mStatusText.setTextColor(getColor(R.color.status_active));
            mToggleButton.setText(R.string.disable_passthrough);
        } else if (mUserEnabled) {
            // User-enabled but not active (e.g. paused)
            mStatusText.setText(R.string.status_observe);
            mStatusText.setTextColor(getColor(R.color.status_observe));
            mToggleButton.setText(R.string.disable_passthrough);
        } else {
            mStatusText.setText(R.string.status_inactive);
            mStatusText.setTextColor(getColor(R.color.status_inactive));
            mToggleButton.setText(R.string.enable_passthrough);
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private String currentReaderName() {
        if (mReaderSpinner.getSelectedItem() != null) {
            return mReaderSpinner.getSelectedItem().toString();
        }
        return "(none)";
    }

    private void showFatal(String msg) {
        mToggleButton.setEnabled(false);
        mReaderSpinner.setEnabled(false);
        mStatusText.setText(msg);
        mStatusText.setTextColor(getColor(R.color.status_inactive));
    }
}
