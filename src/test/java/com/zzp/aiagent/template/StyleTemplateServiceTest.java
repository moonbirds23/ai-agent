package com.zzp.aiagent.template;

import com.zzp.aiagent.domain.template.StyleTemplate;
import com.zzp.aiagent.domain.template.StyleTemplateProperties;
import com.zzp.aiagent.service.StyleTemplateService;
import com.zzp.aiagent.service.impl.StyleTemplateServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <h3>测试目的</h3>
 * 验证 StyleTemplateService 的三种核心能力：
 * - listAll：返回全部预设模板
 * - getByCode：按编码精确查找
 * - match：关键词双向子串匹配，按最高分返回最佳模板
 *
 * <h3>测试分类</h3>
 * 纯单元测试。通过手动构造模板列表注入，不依赖 Spring 和 YAML 文件。
 *
 * <h3>关键验证点</h3>
 * - 关键词双向子串匹配：keyword 包含 word 或 word 包含 keyword 均计分
 * - 多模板竞争时返回最高分
 * - 空输入/null 输入安全处理
 */
@DisplayName("StyleTemplateService：风格模板匹配")
@Tag("unit")
class StyleTemplateServiceTest {

    private StyleTemplateService service;

    @BeforeEach
    void setUp() {
        // 手动构造，不依赖 Spring Boot 配置绑定
        List<StyleTemplate> templates = List.of(
                new StyleTemplate("ppt-business-flat", "PPT 商务扁平插画", "work_study",
                        "ppt", List.of("PPT", "商务", "汇报", "扁平"), "扁平矢量插画", "避免写实", "16:9"),
                new StyleTemplate("children-illustration", "儿童绘本插画", "creative",
                        "illustration", List.of("儿童", "绘本", "可爱", "童话", "动物"), "柔和温暖", "避免尖锐", "1:1"),
                new StyleTemplate("japanese-fresh-life", "日系清新生活记录", "life",
                        "photography", List.of("日系", "清新", "生活", "胶片"), "自然光摄影", null, "3:4"),
                new StyleTemplate("guochao-festival", "国潮节日海报", "creative",
                        "poster", List.of("国潮", "节日", "中国风", "红色"), "国潮插画", "避免沉闷", "9:16"),
                new StyleTemplate("cute-sticker", "社交媒体可爱贴纸风", "creative",
                        "social", List.of("贴纸", "可爱", "卡通", "手绘", "涂鸦"), "手绘贴纸", null, "1:1")
        );

        StyleTemplateProperties props = new StyleTemplateProperties();
        props.setStyles(templates);
        service = new StyleTemplateServiceImpl(props);
    }

    @Nested
    @DisplayName("listAll：列出全部模板")
    class ListAll {

        /**
         * 目的：验证 5 个模板全部返回，且保持原始顺序。
         */
        @Test
        @DisplayName("返回全部 5 个模板")
        void returnsAllFive() {
            List<StyleTemplate> all = service.listAll();

            assertThat(all).hasSize(5);
            assertThat(all.get(0).code()).isEqualTo("ppt-business-flat");
            assertThat(all.get(4).code()).isEqualTo("cute-sticker");
        }
    }

    @Nested
    @DisplayName("getByCode：按编码精确查找")
    class GetByCode {

        /**
         * 目的：存在的 code 返回 Optional.of(template)。
         */
        @Test
        @DisplayName("存在的 code → 返回该模板")
        void existingCode_returnsTemplate() {
            Optional<StyleTemplate> result = service.getByCode("children-illustration");

            assertThat(result).isPresent();
            assertThat(result.get().name()).isEqualTo("儿童绘本插画");
        }

