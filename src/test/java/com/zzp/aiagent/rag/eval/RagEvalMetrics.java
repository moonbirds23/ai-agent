package com.zzp.aiagent.rag.eval;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Pure function metrics calculator for RAG evaluation.
 * All methods are static and side-effect-free. No DB, no model calls, no Spring.
 *
 * <p>Metrics are computed in two layers:
 * <ul>
 *   <li><b>Candidate layer</b> — raw results from the retriever, before reranking</li>
 *   <li><b>Selected layer</b> — results after reranking</li>
 * </ul>
 * This enables measuring reranker impact via {@code rerankGainAtK} and
 * {@code relevantDropRate}.
 */
public final class RagEvalMetrics {

    private RagEvalMetrics() { /* utility class */ }

    // ── Core retrieval metrics (operate on selectedIds by default) ──────────

    /**
     * HitRate@K: fraction of queries where at least one relevant (grade>=1)
     * picture appears in the top K results.
     */
    public static double hitRateAtK(List<RagEvalCaseResult> results, int k) {
        return aggregateMetric(results, k, false /* use selected */, RagEvalMetrics::hasAnyRelevant);
    }

    /**
     * Recall@K: for each query, (relevant pictures in top K) / (total relevant pictures annotated).
     * Returns macro-average across all queries.
     */
    public static double recallAtK(List<RagEvalCaseResult> results, int k) {
        return macroAverage(results, k, false, RagEvalMetrics::recallForCase);
    }

    /**
     * Precision@K: for each query, (relevant in top K) / K.
     * Returns macro-average across all queries.
     */
    public static double precisionAtK(List<RagEvalCaseResult> results, int k) {
        return macroAverage(results, k, false, RagEvalMetrics::precisionForCase);
    }

    /**
     * MRR@K (Mean Reciprocal Rank): mean of 1 / rank of the first relevant picture.
     * Rank is 1-based. Returns 0 for cases with no relevant picture in top K.
     */
    public static double mrrAtK(List<RagEvalCaseResult> results, int k) {
        if (results == null || results.isEmpty()) return 0.0;
        double sum = 0.0;
        int count = 0;
        for (RagEvalCaseResult r : results) {
            if (!r.success()) continue;
            if (r.expectedEmpty()) continue;
            count++;
            sum += firstRelevantRank(r.selectedIds(), r.gradeMap(), k);
        }
        return count == 0 ? 0.0 : sum / count;
    }

    /**
     * nDCG@K (Normalized Discounted Cumulative Gain) using 0-3 graded relevance.
     * nDCG = DCG / IDCG, where DCG uses the standard log2 discount.
     */
    public static double ndcgAtK(List<RagEvalCaseResult> results, int k) {
        return macroAverage(results, k, false, RagEvalMetrics::ndcgForCase);
    }

    // ── Candidate-layer metrics ─────────────────────────────────────────────

    /** Recall on raw candidate results (before reranking). */
    public static double candidateRecallAtK(List<RagEvalCaseResult> results, int k) {
        return macroAverage(results, k, true, RagEvalMetrics::recallForCase);
    }

    // ── Selected-layer metrics ──────────────────────────────────────────────

    /** Recall on selected results (after reranking). */
    public static double selectedRecallAtK(List<RagEvalCaseResult> results, int k) {
        return recallAtK(results, k);
    }

    /** nDCG on selected results (after reranking). */
    public static double selectedNdcgAtK(List<RagEvalCaseResult> results, int k) {
        return ndcgAtK(results, k);
    }

    // ── Reranker quality metrics ────────────────────────────────────────────

    /**
     * RerankGain@K: selected nDCG - candidate nDCG.
     * Positive means reranker improved ranking; negative means it degraded.
     */
    public static double rerankGainAtK(List<RagEvalCaseResult> results, int k) {
        if (results == null || results.isEmpty()) return 0.0;
        return selectedNdcgAtK(results, k) - candidateNdcgAtK(results, k);
    }

    /** nDCG on candidate results (before reranking). */
    private static double candidateNdcgAtK(List<RagEvalCaseResult> results, int k) {
        return macroAverage(results, k, true, RagEvalMetrics::ndcgForCase);
    }

    /**
     * RelevantDropRate: fraction of queries where candidates contain at least one
     * relevant result but after reranking the selected set contains none.
     */
    public static double relevantDropRate(List<RagEvalCaseResult> results) {
        if (results == null || results.isEmpty()) return 0.0;
        int total = 0;
        int drops = 0;
        for (RagEvalCaseResult r : results) {
            if (!r.success()) continue;
            if (r.expectedEmpty()) continue;
            total++;
            Map<Long, Integer> grades = r.gradeMap();
            boolean candidateHasRelevant = hasAnyRelevant(r.candidateIds(), grades, Integer.MAX_VALUE);
            boolean selectedHasRelevant = hasAnyRelevant(r.selectedIds(), grades, Integer.MAX_VALUE);
            if (candidateHasRelevant && !selectedHasRelevant) {
                drops++;
            }
        }
        return total == 0 ? 0.0 : (double) drops / total;
    }

