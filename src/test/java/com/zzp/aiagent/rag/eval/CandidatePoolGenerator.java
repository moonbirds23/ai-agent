package com.zzp.aiagent.rag.eval;

import com.zzp.aiagent.domain.rag.RagCandidate;
import com.zzp.aiagent.domain.rag.RagSearchCriteria;
import com.zzp.aiagent.service.HybridGalleryRetriever;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Generates HTML and CSV candidate pools for manual annotation from RAG eval cases.
 *
 * <p>Workflow:
 * <ol>
 *   <li>Load eval cases via {@link RagEvalDatasetLoader}</li>
 *   <li>For each case, run hybrid retrieval (vector + keyword merge)</li>
 *   <li>Deduplicate by pictureId</li>
 *   <li>Output HTML (with thumbnails, scores, grade radio buttons) and CSV</li>
 * </ol>
 */
public final class CandidatePoolGenerator {

    private CandidatePoolGenerator() { /* utility */ }

    /** Entry for a single candidate in the pool. */
    public record CandidateRow(
            long pictureId,
            String picHash,
            String name,
            String thumbnailUrl,
            String category,
            List<String> tags,
            double vectorScore,
            double keywordScore,
            double metadataScore,
            double finalScore,
            List<String> reasons
    ) {}

    /** A case with its pooled candidates, ready for HTML/CSV output. */
    private record CasePool(String caseId, String query, String notes, List<CandidateRow> candidates) {}

    /**
     * Generate candidate pool from eval cases using the given retriever.
     *
     * @param loader     dataset loader (cases + manifest)
     * @param retriever  hybrid retriever to call for each case
     * @param outputDir  output directory (e.g. "target/rag-eval")
     * @return paths to the generated HTML and CSV files
     */
    public static GenerateResult generate(RagEvalDatasetLoader loader,
                                          HybridGalleryRetriever retriever,
                                          Path outputDir) throws IOException {
        List<RagEvalCase> cases = loader.loadCases();
        List<CasePool> casePools = new ArrayList<>();
        List<CsvRow> csvRows = new ArrayList<>();

        for (RagEvalCase c : cases) {
            RagSearchCriteria criteria = buildCriteria(c);
            List<RagCandidate> vectorCandidates = List.of();

            try {
                vectorCandidates = retriever.retrieve(criteria);
            } catch (Exception e) {
                // vector retrieval failed, continue with empty
            }

            // Deduplicate by pictureId, keep highest scores
            List<CandidateRow> merged = mergeAndDeduplicate(vectorCandidates);
            casePools.add(new CasePool(c.caseId(), c.query(),
                    c.notes() != null ? c.notes() : "", merged));

            for (CandidateRow row : merged) {
                csvRows.add(new CsvRow(
                        c.caseId(), c.query(),
                        row.pictureId, row.picHash, row.name,
                        row.vectorScore, row.keywordScore, row.metadataScore));
            }
        }

        Files.createDirectories(outputDir);
        Path htmlPath = outputDir.resolve("candidate-pool.html");
        Path csvPath = outputDir.resolve("candidate-pool.csv");

        writeHtml(htmlPath, casePools);
        writeCsv(csvPath, csvRows);

        return new GenerateResult(htmlPath, csvPath, cases.size(), csvRows.size());
    }

    private static RagSearchCriteria buildCriteria(RagEvalCase c) {
        // Use fixedCriteria if provided, otherwise fall back to query-based criteria
        if (c.fixedCriteria() != null) {
            return c.fixedCriteria();
        }
        return new RagSearchCriteria(
                c.query(),    // query
                null,         // category
                null,         // tags
                null,         // styleHints
                null,         // colorHints
                null,         // compositionHints
                false,        // favoritedOnly
                null,         // referenceMode
                50,           // candidateSize (oversample for pool generation)
                20,           // finalTopK
                0.0           // minVectorScore (relaxed for pool generation)
        );
    }

