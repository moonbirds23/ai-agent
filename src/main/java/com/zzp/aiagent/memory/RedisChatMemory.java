package com.zzp.aiagent.memory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzp.aiagent.model.dto.memory.MessageRecord;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.model.Media;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeTypeUtils;

import java.net.URL;
import java.time.Duration;
import java.util.Collections;
import java.util.List;

/**
 * ChatMemory 的 Redis 实现：Jackson 序列化 List&lt;MessageRecord&gt; → Redis List。
 * Message → MessageRecord（中间层）→ JSON → RPUSH；LRANGE → JSON → MessageRecord → Message。
 */
@Component
@Profile("!test")
@Slf4j
public class RedisChatMemory implements ChatMemory {

    private static final String KEY_PREFIX = "chat:memory:";
    private static final Duration TTL = Duration.ofDays(7);
    private static final TypeReference<List<MessageRecord>> LIST_TYPE = new TypeReference<>() {};

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;

    public RedisChatMemory(StringRedisTemplate redis) {
        this.redis = redis;
        this.mapper = new ObjectMapper();
    }

    @Override
    public void add(String conversationId, List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        String key = key(conversationId);
        List<MessageRecord> records = messages.stream()
                .map(RedisChatMemory::toRecord)
                .toList();
        List<String> jsonList = records.stream()
                .map(r -> {
                    try { return mapper.writeValueAsString(r); }
                    catch (Exception e) { throw new RuntimeException(e); }
                })
                .toList();
        redis.opsForList().rightPushAll(key, jsonList);
        redis.expire(key, TTL);
        log.debug("[RedisChatMemory] add conversationId={} count={}", conversationId, messages.size());
    }

    @Override
    public List<Message> get(String conversationId, int lastN) {
        String key = key(conversationId);
        long len = redis.opsForList().size(key);
        if (len == 0) {
            return Collections.emptyList();
        }
        long start = Math.max(0, len - lastN);
        List<String> jsonList = redis.opsForList().range(key, start, -1);
        if (jsonList == null || jsonList.isEmpty()) {
            return Collections.emptyList();
        }
        return jsonList.stream()
                .map(this::parseRecord)
                .map(RedisChatMemory::toMessage)
                .toList();
    }

    @Override
    public void clear(String conversationId) {
        redis.delete(key(conversationId));
        log.debug("[RedisChatMemory] clear conversationId={}", conversationId);
    }

    // ── 序列化 ──────────────────────────────────────────────

    private static MessageRecord toRecord(Message msg) {
        List<String> urls = null;
        if (msg instanceof UserMessage um && um.getMedia() != null && !um.getMedia().isEmpty()) {
            urls = um.getMedia().stream()
                    .filter(m -> m.getData() instanceof String)
                    .map(m -> (String) m.getData())
                    .toList();
            if (urls.isEmpty()) urls = null;
        }
        return new MessageRecord(msg.getMessageType().name(), msg.getText(), urls);
    }

    private MessageRecord parseRecord(String json) {
        try {
            return mapper.readValue(json, MessageRecord.class);
        } catch (Exception e) {
            throw new RuntimeException("反序列化 MessageRecord 失败: " + json, e);
        }
    }

    // ── 反序列化（手动多态，根据 role new 具体类） ──────────

    private static Message toMessage(MessageRecord record) {
        return switch (record.role()) {
            case MessageRecord.ROLE_USER -> {
                if (record.mediaUrls() != null && !record.mediaUrls().isEmpty()) {
                    List<Media> media = record.mediaUrls().stream()
                            .map(url -> {
                                try {
                                    return new Media(MimeTypeUtils.IMAGE_PNG, new URL(url));
                                } catch (Exception e) {
                                    throw new RuntimeException("无效的图片URL: " + url, e);
                                }
                            })
                            .toList();
                    yield new UserMessage(record.content(), media);
                }
                yield new UserMessage(record.content());
            }
            case MessageRecord.ROLE_ASSISTANT -> new AssistantMessage(record.content());
            case MessageRecord.ROLE_SYSTEM -> new SystemMessage(record.content());
            default -> throw new IllegalArgumentException("未知 role: " + record.role());
        };
    }

    private static String key(String conversationId) {
        return KEY_PREFIX + conversationId;
    }
}