    // ── Safety / exclusion metrics ──────────────────────────────────────────

    /**
     * EmptyResultAccuracy: for cases annotated as expectedEmpty=true,
     * fraction where the system returned empty (or no relevant results).
     */
    public static double emptyResultAccuracy(List<RagEvalCaseResult> results) {
        if (results == null || results.isEmpty()) return 0.0;
        int emptyCases = 0;
        int correct = 0;
        for (RagEvalCaseResult r : results) {
            if (!r.success()) continue;
            if (!r.expectedEmpty()) continue;
            emptyCases++;
            if (r.selectedIds().isEmpty()) {
                correct++;
            }
        }
        return emptyCases == 0 ? 0.0 : (double) correct / emptyCases;
    }

    /**
     * ForbiddenResultRate: fraction of cases (excluding expectedEmpty) where
     * at least one mustNotReturn picture appears in the top K selected results.
     */
    public static double forbiddenResultRate(List<RagEvalCaseResult> results) {
        if (results == null || results.isEmpty()) return 0.0;
        int total = 0;
        int violations = 0;
        for (RagEvalCaseResult r : results) {
            if (!r.success()) continue;
            if (r.expectedEmpty()) continue;
            total++;
            Set<Long> mustNotReturnSet = Set.copyOf(r.mustNotReturn());
            int checkK = Math.min(r.selectedIds().size(), 5); // always check top 5
            for (int i = 0; i < checkK; i++) {
                if (mustNotReturnSet.contains(r.selectedIds().get(i))) {
                    violations++;
                    break;
                }
            }
        }
        return total == 0 ? 0.0 : (double) violations / total;
    }

    // ── Per-group metrics ───────────────────────────────────────────────────

    /**
     * Compute all metrics grouped by {@link RagEvalCaseResult#group()}.
     * Returns a map of group name to {@link GroupMetrics}.
     */
    public static Map<String, GroupMetrics> groupMetrics(List<RagEvalCaseResult> results, int k) {
        if (results == null || results.isEmpty()) return Collections.emptyMap();

        Map<String, List<RagEvalCaseResult>> byGroup = new LinkedHashMap<>();
        for (RagEvalCaseResult r : results) {
            String group = r.group() != null ? r.group() : "ungrouped";
            byGroup.computeIfAbsent(group, g -> new ArrayList<>()).add(r);
        }

        Map<String, GroupMetrics> out = new LinkedHashMap<>();
        for (var entry : byGroup.entrySet()) {
            List<RagEvalCaseResult> groupResults = entry.getValue();
            List<String> caseIds = groupResults.stream()
                    .map(RagEvalCaseResult::caseId).toList();
            int n = groupResults.size();
            int successCount = (int) groupResults.stream().filter(RagEvalCaseResult::success).count();
            int failCount = n - successCount;

            EvalSummary summary = new EvalSummary(
                    hitRateAtK(groupResults, k),
                    recallAtK(groupResults, k),
                    precisionAtK(groupResults, k),
                    mrrAtK(groupResults, k),
                    ndcgAtK(groupResults, k),
                    candidateRecallAtK(groupResults, k),
                    selectedRecallAtK(groupResults, k),
                    selectedNdcgAtK(groupResults, k),
                    rerankGainAtK(groupResults, k),
                    relevantDropRate(groupResults),
                    emptyResultAccuracy(groupResults),
                    forbiddenResultRate(groupResults),
                    n, successCount, failCount, k
            );
            out.put(entry.getKey(), new GroupMetrics(summary, caseIds));
        }
        return out;
    }

    // ── Internal: macro-average helpers ─────────────────────────────────────

    @FunctionalInterface
    private interface CaseMetricFn {
        double compute(RagEvalCaseResult r, int k, boolean useCandidate);
    }

    private static double macroAverage(List<RagEvalCaseResult> results, int k,
                                        boolean useCandidate, CaseMetricFn fn) {
        if (results == null || results.isEmpty()) return 0.0;
        double sum = 0.0;
        int count = 0;
        for (RagEvalCaseResult r : results) {
            if (!r.success()) continue;
            if (r.expectedEmpty()) continue;
            // skip cases with no annotated relevant pictures for recall/ndcg
            if (r.relevantPictures().isEmpty()) continue;
            sum += fn.compute(r, k, useCandidate);
            count++;
        }
        return count == 0 ? 0.0 : sum / count;
    }

