package com.zzp.aiagent.rag.eval;

import java.util.List;

/**
 * Per-group evaluation metrics, including the breakdown of which cases
 * belong to this group.
 */
public record GroupMetrics(
        EvalSummary summary,
        List<String> caseIds
) {
    public GroupMetrics {
        caseIds = caseIds != null ? List.copyOf(caseIds) : List.of();
    }
}
