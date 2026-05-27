package com.zzp.aiagent.model.dto.image;

import java.util.Map;

/**
 * 图片生成结果。生图 API 返回后包装为此 record。
 */
public record ImageGenerationResult(
        String imageUrl,
        String imageBase64,
        String revisedPrompt,
        Map<String, Object> metadata
) {}
