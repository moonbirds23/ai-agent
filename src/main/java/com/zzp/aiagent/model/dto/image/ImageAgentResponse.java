package com.zzp.aiagent.model.dto.image;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * AI 层结构化输出：LLM 返回的 JSON 由 BeanOutputConverter 反序列化为此 record。
 * 这是 LLM 的输出格式，不是 REST API 的响应格式。API 层由 ChatResponseVO 负责。
 */
public record ImageAgentResponse(
        @JsonProperty("type") String type,
        @JsonProperty("message") String message,
        @JsonProperty("imagePrompt") String imagePrompt,
        @JsonProperty("style") String style,
        @JsonProperty("dimensions") String dimensions,
        @JsonProperty("revisedPrompt") String revisedPrompt
) {}
