package com.zzp.aiagent.rag.eval;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

/**
 * Pure unit tests for {@link RagEvalMetrics}. No Spring context, no DB, no model calls.
 *
 * <p>Covers all 11 scenarios: perfect ranking, first-hit, position-3 hit,
 * complete miss, multiple relevant, graded relevance, empty annotation set,
 * empty system results, duplicate IDs, K exceeding result size, and expectedEmpty queries.
 */
@DisplayName("RagEvalMetrics：检索评测指标")
@Tag("unit")
class RagEvalMetricsTest {

    private static final int K = 3;

    // ── Test helpers ────────────────────────────────────────────────────────

    /** Build a result with the given selected IDs. */
    private static RagEvalCaseResult result(String caseId, String group, List<Long> selectedIds,
                                             List<RelevanceJudgment> relevant,
                                             List<Long> mustNotReturn, boolean expectedEmpty) {
        return new RagEvalCaseResult(caseId, group, "query for " + caseId, expectedEmpty,
                selectedIds,  // candidates = selected for simplicity
                selectedIds,
                relevant, mustNotReturn, true, null);
    }

    /** Build a result with separate candidate and selected IDs. */
    private static RagEvalCaseResult resultWithCandidates(String caseId, String group,
                                                           List<Long> candidateIds,
                                                           List<Long> selectedIds,
                                                           List<RelevanceJudgment> relevant,
                                                           List<Long> mustNotReturn,
                                                           boolean expectedEmpty) {
        return new RagEvalCaseResult(caseId, group, "query for " + caseId, expectedEmpty,
                candidateIds, selectedIds, relevant, mustNotReturn, true, null);
    }

    private static RelevanceJudgment rel(long pictureId, int grade) {
        return new RelevanceJudgment(pictureId, "hash-" + pictureId, grade);
    }

