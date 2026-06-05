package com.zzp.aiagent.domain.pexels;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.pexels")
public record PexelsProperties(
        String apiKey,
        int connectTimeoutSeconds,
        int readTimeoutSeconds,
        int searchMaxResults,
        int maxDownloadCount
) {
    /** Default locale for search (Pexels supports zh-CN, en-US, etc.). */
    public String defaultLocale() {
        return "zh-CN";
    }
}
