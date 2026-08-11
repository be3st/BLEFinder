package com.example.blefinder;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

final class NearbyReading {
    final String key;
    final ProximityFusion fusion = new ProximityFusion();
    final Set<String> sources = new LinkedHashSet<>();
    String name;
    String detail = "";
    long lastSeen;

    NearbyReading(String key, String name) {
        this.key = key;
        this.name = name == null || name.isBlank() ? "Unbekanntes Gerät" : name;
    }

    void rssi(String source, int rssi, long now) {
        sources.add(source);
        fusion.addRssi(source, rssi, now);
        lastSeen = now;
        detail = source + " · " + rssi + " dBm";
    }

    void distance(String source, double meters, Double uncertainty, long now) {
        sources.add(source);
        fusion.addDistance(source, meters, uncertainty, now);
        lastSeen = now;
        detail = uncertainty == null
                ? String.format(Locale.ROOT, "%s · %.2f m", source, meters)
                : String.format(Locale.ROOT, "%s · %.2f m ± %.2f m", source, meters, uncertainty);
    }

    void seen(String source, long now) {
        sources.add(source);
        lastSeen = now;
        detail = source + " · entdeckt";
    }

    boolean fresh(long now) { return lastSeen > 0 && now - lastSeen <= 30_000L; }

    String sourceLabel() { return String.join(" + ", sources); }
}
