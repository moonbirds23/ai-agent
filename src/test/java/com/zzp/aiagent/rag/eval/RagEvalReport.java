package com.zzp.aiagent.rag.eval;

import java.util.List;
import java.util.Map;

/**
 * Complete evaluation report containing all metrics, per-group breakdowns,
 * per-case results, and latency statistics.
 */
public record RagEvalReport(
        String datasetVersion,
        String gitCommit,
        String embeddingModel,
        Map<String, Object> ragConfig,
        String mode,             // fixed-retrieval / rewrite-only / end-to-end
        boolean mcpEnabled,
        EvalSummary overall,
        Map<String, EvalSummary> byGroup,
        List<RagEvalCaseResult> caseResults,
        List<String> failedCases,
        LatencyStats latencyStats
) {
    public RagEvalReport {
        ragConfig = ragConfig != null ? Map.copyOf(ragConfig) : Map.of();
        byGroup = byGroup != null ? Map.copyOf(byGroup) : Map.of();
        caseResults = caseResults != null ? List.copyOf(caseResults) : List.of();
        failedCases = failedCases != null ? List.copyOf(failedCases) : List.of();
    }

    /** Create a builder-style report for the given k value. */
    public static Builder forK(int k) {
        return new Builder(k);
    }

    public static class Builder {
        private final int k;
        private String datasetVersion;
        private String gitCommit;
        private String embeddingModel;
        private Map<String, Object> ragConfig = Map.of();
        private String mode = "end-to-end";
        private boolean mcpEnabled;
        private List<RagEvalCaseResult> caseResults = List.of();

        Builder(int k) { this.k = k; }

        public Builder datasetVersion(String v) { this.datasetVersion = v; return this; }
        public Builder gitCommit(String v) { this.gitCommit = v; return this; }
        public Builder embeddingModel(String v) { this.embeddingModel = v; return this; }
        public Builder ragConfig(Map<String, Object> v) { this.ragConfig = v; return this; }
        public Builder mode(String v) { this.mode = v; return this; }
        public Builder mcpEnabled(boolean v) { this.mcpEnabled = v; return this; }
        public Builder caseResults(List<RagEvalCaseResult> v) { this.caseResults = v; return this; }

        public RagEvalReport build() {
            int total = caseResults.size();
            int successCount = (int) caseResults.stream().filter(RagEvalCaseResult::success).count();
            int failCount = total - successCount;
            List<String> failed = caseResults.stream()
                    .filter(r -> !r.success())
                    .map(RagEvalCaseResult::caseId)
                    .toList();

            EvalSummary overall = new EvalSummary(
                    RagEvalMetrics.hitRateAtK(caseResults, k),
                    RagEvalMetrics.recallAtK(caseResults, k),
                    RagEvalMetrics.precisionAtK(caseResults, k),
                    RagEvalMetrics.mrrAtK(caseResults, k),
                    RagEvalMetrics.ndcgAtK(caseResults, k),
                    RagEvalMetrics.candidateRecallAtK(caseResults, k),
                    RagEvalMetrics.selectedRecallAtK(caseResults, k),
                    RagEvalMetrics.selectedNdcgAtK(caseResults, k),
                    RagEvalMetrics.rerankGainAtK(caseResults, k),
                    RagEvalMetrics.relevantDropRate(caseResults),
                    RagEvalMetrics.emptyResultAccuracy(caseResults),
                    RagEvalMetrics.forbiddenResultRate(caseResults),
                    total, successCount, failCount, k
            );

            Map<String, GroupMetrics> gm = RagEvalMetrics.groupMetrics(caseResults, k);
            Map<String, EvalSummary> byGroup = new java.util.LinkedHashMap<>();
            for (var e : gm.entrySet()) {
                byGroup.put(e.getKey(), e.getValue().summary());
            }

            return new RagEvalReport(
                    datasetVersion, gitCommit, embeddingModel, ragConfig,
                    mode, mcpEnabled, overall, byGroup, caseResults, failed,
                    LatencyStats.empty()
            );
        }
    }
}
