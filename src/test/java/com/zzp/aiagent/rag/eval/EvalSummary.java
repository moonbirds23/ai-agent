package com.zzp.aiagent.rag.eval;

/**
 * Aggregated evaluation metrics for a group of cases or the overall run.
 * All metrics are at a specific K value (the default is typically 5).
 */
public record EvalSummary(
        double hitRateAtK,
        double recallAtK,
        double precisionAtK,
        double mrrAtK,
        double ndcgAtK,
        double candidateRecallAtK,
        double selectedRecallAtK,
        double selectedNdcgAtK,
        double rerankGainAtK,
        double relevantDropRate,
        double emptyResultAccuracy,
        double forbiddenResultRate,
        int totalCases,
        int successfulCases,
        int failedCases,
        int k
) {
    /** Factory for a summary where no cases were evaluated (avoids NaN). */
    public static EvalSummary empty(int k) {
        return new EvalSummary(0.0, 0.0, 0.0, 0.0, 0.0,
                0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0, 0, 0, k);
    }
}
