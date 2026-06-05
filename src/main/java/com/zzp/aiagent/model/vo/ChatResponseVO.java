package com.zzp.aiagent.model.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "对话响应")
public record ChatResponseVO(
        @Schema(description = "会话ID") String chatId,
        @Schema(description = "响应类型: chat=纯文本对话, image_generated=图片已生成", example = "chat") String type,
        @Schema(description = "向用户展示的文本消息") String message,
        @Schema(description = "生成的图片URL，仅image_generated时有值") String imageUrl,
        @Schema(description = "生成的图片base64，仅image_generated时有值") String imageBase64
) {
    public static ChatResponseVO textOnly(String chatId, String text) {
        return new ChatResponseVO(chatId, "chat", text, null, null);
    }

    public static ChatResponseVO imageGenerated(String chatId, String imageUrl, String imageBase64, String message) {
        return new ChatResponseVO(chatId, "image_generated", message, imageUrl, imageBase64);
    }
}
