package com.zzp.aiagent.rag.enhance;

import java.util.Collections;
import java.util.List;

public record RagRewriteResult(
        String searchQuery,
        String category,
        List<String> tags,
        List<String> styleHints,
        List<String> colorHints,
        List<String> compositionHints,
        String referenceMode,
        String templateHint
) {
    public static RagRewriteResult fallback(String originalQuery) {
        return new RagRewriteResult(
                originalQuery, null, Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), null, null);
    }
}