    /**
     * Deduplicate candidates by pictureId, keeping the highest of each score type.
     * HybridGalleryRetriever already merges vector + keyword internally, but we
     * deduplicate here as a safety net.
     */
    private static List<CandidateRow> mergeAndDeduplicate(List<RagCandidate> candidates) {
        Map<Long, CandidateRow> merged = new LinkedHashMap<>();
        for (RagCandidate c : candidates) {
            if (c.picture() == null || c.picture().id() == null) continue;
            long id = c.picture().id();
            var pic = c.picture();
            String thumbnailUrl = pic.thumbnailUrl() != null ? pic.thumbnailUrl()
                    : "/api/gallery/files/" + pic.id();
            CandidateRow row = new CandidateRow(
                    id,
                    pic.picHash() != null ? pic.picHash() : "",
                    pic.name() != null ? pic.name() : "",
                    thumbnailUrl,
                    pic.category() != null ? pic.category() : "",
                    pic.tags() != null ? List.copyOf(pic.tags()) : List.of(),
                    round3(c.vectorScore()),
                    round3(c.keywordScore()),
                    round3(c.metadataScore()),
                    round3(c.finalScore()),
                    c.reasons() != null ? List.copyOf(c.reasons()) : List.of());
            CandidateRow existing = merged.get(id);
            if (existing == null) {
                merged.put(id, row);
            } else {
                merged.put(id, new CandidateRow(
                        id, existing.picHash, existing.name, existing.thumbnailUrl,
                        existing.category, existing.tags,
                        Math.max(existing.vectorScore, row.vectorScore),
                        Math.max(existing.keywordScore, row.keywordScore),
                        Math.max(existing.metadataScore, row.metadataScore),
                        Math.max(existing.finalScore, row.finalScore),
                        existing.reasons));
            }
        }
        return List.copyOf(new ArrayList<>(merged.values()));
    }

    // ── HTML output ────────────────────────────────────────────────────────