    private static List<RagEvalCaseResult> single(RagEvalCaseResult r) {
        return List.of(r);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Scenario 1: 完美排序 — all grade-3 pictures ranked first
    // ══════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("Scenario 1: 完美排序")
    class PerfectRanking {

        @Test
        @DisplayName("所有等级3图片排在前列 → 全部指标为1.0")
        void allRelevantRankedFirst() {
            List<RelevanceJudgment> judgments = List.of(
                    rel(1L, 3), rel(2L, 3), rel(3L, 3)
            );
            RagEvalCaseResult r = result("s1", "test", List.of(1L, 2L, 3L), judgments, List.of(), false);

            assertThat(RagEvalMetrics.hitRateAtK(single(r), K)).isCloseTo(1.0, offset(0.001));
            assertThat(RagEvalMetrics.recallAtK(single(r), K)).isCloseTo(1.0, offset(0.001));
            assertThat(RagEvalMetrics.precisionAtK(single(r), K)).isCloseTo(1.0, offset(0.001));
            assertThat(RagEvalMetrics.mrrAtK(single(r), K)).isCloseTo(1.0, offset(0.001)); // rank 1
            assertThat(RagEvalMetrics.ndcgAtK(single(r), K)).isCloseTo(1.0, offset(0.001));
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Scenario 2: 第一张命中 — first picture is relevant
    // ══════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("Scenario 2: 第一张命中")
    class FirstHit {

        @Test
        @DisplayName("第一张相关 → HitRate=1.0, MRR=1.0")
        void firstPictureRelevant() {
            List<RelevanceJudgment> judgments = List.of(rel(1L, 3), rel(99L, 3));
            // result list: 1 is relevant, 5 and 7 are not annotated
            RagEvalCaseResult r = result("s2", "test", List.of(1L, 5L, 7L), judgments, List.of(), false);

            assertThat(RagEvalMetrics.hitRateAtK(single(r), K)).isCloseTo(1.0, offset(0.001));
            assertThat(RagEvalMetrics.mrrAtK(single(r), K)).isCloseTo(1.0, offset(0.001)); // rank 1
            // recall: 1 found / 2 total = 0.5
            assertThat(RagEvalMetrics.recallAtK(single(r), K)).isCloseTo(0.5, offset(0.001));
            // precision: 1 relevant / 3 results = 0.333
            assertThat(RagEvalMetrics.precisionAtK(single(r), K)).isCloseTo(1.0 / 3.0, offset(0.001));
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Scenario 3: 第三张命中 — first relevant at position 3
    // ══════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("Scenario 3: 第三张命中")
    class ThirdHit {

        @Test
        @DisplayName("第一张相关图排在第3位 → MRR=1/3")
        void firstRelevantAtPosition3() {
            List<RelevanceJudgment> judgments = List.of(rel(3L, 3));
            RagEvalCaseResult r = result("s3", "test", List.of(5L, 7L, 3L), judgments, List.of(), false);

            assertThat(RagEvalMetrics.hitRateAtK(single(r), K)).isCloseTo(1.0, offset(0.001));
            assertThat(RagEvalMetrics.mrrAtK(single(r), K)).isCloseTo(1.0 / 3.0, offset(0.001));
            assertThat(RagEvalMetrics.recallAtK(single(r), K)).isCloseTo(1.0, offset(0.001));
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Scenario 4: 完全未命中 — no relevant pictures in results
    // ══════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("Scenario 4: 完全未命中")
    class CompleteMiss {

        @Test
        @DisplayName("相关图都不在结果中 → HitRate=0, Recall=0, MRR=0")
        void noRelevantInResults() {
            List<RelevanceJudgment> judgments = List.of(rel(99L, 3));
            RagEvalCaseResult r = result("s4", "test", List.of(1L, 2L, 3L), judgments, List.of(), false);

            assertThat(RagEvalMetrics.hitRateAtK(single(r), K)).isCloseTo(0.0, offset(0.001));
            assertThat(RagEvalMetrics.recallAtK(single(r), K)).isCloseTo(0.0, offset(0.001));
            assertThat(RagEvalMetrics.mrrAtK(single(r), K)).isCloseTo(0.0, offset(0.001));
            assertThat(RagEvalMetrics.precisionAtK(single(r), K)).isCloseTo(0.0, offset(0.001));
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Scenario 5: 多张相关图片 — multiple relevant with different grades
    // ══════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("Scenario 5: 多张相关图片不同等级")
    class MultipleRelevant {

        @Test
        @DisplayName("混合等级 → nDCG 反映排序质量")
        void multipleRelevantMixedGrades() {
            List<RelevanceJudgment> judgments = List.of(
                    rel(1L, 3), rel(2L, 2), rel(5L, 1), rel(6L, 3)
            );
            // sorted: 5(grade=1), 1(grade=3), 7(not annotated), 2(grade=2) not in top K=3
            RagEvalCaseResult r = result("s5", "test", List.of(5L, 1L, 7L), judgments, List.of(), false);

            // HitRate: 5 and 1 are relevant → yes
            assertThat(RagEvalMetrics.hitRateAtK(single(r), K)).isCloseTo(1.0, offset(0.001));
            // Recall: found (5,1) = 2 / 4 total = 0.5
            assertThat(RagEvalMetrics.recallAtK(single(r), K)).isCloseTo(0.5, offset(0.001));
            // Precision: (5,1) = 2 relevant / 3 = 0.667
            assertThat(RagEvalMetrics.precisionAtK(single(r), K)).isCloseTo(2.0 / 3.0, offset(0.001));

            // nDCG: dcg = gain(5)/log2(2) + gain(1)/log2(3)
            //   gain(5)=2^1-1=1, gain(1)=2^3-1=7
            //   dcg = 1/1 + 7/1.585 = 1 + 4.416 = 5.416
            // idcg: sorted grades = [3,3,2] → gain=7,7,3
            //   idcg = 7/1 + 7/1.585 + 3/2 = 7 + 4.416 + 1.5 = 12.916
            // ndcg = 5.416/12.916 = 0.419
            assertThat(RagEvalMetrics.ndcgAtK(single(r), K)).isCloseTo(0.419, offset(0.01));
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Scenario 6: 分级相关性 — mix of grade 0/1/2/3
    // ══════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("Scenario 6: 分级相关性（含grade=0）")
    class GradedRelevance {

        @Test
        @DisplayName("grade=0 不计入 recall/precision/MRR")
        void gradeZeroNotCounted() {
            List<RelevanceJudgment> judgments = List.of(
                    rel(1L, 0), rel(2L, 3), rel(3L, 0)
            );
            RagEvalCaseResult r = result("s6", "test", List.of(1L, 2L, 3L), judgments, List.of(), false);

            // HitRate: 2 is relevant → yes
            assertThat(RagEvalMetrics.hitRateAtK(single(r), K)).isCloseTo(1.0, offset(0.001));
            // MRR: first relevant is at position 2 → 1/2 = 0.5
            assertThat(RagEvalMetrics.mrrAtK(single(r), K)).isCloseTo(0.5, offset(0.001));
            // Recall: only 1 relevant (grade>=1) → found 1/1 = 1.0
            assertThat(RagEvalMetrics.recallAtK(single(r), K)).isCloseTo(1.0, offset(0.001));
        }

        @Test
        @DisplayName("nDCG 按等级加权计算")
        void ndcgUsesGradedRelevance() {
            List<RelevanceJudgment> judgments = List.of(
                    rel(1L, 1), rel(2L, 2), rel(3L, 3)
            );
            // sorted results: 1(1), 2(2), 3(3) — ascending grades (suboptimal)
            RagEvalCaseResult r = result("s6b", "test", List.of(1L, 2L, 3L), judgments, List.of(), false);

            // dcg: gain(1)=1/log2(2)=1, gain(2)=3/log2(3)=1.893, gain(3)=7/log2(4)=3.5 → 6.393
            // idcg: sorted [3,2,1] → 7/1 + 3/1.585 + 1/2 = 7 + 1.893 + 0.5 = 9.393
            // ndcg = 6.393 / 9.393 = 0.681
            assertThat(RagEvalMetrics.ndcgAtK(single(r), K)).isCloseTo(0.681, offset(0.01));
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Scenario 7: 空相关集 — no relevant pictures annotated
    // ══════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("Scenario 7: 空相关集")
    class EmptyRelevance {

        @Test
        @DisplayName("无标注相关图 → Recall/Precision/nDCG 返回 0")
        void noAnnotations() {
            RagEvalCaseResult r = result("s7", "test", List.of(1L, 2L, 3L),
                    List.of(), List.of(), false);

            // recall/precision/ndcg skip cases with no annotations → no cases counted → 0
            assertThat(RagEvalMetrics.recallAtK(single(r), K)).isEqualTo(0.0);
            assertThat(RagEvalMetrics.precisionAtK(single(r), K)).isEqualTo(0.0);
            assertThat(RagEvalMetrics.ndcgAtK(single(r), K)).isEqualTo(0.0);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Scenario 8: 空系统结果 — system returned nothing
    // ══════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("Scenario 8: 空系统结果")
    class EmptySystemResults {

        @Test
        @DisplayName("系统返回空 → 所有指标为0")
        void systemReturnedEmpty() {
            List<RelevanceJudgment> judgments = List.of(rel(1L, 3));
            RagEvalCaseResult r = result("s8", "test", List.of(), judgments, List.of(), false);

            assertThat(RagEvalMetrics.hitRateAtK(single(r), K)).isCloseTo(0.0, offset(0.001));
            assertThat(RagEvalMetrics.recallAtK(single(r), K)).isCloseTo(0.0, offset(0.001));
            assertThat(RagEvalMetrics.precisionAtK(single(r), K)).isCloseTo(0.0, offset(0.001));
            assertThat(RagEvalMetrics.mrrAtK(single(r), K)).isCloseTo(0.0, offset(0.001));
            assertThat(RagEvalMetrics.ndcgAtK(single(r), K)).isCloseTo(0.0, offset(0.001));
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Scenario 9: 重复图片ID — duplicate IDs in results
    // ══════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("Scenario 9: 重复图片ID")
    class DuplicateIds {

        @Test
        @DisplayName("重复ID → 每出现一次算一次命中（真实场景应去重）")
        void duplicateIdsCountedEach() {
            List<RelevanceJudgment> judgments = List.of(rel(1L, 3));
            // 1 appears 3 times — metrics count each occurrence
            RagEvalCaseResult r = result("s9", "test", List.of(1L, 1L, 1L), judgments, List.of(), false);

            // precision: 3 relevant / 3 = 1.0
            assertThat(RagEvalMetrics.precisionAtK(single(r), K)).isCloseTo(1.0, offset(0.001));
            // recall: 3 found / 1 total → capped at 1.0
            assertThat(RagEvalMetrics.recallAtK(single(r), K)).isCloseTo(1.0, offset(0.001));
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Scenario 10: K大于返回结果数量 — K exceeds result count
    // ══════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("Scenario 10: K > 返回结果数")
    class KExceedsResults {

        @Test
        @DisplayName("K=10 but only 3 results → 按实际结果数计算")
        void kExceedsResultCount() {
            List<RelevanceJudgment> judgments = List.of(rel(1L, 3), rel(2L, 3));
            RagEvalCaseResult r = result("s10", "test", List.of(1L), judgments, List.of(), false);

            int largeK = 10;
            // precision: 1 relevant / min(1,10) = 1/1 = 1.0
            assertThat(RagEvalMetrics.precisionAtK(single(r), largeK)).isCloseTo(1.0, offset(0.001));
            // recall: 1 found / 2 total = 0.5
            assertThat(RagEvalMetrics.recallAtK(single(r), largeK)).isCloseTo(0.5, offset(0.001));
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Scenario 11: expectedEmpty Query
    // ══════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("Scenario 11: expectedEmpty 查询")
    class ExpectedEmptyQuery {

        @Test
        @DisplayName("expectedEmpty=true且返回空 → emptyResultAccuracy=1.0")
        void emptyQueryCorrectlyReturnsEmpty() {
            RagEvalCaseResult r1 = result("s11a", "test", List.of(), List.of(), List.of(), true);
            RagEvalCaseResult r2 = result("s11b", "normal", List.of(1L),
                    List.of(rel(1L, 3)), List.of(), false);

            assertThat(RagEvalMetrics.emptyResultAccuracy(List.of(r1, r2))).isCloseTo(1.0, offset(0.001));
        }

        @Test
        @DisplayName("expectedEmpty=true但返回了结果 → emptyResultAccuracy=0.0")
        void emptyQueryIncorrectlyReturnsResults() {
            RagEvalCaseResult r = result("s11c", "test", List.of(1L, 2L), List.of(), List.of(), true);

            assertThat(RagEvalMetrics.emptyResultAccuracy(single(r))).isCloseTo(0.0, offset(0.001));
        }

        @Test
        @DisplayName("expectedEmpty 查询不影响 HitRate/Recall/nDCG 计算")
        void expectedEmptyExcludedFromRetrievalMetrics() {
            List<RelevanceJudgment> judgments = List.of(rel(1L, 3));
            RagEvalCaseResult normal = result("norm", "test", List.of(1L), judgments, List.of(), false);
            RagEvalCaseResult empty = result("empty", "test", List.of(), List.of(), List.of(), true);

            List<RagEvalCaseResult> all = List.of(normal, empty);
            // only normal case is counted
            assertThat(RagEvalMetrics.hitRateAtK(all, K)).isCloseTo(1.0, offset(0.001));
            assertThat(RagEvalMetrics.recallAtK(all, K)).isCloseTo(1.0, offset(0.001));
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Additional metrics tests
    // ══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("rerankGain")
    class RerankGain {

        @Test
        @DisplayName("rerank改善了排序 → rerankGain>0")
        void rerankImproves() {
            List<RelevanceJudgment> judgments = List.of(rel(1L, 3), rel(2L, 2));
            // candidates: [2,1] suboptimal; selected: [1,2] improved
            RagEvalCaseResult r = resultWithCandidates("rg1", "test",
                    List.of(2L, 1L), List.of(1L, 2L), judgments, List.of(), false);

            assertThat(RagEvalMetrics.rerankGainAtK(List.of(r), 2)).isGreaterThan(0.0);
        }

        @Test
        @DisplayName("rerank降级了排序 → rerankGain<0")
        void rerankDegrades() {
            List<RelevanceJudgment> judgments = List.of(rel(1L, 3), rel(2L, 2));
            // candidates: [1,2] good; selected: [2,1] worse
            RagEvalCaseResult r = resultWithCandidates("rg2", "test",
                    List.of(1L, 2L), List.of(2L, 1L), judgments, List.of(), false);

            assertThat(RagEvalMetrics.rerankGainAtK(List.of(r), 2)).isLessThan(0.0);
        }
    }

    @Nested
    @DisplayName("relevantDropRate")
    class RelevantDropRateTests {

        @Test
        @DisplayName("candidates有相关但selected没有 → drop")
        void candidateHasRelevantButSelectedDoesNot() {
            List<RelevanceJudgment> judgments = List.of(rel(1L, 3));
            RagEvalCaseResult r = resultWithCandidates("drop1", "test",
                    List.of(1L, 2L), List.of(3L, 4L), judgments, List.of(), false);

            assertThat(RagEvalMetrics.relevantDropRate(List.of(r))).isCloseTo(1.0, offset(0.001));
        }

        @Test
        @DisplayName("selected保留了相关 → no drop")
        void selectedRetainsRelevant() {
            List<RelevanceJudgment> judgments = List.of(rel(1L, 3));
            RagEvalCaseResult r = resultWithCandidates("drop2", "test",
                    List.of(1L, 2L), List.of(2L, 1L), judgments, List.of(), false);

            assertThat(RagEvalMetrics.relevantDropRate(List.of(r))).isCloseTo(0.0, offset(0.001));
        }
    }

    @Nested
    @DisplayName("forbiddenResultRate")
    class ForbiddenResultRateTests {

        @Test
        @DisplayName("mustNotReturn 图片出现在前5 → 违规")
        void forbiddenAppears() {
            List<RelevanceJudgment> judgments = List.of(rel(1L, 3));
            RagEvalCaseResult r = result("fr1", "test", List.of(3L, 1L, 2L),
                    judgments, List.of(3L), false);

            assertThat(RagEvalMetrics.forbiddenResultRate(List.of(r))).isCloseTo(1.0, offset(0.001));
        }

        @Test
        @DisplayName("mustNotReturn 图片不在结果中 → 通过")
        void forbiddenDoesNotAppear() {
            List<RelevanceJudgment> judgments = List.of(rel(1L, 3));
            RagEvalCaseResult r = result("fr2", "test", List.of(1L, 2L, 3L),
                    judgments, List.of(99L), false);

            assertThat(RagEvalMetrics.forbiddenResultRate(List.of(r))).isCloseTo(0.0, offset(0.001));
        }
    }

    @Nested
    @DisplayName("groupMetrics")
    class GroupMetricsTests {

        @Test
        @DisplayName("按group分组计算指标")
        void groupsSeparated() {
            List<RelevanceJudgment> judgments = List.of(rel(1L, 3));
            RagEvalCaseResult r1 = result("g1", "groupA", List.of(1L), judgments, List.of(), false);
            RagEvalCaseResult r2 = result("g2", "groupA", List.of(2L), judgments, List.of(), false);
            RagEvalCaseResult r3 = result("g3", "groupB", List.of(1L), judgments, List.of(), false);

            List<RagEvalCaseResult> all = List.of(r1, r2, r3);
            Map<String, GroupMetrics> gm = RagEvalMetrics.groupMetrics(all, K);

            assertThat(gm).containsKeys("groupA", "groupB");
            assertThat(gm.get("groupA").caseIds()).containsExactly("g1", "g2");
            assertThat(gm.get("groupB").caseIds()).containsExactly("g3");
        }
    }

    @Nested
    @DisplayName("unsuccessful cases")
    class UnsuccessfulCases {

        @Test
        @DisplayName("success=false 的 case 不计入指标")
        void failedCaseExcluded() {
            List<RelevanceJudgment> judgments = List.of(rel(1L, 3));
            RagEvalCaseResult failed = new RagEvalCaseResult("fail", "test", "q",
                    false, List.of(1L), List.of(1L), judgments, List.of(), false, "error");
            RagEvalCaseResult good = result("good", "test", List.of(1L), judgments, List.of(), false);

            // failed case excluded, good case gives 1.0
            assertThat(RagEvalMetrics.hitRateAtK(List.of(failed, good), K)).isCloseTo(1.0, offset(0.001));
        }
    }

    @Nested
    @DisplayName("empty/null input")
    class EmptyInput {

        @Test
        @DisplayName("null list → 所有指标返回 0")
        void nullInput() {
            assertThat(RagEvalMetrics.hitRateAtK(null, K)).isEqualTo(0.0);
            assertThat(RagEvalMetrics.recallAtK(null, K)).isEqualTo(0.0);
            assertThat(RagEvalMetrics.precisionAtK(null, K)).isEqualTo(0.0);
            assertThat(RagEvalMetrics.mrrAtK(null, K)).isEqualTo(0.0);
            assertThat(RagEvalMetrics.ndcgAtK(null, K)).isEqualTo(0.0);
        }

        @Test
        @DisplayName("empty list → 所有指标返回 0")
        void emptyInput() {
            assertThat(RagEvalMetrics.hitRateAtK(List.of(), K)).isEqualTo(0.0);
            assertThat(RagEvalMetrics.recallAtK(List.of(), K)).isEqualTo(0.0);
            assertThat(RagEvalMetrics.precisionAtK(List.of(), K)).isEqualTo(0.0);
            assertThat(RagEvalMetrics.mrrAtK(List.of(), K)).isEqualTo(0.0);
            assertThat(RagEvalMetrics.ndcgAtK(List.of(), K)).isEqualTo(0.0);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Dataset loader tests
    // ══════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("RagEvalDatasetLoader")
    class DatasetLoader {

        @Test
        @DisplayName("加载 cases.jsonl → 解析出10个启用的case（排除1个disabled）")
        void loadCasesCorrectly() throws Exception {
            RagEvalDatasetLoader loader = RagEvalDatasetLoader.fromClasspath("rag-eval/gallery-v1");
            List<RagEvalCase> cases = loader.loadCases();

            // 10 enabled + 1 disabled = 11 total in file, loader filters to 10
            assertThat(cases).hasSize(10);
            // Verify the disabled case is excluded
            assertThat(cases.stream().map(RagEvalCase::caseId))
                    .doesNotContain("case-011");
            // Verify all enabled cases are present
            assertThat(cases.stream().map(RagEvalCase::caseId))
                    .contains("case-001", "case-002", "case-003", "case-004", "case-005",
                              "case-006", "case-007", "case-008", "case-009", "case-010");
        }

        @Test
        @DisplayName("加载 corpus-manifest.json → 解析出所有图片")
        void loadManifestCorrectly() throws Exception {
            RagEvalDatasetLoader loader = RagEvalDatasetLoader.fromClasspath("rag-eval/gallery-v1");
            var manifest = loader.loadManifest();

            assertThat(manifest.pictureCount()).isGreaterThan(0);
            assertThat(manifest.pictures()).hasSize(manifest.pictureCount());
            assertThat(manifest.embeddingModel()).isEqualTo("embedding-2");
            assertThat(manifest.embeddingDimensions()).isEqualTo(1024);
            assertThat(manifest.datasetVersion()).isEqualTo("gallery-v1");
        }

        @Test
        @DisplayName("校验 cases 和 manifest 交叉引用 → 无错误")
        void validateNoErrors() throws Exception {
            RagEvalDatasetLoader loader = RagEvalDatasetLoader.fromClasspath("rag-eval/gallery-v1");
            var result = loader.loadAndValidate();

            assertThat(result.hasErrors()).isFalse();
            // warnings may exist but shouldn't be critical
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Baseline comparator tests
    // ══════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("RagEvalBaselineComparator")
    class BaselineComparator {

        @Test
        @DisplayName("相同指标 → 通过")
        void identicalReportsPass() {
            RagEvalReport baseline = buildReport(0.8, 0.75, 0.75, 0.0);
            RagEvalReport current = buildReport(0.8, 0.75, 0.75, 0.0);

            var result = RagEvalBaselineComparator.compare(current, baseline);
            assertThat(result.passed()).isTrue();
            assertThat(result.diffs()).isEmpty();
        }

        @Test
        @DisplayName("Recall@5 下降超过0.02 → 失败")
        void recallDropsTooMuch() {
            RagEvalReport baseline = buildReport(0.8, 0.75, 0.75, 0.0);
            RagEvalReport current = buildReport(0.8, 0.72, 0.75, 0.0); // -0.03 on recall

            var result = RagEvalBaselineComparator.compare(current, baseline);
            assertThat(result.passed()).isFalse();
            assertThat(result.diffs()).anyMatch(d -> d.contains("Recall@5"));
        }

        @Test
        @DisplayName("RelevantDropRate 增加 → 失败")
        void dropRateIncreases() {
            RagEvalReport baseline = buildReport(0.8, 0.75, 0.75, 0.0);
            RagEvalReport current = buildReport(0.8, 0.75, 0.75, 0.1); // increased drop rate

            var result = RagEvalBaselineComparator.compare(current, baseline);
            assertThat(result.passed()).isFalse();
            assertThat(result.diffs()).anyMatch(d -> d.contains("RelevantDropRate"));
        }

        @Test
        @DisplayName("指标提升 → 通过")
        void metricsImprovedPass() {
            RagEvalReport baseline = buildReport(0.8, 0.75, 0.75, 0.05);
            RagEvalReport current = buildReport(0.85, 0.78, 0.78, 0.02); // improved and lower drop rate

            var result = RagEvalBaselineComparator.compare(current, baseline);
            assertThat(result.passed()).isTrue();
        }
    }

    private static RagEvalReport buildReport(double hitRate, double recall, double ndcg, double dropRate) {
        EvalSummary summary = new EvalSummary(
                hitRate, recall, 0.6, 0.7, ndcg,
                recall, recall, ndcg, 0.0, dropRate, 0.0, 0.0,
                10, 10, 0, 5
        );
        return new RagEvalReport("v1", "abc1234", "embedding-2",
                Map.of(), "end-to-end", false,
                summary, Map.of(), List.of(), List.of(), LatencyStats.empty());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // RagEvalReport.Builder tests
    // ══════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("RagEvalReport.Builder")
    class ReportBuilder {

        @Test
        @DisplayName("Builder 从 caseResults 自动计算 overall metrics")
        void builderComputesOverall() {
            List<RelevanceJudgment> judgments = List.of(rel(1L, 3));
            RagEvalCaseResult r = result("b1", "test", List.of(1L), judgments, List.of(), false);

            RagEvalReport report = RagEvalReport.forK(3)
                    .datasetVersion("test-v1")
                    .caseResults(List.of(r))
                    .build();

            assertThat(report.datasetVersion()).isEqualTo("test-v1");
            assertThat(report.overall().totalCases()).isEqualTo(1);
            assertThat(report.overall().hitRateAtK()).isCloseTo(1.0, offset(0.001));
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // RelevanceJudgment validation
    // ══════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("RelevanceJudgment")
    class RelevanceJudgmentValidation {

        @Test
        @DisplayName("grade 0-3 合法")
        void validGrades() {
            assertThat(new RelevanceJudgment(1L, "h", 0).grade()).isEqualTo(0);
            assertThat(new RelevanceJudgment(1L, "h", 3).grade()).isEqualTo(3);
        }

        @Test
        @DisplayName("grade<0 或 >3 → 抛异常")
        void invalidGradeThrows() {
            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                    () -> new RelevanceJudgment(1L, "h", -1));
            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                    () -> new RelevanceJudgment(1L, "h", 4));
        }
    }
}
