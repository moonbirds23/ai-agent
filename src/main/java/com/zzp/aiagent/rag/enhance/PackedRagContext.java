package com.zzp.aiagent.rag.enhance;

public record PackedRagContext(
        String explicitReferencesText,
        String retrievedReferencesText,
        String styleTemplateText,
        int totalChars
) {}
