package com.zzp.aiagent.rag;

import com.zzp.aiagent.gallery.GalleryService;
import com.zzp.aiagent.gallery.model.GalleryPicture;
import com.zzp.aiagent.profile.PictureAiProfileService;
import com.zzp.aiagent.profile.model.PictureAiProfile;
import com.zzp.aiagent.rag.model.RagContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * <h3>测试目的</h3>
 * 验证 ExplicitReferenceResolver 的图片 ID 解析逻辑：
 * - 根据 ID 列表查询图库和画像服务
 * - 图片不存在时静默跳过
 * - 画像不存在时创建 stub
 * - 异常时降级返回空列表
 *
 * <h3>测试分类</h3>
 * 集成测试（mock 依赖 GalleryService 和 PictureAiProfileService）。
 */
@DisplayName("ExplicitReferenceResolver：明确参考图解析")
@ExtendWith(MockitoExtension.class)
@Tag("integration")
class ExplicitReferenceResolverTest {

    @Mock private GalleryService galleryService;
    @Mock private PictureAiProfileService profileService;

    private ExplicitReferenceResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new ExplicitReferenceResolver(galleryService, profileService);
    }

    private static GalleryPicture samplePic(long id, String name) {
        return new GalleryPicture(
                id, "http://example.com/pic" + id + ".jpg", null, name, "简介",
                "插画", List.of("卡通"), 1024L, 800, 600, 1.33, "png",
                1L, 0L, 1, "#FFF", "upload", false,
                LocalDateTime.now(), LocalDateTime.now()
        );
    }

    @Nested
    @DisplayName("resolve(pictureIds)：解析流程")
    class Resolve {

        /**
         * 目的：正常流程：图库中有图，画像存在 → 返回完整 ReferencePicture。
         */
        @Test
        @DisplayName("图库+画像都有 → 返回完整 ReferencePicture")
        void fullData_returnsCompleteRef() {
            long picId = 1L;
            GalleryPicture pic = samplePic(picId, "测试图");
            PictureAiProfile profile = new PictureAiProfile(
                    picId, "主体", "场景", "卡通", "暖色",
                    "横向", "柔和", "温馨", "prompt",
                    "indexText", 1, LocalDateTime.now()
            );
            when(galleryService.listByIds(List.of(picId))).thenReturn(List.of(pic));
            when(profileService.getByPictureId(picId)).thenReturn(profile);

            List<RagContext.ReferencePicture> result = resolver.resolve(List.of(picId));

            assertThat(result).hasSize(1);
            assertThat(result.get(0).picture().name()).isEqualTo("测试图");
            assertThat(result.get(0).profile()).isNotNull();
            assertThat(result.get(0).profile().style()).isEqualTo("卡通");
        }

        /**
         * 目的：画像不存在时创建 stub，保证流程不中断。
         */
        @Test
        @DisplayName("画像不存在 → 创建 stub，流程不中断")
        void noProfile_createsStub() {
            long picId = 1L;
            GalleryPicture pic = samplePic(picId, "无画像图");
            when(galleryService.listByIds(List.of(picId))).thenReturn(List.of(pic));
            when(profileService.getByPictureId(picId))
                    .thenThrow(new RuntimeException("画像文件不存在"));

            List<RagContext.ReferencePicture> result = resolver.resolve(List.of(picId));

            assertThat(result).hasSize(1);
            assertThat(result.get(0).picture().name()).isEqualTo("无画像图");
            assertThat(result.get(0).profile()).isNotNull(); // stub
            assertThat(result.get(0).profile().vectorStatus()).isEqualTo(0); // pending
        }

        /**
         * 目的：图库中不存在的 ID 静默跳过，不影响其他有效 ID。
         */
        @Test
        @DisplayName("部分 ID 不存在 → 跳过，不影响有效 ID")
        void missingIds_skipped() {
            GalleryPicture pic1 = samplePic(1L, "存在的图");
            when(galleryService.listByIds(List.of(1L, 999L))).thenReturn(List.of(pic1));
            when(profileService.getByPictureId(1L))
                    .thenThrow(new RuntimeException("无画像"));

            List<RagContext.ReferencePicture> result = resolver.resolve(List.of(1L, 999L));

            // 只解析出存在的 ID=1，ID=999 静默跳过
            assertThat(result).hasSize(1);
            assertThat(result.get(0).picture().id()).isEqualTo(1L);
        }

        /**
         * 目的：null 输入返回空列表。
         */
        @Test
        @DisplayName("null 输入 → 返回空列表")
        void nullInput_returnsEmpty() {
            List<RagContext.ReferencePicture> result = resolver.resolve(null);

            assertThat(result).isEmpty();
        }

        /**
         * 目的：空列表输入返回空列表。
         */
        @Test
        @DisplayName("空列表 → 返回空列表")
        void emptyList_returnsEmpty() {
            List<RagContext.ReferencePicture> result = resolver.resolve(List.of());

            assertThat(result).isEmpty();
        }

        /**
         * 目的：GalleryService 全量抛异常时降级返回空列表，不中断主流程。
         */
        @Test
        @DisplayName("GalleryService 异常 → 降级返回空列表")
        void galleryServiceException_returnsEmpty() {
            when(galleryService.listByIds(List.of(1L)))
                    .thenThrow(new RuntimeException("存储异常"));

            List<RagContext.ReferencePicture> result = resolver.resolve(List.of(1L));

            assertThat(result).isEmpty();
        }

        /**
         * 目的：多张图片批量解析，所有存在的图片全部返回。
         */
        @Test
        @DisplayName("批量解析 → 全部存在则全部返回")
        void batchResolve_allReturned() {
            GalleryPicture pic1 = samplePic(1L, "图1");
            GalleryPicture pic2 = samplePic(2L, "图2");
            GalleryPicture pic3 = samplePic(3L, "图3");
            when(galleryService.listByIds(List.of(1L, 2L, 3L)))
                    .thenReturn(List.of(pic1, pic2, pic3));
            when(profileService.getByPictureId(1L))
                    .thenThrow(new RuntimeException("无画像"));
            when(profileService.getByPictureId(2L))
                    .thenThrow(new RuntimeException("无画像"));
            when(profileService.getByPictureId(3L))
                    .thenThrow(new RuntimeException("无画像"));

            List<RagContext.ReferencePicture> result = resolver.resolve(List.of(1L, 2L, 3L));

            assertThat(result).hasSize(3);
            // 全部创建 stub，vectorStatus = 0
            assertThat(result).allMatch(r -> r.profile().vectorStatus() == 0);
        }
    }
}
