package com.example.blefinder;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Space;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class MainActivity extends Activity {

    private static final int PERMISSION_SCAN = 41;
    private static final int ENABLE_BT_REQUEST = 42;
    private static final int PERMISSION_ADVERTISE = 43;
    private static final int PERMISSION_DISCOVERABLE = 44;
    private static final int DISCOVERABLE_REQUEST = 45;

    private static final int ACTION_NONE = 0;
    private static final int ACTION_SCAN = 1;
    private static final int ACTION_ADVERTISE = 2;
    private static final int ACTION_DISCOVERABLE = 3;

    private static final long BLE_LOST_AFTER_MS = 5000L;
    private static final long CLASSIC_LOST_AFTER_MS = 18000L;
    private static final long CLASSIC_RESTART_DELAY_MS = 1200L;

    // Private service UUID used only to identify another phone running BLE Finder in "Finder-Signal" mode.
    private static final ParcelUuid FINDER_SERVICE_UUID = new ParcelUuid(
            UUID.fromString("7af2b410-7d64-4df0-9bbf-10ab3b7d1201"));

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<String, DeviceReading> readings = new HashMap<>();
    private final List<DeviceReading> visibleReadings = new ArrayList<>();

    private BluetoothAdapter bluetoothAdapter;
    private BluetoothLeScanner bleScanner;
    private BluetoothLeAdvertiser bleAdvertiser;

    private boolean scanning = false;
    private boolean bleScanRunning = false;
    private boolean classicDiscoveryRunning = false;
    private boolean advertising = false;
    private boolean receiverRegistered = false;
    private int pendingBtAction = ACTION_NONE;

    private ToneGenerator toneGenerator;
    private Vibrator vibrator;
    private boolean soundEnabled = true;
    private boolean vibrationEnabled = true;
    private boolean activityVisible = false;

    private LinearLayout root;
    private TextView statusText;
    private Button scanButton;
    private Button advertiseButton;
    private EditText filterEdit;
    private ListView listView;
    private DeviceAdapter adapter;

    private DeviceReading selected;
    private FinderGauge finderGauge;
    private TextView finderName;
    private TextView finderRssi;
    private TextView finderQuality;
    private TextView finderHint;
    private TextView finderAge;

    private final Runnable staleTicker = new Runnable() {
        @Override public void run() {
            updateFinderUi();
            handler.postDelayed(this, 500L);
        }
    };

    private final Runnable feedbackTicker = new Runnable() {
        @Override public void run() {
            long nextDelay = 400L;
            if (activityVisible && scanning && selected != null && finderGauge != null) {
                DeviceReading current = readings.get(selected.address);
                if (current != null) selected = current;
                long age = System.currentTimeMillis() - selected.lastSeenMs;
                if (selected.hasSample && age <= selected.lostAfterMs() && (soundEnabled || vibrationEnabled)) {
                    int rssi = (int) Math.round(selected.smoothedRssi);
                    playFinderPulse(rssi);
                    nextDelay = feedbackIntervalMs(rssi);
                }
            }
            handler.postDelayed(this, nextDelay);
        }
    };

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override public void onScanResult(int callbackType, ScanResult result) {
            handleBleResult(result);
        }

        @Override public void onBatchScanResults(List<ScanResult> results) {
            for (ScanResult result : results) handleBleResult(result);
        }

        @Override public void onScanFailed(int errorCode) {
            bleScanRunning = false;
            statusTextSafe("BLE-Scan-Fehler: " + scanErrorName(errorCode)
                    + ". Classic-Suche laeuft ggf. weiter.");
            updateScanButton();
        }
    };

    private final AdvertiseCallback advertiseCallback = new AdvertiseCallback() {
        @Override public void onStartSuccess(AdvertiseSettings settingsInEffect) {
            advertising = true;
            updateAdvertiseButton();
            Toast.makeText(MainActivity.this,
                    "Finder-Signal aktiv. Ein zweites Handy mit BLE Finder kann dieses Handy jetzt leichter finden.",
                    Toast.LENGTH_LONG).show();
        }

        @Override public void onStartFailure(int errorCode) {
            advertising = false;
            updateAdvertiseButton();
            Toast.makeText(MainActivity.this,
                    "Finder-Signal konnte nicht gestartet werden (Fehler " + errorCode + ").",
                    Toast.LENGTH_LONG).show();
        }
    };

    private final BroadcastReceiver bluetoothReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                BluetoothDevice device;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class);
                } else {
                    device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                }
                short rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE);
                handleClassicResult(device, rssi);
            } else if (BluetoothAdapter.ACTION_DISCOVERY_STARTED.equals(action)) {
                classicDiscoveryRunning = true;
                refreshList();
            } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
                classicDiscoveryRunning = false;
                if (scanning) {
                    handler.postDelayed(() -> {
                        if (scanning) startClassicDiscovery();
                    }, CLASSIC_RESTART_DELAY_MS);
                }
            }
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        BluetoothManager manager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        bluetoothAdapter = manager == null ? null : manager.getAdapter();

        initializeFinderFeedback();
        registerBluetoothReceiver();
        buildScanScreen();
        handler.post(staleTicker);
        handler.post(feedbackTicker);

        if (bluetoothAdapter == null) {
            statusText.setText("Dieses Geraet unterstuetzt kein Bluetooth.");
            scanButton.setEnabled(false);
            if (advertiseButton != null) advertiseButton.setEnabled(false);
        }
    }

    @Override protected void onResume() {
        super.onResume();
        activityVisible = true;
    }

    @Override protected void onPause() {
        activityVisible = false;
        stopFeedbackNow();
        super.onPause();
    }

    @Override protected void onDestroy() {
        stopScan();
        stopAdvertising();
        stopFeedbackNow();
        if (receiverRegistered) {
            try { unregisterReceiver(bluetoothReceiver); } catch (IllegalArgumentException ignored) { }
            receiverRegistered = false;
        }
        if (toneGenerator != null) {
            toneGenerator.release();
            toneGenerator = null;
        }
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override public void onBackPressed() {
        if (selected != null) {
            selected = null;
            buildScanScreen();
            refreshList();
        } else {
            super.onBackPressed();
        }
    }

    private void registerBluetoothReceiver() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothDevice.ACTION_FOUND);
        filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED);
        filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(bluetoothReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(bluetoothReceiver, filter);
        }
        receiverRegistered = true;
    }

    private void buildScanScreen() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(14), dp(18), dp(14));
        root.setBackgroundColor(Color.rgb(245, 247, 250));
        setContentView(root);

        TextView title = text("Bluetooth Finder", 28, true, Color.rgb(23, 32, 42));
        root.addView(title, lpMatchWrap());

        TextView subtitle = text(
                "Sucht gleichzeitig nach BLE- und sichtbaren Bluetooth-Classic-Geraeten und zeigt die Signalstaerke an.",
                15, false, Color.rgb(96, 112, 128));
        subtitle.setPadding(0, dp(3), 0, dp(10));
        root.addView(subtitle, lpMatchWrap());

        TextView phoneHelp = text(
                "Handy finden: Am sichersten BLE Finder auch auf dem Ziel-Handy oeffnen und dort 'Finder-Signal senden' aktivieren. "
                        + "Ein beliebiges Handy ist sonst nicht automatisch per Bluetooth sichtbar.",
                13, false, Color.rgb(75, 85, 95));
        phoneHelp.setPadding(0, 0, 0, dp(10));
        root.addView(phoneHelp, lpMatchWrap());

        statusText = text("Bereit", 14, false, Color.rgb(75, 85, 95));
        root.addView(statusText, lpMatchWrap());

        scanButton = new Button(this);
        scanButton.setText("Suche starten");
        scanButton.setAllCaps(false);
        scanButton.setTextSize(16);
        scanButton.setOnClickListener(v -> {
            if (scanning) stopScan(); else ensureReadyAndScan();
        });
        LinearLayout.LayoutParams scanLp = lpMatchWrap();
        scanLp.topMargin = dp(9);
        root.addView(scanButton, scanLp);

        advertiseButton = new Button(this);
        advertiseButton.setAllCaps(false);
        advertiseButton.setTextSize(15);
        advertiseButton.setOnClickListener(v -> ensureReadyAndToggleAdvertising());
        LinearLayout.LayoutParams advLp = lpMatchWrap();
        advLp.topMargin = dp(6);
        root.addView(advertiseButton, advLp);
        updateAdvertiseButton();

        Button discoverableButton = new Button(this);
        discoverableButton.setAllCaps(false);
        discoverableButton.setText("Dieses Handy 5 Min. Classic-sichtbar machen");
        discoverableButton.setTextSize(14);
        discoverableButton.setOnClickListener(v -> ensureReadyAndRequestDiscoverable());
        LinearLayout.LayoutParams discLp = lpMatchWrap();
        discLp.topMargin = dp(4);
        root.addView(discoverableButton, discLp);

        filterEdit = new EditText(this);
        filterEdit.setHint("Geraetename, Adresse oder Typ filtern");
        filterEdit.setSingleLine(true);
        filterEdit.setTextSize(15);
        LinearLayout.LayoutParams filterLp = lpMatchWrap();
        filterLp.topMargin = dp(8);
        root.addView(filterEdit, filterLp);
        filterEdit.setOnEditorActionListener((v, actionId, event) -> {
            refreshList();
            return false;
        });
        filterEdit.addTextChangedListener(new SimpleTextWatcher(this::refreshList));

        TextView help = text(
                "Tipp: BLE liefert meist haeufigere RSSI-Werte. Classic-Geraete werden nur waehrend ihrer Sichtbarkeit gefunden; "
                        + "deren Messwert aktualisiert sich langsamer.",
                13, false, Color.rgb(96, 112, 128));
        help.setPadding(0, dp(8), 0, dp(8));
        root.addView(help, lpMatchWrap());

        listView = new ListView(this);
        listView.setDividerHeight(dp(1));
        adapter = new DeviceAdapter();
        listView.setAdapter(adapter);
        listView.setOnItemClickListener((parent, view, position, id) -> {
            DeviceReading item = visibleReadings.get(position);
            selected = item;
            buildFinderScreen();
        });
        root.addView(listView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        updateScanButton();
    }

    private void buildFinderScreen() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(18), dp(14), dp(18), dp(18));
        root.setBackgroundColor(Color.rgb(245, 247, 250));
        setContentView(root);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        Button back = new Button(this);
        back.setText("Zurueck");
        back.setAllCaps(false);
        back.setOnClickListener(v -> onBackPressed());
        top.addView(back, new LinearLayout.LayoutParams(dp(105), ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView title = text("Geraet finden", 24, true, Color.rgb(23, 32, 42));
        title.setGravity(Gravity.CENTER);
        top.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Space spacer = new Space(this);
        top.addView(spacer, new LinearLayout.LayoutParams(dp(105), 1));
        root.addView(top, lpMatchWrap());

        finderName = text("", 18, true, Color.rgb(35, 45, 55));
        finderName.setGravity(Gravity.CENTER);
        finderName.setPadding(0, dp(12), 0, dp(2));
        root.addView(finderName, lpMatchWrap());

        TextView address = text(selected == null ? "" : selected.address + "  |  " + selected.typeLabel(),
                13, false, Color.rgb(96, 112, 128));
        address.setGravity(Gravity.CENTER);
        root.addView(address, lpMatchWrap());

        finderGauge = new FinderGauge(this);
        LinearLayout.LayoutParams gaugeLp = new LinearLayout.LayoutParams(dp(300), dp(300));
        gaugeLp.topMargin = dp(14);
        root.addView(finderGauge, gaugeLp);

        finderRssi = text("-- dBm", 30, true, Color.rgb(23, 32, 42));
        finderRssi.setGravity(Gravity.CENTER);
        root.addView(finderRssi, lpMatchWrap());

        finderQuality = text("Warte auf Signal ...", 22, true, Color.rgb(23, 32, 42));
        finderQuality.setGravity(Gravity.CENTER);
        finderQuality.setPadding(0, dp(5), 0, 0);
        root.addView(finderQuality, lpMatchWrap());

        finderHint = text("Je gruener und voller die Anzeige, desto staerker ist das Signal.",
                15, false, Color.rgb(96, 112, 128));
        finderHint.setGravity(Gravity.CENTER);
        finderHint.setPadding(dp(8), dp(8), dp(8), 0);
        root.addView(finderHint, lpMatchWrap());

        finderAge = text("", 13, false, Color.rgb(96, 112, 128));
        finderAge.setGravity(Gravity.CENTER);
        finderAge.setPadding(0, dp(10), 0, 0);
        root.addView(finderAge, lpMatchWrap());

        TextView feedbackHelp = text(
                "Metalldetektor: Je staerker das Signal, desto schneller folgen Ton und Vibrationsimpulse.",
                13, false, Color.rgb(96, 112, 128));
        feedbackHelp.setGravity(Gravity.CENTER);
        feedbackHelp.setPadding(dp(8), dp(12), dp(8), dp(4));
        root.addView(feedbackHelp, lpMatchWrap());

        LinearLayout feedbackControls = new LinearLayout(this);
        feedbackControls.setOrientation(LinearLayout.HORIZONTAL);
        feedbackControls.setGravity(Gravity.CENTER);

        CheckBox soundToggle = new CheckBox(this);
        soundToggle.setText("Ton");
        soundToggle.setChecked(soundEnabled);
        soundToggle.setOnCheckedChangeListener((buttonView, isChecked) -> {
            soundEnabled = isChecked;
            if (!isChecked && toneGenerator != null) toneGenerator.stopTone();
        });
        feedbackControls.addView(soundToggle);

        CheckBox vibrationToggle = new CheckBox(this);
        vibrationToggle.setText("Vibration");
        vibrationToggle.setChecked(vibrationEnabled);
        vibrationToggle.setOnCheckedChangeListener((buttonView, isChecked) -> {
            vibrationEnabled = isChecked;
            if (!isChecked && vibrator != null) vibrator.cancel();
        });
        LinearLayout.LayoutParams vibrationLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        vibrationLp.leftMargin = dp(18);
        feedbackControls.addView(vibrationToggle, vibrationLp);
        root.addView(feedbackControls, lpMatchWrap());

        Button scanState = new Button(this);
        scanState.setAllCaps(false);
        scanState.setText(scanning ? "Suche laeuft - stoppen" : "Suche starten");
        scanState.setOnClickListener(v -> {
            if (scanning) {
                stopScan();
                scanState.setText("Suche starten");
            } else {
                ensureReadyAndScan();
                scanState.setText("Suche laeuft - stoppen");
            }
        });
        LinearLayout.LayoutParams stateLp = lpMatchWrap();
        stateLp.topMargin = dp(16);
        root.addView(scanState, stateLp);

        updateFinderUi();
    }

    private void ensureReadyAndScan() {
        if (bluetoothAdapter == null) return;
        if (!hasScanPermissions()) {
            requestScanPermissions();
            return;
        }
        if (!isBluetoothEnabledSafe()) {
            requestEnableBluetooth(ACTION_SCAN);
            return;
        }
        startScan();
    }

    private void ensureReadyAndToggleAdvertising() {
        if (advertising) {
            stopAdvertising();
            return;
        }
        if (bluetoothAdapter == null) return;
        if (!hasAdvertisePermissions()) {
            requestAdvertisePermissions(PERMISSION_ADVERTISE);
            return;
        }
        if (!isBluetoothEnabledSafe()) {
            requestEnableBluetooth(ACTION_ADVERTISE);
            return;
        }
        startAdvertising();
    }

    private void ensureReadyAndRequestDiscoverable() {
        if (bluetoothAdapter == null) return;
        if (!hasAdvertisePermissions()) {
            requestAdvertisePermissions(PERMISSION_DISCOVERABLE);
            return;
        }
        if (!isBluetoothEnabledSafe()) {
            requestEnableBluetooth(ACTION_DISCOVERABLE);
            return;
        }
        requestClassicDiscoverable();
    }

    private boolean hasScanPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
                    && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
                    && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        }
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasAdvertisePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED
                    && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    private void requestScanPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requestPermissions(new String[]{
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.ACCESS_FINE_LOCATION
            }, PERMISSION_SCAN);
        } else {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, PERMISSION_SCAN);
        }
    }

    private void requestAdvertisePermissions(int requestCode) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requestPermissions(new String[]{
                    Manifest.permission.BLUETOOTH_ADVERTISE,
                    Manifest.permission.BLUETOOTH_CONNECT
            }, requestCode);
        } else if (requestCode == PERMISSION_DISCOVERABLE) {
            requestClassicDiscoverable();
        } else {
            startAdvertising();
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_SCAN) {
            if (hasScanPermissions()) {
                ensureReadyAndScan();
            } else {
                Toast.makeText(this,
                        "Fuer die Naeherungssuche werden 'Geraete in der Naehe' und Standort benoetigt.",
                        Toast.LENGTH_LONG).show();
            }
        } else if (requestCode == PERMISSION_ADVERTISE) {
            if (hasAdvertisePermissions()) ensureReadyAndToggleAdvertising();
            else Toast.makeText(this, "Bluetooth-Sichtbarkeit wurde nicht erlaubt.", Toast.LENGTH_LONG).show();
        } else if (requestCode == PERMISSION_DISCOVERABLE) {
            if (hasAdvertisePermissions()) ensureReadyAndRequestDiscoverable();
            else Toast.makeText(this, "Bluetooth-Sichtbarkeit wurde nicht erlaubt.", Toast.LENGTH_LONG).show();
        }
    }

    private boolean isBluetoothEnabledSafe() {
        try {
            return bluetoothAdapter != null && bluetoothAdapter.isEnabled();
        } catch (SecurityException e) {
            return false;
        }
    }

    private void requestEnableBluetooth(int action) {
        pendingBtAction = action;
        try {
            startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), ENABLE_BT_REQUEST);
        } catch (Exception e) {
            Toast.makeText(this, "Bitte Bluetooth in den Systemeinstellungen einschalten.", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == ENABLE_BT_REQUEST && isBluetoothEnabledSafe()) {
            int action = pendingBtAction;
            pendingBtAction = ACTION_NONE;
            if (action == ACTION_SCAN) startScan();
            else if (action == ACTION_ADVERTISE) startAdvertising();
            else if (action == ACTION_DISCOVERABLE) requestClassicDiscoverable();
        }
    }

    private void startScan() {
        if (scanning || bluetoothAdapter == null || !hasScanPermissions()) return;
        scanning = true;
        addBondedDevices();
        startBleScan();
        startClassicDiscovery();

        if (!bleScanRunning && !classicDiscoveryRunning) {
            scanning = false;
            statusTextSafe("Suche konnte nicht gestartet werden.");
        } else {
            statusTextSafe("Suche laeuft (" + activeScanModesText() + ") ...");
        }
        updateScanButton();
        refreshList();
    }

    private void startBleScan() {
        if (!scanning || bluetoothAdapter == null || !hasScanPermissions()) return;
        if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) return;
        try {
            bleScanner = bluetoothAdapter.getBluetoothLeScanner();
            if (bleScanner == null) return;
            ScanSettings settings = new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .setReportDelay(0)
                    .build();
            bleScanner.startScan(null, settings, scanCallback);
            bleScanRunning = true;
        } catch (SecurityException e) {
            requestScanPermissions();
        } catch (RuntimeException e) {
            bleScanRunning = false;
        }
    }

    private void startClassicDiscovery() {
        if (!scanning || bluetoothAdapter == null || !hasScanPermissions()) return;
        try {
            if (bluetoothAdapter.isDiscovering()) {
                classicDiscoveryRunning = true;
                return;
            }
            classicDiscoveryRunning = bluetoothAdapter.startDiscovery();
        } catch (SecurityException e) {
            requestScanPermissions();
        } catch (RuntimeException e) {
            classicDiscoveryRunning = false;
        }
    }

    private void stopScan() {
        if (!scanning && !bleScanRunning && !classicDiscoveryRunning) return;
        scanning = false;
        try {
            if (bleScanner != null && bleScanRunning && hasScanPermissions()) {
                bleScanner.stopScan(scanCallback);
            }
        } catch (SecurityException ignored) { }
        bleScanRunning = false;

        try {
            if (bluetoothAdapter != null && hasScanPermissions() && bluetoothAdapter.isDiscovering()) {
                bluetoothAdapter.cancelDiscovery();
            }
        } catch (SecurityException ignored) { }
        classicDiscoveryRunning = false;
        stopFeedbackNow();
        statusTextSafe("Suche gestoppt");
        updateScanButton();
    }

    private void addBondedDevices() {
        if (bluetoothAdapter == null) return;
        try {
            Set<BluetoothDevice> bonded = bluetoothAdapter.getBondedDevices();
            if (bonded == null) return;
            for (BluetoothDevice device : bonded) {
                String address = safeAddress(device, "Gekoppelt-");
                String name = safeDeviceName(device);
                if (name == null || name.trim().isEmpty()) name = "Gekoppeltes Geraet";
                DeviceReading item = getOrCreate(address, name);
                item.paired = true;
                if (!item.hasSample) item.lastSignalSource = "Gekoppelt";
            }
        } catch (SecurityException ignored) { }
    }

    private void handleBleResult(ScanResult result) {
        if (result == null) return;
        BluetoothDevice device = result.getDevice();
        String address = safeAddress(device, "BLE-");
        String name = null;
        boolean finderBeacon = false;
        ScanRecord record = result.getScanRecord();
        if (record != null) {
            name = record.getDeviceName();
            List<ParcelUuid> uuids = record.getServiceUuids();
            finderBeacon = uuids != null && uuids.contains(FINDER_SERVICE_UUID);
        }
        if ((name == null || name.trim().isEmpty()) && hasScanPermissions()) name = safeDeviceName(device);
        if (finderBeacon) name = "BLE Finder-Handy";
        if (name == null || name.trim().isEmpty()) name = "Unbenanntes BLE-Geraet";

        DeviceReading item = getOrCreate(address, name);
        if (!name.startsWith("Unbenanntes")) item.name = name;
        item.seenBle = true;
        item.finderBeacon = item.finderBeacon || finderBeacon;
        item.addRssi(result.getRssi(), "BLE");
        onReadingUpdated(item);
    }

    private void handleClassicResult(BluetoothDevice device, short rssi) {
        if (device == null) return;
        String address = safeAddress(device, "Classic-");
        String name = safeDeviceName(device);
        if (name == null || name.trim().isEmpty()) name = "Unbenanntes Classic-Geraet";
        DeviceReading item = getOrCreate(address, name);
        if (!name.startsWith("Unbenanntes")) item.name = name;
        item.seenClassic = true;
        if (rssi != Short.MIN_VALUE) item.addRssi(rssi, "Classic");
        onReadingUpdated(item);
    }

    private DeviceReading getOrCreate(String address, String name) {
        DeviceReading item = readings.get(address);
        if (item == null) {
            item = new DeviceReading(address, name);
            readings.put(address, item);
        }
        return item;
    }

    private String safeAddress(BluetoothDevice device, String fallbackPrefix) {
        if (device == null) return fallbackPrefix + "unbekannt";
        try {
            String address = device.getAddress();
            return address == null ? fallbackPrefix + Integer.toHexString(device.hashCode()) : address;
        } catch (SecurityException e) {
            return fallbackPrefix + Integer.toHexString(device.hashCode());
        }
    }

    private String safeDeviceName(BluetoothDevice device) {
        if (device == null) return null;
        try {
            return device.getName();
        } catch (SecurityException e) {
            return null;
        }
    }

    private void onReadingUpdated(DeviceReading item) {
        if (selected != null && selected.address.equals(item.address)) selected = item;
        refreshList();
        updateFinderUi();
    }

    private void refreshList() {
        if (adapter == null) return;
        String query = filterEdit == null ? "" : filterEdit.getText().toString().trim().toLowerCase(Locale.ROOT);
        visibleReadings.clear();
        for (DeviceReading reading : readings.values()) {
            String type = reading.typeLabel().toLowerCase(Locale.ROOT);
            if (query.isEmpty()
                    || reading.name.toLowerCase(Locale.ROOT).contains(query)
                    || reading.address.toLowerCase(Locale.ROOT).contains(query)
                    || type.contains(query)) {
                visibleReadings.add(reading);
            }
        }
        Collections.sort(visibleReadings, (a, b) -> {
            if (a.hasSample != b.hasSample) return a.hasSample ? -1 : 1;
            return Double.compare(b.smoothedRssi, a.smoothedRssi);
        });
        adapter.notifyDataSetChanged();
        if (scanning) {
            statusTextSafe("Suche laeuft (" + activeScanModesText() + ") ... "
                    + visibleReadings.size() + " Geraet(e) in der Liste");
        } else {
            statusTextSafe(visibleReadings.size() + " Geraet(e) in der Liste");
        }
    }

    private String activeScanModesText() {
        if (bleScanRunning && classicDiscoveryRunning) return "BLE + Classic";
        if (bleScanRunning) return "BLE";
        if (classicDiscoveryRunning) return "Classic";
        return "warte";
    }

    private void updateFinderUi() {
        if (selected == null || finderGauge == null) return;
        DeviceReading current = readings.get(selected.address);
        if (current != null) selected = current;
        finderName.setText(selected.name);

        if (!selected.hasSample) {
            stopFeedbackNow();
            finderGauge.setRssi(-100, false);
            finderRssi.setText("-- dBm");
            finderQuality.setText("Noch kein aktuelles Signal");
            finderHint.setText(selected.paired
                    ? "Das Geraet ist gekoppelt, sendet aber momentan kein auffindbares Signal. Mache es sichtbar oder aktiviere dort BLE-Werbung."
                    : "Warte auf ein neues Bluetooth-Signal.");
            finderAge.setText(selected.typeLabel());
            return;
        }

        long age = System.currentTimeMillis() - selected.lastSeenMs;
        boolean lost = age > selected.lostAfterMs();
        if (lost) {
            stopFeedbackNow();
            finderGauge.setRssi(-100, false);
            finderRssi.setText("-- dBm");
            finderQuality.setText("Signal verloren");
            finderHint.setText("Gehe etwas zurueck, aendere die Richtung oder stelle sicher, dass das Zielgeraet weiter sichtbar sendet.");
            finderAge.setText("Letztes " + selected.lastSignalSource + "-Signal vor " + (age / 1000) + " s");
        } else {
            int rounded = (int) Math.round(selected.smoothedRssi);
            finderGauge.setRssi(rounded, true);
            finderRssi.setText(rounded + " dBm");
            finderQuality.setText(qualityText(rounded));
            finderHint.setText(proximityHint(rounded));
            finderAge.setText("Quelle: " + selected.lastSignalSource + " | vor "
                    + Math.max(0, age / 1000) + " s | Rohwert " + selected.lastRssi + " dBm");
        }
    }

    private void startAdvertising() {
        if (bluetoothAdapter == null || advertising || !hasAdvertisePermissions()) return;
        try {
            if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)
                    || !bluetoothAdapter.isMultipleAdvertisementSupported()) {
                Toast.makeText(this,
                        "Dieses Handy unterstuetzt kein BLE-Senden im Peripheral-Modus. Nutze stattdessen die Classic-Sichtbarkeit.",
                        Toast.LENGTH_LONG).show();
                return;
            }
            bleAdvertiser = bluetoothAdapter.getBluetoothLeAdvertiser();
            if (bleAdvertiser == null) {
                Toast.makeText(this, "BLE-Sender ist auf diesem Handy nicht verfuegbar.", Toast.LENGTH_LONG).show();
                return;
            }
            AdvertiseSettings settings = new AdvertiseSettings.Builder()
                    .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                    .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                    .setConnectable(false)
                    .setTimeout(0)
                    .build();
            AdvertiseData data = new AdvertiseData.Builder()
                    .setIncludeDeviceName(false)
                    .addServiceUuid(FINDER_SERVICE_UUID)
                    .build();
            bleAdvertiser.startAdvertising(settings, data, advertiseCallback);
        } catch (SecurityException e) {
            requestAdvertisePermissions(PERMISSION_ADVERTISE);
        } catch (RuntimeException e) {
            Toast.makeText(this, "Finder-Signal konnte nicht gestartet werden.", Toast.LENGTH_LONG).show();
        }
    }

    private void stopAdvertising() {
        try {
            if (bleAdvertiser != null && advertising && hasAdvertisePermissions()) {
                bleAdvertiser.stopAdvertising(advertiseCallback);
            }
        } catch (SecurityException ignored) { }
        advertising = false;
        updateAdvertiseButton();
    }

    private void updateAdvertiseButton() {
        if (advertiseButton != null) {
            advertiseButton.setText(advertising
                    ? "Finder-Signal senden: EIN - stoppen"
                    : "Finder-Signal von diesem Handy senden");
        }
    }

    private void requestClassicDiscoverable() {
        try {
            Intent discoverableIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
            discoverableIntent.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300);
            startActivityForResult(discoverableIntent, DISCOVERABLE_REQUEST);
        } catch (SecurityException e) {
            requestAdvertisePermissions(PERMISSION_DISCOVERABLE);
        } catch (Exception e) {
            Toast.makeText(this, "Classic-Sichtbarkeit konnte nicht angefordert werden.", Toast.LENGTH_LONG).show();
        }
    }

    private void initializeFinderFeedback() {
        try {
            toneGenerator = new ToneGenerator(AudioManager.STREAM_MUSIC, 70);
        } catch (RuntimeException ignored) {
            toneGenerator = null;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            VibratorManager vibratorManager = (VibratorManager) getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
            vibrator = vibratorManager == null ? null : vibratorManager.getDefaultVibrator();
        } else {
            vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        }
    }

    private void playFinderPulse(int rssi) {
        float normalized = Math.max(0f, Math.min(1f, (rssi + 95f) / 45f));
        int durationMs = Math.round(35f + 35f * normalized);
        if (soundEnabled && toneGenerator != null) {
            toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP, Math.max(35, durationMs));
        }
        if (vibrationEnabled && vibrator != null && vibrator.hasVibrator()) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    int amplitude = VibrationEffect.DEFAULT_AMPLITUDE;
                    if (vibrator.hasAmplitudeControl()) {
                        amplitude = Math.max(55, Math.min(255, Math.round(55f + 200f * normalized)));
                    }
                    vibrator.vibrate(VibrationEffect.createOneShot(durationMs, amplitude));
                } else {
                    vibrator.vibrate(durationMs);
                }
            } catch (SecurityException ignored) { }
        }
    }

    private long feedbackIntervalMs(int rssi) {
        float normalized = Math.max(0f, Math.min(1f, (rssi + 95f) / 45f));
        return Math.round(1150f - 1000f * normalized);
    }

    private void stopFeedbackNow() {
        if (toneGenerator != null) toneGenerator.stopTone();
        if (vibrator != null) {
            try { vibrator.cancel(); } catch (SecurityException ignored) { }
        }
    }

    private void updateScanButton() {
        if (scanButton != null) scanButton.setText(scanning ? "Suche stoppen" : "Suche starten");
    }

    private void statusTextSafe(String text) {
        if (statusText != null) statusText.setText(text);
    }

    private String qualityText(int rssi) {
        if (rssi >= -55) return "Sehr nah";
        if (rssi >= -65) return "Nah";
        if (rssi >= -75) return "Mittel";
        if (rssi >= -85) return "Schwach";
        return "Sehr schwach";
    }

    private String proximityHint(int rssi) {
        if (rssi >= -55) return "Sehr starkes Signal - das Geraet duerfte in unmittelbarer Naehe sein.";
        if (rssi >= -65) return "Starkes Signal - bewege dich langsam weiter in diese Richtung.";
        if (rssi >= -75) return "Mittleres Signal - probiere verschiedene Richtungen aus.";
        if (rssi >= -85) return "Schwaches Signal - moeglicherweise weiter entfernt oder hinter einem Hindernis.";
        return "Sehr schwach - groessere Entfernung oder starke Abschirmung wahrscheinlich.";
    }

    private String scanErrorName(int code) {
        switch (code) {
            case ScanCallback.SCAN_FAILED_ALREADY_STARTED: return "bereits gestartet";
            case ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED: return "Registrierung fehlgeschlagen";
            case ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED: return "nicht unterstuetzt";
            case ScanCallback.SCAN_FAILED_INTERNAL_ERROR: return "interner Fehler";
            default: return "Code " + code;
        }
    }

    private TextView text(String value, int sp, boolean bold, int color) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }

    private LinearLayout.LayoutParams lpMatchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static int signalColor(int rssi) {
        float normalized = Math.max(0f, Math.min(1f, (rssi + 100f) / 50f));
        float hue = 120f * normalized;
        return Color.HSVToColor(new float[]{hue, 0.88f, 0.90f});
    }

    private static class DeviceReading {
        final String address;
        String name;
        int lastRssi = -100;
        double smoothedRssi = -100.0;
        boolean hasSample = false;
        long lastSeenMs = 0;
        boolean seenBle = false;
        boolean seenClassic = false;
        boolean paired = false;
        boolean finderBeacon = false;
        String lastSignalSource = "";

        DeviceReading(String address, String name) {
            this.address = address;
            this.name = name;
        }

        void addRssi(int rssi, String source) {
            lastRssi = rssi;
            if (!hasSample) {
                smoothedRssi = rssi;
                hasSample = true;
            } else {
                double alpha = "Classic".equals(source) ? 0.40 : 0.28;
                smoothedRssi = alpha * rssi + (1.0 - alpha) * smoothedRssi;
            }
            lastSignalSource = source;
            lastSeenMs = System.currentTimeMillis();
        }

        long lostAfterMs() {
            return "Classic".equals(lastSignalSource) ? CLASSIC_LOST_AFTER_MS : BLE_LOST_AFTER_MS;
        }

        String typeLabel() {
            if (finderBeacon) return "BLE Finder-Handy";
            if (seenBle && seenClassic) return paired ? "BLE + Classic | gekoppelt" : "BLE + Classic";
            if (seenBle) return paired ? "BLE | gekoppelt" : "BLE";
            if (seenClassic) return paired ? "Classic | gekoppelt" : "Classic";
            return paired ? "Gekoppelt | kein Signal" : "Unbekannt";
        }
    }

    private class DeviceAdapter extends BaseAdapter {
        @Override public int getCount() { return visibleReadings.size(); }
        @Override public Object getItem(int position) { return visibleReadings.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout row;
            if (convertView instanceof LinearLayout) {
                row = (LinearLayout) convertView;
            } else {
                row = new LinearLayout(MainActivity.this);
                row.setOrientation(LinearLayout.VERTICAL);
                row.setPadding(dp(12), dp(10), dp(12), dp(10));
                TextView n = text("", 17, true, Color.rgb(23, 32, 42));
                n.setTag("name");
                row.addView(n, lpMatchWrap());
                TextView d = text("", 13, false, Color.rgb(96, 112, 128));
                d.setTag("detail");
                row.addView(d, lpMatchWrap());
            }

            DeviceReading item = visibleReadings.get(position);
            TextView n = row.findViewWithTag("name");
            TextView d = row.findViewWithTag("detail");
            n.setText(item.name);
            if (item.hasSample) {
                int rssi = (int) Math.round(item.smoothedRssi);
                n.setTextColor(signalColor(rssi));
                d.setText(item.typeLabel() + "  |  " + item.address + "  |  " + rssi + " dBm  |  " + qualityText(rssi));
            } else {
                n.setTextColor(Color.rgb(90, 100, 110));
                d.setText(item.typeLabel() + "  |  " + item.address + "  |  kein aktueller RSSI");
            }
            return row;
        }
    }

    private static class FinderGauge extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int rssi = -100;
        private boolean active = false;

        FinderGauge(Context context) {
            super(context);
            paint.setStrokeCap(Paint.Cap.ROUND);
            textPaint.setTextAlign(Paint.Align.CENTER);
            textPaint.setTypeface(Typeface.DEFAULT_BOLD);
        }

        void setRssi(int rssi, boolean active) {
            this.rssi = rssi;
            this.active = active;
            invalidate();
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float w = getWidth();
            float h = getHeight();
            float cx = w / 2f;
            float cy = h / 2f;
            float stroke = Math.max(18f, w * 0.07f);
            float radius = Math.min(w, h) * 0.36f;
            RectF arc = new RectF(cx - radius, cy - radius, cx + radius, cy + radius);

            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(stroke);
            paint.setColor(Color.rgb(222, 228, 234));
            canvas.drawArc(arc, 135, 270, false, paint);

            float normalized = active ? Math.max(0f, Math.min(1f, (rssi + 100f) / 50f)) : 0f;
            paint.setColor(active ? signalColor(rssi) : Color.rgb(180, 188, 196));
            canvas.drawArc(arc, 135, 270 * normalized, false, paint);

            paint.setStyle(Paint.Style.FILL);
            paint.setColor(active ? signalColor(rssi) : Color.rgb(210, 216, 222));
            canvas.drawCircle(cx, cy, radius * 0.46f, paint);

            textPaint.setColor(active ? Color.WHITE : Color.rgb(100, 110, 120));
            textPaint.setTextSize(w * 0.105f);
            canvas.drawText(active ? String.valueOf(rssi) : "-", cx, cy + w * 0.025f, textPaint);
            textPaint.setTextSize(w * 0.048f);
            canvas.drawText("dBm", cx, cy + w * 0.09f, textPaint);
        }
    }

    private static class SimpleTextWatcher implements android.text.TextWatcher {
        private final Runnable action;
        SimpleTextWatcher(Runnable action) { this.action = action; }
        @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
        @Override public void onTextChanged(CharSequence s, int start, int before, int count) { action.run(); }
        @Override public void afterTextChanged(android.text.Editable s) { }
    }
}
