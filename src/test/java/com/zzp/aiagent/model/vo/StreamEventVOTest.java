package com.zzp.aiagent.model.vo;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <h3>测试目的</h3>
 * 验证 StreamEventVO 四种工厂方法 (chatId/token/done/error) 的字段正确性
 * 以及 Jackson 序列化行为——SSE 客户端解析的就是这些 JSON。
 *
 * <h3>实现方式</h3>
 * - 直接调用各工厂方法，AssertJ 断言 record 字段
 * - ObjectMapper 序列化后断言 JSON 字符串中包含/不包含特定字段
 *
 * <h3>关键验证点</h3>
 * - chatId/token 事件：null 字段不出现在 JSON 中（Jackson 默认 @JsonInclude(NON_NULL)）
 * - done 事件：data 嵌套序列化正确
 * - record 各字段互不污染（type != chatId != content）
 */
@DisplayName("StreamEventVO 工厂方法与序列化")
class StreamEventVOTest {

    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * 目的：验证 StreamEventVO.chatId() 工厂方法正确填充 type 和 chatId。
     * 实现：chatId("abc-123") → 断言 type="chatId", chatId="abc-123", content/data 为 null。
     * 结果：前端收到首条事件后据此保存 chatId。
     */
    @Test
    @DisplayName("chatId 工厂 → type=chatId, chatId=传入值")
    void chatId_shouldSetTypeAndChatId() {
        StreamEventVO event = StreamEventVO.chatId("abc-123");

        assertThat(event.type()).isEqualTo("chatId");
        assertThat(event.chatId()).isEqualTo("abc-123");
        assertThat(event.content()).isNull();
        assertThat(event.data()).isNull();
    }

    /**
     * 目的：验证 StreamEventVO.token() 工厂方法正确填充 type 和 content。
     * 实现：token("你好") → 断言 type="token", content="你好", chatId/data 为 null。
     * 结果：前端逐字展示 token 内容。
     */
    @Test
    @DisplayName("token 工厂 → type=token, content=传入值")
    void token_shouldSetTypeAndContent() {
        StreamEventVO event = StreamEventVO.token("你好");

        assertThat(event.type()).isEqualTo("token");
        assertThat(event.content()).isEqualTo("你好");
        assertThat(event.chatId()).isNull();
        assertThat(event.data()).isNull();
    }

    /**
     * 目的：验证 StreamEventVO.done() 工厂方法包含 chatId 和嵌套的 ChatResponseVO。
     * 实现：done("chat-1", ChatResponseVO.textOnly(...)) → 断言 type="done", data 非 null。
     * 结果：前端收到 done 事件后展示完整结构化结果。
     */
    @Test
    @DisplayName("done 工厂 → type=done, 携带 chatId 和嵌套 data")
    void done_shouldSetTypeAndData() {
        ChatResponseVO data = ChatResponseVO.textOnly("chat-1", "测试回复");
        StreamEventVO event = StreamEventVO.done("chat-1", data);

        assertThat(event.type()).isEqualTo("done");
        assertThat(event.chatId()).isEqualTo("chat-1");
        assertThat(event.data()).isEqualTo(data);
        assertThat(event.content()).isNull();
    }

    /**
     * 目的：验证 StreamEventVO.error() 工厂方法正确封装错误内容。
     * 实现：error("处理异常") → 断言 type="error", content="处理异常"。
     * 结果：前端根据 type=error 显示错误提示。
     */
    @Test
    @DisplayName("error 工厂 → type=error, content=错误信息")
    void error_shouldSetTypeAndContent() {
        StreamEventVO event = StreamEventVO.error("处理异常");

        assertThat(event.type()).isEqualTo("error");
        assertThat(event.content()).isEqualTo("处理异常");
    }

    /**
     * 目的：验证 token 事件序列化为 JSON 时不含 chatId 和 data 字段。
     * 原因：@JsonInclude(NON_NULL) 确保 null 字段不出现在 JSON 中，减小 SSE 传输体积。
     * 实现：token("好") → writeValueAsString → 断言含 type/content，不含 chatId/data。
     * 结果：JSON 干净，仅含两个非 null 字段。
     */
    @Test
    @DisplayName("token 序列化 → JSON 只含 type 和 content")
    void token_shouldSerializeToJson() throws Exception {
        String json = mapper.writeValueAsString(StreamEventVO.token("好"));

        assertThat(json).contains("\"type\":\"token\"");
        assertThat(json).contains("\"content\":\"好\"");
        assertThat(json).doesNotContain("\"chatId\"");
        assertThat(json).doesNotContain("\"data\"");
    }

    /**
     * 目的：验证 done 事件的 data 字段正确嵌套序列化 ChatResponseVO。
     * 实现：done("id1", ChatResponseVO) → 序列化 → 断言外层 type/chatId 和内层 type/message 均出现。
     * 结果：SSE 客户端能从 data.type/data.message 读取结构化回复。
     */
    @Test
    @DisplayName("done 序列化 → data 嵌套 ChatResponseVO 完整输出")
    void done_shouldSerializeDataNested() throws Exception {
        ChatResponseVO data = new ChatResponseVO("id1", "chat", "你好", null, null, null, null, null, null, null);
        String json = mapper.writeValueAsString(StreamEventVO.done("id1", data));

        assertThat(json).contains("\"type\":\"done\"");
        assertThat(json).contains("\"chatId\":\"id1\"");
        assertThat(json).contains("\"type\":\"chat\"");
        assertThat(json).contains("\"message\":\"你好\"");
    }
}
