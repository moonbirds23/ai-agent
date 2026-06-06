package com.zzp.aiagent.service.impl;

import com.zzp.aiagent.service.PictureAiProfileService;
import com.zzp.aiagent.model.entity.GalleryPicture;
import com.zzp.aiagent.model.entity.PictureAiProfile;
import com.zzp.aiagent.domain.rag.RagProperties;
import com.zzp.aiagent.domain.rag.RagCandidate;
import com.zzp.aiagent.domain.rag.RagSearchCriteria;
import com.zzp.aiagent.service.HybridGalleryRetriever;
import com.zzp.aiagent.exception.BusinessException;
import com.zzp.aiagent.manager.VectorIndexService;
import com.zzp.aiagent.domain.vector.VectorSearchHit;
import com.zzp.aiagent.repository.GalleryPictureRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@Profile("!test")
@Slf4j
public class HybridGalleryRetrieverImpl implements HybridGalleryRetriever {

    private static final int MAX_OVER_SAMPLE = 50;

    private final VectorIndexService vectorIndexService;
    private final GalleryPictureRepository galleryRepository;
    private final PictureAiProfileService profileService;
    private final RagProperties ragProperties;

    public HybridGalleryRetrieverImpl(VectorIndexService vectorIndexService,
                                      GalleryPictureRepository galleryRepository,
                                      PictureAiProfileService profileService,
                                      RagProperties ragProperties) {
        this.vectorIndexService = vectorIndexService;
        this.galleryRepository = galleryRepository;
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
        } catch (BusinessException e) {
            log.warn("[Hybrid] 向量检索失败，降级到关键词搜索 query={}: {}", query, e.getMessage());
            return fallbackToKeyword(criteria);
        }

        if (hits.isEmpty()) {
            log.info("[Hybrid] 无向量匹配结果，回退到关键词搜索 query={}", query);
            return fallbackToKeyword(criteria);
        }

        // Batch load pictures + profiles (avoid N+1)
        List<Long> pictureIds = hits.stream()
                .map(VectorSearchHit::pictureId)
                .filter(id -> id != null)
                .distinct()
                .toList();

        Map<Long, GalleryPicture> pictureMap = new HashMap<>();
        try {
            for (GalleryPicture pic : galleryRepository.findByIds(pictureIds)) {
                if (pic != null && pic.id() != null) {
                    pictureMap.put(pic.id(), pic);
                }
            }
        } catch (Exception e) {
            log.warn("[Hybrid] 批量加载图片失败，回退到关键词搜索: {}", e.getMessage());
            return fallbackToKeyword(criteria);
        }

        Map<Long, PictureAiProfile> profileMap = new HashMap<>();
        try {
            for (PictureAiProfile profile : profileService.listByPictureIds(pictureIds)) {
                if (profile != null && profile.pictureId() != null) {
                    profileMap.put(profile.pictureId(), profile);
                }
            }
        } catch (Exception e) {
            log.debug("[Hybrid] 批量加载画像失败: {}", e.getMessage());
        }

        List<RagCandidate> candidates = new ArrayList<>();
        for (VectorSearchHit hit : hits) {
            if (hit.pictureId() == null) continue;
            GalleryPicture pic = pictureMap.get(hit.pictureId());
            if (pic == null) continue;

            if (Boolean.TRUE.equals(criteria.favoritedOnly())
                    && !Boolean.TRUE.equals(pic.favorited())) {
                continue;
            }

            PictureAiProfile profile = profileMap.get(hit.pictureId());

            double vectorScore = hit.vectorScore() != null ? hit.vectorScore() : 0.0;
            double keywordScore = computeKeywordScore(pic, criteria);
            double metadataScore = computeMetadataScore(pic, profile, criteria);

            candidates.add(new RagCandidate(pic, profile,
                    vectorScore, keywordScore, metadataScore,
                    vectorScore, List.of()));
        }

        log.info("[Hybrid] 检索完成 query={} hits={} candidates={}", query, hits.size(), candidates.size());
        return candidates;
    }

    /**
     * Fallback to SQL keyword search when vector search is unavailable or returns empty.
     */
    private List<RagCandidate> fallbackToKeyword(RagSearchCriteria criteria) {
        try {
            List<GalleryPicture> pictures = galleryRepository.searchByKeyword(
                    criteria.query(), criteria.finalTopK());
            if (pictures.isEmpty()) return List.of();

            // Batch load profiles
            List<Long> ids = pictures.stream().map(GalleryPicture::id).filter(id -> id != null).toList();
            Map<Long, PictureAiProfile> profileMap = new HashMap<>();
            try {
                for (PictureAiProfile profile : profileService.listByPictureIds(ids)) {
                    if (profile != null && profile.pictureId() != null) {
                        profileMap.put(profile.pictureId(), profile);
                    }
                }
            } catch (Exception ignored) {
            }

            return pictures.stream()
                    .map(pic -> new RagCandidate(pic, profileMap.get(pic.id()), 0, 0, 0, 0,
                            List.of("关键词回退")))
                    .toList();
        } catch (Exception e) {
            log.warn("[Hybrid] 关键词回退也失败了 query={}: {}", criteria.query(), e.getMessage());
            return List.of();
        }
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
