package com.zzp.aiagent.rag;

import com.zzp.aiagent.gallery.GalleryService;
import com.zzp.aiagent.gallery.model.GalleryPicture;
import com.zzp.aiagent.profile.PictureAiProfileService;
import com.zzp.aiagent.profile.model.PictureAiProfile;
import com.zzp.aiagent.rag.model.RagContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 图库 RAG 检索器：对用户需求执行向量相似度检索，从收藏的图库图片中召回相关参考图。
 */
@Component
@Profile("!test")
@Slf4j
public class GalleryRagRetriever {

    private static final int TOP_K = 5;
    private static final double MIN_SCORE = 0.4;
    private static final String DOC_ID_PREFIX = "pic-";

    private final VectorStore vectorStore;
    private final GalleryService galleryService;
    private final PictureAiProfileService profileService;

    public GalleryRagRetriever(@Qualifier("knowledgeVectorStore") VectorStore vectorStore,
                               GalleryService galleryService,
                               PictureAiProfileService profileService) {
        this.vectorStore = vectorStore;
        this.galleryService = galleryService;
        this.profileService = profileService;
    }

    /**
     * 语义检索与用户需求相似的历史收藏图。
     * 检索失败时降级返回空列表，不中断主流程。
     */
    public List<RagContext.ReferencePicture> retrieve(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        try {
            List<Document> docs = vectorStore.similaritySearch(
                    SearchRequest.builder()
                            .query(query)
                            .topK(TOP_K)
                            .similarityThreshold(MIN_SCORE)
                            .build());

            if (docs.isEmpty()) {
                log.info("[GalleryRAG] 无匹配结果 query={}", query);
                return List.of();
            }

            List<RagContext.ReferencePicture> results = new ArrayList<>();
            for (Document doc : docs) {
                Long pictureId = extractPictureId(doc.getId());
                if (pictureId == null) {
                    continue;
                }
                try {
                    GalleryPicture picture = galleryService.getById(pictureId);
                    PictureAiProfile profile = null;
                    try {
                        profile = profileService.getByPictureId(pictureId);
                    } catch (Exception e) {
                        log.debug("[GalleryRAG] 图片画像不存在 pictureId={}", pictureId);
                    }
                    results.add(new RagContext.ReferencePicture(picture, profile));
                } catch (Exception e) {
                    log.debug("[GalleryRAG] 图库图片不存在 pictureId={}", pictureId);
                }
            }
            log.info("[GalleryRAG] 检索到 {} 条参考 (topK={}, query={})", results.size(), TOP_K, query);
            return results;
        } catch (Exception e) {
            log.warn("[GalleryRAG] 检索失败 query={}", query, e);
            return List.of();
        }
    }

    private Long extractPictureId(String docId) {
        if (docId == null || !docId.startsWith(DOC_ID_PREFIX)) {
            return null;
        }
        try {
            return Long.parseLong(docId.substring(DOC_ID_PREFIX.length()));
        } catch (NumberFormatException e) {
            log.warn("[GalleryRAG] 无法解析 docId={}", docId);
            return null;
        }
    }
}
