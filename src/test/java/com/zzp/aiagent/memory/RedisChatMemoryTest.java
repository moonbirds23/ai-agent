package com.zzp.aiagent.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzp.aiagent.model.dto.memory.ImageRef;
import com.zzp.aiagent.model.dto.memory.MessageRecord;
import com.zzp.aiagent.manager.RedisChatMemory;
import com.zzp.aiagent.repository.ChatHistoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("RedisChatMemory 单元测试")
class RedisChatMemoryTest {

    private StringRedisTemplate redis;
    @SuppressWarnings("unchecked")
    private ListOperations<String, String> listOps;
    private ChatHistoryRepository historyRepo;
    private ChatMemoryProperties props;
    private RedisChatMemory memory;
    private Executor executor;

    @SuppressWarnings({"unchecked", "rawtypes"})
    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        listOps = mock(ListOperations.class);
        when(redis.opsForList()).thenReturn(listOps);
        when(redis.expire(anyString(), any(Duration.class))).thenReturn(true);

        historyRepo = mock(ChatHistoryRepository.class);
        props = new ChatMemoryProperties(50, 8, 3000, true, 7, 200);

        // ObjectProvider 默认返回 null（模拟无 Gallery/Vision/Storage 服务）
        ObjectProvider galleryProvider = mock(ObjectProvider.class);
        when(galleryProvider.getIfAvailable()).thenReturn(null);
        ObjectProvider visionProvider = mock(ObjectProvider.class);
        when(visionProvider.getIfAvailable()).thenReturn(null);
        ObjectProvider storageProvider = mock(ObjectProvider.class);
        when(storageProvider.getIfAvailable()).thenReturn(null);

        executor = Runnable::run;

