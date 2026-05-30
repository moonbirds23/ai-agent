package com.zzp.aiagent.rag;

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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <h3>测试目的</h3>
 * 验证 RagContext 模型的三层数据结构管理和 isEmpty 判断逻辑。
 *
 * <h3>测试分类</h3>
 * 纯单元测试，不依赖 Spring 容器和外部资源。
 *
 * <h3>关键验证点</h3>
 * - empty() 创建全空上下文，isEmpty() = true
 * - addExplicit / addRetrieved 正确追加参考图
 * - withTemplate 设置/覆盖风格模板
 * - ReferencePicture 组合 GalleryPicture + PictureAiProfile
 * - isEmpty 判断：三者全空才为 true
 */
@DisplayName("RagContext 模型")
@Tag("unit")
class RagContextTest {

    private GalleryPicture samplePic;
    private PictureAiProfile sampleProfile;

    @BeforeEach
    void setUp() {
        samplePic = new GalleryPicture(
                1L, "http://example.com/pic1.jpg", null, "测试图片", "简介",
                "插画", List.of("卡通", "可爱"), 1024L, 800, 600, 1.33, "png",
                1L, 0L, 1, "#FFFFFF", "upload", false,
                LocalDateTime.now(), LocalDateTime.now(), "MAIN"
        );
        sampleProfile = new PictureAiProfile(
                1L, "测试主体", "测试场景", "卡通风格", "暖色调",
                "居中构图", "柔和光线", "温馨氛围", "可复用的Prompt",
                "主体：测试主体\n风格：卡通风格\n", 1, LocalDateTime.now()
        );
    }

    @Nested
    @DisplayName("empty()：创建空上下文")
    class Empty {

        /**
         * 目的：empty() 工厂返回的三层数据应全为空。
         * 结果：explicitReferences=[], retrievedReferences=[], template=null, isEmpty()=true。
         */
        @Test
        @DisplayName("三层数据全空，isEmpty() = true")
        void allEmpty() {
            RagContext ctx = RagContext.empty();

            assertThat(ctx.getExplicitReferences()).isEmpty();
            assertThat(ctx.getRetrievedReferences()).isEmpty();
            assertThat(ctx.getStyleTemplate()).isNull();
            assertThat(ctx.isEmpty()).isTrue();
        }
    }

    @Nested
    @DisplayName("addExplicit：Layer 1 明确参考图")
    class AddExplicit {

        /**
         * 目的：addExplicit 后上下文应包含该参考图，且 isEmpty 返回 false。
         */
        @Test
        @DisplayName("添加明确参考图 → 列表含该图，isEmpty=false")
        void singleRef() {
            RagContext.ReferencePicture ref = new RagContext.ReferencePicture(samplePic, sampleProfile);
            RagContext ctx = RagContext.empty().addExplicit(ref);

            assertThat(ctx.getExplicitReferences()).hasSize(1);
            assertThat(ctx.getExplicitReferences().get(0).picture().name()).isEqualTo("测试图片");
            assertThat(ctx.isEmpty()).isFalse();
        }

        /**
         * 目的：addExplicit(null) 不应影响上下文（防御性忽略 null）。
         */
        @Test
        @DisplayName("addExplicit(null) → 不添加，列表保持空")
        void nullRef_ignored() {
            RagContext ctx = RagContext.empty().addExplicit(null);

            assertThat(ctx.getExplicitReferences()).isEmpty();
            assertThat(ctx.isEmpty()).isTrue();
        }

        /**
         * 目的：多张参考图应全部保留，不覆盖。
         */
        @Test
        @DisplayName("多次 addExplicit → 追加模式，列表含全部")
        void multipleRefs_appended() {
            GalleryPicture pic2 = new GalleryPicture(
                    2L, "http://example.com/pic2.jpg", null, "图片2", null,
                    null, null, null, null, null, null, null,
                    null, null, null, null, null, false, null, null, "MAIN"
            );
            RagContext.ReferencePicture ref1 = new RagContext.ReferencePicture(samplePic, sampleProfile);
            RagContext.ReferencePicture ref2 = new RagContext.ReferencePicture(pic2, null);

            RagContext ctx = RagContext.empty().addExplicit(ref1).addExplicit(ref2);

            assertThat(ctx.getExplicitReferences()).hasSize(2);
        }
    }

    @Nested
    @DisplayName("addRetrieved：Layer 2 RAG 检索图")
    class AddRetrieved {

