package com.zzp.aiagent.model.vo;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Map;

@Schema(description = "生图 SSE 事件数据")
public record ImageGeneratedEventVO(
        @Schema(description = "生成图片 URL") String imageUrl,
        @Schema(description = "生成图片 Base64") String imageBase64,
        @Schema(description = "原始生图 Prompt") String imagePrompt,
        @Schema(description = "模型修订 Prompt") String revisedPrompt,
        @Schema(description = "生图风格") String style,
        @Schema(description = "生图尺寸") String dimensions,
        @Schema(description = "提供商元数据") Map<String, Object> metadata
) {}
