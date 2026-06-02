package com.zzp.aiagent.domain.gallery;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.gallery")
public record GalleryProperties(
        int cacheMaxAgeDays,
        String cacheCleanupCron
) {
    public GalleryProperties {
        if (cacheMaxAgeDays <= 0) cacheMaxAgeDays = 7;
        if (cacheCleanupCron == null || cacheCleanupCron.isBlank()) cacheCleanupCron = "0 0 3 * * ?";
    }
}
