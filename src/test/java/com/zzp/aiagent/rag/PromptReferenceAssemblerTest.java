package com.zzp.aiagent.rag;

import com.zzp.aiagent.common.PromptTemplate;
import com.zzp.aiagent.gallery.model.GalleryPicture;
import com.zzp.aiagent.profile.model.PictureAiProfile;
import com.zzp.aiagent.rag.model.RagContext;
import com.zzp.aiagent.template.model.StyleTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <h3>测试目的</h3>
 * 验证 PromptReferenceAssembler 的三种核心能力：
 * - assemble：将 RAG 上下文渲染到 generation_with_rag.st 模板
 * - buildDebugInfo：生成人类可读的日志调试信息
 * - buildDebugData：生成结构化前端调试数据
 *
 * <h3>测试分类</h3>
 * 纯单元测试。PromptTemplate 通过 render 的简单字符串替换即可工作，无需 Spring。
 */
@DisplayName("PromptReferenceAssembler：Prompt 装配器")
@Tag("unit")
class PromptReferenceAssemblerTest {

    private PromptReferenceAssembler assembler;
    private GalleryPicture samplePic;
    private PictureAiProfile sampleProfile;
    private StyleTemplate sampleTemplate;

    @BeforeEach
    void setUp() {
        assembler = new PromptReferenceAssembler(new PromptTemplate());

        samplePic = new GalleryPicture(
                1L, "http://example.com/pic1.jpg", null, "BIGBANG Q版合影", "团体合影",
                "插画", List.of("卡通", "Q版", "偶像"), 1024L, 800, 600, 1.33, "png",
                1L, 0L, 1, "#FFFFFF", "upload", true,
                LocalDateTime.now(), LocalDateTime.now()
        );

        sampleProfile = new PictureAiProfile(
                1L, "BIGBANG五人Q版", "团体合影场景", "卡通/Q版风格", "暖色调",
                "横向排列", "柔和均匀光线", "温馨可爱", "Q版BIGBANG五人合影...",
                "主体：BIGBANG五人Q版\n风格：卡通/Q版风格\n", 1, LocalDateTime.now()
        );

        sampleTemplate = new StyleTemplate(
                "children-illustration", "儿童绘本插画", "creative",
                "illustration", List.of("儿童", "绘本", "可爱"), "柔和温暖风格",
                "避免尖锐线条", "1:1"
        );
    }

    @Nested
    @DisplayName("assemble(userInput, context)：装配增强 Prompt")
    class Assemble {

        /**
         * 目的：当上下文为空时，应直接返回原始用户输入，不做模板包裹。
         * 原因：避免无意义的模板开销，保持对话 Prompt 简洁。
         */
        @Test
        @DisplayName("空上下文 → 直接返回 userInput")
        void emptyContext_returnsUserInput() {
            RagContext ctx = RagContext.empty();
            String result = assembler.assemble("帮我生成一张卡通图", ctx);

            assertThat(result).isEqualTo("帮我生成一张卡通图");
        }

        /**
         * 目的：只有明确参考图时，assemble 应在增强 Prompt 中包含参考图信息。
         */
        @Test
        @DisplayName("仅 explicit 参考图 → 增强 Prompt 含参考图名称和画像")
        void explicitOnly_includesPictureDetails() {
            RagContext ctx = RagContext.empty()
                    .addExplicit(new RagContext.ReferencePicture(samplePic, sampleProfile));

            String result = assembler.assemble("生成类似风格图", ctx);

            assertThat(result).isNotEqualTo("生成类似风格图");
            assertThat(result).contains("BIGBANG Q版合影");
            assertThat(result).contains("卡通/Q版风格");
            assertThat(result).contains("生成类似风格图"); // 原始输入保留
        }

