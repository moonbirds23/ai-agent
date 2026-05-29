package com.zzp.aiagent.model.dto.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("MessageRecord 序列化")
class MessageRecordTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("序列化 → {\"role\":\"USER\",\"content\":\"你好\"}")
    void serialize_userMessage() throws Exception {
        var record = new MessageRecord(MessageRecord.ROLE_USER, "你好");

        String json = mapper.writeValueAsString(record);

        assertThat(json).isEqualTo("{\"role\":\"USER\",\"content\":\"你好\"}");
    }

    @Test
    @DisplayName("序列化 → assistant")
    void serialize_assistantMessage() throws Exception {
        var record = new MessageRecord("ASSISTANT", "已生成");

        String json = mapper.writeValueAsString(record);

        assertThat(json).contains("\"role\":\"ASSISTANT\"");
        assertThat(json).contains("\"content\":\"已生成\"");
    }

    @Test
    @DisplayName("反序列化 → 还原为 MessageRecord")
    void deserialize_roundTrip() throws Exception {
        String json = "{\"role\":\"USER\",\"content\":\"我想要一张雪景图\"}";

        MessageRecord record = mapper.readValue(json, MessageRecord.class);

        assertThat(record.role()).isEqualTo("USER");
        assertThat(record.content()).isEqualTo("我想要一张雪景图");
    }

    @Test
    @DisplayName("特殊字符（换行/引号）→ 序列化后能正确反序列化")
    void specialCharacters_roundTrip() throws Exception {
        var original = new MessageRecord("ASSISTANT",
                "好的，以下是我的建议：\n1. \"雪景\" 推荐\n2. \"日出\" 推荐");

        String json = mapper.writeValueAsString(original);
        MessageRecord restored = mapper.readValue(json, MessageRecord.class);

        assertThat(restored.content()).isEqualTo(original.content());
    }

    @Test
    @DisplayName("常量引用：USER/ASSISTANT/SYSTEM 三值正确")
    void constants_areCorrect() {
        assertThat(MessageRecord.ROLE_USER).isEqualTo("USER");
        assertThat(MessageRecord.ROLE_ASSISTANT).isEqualTo("ASSISTANT");
        assertThat(MessageRecord.ROLE_SYSTEM).isEqualTo("SYSTEM");
    }

    // ── ImageRef 扩展 ──────────────────────────────────────

    @Test
    @DisplayName("序列化 → 含 GALLERY imageRefs")
    void serialize_withGalleryRef() throws Exception {
        var record = new MessageRecord("USER", "参考这张图的风格",
                List.of(ImageRef.gallery(42L)));

        String json = mapper.writeValueAsString(record);

        assertThat(json).contains("\"content\":\"参考这张图的风格\"");
        assertThat(json).contains("\"type\":\"GALLERY\"");
        assertThat(json).contains("\"pictureId\":42");
        assertThat(json).doesNotContain("\"description\"");
    }

    @Test
    @DisplayName("序列化 → 含 TEXT_DESCRIPTION imageRefs")
    void serialize_withTextDescriptionRef() throws Exception {
        var record = new MessageRecord("USER", "分析这张图",
                List.of(ImageRef.textDescription("橘猫，暖色调，浅景深")));

        String json = mapper.writeValueAsString(record);

        assertThat(json).contains("\"type\":\"TEXT_DESCRIPTION\"");
        assertThat(json).contains("\"description\":\"橘猫，暖色调，浅景深\"");
        assertThat(json).doesNotContain("\"pictureId\"");
    }

    @Test
    @DisplayName("序列化 → null imageRefs 不出现在 JSON 中")
    void serialize_nullImageRefs_omitted() throws Exception {
        var record = new MessageRecord("ASSISTANT", "好的");

        String json = mapper.writeValueAsString(record);

        assertThat(json).doesNotContain("imageRefs");
        assertThat(json).doesNotContain("mediaUrls");  // 旧字段已移除
    }

    @Test
    @DisplayName("反序列化 → 无 imageRefs 字段的旧 JSON 兼容")
    void deserialize_oldFormat_noImageRefs() throws Exception {
        String json = "{\"role\":\"USER\",\"content\":\"你好\"}";

        MessageRecord record = mapper.readValue(json, MessageRecord.class);

        assertThat(record.role()).isEqualTo("USER");
        assertThat(record.content()).isEqualTo("你好");
        assertThat(record.imageRefs()).isNull();
    }

    @Test
    @DisplayName("ImageRef 全量 round-trip")
    void imageRef_roundTrip() throws Exception {
        var original = new MessageRecord("USER", "参考这两张图",
                List.of(
                        ImageRef.gallery(10L),
                        ImageRef.textDescription("水墨山水画")
                ));

        String json = mapper.writeValueAsString(original);
        MessageRecord restored = mapper.readValue(json, MessageRecord.class);

        assertThat(restored.imageRefs()).hasSize(2);
        assertThat(restored.imageRefs().get(0).type()).isEqualTo("GALLERY");
        assertThat(restored.imageRefs().get(0).pictureId()).isEqualTo(10L);
        assertThat(restored.imageRefs().get(1).type()).isEqualTo("TEXT_DESCRIPTION");
        assertThat(restored.imageRefs().get(1).description()).isEqualTo("水墨山水画");
    }

    @Test
    @DisplayName("ImageRef 工厂方法正确性")
    void imageRef_factoryMethods() {
        var gallery = ImageRef.gallery(99L);
        assertThat(gallery.type()).isEqualTo("GALLERY");
        assertThat(gallery.pictureId()).isEqualTo(99L);
        assertThat(gallery.description()).isNull();

        var text = ImageRef.textDescription("赛博朋克夜景");
        assertThat(text.type()).isEqualTo("TEXT_DESCRIPTION");
        assertThat(text.pictureId()).isNull();
        assertThat(text.description()).isEqualTo("赛博朋克夜景");
    }
}
