package com.zzp.aiagent.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzp.aiagent.common.ThrowUtils;
import com.zzp.aiagent.exception.BusinessException;
import com.zzp.aiagent.exception.ErrorCode;
import com.zzp.aiagent.service.GalleryService;
import com.zzp.aiagent.service.PictureAiProfileService;
import com.zzp.aiagent.model.entity.GalleryPicture;
import com.zzp.aiagent.service.VisionAnalysisService;
import com.zzp.aiagent.model.dto.image.VisionAnalysisResult;
import com.zzp.aiagent.model.entity.PictureAiProfile;
import com.zzp.aiagent.repository.PictureAiProfileRepository;
import com.zzp.aiagent.manager.ObjectStorageService;
import com.zzp.aiagent.manager.VectorIndexService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;

@Service
@Profile("!test")
@Slf4j
public class PictureAiProfileServiceImpl implements PictureAiProfileService {

    private final PictureAiProfileRepository repository;
    private final VectorIndexService vectorIndexService;
    private final VisionAnalysisService visionAnalysisService;
    private final ObjectMapper mapper;

    private final GalleryService galleryService;
    private final ObjectStorageService storageService;

    public PictureAiProfileServiceImpl(PictureAiProfileRepository repository,
                                       VectorIndexService vectorIndexService,
                                       VisionAnalysisService visionAnalysisService,
                                       ObjectMapper mapper,
                                       GalleryService galleryService,
                                       ObjectStorageService storageService) {
        this.repository = repository;
        this.vectorIndexService = vectorIndexService;
        this.visionAnalysisService = visionAnalysisService;
        this.mapper = mapper;
        this.galleryService = galleryService;
        this.storageService = storageService;
    }

    @Override
    public PictureAiProfile analyze(Long pictureId) {
        ThrowUtils.throwIf(pictureId == null || pictureId <= 0,
                ErrorCode.PARAMS_ERROR, "图片ID不合法");

        GalleryPicture picture = galleryService.getById(pictureId);
        byte[] imageBytes = storageService.download(picture.storageKey());

        String ext = picture.picFormat() != null ? picture.picFormat() : "png";
        String contentType = contentTypeFromExt(ext);
        return analyzeDirect(picture, imageBytes, contentType);
    }

    @Override
    public PictureAiProfile analyzeDirect(GalleryPicture picture, byte[] imageBytes, String contentType) {
        ThrowUtils.throwIf(picture == null || picture.id() == null,
                ErrorCode.PARAMS_ERROR, "图片信息不能为空");

        String encoded = Base64.getEncoder().encodeToString(imageBytes);
        String imageBase64 = "data:" + contentType + ";base64," + encoded;
        return doAnalyze(picture, imageBase64, contentType);
    }

    @Override
    public PictureAiProfile analyzeDirectWithBase64(GalleryPicture picture, String base64Data, String contentType) {
        ThrowUtils.throwIf(picture == null || picture.id() == null,
                ErrorCode.PARAMS_ERROR, "图片信息不能为空");
        return doAnalyze(picture, base64Data, contentType);
    }