        /**
         * 目的：只有检索参考图时，assemble 应包含检索到的图片信息。
         */
        @Test
        @DisplayName("仅 retrieved 参考图 → 增强 Prompt 含检索图信息")
        void retrievedOnly_includesRetrievedDetails() {
            RagContext ctx = RagContext.empty()
                    .addRetrieved(new RagContext.ReferencePicture(samplePic, sampleProfile));

            String result = assembler.assemble("卡通偶像团体", ctx);

            assertThat(result).contains("BIGBANG Q版合影");
            assertThat(result).contains("卡通偶像团体");
        }

        /**
         * 目的：只有风格模板时，assemble 应包含模板的提示词和场景信息。
         */
        @Test
        @DisplayName("仅风格模板 → 增强 Prompt 含模板 name/code/prompt")
        void templateOnly_includesTemplateDetails() {
            RagContext ctx = RagContext.empty().withTemplate(sampleTemplate);

            String result = assembler.assemble("儿童插画", ctx);

            assertThat(result).contains("儿童绘本插画");
            assertThat(result).contains("children-illustration");
            assertThat(result).contains("柔和温暖风格");
            assertThat(result).contains("儿童插画");
        }

        /**
         * 目的：三层数据全部存在时，assemble 应包含全部三部分信息。
         */
        @Test
        @DisplayName("三层全有 → 增强 Prompt 含全部三层信息")
        void allThreeLayers_allIncluded() {
            RagContext ctx = RagContext.empty()
                    .addExplicit(new RagContext.ReferencePicture(samplePic, sampleProfile))
                    .addRetrieved(new RagContext.ReferencePicture(samplePic, null))
                    .withTemplate(sampleTemplate);

            String result = assembler.assemble("生成卡通图", ctx);

            assertThat(result).contains("明确参考图");
            assertThat(result).contains("历史收藏图参考");
            assertThat(result).contains("系统风格模板");
            assertThat(result).contains("BIGBANG Q版合影");
            assertThat(result).contains("儿童绘本插画");
        }

        /**
         * 目的：无画像的参考图也能正常格式化，不抛异常。
         */
        @Test
        @DisplayName("参考图无画像 → 不抛异常，显示'未分析'")
        void noProfile_noException() {
            RagContext ctx = RagContext.empty()
                    .addExplicit(new RagContext.ReferencePicture(samplePic, null));

            String result = assembler.assemble("测试", ctx);

            assertThat(result).isNotNull();
            assertThat(result).isNotEmpty();
        }
    }

    @Nested
    @DisplayName("buildDebugInfo(context)：人类可读调试信息")
    class BuildDebugInfo {

        /**
         * 目的：全空上下文应显示三层均为 0/无。
         */
        @Test
        @DisplayName("空上下文 → 三层都显示 0/无")
        void emptyContext_showsAllEmpty() {
            String info = assembler.buildDebugInfo(RagContext.empty());

            assertThat(info).contains("RAG Layers:");
            assertThat(info).contains("1. 明确参考图: 0 张");
            assertThat(info).contains("2. RAG检索图: 0 张");
            assertThat(info).contains("3. 风格模板: 无");
        }

        /**
         * 目的：有数据时展示图片名称和风格信息。
         */
        @Test
        @DisplayName("有参考图 → 展示名称和风格")
        void withRefs_showsNameAndStyle() {
            RagContext ctx = RagContext.empty()
                    .addExplicit(new RagContext.ReferencePicture(samplePic, sampleProfile));

            String info = assembler.buildDebugInfo(ctx);

            assertThat(info).contains("BIGBANG Q版合影");
            assertThat(info).contains("卡通/Q版风格");
        }

        @Test
        @DisplayName("有模板 → 展示模板 code 和 name")
        void withTemplate_showsTemplateInfo() {
            RagContext ctx = RagContext.empty().withTemplate(sampleTemplate);

            String info = assembler.buildDebugInfo(ctx);

            assertThat(info).contains("children-illustration");
            assertThat(info).contains("儿童绘本插画");
        }
    }

    @Nested
    @DisplayName("buildDebugData(context, enhancedPrompt)：结构化调试数据")
    class BuildDebugData {

