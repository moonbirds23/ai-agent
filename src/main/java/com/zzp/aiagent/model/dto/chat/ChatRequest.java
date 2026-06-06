package com.zzp.aiagent.model.dto.chat;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

import java.util.List;

@Schema(description = "对话请求")
public record ChatRequest(
        @Schema(description = "用户消息", example = "帮我生成一张冬日雪景图")
        @Size(max = 10000, message = "消息长度不能超过 10000 字符")
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
        String mode,

        @Schema(description = "明确参考图ID列表，最多 3 张")
        @Size(max = 3, message = "参考图不超过 3 张")
        List<Long> referencePictureIds,

        @Schema(description = "启用/禁用图库RAG检索，默认true", example = "true")
        Boolean useGalleryRag,

        @Schema(description = "参考模式: overall/style/color/composition")
        String referenceMode,

        @Schema(description = "指定风格模板编码")
        String styleTemplateCode,

        @Schema(description = "生成的图片是否保存到图库", example = "false")
        Boolean saveGeneratedToGallery
) {
    public static final String MODE_CHAT = "chat";
    public static final String MODE_IMAGE_ANALYSIS = "image_analysis";
    public static final String MODE_IMAGE_GENERATION = "image_generation";
    /** Default mode — model decides what to do via Tool Calling. */
    public static final String MODE_AUTO = "auto";

    // Backward-compatible 5-arg constructor (old code without mode / RAG fields)
    public ChatRequest(String message, String chatId, Boolean generationMode, String imageBase64, String imageUrl) {
        this(message, chatId, generationMode, imageBase64, imageUrl, null, null, null, null, null, null);
    }

    // Backward-compatible 6-arg constructor (old code with mode but without RAG fields)
    public ChatRequest(String message, String chatId, Boolean generationMode, String imageBase64, String imageUrl, String mode) {
        this(message, chatId, generationMode, imageBase64, imageUrl, mode, null, null, null, null, null);
    }
}