        memory = new RedisChatMemory(redis, new ObjectMapper(),
                historyRepo, props,
                galleryProvider, visionProvider, storageProvider,
                new MemorySanitizer(), executor);
    }

    @SuppressWarnings("unchecked")
    private Collection<String> capturePush(String key) {
        ArgumentCaptor<Collection<String>> captor = ArgumentCaptor.forClass(Collection.class);
        verify(listOps).rightPushAll(eq(key), captor.capture());
        return captor.getValue();
    }

    // ── 序列化: Message → MessageRecord ─────────────────────────

    @Test
    @DisplayName("UserMessage → JSON 含 role/content")
    void userMessage_toRecord() {
        when(listOps.rightPushAll(anyString(), ArgumentMatchers.<String>anyCollection())).thenReturn(1L);
        when(listOps.size(anyString())).thenReturn(null);

        memory.add("chat-1", List.of(new UserMessage("你好")));

        String json = capturePush("chat:memory:chat-1").iterator().next();
        assertThat(json).contains("\"role\":\"USER\"");
        assertThat(json).contains("\"content\":\"你好\"");
    }

    @Test
    @DisplayName("AssistantMessage → JSON 含 role/content")
    void assistantMessage_toRecord() {
        when(listOps.rightPushAll(anyString(), ArgumentMatchers.<String>anyCollection())).thenReturn(1L);
        when(listOps.size(anyString())).thenReturn(null);

        memory.add("chat-1", List.of(new AssistantMessage("好的")));

        String json = capturePush("chat:memory:chat-1").iterator().next();
        assertThat(json).contains("\"role\":\"ASSISTANT\"");
        assertThat(json).contains("\"content\":\"好的\"");
    }

    @Test
    @DisplayName("SystemMessage → JSON 含 role/content")
    void systemMessage_toRecord() {
        when(listOps.rightPushAll(anyString(), ArgumentMatchers.<String>anyCollection())).thenReturn(1L);
        when(listOps.size(anyString())).thenReturn(null);

        memory.add("chat-1", List.of(new SystemMessage("你是AI助手")));

        String json = capturePush("chat:memory:chat-1").iterator().next();
        assertThat(json).contains("\"role\":\"SYSTEM\"");
    }

    @Test
    @DisplayName("空列表 → 不触发 Redis 操作")
    void emptyList_skipsRedis() {
        memory.add("chat-1", Collections.emptyList());
        verify(listOps, never()).rightPushAll(anyString(), ArgumentMatchers.<String>anyCollection());
    }

    @Test
    @DisplayName("JSON round-trip: Message → JSON → MessageRecord → Message")
    void jsonFormat_roundTrip() throws Exception {
        when(listOps.rightPushAll(anyString(), ArgumentMatchers.<String>anyCollection())).thenReturn(2L);
        when(listOps.size(anyString())).thenReturn(null);

        memory.add("chat-1", List.of(new UserMessage("雪景图"), new AssistantMessage("已生成")));

        ObjectMapper mapper = new ObjectMapper();
        for (String json : capturePush("chat:memory:chat-1")) {
            MessageRecord record = mapper.readValue(json, MessageRecord.class);
            assertThat(record.role()).isIn("USER", "ASSISTANT", "SYSTEM");
            assertThat(record.content()).isNotEmpty();
        }
    }

    // ── get(): LRANGE 截断 ─────────────────────────────────────

    @Test
    @DisplayName("get → 用 LRANGE(len-N, len-1) 只取最后 maxMessages 条")
    void get_capsAtMaxMessages() {
        String key = "chat:memory:chat-1";
        when(listOps.size(key)).thenReturn(100L);
        // 模拟最后 50 条
        String[] jsons = new String[50];
        for (int i = 0; i < 50; i++) {
            jsons[i] = "{\"role\":\"ASSISTANT\",\"content\":\"msg" + i + "\"}";
        }
        when(listOps.range(key, 50L, 99L)).thenReturn(java.util.Arrays.asList(jsons));

        memory.get("chat-1");

        verify(listOps).range(key, 50L, 99L);
    }

    @Test
    @DisplayName("get → 消息数少于 maxMessages 时取全部")
    void get_fewerThanMax_returnsAll() {
        String key = "chat:memory:chat-1";
        when(listOps.size(key)).thenReturn(3L);
        String json1 = "{\"role\":\"USER\",\"content\":\"消息1\"}";
        String json2 = "{\"role\":\"USER\",\"content\":\"消息2\"}";
        String json3 = "{\"role\":\"ASSISTANT\",\"content\":\"回复\"}";
        when(listOps.range(key, 0L, 2L)).thenReturn(List.of(json1, json2, json3));

        List<Message> result = memory.get("chat-1");

        assertThat(result).hasSize(3);
        assertThat(result.get(0).getText()).isEqualTo("消息1");
        assertThat(result.get(2).getText()).isEqualTo("回复");
    }

    @Test
    @DisplayName("get → Redis 空 → PG 回源")
    void get_redisEmpty_fallsBackToPg() {
        String key = "chat:memory:chat-1";
        when(listOps.size(key)).thenReturn(0L);
        when(historyRepo.findByConversation("chat-1", 50)).thenReturn(List.of(
                new com.zzp.aiagent.model.dto.memory.ChatMessageRecord(
                        "chat-1", "USER", "历史消息1"),
                new com.zzp.aiagent.model.dto.memory.ChatMessageRecord(
                        "chat-1", "ASSISTANT", "历史回复1")
        ));
        when(listOps.rightPushAll(eq(key), ArgumentMatchers.<String>anyCollection())).thenReturn(2L);

        List<Message> result = memory.get("chat-1");

        assertThat(result).hasSize(2);
        assertThat(result.get(0)).isInstanceOf(UserMessage.class);
        assertThat(result.get(1)).isInstanceOf(AssistantMessage.class);
        // 验证回写 Redis
        verify(listOps).rightPushAll(eq(key), ArgumentMatchers.<String>anyCollection());
    }

    @Test
    @DisplayName("get → Redis 和 PG 都空 → 返回空列表")
    void get_bothEmpty_returnsEmpty() {
        String key = "chat:memory:chat-1";
        when(listOps.size(key)).thenReturn(0L);
        when(historyRepo.findByConversation("chat-1", 50)).thenReturn(Collections.emptyList());

        List<Message> result = memory.get("chat-1");

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("get → key 不存在返回空")
    void get_emptyKey_returnsEmpty() {
        when(listOps.size("chat:memory:new-chat")).thenReturn(0L);
        when(historyRepo.findByConversation("new-chat", 50)).thenReturn(Collections.emptyList());

        List<Message> result = memory.get("new-chat");

        assertThat(result).isEmpty();
    }

    // ── Text description 还原 ──────────────────────────────────

    @Test
    @DisplayName("get → TEXT_DESCRIPTION ref → 拼入 content")
    void get_textDescription_prependsToContent() {
        String key = "chat:memory:chat-1";
        when(listOps.size(key)).thenReturn(1L);
        String json = "{\"role\":\"USER\",\"content\":\"参考这张图\"," +
                "\"imageRefs\":[{\"type\":\"TEXT_DESCRIPTION\",\"description\":\"橘猫，暖色调，浅景深\"}]}";
        when(listOps.range(key, 0L, 0L)).thenReturn(List.of(json));

        List<Message> result = memory.get("chat-1");

        assertThat(result).hasSize(1);
        Message msg = result.get(0);
        assertThat(msg).isInstanceOf(UserMessage.class);
        assertThat(msg.getText()).contains("图片描述：橘猫，暖色调，浅景深");
        assertThat(msg.getText()).contains("当前用户消息：参考这张图");
    }

    @Test
    @DisplayName("get → 纯文本消息（无 imageRefs）→ 原样返回")
    void get_plainText_returnsAsIs() {
        String key = "chat:memory:chat-1";
        when(listOps.size(key)).thenReturn(1L);
        String json = "{\"role\":\"ASSISTANT\",\"content\":\"已生成雪景图\"}";
        when(listOps.range(key, 0L, 0L)).thenReturn(List.of(json));

        List<Message> result = memory.get("chat-1");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getText()).isEqualTo("已生成雪景图");
    }

    // ── 图片引用来源区分 ───────────────────────────────────────

    @Test
    @DisplayName("add → gallery URL → 存 GALLERY ref")
    void add_galleryUrl_storesGalleryRef() throws Exception {
        // 构造 ObjectProvider 返回 mock GalleryService
        var galleryService = mock(com.zzp.aiagent.service.GalleryService.class);
        when(galleryService.getById(42L)).thenReturn(new com.zzp.aiagent.model.entity.GalleryPicture(
                42L, "/api/gallery/files/42", null, "测试图", null, null, null, null, null, null,
                null, null, 1L, 0L, 1, null, "upload", false, null, null, "MAIN", null));

        ObjectProvider galleryProvider = mock(ObjectProvider.class);
        when(galleryProvider.getIfAvailable()).thenReturn(galleryService);
        ObjectProvider visionProvider = mock(ObjectProvider.class);
        when(visionProvider.getIfAvailable()).thenReturn(null);
        ObjectProvider storageProvider = mock(ObjectProvider.class);
        when(storageProvider.getIfAvailable()).thenReturn(null);

        var m = new RedisChatMemory(redis, new ObjectMapper(), historyRepo, props,
                galleryProvider, visionProvider, storageProvider,
                new MemorySanitizer(), executor);

        when(listOps.rightPushAll(anyString(), ArgumentMatchers.<String>anyCollection())).thenReturn(1L);
        when(listOps.size(anyString())).thenReturn(null);

        // 模拟 gallery URL 的 UserMessage
        var media = new org.springframework.ai.content.Media(
                org.springframework.util.MimeTypeUtils.IMAGE_PNG,
                java.net.URI.create("http://localhost:8231/api/gallery/files/42"));
        var userMsg = UserMessage.builder().text("参考这张图").media(List.of(media)).build();

        m.add("chat-1", List.of(userMsg));

        String json = capturePush("chat:memory:chat-1").iterator().next();
        assertThat(json).contains("\"type\":\"GALLERY\"");
        assertThat(json).contains("\"pictureId\":42");
        assertThat(json).doesNotContain("\"mediaUrls\"");
    }

    @Test
    @DisplayName("add → base64 ByteArrayResource → 无 Vision 服务时跳过图片引用")
    void add_byteArray_noVision_skipsImageRef() throws Exception {
        when(listOps.rightPushAll(anyString(), ArgumentMatchers.<String>anyCollection())).thenReturn(1L);
        when(listOps.size(anyString())).thenReturn(null);

        var media = new org.springframework.ai.content.Media(
                org.springframework.util.MimeTypeUtils.IMAGE_PNG,
                new org.springframework.core.io.ByteArrayResource("fake-image-bytes".getBytes()));
        var userMsg = UserMessage.builder().text("分析这张图").media(List.of(media)).build();

        memory.add("chat-1", List.of(userMsg));

        String json = capturePush("chat:memory:chat-1").iterator().next();
        assertThat(json).doesNotContain("imageRefs");
        assertThat(json).doesNotContain("mediaUrls");
    }

    @Test
    @DisplayName("resolveImageRef → 非 gallery URI → 返回占位文本（异步分析不阻塞）")
    void resolveImageRef_externalUrl_noVision_returnsNull() throws Exception {
        when(listOps.rightPushAll(anyString(), ArgumentMatchers.<String>anyCollection())).thenReturn(1L);
        when(listOps.size(anyString())).thenReturn(null);

        var media = new org.springframework.ai.content.Media(
                org.springframework.util.MimeTypeUtils.IMAGE_PNG,
                java.net.URI.create("https://example.com/photo.jpg"));
        var userMsg = UserMessage.builder().text("分析这张图").media(List.of(media)).build();

        memory.add("chat-1", List.of(userMsg));

        String json = capturePush("chat:memory:chat-1").iterator().next();
        // P3 fix: 外部图片立即返回占位文本，不再同步等待视觉分析
        assertThat(json).contains("imageRefs");
        assertThat(json).contains("用户上传了一张图片");
    }

    // ── clear ─────────────────────────────────────────────────

    @Test
    @DisplayName("clear → 删除 Redis Key + PG 记录")
    void clear_deletesBothRedisAndPg() {
        when(redis.delete("chat:memory:chat-1")).thenReturn(true);
        when(historyRepo.deleteByConversation("chat-1")).thenReturn(1);

        memory.clear("chat-1");

        verify(redis).delete("chat:memory:chat-1");
        verify(historyRepo).deleteByConversation("chat-1");
    }

    // ── TTL / Key 格式 ────────────────────────────────────────

    @Test
    @DisplayName("add → 设置配置的 TTL")
    void add_setsConfiguredTTL() {
        when(listOps.rightPushAll(anyString(), ArgumentMatchers.<String>anyCollection())).thenReturn(1L);
        when(listOps.size(anyString())).thenReturn(null);

        memory.add("chat-1", List.of(new UserMessage("hi")));

        verify(redis).expire(eq("chat:memory:chat-1"), eq(Duration.ofDays(7)));
    }

    @Test
    @DisplayName("Key 格式为 chat:memory:{conversationId}")
    void keyFormat() {
        when(listOps.rightPushAll(anyString(), ArgumentMatchers.<String>anyCollection())).thenReturn(1L);
        when(listOps.size(anyString())).thenReturn(null);

        memory.add("abc-123", List.of(new UserMessage("test")));

        verify(listOps).rightPushAll(eq("chat:memory:abc-123"), ArgumentMatchers.<String>anyCollection());
    }

    @Test
    @DisplayName("一轮对话 → User+Assistant 两条 JSON 按顺序写入")
    void roundTrip_userAndAssistant_storedInOrder() {
        when(listOps.rightPushAll(anyString(), ArgumentMatchers.<String>anyCollection())).thenReturn(2L);
        when(listOps.size(anyString())).thenReturn(null);

        memory.add("chat-1", List.of(new UserMessage("雪景"), new AssistantMessage("已生成雪景图")));

        List<String> list = List.copyOf(capturePush("chat:memory:chat-1"));
        assertThat(list).hasSize(2);
        assertThat(list.get(0)).contains("\"role\":\"USER\"");
        assertThat(list.get(1)).contains("\"role\":\"ASSISTANT\"");
    }

    // ── 自定义 maxMessages ────────────────────────────────────

    @Test
    @DisplayName("maxMessages=10 → get 最多取 10 条")
    void customMaxMessages_limitsGet() {
        var customProps = new ChatMemoryProperties(10, 8, 3000, true, 7, 200);
        ObjectProvider galleryProvider = mock(ObjectProvider.class);
        when(galleryProvider.getIfAvailable()).thenReturn(null);
        ObjectProvider visionProvider = mock(ObjectProvider.class);
        when(visionProvider.getIfAvailable()).thenReturn(null);
        ObjectProvider storageProvider = mock(ObjectProvider.class);
        when(storageProvider.getIfAvailable()).thenReturn(null);

        var customMemory = new RedisChatMemory(redis, new ObjectMapper(), historyRepo,
                customProps, galleryProvider, visionProvider, storageProvider,
                new MemorySanitizer(), executor);

        String key = "chat:memory:chat-1";
        when(listOps.size(key)).thenReturn(100L);

        customMemory.get("chat-1");

        // 100 条消息，maxMessages=10 → range(90, 99)
        verify(listOps).range(key, 90L, 99L);
    }

    @Test
    @DisplayName("get(String, int) 可覆盖 maxMessages")
    void get_withOverrideLimit() {
        String key = "chat:memory:chat-1";
        when(listOps.size(key)).thenReturn(100L);

        memory.get("chat-1", 20);

        // limit = min(20, 50) = 20 → range(80, 99)
        verify(listOps).range(key, 80L, 99L);
    }

    @Test
    @DisplayName("get(String, int) → lastN 超过 maxMessages 时被上限截断")
    void get_overrideLimit_capped() {
        String key = "chat:memory:chat-1";
        when(listOps.size(key)).thenReturn(200L);

        memory.get("chat-1", 1000);

        // limit = min(1000, 50) = 50 → range(150, 199)
        verify(listOps).range(key, 150L, 199L);
    }

    // ── ChatMemoryProperties 默认值 ────────────────────────────

    @Test
    @DisplayName("ChatMemoryProperties 默认值：maxMessages=50, ttlDays=7")
    void properties_defaults() {
        var p = new ChatMemoryProperties(0, 0, 0, false, 0, 0);
        assertThat(p.maxMessages()).isEqualTo(50);
        assertThat(p.ttlDays()).isEqualTo(7);
        assertThat(p.maxConversationMessages()).isEqualTo(200);
    }

    @Test
    @DisplayName("ChatMemoryProperties 负值 → 兜底为默认值")
    void properties_negative_fallsBack() {
        var p = new ChatMemoryProperties(-1, -1, -1, false, -1, -1);
        assertThat(p.maxMessages()).isEqualTo(50);
        assertThat(p.ttlDays()).isEqualTo(7);
        assertThat(p.maxConversationMessages()).isEqualTo(200);
    }
}
