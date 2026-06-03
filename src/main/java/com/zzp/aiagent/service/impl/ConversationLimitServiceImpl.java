package com.zzp.aiagent.service.impl;

import com.zzp.aiagent.common.ThrowUtils;
import com.zzp.aiagent.exception.ErrorCode;
import com.zzp.aiagent.manager.RedisChatMemory;
import com.zzp.aiagent.memory.ChatMemoryProperties;
import com.zzp.aiagent.repository.ChatHistoryRepository;
import com.zzp.aiagent.service.ConversationLimitService;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("!test")
public class ConversationLimitServiceImpl implements ConversationLimitService {

    private final ChatMemory chatMemory;
    private final ChatHistoryRepository chatHistoryRepo;
    private final ChatMemoryProperties chatMemoryProps;

    public ConversationLimitServiceImpl(ChatMemory chatMemory,
                                        ChatHistoryRepository chatHistoryRepo,
                                        ChatMemoryProperties chatMemoryProps) {
        this.chatMemory = chatMemory;
        this.chatHistoryRepo = chatHistoryRepo;
        this.chatMemoryProps = chatMemoryProps;
    }

    @Override
    public void checkLimit(String chatId) {
        if (chatMemory instanceof RedisChatMemory redisMemory) {
            int redisCount = redisMemory.count(chatId);
            ThrowUtils.throwIf(redisCount >= chatMemoryProps.maxConversationMessages(),
                    ErrorCode.PARAMS_ERROR,
                    "会话消息已达上限(" + redisCount + "/" + chatMemoryProps.maxConversationMessages() + ")，请开启新会话");
            return;
        }
        // Fallback: PG count
        int count = chatHistoryRepo.countByConversation(chatId);
        ThrowUtils.throwIf(count >= chatMemoryProps.maxConversationMessages(),
                ErrorCode.PARAMS_ERROR,
                "会话消息已达上限(" + count + "/" + chatMemoryProps.maxConversationMessages() + ")，请开启新会话");
    }
}
