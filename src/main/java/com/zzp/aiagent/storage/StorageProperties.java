package com.zzp.aiagent.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.storage")
public record StorageProperties(
        String type,
        LocalConfig local,
        CosConfig cos
) {
    public record LocalConfig(String root) {}
    public record CosConfig(String secretId, String secretKey, String region, String bucket, String baseUrl) {}
}
