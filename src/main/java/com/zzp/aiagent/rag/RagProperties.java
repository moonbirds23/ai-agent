package com.zzp.aiagent.rag;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.rag")
public record RagProperties(
    boolean enabled,
    int topK,
    double minScore,
    int maxContextChars,
    boolean retrieveFavoritesOnly
) {}
