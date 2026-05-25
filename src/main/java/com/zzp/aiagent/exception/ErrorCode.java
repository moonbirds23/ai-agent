package com.zzp.aiagent.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public enum ErrorCode {

    // 输入校验 1xxx
    EMPTY_MESSAGE(      "1001", "消息不能为空",            HttpStatus.BAD_REQUEST, false),
    MESSAGE_TOO_LONG(   "1002", "消息超过长度限制",        HttpStatus.BAD_REQUEST, false),
    UNSUPPORTED_FORMAT( "1003", "不支持的文件格式",        HttpStatus.BAD_REQUEST, false),

    // 内容安全 2xxx
    CONTENT_BLOCKED(    "2001", "请求包含不支持的词汇",      HttpStatus.FORBIDDEN, false),

    // AI 服务 3xxx
    AI_AUTH_FAILED(     "3001", "AI 服务鉴权失败",         HttpStatus.INTERNAL_SERVER_ERROR, false),
    AI_RATE_LIMIT(      "3002", "AI 服务请求过于频繁",      HttpStatus.TOO_MANY_REQUESTS, true),
    AI_TIMEOUT(         "3003", "AI 服务响应超时",         HttpStatus.GATEWAY_TIMEOUT, true),
    AI_MODEL_UNAVAILABLE("3004","AI 模型暂不可用",         HttpStatus.INTERNAL_SERVER_ERROR, true),

    // 对话记忆 4xxx
    MEMORY_READ_FAILED(  "4001", "对话记忆读取失败",        HttpStatus.INTERNAL_SERVER_ERROR, true),
    MEMORY_WRITE_FAILED( "4002", "对话记忆保存失败",        HttpStatus.INTERNAL_SERVER_ERROR, true),

    // 通用
    INTERNAL_ERROR(     "9999", "系统异常，请稍后重试",      HttpStatus.INTERNAL_SERVER_ERROR, true);

    private final String code;
    private final String userMessage;
    private final HttpStatus httpStatus;
    private final boolean retryable;

    ErrorCode(String code, String userMessage, HttpStatus httpStatus, boolean retryable) {
        this.code = code;
        this.userMessage = userMessage;
        this.httpStatus = httpStatus;
        this.retryable = retryable;
    }
}
