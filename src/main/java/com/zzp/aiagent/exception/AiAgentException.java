package com.zzp.aiagent.exception;

import lombok.Getter;

@Getter
public abstract class AiAgentException extends RuntimeException {

    private final ErrorCode errorCode;
    private final boolean retryable;

    protected AiAgentException(ErrorCode errorCode, String message, boolean retryable) {
        super(message);
        this.errorCode = errorCode;
        this.retryable = retryable;
    }

    protected AiAgentException(ErrorCode errorCode, String message, boolean retryable, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.retryable = retryable;
    }
}