    private static void writeHtml(Path path, List<CasePool> casePools) throws IOException {
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(path, StandardCharsets.UTF_8))) {
            w.println("<!DOCTYPE html>");
            w.println("<html lang=\"zh-CN\"><head><meta charset=\"UTF-8\">");
            w.println("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">");
            w.println("<title>RAG Candidate Pool — Manual Annotation</title>");
            w.println("<style>");
            w.println(css());
            w.println("</style></head><body>");

            w.println("<div class=\"header\">");
            w.println("<h1>RAG Candidate Pool</h1>");
            w.println("<p>Generated: " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) + "</p>");
            w.println("<p>Total cases: " + casePools.size() + "</p>");
            w.println("</div>");

            for (CasePool cp : casePools) {
                w.println("<div class=\"case-section\">");
                w.println("<h2 class=\"case-id\">" + escapeHtml(cp.caseId) + "</h2>");
                w.println("<div class=\"query-box\">");
                w.println("<div class=\"query-text\">" + escapeHtml(cp.query) + "</div>");
                if (cp.notes != null && !cp.notes.isEmpty()) {
                    w.println("<div class=\"query-notes\">" + escapeHtml(cp.notes) + "</div>");
                }
                w.println("</div>");

                if (cp.candidates.isEmpty()) {
                    w.println("<p class=\"no-results\">No candidates retrieved.</p>");
                } else {
                    w.println("<div class=\"candidate-grid\">");
                    for (int i = 0; i < cp.candidates.size(); i++) {
                        writeCandidateCard(w, cp.caseId, i, cp.candidates.get(i));
                    }
                    w.println("</div>");
                }
                w.println("</div>");
            }

            w.println("</body></html>");
        }
    }

    private static void writeCandidateCard(PrintWriter w, String caseId, int index, CandidateRow row) {
        String safeName = escapeHtml(row.name);
        String safeCategory = escapeHtml(row.category);
        String safeUrl = escapeHtml(row.thumbnailUrl);
        String uniqueId = "grade-" + caseId + "-" + index;

        w.println("<div class=\"candidate-card\">");
        w.println("  <img src=\"" + safeUrl + "\" alt=\"" + safeName + "\" class=\"thumbnail\" "
                + "onerror=\"this.src='data:image/svg+xml,<svg xmlns=%22http://www.w3.org/2000/svg%22 width=%22150%22 height=%22150%22>"
                + "<rect fill=%22%23e0e0e0%22 width=%22150%22 height=%22150%22/>"
                + "<text x=%2275%22 y=%2275%22 text-anchor=%22middle%22 dy=%22.3em%22 fill=%22%23666%22>N/A</text></svg>'\">");
        w.println("  <div class=\"card-body\">");
        w.println("    <div class=\"card-name\">" + safeName + "</div>");
        if (row.category != null && !row.category.isEmpty()) {
            w.println("    <div class=\"card-category\">" + safeCategory + "</div>");
        }
        if (row.tags != null && !row.tags.isEmpty()) {
            w.println("    <div class=\"card-tags\">" + escapeHtml(String.join(", ", row.tags)) + "</div>");
        }
        w.println("    <div class=\"scores\">");
        w.println("      <span class=\"score vs\" title=\"Vector Score\">VS: " + row.vectorScore + "</span>");
        w.println("      <span class=\"score ks\" title=\"Keyword Score\">KS: " + row.keywordScore + "</span>");
        w.println("      <span class=\"score ms\" title=\"Metadata Score\">MS: " + row.metadataScore + "</span>");
        w.println("    </div>");
        if (row.reasons != null && !row.reasons.isEmpty()) {
            w.println("    <div class=\"card-reasons\">" + escapeHtml(String.join("; ", row.reasons)) + "</div>");
        }
        w.println("    <div class=\"grade-group\">");
        for (int g = 0; g <= 3; g++) {
            String label = gradeLabel(g);
            w.println("      <label class=\"grade-label\">"
                    + "<input type=\"radio\" name=\"" + uniqueId + "\" value=\"" + g + "\"> "
                    + label + "</label>");
        }
        w.println("    </div>");
        w.println("  </div>");
        w.println("</div>");
    }

    private static String gradeLabel(int grade) {
        return switch (grade) {
            case 0 -> "0-Irr";
            case 1 -> "1-Weak";
            case 2 -> "2-Rel";
            case 3 -> "3-High";
            default -> String.valueOf(grade);
        };
    }

    // ── CSV output ─────────────────────────────────────────────────────────

    private record CsvRow(String caseId, String query, long pictureId, String picHash,
                          String name, double vectorScore, double keywordScore, double metadataScore) {}

    private static void writeCsv(Path path, List<CsvRow> rows) throws IOException {
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(path, StandardCharsets.UTF_8))) {
            w.println("caseId,query,pictureId,picHash,name,vectorScore,keywordScore,metadataScore");
            for (CsvRow r : rows) {
                w.printf("%s,\"%s\",%d,%s,\"%s\",%.3f,%.3f,%.3f%n",
                        r.caseId, escapeCsv(r.query),
                        r.pictureId, r.picHash, escapeCsv(r.name),
                        r.vectorScore, r.keywordScore, r.metadataScore);
            }
        }
    }

    // ── HTML/CSS helpers ───────────────────────────────────────────────────

    /** Self-contained inline CSS. */
    private static String css() {
        return """
        * { box-sizing: border-box; margin: 0; padding: 0; }
        body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
               background: #f5f5f5; color: #333; padding: 20px; }
        .header { background: #fff; padding: 20px 30px; border-radius: 8px; margin-bottom: 20px;
                  box-shadow: 0 1px 3px rgba(0,0,0,.1); }
        .header h1 { font-size: 1.5em; margin-bottom: 5px; }
        .header p { color: #666; font-size: 0.9em; }
        .case-section { background: #fff; padding: 24px; border-radius: 8px; margin-bottom: 20px;
                        box-shadow: 0 1px 3px rgba(0,0,0,.1); }
        .case-id { font-size: 1.2em; color: #1a73e8; margin-bottom: 8px; }
        .query-box { background: #e8f0fe; border-left: 4px solid #1a73e8; padding: 10px 14px;
                     font-size: 1.05em; color: #1a237e; margin-bottom: 16px; border-radius: 4px; }
        .no-results { color: #999; font-style: italic; padding: 12px; }
        .candidate-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(240px, 1fr));
                          gap: 16px; }
        .candidate-card { border: 1px solid #e0e0e0; border-radius: 8px; overflow: hidden;
                          transition: box-shadow .2s; }
        .candidate-card:hover { box-shadow: 0 2px 8px rgba(0,0,0,.15); }
        .thumbnail { width: 100%; height: 150px; object-fit: cover; display: block; background: #f0f0f0; }
        .card-body { padding: 10px 12px 12px; }
        .card-name { font-weight: 600; font-size: 0.95em; margin-bottom: 2px; }
        .card-category { color: #666; font-size: 0.8em; margin-bottom: 4px; }
        .card-tags { color: #888; font-size: 0.75em; margin-bottom: 6px;
                     overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
        .scores { display: flex; gap: 6px; margin-bottom: 6px; flex-wrap: wrap; }
        .score { font-size: 0.75em; padding: 1px 6px; border-radius: 3px; }
        .score.vs { background: #e3f2fd; color: #1565c0; }
        .score.ks { background: #e8f5e9; color: #2e7d32; }
        .score.ms { background: #fff3e0; color: #e65100; }
        .card-reasons { color: #666; font-size: 0.75em; margin-bottom: 8px; }
        .grade-group { display: flex; gap: 4px; flex-wrap: wrap; margin-top: 8px;
                       padding-top: 8px; border-top: 1px solid #eee; }
        .grade-label { font-size: 0.78em; cursor: pointer; padding: 2px 6px; border-radius: 4px;
                       border: 1px solid #ccc; white-space: nowrap; }
        .grade-label:has(input:checked) { background: #1a73e8; color: #fff; border-color: #1a73e8; }
        """;
    }

    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    private static String escapeCsv(String s) {
        if (s == null) return "";
        return s.replace("\"", "\"\"");
    }

    private static double round3(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }

    /** Result of candidate pool generation. */
    public record GenerateResult(Path htmlPath, Path csvPath, int caseCount, int totalCandidates) {}
}
