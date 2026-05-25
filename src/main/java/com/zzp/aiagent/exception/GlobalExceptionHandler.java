package com.zzp.aiagent.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.UUID;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ContentSafetyException.class)
    public ResponseEntity<ErrorResponse> handleContentSafety(ContentSafetyException ex) {
        ErrorResponse body = ErrorResponse.of(ex, traceId());
        log.warn("[ContentSafety] code={} msg={}", body.errorCode(), body.message());
        return ResponseEntity.status(ex.getErrorCode().getHttpStatus()).body(body);
    }

    @ExceptionHandler(InvalidInputException.class)
    public ResponseEntity<ErrorResponse> handleInvalidInput(InvalidInputException ex) {
        ErrorResponse body = ErrorResponse.of(ex, traceId());
        log.info("[InvalidInput] code={} msg={}", body.errorCode(), body.message());
        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(AiApiException.class)
    public ResponseEntity<ErrorResponse> handleAiApi(AiApiException ex) {
        ErrorResponse body = ErrorResponse.of(ex, traceId());
        log.error("[AiApi] code={} retryable={} cause={}", body.errorCode(), ex.isRetryable(), ex.getCause() != null ? ex.getCause().getMessage() : "");
        return ResponseEntity.status(ex.getErrorCode().getHttpStatus()).body(body);
    }

    @ExceptionHandler(ChatMemoryException.class)
    public ResponseEntity<ErrorResponse> handleChatMemory(ChatMemoryException ex) {
        ErrorResponse body = ErrorResponse.of(ex, traceId());
        log.error("[ChatMemory] code={}", body.errorCode(), ex);
        return ResponseEntity.internalServerError().body(body);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnknown(Exception ex) {
        String requestId = traceId();
        log.error("[Unknown] requestId={}", requestId, ex);
        ErrorResponse body = ErrorResponse.of(ErrorCode.INTERNAL_ERROR,
                ErrorCode.INTERNAL_ERROR.getUserMessage(), requestId);
        return ResponseEntity.internalServerError().body(body);
    }

    private String traceId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
