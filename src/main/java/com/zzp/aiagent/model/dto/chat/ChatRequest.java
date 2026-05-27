package com.zzp.aiagent.model.dto.chat;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "对话请求")
public record ChatRequest(
        @Schema(description = "用户消息", example = "帮我生成一张冬日雪景图")
        String message,

        @Schema(description = "会话ID，不传则自动生成", example = "550e8400-e29b-41d4-a716-446655440000")
        String chatId,

        @Schema(description = "生成模式标志，true=基于历史对话生成图片", example = "false")
        Boolean generationMode,

        @Schema(description = "图片base64数据（与imageUrl互斥）")
        String imageBase64,

        @Schema(description = "图片URL（与imageBase64互斥）")
        String imageUrl,

        @Schema(description = "显式模式: chat=文本交流, image_analysis=图片分析, image_generation=图片生成", example = "chat")
        String mode
) {
    public static final String MODE_CHAT = "chat";
    public static final String MODE_IMAGE_ANALYSIS = "image_analysis";
    public static final String MODE_IMAGE_GENERATION = "image_generation";

    public ChatRequest(String message, String chatId, Boolean generationMode, String imageBase64, String imageUrl) {
        this(message, chatId, generationMode, imageBase64, imageUrl, null);
    }
}
