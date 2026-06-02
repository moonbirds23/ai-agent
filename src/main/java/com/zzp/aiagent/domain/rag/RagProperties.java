package com.zzp.aiagent.domain.rag;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.rag")
public record RagProperties(
    boolean enabled,
    int topK,
    double minScore,
    int maxContextChars,
    boolean retrieveFavoritesOnly,
    double vectorWeight,
    double keywordWeight,
    double metadataWeight
) {}