        /**
         * 目的：addRetrieved 与 addExplicit 操作不同的列表，互不干扰。
         */
        @Test
        @DisplayName("添加到 retrieved 列表，不影响 explicit")
        void separateFromExplicit() {
            RagContext.ReferencePicture ref = new RagContext.ReferencePicture(samplePic, null);
            RagContext ctx = RagContext.empty().addRetrieved(ref);

            assertThat(ctx.getRetrievedReferences()).hasSize(1);
            assertThat(ctx.getExplicitReferences()).isEmpty();
            assertThat(ctx.isEmpty()).isFalse();
        }
    }

    @Nested
    @DisplayName("withTemplate：Layer 3 风格模板")
    class WithTemplate {

        /**
         * 目的：设置模板后 getStyleTemplate() 返回该模板，isEmpty=false。
         */
        @Test
        @DisplayName("设置风格模板 → 上下文含模板，isEmpty=false")
        void setTemplate() {
            StyleTemplate tmpl = new StyleTemplate("test-code", "测试模板", "测试场景",
                    "插画", List.of("测试", "模板"), "正面提示词", "负面提示词", "1:1");
            RagContext ctx = RagContext.empty().withTemplate(tmpl);

            assertThat(ctx.getStyleTemplate()).isNotNull();
            assertThat(ctx.getStyleTemplate().code()).isEqualTo("test-code");
            assertThat(ctx.getStyleTemplate().name()).isEqualTo("测试模板");
            assertThat(ctx.isEmpty()).isFalse();
        }
    }

    @Nested
    @DisplayName("isEmpty：组合判断")
    class IsEmpty {

        /**
         * 目的：只要三层中任意一层有数据，isEmpty 就返回 false。
         */
        @Test
        @DisplayName("仅 explicit 有数据 → isEmpty=false")
        void onlyExplicit_isNotEmpty() {
            RagContext ctx = RagContext.empty()
                    .addExplicit(new RagContext.ReferencePicture(samplePic, null));

            assertThat(ctx.isEmpty()).isFalse();
        }

        @Test
        @DisplayName("仅 retrieved 有数据 → isEmpty=false")
        void onlyRetrieved_isNotEmpty() {
            RagContext ctx = RagContext.empty()
                    .addRetrieved(new RagContext.ReferencePicture(samplePic, null));

            assertThat(ctx.isEmpty()).isFalse();
        }

        @Test
        @DisplayName("仅 template 有数据 → isEmpty=false")
        void onlyTemplate_isNotEmpty() {
            StyleTemplate tmpl = new StyleTemplate("c", "n", "s", "cat",
                    List.of(), "p", null, null);
            RagContext ctx = RagContext.empty().withTemplate(tmpl);

            assertThat(ctx.isEmpty()).isFalse();
        }

        @Test
        @DisplayName("三层全有 → isEmpty=false")
        void allLayersFilled_isNotEmpty() {
            RagContext.ReferencePicture ref = new RagContext.ReferencePicture(samplePic, sampleProfile);
            StyleTemplate tmpl = new StyleTemplate("c", "n", "s", "cat",
                    List.of(), "p", null, null);

            RagContext ctx = RagContext.empty()
                    .addExplicit(ref)
                    .addRetrieved(ref)
                    .withTemplate(tmpl);

            assertThat(ctx.isEmpty()).isFalse();
        }
    }

    @Nested
    @DisplayName("ReferencePicture：组合 record")
    class ReferencePictureRecord {

        /**
         * 目的：验证 ReferencePicture 正确组合 GalleryPicture 和可选的 PictureAiProfile。
         */
        @Test
        @DisplayName("picture + profile 组合存储")
        void withProfile() {
            RagContext.ReferencePicture ref = new RagContext.ReferencePicture(samplePic, sampleProfile);

            assertThat(ref.picture().id()).isEqualTo(1L);
            assertThat(ref.picture().name()).isEqualTo("测试图片");
            assertThat(ref.profile()).isNotNull();
            assertThat(ref.profile().style()).isEqualTo("卡通风格");
        }

        @Test
        @DisplayName("仅 picture，profile 为 null")
        void withoutProfile() {
            RagContext.ReferencePicture ref = new RagContext.ReferencePicture(samplePic, null);

            assertThat(ref.picture()).isNotNull();
            assertThat(ref.profile()).isNull();
        }
    }
}
