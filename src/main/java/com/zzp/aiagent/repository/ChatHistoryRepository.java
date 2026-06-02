package com.zzp.aiagent.repository;

import com.zzp.aiagent.model.dto.memory.ChatMessageRecord;

import java.util.List;

public interface ChatHistoryRepository {

    void save(ChatMessageRecord record);

    List<ChatMessageRecord> findByConversation(String conversationId, int limit);

    int deleteByConversation(String conversationId);

    int countByConversation(String conversationId);
}
