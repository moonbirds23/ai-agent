package com.zzp.aiagent.rag.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.zzp.aiagent.domain.rag.RagCandidate;
import com.zzp.aiagent.domain.rag.RagSearchCriteria;
import com.zzp.aiagent.service.HybridGalleryRetriever;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.DisplayName;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

@SpringBootTest
@ActiveProfiles("local")
@Tag("integration")
@EnabledIfSystemProperty(named = "rag.eval.enabled", matches = "true")
@DisplayName("RAG Evaluation Runner")
class RagEvaluationIT {

    @Autowired(required = false)
    private HybridGalleryRetriever retriever;

    private final ObjectMapper mapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    @Test
    @DisplayName("fixed-retrieval mode: 使用人工 criteria 跑检索 + 计算指标")
    void runFixedRetrievalEval() throws Exception {
        if (retriever == null) {
            throw new IllegalStateException("HybridGalleryRetriever bean not available");
        }

        // Load dataset
        RagEvalDatasetLoader loader = RagEvalDatasetLoader.fromClasspath("rag-eval/gallery-v1");
        var validation = loader.loadAndValidate();
        if (validation.hasErrors()) {
            throw new IllegalStateException("Dataset validation failed: " + validation.errors());
        }
        List<RagEvalCase> cases = loader.loadCases();
        var manifest = loader.getManifest();
        System.out.println("[Eval] Loaded " + cases.size() + " cases, "
                + manifest.pictureCount() + " pictures in manifest");
        if (!validation.warnings().isEmpty()) {
            System.out.println("[Eval] Warnings: " + String.join("; ", validation.warnings()));
        }

        // Run retrieval for each case
        List<RagEvalCaseResult> results = new ArrayList<>();
        long totalLatency = 0;

        for (RagEvalCase c : cases) {
            long start = System.currentTimeMillis();
            try {
                RagSearchCriteria criteria = c.fixedCriteria();
                List<RagCandidate> candidates = retriever.retrieve(criteria);

                long latency = System.currentTimeMillis() - start;
                totalLatency += latency;

                List<Long> candidateIds = candidates.stream()
                        .map(r -> r.picture().id())
                        .toList();
                results.add(new RagEvalCaseResult(
                        c.caseId(), c.group(), c.query(), c.expectedEmpty(),
                        candidateIds, candidateIds,
                        c.relevantPictures(), c.mustNotReturn(),
                        true, null));

                System.out.printf("[Eval] %s: %d candidates in %dms%n",
                        c.caseId(), candidateIds.size(), latency);
            } catch (Exception e) {
                System.err.printf("[Eval] %s FAILED: %s%n", c.caseId(), e.getMessage());
                results.add(new RagEvalCaseResult(
                        c.caseId(), c.group(), c.query(), c.expectedEmpty(),
                        List.of(), List.of(),
                        c.relevantPictures(), c.mustNotReturn(),
                        false, e.getMessage()));
            }
        }

        // Build report (Builder auto-computes all metrics from caseResults)
        int k = 5;
        Map<String, Object> ragConfig = new LinkedHashMap<>();
        ragConfig.put("mode", "fixed-retrieval");

        RagEvalReport report = new RagEvalReport.Builder(k)
                .datasetVersion(manifest.datasetVersion())
                .gitCommit(getGitCommit())
                .embeddingModel(manifest.embeddingModel())
                .ragConfig(ragConfig)
                .mode("fixed-retrieval")
                .mcpEnabled(false)
                .caseResults(results)
                .build();

        // Output directory
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path outDir = Path.of("target/rag-eval", timestamp);
        Files.createDirectories(outDir);

        // Write report.json
        Path jsonPath = outDir.resolve("report.json");
        mapper.writeValue(jsonPath.toFile(), report);
        System.out.println("[Eval] report.json → " + jsonPath.toAbsolutePath());

        // Write report.md
        Path mdPath = outDir.resolve("report.md");
        Files.writeString(mdPath, formatMarkdown(report));
        System.out.println("[Eval] report.md → " + mdPath.toAbsolutePath());

        // Write case-results.csv
        Path csvPath = outDir.resolve("case-results.csv");
        StringBuilder csv = new StringBuilder("caseId,group,query,expectedEmpty,candidateCount,success\n");
        for (RagEvalCaseResult r : results) {
            csv.append(String.format("%s,%s,\"%s\",%s,%d,%s%n",
                    r.caseId(), r.group(), r.query().replace("\"", "\"\""),
                    r.expectedEmpty(), r.candidateIds().size(), r.success()));
        }
        Files.writeString(csvPath, csv.toString());
        System.out.println("[Eval] case-results.csv → " + csvPath.toAbsolutePath());

        // Print summary
        EvalSummary o = report.overall();
        System.out.println("\n========================================");
        System.out.println("  RAG Evaluation Results (fixed-retrieval)");
        System.out.println("========================================");
        System.out.printf("  Cases:       %d / %d successful%n", o.successfulCases(), o.totalCases());
        System.out.printf("  HitRate@K:   %.4f%n", o.hitRateAtK());
        System.out.printf("  Recall@K:    %.4f%n", o.recallAtK());
        System.out.printf("  Precision@K: %.4f%n", o.precisionAtK());
        System.out.printf("  MRR@K:       %.4f%n", o.mrrAtK());
        System.out.printf("  nDCG@K:      %.4f%n", o.ndcgAtK());
        System.out.printf("  CandRecall:  %.4f%n", o.candidateRecallAtK());
        System.out.printf("  DropRate:    %.4f%n", o.relevantDropRate());
        System.out.printf("  EmptyAcc:    %.4f%n", o.emptyResultAccuracy());
        System.out.printf("  ForbidRate:  %.4f%n", o.forbiddenResultRate());
        System.out.println("========================================");

        // Write baseline
        Path baselinePath = Path.of("src/test/resources/rag-eval/gallery-v1/baseline.json");
        boolean isFirstRun = !Files.exists(baselinePath);
        mapper.writeValue(baselinePath.toFile(), report);
        System.out.println("[Eval] " + (isFirstRun ? "First run! Baseline generated." : "Baseline updated.")
                + " → " + baselinePath.toAbsolutePath());
    }

