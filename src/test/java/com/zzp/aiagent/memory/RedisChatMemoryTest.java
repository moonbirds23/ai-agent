package com.zzp.aiagent.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzp.aiagent.model.dto.memory.MessageRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * <h3>测试目的</h3>
 * 验证 RedisChatMemory 的存储读取全链路：Spring AI 的 Message → 中间层 MessageRecord
 * → Jackson JSON → Redis List，以及反向读取还原。不依赖真实 Redis，全程 mock StringRedisTemplate。
 *
 * <h3>实现方式</h3>
 * - 使用 Mockito 模拟 StringRedisTemplate 和 ListOperations
 * - 用 ArgumentCaptor 捕获写入 Redis 的 JSON，验证内容正确性
 * - 双向验证 Message ↔ MessageRecord ↔ JSON 三态转换
 *
 * <h3>关键验证点</h3>
 * - 序列化：Message 转为 {role, content} JSON，丢弃 metadata/media
 * - 反序列化：根据 role 字段 switch 创建正确的 Message 子类
 * - Redis Key 格式：chat:memory:{conversationId}
 * - TTL：7 天自动过期
 * - 空列表不触发 Redis 操作
 */
@DisplayName("RedisChatMemory 单元测试")
class RedisChatMemoryTest {

    private StringRedisTemplate redis;
    @SuppressWarnings("unchecked")
    private ListOperations<String, String> listOps;
    private RedisChatMemory memory;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        listOps = mock(ListOperations.class);
        when(redis.opsForList()).thenReturn(listOps);
        when(redis.expire(anyString(), any(Duration.class))).thenReturn(true);
        memory = new RedisChatMemory(redis, new ObjectMapper());
    }

    @SuppressWarnings("unchecked")
    private Collection<String> capturePush(String key) {
        ArgumentCaptor<Collection<String>> captor = ArgumentCaptor.forClass(Collection.class);
        verify(listOps).rightPushAll(eq(key), captor.capture());
        return captor.getValue();
    }

    // ── Message → MessageRecord 转换 ─────────────────────────────

    /**
     * 目的：验证 UserMessage 序列化为 JSON 后 role 为 "USER"。
     * 实现：add 一条 UserMessage → 用 ArgumentCaptor 捕获写入 Redis 的 JSON → 断言字段。
     * 结果：JSON 包含 "role":"USER" 和 "content":"你好"。
     */
    @Test
    @DisplayName("UserMessage → JSON {\"role\":\"USER\",\"content\":\"...\"}")
    void userMessage_toRecord() {
        when(listOps.rightPushAll(anyString(), ArgumentMatchers.<String>anyCollection())).thenReturn(1L);

        memory.add("chat-1", List.of(new UserMessage("你好")));

        String json = capturePush("chat:memory:chat-1").iterator().next();
        assertThat(json).contains("\"role\":\"USER\"");
        assertThat(json).contains("\"content\":\"你好\"");
    }

    /**
     * 目的：验证 AssistantMessage 序列化后 role 为 "ASSISTANT"。
     * 实现：同上，消息类型换为 AssistantMessage。
     * 结果：JSON 包含 "role":"ASSISTANT"。
     */
    @Test
    @DisplayName("AssistantMessage → JSON {\"role\":\"ASSISTANT\",\"content\":\"...\"}")
    void assistantMessage_toRecord() {
        when(listOps.rightPushAll(anyString(), ArgumentMatchers.<String>anyCollection())).thenReturn(1L);

        memory.add("chat-1", List.of(new AssistantMessage("好的")));

        String json = capturePush("chat:memory:chat-1").iterator().next();
        assertThat(json).contains("\"role\":\"ASSISTANT\"");
        assertThat(json).contains("\"content\":\"好的\"");
    }

    /**
     * 目的：验证 SystemMessage 也能正确序列化（补充覆盖三种消息类型）。
     * 结果：JSON 包含 "role":"SYSTEM"。
     */
    @Test
    @DisplayName("SystemMessage → JSON {\"role\":\"SYSTEM\",...}")
    void systemMessage_toRecord() {
        when(listOps.rightPushAll(anyString(), ArgumentMatchers.<String>anyCollection())).thenReturn(1L);

        memory.add("chat-1", List.of(new SystemMessage("你是AI助手")));

        String json = capturePush("chat:memory:chat-1").iterator().next();
        assertThat(json).contains("\"role\":\"SYSTEM\"");
    }

    /**
     * 目的：验证空消息列表不会触发无意义的 Redis 写操作。
     * 实现：直接调用 add 传入空 List → 断言 rightPushAll 从未被调用。
     * 结果：rightPushAll 调用 0 次。
     */
    @Test
    @DisplayName("空列表 → 不调用 rightPushAll")
    void emptyList_skipsRedis() {
        memory.add("chat-1", Collections.emptyList());
        verify(listOps, never()).rightPushAll(anyString(), ArgumentMatchers.<String>anyCollection());
    }

    /**
     * 目的：验证写入的 JSON 能被 Jackson 反序列化回 MessageRecord。
     * 实现：add 两条消息 → 捕获 JSON → ObjectMapper.readValue 反序列化 → 断言字段完整。
     * 结果：每条 JSON 都能还原为 role/content 双字段的对象。
     */
    @Test
    @DisplayName("JSON 反序列化回 MessageRecord 通过")
    void jsonFormat_roundTrip() throws Exception {
        when(listOps.rightPushAll(anyString(), ArgumentMatchers.<String>anyCollection())).thenReturn(2L);

        memory.add("chat-1", List.of(new UserMessage("雪景图"), new AssistantMessage("已生成")));

        ObjectMapper mapper = new ObjectMapper();
        for (String json : capturePush("chat:memory:chat-1")) {
            MessageRecord record = mapper.readValue(json, MessageRecord.class);
            assertThat(record.role()).isIn("USER", "ASSISTANT", "SYSTEM");
            assertThat(record.content()).isNotEmpty();
        }
    }

    // ── 存储读取 ─────────────────────────────────────────────────

    @Test
    @DisplayName("get(chatId) → 取回全部消息")
    void get_returnsMessages() {
        String key = "chat:memory:chat-1";
        String json1 = "{\"role\":\"USER\",\"content\":\"消息1\"}";
        String json2 = "{\"role\":\"ASSISTANT\",\"content\":\"回复1\"}";
        when(listOps.range(key, 0, -1)).thenReturn(List.of(json1, json2));

        List<Message> result = memory.get("chat-1");

        assertThat(result).hasSize(2);
        assertThat(result.get(0)).isInstanceOf(UserMessage.class);
        assertThat(result.get(1)).isInstanceOf(AssistantMessage.class);
        assertThat(result.get(0).getText()).isEqualTo("消息1");
        assertThat(result.get(1).getText()).isEqualTo("回复1");
    }

    @Test
    @DisplayName("get → key 不存在返回空列表")
    void get_emptyKey_returnsEmpty() {
        when(listOps.range("chat:memory:new-chat", 0, -1)).thenReturn(null);

        List<Message> result = memory.get("new-chat");

        assertThat(result).isEmpty();
    }

    /**
     * 目的：验证 clear 将整个 Key 从 Redis 中删除。
     * 实现：clear("chat-1") → 断言 redis.delete("chat:memory:chat-1")。
     * 结果：delete 方法被调用 1 次，参数正确。
     */
    @Test
    @DisplayName("clear → DEL key")
    void clear_deletesKey() {
        when(redis.delete("chat:memory:chat-1")).thenReturn(true);

        memory.clear("chat-1");

        verify(redis).delete("chat:memory:chat-1");
    }

    // ── TTL ──────────────────────────────────────────────────────

    /**
     * 目的：验证每次 add 都会设置 7 天过期，避免 Redis 内存无限增长。
     * 实现：add → 断言 expire(key, 7天) 被调用。
     * 结果：TTL 为 Duration.ofDays(7)。
     */
    @Test
    @DisplayName("add → 设置 7 天 EXPIRE")
    void add_setsSevenDayTTL() {
        when(listOps.rightPushAll(anyString(), ArgumentMatchers.<String>anyCollection())).thenReturn(1L);

        memory.add("chat-1", List.of(new UserMessage("hi")));

        verify(redis).expire(eq("chat:memory:chat-1"), eq(Duration.ofDays(7)));
    }

    // ── Key 格式 ─────────────────────────────────────────────────

    /**
     * 目的：验证 Redis Key 使用约定前缀 chat:memory:。
     * 实现：add("abc-123", ...) → 断言 rightPushAll 的第一个参数是 "chat:memory:abc-123"。
     * 结果：Key 格式为 chat:memory:{conversationId}。
     */
    @Test
    @DisplayName("Redis Key 格式为 chat:memory:{conversationId}")
    void keyFormat() {
        when(listOps.rightPushAll(anyString(), ArgumentMatchers.<String>anyCollection())).thenReturn(1L);

        memory.add("abc-123", List.of(new UserMessage("test")));

        verify(listOps).rightPushAll(eq("chat:memory:abc-123"), ArgumentMatchers.<String>anyCollection());
    }

    // ── 批量写入 ─────────────────────────────────────────────────

    /**
     * 目的：验证一轮对话（User+Assistant 两条消息）按顺序写入，不丢消息不乱序。
     * 实现：add 两条 → 捕获 JSON 列表 → 断言长度为 2，第一条 role=USER，第二条 role=ASSISTANT。
     * 结果：消息顺序与 add 时一致。
     */
    @Test
    @DisplayName("一轮对话（User+Assistant）→ 两条 JSON 按顺序写入")
    void roundTrip_userAndAssistant_storedInOrder() {
        when(listOps.rightPushAll(anyString(), ArgumentMatchers.<String>anyCollection())).thenReturn(2L);

        memory.add("chat-1", List.of(new UserMessage("雪景"), new AssistantMessage("已生成雪景图")));

        List<String> list = List.copyOf(capturePush("chat:memory:chat-1"));
        assertThat(list).hasSize(2);
        assertThat(list.get(0)).contains("\"role\":\"USER\"");
        assertThat(list.get(1)).contains("\"role\":\"ASSISTANT\"");
    }
}