        /**
         * 目的：不存在的 code 返回 Optional.empty()。
         */
        @Test
        @DisplayName("不存在的 code → Optional.empty")
        void nonExistentCode_returnsEmpty() {
            Optional<StyleTemplate> result = service.getByCode("non-existent-code");

            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("match(userInput)：关键词匹配")
    class Match {

        /**
         * 目的："卡通风格" 应匹配到 "可爱"、"卡通" 等关键词，命中 children-illustration 或 cute-sticker。
         * 验证返回最佳匹配（高分者）。
         */
        @Test
        @DisplayName("'卡通风格' → 匹配到含'卡通'关键词的模板")
        void cartoonStyle_matches() {
            Optional<StyleTemplate> result = service.match("卡通风格");

            assertThat(result).isPresent();
            assertThat(result.get().code()).isIn("children-illustration", "cute-sticker");
        }

        /**
         * 目的："日系" 精确命中 japanese-fresh-life 的关键词。
         */
        @Test
        @DisplayName("'日系' → 匹配 japanese-fresh-life")
        void japanese_matchesExactly() {
            Optional<StyleTemplate> result = service.match("日系");

            assertThat(result).isPresent();
            assertThat(result.get().code()).isEqualTo("japanese-fresh-life");
        }

        /**
         * 目的："PPT" 应匹配 ppt-business-flat（唯一含 PPT 关键词的模板）。
         */
        @Test
        @DisplayName("'PPT' → 匹配 ppt-business-flat")
        void ppt_matchesBusinessFlat() {
            Optional<StyleTemplate> result = service.match("PPT");

            assertThat(result).isPresent();
            assertThat(result.get().code()).isEqualTo("ppt-business-flat");
        }

        /**
         * 目的："我要做一个商务汇报PPT" — "商务"命中 ppt-business-flat，"汇报"也命中同一模板，得分最高。
         */
        @Test
        @DisplayName("'我要做一个商务汇报PPT' → ppt-business-flat 得分最高")
        void businessReport_matchesHighestScore() {
            Optional<StyleTemplate> result = service.match("我要做一个商务汇报PPT");

            assertThat(result).isPresent();
            assertThat(result.get().code()).isEqualTo("ppt-business-flat");
        }

        /**
         * 目的："国潮新年海报" — "国潮"命中 guochao-festival，"海报"非关键词但 "国潮" 已命中。
         */
        @Test
        @DisplayName("'国潮新年海报' → 匹配 guochao-festival")
        void guochao_matches() {
            Optional<StyleTemplate> result = service.match("国潮新年海报");

            assertThat(result).isPresent();
            assertThat(result.get().code()).isEqualTo("guochao-festival");
        }

        /**
         * 目的：无关输入无法匹配任何模板，返回 empty。
         */
        @Test
        @DisplayName("无关输入 → Optional.empty")
        void irrelevantInput_noMatch() {
            Optional<StyleTemplate> result = service.match("量子力学与黑洞理论");

            assertThat(result).isEmpty();
        }

        /**
         * 目的：null 输入安全返回 empty。
         */
        @Test
        @DisplayName("null 输入 → Optional.empty")
        void nullInput_returnsEmpty() {
            Optional<StyleTemplate> result = service.match(null);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("空字符串 → Optional.empty")
        void blankInput_returnsEmpty() {
            Optional<StyleTemplate> result = service.match("   ");

            assertThat(result).isEmpty();
        }

        /**
         * 目的：双向子串匹配 — keyword 包含 word 或 word 包含 keyword 都能命中。
         * "儿童" 是 children-illustration 的精确关键词。
         */
        @Test
        @DisplayName("'儿童教育绘本' 含'儿童'+'绘本' → 匹配 children-illustration")
        void bidirectionalMatch_works() {
            Optional<StyleTemplate> result = service.match("儿童教育绘本");

            assertThat(result).isPresent();
            assertThat(result.get().code()).isEqualTo("children-illustration");
        }

        /**
         * 目的：多个模板竞争时，返回关键词命中数最多的。
         * "可爱卡通贴纸" — cute-sticker 命中"可爱""卡通""贴纸"(3), children-illustration 命中"可爱""卡通"(2)。
         */
        @Test
        @DisplayName("'可爱卡通贴纸' → cute-sticker(3分) > children-illustration(2分)")
        void highestScoreWins() {
            Optional<StyleTemplate> result = service.match("可爱卡通贴纸");

            assertThat(result).isPresent();
            assertThat(result.get().code()).isEqualTo("cute-sticker");
        }
    }
}