    private static String getGitCommit() {
        try {
            Process p = Runtime.getRuntime().exec("git rev-parse --short HEAD");
            String sha = new String(p.getInputStream().readAllBytes()).trim();
            return sha.isEmpty() ? "unknown" : sha;
        } catch (Exception e) {
            return "unknown";
        }
    }

    private static String formatMarkdown(RagEvalReport r) {
        EvalSummary o = r.overall();
        StringBuilder sb = new StringBuilder();
        sb.append("# RAG Evaluation Report\n\n");
        sb.append("| Item | Value |\n|------|-------|\n");
        sb.append("| Dataset | ").append(r.datasetVersion()).append(" |\n");
        sb.append("| Embedding | ").append(r.embeddingModel()).append(" |\n");
        sb.append("| Mode | ").append(r.mode()).append(" |\n");
        sb.append("| MCP Enabled | ").append(r.mcpEnabled()).append(" |\n");
        sb.append("| Git Commit | ").append(r.gitCommit()).append(" |\n\n");

        sb.append("## Overall Metrics (K=").append(o.k()).append(")\n\n");
        sb.append("| Metric | Value |\n|--------|-------|\n");
        sb.append(String.format("| HitRate@%d | %.4f |\n", o.k(), o.hitRateAtK()));
        sb.append(String.format("| Recall@%d | %.4f |\n", o.k(), o.recallAtK()));
        sb.append(String.format("| Precision@%d | %.4f |\n", o.k(), o.precisionAtK()));
        sb.append(String.format("| MRR@%d | %.4f |\n", o.k(), o.mrrAtK()));
        sb.append(String.format("| nDCG@%d | %.4f |\n", o.k(), o.ndcgAtK()));
        sb.append(String.format("| CandidateRecall | %.4f |\n", o.candidateRecallAtK()));
        sb.append(String.format("| SelectedRecall | %.4f |\n", o.selectedRecallAtK()));
        sb.append(String.format("| RerankGain | %.4f |\n", o.rerankGainAtK()));
        sb.append(String.format("| RelevantDropRate | %.4f |\n", o.relevantDropRate()));
        sb.append(String.format("| EmptyResultAccuracy | %.4f |\n", o.emptyResultAccuracy()));
        sb.append(String.format("| ForbiddenResultRate | %.4f |\n\n", o.forbiddenResultRate()));

        if (!r.failedCases().isEmpty()) {
            sb.append("## Failed Cases\n\n");
            for (String cid : r.failedCases()) {
                sb.append("- ").append(cid).append("\n");
            }
        }

        if (!r.byGroup().isEmpty()) {
            sb.append("\n## By Group\n\n");
            sb.append("| Group | HitRate | Recall | nDCG | Cases |\n");
            sb.append("|-------|---------|--------|------|-------|\n");
            for (var e : r.byGroup().entrySet()) {
                var g = e.getValue();
                sb.append(String.format("| %s | %.4f | %.4f | %.4f | %d |\n",
                        e.getKey(), g.hitRateAtK(), g.recallAtK(), g.ndcgAtK(), g.totalCases()));
            }
        }

        return sb.toString();
    }
}
