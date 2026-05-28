package com.zzp.aiagent.storage.model;

public record StoredObject(
        String key,
        String url,
        String contentType,
        Long size
) {}
