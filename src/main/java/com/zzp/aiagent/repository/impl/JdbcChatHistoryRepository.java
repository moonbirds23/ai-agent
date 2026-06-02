package com.zzp.aiagent.repository.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzp.aiagent.model.dto.memory.ChatMessageRecord;
import com.zzp.aiagent.model.dto.memory.ImageRef;
import com.zzp.aiagent.repository.ChatHistoryRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Repository
@Profile("!test")
@Slf4j
public class JdbcChatHistoryRepository implements ChatHistoryRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public JdbcChatHistoryRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    public void save(ChatMessageRecord record) {
        String imageRefsJson = toJson(record.imageRefs());
        String metadataJson = toJson(record.metadata());
        jdbc.update(
                "INSERT INTO chat_message (conversation_id, role, content, image_refs, metadata) VALUES (?,?,?,?::jsonb,?::jsonb)",
                record.conversationId(), record.role(), record.content(),
                imageRefsJson, metadataJson);
    }

    @Override
    public List<ChatMessageRecord> findByConversation(String conversationId, int limit) {
        String sql = """
                SELECT id, conversation_id, role, content, image_refs, metadata, created_at
                FROM (
                    SELECT * FROM chat_message
                    WHERE conversation_id = ?
                    ORDER BY created_at DESC
                    LIMIT ?
                ) sub
                ORDER BY created_at ASC
                """;
        return jdbc.query(sql, this::mapRow, conversationId, limit);
    }

    @Override
    public int deleteByConversation(String conversationId) {
        return jdbc.update("DELETE FROM chat_message WHERE conversation_id = ?", conversationId);
    }

    @Override
    public int countByConversation(String conversationId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM chat_message WHERE conversation_id = ?",
                Integer.class, conversationId);
        return count != null ? count : 0;
    }

    private ChatMessageRecord mapRow(ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new ChatMessageRecord(
                rs.getLong("id"),
                rs.getString("conversation_id"),
                rs.getString("role"),
                rs.getString("content"),
                parseImageRefs(rs.getString("image_refs")),
                parseMetadata(rs.getString("metadata")),
                toLocalDateTime(rs.getTimestamp("created_at"))
        );
    }

    private List<ImageRef> parseImageRefs(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return mapper.readValue(json, new TypeReference<List<ImageRef>>() {});
        } catch (Exception e) {
            log.warn("[ChatHistoryRepo] image_refs JSON解析失败: {}", e.getMessage());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseMetadata(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return mapper.readValue(json, Map.class);
        } catch (Exception e) {
            log.warn("[ChatHistoryRepo] metadata JSON解析失败: {}", e.getMessage());
            return null;
        }
    }

    private java.time.LocalDateTime toLocalDateTime(Timestamp ts) {
        return ts != null ? ts.toLocalDateTime() : null;
    }

    private String toJson(Object obj) {
        if (obj == null) return null;
        try {
            return mapper.writeValueAsString(obj);
        } catch (Exception e) {
            log.warn("[ChatHistoryRepo] JSON序列化失败: {}", e.getMessage());
            return null;
        }
    }
}
