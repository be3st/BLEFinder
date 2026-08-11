package com.example.blefinder;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
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
import android.net.wifi.WifiManager;
import android.net.wifi.aware.AttachCallback;
import android.net.wifi.aware.DiscoverySessionCallback;
import android.net.wifi.aware.PeerHandle;
import android.net.wifi.aware.PublishConfig;
import android.net.wifi.aware.SubscribeConfig;
import android.net.wifi.aware.WifiAwareManager;
import android.net.wifi.aware.WifiAwareSession;
import android.net.wifi.rtt.RangingRequest;
import android.net.wifi.rtt.RangingResult;
import android.net.wifi.rtt.RangingResultCallback;
import android.net.wifi.rtt.WifiRttManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class MultiRadioScanner {
    interface Listener {
        void onRssi(String key, String name, String source, int rssi);
        void onDistance(String key, String name, String source, double meters, Double uncertainty);
        void onSeen(String key, String name, String source);
        void onStatus(String status);
    }

    private static final String AWARE_SERVICE = "blefinder-nearby-v2";
    private final Activity activity;
    private final Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final BluetoothAdapter bt;
    private final WifiManager wifi;
    private final WifiRttManager rtt;
    private final WifiAwareManager aware;
    private BluetoothLeScanner ble;
    private WifiAwareSession awareSession;
    private boolean running;
    private boolean receiverRegistered;

    private boolean bleStarted;
    private boolean classicStarted;
    private boolean wifiStarted;
    private boolean awareStarted;
    private boolean rttAvailable;
    private int blePackets;
    private int classicDevices;
    private int wifiNetworks;
    private int rttMeasurements;
    private int awarePeers;
    private String bleNote = "warte";
    private String classicNote = "warte";
    private String wifiNote = "warte";
    private String rttNote = "warte";
    private String awareNote = "warte";
    private long startedAt;

    private final Runnable diagnosticsTicker = new Runnable() {
        @Override public void run() {
            if (!running) return;
            listener.onStatus(diagnosticsText());
            handler.postDelayed(this, 1000L);
        }
    };

    MultiRadioScanner(Activity activity, Listener listener) {
        this.activity = activity;
        this.listener = listener;
        BluetoothManager bm = (BluetoothManager) activity.getSystemService(Context.BLUETOOTH_SERVICE);
        bt = bm == null ? null : bm.getAdapter();
        wifi = (WifiManager) activity.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        rtt = Build.VERSION.SDK_INT >= 28 ? (WifiRttManager) activity.getSystemService(Context.WIFI_RTT_RANGING_SERVICE) : null;
        aware = Build.VERSION.SDK_INT >= 26 ? (WifiAwareManager) activity.getSystemService(Context.WIFI_AWARE_SERVICE) : null;
    }

    void start() {
        if (running) return;
        running = true;
        resetDiagnostics();
        startedAt = System.currentTimeMillis();

        if (bt == null) {
            bleNote = "kein Bluetooth-Adapter";
            classicNote = "kein Bluetooth-Adapter";
        } else if (!isBluetoothEnabled()) {
            bleNote = "Bluetooth ist AUS";
            classicNote = "Bluetooth ist AUS";
        }
        if (wifi == null) wifiNote = "kein Wi-Fi-Manager";
        else if (!isWifiEnabled()) wifiNote = "Wi-Fi ist AUS";

        registerReceiver();
        startBle();
        startClassic();
        startWifi();
        startAware();
        handler.removeCallbacks(diagnosticsTicker);
        handler.post(diagnosticsTicker);
    }

    void stop() {
        running = false;
        try { if (ble != null) ble.stopScan(bleCallback); } catch (Exception ignored) {}
        try { if (bt != null && bt.isDiscovering()) bt.cancelDiscovery(); } catch (Exception ignored) {}
        try { if (awareSession != null) awareSession.close(); } catch (Exception ignored) {}
        awareSession = null;
        handler.removeCallbacksAndMessages(null);
        if (receiverRegistered) {
            try { activity.unregisterReceiver(receiver); } catch (Exception ignored) {}
            receiverRegistered = false;
        }
        listener.onStatus("Radar gestoppt");
    }

    boolean isRunning() { return running; }

    boolean hasRequiredPermissions() {
        if (Build.VERSION.SDK_INT >= 31) {
            if (activity.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) return false;
            if (activity.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return false;
        }
        if (Build.VERSION.SDK_INT >= 33
                && activity.checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) return false;
        return activity.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    String[] requiredPermissions() {
        List<String> p = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 31) {
            p.add(Manifest.permission.BLUETOOTH_SCAN);
            p.add(Manifest.permission.BLUETOOTH_CONNECT);
        }
        if (Build.VERSION.SDK_INT >= 33) p.add(Manifest.permission.NEARBY_WIFI_DEVICES);
        p.add(Manifest.permission.ACCESS_FINE_LOCATION);
        return p.toArray(new String[0]);
    }

    private void resetDiagnostics() {
        bleStarted = false;
        classicStarted = false;
        wifiStarted = false;
        awareStarted = false;
        rttAvailable = false;
        blePackets = 0;
        classicDevices = 0;
        wifiNetworks = 0;
        rttMeasurements = 0;
        awarePeers = 0;
        bleNote = "warte";
        classicNote = "warte";
        wifiNote = "warte";
        rttNote = "warte";
        awareNote = "warte";
    }

    private boolean isBluetoothEnabled() {
        try { return bt != null && bt.isEnabled(); } catch (Exception e) { return false; }
    }

    private boolean isWifiEnabled() {
        try { return wifi != null && wifi.isWifiEnabled(); } catch (Exception e) { return false; }
    }

    private void registerReceiver() {
        if (receiverRegistered) return;
        IntentFilter f = new IntentFilter();
        f.addAction(BluetoothDevice.ACTION_FOUND);
        f.addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED);
        f.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        f.addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
        try {
            if (Build.VERSION.SDK_INT >= 33) activity.registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED);
            else activity.registerReceiver(receiver, f);
            receiverRegistered = true;
        } catch (Exception e) {
            bleNote = "Receiver-Fehler: " + e.getClass().getSimpleName();
            classicNote = bleNote;
            wifiNote = bleNote;
        }
    }

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String a = intent.getAction();
            if (BluetoothDevice.ACTION_FOUND.equals(a)) {
                BluetoothDevice d = Build.VERSION.SDK_INT >= 33
                        ? intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class)
                        : intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                int rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE);
                if (d != null && rssi != Short.MIN_VALUE) {
                    classicDevices++;
                    classicNote = "empfängt";
                    listener.onRssi("BT:" + address(d), name(d, "Bluetooth-Gerät"), "Classic", rssi);
                }
            } else if (BluetoothAdapter.ACTION_DISCOVERY_STARTED.equals(a)) {
                classicStarted = true;
                classicNote = "aktiv";
            } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(a)) {
                classicStarted = false;
                classicNote = classicDevices > 0 ? "Zyklus fertig" : "0 Treffer im letzten Zyklus";
                if (running) handler.postDelayed(MultiRadioScanner.this::startClassic, 1200);
            } else if (WifiManager.SCAN_RESULTS_AVAILABLE_ACTION.equals(a)) {
                consumeWifi();
            }
        }
    };

    private final ScanCallback bleCallback = new ScanCallback() {
        @Override public void onScanResult(int callbackType, ScanResult result) { consumeBle(result); }
        @Override public void onBatchScanResults(List<ScanResult> results) { for (ScanResult r : results) consumeBle(r); }
        @Override public void onScanFailed(int errorCode) {
            bleStarted = false;
            bleNote = "Scanfehler " + errorCode;
        }
    };

    private void startBle() {
        if (!running || bt == null) return;
        if (!isBluetoothEnabled()) { bleNote = "Bluetooth ist AUS"; return; }
        if (!hasRequiredPermissions()) { bleNote = "Berechtigung fehlt"; return; }
        if (!activity.getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            bleNote = "BLE nicht unterstützt";
            return;
        }
        try {
            ble = bt.getBluetoothLeScanner();
            if (ble == null) { bleNote = "Scanner nicht verfügbar"; return; }
            ScanSettings s = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).setReportDelay(0).build();
            ble.startScan(null, s, bleCallback);
            bleStarted = true;
            bleNote = "aktiv, warte auf Pakete";
        } catch (SecurityException e) {
            bleNote = "SecurityException";
        } catch (Exception e) {
            bleNote = "Startfehler: " + e.getClass().getSimpleName();
        }
    }

    private void consumeBle(ScanResult result) {
        if (result == null) return;
        blePackets++;
        bleNote = "empfängt";
        BluetoothDevice d = result.getDevice();
        ScanRecord record = result.getScanRecord();
        String n = record == null ? null : record.getDeviceName();
        if (n == null || n.isBlank()) n = name(d, "Unbekanntes BLE-Gerät");
        listener.onRssi("BLE:" + address(d), n, "BLE", result.getRssi());
    }

    private void startClassic() {
        if (!running || bt == null) return;
        if (!isBluetoothEnabled()) { classicNote = "Bluetooth ist AUS"; return; }
        if (!hasRequiredPermissions()) { classicNote = "Berechtigung fehlt"; return; }
        try {
            if (bt.isDiscovering()) {
                classicStarted = true;
                classicNote = "aktiv";
            } else {
                classicStarted = bt.startDiscovery();
                classicNote = classicStarted ? "aktiv" : "startDiscovery=false";
            }
        } catch (SecurityException e) {
            classicNote = "SecurityException";
        } catch (Exception e) {
            classicNote = "Startfehler: " + e.getClass().getSimpleName();
        }
    }

    private void startWifi() {
        if (!running || wifi == null) return;
        if (!isWifiEnabled()) { wifiNote = "Wi-Fi ist AUS"; scheduleWifi(); return; }
        if (!hasRequiredPermissions()) { wifiNote = "Berechtigung fehlt"; scheduleWifi(); return; }
        try {
            boolean requested = wifi.startScan();
            wifiStarted = requested;
            wifiNote = requested ? "Scan angefordert" : "startScan=false / gedrosselt";
            // Auch gecachte Resultate sofort auswerten. So bleibt die Liste nutzbar, wenn Android aktive Scans drosselt.
            consumeWifi();
        } catch (SecurityException e) {
            wifiNote = "SecurityException";
        } catch (Exception e) {
            wifiNote = "Startfehler: " + e.getClass().getSimpleName();
        }
        scheduleWifi();
    }

    private void scheduleWifi() {
        handler.postDelayed(() -> { if (running) startWifi(); }, 15_000L);
    }

    private void consumeWifi() {
        if (wifi == null || !hasRequiredPermissions()) return;
        List<android.net.wifi.ScanResult> results;
        try { results = wifi.getScanResults(); }
        catch (SecurityException e) { wifiNote = "SecurityException bei Ergebnissen"; return; }
        catch (Exception e) { wifiNote = "Ergebnisfehler"; return; }
        if (results == null) return;
        wifiNetworks = results.size();
        if (!results.isEmpty()) wifiNote = "empfängt";
        List<android.net.wifi.ScanResult> responders = new ArrayList<>();
        for (android.net.wifi.ScanResult r : results) {
            String n = r.SSID == null || r.SSID.isBlank() ? "Wi-Fi Access Point" : r.SSID;
            listener.onRssi("WIFI:" + r.BSSID, n, "Wi-Fi", r.level);
            if (Build.VERSION.SDK_INT >= 23 && r.is80211mcResponder()) responders.add(r);
        }
        if (!responders.isEmpty()) rangeWifi(responders);
        else rttNote = rttAvailable ? "keine RTT-Responder" : rttNote;
    }

    private void rangeWifi(List<android.net.wifi.ScanResult> responders) {
        if (Build.VERSION.SDK_INT < 28 || rtt == null) { rttNote = "nicht unterstützt"; return; }
        try { rttAvailable = rtt.isAvailable(); }
        catch (Exception e) { rttAvailable = false; }
        if (!rttAvailable) { rttNote = "nicht verfügbar"; return; }
        responders.sort(Comparator.comparingInt((android.net.wifi.ScanResult x) -> x.level).reversed());
        List<android.net.wifi.ScanResult> selected = responders.subList(0, Math.min(8, responders.size()));
        rttNote = "messe " + selected.size() + " AP(s)";
        try {
            RangingRequest.Builder b = new RangingRequest.Builder();
            b.addAccessPoints(selected);
            rtt.startRanging(b.build(), activity.getMainExecutor(), new RangingResultCallback() {
                @Override public void onRangingFailure(int code) { rttNote = "Ranging-Fehler " + code; }
                @Override public void onRangingResults(List<RangingResult> results) {
                    for (RangingResult rr : results) {
                        if (rr.getStatus() != RangingResult.STATUS_SUCCESS || rr.getMacAddress() == null) continue;
                        rttMeasurements++;
                        rttNote = "empfängt";
                        String mac = rr.getMacAddress().toString();
                        double m = rr.getDistanceMm() / 1000.0;
                        Double sd = rr.getDistanceStdDevMm() > 0 ? rr.getDistanceStdDevMm() / 1000.0 : null;
                        listener.onDistance("WIFI:" + mac, "RTT Access Point", "Wi-Fi RTT", m, sd);
                    }
                }
            });
        } catch (SecurityException e) {
            rttNote = "Berechtigung fehlt";
        } catch (Exception e) {
            rttNote = "Startfehler: " + e.getClass().getSimpleName();
        }
    }

    private void startAware() {
        if (!running || Build.VERSION.SDK_INT < 26 || aware == null) {
            awareNote = "nicht unterstützt";
            return;
        }
        boolean available;
        try { available = aware.isAvailable(); }
        catch (Exception e) { available = false; }
        if (!available) { awareNote = "nicht verfügbar"; return; }
        if (!hasRequiredPermissions()) { awareNote = "Berechtigung fehlt"; return; }
        try {
            aware.attach(new AttachCallback() {
                @Override public void onAttached(WifiAwareSession session) {
                    awareSession = session;
                    awareStarted = true;
                    awareNote = "verbunden";
                    try {
                        PublishConfig pub = new PublishConfig.Builder().setServiceName(AWARE_SERVICE).build();
                        SubscribeConfig sub = new SubscribeConfig.Builder().setServiceName(AWARE_SERVICE).build();
                        session.publish(pub, new DiscoverySessionCallback() {}, handler);
                        session.subscribe(sub, new DiscoverySessionCallback() {
                            @Override public void onServiceDiscovered(PeerHandle peer, byte[] info, List<byte[]> match) {
                                awarePeers++;
                                awareNote = "Peer entdeckt";
                                listener.onSeen("AWARE:" + peer.hashCode(), "BLE Finder Peer", "Wi-Fi Aware");
                            }
                        }, handler);
                    } catch (SecurityException e) {
                        awareNote = "Berechtigung fehlt";
                    } catch (Exception e) {
                        awareNote = "Sessionfehler: " + e.getClass().getSimpleName();
                    }
                }

                @Override public void onAttachFailed() {
                    awareStarted = false;
                    awareNote = "Attach fehlgeschlagen";
                }
            }, handler);
            awareNote = "verbinde";
        } catch (SecurityException e) {
            awareNote = "Berechtigung fehlt";
        } catch (Exception e) {
            awareNote = "übersprungen: " + e.getClass().getSimpleName();
        }
    }

    private String diagnosticsText() {
        long seconds = Math.max(0, (System.currentTimeMillis() - startedAt) / 1000L);
        String btState = bt == null ? "nicht vorhanden" : (isBluetoothEnabled() ? "EIN" : "AUS");
        String wifiState = wifi == null ? "nicht vorhanden" : (isWifiEnabled() ? "EIN" : "AUS");
        StringBuilder s = new StringBuilder();
        s.append("Radar läuft seit ").append(seconds).append(" s\n");
        s.append("Bluetooth: ").append(btState).append(" · Wi-Fi: ").append(wifiState).append("\n");
        s.append("BLE: ").append(bleStarted ? "✓ " : "○ ").append(bleNote).append(" · ").append(blePackets).append(" Pakete\n");
        s.append("Classic: ").append(classicStarted ? "✓ " : "○ ").append(classicNote).append(" · ").append(classicDevices).append(" Treffer\n");
        s.append("Wi-Fi: ").append(wifiStarted ? "✓ " : "○ ").append(wifiNote).append(" · ").append(wifiNetworks).append(" Netze\n");
        s.append("RTT: ").append(rttAvailable ? "✓ " : "○ ").append(rttNote).append(" · ").append(rttMeasurements).append(" Messungen\n");
        s.append("Aware: ").append(awareStarted ? "✓ " : "○ ").append(awareNote).append(" · ").append(awarePeers).append(" Peers");
        return s.toString();
    }

    private String address(BluetoothDevice d) {
        if (d == null) return "unknown";
        try { return d.getAddress(); } catch (Exception e) { return Integer.toHexString(d.hashCode()); }
    }

    private String name(BluetoothDevice d, String fallback) {
        if (d == null) return fallback;
        try { String n = d.getName(); return n == null || n.isBlank() ? fallback : n; }
        catch (Exception e) { return fallback; }
    }
}
