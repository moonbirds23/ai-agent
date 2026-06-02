package com.zzp.aiagent.domain.rag;

public record PackedRagContext(
        String explicitReferencesText,
        String retrievedReferencesText,
        String styleTemplateText,
        int totalChars
) {}
