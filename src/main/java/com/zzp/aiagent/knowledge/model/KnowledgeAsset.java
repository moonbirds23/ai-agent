package com.zzp.aiagent.knowledge.model;

import java.util.List;

public record KnowledgeAsset(
        String id,
        String userId,
        String title,
        String description,
        List<String> tags,
        String storageUrl
) {}
