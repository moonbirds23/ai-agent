package com.zzp.aiagent.rag.eval;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Compares a current {@link RagEvalReport} against a saved baseline.json.
 *
 * <p>Checks:
 * <ul>
 *   <li>HitRate@5 does not drop by more than 1 case worth of queries</li>
 *   <li>Recall@5 does not drop by more than 0.02</li>
 *   <li>nDCG@5 does not drop by more than 0.02</li>
 *   <li>CandidateRecall@20 does not drop by more than 0.02</li>
 *   <li>RelevantDropRate does not increase</li>
 * </ul>
 */
public final class RagEvalBaselineComparator {

    private RagEvalBaselineComparator() {}

    /**
     * Compare the current report against a baseline report loaded from classpath.
     * Returns a {@link BaselineComparison} with pass/fail and detailed diffs.
     */
    public static BaselineComparison compare(RagEvalReport current, String baselineClasspath) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        RagEvalReport baseline;
        try (InputStream is = RagEvalBaselineComparator.class.getClassLoader()
                .getResourceAsStream(baselineClasspath)) {
            if (is == null) {
                throw new IOException("Baseline file not found: " + baselineClasspath);
            }
            baseline = mapper.readValue(is, RagEvalReport.class);
        }
        return compare(current, baseline);
    }

    /** Compare two reports directly. */
    public static BaselineComparison compare(RagEvalReport current, RagEvalReport baseline) {
        List<String> diffs = new ArrayList<>();
        Map<String, Double> diffsNumeric = new LinkedHashMap<>();

        EvalSummary c = current.overall();
        EvalSummary b = baseline.overall();

        // HitRate@5 — check case count equivalence
        double hitRateDiff = round4(c.hitRateAtK() - b.hitRateAtK());
        diffsNumeric.put("hitRate@5", hitRateDiff);
        // Convert to approximate case count: one case worth is 1/totalCases
        double oneCaseWorth = b.totalCases() > 0 ? 1.0 / b.totalCases() : 0.1;
        if (hitRateDiff < -oneCaseWorth * 1.0) {
            diffs.add(String.format("HitRate@5 dropped by %.4f (>1 case worth %.4f): baseline=%.4f current=%.4f",
                    -hitRateDiff, oneCaseWorth, b.hitRateAtK(), c.hitRateAtK()));
        }

        // Recall@5
        double recallDiff = round4(c.recallAtK() - b.recallAtK());
        diffsNumeric.put("recall@5", recallDiff);
        if (recallDiff < -0.02) {
            diffs.add(String.format("Recall@5 dropped by %.4f (>0.02): baseline=%.4f current=%.4f",
                    -recallDiff, b.recallAtK(), c.recallAtK()));
        }

        // nDCG@5
        double ndcgDiff = round4(c.ndcgAtK() - b.ndcgAtK());
        diffsNumeric.put("ndcg@5", ndcgDiff);
        if (ndcgDiff < -0.02) {
            diffs.add(String.format("nDCG@5 dropped by %.4f (>0.02): baseline=%.4f current=%.4f",
                    -ndcgDiff, b.ndcgAtK(), c.ndcgAtK()));
        }

        // CandidateRecall@20
        double candRecallDiff = round4(c.candidateRecallAtK() - b.candidateRecallAtK());
        diffsNumeric.put("candidateRecall@20", candRecallDiff);
        if (candRecallDiff < -0.02) {
            diffs.add(String.format("CandidateRecall@20 dropped by %.4f (>0.02): baseline=%.4f current=%.4f",
                    -candRecallDiff, b.candidateRecallAtK(), c.candidateRecallAtK()));
        }

        // RelevantDropRate — should not increase
        double dropRateDiff = round4(c.relevantDropRate() - b.relevantDropRate());
        diffsNumeric.put("relevantDropRate", dropRateDiff);
        if (dropRateDiff > 0.0) {
            diffs.add(String.format("RelevantDropRate increased by %.4f: baseline=%.4f current=%.4f",
                    dropRateDiff, b.relevantDropRate(), c.relevantDropRate()));
        }

        boolean passed = diffs.isEmpty();
        return new BaselineComparison(passed, diffs, diffsNumeric);
    }

    private static double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }

    /**
     * Result of comparing current metrics against a baseline.
     */
    public record BaselineComparison(
            boolean passed,
            List<String> diffs,
            Map<String, Double> diffsNumeric
    ) {
        public BaselineComparison {
            diffs = diffs != null ? List.copyOf(diffs) : List.of();
            diffsNumeric = diffsNumeric != null ? Map.copyOf(diffsNumeric) : Map.of();
        }
    }
}
