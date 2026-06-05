package com.zzp.aiagent.domain.web;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.web")
public record WebProperties(
        boolean enabled,
        int connectTimeoutSeconds,
        int readTimeoutSeconds,
        int maxFetchBytes,
        int maxDownloadBytes,
        String userAgent,
        int searchMaxResults,
        int fetchMaxChars,
        String proxyHost,
        int proxyPort,
        boolean imageSearchDebug,
        boolean imageSearchAsyncEnabled,
        boolean imageSearchSaveDebugHtml,
        double imageSearchMinRelevanceScore
) {
    public boolean hasProxy() {
        return proxyHost != null && !proxyHost.isBlank() && proxyPort > 0;
    }
}
