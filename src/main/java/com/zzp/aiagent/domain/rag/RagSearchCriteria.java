package com.zzp.aiagent.domain.rag;

import java.util.List;

public record RagSearchCriteria(
        String query,
        String category,
        List<String> tags,
        List<String> styleHints,
        List<String> colorHints,
        List<String> compositionHints,
        Boolean favoritedOnly,
        String referenceMode,
        int candidateSize,
        int finalTopK,
        double minVectorScore
) {}
