package com.zzp.aiagent.exception;

import java.time.Instant;

public record ErrorResponse(
        String errorCode,
        String message,
        String requestId,
        Instant timestamp,
        boolean retryable
) {
    public static ErrorResponse of(AiAgentException ex, String requestId) {
        return new ErrorResponse(
                ex.getErrorCode().getCode(),
                ex.getErrorCode().getUserMessage(),
                requestId,
                Instant.now(),
                ex.isRetryable()
        );
    }

    public static ErrorResponse of(ErrorCode code, String message, String requestId) {
        return new ErrorResponse(
                code.getCode(),
                message != null ? message : code.getUserMessage(),
                requestId,
                Instant.now(),
                code.isRetryable()
        );
    }
}
