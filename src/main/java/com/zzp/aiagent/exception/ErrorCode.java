package com.zzp.aiagent.exception;

import lombok.Getter;

@Getter
public enum ErrorCode {

    SUCCESS(0, "ok"),
    PARAMS_ERROR(40000, "请求参数错误"),
    EMPTY_MESSAGE(40100, "消息不能为空"),
    MESSAGE_TOO_LONG(40101, "消息超过长度限制"),
    CONTENT_BLOCKED(40200, "请求包含不支持的词汇"),
    AI_AUTH_FAILED(50000, "AI 服务鉴权失败"),
    AI_RATE_LIMIT(50001, "AI 服务请求过于频繁"),
    AI_TIMEOUT(50002, "AI 服务响应超时"),
    AI_MODEL_UNAVAILABLE(50003, "AI 模型暂不可用"),
    MEMORY_ERROR(50010, "对话记忆异常"),
    IMAGE_TOO_LARGE(40201, "图片尺寸超过限制"),
    IMAGE_FORMAT_INVALID(40202, "不支持的图片格式"),
    UNSUPPORTED_MEDIA_TYPE(40203, "不支持的多媒体类型"),
    IMAGE_GENERATION_FAILED(50020, "图片生成失败"),
    IMAGE_ANALYSIS_FAILED(50021, "图片分析失败"),
    GALLERY_OPERATION_FAILED(50040, "图库操作失败"),
    PROFILE_OPERATION_FAILED(50050, "图片画像操作失败"),
    SYSTEM_ERROR(59999, "系统内部异常");

    private final int code;
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }
}
