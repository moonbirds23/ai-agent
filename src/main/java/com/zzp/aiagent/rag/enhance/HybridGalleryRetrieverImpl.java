package com.zzp.aiagent.rag.enhance;

import com.zzp.aiagent.gallery.GalleryService;
import com.zzp.aiagent.gallery.model.GalleryPicture;
import com.zzp.aiagent.profile.PictureAiProfileService;
import com.zzp.aiagent.profile.model.PictureAiProfile;
import com.zzp.aiagent.rag.RagProperties;
import com.zzp.aiagent.vector.VectorIndexService;
import com.zzp.aiagent.vector.model.VectorSearchHit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@Profile("postgres")
@Slf4j
public class HybridGalleryRetrieverImpl implements HybridGalleryRetriever {

    private static final int MAX_OVER_SAMPLE = 50;

    private final VectorIndexService vectorIndexService;
    private final GalleryService galleryService;
    private final PictureAiProfileService profileService;
    private final RagProperties ragProperties;

    public HybridGalleryRetrieverImpl(VectorIndexService vectorIndexService,
                                      GalleryService galleryService,
                                      PictureAiProfileService profileService,
                                      RagProperties ragProperties) {
        this.vectorIndexService = vectorIndexService;
        this.galleryService = galleryService;
        this.profileService = profileService;
        this.ragProperties = ragProperties;
    }

    @Override
    public List<RagCandidate> retrieve(RagSearchCriteria criteria) {
        String query = criteria.query();
        if (query == null || query.isBlank()) {
            return List.of();
        }

        int oversample = Math.min(criteria.candidateSize() * 2, MAX_OVER_SAMPLE);
        List<VectorSearchHit> hits;
        try {
            hits = vectorIndexService.search(query, oversample, criteria.minVectorScore());
        } catch (Exception e) {
            log.warn("[Hybrid] 向量检索失败 query={}", query, e);
            return List.of();
        }

        if (hits.isEmpty()) {
            log.info("[Hybrid] 无向量匹配结果 query={}", query);
            return List.of();
        }

        List<RagCandidate> candidates = new ArrayList<>();
        for (VectorSearchHit hit : hits) {
            if (hit.pictureId() == null) continue;
            try {
                GalleryPicture pic = galleryService.getById(hit.pictureId());

                if (Boolean.TRUE.equals(criteria.favoritedOnly())
                        && !Boolean.TRUE.equals(pic.favorited())) {
                    continue;
                }

                PictureAiProfile profile = null;
                try {
                    profile = profileService.getByPictureId(hit.pictureId());
                } catch (Exception ignored) {
                    // profile not available — keyword/metadata scores will be 0
                }

                double vectorScore = hit.vectorScore() != null ? hit.vectorScore() : 0.0;
                double keywordScore = computeKeywordScore(pic, criteria);
                double metadataScore = computeMetadataScore(pic, profile, criteria);

                candidates.add(new RagCandidate(pic, profile,
                        vectorScore, keywordScore, metadataScore,
                        vectorScore, List.of()));

            } catch (Exception e) {
                log.debug("[Hybrid] 图片查找失败 pictureId={}", hit.pictureId(), e);
            }
        }

        log.info("[Hybrid] 检索完成 query={} hits={} candidates={}", query, hits.size(), candidates.size());
        return candidates;
    }

    private double computeKeywordScore(GalleryPicture pic, RagSearchCriteria criteria) {
        double score = 0;

        // tag matching
        if (criteria.tags() != null && !criteria.tags().isEmpty()
                && pic.tags() != null && !pic.tags().isEmpty()) {
            for (String searchTag : criteria.tags()) {
                String lower = searchTag.toLowerCase();
                for (String picTag : pic.tags()) {
                    String picLower = picTag.toLowerCase();
                    if (picLower.equals(lower)) {
                        score += 10;
                    } else if (picLower.contains(lower) || lower.contains(picLower)) {
                        score += 5;
                    }
                }
            }
        }

        // name contains search query keywords
        if (pic.name() != null && criteria.query() != null) {
            String lowerName = pic.name().toLowerCase();
            String lowerQuery = criteria.query().toLowerCase();
            if (lowerName.contains(lowerQuery)) {
                score += 5;
            }
        }

        return score;
    }

    private double computeMetadataScore(GalleryPicture pic, PictureAiProfile profile,
                                        RagSearchCriteria criteria) {
        double score = 0;

        // category match
        if (criteria.category() != null && criteria.category().equalsIgnoreCase(pic.category())) {
            score += 15;
        }

        if (profile == null) return score;

        // style hints match
        if (criteria.styleHints() != null && profile.style() != null) {
            String profileStyle = profile.style().toLowerCase();
            for (String hint : criteria.styleHints()) {
                if (profileStyle.contains(hint.toLowerCase())) {
                    score += 10;
                }
            }
        }

        // color hints match
        if (criteria.colorHints() != null && profile.colors() != null) {
            String profileColors = profile.colors().toLowerCase();
            for (String hint : criteria.colorHints()) {
                if (profileColors.contains(hint.toLowerCase())) {
                    score += 10;
                }
            }
        }

        // composition hints match
        if (criteria.compositionHints() != null && profile.composition() != null) {
            String profileComp = profile.composition().toLowerCase();
            for (String hint : criteria.compositionHints()) {
                if (profileComp.contains(hint.toLowerCase())) {
                    score += 10;
                }
            }
        }

        return score;
    }
}
