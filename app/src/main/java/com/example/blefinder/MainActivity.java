package com.example.blefinder;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends Activity implements MultiRadioScanner.Listener {
    private static final int REQ_PERMISSIONS = 100;
    private static final int REQ_NEARBY_WIFI = 101;

    private final Map<String, NearbyReading> devices = new LinkedHashMap<>();
    private final List<NearbyReading> visible = new ArrayList<>();
    private MultiRadioScanner scanner;
    private ListView list;
    private DeviceAdapter adapter;
    private TextView status;
    private TextView heroValue;
    private TextView heroLabel;
    private TextView heroMeta;
    private Button scanButton;
    private boolean detailMode;
    private NearbyReading selected;
    private boolean nearbyWifiPromptedThisSession;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(0xFFF4F7FB);
        getWindow().setNavigationBarColor(0xFFF4F7FB);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        scanner = new MultiRadioScanner(this, this);
        showDashboard();
    }

    @Override protected void onDestroy() {
        if (scanner != null) scanner.stop();
        super.onDestroy();
    }

    private void applySafeInsets(LinearLayout root) {
        root.setPadding(dp(20), dp(32), dp(20), dp(18));
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int top = insets.getSystemWindowInsetTop();
            int bottom = insets.getSystemWindowInsetBottom();
            v.setPadding(dp(20), Math.max(dp(20), top + dp(10)), dp(20), Math.max(dp(18), bottom + dp(10)));
            return insets;
        });
        root.requestApplyInsets();
    }

    private void showDashboard() {
        detailMode = false;
        selected = null;
        LinearLayout root = column(0xFFF4F7FB);
        applySafeInsets(root);

        TextView eyebrow = text("NEARBY RADAR", 12, true, 0xFF5B6B80);
        root.addView(eyebrow);
        TextView title = text("BLE Finder", 32, true, 0xFF142033);
        root.addView(title);
        TextView subtitle = text("Mehrere Funkquellen werden zu einer gemeinsamen Nähe-Schätzung kombiniert.", 14, false, 0xFF64748B);
        subtitle.setPadding(0, dp(4), 0, dp(14));
        root.addView(subtitle);

        LinearLayout chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.addView(chip("BLE"));
        chips.addView(chip("Classic"));
        chips.addView(chip("Wi‑Fi"));
        chips.addView(chip("RTT"));
        chips.addView(chip("Aware"));
        chips.addView(chip("UWB-ready"));
        root.addView(chips, matchWrap());

        LinearLayout hero = card(0xFFFFFFFF, 28);
        hero.setGravity(Gravity.CENTER);
        hero.setPadding(dp(18), dp(24), dp(18), dp(24));
        LinearLayout.LayoutParams heroLp = matchWrap();
        heroLp.topMargin = dp(16);
        root.addView(hero, heroLp);
        TextView heroTitle = text("Stärkstes aktuelles Signal", 14, true, 0xFF64748B);
        heroTitle.setGravity(Gravity.CENTER);
        hero.addView(heroTitle);
        heroValue = text("--", 58, true, 0xFF3157D5);
        heroValue.setGravity(Gravity.CENTER);
        hero.addView(heroValue);
        heroLabel = text("Nähe 0–100", 16, true, 0xFF142033);
        heroLabel.setGravity(Gravity.CENTER);
        hero.addView(heroLabel);
        heroMeta = text("Noch keine aktuelle Messung", 13, false, 0xFF64748B);
        heroMeta.setGravity(Gravity.CENTER);
        heroMeta.setPadding(0, dp(6), 0, 0);
        hero.addView(heroMeta);

        scanButton = new Button(this);
        scanButton.setAllCaps(false);
        scanButton.setTextSize(16);
        scanButton.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        scanButton.setText(scanner != null && scanner.isRunning() ? "Radar stoppen" : "Radar starten");
        scanButton.setOnClickListener(v -> toggleScan());
        LinearLayout.LayoutParams scanLp = matchWrap();
        scanLp.topMargin = dp(14);
        root.addView(scanButton, scanLp);

        LinearLayout diagnosticCard = card(0xFFFFFFFF, 20);
        diagnosticCard.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams diagnosticLp = matchWrap();
        diagnosticLp.topMargin = dp(10);
        diagnosticLp.bottomMargin = dp(12);
        root.addView(diagnosticCard, diagnosticLp);
        diagnosticCard.addView(text("Live-Diagnose", 13, true, 0xFF142033));
        status = text(scanner != null && scanner.isRunning() ? "Radar läuft …" : "Bereit – Radar starten, um Funkmodule zu prüfen.", 12, false, 0xFF64748B);
        status.setTypeface(Typeface.MONOSPACE);
        status.setLineSpacing(0f, 1.08f);
        status.setPadding(0, dp(6), 0, 0);
        diagnosticCard.addView(status);

        TextView section = text("Geräte in der Nähe", 20, true, 0xFF142033);
        section.setPadding(0, dp(4), 0, dp(8));
        root.addView(section);

        list = new ListView(this);
        list.setDivider(null);
        adapter = new DeviceAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((p, v, pos, id) -> showDetail(visible.get(pos)));
        root.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
        refresh();
    }

    private void showDetail(NearbyReading item) {
        detailMode = true;
        selected = item;
        LinearLayout root = column(0xFFF4F7FB);
        applySafeInsets(root);

        Button back = new Button(this);
        back.setAllCaps(false);
        back.setText("‹ Zurück");
        back.setOnClickListener(v -> showDashboard());
        root.addView(back, matchWrap());

        TextView name = text(item.name, 28, true, 0xFF142033);
        name.setPadding(0, dp(14), 0, dp(2));
        root.addView(name);
        TextView src = text(item.sourceLabel(), 14, false, 0xFF64748B);
        root.addView(src);

        LinearLayout scoreCard = card(0xFFFFFFFF, 30);
        scoreCard.setGravity(Gravity.CENTER);
        scoreCard.setPadding(dp(20), dp(26), dp(20), dp(26));
        LinearLayout.LayoutParams scLp = matchWrap();
        scLp.topMargin = dp(18);
        root.addView(scoreCard, scLp);

        ProximityFusion.Result result = item.fusion.result(System.currentTimeMillis());
        TextView score = text(String.valueOf(result.proximity), 72, true, scoreColor(result.proximity));
        score.setGravity(Gravity.CENTER);
        scoreCard.addView(score);
        TextView scoreText = text(proximityWord(result.proximity), 22, true, 0xFF142033);
        scoreText.setGravity(Gravity.CENTER);
        scoreCard.addView(scoreText);
        TextView confidence = text("Vertrauen " + result.confidence + "/100", 14, false, 0xFF64748B);
        confidence.setGravity(Gravity.CENTER);
        confidence.setPadding(0, dp(8), 0, 0);
        scoreCard.addView(confidence);
        if (result.distanceMeters != null) {
            TextView distance = text(String.format(Locale.ROOT, "ca. %.2f m", result.distanceMeters), 24, true, 0xFF3157D5);
            distance.setGravity(Gravity.CENTER);
            distance.setPadding(0, dp(8), 0, 0);
            scoreCard.addView(distance);
        } else {
            TextView approx = text("RSSI-basierte Näherung – keine echte Distanzmessung", 13, false, 0xFF64748B);
            approx.setGravity(Gravity.CENTER);
            approx.setPadding(0, dp(8), 0, 0);
            scoreCard.addView(approx);
        }

        LinearLayout info = card(0xFFFFFFFF, 22);
        info.setPadding(dp(16), dp(16), dp(16), dp(16));
        LinearLayout.LayoutParams infoLp = matchWrap();
        infoLp.topMargin = dp(14);
        root.addView(info, infoLp);
        info.addView(text("Letzte Messung", 13, true, 0xFF64748B));
        info.addView(text(item.detail, 17, true, 0xFF142033));
        TextView id = text(item.key, 12, false, 0xFF94A3B8);
        id.setPadding(0, dp(8), 0, 0);
        info.addView(id);

        TextView note = text("100 bedeutet sehr nah, 0 bedeutet kein verwertbares aktuelles Signal. Echte RTT/UWB-Distanzen werden höher gewichtet als RSSI.", 13, false, 0xFF64748B);
        note.setPadding(0, dp(14), 0, 0);
        root.addView(note);
        setContentView(root);
    }

    private void toggleScan() {
        if (scanner.isRunning()) {
            scanner.stop();
            scanButton.setText("Radar starten");
            return;
        }
        if (!scanner.hasRequiredPermissions()) {
            requestPermissions(scanner.requiredPermissions(), REQ_PERMISSIONS);
            return;
        }
        if (scanner.needsNearbyWifiPermission() && !nearbyWifiPromptedThisSession) {
            nearbyWifiPromptedThisSession = true;
            requestPermissions(scanner.nearbyWifiPermissions(), REQ_NEARBY_WIFI);
            return;
        }
        devices.clear();
        scanner.start();
        scanButton.setText("Radar stoppen");
        refresh();
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERMISSIONS) {
            if (scanner.hasRequiredPermissions()) toggleScan();
            else Toast.makeText(this, "Für Bluetooth- und Wi-Fi-Scans werden Bluetooth und präziser Standort benötigt.", Toast.LENGTH_LONG).show();
        } else if (requestCode == REQ_NEARBY_WIFI) {
            if (scanner.needsNearbyWifiPermission()) {
                Toast.makeText(this, "Nearby-Wi-Fi wurde nicht erlaubt. BLE, Classic und normale Wi-Fi-Netze funktionieren trotzdem; RTT/Aware bleiben deaktiviert.", Toast.LENGTH_LONG).show();
            }
            toggleScan();
        }
    }

    @Override public void onBackPressed() {
        if (detailMode) showDashboard(); else super.onBackPressed();
    }

    @Override public void onRssi(String key, String name, String source, int rssi) {
        runOnUiThread(() -> {
            NearbyReading d = get(key, name);
            d.rssi(source, rssi, System.currentTimeMillis());
            refresh();
        });
    }

    @Override public void onDistance(String key, String name, String source, double meters, Double uncertainty) {
        runOnUiThread(() -> {
            NearbyReading d = get(key, name);
            d.distance(source, meters, uncertainty, System.currentTimeMillis());
            refresh();
        });
    }

    @Override public void onSeen(String key, String name, String source) {
        runOnUiThread(() -> {
            get(key, name).seen(source, System.currentTimeMillis());
            refresh();
        });
    }

    @Override public void onStatus(String value) {
        runOnUiThread(() -> {
            if (status != null) status.setText(value);
        });
    }

    private NearbyReading get(String key, String name) {
        NearbyReading d = devices.get(key);
        if (d == null) {
            d = new NearbyReading(key, name);
            devices.put(key, d);
        } else if (name != null && !name.isBlank() && !name.startsWith("Unbekannt")) d.name = name;
        return d;
    }

    private void refresh() {
        if (adapter == null) return;
        long now = System.currentTimeMillis();
        visible.clear();
        for (NearbyReading d : devices.values()) if (d.fresh(now)) visible.add(d);
        visible.sort(Comparator.comparingInt((NearbyReading d) -> d.fusion.result(now).proximity).reversed());
        adapter.notifyDataSetChanged();
        if (!visible.isEmpty()) {
            NearbyReading top = visible.get(0);
            ProximityFusion.Result r = top.fusion.result(now);
            heroValue.setText(String.valueOf(r.proximity));
            heroValue.setTextColor(scoreColor(r.proximity));
            heroLabel.setText(top.name);
            heroMeta.setText(visible.size() + " aktuelle Signale · Vertrauen " + r.confidence + "/100 · " + top.sourceLabel());
        } else {
            heroValue.setText("--");
            heroLabel.setText("Nähe 0–100");
            heroMeta.setText(scanner.isRunning() ? "Radar aktiv · noch keine verwertbare Messung" : "Noch keine aktuelle Messung");
        }
    }

    private class DeviceAdapter extends BaseAdapter {
        @Override public int getCount() { return visible.size(); }
        @Override public Object getItem(int p) { return visible.get(p); }
        @Override public long getItemId(int p) { return p; }
        @Override public View getView(int p, View convertView, ViewGroup parent) {
            NearbyReading d = visible.get(p);
            ProximityFusion.Result r = d.fusion.result(System.currentTimeMillis());
            LinearLayout card = card(0xFFFFFFFF, 20);
            card.setPadding(dp(16), dp(14), dp(16), dp(14));
            LinearLayout.LayoutParams lp = matchWrap();
            lp.bottomMargin = dp(10);
            card.setLayoutParams(lp);

            LinearLayout row = new LinearLayout(MainActivity.this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout left = new LinearLayout(MainActivity.this);
            left.setOrientation(LinearLayout.VERTICAL);
            row.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            left.addView(text(d.name, 17, true, 0xFF142033));
            left.addView(text(d.sourceLabel() + " · Vertrauen " + r.confidence, 12, false, 0xFF64748B));
            TextView score = text(String.valueOf(r.proximity), 30, true, scoreColor(r.proximity));
            score.setGravity(Gravity.CENTER);
            row.addView(score, new LinearLayout.LayoutParams(dp(58), ViewGroup.LayoutParams.WRAP_CONTENT));
            card.addView(row);
            return card;
        }
    }

    private LinearLayout column(int color) {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setBackgroundColor(color);
        return v;
    }

    private LinearLayout card(int color, int radius) {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(radius));
        v.setBackground(bg);
        if (android.os.Build.VERSION.SDK_INT >= 21) v.setElevation(dp(2));
        return v;
    }

    private TextView chip(String label) {
        TextView t = text(label, 11, true, 0xFF3157D5);
        t.setPadding(dp(9), dp(5), dp(9), dp(5));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFFE8EEFF);
        bg.setCornerRadius(dp(99));
        t.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(6);
        t.setLayoutParams(lp);
        return t;
    }

    private TextView text(String s, int sp, boolean bold, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }

    private LinearLayout.LayoutParams matchWrap() { return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT); }
    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
    private int scoreColor(int score) {
        if (score >= 81) return Color.rgb(18, 145, 96);
        if (score >= 61) return Color.rgb(40, 111, 210);
        if (score >= 41) return Color.rgb(202, 133, 26);
        return Color.rgb(131, 87, 191);
    }
    private String proximityWord(int s) {
        if (s >= 81) return "Sehr nah";
        if (s >= 61) return "Nah";
        if (s >= 41) return "Mittlere Nähe";
        if (s >= 21) return "Eher entfernt";
        return "Sehr weit / unsicher";
    }
}
