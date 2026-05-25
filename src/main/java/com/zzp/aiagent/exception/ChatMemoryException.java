package com.zzp.aiagent.exception;

public class ChatMemoryException extends AiAgentException {
    public ChatMemoryException(ErrorCode errorCode, Throwable cause) {
        super(errorCode, errorCode.getUserMessage(), true, cause);
    }

    public ChatMemoryException(ErrorCode errorCode, String detail) {
        super(errorCode, detail, true);
    }
}
