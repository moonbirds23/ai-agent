package com.zzp.aiagent.model.dto.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <h3>测试目的</h3>
 * 验证 MessageRecord（中间层 DTO）的 Jackson 序列化/反序列化正确性。
 * 这是解决 Spring AI Message 接口多态无法反序列化的关键——只保留 role + content。
 *
 * <h3>实现方式</h3>
 * - 手动构造 MessageRecord → writeValueAsString → 断言 JSON 字符串格式
 * - 构造 JSON 字符串 → readValue → 断言字段还原
 * - round-trip：含特殊字符的对象 → 序列化 → 反序列化 → 断言内容一致
 *
 * <h3>关键验证点</h3>
 * - JSON 格式为 {"role":"...","content":"..."}，无多余字段
 * - 换行符、引号等特殊字符能正确转义
 * - ROLE_USER/ROLE_ASSISTANT/ROLE_SYSTEM 常量值正确
 */
@DisplayName("MessageRecord 序列化")
class MessageRecordTest {

    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * 目的：验证 UserMessage 对应的 MessageRecord 序列化为标准 JSON。
     * 结果：{"role":"USER","content":"你好"}。
     */
    @Test
    @DisplayName("序列化 → {\"role\":\"USER\",\"content\":\"你好\"}")
    void serialize_userMessage() throws Exception {
        var record = new MessageRecord(MessageRecord.ROLE_USER, "你好");

        String json = mapper.writeValueAsString(record);

        assertThat(json).isEqualTo("{\"role\":\"USER\",\"content\":\"你好\"}");
    }

    /**
     * 目的：验证 AssistantMessage 的序列化包含正确的 role 值。
     * 结果：JSON 中 "role":"ASSISTANT"。
     */
    @Test
    @DisplayName("序列化 → assistant")
    void serialize_assistantMessage() throws Exception {
        var record = new MessageRecord("ASSISTANT", "已生成");

        String json = mapper.writeValueAsString(record);

        assertThat(json).contains("\"role\":\"ASSISTANT\"");
        assertThat(json).contains("\"content\":\"已生成\"");
    }

    /**
     * 目的：验证 JSON 字符串能正确反序列化回 MessageRecord 对象。
     * 结果：role 为 "USER"，content 为原始文本。
     */
    @Test
    @DisplayName("反序列化 → 还原为 MessageRecord")
    void deserialize_roundTrip() throws Exception {
        String json = "{\"role\":\"USER\",\"content\":\"我想要一张雪景图\"}";

        MessageRecord record = mapper.readValue(json, MessageRecord.class);

        assertThat(record.role()).isEqualTo("USER");
        assertThat(record.content()).isEqualTo("我想要一张雪景图");
    }

    /**
     * 目的：验证含换行符和双引号的内容经序列化→反序列化后内容不丢失、不变形。
     * 实现：构造含 \n 和 \" 的文本 → 写为 JSON → 读回 → 断言内容全等。
     * 结果：content 完全一致，Jackson 转义正常。
     */
    @Test
    @DisplayName("特殊字符（换行/引号）→ 序列化后能正确反序列化")
    void specialCharacters_roundTrip() throws Exception {
        var original = new MessageRecord("ASSISTANT",
                "好的，以下是我的建议：\n1. \"雪景\" 推荐\n2. \"日出\" 推荐");

        String json = mapper.writeValueAsString(original);
        MessageRecord restored = mapper.readValue(json, MessageRecord.class);

        assertThat(restored.content()).isEqualTo(original.content());
    }

    /**
     * 目的：验证三个 role 常量值与 Spring AI MessageType 枚举名称一致。
     * 结果：USER / ASSISTANT / SYSTEM。
     */
    @Test
    @DisplayName("常量引用：USER/ASSISTANT/SYSTEM 三值正确")
    void constants_areCorrect() {
        assertThat(MessageRecord.ROLE_USER).isEqualTo("USER");
        assertThat(MessageRecord.ROLE_ASSISTANT).isEqualTo("ASSISTANT");
        assertThat(MessageRecord.ROLE_SYSTEM).isEqualTo("SYSTEM");
    }
}
