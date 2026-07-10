package com.zzp.imageretrievalmcp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the Pexels API integration.
 * Bound to the {@code pexels} prefix in application.yml.
 */
@ConfigurationProperties(prefix = "pexels")
public record PexelsConfig(
    String apiKey,
    String baseUrl,
    int connectTimeoutSeconds,
    int readTimeoutSeconds,
    int searchMaxResults
) {
    public PexelsConfig {
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "https://api.pexels.com";
        }
        if (connectTimeoutSeconds <= 0) {
            connectTimeoutSeconds = 10;
        }
        if (readTimeoutSeconds <= 0) {
            readTimeoutSeconds = 30;
        }
        if (searchMaxResults <= 0) {
            searchMaxResults = 5;
        }
    }
}
