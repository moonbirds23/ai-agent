package com.zzp.aiagent.rag.eval;

/**
 * Latency statistics for the evaluation run, in milliseconds.
 */
public record LatencyStats(
        double avgLatencyMs,
        double p50LatencyMs,
        double p95LatencyMs,
        double p99LatencyMs,
        double minLatencyMs,
        double maxLatencyMs
) {
    /** Sentinel for runs where no latency was measured. */
    public static LatencyStats empty() {
        return new LatencyStats(0.0, 0.0, 0.0, 0.0, 0.0, 0.0);
    }

    /** Compute stats from an array of per-case latencies in milliseconds. */
    public static LatencyStats from(double[] latenciesMs) {
        if (latenciesMs == null || latenciesMs.length == 0) {
            return empty();
        }
        int n = latenciesMs.length;
        double sum = 0.0;
        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;
        for (double d : latenciesMs) {
            sum += d;
            if (d < min) min = d;
            if (d > max) max = d;
        }
        double avg = sum / n;

        // sort for percentiles
        double[] sorted = java.util.Arrays.copyOf(latenciesMs, n);
        java.util.Arrays.sort(sorted);

        return new LatencyStats(
                avg,
                percentile(sorted, 0.50),
                percentile(sorted, 0.95),
                percentile(sorted, 0.99),
                min,
                max
        );
    }

    private static double percentile(double[] sorted, double p) {
        int n = sorted.length;
        if (n == 0) return 0.0;
        double index = p * (n - 1);
        int lo = (int) Math.floor(index);
        int hi = (int) Math.ceil(index);
        if (lo == hi) return sorted[lo];
        double frac = index - lo;
        return sorted[lo] * (1.0 - frac) + sorted[hi] * frac;
    }
}
