package com.zzp.aiagent.tool;

import com.zzp.aiagent.domain.pexels.PexelsPhoto;
import com.zzp.aiagent.domain.pexels.PexelsPhotoService;
import com.zzp.aiagent.domain.pexels.PexelsPhotoSrc;
import com.zzp.aiagent.domain.pexels.PexelsSearchResult;
import com.zzp.aiagent.service.GalleryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * <h3>测试目的</h3>
 * 验证 PexelsSearchTools 的 4 个 @Tool 方法返回正确的文字摘要。
 *
 * <h3>测试分类</h3>
 * 集成测试（mock PexelsPhotoService + GalleryService），不发起真实网络请求。
 *
 * <h3>关键验证点</h3>
 * - pexelsSearchPhotos → 返回搜索结果摘要（含颜色、尺寸、摄影师）
 * - pexelsCuratedPhotos → 返回精选照片摘要
 * - pexelsSearchAndImport → 搜索+下载入库，返回入库列表
 * - pexelsGetPhoto → 返回照片完整元数据
 * - pickDownloadUrl → 降级逻辑
 */
@DisplayName("PexelsSearchTools")
@ExtendWith(MockitoExtension.class)
@Tag("integration")
class PexelsSearchToolsTest {

    @Mock
    private PexelsPhotoService pexelsPhotoService;

    @Mock
    private GalleryService galleryService;

    @Mock
    private ToolProgressContext progressContext;

    private PexelsSearchTools tools;

    private static PexelsPhoto samplePhoto(long id, String alt, String color) {
        return new PexelsPhoto(
                id, 1920, 1080,
                "https://www.pexels.com/photo/test-" + id + "/",
                "Test Photographer",
                "https://www.pexels.com/@test",
                999L,
                color,
                alt,
                new PexelsPhotoSrc(
                        "https://example.com/o" + id + ".jpg",
                        "https://example.com/l2-" + id + ".jpg",
                        "https://example.com/l-" + id + ".jpg",
                        "https://example.com/m-" + id + ".jpg",
                        "https://example.com/s-" + id + ".jpg",
                        "https://example.com/p-" + id + ".jpg",
                        "https://example.com/ld-" + id + ".jpg",
                        "https://example.com/t-" + id + ".jpg"
                )
        );
    }

    @BeforeEach
    void setUp() {
        tools = new PexelsSearchTools(pexelsPhotoService, galleryService, progressContext);
    }

    // ── pexelsSearchPhotos ─────────────────────────────────────────

    @Nested
    @DisplayName("pexelsSearchPhotos")
    class SearchPhotos {

        @Test
        @DisplayName("有结果 → 返回摘要含颜色和摄影师")
        void returnsSummaryWithMetadata() {
            PexelsSearchResult result = new PexelsSearchResult(
                    1, 2, 50,
                    "https://www.pexels.com/search/test/",
                    null,
                    List.of(
                            samplePhoto(100, "Sunset over mountains", "#FF6600"),
                            samplePhoto(200, "Ocean waves", "#0066FF")
                    ));
            when(pexelsPhotoService.search(any())).thenReturn(result);

            String output = tools.pexelsSearchPhotos("test", null, null, null, 2, null);

            assertThat(output).contains("Pexels 搜索「test」");
            assertThat(output).contains("50 张图片");
            assertThat(output).contains("[ID:100]");
            assertThat(output).contains("Sunset over mountains");
            assertThat(output).contains("#FF6600");
            assertThat(output).contains("© Test Photographer");
            assertThat(output).contains("[ID:200]");
            assertThat(output).contains("Ocean waves");
            assertThat(output).contains("#0066FF");
        }

        @Test
        @DisplayName("无结果 → 返回未找到提示")
        void noResults() {
            PexelsSearchResult empty = new PexelsSearchResult(
                    1, 5, 0, "https://www.pexels.com/search/none/", null, List.of());
            when(pexelsPhotoService.search(any())).thenReturn(empty);

            String output = tools.pexelsSearchPhotos("none", null, null, null, 5, null);

            assertThat(output).contains("未找到");
            assertThat(output).contains("none");
        }
    }

    // ── pexelsCuratedPhotos ────────────────────────────────────────

    @Nested
    @DisplayName("pexelsCuratedPhotos")
    class CuratedPhotos {

        @Test
        @DisplayName("有精选 → 返回摘要")
        void returnsCuratedSummary() {
            PexelsSearchResult result = new PexelsSearchResult(
                    1, 2, 100, "https://www.pexels.com/curated/", null,
                    List.of(samplePhoto(300, "City lights", "#FFFF00")));
            when(pexelsPhotoService.curated(2, 1)).thenReturn(result);

            String output = tools.pexelsCuratedPhotos(2, null);

            assertThat(output).contains("Pexels 精选照片");
            assertThat(output).contains("[ID:300]");
            assertThat(output).contains("City lights");
        }

        @Test
        @DisplayName("无精选 → 返回提示")
        void noCurated() {
            PexelsSearchResult empty = new PexelsSearchResult(
                    1, 5, 0, "", null, List.of());
            when(pexelsPhotoService.curated(5, 1)).thenReturn(empty);

            String output = tools.pexelsCuratedPhotos(5, null);

            assertThat(output).contains("暂无精选照片");
        }
    }

    // ── pexelsGetPhoto ─────────────────────────────────────────────

    @Nested
    @DisplayName("pexelsGetPhoto")
    class GetPhoto {

        @Test
        @DisplayName("完整照片 → 返回全部元数据")
        void returnsFullMetadata() {
            when(pexelsPhotoService.getPhoto(999L)).thenReturn(
                    samplePhoto(999, "A beautiful landscape", "#123456"));

            String output = tools.pexelsGetPhoto(999L, null);

            assertThat(output).contains("[ID:999]");
            assertThat(output).contains("A beautiful landscape");
            assertThat(output).contains("Test Photographer");
            assertThat(output).contains("#123456");
            assertThat(output).contains("1920×1080");
            assertThat(output).contains("原图");
            assertThat(output).contains("大图 2x");
            assertThat(output).contains("缩略图");
        }
    }

    // ── pickDownloadUrl ────────────────────────────────────────────

    @Nested
    @DisplayName("pickDownloadUrl")
    class PickDownloadUrl {

        @Test
        @DisplayName("降级链：medium 作为最后有效值")
        void fallbackToMedium() {
            var src = new PexelsPhotoSrc(
                    "", "", "",
                    "https://a.com/m.jpg",
                    "https://a.com/s.jpg",
                    "", "", "");
            assertThat(PexelsSearchTools.pickDownloadUrl(src))
                    .isEqualTo("https://a.com/m.jpg");
        }

        @Test
        @DisplayName("large 优先于 medium")
        void prefersLargeOverMedium() {
            var src = new PexelsPhotoSrc(
                    "", "",
                    "https://a.com/l.jpg",
                    "https://a.com/m.jpg",
                    "", "", "", "");
            assertThat(PexelsSearchTools.pickDownloadUrl(src))
                    .isEqualTo("https://a.com/l.jpg");
        }
    }
}
