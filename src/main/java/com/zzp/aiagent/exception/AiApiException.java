package com.zzp.aiagent.exception;

public class AiApiException extends AiAgentException {
    public AiApiException(ErrorCode errorCode) {
        super(errorCode, errorCode.getUserMessage(), errorCode.isRetryable());
    }

    public AiApiException(ErrorCode errorCode, String detail) {
        super(errorCode, detail, errorCode.isRetryable());
    }

    public AiApiException(ErrorCode errorCode, Throwable cause) {
        super(errorCode, errorCode.getUserMessage(), errorCode.isRetryable(), cause);
    }
}
