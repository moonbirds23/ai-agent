package com.zzp.aiagent.domain.storage;

public record StoredObject(
        String key,
        String url,
        String contentType,
        Long size
) {}
