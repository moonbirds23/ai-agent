package com.zzp.aiagent.rag.eval;

import java.util.List;

/**
 * The result of executing a single {@link RagEvalCase} against the RAG pipeline.
 * Contains both raw candidate IDs and post-rerank selected IDs so that
 * metrics can be computed at each stage.
 */
public record RagEvalCaseResult(
        String caseId,
        String group,
        String query,
        boolean expectedEmpty,
        List<Long> candidateIds,       // raw candidates from retriever (before rerank)
        List<Long> selectedIds,         // after reranking
        List<RelevanceJudgment> relevantPictures,
        List<Long> mustNotReturn,
        boolean success,
        String errorMessage
) {
    public RagEvalCaseResult {
        candidateIds = candidateIds != null ? List.copyOf(candidateIds) : List.of();
        selectedIds = selectedIds != null ? List.copyOf(selectedIds) : List.of();
        relevantPictures = relevantPictures != null ? List.copyOf(relevantPictures) : List.of();
        mustNotReturn = mustNotReturn != null ? List.copyOf(mustNotReturn) : List.of();
    }

    /** Build a grade lookup map: pictureId -> grade (0-3). */
    public java.util.Map<Long, Integer> gradeMap() {
        java.util.Map<Long, Integer> map = new java.util.LinkedHashMap<>();
        for (RelevanceJudgment j : relevantPictures) {
            map.put(j.pictureId(), j.grade());
        }
        return map;
    }
}
