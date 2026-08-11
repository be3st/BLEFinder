package com.example.blefinder;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Converts heterogeneous nearby-radio measurements into two deliberately separate values:
 * proximity (0..100, higher means nearer) and confidence (0..100).
 *
 * RSSI is never presented as metres. Real ranging measurements (Wi-Fi RTT/UWB/Aware RTT)
 * dominate the fused result when they are fresh.
 */
final class ProximityFusion {
    static final long NOW_STALE_MS = 30_000L;

    static final class Measurement {
        final String source;
        double score;
        double quality;
        long timestampMs;
        double tauSeconds;
        boolean precise;
        Double distanceMeters;
        Double uncertaintyMeters;

        Measurement(String source) { this.source = source; }
    }

    static final class Result {
        final int proximity;
        final int confidence;
        final String primarySource;
        final Double distanceMeters;
        final Double uncertaintyMeters;

        Result(int proximity, int confidence, String primarySource,
               Double distanceMeters, Double uncertaintyMeters) {
            this.proximity = proximity;
            this.confidence = confidence;
            this.primarySource = primarySource;
            this.distanceMeters = distanceMeters;
            this.uncertaintyMeters = uncertaintyMeters;
        }
    }

    private final Map<String, Measurement> measurements = new LinkedHashMap<>();

    void addRssi(String source, int rssi, long nowMs) {
        Measurement m = measurements.get(source);
        if (m == null) {
            m = new Measurement(source);
            measurements.put(source, m);
        }
        m.score = rssiScore(source, rssi);
        m.quality = sourceQuality(source);
        m.timestampMs = nowMs;
        m.tauSeconds = sourceTau(source);
        m.precise = false;
        m.distanceMeters = null;
        m.uncertaintyMeters = null;
    }

    void addDistance(String source, double distanceMeters, Double uncertaintyMeters, long nowMs) {
        Measurement m = measurements.get(source);
        if (m == null) {
            m = new Measurement(source);
            measurements.put(source, m);
        }
        m.score = distanceScore(distanceMeters);
        m.quality = sourceQuality(source);
        m.timestampMs = nowMs;
        m.tauSeconds = sourceTau(source);
        m.precise = true;
        m.distanceMeters = Math.max(0.0, distanceMeters);
        m.uncertaintyMeters = uncertaintyMeters;
    }

    Result result(long nowMs) {
        double weighted = 0.0;
        double weightSum = 0.0;
        int freshSources = 0;
        int freshSamples = 0;
        boolean hasPrecise = false;
        double bestWeight = -1;
        String primary = "-";
        Double bestDistance = null;
        Double bestUncertainty = null;

        for (Measurement m : measurements.values()) {
            long ageMs = Math.max(0L, nowMs - m.timestampMs);
            if (ageMs > NOW_STALE_MS) continue;
            double freshness = Math.exp(-(ageMs / 1000.0) / Math.max(0.5, m.tauSeconds));
            double w = m.quality * freshness;
            if (w < 0.01) continue;
            weighted += m.score * w;
            weightSum += w;
            freshSources++;
            freshSamples++;
            if (m.precise) hasPrecise = true;
            if (w > bestWeight || (m.precise && bestDistance == null)) {
                bestWeight = w;
                primary = m.source;
                if (m.precise) {
                    bestDistance = m.distanceMeters;
                    bestUncertainty = m.uncertaintyMeters;
                }
            }
        }

        if (weightSum <= 0.0) return new Result(0, 0, "-", null, null);
        int proximity = clamp((int) Math.round(weighted / weightSum));

        int confidence = 15;
        if (hasPrecise) confidence += 48;
        if (freshSources >= 2) confidence += 18;
        if (freshSources >= 3) confidence += 8;
        if (freshSamples >= 4) confidence += 5;
        confidence += Math.min(6, (int) Math.round(Math.min(1.0, weightSum) * 6.0));
        confidence = clamp(confidence);

        return new Result(proximity, confidence, primary, bestDistance, bestUncertainty);
    }

    static int rssiScore(String source, int rssi) {
        double min;
        double max;
        if ("Wi-Fi".equals(source)) {
            min = -95.0;
            max = -30.0;
        } else {
            min = -100.0;
            max = -40.0;
        }
        double x = (rssi - min) / (max - min);
        x = Math.max(0.0, Math.min(1.0, x));
        return clamp((int) Math.round(100.0 * Math.pow(x, 1.5)));
    }

    static int distanceScore(double meters) {
        final double maxDistance = 50.0;
        double d = Math.max(0.0, Math.min(maxDistance, meters));
        double value = 100.0 * (1.0 - Math.log10(1.0 + d) / Math.log10(1.0 + maxDistance));
        return clamp((int) Math.round(value));
    }

    private static double sourceQuality(String source) {
        if ("UWB".equals(source)) return 1.00;
        if ("Wi-Fi RTT".equals(source)) return 0.90;
        if ("Wi-Fi Aware RTT".equals(source)) return 0.90;
        if ("BLE CS".equals(source)) return 0.85;
        if ("BLE".equals(source)) return 0.45;
        if ("Classic".equals(source)) return 0.35;
        if ("Wi-Fi".equals(source)) return 0.30;
        return 0.25;
    }

    private static double sourceTau(String source) {
        if ("UWB".equals(source) || "Wi-Fi RTT".equals(source) || "Wi-Fi Aware RTT".equals(source)) return 3.0;
        if ("BLE CS".equals(source)) return 3.0;
        if ("BLE".equals(source)) return 5.0;
        if ("Classic".equals(source)) return 10.0;
        if ("Wi-Fi".equals(source)) return 15.0;
        return 8.0;
    }

    private static int clamp(int value) { return Math.max(0, Math.min(100, value)); }
}
