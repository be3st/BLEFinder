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
import android.location.LocationManager;
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
    private static final long BLE_HEALTH_CHECK_MS = 15_000L;
    private static final long BLE_RESTART_DELAY_MS = 1_000L;

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
    private boolean bleStarted, classicStarted, wifiStarted, awareStarted, rttAvailable;
    private int blePackets, classicDevices, wifiNetworks, rttMeasurements, awarePeers;
    private String bleNote="warte", classicNote="warte", wifiNote="warte", rttNote="warte", awareNote="warte";
    private long startedAt;
    private long lastBlePacketAt;
    private int bleRestartCount;

    private final Runnable diagnosticsTicker = new Runnable() {
        @Override public void run() {
            if (!running) return;
            listener.onStatus(diagnosticsText());
            handler.postDelayed(this, 1000L);
        }
    };

    private final Runnable bleHealthTicker = new Runnable() {
        @Override public void run() {
            if (!running) return;
            long now = System.currentTimeMillis();
            long lastActivity = lastBlePacketAt > 0 ? lastBlePacketAt : startedAt;
            if (bleStarted && now - lastActivity >= BLE_HEALTH_CHECK_MS) {
                restartBle("keine Pakete seit " + ((now - lastActivity) / 1000L) + " s");
            }
            handler.postDelayed(this, BLE_HEALTH_CHECK_MS);
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
        registerReceiver();
        startBle();
        startClassicFresh();
        startWifi();
        startAware();
        handler.removeCallbacks(diagnosticsTicker);
        handler.removeCallbacks(bleHealthTicker);
        handler.post(diagnosticsTicker);
        handler.postDelayed(bleHealthTicker, BLE_HEALTH_CHECK_MS);
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
        return hasBlePermissions() && hasLocationPermission();
    }

    String[] requiredPermissions() {
        List<String> p = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 31) {
            p.add(Manifest.permission.BLUETOOTH_SCAN);
            p.add(Manifest.permission.BLUETOOTH_CONNECT);
        }
        p.add(Manifest.permission.ACCESS_FINE_LOCATION);
        return p.toArray(new String[0]);
    }

    boolean needsNearbyWifiPermission() {
        return Build.VERSION.SDK_INT >= 33 && !hasNearbyWifiPermission();
    }

    String[] nearbyWifiPermissions() {
        if (Build.VERSION.SDK_INT >= 33) return new String[]{Manifest.permission.NEARBY_WIFI_DEVICES};
        return new String[0];
    }

    private boolean granted(String permission) {
        return Build.VERSION.SDK_INT < 23 || activity.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasBlePermissions() {
        return Build.VERSION.SDK_INT < 31 || (granted(Manifest.permission.BLUETOOTH_SCAN) && granted(Manifest.permission.BLUETOOTH_CONNECT));
    }

    private boolean hasLocationPermission() { return granted(Manifest.permission.ACCESS_FINE_LOCATION); }
    private boolean hasNearbyWifiPermission() { return Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.NEARBY_WIFI_DEVICES); }

    // startScan()/getScanResults() are location-sensitive APIs. Nearby Wi-Fi is not allowed to block plain AP scanning.
    private boolean hasWifiScanPermissions() { return hasLocationPermission(); }
    private boolean hasWifiRangingPermissions() { return hasLocationPermission() && hasNearbyWifiPermission(); }

    private boolean locationServicesEnabled() {
        try {
            LocationManager lm = (LocationManager) activity.getSystemService(Context.LOCATION_SERVICE);
            if (lm == null) return false;
            if (Build.VERSION.SDK_INT >= 28) return lm.isLocationEnabled();
            return lm.isProviderEnabled(LocationManager.GPS_PROVIDER) || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
        } catch (Exception e) { return false; }
    }

    private void resetDiagnostics() {
        bleStarted=classicStarted=wifiStarted=awareStarted=rttAvailable=false;
        blePackets=classicDevices=wifiNetworks=rttMeasurements=awarePeers=0;
        bleRestartCount=0;
        lastBlePacketAt=0L;
        bleNote=classicNote=wifiNote=rttNote=awareNote="warte";
    }

    private boolean isBluetoothEnabled() { try { return bt != null && bt.isEnabled(); } catch (Exception e) { return false; } }
    private boolean isWifiEnabled() { try { return wifi != null && wifi.isWifiEnabled(); } catch (Exception e) { return false; } }

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
            bleNote = classicNote = wifiNote = "Receiver-Fehler: " + e.getClass().getSimpleName();
        }
    }

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String a = intent.getAction();
            if (BluetoothDevice.ACTION_FOUND.equals(a)) {
                BluetoothDevice d = Build.VERSION.SDK_INT >= 33 ? intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class) : intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                int rssiValue = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE);
                if (d != null && rssiValue != Short.MIN_VALUE) {
                    classicDevices++;
                    classicNote="empfängt";
                    listener.onRssi("BT:"+address(d), name(d,"Bluetooth-Gerät"), "Classic", rssiValue);
                }
            } else if (BluetoothAdapter.ACTION_DISCOVERY_STARTED.equals(a)) {
                classicStarted=true;
                classicNote="aktiv";
            } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(a)) {
                classicStarted=false;
                classicNote=classicDevices>0 ? "Zyklus fertig" : "0 Treffer im letzten Zyklus";
                if (running) handler.postDelayed(MultiRadioScanner.this::startClassicFresh, 1500L);
            } else if (WifiManager.SCAN_RESULTS_AVAILABLE_ACTION.equals(a)) {
                consumeWifi();
            }
        }
    };

    private final ScanCallback bleCallback = new ScanCallback() {
        @Override public void onScanResult(int callbackType, ScanResult result) { consumeBle(result); }
        @Override public void onBatchScanResults(List<ScanResult> results) { for (ScanResult r:results) consumeBle(r); }
        @Override public void onScanFailed(int errorCode) {
            bleStarted=false;
            bleNote="Scanfehler "+errorCode;
        }
    };

    private void startBle() {
        if (!running || bt == null) { bleNote="kein Bluetooth-Adapter"; return; }
        if (!isBluetoothEnabled()) { bleNote="Bluetooth ist AUS"; return; }
        if (!hasBlePermissions()) { bleNote="Bluetooth-Berechtigung fehlt"; return; }
        if (!hasLocationPermission()) { bleNote="Standort-Berechtigung fehlt"; return; }
        if (!locationServicesEnabled()) { bleNote="Standortdienst ist AUS"; return; }
        if (!activity.getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) { bleNote="BLE nicht unterstützt"; return; }
        try {
            ble=bt.getBluetoothLeScanner();
            if (ble==null) { bleNote="Scanner nicht verfügbar"; return; }
            ScanSettings settings=new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .setReportDelay(0)
                    .build();
            ble.startScan(null,settings,bleCallback);
            bleStarted=true;
            bleNote="aktiv, warte auf Pakete" + (bleRestartCount>0 ? " · Neustarts "+bleRestartCount : "");
        } catch (SecurityException e) { bleNote="SecurityException"; }
        catch (Exception e) { bleNote="Startfehler: "+e.getClass().getSimpleName(); }
    }

    private void restartBle(String reason) {
        if (!running) return;
        try { if (ble != null && bleStarted) ble.stopScan(bleCallback); } catch (Exception ignored) {}
        bleStarted=false;
        bleRestartCount++;
        bleNote="Neustart #"+bleRestartCount+" ("+reason+")";
        handler.postDelayed(() -> { if (running) startBle(); }, BLE_RESTART_DELAY_MS);
    }

    private void consumeBle(ScanResult result) {
        if (result==null) return;
        blePackets++;
        lastBlePacketAt=System.currentTimeMillis();
        bleNote="empfängt" + (bleRestartCount>0 ? " · Neustarts "+bleRestartCount : "");
        BluetoothDevice d=result.getDevice();
        ScanRecord record=result.getScanRecord();
        String n=record==null?null:record.getDeviceName();
        if (n==null || n.isBlank()) n=name(d,"Unbekanntes BLE-Gerät");
        listener.onRssi("BLE:"+address(d),n,"BLE",result.getRssi());
    }

    private void startClassicFresh() {
        if (!running || bt==null) { classicNote="kein Bluetooth-Adapter"; return; }
        if (!isBluetoothEnabled()) { classicNote="Bluetooth ist AUS"; return; }
        if (!hasBlePermissions()) { classicNote="Bluetooth-Berechtigung fehlt"; return; }
        try {
            if (bt.isDiscovering()) {
                classicNote="beende alten Zyklus";
                bt.cancelDiscovery();
                handler.postDelayed(this::startClassicNow, 800L);
            } else startClassicNow();
        } catch (SecurityException e) { classicNote="SecurityException"; }
        catch (Exception e) { classicNote="Startfehler: "+e.getClass().getSimpleName(); }
    }

    private void startClassicNow() {
        if (!running || bt==null) return;
        try {
            classicStarted=bt.startDiscovery();
            classicNote=classicStarted?"aktiv":"startDiscovery=false (System lehnt ab)";
            if (!classicStarted) handler.postDelayed(this::startClassicFresh,4000L);
        } catch (SecurityException e) { classicNote="SecurityException"; }
        catch (Exception e) { classicNote="Startfehler: "+e.getClass().getSimpleName(); }
    }

    private void startWifi() {
        if (!running || wifi==null) { wifiNote="kein Wi-Fi-Manager"; return; }
        if (!isWifiEnabled()) { wifiNote="Wi-Fi ist AUS"; scheduleWifi(); return; }
        if (!hasWifiScanPermissions()) { wifiNote="Standort-Berechtigung fehlt"; scheduleWifi(); return; }
        if (!locationServicesEnabled()) { wifiNote="Standortdienst ist AUS"; scheduleWifi(); return; }

        // Cached results are useful even when Android throttles a new active scan.
        consumeWifi();
        try {
            boolean requested=wifi.startScan();
            wifiStarted=requested;
            if (wifiNetworks>0) wifiNote="Ergebnisse verfügbar" + (requested?" · neuer Scan angefordert":" · aktiver Scan gedrosselt");
            else wifiNote=requested?"Scan angefordert":"startScan=false / gedrosselt";
        } catch (SecurityException e) { wifiNote="SecurityException"; }
        catch (Exception e) { wifiNote="Startfehler: "+e.getClass().getSimpleName(); }
        scheduleWifi();
    }

    private void scheduleWifi() { handler.postDelayed(() -> { if (running) startWifi(); },15000L); }

    private void consumeWifi() {
        if (wifi==null || !hasWifiScanPermissions()) return;
        List<android.net.wifi.ScanResult> results;
        try { results=wifi.getScanResults(); }
        catch (SecurityException e) { wifiNote="SecurityException bei Ergebnissen"; return; }
        catch (Exception e) { wifiNote="Ergebnisfehler: "+e.getClass().getSimpleName(); return; }
        if (results==null) return;
        wifiNetworks=results.size();
        if (!results.isEmpty()) wifiNote="empfängt";
        List<android.net.wifi.ScanResult> responders=new ArrayList<>();
        for (android.net.wifi.ScanResult result:results) {
            String n=result.SSID==null || result.SSID.isBlank()?"Wi-Fi Access Point":result.SSID;
            listener.onRssi("WIFI:"+result.BSSID,n,"Wi-Fi",result.level);
            if (Build.VERSION.SDK_INT>=23 && result.is80211mcResponder()) responders.add(result);
        }
        if (!responders.isEmpty()) rangeWifi(responders);
        else rttNote=rttAvailable?"keine RTT-Responder":rttNote;
    }

    private void rangeWifi(List<android.net.wifi.ScanResult> responders) {
        if (Build.VERSION.SDK_INT<28 || rtt==null) { rttNote="nicht unterstützt"; return; }
        if (!hasWifiRangingPermissions()) { rttNote="Nearby-Wi-Fi/Standort fehlt"; return; }
        try { rttAvailable=rtt.isAvailable(); } catch (Exception e) { rttAvailable=false; }
        if (!rttAvailable) { rttNote="nicht verfügbar"; return; }
        responders.sort(Comparator.comparingInt((android.net.wifi.ScanResult x)->x.level).reversed());
        List<android.net.wifi.ScanResult> selected=responders.subList(0,Math.min(8,responders.size()));
        rttNote="messe "+selected.size()+" AP(s)";
        try {
            RangingRequest.Builder b=new RangingRequest.Builder();
            b.addAccessPoints(selected);
            rtt.startRanging(b.build(),activity.getMainExecutor(),new RangingResultCallback() {
                @Override public void onRangingFailure(int code) { rttNote="Ranging-Fehler "+code; }
                @Override public void onRangingResults(List<RangingResult> results) {
                    for (RangingResult rr:results) {
                        if (rr.getStatus()!=RangingResult.STATUS_SUCCESS || rr.getMacAddress()==null) continue;
                        rttMeasurements++;
                        rttNote="empfängt";
                        String mac=rr.getMacAddress().toString();
                        double m=rr.getDistanceMm()/1000.0;
                        Double sd=rr.getDistanceStdDevMm()>0?rr.getDistanceStdDevMm()/1000.0:null;
                        listener.onDistance("WIFI:"+mac,"RTT Access Point","Wi-Fi RTT",m,sd);
                    }
                }
            });
        } catch (SecurityException e) { rttNote="Berechtigung fehlt"; }
        catch (Exception e) { rttNote="Startfehler: "+e.getClass().getSimpleName(); }
    }

    private void startAware() {
        if (!running || Build.VERSION.SDK_INT<26 || aware==null) { awareNote="nicht unterstützt"; return; }
        if (!hasNearbyWifiPermission()) { awareNote="NEARBY_WIFI_DEVICES fehlt"; return; }
        boolean available;
        try { available=aware.isAvailable(); } catch (Exception e) { available=false; }
        if (!available) { awareNote="nicht verfügbar"; return; }
        try {
            aware.attach(new AttachCallback() {
                @Override public void onAttached(WifiAwareSession session) {
                    awareSession=session;
                    awareStarted=true;
                    awareNote="verbunden";
                    try {
                        PublishConfig pub=new PublishConfig.Builder().setServiceName(AWARE_SERVICE).build();
                        SubscribeConfig sub=new SubscribeConfig.Builder().setServiceName(AWARE_SERVICE).build();
                        session.publish(pub,new DiscoverySessionCallback(){},handler);
                        session.subscribe(sub,new DiscoverySessionCallback() {
                            @Override public void onServiceDiscovered(PeerHandle peer, byte[] info, List<byte[]> match) {
                                awarePeers++;
                                awareNote="Peer entdeckt";
                                listener.onSeen("AWARE:"+peer.hashCode(),"BLE Finder Peer","Wi-Fi Aware");
                            }
                        },handler);
                    } catch (SecurityException e) { awareNote="Berechtigung fehlt"; }
                    catch (Exception e) { awareNote="Sessionfehler: "+e.getClass().getSimpleName(); }
                }
                @Override public void onAttachFailed() { awareStarted=false; awareNote="Attach fehlgeschlagen"; }
            },handler);
            awareNote="verbinde";
        } catch (SecurityException e) { awareNote="Berechtigung fehlt"; }
        catch (Exception e) { awareNote="übersprungen: "+e.getClass().getSimpleName(); }
    }

    private String mark(boolean ok) { return ok?"✓":"✕"; }

    private String diagnosticsText() {
        long seconds=Math.max(0,(System.currentTimeMillis()-startedAt)/1000L);
        StringBuilder s=new StringBuilder();
        s.append("Radar läuft seit ").append(seconds).append(" s\n");
        s.append("System: Bluetooth ").append(isBluetoothEnabled()?"EIN":"AUS")
                .append(" · Wi-Fi ").append(isWifiEnabled()?"EIN":"AUS")
                .append(" · Standort ").append(locationServicesEnabled()?"EIN":"AUS").append("\n");
        s.append("Rechte: ")
                .append(mark(hasBlePermissions())).append(" Bluetooth · ")
                .append(mark(hasLocationPermission())).append(" Standort · ")
                .append(mark(hasNearbyWifiPermission())).append(" Nearby Wi-Fi\n");
        s.append("BLE: ").append(bleStarted?"✓ ":"○ ").append(bleNote).append(" · ").append(blePackets).append(" Pakete\n");
        s.append("Classic: ").append(classicStarted?"✓ ":"○ ").append(classicNote).append(" · ").append(classicDevices).append(" Treffer\n");
        s.append("Wi-Fi: ").append(wifiNetworks>0?"✓ ":"○ ").append(wifiNote).append(" · ").append(wifiNetworks).append(" Netze\n");
        s.append("RTT: ").append(rttAvailable?"✓ ":"○ ").append(rttNote).append(" · ").append(rttMeasurements).append(" Messungen\n");
        s.append("Aware: ").append(awareStarted?"✓ ":"○ ").append(awareNote).append(" · ").append(awarePeers).append(" Peers");
        return s.toString();
    }

    private String address(BluetoothDevice d) {
        if (d==null) return "unknown";
        try { return d.getAddress(); } catch (Exception e) { return Integer.toHexString(d.hashCode()); }
    }

    private String name(BluetoothDevice d,String fallback) {
        if (d==null) return fallback;
        try { String n=d.getName(); return n==null || n.isBlank()?fallback:n; }
        catch (Exception e) { return fallback; }
    }
}
