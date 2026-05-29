package com.zzp.aiagent.memory;

import com.zzp.aiagent.model.dto.memory.ChatMessageRecord;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.util.Collections;
import java.util.List;

@Repository
@Profile("!postgres")
@Slf4j
public class NoopChatHistoryRepository implements ChatHistoryRepository {

    @Override
    public void save(ChatMessageRecord record) {
        log.debug("[ChatHistoryRepo] Noop save (postgres profile not active)");
    }

    @Override
    public List<ChatMessageRecord> findByConversation(String conversationId, int limit) {
        return Collections.emptyList();
    }

    @Override
    public int deleteByConversation(String conversationId) {
        return 0;
    }

    @Override
    public int countByConversation(String conversationId) {
        return 0;
    }
}
