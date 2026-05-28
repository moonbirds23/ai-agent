package com.zzp.aiagent.rag;

import com.zzp.aiagent.gallery.GalleryService;
import com.zzp.aiagent.gallery.model.GalleryPicture;
import com.zzp.aiagent.profile.PictureAiProfileService;
import com.zzp.aiagent.rag.model.RagContext;
import com.zzp.aiagent.vector.VectorIndexService;
import com.zzp.aiagent.vector.model.VectorSearchHit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("GalleryRagRetriever：向量检索")
@ExtendWith(MockitoExtension.class)
@Tag("integration")
class GalleryRagRetrieverTest {

    @Mock private VectorIndexService vectorIndexService;
    @Mock private GalleryService galleryService;
    @Mock private PictureAiProfileService profileService;

    private GalleryRagRetriever retriever;

    @BeforeEach
    void setUp() {
        retriever = new GalleryRagRetriever(vectorIndexService, galleryService, profileService,
                new RagProperties(true, 5, 0.4, 2500, true));
    }

    private static GalleryPicture samplePic(long id, String name) {
        return new GalleryPicture(
                id, "http://example.com/pic" + id + ".jpg", null, name, null,
                null, null, null, null, null, null, null,
                null, null, null, null, null, false, null, null
        );
    }

    private static VectorSearchHit sampleHit(long picId, double score) {
        return new VectorSearchHit(picId, score, Map.of("score", score));
    }

    @Nested
    @DisplayName("retrieve(query)：检索流程")
    class Retrieve {

        @Test
        @DisplayName("检索到结果 → 查询图库")
        void retrievesAndResolves() {
            VectorSearchHit hit = sampleHit(1L, 0.55);
            when(vectorIndexService.search(anyString(), anyInt(), anyDouble()))
                    .thenReturn(List.of(hit));
            GalleryPicture pic = samplePic(1L, "BIGBANG Q版合影");
            when(galleryService.getById(1L)).thenReturn(pic);
            when(profileService.getByPictureId(1L))
                    .thenThrow(new RuntimeException("无画像"));

            List<RagContext.ReferencePicture> results = retriever.retrieve("卡通偶像团体");

            assertThat(results).hasSize(1);
            assertThat(results.get(0).picture().name()).isEqualTo("BIGBANG Q版合影");
        }

        @Test
        @DisplayName("无匹配 → 返回空列表")
        void noMatch_returnsEmpty() {
            when(vectorIndexService.search(anyString(), anyInt(), anyDouble()))
                    .thenReturn(List.of());

            List<RagContext.ReferencePicture> results = retriever.retrieve("完全无关的查询");

            assertThat(results).isEmpty();
        }

        @Test
        @DisplayName("null 查询 → 返回空列表")
        void nullQuery_returnsEmpty() {
            List<RagContext.ReferencePicture> results = retriever.retrieve(null);

            assertThat(results).isEmpty();
            verify(vectorIndexService, never()).search(anyString(), anyInt(), anyDouble());
        }

        @Test
        @DisplayName("空白查询 → 返回空列表")
        void blankQuery_returnsEmpty() {
            List<RagContext.ReferencePicture> results = retriever.retrieve("   ");

            assertThat(results).isEmpty();
            verify(vectorIndexService, never()).search(anyString(), anyInt(), anyDouble());
        }

        @Test
        @DisplayName("检索到多条结果 → 全部返回")
        void multipleResults_allReturned() {
            when(vectorIndexService.search(anyString(), anyInt(), anyDouble()))
                    .thenReturn(List.of(sampleHit(1L, 0.55), sampleHit(2L, 0.48), sampleHit(3L, 0.42)));
            when(galleryService.getById(1L)).thenReturn(samplePic(1L, "图1"));
            when(galleryService.getById(2L)).thenReturn(samplePic(2L, "图2"));
            when(galleryService.getById(3L)).thenReturn(samplePic(3L, "图3"));
            when(profileService.getByPictureId(1L)).thenThrow(new RuntimeException("无画像"));
            when(profileService.getByPictureId(2L)).thenThrow(new RuntimeException("无画像"));
            when(profileService.getByPictureId(3L)).thenThrow(new RuntimeException("无画像"));

            List<RagContext.ReferencePicture> results = retriever.retrieve("测试查询");

            assertThat(results).hasSize(3);
        }

        @Test
        @DisplayName("VectorIndexService 异常 → 返回空列表")
        void vectorStoreException_returnsEmpty() {
            when(vectorIndexService.search(anyString(), anyInt(), anyDouble()))
                    .thenThrow(new RuntimeException("向量存储连接失败"));

            List<RagContext.ReferencePicture> results = retriever.retrieve("测试");

            assertThat(results).isEmpty();
        }

        @Test
        @DisplayName("图库记录不存在 → 跳过该条")
        void galleryRecordMissing_skipped() {
            when(vectorIndexService.search(anyString(), anyInt(), anyDouble()))
                    .thenReturn(List.of(sampleHit(999L, 0.50)));
            when(galleryService.getById(999L))
                    .thenThrow(new RuntimeException("图片不存在"));

            List<RagContext.ReferencePicture> results = retriever.retrieve("测试");

            assertThat(results).isEmpty();
        }
    }
}
