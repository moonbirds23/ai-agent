package com.zzp.aiagent.model.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.zzp.aiagent.model.dto.image.ImageAgentResponse;
import com.zzp.aiagent.model.dto.image.VisionAnalysisResult;
import io.swagger.v3.oas.annotations.media.Schema;

@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "对话响应")
public record ChatResponseVO(
        @Schema(description = "会话ID") String chatId,
        @Schema(description = "响应类型: chat=纯文本对话, image_ready=图片已可生成, image_analyzed=图片已分析, image_generated=图片已生成", example = "chat") String type,
        @Schema(description = "向用户展示的文本消息") String message,
        @Schema(description = "优化后的图片生成prompt") String imagePrompt,
        @Schema(description = "艺术风格描述") String style,
        @Schema(description = "建议尺寸，仅image_ready时有值") String dimensions,
        @Schema(description = "修订后prompt或图片分析详情") String revisedPrompt,
        @Schema(description = "生成的图片URL，仅image_generated时有值") String imageUrl,
        @Schema(description = "生成的图片base64，仅image_generated时有值") String imageBase64,
        @Schema(description = "RAG增强调试信息") Object ragDebugInfo
) {
    public static ChatResponseVO objToVo(ImageAgentResponse ai, String chatId) {
        return new ChatResponseVO(
                chatId,
                ai.type(),
                ai.message(),
                ai.imagePrompt(),
                ai.style(),
                ai.dimensions(),
                ai.revisedPrompt(),
                null, null,
                null
        );
    }

    public static ChatResponseVO textOnly(String chatId, String text) {
        return new ChatResponseVO(chatId, "chat", text, null, null, null, null, null, null, null);
    }

    public static ChatResponseVO imageGenerated(String chatId, String imageUrl, String imageBase64, String message) {
        return imageGenerated(chatId, imageUrl, imageBase64, message, null, null, null, null, null);
    }

    public static ChatResponseVO imageGenerated(String chatId, String imageUrl, String imageBase64, String message,
                                                String imagePrompt, String style, String dimensions, String revisedPrompt) {
        return imageGenerated(chatId, imageUrl, imageBase64, message, imagePrompt, style, dimensions, revisedPrompt, null);
    }

    public static ChatResponseVO imageGenerated(String chatId, String imageUrl, String imageBase64, String message,
                                                String imagePrompt, String style, String dimensions, String revisedPrompt,
                                                Object ragDebugInfo) {
        return new ChatResponseVO(chatId, "image_generated", message, imagePrompt, style, dimensions, revisedPrompt,
                imageUrl, imageBase64, ragDebugInfo);
    }

    public static ChatResponseVO imageAnalyzed(String chatId, VisionAnalysisResult result) {
        return new ChatResponseVO(chatId, "image_analyzed", result.message(), result.imagePrompt(),
                result.style(), null, result.detailText(), null, null, null);
    }
}
