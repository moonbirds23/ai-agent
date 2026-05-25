package com.zzp.aiagent.exception;

public class InvalidInputException extends AiAgentException {
    public InvalidInputException(ErrorCode errorCode) {
        super(errorCode, errorCode.getUserMessage(), false);
    }

    public InvalidInputException(ErrorCode errorCode, String detail) {
        super(errorCode, detail, false);
    }
}
