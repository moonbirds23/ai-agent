package com.zzp.aiagent.vector.model;

import java.util.Map;

public record VectorSearchHit(
        Long pictureId,
        Double vectorScore,
        Map<String, Object> metadata
) {}
