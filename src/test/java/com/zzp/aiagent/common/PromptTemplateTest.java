package com.zzp.aiagent.common;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <h3>测试目的</h3>
 * 验证 PromptTemplate 的模板加载和渲染逻辑。
 *
 * <h3>测试分类</h3>
 * 单元测试。PromptTemplate 的无参构造会自动扫描 classpath:prompts/ 下的 .st 文件，
 * 测试依赖实际模板文件（generation_with_rag.st 等）。
 *
 * <h3>关键验证点</h3>
 * - 变量原位替换：{key} → value
 * - 未匹配的占位符保持原样（不抛异常）
 * - mode 不存在时回退到 default
 * - 模板不存在时返回空字符串
 * - 奇数个 keyValue 参数安全处理
 */
@DisplayName("PromptTemplate：提示词模板引擎")
@Tag("unit")
class PromptTemplateTest {

    private PromptTemplate promptTemplate;

    @BeforeEach
    void setUp() {
        promptTemplate = new PromptTemplate();
    }

    @Nested
    @DisplayName("render(mode, name, keyValues...)：变量替换")
    class Render {

        /**
         * 目的：验证 {userInput} 被正确替换为实际值。
         */
        @Test
        @DisplayName("单变量替换 → {userInput} 被替换")
        void singleVariableReplaced() {
            String result = promptTemplate.render("default", "generation_with_rag",
                    "userInput", "帮我生成卡通图",
                    "explicitReferences", "",
                    "retrievedReferences", "",
                    "styleTemplate", "");

            assertThat(result).contains("帮我生成卡通图");
            assertThat(result).doesNotContain("{userInput}");
        }

        /**
         * 目的：多变量同时替换，全部生效。
         */
        @Test
        @DisplayName("多变量替换 → 全部占位符被替换")
        void multipleVariablesReplaced() {
            String result = promptTemplate.render("default", "generation_with_rag",
                    "userInput", "生成偶像团体图",
                    "explicitReferences", "参考图1：BIGBANG",
                    "retrievedReferences", "检索图1：Q版合影",
                    "styleTemplate", "模板：PPT商务");

            assertThat(result).contains("生成偶像团体图");
            assertThat(result).contains("参考图1：BIGBANG");
            assertThat(result).contains("检索图1：Q版合影");
            assertThat(result).contains("模板：PPT商务");
            // 不应残留未替换的占位符
            assertThat(result).doesNotContain("{userInput}");
            assertThat(result).doesNotContain("{explicitReferences}");
        }

        /**
         * 目的：system.st 模板渲染时 {outputFormat} 替换为 JSON Schema 不抛异常。
         * 这是之前 StringTemplate4 的坑 — 现在用简单字符串替换，不会有问题。
         */
        @Test
        @DisplayName("system.st 含 JSON Schema → 不抛异常")
        void jsonSchemaInSystemPrompt_noException() {
            String jsonSchema = "{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"}}}";

            String result = promptTemplate.render("default", "system",
                    "outputFormat", jsonSchema);

            assertThat(result).isNotEmpty();
            assertThat(result).contains(jsonSchema);
        }
    }

    @Nested
    @DisplayName("模板回退与容错")
    class FallbackAndTolerance {

        /**
         * 目的：不存在的 mode 应回退到 default 目录下的同名模板。
         */
        @Test
        @DisplayName("不存在的 mode → 回退到 default")
        void nonExistentMode_fallsBackToDefault() {
            // 假设 default/system.st 存在，nonexistent/system.st 不存在
            String result = promptTemplate.render("nonexistent", "system",
                    "outputFormat", "test");

            // 回退到 default/system.st 后应正常渲染
            assertThat(result).isNotEmpty();
            assertThat(result).contains("test");
        }

        /**
         * 目的：完全不存在的模板返回空字符串，不抛异常。
         */
        @Test
        @DisplayName("不存在的模板 → 返回空字符串")
        void nonExistentTemplate_returnsEmpty() {
            String result = promptTemplate.render("default", "nonexistent_template_xyz");

            assertThat(result).isEqualTo("");
        }

        /**
         * 目的：奇数个 keyValue 参数时，最后一个孤立的 key 被忽略，不抛异常。
         */
        @Test
        @DisplayName("奇数个参数 → 忽略最后一个 key，不抛异常")
        void oddKeyValues_ignoresLastKey() {
            String result = promptTemplate.render("default", "system",
                    "outputFormat", "test",
                    "orphanKey"  // 无对应 value
            );

            assertThat(result).isNotEmpty();
        }

        /**
         * 目的：部分占位符未在 keyValues 中出现时，保持原文不变，不抛异常。
         */
        @Test
        @DisplayName("未匹配的占位符 → 保持原样")
        void unmatchedPlaceholder_keptAsIs() {
            String result = promptTemplate.render("default", "generation_with_rag",
                    "userInput", "hello");

            // {explicitReferences}、{retrievedReferences}、{styleTemplate} 未被替换
            // 应保持原样（不抛异常）
            assertThat(result).contains("hello");
            assertThat(result).doesNotContain("{userInput}");
            // 其他占位符可能保留（取决于模板中是否存在）
        }
    }

    @Nested
    @DisplayName("模板内容完整性")
    class TemplateContent {

        /**
         * 目的：验证 system.st 包含输出格式约束的核心指令。
         */
        @Test
        @DisplayName("system.st → 含图片生成助手定位")
        void systemTemplate_hasAssistantRole() {
            String result = promptTemplate.render("default", "system",
                    "outputFormat", "{}");

            assertThat(result).isNotEmpty();
            // 核心角色定位
            assertThat(result).contains("图片生成");
        }

        /**
         * 目的：验证 generation_with_rag.st 包含三层参考图结构。
         */
        @Test
        @DisplayName("generation_with_rag.st → 含三层参考结构")
        void ragTemplate_hasLayerStructure() {
            String result = promptTemplate.render("default", "generation_with_rag",
                    "userInput", "hello",
                    "explicitReferences", "REF_A",
                    "retrievedReferences", "REF_B",
                    "styleTemplate", "TMPL_C");

            assertThat(result).contains("明确参考图");
            assertThat(result).contains("历史收藏图参考");
            assertThat(result).contains("系统风格模板");
        }
    }
}
