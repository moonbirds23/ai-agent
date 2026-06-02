package com.zzp.aiagent.domain.vector;

import java.util.Map;

public record VectorSearchHit(
        Long pictureId,
        Double vectorScore,
        Map<String, Object> metadata
) {}
