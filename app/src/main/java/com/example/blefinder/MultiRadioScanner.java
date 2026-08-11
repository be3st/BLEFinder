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
        registerReceiver();
        startBle();
        startClassic();
        startWifi();
        startAware();
        listener.onStatus(statusText());
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
        listener.onStatus("Scan gestoppt");
    }

    boolean isRunning() { return running; }

    boolean hasRequiredPermissions() {
        if (Build.VERSION.SDK_INT >= 31) {
            if (activity.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) return false;
            if (activity.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return false;
        }
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

    private void registerReceiver() {
        IntentFilter f = new IntentFilter();
        f.addAction(BluetoothDevice.ACTION_FOUND);
        f.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        f.addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
        if (Build.VERSION.SDK_INT >= 33) activity.registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED);
        else activity.registerReceiver(receiver, f);
        receiverRegistered = true;
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
                    listener.onRssi("BT:" + address(d), name(d, "Bluetooth-Gerät"), "Classic", rssi);
                }
            } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(a)) {
                if (running) handler.postDelayed(MultiRadioScanner.this::startClassic, 1200);
            } else if (WifiManager.SCAN_RESULTS_AVAILABLE_ACTION.equals(a)) {
                consumeWifi();
            }
        }
    };

    private final ScanCallback bleCallback = new ScanCallback() {
        @Override public void onScanResult(int callbackType, ScanResult result) { consumeBle(result); }
        @Override public void onBatchScanResults(List<ScanResult> results) { for (ScanResult r : results) consumeBle(r); }
        @Override public void onScanFailed(int errorCode) { listener.onStatus("BLE-Scanfehler " + errorCode); }
    };

    private void startBle() {
        if (!running || bt == null || !hasRequiredPermissions()) return;
        try {
            ble = bt.getBluetoothLeScanner();
            if (ble == null) return;
            ScanSettings s = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
            ble.startScan(null, s, bleCallback);
        } catch (Exception ignored) {}
    }

    private void consumeBle(ScanResult result) {
        if (result == null) return;
        BluetoothDevice d = result.getDevice();
        ScanRecord record = result.getScanRecord();
        String n = record == null ? null : record.getDeviceName();
        if (n == null || n.isBlank()) n = name(d, "Unbekanntes BLE-Gerät");
        listener.onRssi("BLE:" + address(d), n, "BLE", result.getRssi());
    }

    private void startClassic() {
        if (!running || bt == null || !hasRequiredPermissions()) return;
        try { if (!bt.isDiscovering()) bt.startDiscovery(); } catch (Exception ignored) {}
    }

    private void startWifi() {
        if (!running || wifi == null || !hasRequiredPermissions()) return;
        try { wifi.startScan(); } catch (Exception ignored) {}
        handler.postDelayed(() -> { if (running) startWifi(); }, 15_000);
    }

    private void consumeWifi() {
        if (wifi == null || !hasRequiredPermissions()) return;
        List<android.net.wifi.ScanResult> results;
        try { results = wifi.getScanResults(); } catch (Exception e) { return; }
        if (results == null) return;
        List<android.net.wifi.ScanResult> responders = new ArrayList<>();
        for (android.net.wifi.ScanResult r : results) {
            String name = r.SSID == null || r.SSID.isBlank() ? "Wi‑Fi Access Point" : r.SSID;
            listener.onRssi("WIFI:" + r.BSSID, name, "Wi-Fi", r.level);
            if (Build.VERSION.SDK_INT >= 23 && r.is80211mcResponder()) responders.add(r);
        }
        if (!responders.isEmpty()) rangeWifi(responders);
    }

    private void rangeWifi(List<android.net.wifi.ScanResult> responders) {
        if (Build.VERSION.SDK_INT < 28 || rtt == null || !rtt.isAvailable()) return;
        responders.sort(Comparator.comparingInt((android.net.wifi.ScanResult x) -> x.level).reversed());
        List<android.net.wifi.ScanResult> selected = responders.subList(0, Math.min(8, responders.size()));
        try {
            RangingRequest.Builder b = new RangingRequest.Builder();
            b.addAccessPoints(selected);
            rtt.startRanging(b.build(), activity.getMainExecutor(), new RangingResultCallback() {
                @Override public void onRangingFailure(int code) { }
                @Override public void onRangingResults(List<RangingResult> results) {
                    for (RangingResult rr : results) {
                        if (rr.getStatus() != RangingResult.STATUS_SUCCESS || rr.getMacAddress() == null) continue;
                        String mac = rr.getMacAddress().toString();
                        double m = rr.getDistanceMm() / 1000.0;
                        Double sd = rr.getDistanceStdDevMm() > 0 ? rr.getDistanceStdDevMm() / 1000.0 : null;
                        listener.onDistance("WIFI:" + mac, "RTT Access Point", "Wi-Fi RTT", m, sd);
                    }
                }
            });
        } catch (Exception ignored) {}
    }

    private void startAware() {
        if (!running || Build.VERSION.SDK_INT < 26 || aware == null || !aware.isAvailable()) return;
        try {
            aware.attach(new AttachCallback() {
                @Override public void onAttached(WifiAwareSession session) {
                    awareSession = session;
                    PublishConfig pub = new PublishConfig.Builder().setServiceName(AWARE_SERVICE).build();
                    SubscribeConfig sub = new SubscribeConfig.Builder().setServiceName(AWARE_SERVICE).build();
                    session.publish(pub, new DiscoverySessionCallback(){}, handler);
                    session.subscribe(sub, new DiscoverySessionCallback() {
                        @Override public void onServiceDiscovered(PeerHandle peer, byte[] info, List<byte[]> match) {
                            listener.onSeen("AWARE:" + peer.hashCode(), "BLE Finder Peer", "Wi-Fi Aware");
                        }
                    }, handler);
                }
            }, handler);
        } catch (Exception ignored) {}
    }

    private String statusText() {
        List<String> s = new ArrayList<>();
        if (bt != null) { s.add("BLE"); s.add("Classic"); }
        if (wifi != null) s.add("Wi-Fi");
        if (rtt != null && Build.VERSION.SDK_INT >= 28 && rtt.isAvailable()) s.add("RTT");
        if (aware != null && Build.VERSION.SDK_INT >= 26 && aware.isAvailable()) s.add("Aware");
        if (activity.getPackageManager().hasSystemFeature("android.hardware.uwb")) s.add("UWB-ready");
        return "Aktiv: " + String.join(" · ", s);
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