    @FunctionalInterface
    private interface AggregateFn {
        boolean test(RagEvalCaseResult r, int k, boolean useCandidate);
    }

    private static double aggregateMetric(List<RagEvalCaseResult> results, int k,
                                           boolean useCandidate, AggregateFn fn) {
        if (results == null || results.isEmpty()) return 0.0;
        int total = 0;
        int hits = 0;
        for (RagEvalCaseResult r : results) {
            if (!r.success()) continue;
            if (r.expectedEmpty()) continue;
            total++;
            if (fn.test(r, k, useCandidate)) hits++;
        }
        return total == 0 ? 0.0 : (double) hits / total;
    }

    // ── Internal: per-case metric computations ──────────────────────────────

    private static double recallForCase(RagEvalCaseResult r, int k, boolean useCandidate) {
        List<Long> ids = useCandidate ? r.candidateIds() : r.selectedIds();
        Map<Long, Integer> grades = r.gradeMap();
        int totalRelevant = (int) r.relevantPictures().stream()
                .filter(RelevanceJudgment::isRelevant).count();
        if (totalRelevant == 0) return 0.0;
        java.util.Set<Long> foundSet = new java.util.HashSet<>();
        int limit = Math.min(ids.size(), k);
        for (int i = 0; i < limit; i++) {
            Integer grade = grades.get(ids.get(i));
            if (grade != null && grade >= 1) foundSet.add(ids.get(i));
        }
        return (double) foundSet.size() / totalRelevant;
    }

    private static double precisionForCase(RagEvalCaseResult r, int k, boolean useCandidate) {
        List<Long> ids = useCandidate ? r.candidateIds() : r.selectedIds();
        Map<Long, Integer> grades = r.gradeMap();
        int limit = Math.min(ids.size(), k);
        if (limit == 0) return 0.0;
        int relevant = 0;
        for (int i = 0; i < limit; i++) {
            Integer grade = grades.get(ids.get(i));
            if (grade != null && grade >= 1) relevant++;
        }
        return (double) relevant / limit;
    }

    private static double ndcgForCase(RagEvalCaseResult r, int k, boolean useCandidate) {
        List<Long> ids = useCandidate ? r.candidateIds() : r.selectedIds();
        Map<Long, Integer> grades = r.gradeMap();
        if (grades.isEmpty()) return 0.0;

        int limit = Math.min(ids.size(), k);
        double dcg = 0.0;
        for (int i = 0; i < limit; i++) {
            Integer grade = grades.get(ids.get(i));
            if (grade != null && grade > 0) {
                // gain = 2^grade - 1 (standard exponential gain for graded relevance)
                double gain = Math.pow(2, grade) - 1;
                // discount = 1 / log2(rank + 1), where rank is 1-based
                double discount = 1.0 / log2(i + 2);
                dcg += gain * discount;
            }
        }

        // IDCG: ideal ordering — sort all annotated grades descending
        List<Integer> sortedGrades = r.relevantPictures().stream()
                .map(RelevanceJudgment::grade)
                .filter(g -> g > 0)
                .sorted(Collections.reverseOrder())
                .collect(Collectors.toList());

        double idcg = 0.0;
        int idealLimit = Math.min(sortedGrades.size(), k);
        for (int i = 0; i < idealLimit; i++) {
            double gain = Math.pow(2, sortedGrades.get(i)) - 1;
            double discount = 1.0 / log2(i + 2);
            idcg += gain * discount;
        }

        return idcg == 0.0 ? 0.0 : dcg / idcg;
    }

    // ── Internal: utility functions ─────────────────────────────────────────

    /** Check if any relevant (grade>=1) picture appears in the given result list. */
    private static boolean hasAnyRelevant(RagEvalCaseResult r, int k, boolean useCandidate) {
        List<Long> ids = useCandidate ? r.candidateIds() : r.selectedIds();
        return hasAnyRelevant(ids, r.gradeMap(), k);
    }

    private static boolean hasAnyRelevant(List<Long> ids, Map<Long, Integer> grades, int k) {
        int limit = Math.min(ids.size(), k);
        for (int i = 0; i < limit; i++) {
            Integer grade = grades.get(ids.get(i));
            if (grade != null && grade >= 1) return true;
        }
        return false;
    }

    /** Return 1-based rank of the first relevant picture, or 0 if none found. */
    private static double firstRelevantRank(List<Long> ids, Map<Long, Integer> grades, int k) {
        int limit = Math.min(ids.size(), k);
        for (int i = 0; i < limit; i++) {
            Integer grade = grades.get(ids.get(i));
            if (grade != null && grade >= 1) return 1.0 / (i + 1.0); // reciprocal rank
        }
        return 0.0;
    }

    private static double log2(double x) {
        return Math.log(x) / Math.log(2);
    }
}
