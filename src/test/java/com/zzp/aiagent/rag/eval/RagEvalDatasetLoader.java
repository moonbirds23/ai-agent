package com.zzp.aiagent.rag.eval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Loads RAG evaluation cases from a JSONL file and a corpus manifest.
 *
 * <p>Usage:
 * <pre>{@code
 * RagEvalDatasetLoader loader = RagEvalDatasetLoader.fromClasspath("rag-eval/gallery-v1");
 * List<RagEvalCase> cases = loader.loadCases();
 * CorpusManifest manifest = loader.getManifest();
 * }</pre>
 */
public class RagEvalDatasetLoader {

    private final ObjectMapper mapper;
    private final String resourceBase;
    private CorpusManifest manifest;
    private List<RagEvalCase> cases;

    public RagEvalDatasetLoader(ObjectMapper mapper, String resourceBase) {
        this.mapper = mapper;
        this.resourceBase = resourceBase.endsWith("/") ? resourceBase : resourceBase + "/";
    }

    /** Create a loader from a classpath resource directory, e.g. "rag-eval/gallery-v1". */
    public static RagEvalDatasetLoader fromClasspath(String resourceBase) {
        return new RagEvalDatasetLoader(new ObjectMapper(), resourceBase);
    }

    /** Load the corpus manifest. */
    public CorpusManifest loadManifest() throws IOException {
        if (manifest != null) return manifest;
        String path = resourceBase + "corpus-manifest.json";
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(path)) {
            if (is == null) {
                throw new IOException("Corpus manifest not found: " + path);
            }
            manifest = mapper.readValue(is, CorpusManifest.class);
        }
        return manifest;
    }

    /**
     * Load all enabled cases from cases.jsonl.
     * Each line is a complete JSON object representing a RagEvalCase.
     */
    public List<RagEvalCase> loadCases() throws IOException {
        if (cases != null) return cases;
        String path = resourceBase + "cases.jsonl";
        List<RagEvalCase> all = new ArrayList<>();
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(path)) {
            if (is == null) {
                throw new IOException("Cases file not found: " + path);
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line;
                int lineNumber = 0;
                while ((line = reader.readLine()) != null) {
                    lineNumber++;
                    line = line.strip();
                    if (line.isEmpty()) continue;
                    try {
                        RagEvalCase c = mapper.readValue(line, RagEvalCase.class);
                        all.add(c);
                    } catch (Exception e) {
                        throw new IOException("Failed to parse line " + lineNumber + " in " + path + ": " + e.getMessage(), e);
                    }
                }
            }
        }

        // Filter disabled cases
        cases = all.stream().filter(RagEvalCase::enabled).collect(Collectors.toList());
        return cases;
    }

    /**
     * Load everything and validate that all picHash references in cases
     * are present in the corpus manifest.
     */
    public ValidationResult loadAndValidate() throws IOException {
        loadManifest();
        loadCases();

        Set<String> knownHashes = manifest.pictures().stream()
                .map(CorpusManifest.PictureEntry::picHash)
                .collect(Collectors.toSet());

        Map<Long, String> knownIdToHash = manifest.pictures().stream()
                .collect(Collectors.toMap(
                        CorpusManifest.PictureEntry::pictureId,
                        CorpusManifest.PictureEntry::picHash,
                        (a, b) -> a,
                        LinkedHashMap::new));

        List<String> warnings = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        for (RagEvalCase c : cases) {
            for (RelevanceJudgment j : c.relevantPictures()) {
                if (!knownHashes.contains(j.picHash())) {
                    warnings.add(String.format(
                            "[%s] picHash '%s' not found in corpus manifest",
                            c.caseId(), j.picHash()));
                }
                String knownHash = knownIdToHash.get(j.pictureId());
                if (knownHash != null && !knownHash.equals(j.picHash())) {
                    warnings.add(String.format(
                            "[%s] pictureId %d has hash '%s' but manifest has '%s'",
                            c.caseId(), j.pictureId(), j.picHash(), knownHash));
                }
            }
            for (Long id : c.mustNotReturn()) {
                if (!knownIdToHash.containsKey(id)) {
                    warnings.add(String.format(
                            "[%s] mustNotReturn pictureId %d not in corpus manifest",
                            c.caseId(), id));
                }
            }
        }

        return new ValidationResult(errors, warnings);
    }

    public CorpusManifest getManifest() {
        return manifest;
    }

    /** Corpus manifest data object, loaded from corpus-manifest.json. */
    public record CorpusManifest(
            String datasetVersion,
            String createdAt,
            String embeddingModel,
            int embeddingDimensions,
            String indexTextVersion,
            int pictureCount,
            List<PictureEntry> pictures
    ) {
        public record PictureEntry(long pictureId, String picHash, String name, int vectorStatus) {}
    }

    /** Result of cross-validating cases against the corpus manifest. */
    public record ValidationResult(List<String> errors, List<String> warnings) {
        public boolean hasErrors() { return !errors.isEmpty(); }
        public boolean hasWarnings() { return !warnings.isEmpty(); }
    }
}
