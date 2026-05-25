package com.zzp.aiagent.exception;

public class ContentSafetyException extends AiAgentException {
    public ContentSafetyException() {
        super(ErrorCode.CONTENT_BLOCKED, ErrorCode.CONTENT_BLOCKED.getUserMessage(), false);
    }

    public ContentSafetyException(String detail) {
        super(ErrorCode.CONTENT_BLOCKED, detail, false);
    }
}
