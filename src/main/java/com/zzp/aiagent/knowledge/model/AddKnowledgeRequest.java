package com.zzp.aiagent.knowledge.model;

import java.util.List;

public record AddKnowledgeRequest(
        String imageUrl,
        String imageBase64,
        String title,
        String description,
        List<String> tags
) {}
