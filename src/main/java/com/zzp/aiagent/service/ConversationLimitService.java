package com.zzp.aiagent.service;

public interface ConversationLimitService {

    /**
     * Check whether the conversation has reached the maximum number of messages.
     * Throws BusinessException(PARAMS_ERROR) if the limit is exceeded.
     */
    void checkLimit(String chatId);
}