    private PictureAiProfile doAnalyze(GalleryPicture picture, String imageBase64, String contentType) {
        Long pictureId = picture.id();

        // 1. Call vision analysis
        VisionAnalysisResult analysis = visionAnalysisService.analyze(null, imageBase64, null);

        // 2. Build indexText from GalleryPicture metadata
        String indexText = buildIndexText(picture, analysis);

        // 3. Build and save profile
        PictureAiProfile profile = PictureAiProfile.pending(
                pictureId,
                analysis.subject(),
                analysis.scene(),
                analysis.style(),
                analysis.colors(),
                analysis.composition(),
                analysis.lighting(),
                analysis.mood(),
                analysis.imagePrompt(),
                indexText
        ).withAnalyzedAt(LocalDateTime.now());

        PictureAiProfile saved = repository.save(profile);

        // 4. Index to vector store
        try {
            index(pictureId);
        } catch (Exception e) {
            log.warn("[ProfileService] 向量索引失败 pictureId={}，标记状态", pictureId, e);
            PictureAiProfile failed = saved.withVectorStatus(-1);
            repository.save(failed);
            return failed;
        }

        return repository.findByPictureId(pictureId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PROFILE_OPERATION_FAILED,
                        "画像保存后读取失败 pictureId=" + pictureId));
    }

    @Override
    public PictureAiProfile getByPictureId(Long pictureId) {
        ThrowUtils.throwIf(pictureId == null || pictureId <= 0,
                ErrorCode.PARAMS_ERROR, "图片ID不合法");
        return repository.findByPictureId(pictureId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PROFILE_OPERATION_FAILED,
                        "图片画像不存在 pictureId=" + pictureId));
    }

    @Override
    public List<PictureAiProfile> listByPictureIds(List<Long> pictureIds) {
        ThrowUtils.throwIf(pictureIds == null || pictureIds.isEmpty(),
                ErrorCode.PARAMS_ERROR, "图片ID列表不能为空");
        return repository.findByPictureIds(pictureIds);
    }

    @Override
    public void index(Long pictureId) {
        ThrowUtils.throwIf(pictureId == null || pictureId <= 0,
                ErrorCode.PARAMS_ERROR, "图片ID不合法");

        PictureAiProfile profile = repository.findByPictureId(pictureId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PROFILE_OPERATION_FAILED,
                        "图片画像不存在 pictureId=" + pictureId));

        try {
            Map<String, Object> metadata = Map.of(
                    "pictureId", profile.pictureId(),
                    "subject", nullToEmpty(profile.subject()),
                    "style", nullToEmpty(profile.style()),
                    "colors", nullToEmpty(profile.colors()),
                    "mood", nullToEmpty(profile.mood())
            );
            vectorIndexService.upsertPictureVector(pictureId, profile.indexText(), metadata);

            PictureAiProfile indexed = profile.withVectorStatus(1);
            repository.save(indexed);
            log.info("[ProfileService] 向量索引成功 pictureId={}", pictureId);
        } catch (Exception e) {
            log.error("[ProfileService] 向量索引失败 pictureId={}", pictureId, e);
            PictureAiProfile failed = profile.withVectorStatus(-1);
            repository.save(failed);
            throw new BusinessException(ErrorCode.PROFILE_OPERATION_FAILED,
                    "向量索引失败 pictureId=" + pictureId + ": " + e.getMessage());
        }
    }

    @Override
    public void removeIndex(Long pictureId) {
        ThrowUtils.throwIf(pictureId == null || pictureId <= 0,
                ErrorCode.PARAMS_ERROR, "图片ID不合法");

        try {
            vectorIndexService.deletePictureVector(pictureId);
        } catch (Exception e) {
            log.warn("[ProfileService] 向量删除失败 pictureId={}", pictureId, e);
        }
        repository.deleteByPictureId(pictureId);
        log.info("[ProfileService] 移除索引成功 pictureId={}", pictureId);
    }

    // ───────────────────────────── private helpers ─────────────────────────────

    private String buildIndexText(GalleryPicture picture, VisionAnalysisResult analysis) {
        StringBuilder sb = new StringBuilder();
        appendIfPresent(sb, "名称", picture.name());
        appendIfPresent(sb, "简介", picture.introduction());
        appendIfPresent(sb, "分类", picture.category());
        if (picture.tags() != null && !picture.tags().isEmpty()) {
            appendIfPresent(sb, "标签", String.join(", ", picture.tags()));
        }
        appendIfPresent(sb, "主体", analysis.subject());
        appendIfPresent(sb, "场景", analysis.scene());
        appendIfPresent(sb, "风格", analysis.style());
        appendIfPresent(sb, "色彩", analysis.colors());
        appendIfPresent(sb, "构图", analysis.composition());
        appendIfPresent(sb, "光影", analysis.lighting());
        appendIfPresent(sb, "氛围", analysis.mood());
        appendIfPresent(sb, "可复用 Prompt", analysis.imagePrompt());
        return sb.toString().trim();
    }

    private static void appendIfPresent(StringBuilder sb, String label, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        if (!sb.isEmpty()) {
            sb.append('\n');
        }
        sb.append(label).append("：").append(value);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String contentTypeFromExt(String ext) {
        return switch (ext.toLowerCase()) {
            case "jpg", "jpeg" -> "image/jpeg";
            case "webp" -> "image/webp";
            case "gif" -> "image/gif";
            case "bmp" -> "image/bmp";
            default -> "image/png";
        };
    }
}
