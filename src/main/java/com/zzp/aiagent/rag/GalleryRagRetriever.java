package com.zzp.aiagent.rag;

import com.zzp.aiagent.gallery.GalleryService;
import com.zzp.aiagent.gallery.model.GalleryPicture;
import com.zzp.aiagent.profile.PictureAiProfileService;
import com.zzp.aiagent.profile.model.PictureAiProfile;
import com.zzp.aiagent.rag.model.RagContext;
import com.zzp.aiagent.vector.VectorIndexService;
import com.zzp.aiagent.vector.model.VectorSearchHit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@Profile("!test")
@Slf4j
public class GalleryRagRetriever {

    private final VectorIndexService vectorIndexService;
    private final GalleryService galleryService;
    private final PictureAiProfileService profileService;
    private final RagProperties ragProperties;

    public GalleryRagRetriever(VectorIndexService vectorIndexService,
                               GalleryService galleryService,
                               PictureAiProfileService profileService,
                               RagProperties ragProperties) {
        this.vectorIndexService = vectorIndexService;
        this.galleryService = galleryService;
        this.profileService = profileService;
        this.ragProperties = ragProperties;
    }

    public List<RagContext.ReferencePicture> retrieve(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        try {
            List<VectorSearchHit> hits = vectorIndexService.search(
                    query, ragProperties.topK(), ragProperties.minScore());

            if (hits.isEmpty()) {
                log.info("[GalleryRAG] 无匹配结果 query={}", query);
                return List.of();
            }

            List<RagContext.ReferencePicture> results = new ArrayList<>();
            for (VectorSearchHit hit : hits) {
                if (hit.pictureId() == null) continue;
                try {
                    GalleryPicture picture = galleryService.getById(hit.pictureId());
                    PictureAiProfile profile = null;
                    try {
                        profile = profileService.getByPictureId(hit.pictureId());
                    } catch (Exception e) {
                        log.debug("[GalleryRAG] 图片画像不存在 pictureId={}", hit.pictureId());
                    }
                    results.add(new RagContext.ReferencePicture(picture, profile));
                } catch (Exception e) {
                    log.debug("[GalleryRAG] 图库图片不存在 pictureId={}", hit.pictureId());
                }
            }
            log.info("[GalleryRAG] 检索到 {} 条参考 (topK={}, query={})", results.size(), ragProperties.topK(), query);
            return results;
        } catch (Exception e) {
            log.warn("[GalleryRAG] 检索失败 query={}", query, e);
            return List.of();
        }
    }
}
