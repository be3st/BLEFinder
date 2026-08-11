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
    private static final ParcelUuid FINDER_SERVICE_UUID = new ParcelUuid(UUID.fromString("7af2b410-7d64-4df0-9bbf-10ab3b7d1201"));

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<String, DeviceReading> readings = new HashMap<>();
    private final List<DeviceReading> visibleReadings = new ArrayList<>();
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothLeScanner bleScanner;
    private BluetoothLeAdvertiser bleAdvertiser;
    private boolean scanning, bleScanRunning, classicDiscoveryRunning, advertising, receiverRegistered;
    private int pendingBtAction = ACTION_NONE;
    private ToneGenerator toneGenerator;
    private Vibrator vibrator;
    private boolean soundEnabled = true, vibrationEnabled = true, activityVisible;
    private LinearLayout root;
    private TextView statusText;
    private Button scanButton, advertiseButton;
    private EditText filterEdit;
    private ListView listView;
    private DeviceAdapter adapter;
    private DeviceReading selected;
    private FinderGauge finderGauge;
    private TextView finderName, finderRssi, finderQuality, finderHint, finderAge;

    private final Runnable staleTicker = new Runnable() { @Override public void run() { updateFinderUi(); handler.postDelayed(this, 500L); } };
    private final Runnable feedbackTicker = new Runnable() {
        @Override public void run() {
            long nextDelay = 400L;
            if (activityVisible && scanning && selected != null && finderGauge != null) {
                DeviceReading current = readings.get(selected.address);
                if (current != null) selected = current;
                long age = System.currentTimeMillis() - selected.lastSeenMs;
                if (selected.hasSample && age <= selected.lostAfterMs() && (soundEnabled || vibrationEnabled)) {
                    int rssi = (int) Math.round(selected.smoothedRssi);
                    playFinderPulse(rssi); nextDelay = feedbackIntervalMs(rssi);
                }
            }
            handler.postDelayed(this, nextDelay);
        }
    };
    private final ScanCallback scanCallback = new ScanCallback() {
        @Override public void onScanResult(int callbackType, ScanResult result) { handleBleResult(result); }
        @Override public void onBatchScanResults(List<ScanResult> results) { for (ScanResult result : results) handleBleResult(result); }
        @Override public void onScanFailed(int errorCode) { bleScanRunning = false; statusTextSafe("BLE-Scan-Fehler: " + scanErrorName(errorCode) + ". Classic-Suche laeuft ggf. weiter."); updateScanButton(); }
    };
    private final AdvertiseCallback advertiseCallback = new AdvertiseCallback() {
        @Override public void onStartSuccess(AdvertiseSettings settingsInEffect) { advertising = true; updateAdvertiseButton(); Toast.makeText(MainActivity.this, "Finder-Signal aktiv. Ein zweites Handy mit BLE Finder kann dieses Handy jetzt leichter finden.", Toast.LENGTH_LONG).show(); }
        @Override public void onStartFailure(int errorCode) { advertising = false; updateAdvertiseButton(); Toast.makeText(MainActivity.this, "Finder-Signal konnte nicht gestartet werden (Fehler " + errorCode + ").", Toast.LENGTH_LONG).show(); }
    };
    private final BroadcastReceiver bluetoothReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                BluetoothDevice device;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class); else device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                short rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE); handleClassicResult(device, rssi);
            } else if (BluetoothAdapter.ACTION_DISCOVERY_STARTED.equals(action)) { classicDiscoveryRunning = true; refreshList(); }
            else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) { classicDiscoveryRunning = false; if (scanning) handler.postDelayed(() -> { if (scanning) startClassicDiscovery(); }, CLASSIC_RESTART_DELAY_MS); }
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        BluetoothManager manager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE); bluetoothAdapter = manager == null ? null : manager.getAdapter();
        initializeFinderFeedback(); registerBluetoothReceiver(); buildScanScreen(); handler.post(staleTicker); handler.post(feedbackTicker);
        if (bluetoothAdapter == null) { statusText.setText("Dieses Geraet unterstuetzt kein Bluetooth."); scanButton.setEnabled(false); if (advertiseButton != null) advertiseButton.setEnabled(false); }
    }
    @Override protected void onResume() { super.onResume(); activityVisible = true; }
    @Override protected void onPause() { activityVisible = false; stopFeedbackNow(); super.onPause(); }
    @Override protected void onDestroy() { stopScan(); stopAdvertising(); stopFeedbackNow(); if (receiverRegistered) { try { unregisterReceiver(bluetoothReceiver); } catch (IllegalArgumentException ignored) {} } if (toneGenerator != null) toneGenerator.release(); handler.removeCallbacksAndMessages(null); super.onDestroy(); }
    @Override public void onBackPressed() { if (selected != null) { selected = null; buildScanScreen(); refreshList(); } else super.onBackPressed(); }

    private void registerBluetoothReceiver() {
        IntentFilter filter = new IntentFilter(); filter.addAction(BluetoothDevice.ACTION_FOUND); filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED); filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) registerReceiver(bluetoothReceiver, filter, Context.RECEIVER_NOT_EXPORTED); else registerReceiver(bluetoothReceiver, filter); receiverRegistered = true;
    }
    private void buildScanScreen() {
        root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(dp(18),dp(14),dp(18),dp(14)); root.setBackgroundColor(Color.rgb(245,247,250)); setContentView(root);
        root.addView(text("Bluetooth Finder",28,true,Color.rgb(23,32,42)),lpMatchWrap());
        TextView subtitle=text("Sucht gleichzeitig nach BLE- und sichtbaren Bluetooth-Classic-Geraeten und zeigt die Signalstaerke an.",15,false,Color.rgb(96,112,128)); subtitle.setPadding(0,dp(3),0,dp(10)); root.addView(subtitle,lpMatchWrap());
        TextView phoneHelp=text("Handy finden: Am sichersten BLE Finder auch auf dem Ziel-Handy oeffnen und dort 'Finder-Signal senden' aktivieren. Ein beliebiges Handy ist sonst nicht automatisch per Bluetooth sichtbar.",13,false,Color.rgb(75,85,95)); phoneHelp.setPadding(0,0,0,dp(10)); root.addView(phoneHelp,lpMatchWrap());
        statusText=text("Bereit",14,false,Color.rgb(75,85,95)); root.addView(statusText,lpMatchWrap());
        scanButton=new Button(this); scanButton.setText("Suche starten"); scanButton.setAllCaps(false); scanButton.setOnClickListener(v->{if(scanning)stopScan();else ensureReadyAndScan();}); root.addView(scanButton,lpMatchWrap());
        advertiseButton=new Button(this); advertiseButton.setAllCaps(false); advertiseButton.setOnClickListener(v->ensureReadyAndToggleAdvertising()); root.addView(advertiseButton,lpMatchWrap()); updateAdvertiseButton();
        Button discoverableButton=new Button(this); discoverableButton.setAllCaps(false); discoverableButton.setText("Dieses Handy 5 Min. Classic-sichtbar machen"); discoverableButton.setOnClickListener(v->ensureReadyAndRequestDiscoverable()); root.addView(discoverableButton,lpMatchWrap());
        filterEdit=new EditText(this); filterEdit.setHint("Geraetename, Adresse oder Typ filtern"); filterEdit.setSingleLine(true); root.addView(filterEdit,lpMatchWrap()); filterEdit.addTextChangedListener(new SimpleTextWatcher(this::refreshList));
        TextView help=text("Tipp: BLE liefert meist haeufigere RSSI-Werte. Classic-Geraete werden nur waehrend ihrer Sichtbarkeit gefunden; deren Messwert aktualisiert sich langsamer.",13,false,Color.rgb(96,112,128)); root.addView(help,lpMatchWrap());
        listView=new ListView(this); adapter=new DeviceAdapter(); listView.setAdapter(adapter); listView.setOnItemClickListener((p,v,pos,id)->{selected=visibleReadings.get(pos);buildFinderScreen();}); root.addView(listView,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,0,1f)); updateScanButton();
    }
    private void buildFinderScreen() {
        root=new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setGravity(Gravity.CENTER_HORIZONTAL); root.setPadding(dp(18),dp(14),dp(18),dp(18)); root.setBackgroundColor(Color.rgb(245,247,250)); setContentView(root);
        Button back=new Button(this); back.setText("Zurueck"); back.setAllCaps(false); back.setOnClickListener(v->onBackPressed()); root.addView(back,lpMatchWrap());
        finderName=text("",18,true,Color.rgb(35,45,55)); finderName.setGravity(Gravity.CENTER); root.addView(finderName,lpMatchWrap());
        TextView address=text(selected==null?"":selected.address+"  |  "+selected.typeLabel(),13,false,Color.rgb(96,112,128)); address.setGravity(Gravity.CENTER); root.addView(address,lpMatchWrap());
        finderGauge=new FinderGauge(this); root.addView(finderGauge,new LinearLayout.LayoutParams(dp(300),dp(300)));
        finderRssi=text("-- dBm",30,true,Color.rgb(23,32,42)); finderRssi.setGravity(Gravity.CENTER); root.addView(finderRssi,lpMatchWrap());
        finderQuality=text("Warte auf Signal ...",22,true,Color.rgb(23,32,42)); finderQuality.setGravity(Gravity.CENTER); root.addView(finderQuality,lpMatchWrap());
        finderHint=text("Je gruener und voller die Anzeige, desto staerker ist das Signal.",15,false,Color.rgb(96,112,128)); finderHint.setGravity(Gravity.CENTER); root.addView(finderHint,lpMatchWrap());
        finderAge=text("",13,false,Color.rgb(96,112,128)); finderAge.setGravity(Gravity.CENTER); root.addView(finderAge,lpMatchWrap());
        LinearLayout controls=new LinearLayout(this); controls.setGravity(Gravity.CENTER); CheckBox sound=new CheckBox(this); sound.setText("Ton"); sound.setChecked(soundEnabled); sound.setOnCheckedChangeListener((b,c)->soundEnabled=c); controls.addView(sound); CheckBox vibration=new CheckBox(this); vibration.setText("Vibration"); vibration.setChecked(vibrationEnabled); vibration.setOnCheckedChangeListener((b,c)->vibrationEnabled=c); controls.addView(vibration); root.addView(controls,lpMatchWrap()); updateFinderUi();
    }

    private void ensureReadyAndScan(){if(bluetoothAdapter==null)return;if(!hasScanPermissions()){requestScanPermissions();return;}if(!isBluetoothEnabledSafe()){requestEnableBluetooth(ACTION_SCAN);return;}startScan();}
    private void ensureReadyAndToggleAdvertising(){if(advertising){stopAdvertising();return;}if(bluetoothAdapter==null)return;if(!hasAdvertisePermissions()){requestAdvertisePermissions(PERMISSION_ADVERTISE);return;}if(!isBluetoothEnabledSafe()){requestEnableBluetooth(ACTION_ADVERTISE);return;}startAdvertising();}
    private void ensureReadyAndRequestDiscoverable(){if(bluetoothAdapter==null)return;if(!hasAdvertisePermissions()){requestAdvertisePermissions(PERMISSION_DISCOVERABLE);return;}if(!isBluetoothEnabledSafe()){requestEnableBluetooth(ACTION_DISCOVERABLE);return;}requestClassicDiscoverable();}
    private boolean hasScanPermissions(){if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.S)return checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)==PackageManager.PERMISSION_GRANTED&&checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED&&checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED;return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED;}
    private boolean hasAdvertisePermissions(){return Build.VERSION.SDK_INT<Build.VERSION_CODES.S||(checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE)==PackageManager.PERMISSION_GRANTED&&checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED);}
    private void requestScanPermissions(){if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.S)requestPermissions(new String[]{Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.BLUETOOTH_CONNECT,Manifest.permission.ACCESS_FINE_LOCATION},PERMISSION_SCAN);else requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION},PERMISSION_SCAN);}
    private void requestAdvertisePermissions(int code){if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.S)requestPermissions(new String[]{Manifest.permission.BLUETOOTH_ADVERTISE,Manifest.permission.BLUETOOTH_CONNECT},code);else if(code==PERMISSION_DISCOVERABLE)requestClassicDiscoverable();else startAdvertising();}
    @Override public void onRequestPermissionsResult(int requestCode,String[] permissions,int[] grantResults){super.onRequestPermissionsResult(requestCode,permissions,grantResults);if(requestCode==PERMISSION_SCAN&&hasScanPermissions())ensureReadyAndScan();else if(requestCode==PERMISSION_ADVERTISE&&hasAdvertisePermissions())ensureReadyAndToggleAdvertising();else if(requestCode==PERMISSION_DISCOVERABLE&&hasAdvertisePermissions())ensureReadyAndRequestDiscoverable();}
    private boolean isBluetoothEnabledSafe(){try{return bluetoothAdapter!=null&&bluetoothAdapter.isEnabled();}catch(SecurityException e){return false;}}
    private void requestEnableBluetooth(int action){pendingBtAction=action;try{startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE),ENABLE_BT_REQUEST);}catch(Exception e){startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));}}
    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data){super.onActivityResult(requestCode,resultCode,data);if(requestCode==ENABLE_BT_REQUEST&&isBluetoothEnabledSafe()){int action=pendingBtAction;pendingBtAction=ACTION_NONE;if(action==ACTION_SCAN)startScan();else if(action==ACTION_ADVERTISE)startAdvertising();else if(action==ACTION_DISCOVERABLE)requestClassicDiscoverable();}}

    private void startScan(){if(scanning||bluetoothAdapter==null||!hasScanPermissions())return;scanning=true;addBondedDevices();startBleScan();startClassicDiscovery();statusTextSafe((bleScanRunning||classicDiscoveryRunning)?"Suche laeuft ("+activeScanModesText()+") ...":"Suche konnte nicht gestartet werden.");if(!bleScanRunning&&!classicDiscoveryRunning)scanning=false;updateScanButton();refreshList();}
    private void startBleScan(){if(!scanning||bluetoothAdapter==null||!hasScanPermissions())return;try{bleScanner=bluetoothAdapter.getBluetoothLeScanner();if(bleScanner==null)return;ScanSettings settings=new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).setReportDelay(0).build();bleScanner.startScan(null,settings,scanCallback);bleScanRunning=true;}catch(Exception e){bleScanRunning=false;}}
    private void startClassicDiscovery(){if(!scanning||bluetoothAdapter==null||!hasScanPermissions())return;try{classicDiscoveryRunning=bluetoothAdapter.isDiscovering()||bluetoothAdapter.startDiscovery();}catch(Exception e){classicDiscoveryRunning=false;}}
    private void stopScan(){scanning=false;try{if(bleScanner!=null&&bleScanRunning)bleScanner.stopScan(scanCallback);}catch(Exception ignored){}bleScanRunning=false;try{if(bluetoothAdapter!=null&&bluetoothAdapter.isDiscovering())bluetoothAdapter.cancelDiscovery();}catch(Exception ignored){}classicDiscoveryRunning=false;stopFeedbackNow();statusTextSafe("Suche gestoppt");updateScanButton();}
    private void addBondedDevices(){if(bluetoothAdapter==null)return;try{Set<BluetoothDevice> bonded=bluetoothAdapter.getBondedDevices();if(bonded!=null)for(BluetoothDevice d:bonded){String address=safeAddress(d,"Gekoppelt-");String name=safeDeviceName(d);if(name==null||name.trim().isEmpty())name="Gekoppeltes Geraet";DeviceReading item=getOrCreate(address,name);item.paired=true;}}catch(SecurityException ignored){}}
    private void handleBleResult(ScanResult result){if(result==null)return;BluetoothDevice device=result.getDevice();String address=safeAddress(device,"BLE-");String name=null;boolean finder=false;ScanRecord record=result.getScanRecord();if(record!=null){name=record.getDeviceName();List<ParcelUuid> uuids=record.getServiceUuids();finder=uuids!=null&&uuids.contains(FINDER_SERVICE_UUID);}if((name==null||name.trim().isEmpty())&&hasScanPermissions())name=safeDeviceName(device);if(finder)name="BLE Finder-Handy";if(name==null||name.trim().isEmpty())name="Unbenanntes BLE-Geraet";DeviceReading item=getOrCreate(address,name);item.name=name;item.seenBle=true;item.finderBeacon|=finder;item.addRssi(result.getRssi(),"BLE");onReadingUpdated(item);}
    private void handleClassicResult(BluetoothDevice device,short rssi){if(device==null)return;String address=safeAddress(device,"Classic-");String name=safeDeviceName(device);if(name==null||name.trim().isEmpty())name="Unbenanntes Classic-Geraet";DeviceReading item=getOrCreate(address,name);item.name=name;item.seenClassic=true;if(rssi!=Short.MIN_VALUE)item.addRssi(rssi,"Classic");onReadingUpdated(item);}
    private DeviceReading getOrCreate(String address,String name){DeviceReading item=readings.get(address);if(item==null){item=new DeviceReading(address,name);readings.put(address,item);}return item;}
    private String safeAddress(BluetoothDevice device,String prefix){try{String a=device.getAddress();return a==null?prefix+Integer.toHexString(device.hashCode()):a;}catch(Exception e){return prefix+"unbekannt";}}
    private String safeDeviceName(BluetoothDevice device){try{return device==null?null:device.getName();}catch(Exception e){return null;}}
    private void onReadingUpdated(DeviceReading item){if(selected!=null&&selected.address.equals(item.address))selected=item;refreshList();updateFinderUi();}
    private void refreshList(){if(adapter==null)return;String q=filterEdit==null?"":filterEdit.getText().toString().trim().toLowerCase(Locale.ROOT);visibleReadings.clear();for(DeviceReading r:readings.values())if(q.isEmpty()||r.name.toLowerCase(Locale.ROOT).contains(q)||r.address.toLowerCase(Locale.ROOT).contains(q)||r.typeLabel().toLowerCase(Locale.ROOT).contains(q))visibleReadings.add(r);Collections.sort(visibleReadings,(a,b)->{if(a.hasSample!=b.hasSample)return a.hasSample?-1:1;return Double.compare(b.smoothedRssi,a.smoothedRssi);});adapter.notifyDataSetChanged();statusTextSafe((scanning?"Suche laeuft ("+activeScanModesText()+") ... ":"")+visibleReadings.size()+" Geraet(e) in der Liste");}
    private String activeScanModesText(){if(bleScanRunning&&classicDiscoveryRunning)return"BLE + Classic";if(bleScanRunning)return"BLE";if(classicDiscoveryRunning)return"Classic";return"warte";}
    private void updateFinderUi(){if(selected==null||finderGauge==null)return;DeviceReading current=readings.get(selected.address);if(current!=null)selected=current;finderName.setText(selected.name);if(!selected.hasSample){finderGauge.setRssi(-100,false);finderRssi.setText("-- dBm");finderQuality.setText("Noch kein aktuelles Signal");finderHint.setText("Warte auf ein neues Bluetooth-Signal.");finderAge.setText(selected.typeLabel());return;}long age=System.currentTimeMillis()-selected.lastSeenMs;boolean lost=age>selected.lostAfterMs();if(lost){finderGauge.setRssi(-100,false);finderRssi.setText("-- dBm");finderQuality.setText("Signal verloren");finderHint.setText("Gehe etwas zurueck oder aendere die Richtung.");finderAge.setText("Letztes Signal vor "+age/1000+" s");}else{int r=(int)Math.round(selected.smoothedRssi);finderGauge.setRssi(r,true);finderRssi.setText(r+" dBm");finderQuality.setText(qualityText(r));finderHint.setText(proximityHint(r));finderAge.setText("Quelle: "+selected.lastSignalSource+" | vor "+age/1000+" s");}}
    private void startAdvertising(){if(bluetoothAdapter==null||advertising||!hasAdvertisePermissions())return;try{bleAdvertiser=bluetoothAdapter.getBluetoothLeAdvertiser();if(bleAdvertiser==null)return;AdvertiseSettings s=new AdvertiseSettings.Builder().setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY).setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH).setConnectable(false).setTimeout(0).build();AdvertiseData d=new AdvertiseData.Builder().setIncludeDeviceName(false).addServiceUuid(FINDER_SERVICE_UUID).build();bleAdvertiser.startAdvertising(s,d,advertiseCallback);}catch(Exception e){Toast.makeText(this,"Finder-Signal konnte nicht gestartet werden.",Toast.LENGTH_LONG).show();}}
    private void stopAdvertising(){try{if(bleAdvertiser!=null&&advertising)bleAdvertiser.stopAdvertising(advertiseCallback);}catch(Exception ignored){}advertising=false;updateAdvertiseButton();}
    private void updateAdvertiseButton(){if(advertiseButton!=null)advertiseButton.setText(advertising?"Finder-Signal senden: EIN - stoppen":"Finder-Signal von diesem Handy senden");}
    private void requestClassicDiscoverable(){try{Intent i=new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);i.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION,300);startActivityForResult(i,DISCOVERABLE_REQUEST);}catch(Exception e){Toast.makeText(this,"Classic-Sichtbarkeit konnte nicht angefordert werden.",Toast.LENGTH_LONG).show();}}
    private void initializeFinderFeedback(){try{toneGenerator=new ToneGenerator(AudioManager.STREAM_MUSIC,70);}catch(Exception ignored){}if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.S){VibratorManager vm=(VibratorManager)getSystemService(Context.VIBRATOR_MANAGER_SERVICE);vibrator=vm==null?null:vm.getDefaultVibrator();}else vibrator=(Vibrator)getSystemService(Context.VIBRATOR_SERVICE);}
    private void playFinderPulse(int rssi){float n=Math.max(0f,Math.min(1f,(rssi+95f)/45f));int duration=Math.round(35f+35f*n);if(soundEnabled&&toneGenerator!=null)toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP,duration);if(vibrationEnabled&&vibrator!=null&&vibrator.hasVibrator())try{if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.O)vibrator.vibrate(VibrationEffect.createOneShot(duration,VibrationEffect.DEFAULT_AMPLITUDE));else vibrator.vibrate(duration);}catch(Exception ignored){}}
    private long feedbackIntervalMs(int rssi){float n=Math.max(0f,Math.min(1f,(rssi+95f)/45f));return Math.round(1150f-1000f*n);}
    private void stopFeedbackNow(){if(toneGenerator!=null)toneGenerator.stopTone();if(vibrator!=null)try{vibrator.cancel();}catch(Exception ignored){}}
    private void updateScanButton(){if(scanButton!=null)scanButton.setText(scanning?"Suche stoppen":"Suche starten");}
    private void statusTextSafe(String text){if(statusText!=null)statusText.setText(text);}
    private String qualityText(int rssi){if(rssi>=-55)return"Sehr nah";if(rssi>=-65)return"Nah";if(rssi>=-75)return"Mittel";if(rssi>=-85)return"Schwach";return"Sehr schwach";}
    private String proximityHint(int rssi){if(rssi>=-55)return"Sehr starkes Signal - das Geraet duerfte in unmittelbarer Naehe sein.";if(rssi>=-65)return"Starkes Signal - bewege dich langsam weiter in diese Richtung.";if(rssi>=-75)return"Mittleres Signal - probiere verschiedene Richtungen aus.";if(rssi>=-85)return"Schwaches Signal - moeglicherweise weiter entfernt.";return"Sehr schwach - groessere Entfernung wahrscheinlich.";}
    private String scanErrorName(int code){switch(code){case ScanCallback.SCAN_FAILED_ALREADY_STARTED:return"bereits gestartet";case ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED:return"Registrierung fehlgeschlagen";case ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED:return"nicht unterstuetzt";case ScanCallback.SCAN_FAILED_INTERNAL_ERROR:return"interner Fehler";default:return"Code "+code;}}
    private TextView text(String value,int sp,boolean bold,int color){TextView t=new TextView(this);t.setText(value);t.setTextSize(sp);t.setTextColor(color);if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return t;}
    private LinearLayout.LayoutParams lpMatchWrap(){return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT);}
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private static int signalColor(int rssi){float n=Math.max(0f,Math.min(1f,(rssi+100f)/50f));return Color.HSVToColor(new float[]{120f*n,0.88f,0.90f});}

    private static class DeviceReading {
        final String address; String name; int lastRssi=-100; double smoothedRssi=-100; boolean hasSample; long lastSeenMs; boolean seenBle,seenClassic,paired,finderBeacon; String lastSignalSource="";
        DeviceReading(String address,String name){this.address=address;this.name=name;}
        void addRssi(int rssi,String source){lastRssi=rssi;if(!hasSample){smoothedRssi=rssi;hasSample=true;}else{double a="Classic".equals(source)?0.40:0.28;smoothedRssi=a*rssi+(1-a)*smoothedRssi;}lastSignalSource=source;lastSeenMs=System.currentTimeMillis();}
        long lostAfterMs(){return"Classic".equals(lastSignalSource)?CLASSIC_LOST_AFTER_MS:BLE_LOST_AFTER_MS;}
        String typeLabel(){if(finderBeacon)return"BLE Finder-Handy";if(seenBle&&seenClassic)return paired?"BLE + Classic | gekoppelt":"BLE + Classic";if(seenBle)return paired?"BLE | gekoppelt":"BLE";if(seenClassic)return paired?"Classic | gekoppelt":"Classic";return paired?"Gekoppelt | kein Signal":"Unbekannt";}
    }
    private class DeviceAdapter extends BaseAdapter {
        @Override public int getCount(){return visibleReadings.size();}@Override public Object getItem(int p){return visibleReadings.get(p);}@Override public long getItemId(int p){return p;}
        @Override public View getView(int p,View convert,ViewGroup parent){LinearLayout row=new LinearLayout(MainActivity.this);row.setOrientation(LinearLayout.VERTICAL);row.setPadding(dp(12),dp(10),dp(12),dp(10));DeviceReading item=visibleReadings.get(p);TextView n=text(item.name,17,true,item.hasSample?signalColor((int)Math.round(item.smoothedRssi)):Color.rgb(90,100,110));row.addView(n,lpMatchWrap());String detail=item.typeLabel()+" | "+item.address+(item.hasSample?" | "+(int)Math.round(item.smoothedRssi)+" dBm":" | kein aktueller RSSI");row.addView(text(detail,13,false,Color.rgb(96,112,128)),lpMatchWrap());return row;}
    }
    private static class FinderGauge extends View {
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG),textPaint=new Paint(Paint.ANTI_ALIAS_FLAG);private int rssi=-100;private boolean active;
        FinderGauge(Context c){super(c);paint.setStrokeCap(Paint.Cap.ROUND);textPaint.setTextAlign(Paint.Align.CENTER);textPaint.setTypeface(Typeface.DEFAULT_BOLD);}
        void setRssi(int r,boolean a){rssi=r;active=a;invalidate();}
        @Override protected void onDraw(Canvas canvas){super.onDraw(canvas);float w=getWidth(),h=getHeight(),cx=w/2f,cy=h/2f,radius=Math.min(w,h)*.36f;RectF arc=new RectF(cx-radius,cy-radius,cx+radius,cy+radius);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(Math.max(18f,w*.07f));paint.setColor(Color.rgb(222,228,234));canvas.drawArc(arc,135,270,false,paint);float n=active?Math.max(0f,Math.min(1f,(rssi+100f)/50f)):0f;paint.setColor(active?signalColor(rssi):Color.GRAY);canvas.drawArc(arc,135,270*n,false,paint);textPaint.setColor(Color.DKGRAY);textPaint.setTextSize(w*.1f);canvas.drawText(active?String.valueOf(rssi):"-",cx,cy,textPaint);}
    }
    private static class SimpleTextWatcher implements android.text.TextWatcher {private final Runnable action;SimpleTextWatcher(Runnable a){action=a;}public void beforeTextChanged(CharSequence s,int st,int c,int a){}public void onTextChanged(CharSequence s,int st,int b,int c){action.run();}public void afterTextChanged(android.text.Editable s){}}
}
