package com.zzp.aiagent.observability;

/**
 * Token usage reported by the provider. Missing usage is represented explicitly;
 * it must never be replaced with a fabricated zero-token measurement.
 */
public record ModelUsage(Long inputTokens, Long outputTokens) {

    public ModelUsage {
        requireNonNegative(inputTokens, "inputTokens");
        requireNonNegative(outputTokens, "outputTokens");
    }

    public static ModelUsage unavailable() {
        return new ModelUsage(null, null);
    }

    public boolean available() {
        return inputTokens != null && outputTokens != null;
    }

    public Long totalTokens() {
        return available() ? Math.addExact(inputTokens, outputTokens) : null;
    }

    private static void requireNonNegative(Long value, String name) {
        if (value != null && value < 0) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
    }
}