        /**
         * 目的：结构化数据应包含 enhancedPrompt 字段。
         */
        @Test
        @DisplayName("enhancedPrompt 原样保留在 debug data 中")
        void includesEnhancedPrompt() {
            String enhanced = "这是增强后的完整Prompt，含RAG上下文...";
            Map<String, Object> data = assembler.buildDebugData(RagContext.empty(), enhanced);

            assertThat(data).containsKey("enhancedPrompt");
            assertThat(data.get("enhancedPrompt")).isEqualTo(enhanced);
        }

        /**
         * 目的：检索图列表应包含 pictureId 和 name。
         */
        @Test
        @DisplayName("retrievedPictures 含 pictureId 和 name")
        void retrievedPictures_hasIdAndName() {
            RagContext ctx = RagContext.empty()
                    .addRetrieved(new RagContext.ReferencePicture(samplePic, sampleProfile));

            @SuppressWarnings("unchecked")
            Map<String, Object> data = assembler.buildDebugData(ctx, "test");

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> retrieved = (List<Map<String, Object>>) data.get("retrievedPictures");
            assertThat(retrieved).hasSize(1);
            assertThat(retrieved.get(0)).containsEntry("pictureId", 1L);
            assertThat(retrieved.get(0)).containsEntry("name", "BIGBANG Q版合影");
            assertThat(retrieved.get(0)).containsEntry("style", "卡通/Q版风格");
        }

        /**
         * 目的：未命名图片应回退为 "未命名"。
         */
        @Test
        @DisplayName("无名称图片 → name 回退为'未命名'")
        void unnamedPicture_fallbackName() {
            GalleryPicture noName = new GalleryPicture(
                    99L, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null,
                    null, false, null, null
            );
            RagContext ctx = RagContext.empty()
                    .addExplicit(new RagContext.ReferencePicture(noName, null));

            @SuppressWarnings("unchecked")
            Map<String, Object> data = assembler.buildDebugData(ctx, "test");

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> explicit = (List<Map<String, Object>>) data.get("explicitPictures");
            assertThat(explicit.get(0)).containsEntry("name", "未命名");
        }

        /**
         * 目的：无画像时 style 字段不添加到 debug data 中（前端自行处理缺失值）。
         */
        @Test
        @DisplayName("无画像 → style 键不存在")
        void noProfile_noStyleKey() {
            RagContext ctx = RagContext.empty()
                    .addRetrieved(new RagContext.ReferencePicture(samplePic, null));

            @SuppressWarnings("unchecked")
            Map<String, Object> data = assembler.buildDebugData(ctx, "test");

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> retrieved = (List<Map<String, Object>>) data.get("retrievedPictures");
            assertThat(retrieved.get(0)).doesNotContainKey("style");
        }

        /**
         * 目的：匹配的模板应在 debug data 中包含完整字段。
         */
        @Test
        @DisplayName("matchedTemplate 含 code/name/category/scene")
        void matchedTemplate_hasFullFields() {
            RagContext ctx = RagContext.empty().withTemplate(sampleTemplate);

            @SuppressWarnings("unchecked")
            Map<String, Object> data = assembler.buildDebugData(ctx, "test");

            @SuppressWarnings("unchecked")
            Map<String, Object> tmpl = (Map<String, Object>) data.get("matchedTemplate");
            assertThat(tmpl).containsEntry("code", "children-illustration");
            assertThat(tmpl).containsEntry("name", "儿童绘本插画");
            assertThat(tmpl).containsEntry("category", "illustration");
            assertThat(tmpl).containsEntry("scene", "creative");
        }

        /**
         * 目的：无模板时 debug data 不应包含 matchedTemplate 键。
         */
        @Test
        @DisplayName("无模板 → matchedTemplate 键不存在")
        void noTemplate_keyAbsent() {
            Map<String, Object> data = assembler.buildDebugData(RagContext.empty(), "test");

            assertThat(data).doesNotContainKey("matchedTemplate");
        }
    }
}
