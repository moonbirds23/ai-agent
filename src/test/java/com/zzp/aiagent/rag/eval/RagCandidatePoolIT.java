package com.zzp.aiagent.rag.eval;

import com.zzp.aiagent.service.HybridGalleryRetriever;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for candidate pool generation.
 *
 * <p>Requires a running PostgreSQL with pgvector and gallery data.
 * Only runs when {@code -Drag.pool.enabled=true} is set.
 * Output files go to {@code target/rag-eval/}.
 */
@SpringBootTest
@ActiveProfiles("local")
@Tag("integration")
@EnabledIfSystemProperty(named = "rag.pool.enabled", matches = "true")
@DisplayName("Candidate Pool Generator IT")
class RagCandidatePoolIT {

    @Autowired(required = false)
    private HybridGalleryRetriever hybridRetriever;

    @Test
    @DisplayName("Generate candidate pool HTML and CSV")
    void generateCandidatePool() throws Exception {
        assertThat(hybridRetriever)
                .as("HybridGalleryRetriever should be available for candidate pool generation. "
                        + "Ensure PostgreSQL is running with gallery data.")
                .isNotNull();

        RagEvalDatasetLoader loader = RagEvalDatasetLoader.fromClasspath("rag-eval/gallery-v1");
        loader.loadCases(); // validate JSONL parses correctly

        Path outputDir = Path.of("target", "rag-eval");

        CandidatePoolGenerator.GenerateResult result =
                CandidatePoolGenerator.generate(loader, hybridRetriever, outputDir);

        assertThat(result.htmlPath()).exists().isRegularFile();
        assertThat(result.csvPath()).exists().isRegularFile();
        assertThat(result.caseCount()).isGreaterThan(0);
        assertThat(result.totalCandidates()).isGreaterThanOrEqualTo(0);

        System.out.println("Candidate pool generated:");
        System.out.println("  HTML: " + result.htmlPath().toAbsolutePath());
        System.out.println("  CSV:  " + result.csvPath().toAbsolutePath());
        System.out.println("  Cases: " + result.caseCount() + ", Total candidates: " + result.totalCandidates());
    }
}
