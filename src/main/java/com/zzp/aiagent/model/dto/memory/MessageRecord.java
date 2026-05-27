package com.zzp.aiagent.model.dto.memory;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 中间层 DTO：解决 Spring AI 的 Message 接口无法被 Jackson 反序列化（多态）的问题。
 * 保留 role + content + mediaUrls，丢弃 metadata。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MessageRecord(
        String role,
        String content,
        List<String> mediaUrls
) {
    public static final String ROLE_USER = "USER";
    public static final String ROLE_ASSISTANT = "ASSISTANT";
    public static final String ROLE_SYSTEM = "SYSTEM";

    public MessageRecord(String role, String content) {
        this(role, content, null);
    }
}
