package com.zzp.aiagent.domain.rag;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Structured trace of a single RAG pipeline execution.
 *
 * <p>Captures timing breakdowns per stage, retrieval path classification,
 * and candidate/selected picture IDs for evaluation and debugging.
 */
public record RagTrace(
        String chatId,
        Long userId,
        String originalQuery,
        String rewrittenQuery,
        Object criteria,
        Object candidates,
        Object selected,
        String templateCode,
        String enhancedPrompt,
        long latencyMs,
        LocalDateTime createTime,

        // ── Per-stage latencies (R4 enhancement) ──────────────────────
        long rewriteLatencyMs,
        long retrieveLatencyMs,
        long rerankLatencyMs,
        long packLatencyMs,

        // ── Retrieval path classification ─────────────────────────────
        String retrievalPath,

        // ── Picture IDs for evaluation ────────────────────────────────
        List<Long> candidatePictureIds,
        List<Long> selectedPictureIds,
        int candidateCount,
        int selectedCount
) {
    public RagTrace {
        if (createTime == null) {
            createTime = LocalDateTime.now();
        }
        if (retrievalPath == null) {
            retrievalPath = "UNKNOWN";
        }
        if (candidatePictureIds == null) {
            candidatePictureIds = List.of();
        }
        if (selectedPictureIds == null) {
            selectedPictureIds = List.of();
        }
    }

    public static RagTrace of(String chatId, String originalQuery, String enhancedPrompt, long latencyMs) {
        return new RagTrace(chatId, null, originalQuery, null, null, null, null, null,
                enhancedPrompt, latencyMs, LocalDateTime.now(),
                0, 0, 0, 0, "UNKNOWN", List.of(), List.of(), 0, 0);
    }
}
