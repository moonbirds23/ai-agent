package com.zzp.aiagent.profile;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzp.aiagent.common.ThrowUtils;
import com.zzp.aiagent.exception.BusinessException;
import com.zzp.aiagent.exception.ErrorCode;
import com.zzp.aiagent.image.VisionAnalysisService;
import com.zzp.aiagent.model.dto.image.VisionAnalysisResult;
import com.zzp.aiagent.profile.model.PictureAiProfile;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

@Component
@Profile("!test")
@Slf4j
public class PictureAiProfileServiceImpl implements PictureAiProfileService {

    private static final File DATA_DIR = new File("./gallery-data");
    private static final File IMAGES_DIR = new File(DATA_DIR, "images");
    private static final File PICTURES_JSON = new File(DATA_DIR, "pictures.json");

    private static final String VECTOR_DOC_ID_PREFIX = "pic-";

    private final PictureAiProfileRepository repository;
    private final VectorStore vectorStore;
    private final VisionAnalysisService visionAnalysisService;
    private final ObjectMapper mapper;

    public PictureAiProfileServiceImpl(PictureAiProfileRepository repository,
                                       @Qualifier("knowledgeVectorStore") VectorStore vectorStore,
                                       VisionAnalysisService visionAnalysisService) {
        this.repository = repository;
        this.vectorStore = vectorStore;
        this.visionAnalysisService = visionAnalysisService;
        this.mapper = new ObjectMapper();
    }

    @Override
    public PictureAiProfile analyze(Long pictureId) {
        ThrowUtils.throwIf(pictureId == null || pictureId <= 0,
                ErrorCode.PARAMS_ERROR, "图片ID不合法");

        // 1. Parse picture metadata from JSON
        GalleryPictureMeta meta = loadPictureMeta(pictureId);

        // 2. Read image file bytes and encode to base64
        String imageBase64 = readImageBase64(pictureId);

        // 3. Call vision analysis
        VisionAnalysisResult analysis = visionAnalysisService.analyze(null, imageBase64, null);

        // 4. Build indexText
        String indexText = buildIndexText(meta, analysis);

        // 5. Build and save profile
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

        // 6. Index to vector store
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

            Document doc = Document.builder()
                    .id(VECTOR_DOC_ID_PREFIX + pictureId)
                    .text(profile.indexText())
                    .metadata(metadata)
                    .build();

            vectorStore.add(List.of(doc));

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
            vectorStore.delete(VECTOR_DOC_ID_PREFIX + pictureId);
        } catch (Exception e) {
            log.warn("[ProfileService] 向量删除失败 pictureId={}", pictureId, e);
        }
        repository.deleteByPictureId(pictureId);
        log.info("[ProfileService] 移除索引成功 pictureId={}", pictureId);
    }

    // ───────────────────────────── private helpers ─────────────────────────────

    private GalleryPictureMeta loadPictureMeta(Long pictureId) {
        if (!PICTURES_JSON.exists()) {
            log.warn("[ProfileService] pictures.json 不存在，使用空元数据 pictureId={}", pictureId);
            return new GalleryPictureMeta(null, "", "", "", List.of());
        }
        try {
            List<GalleryPictureMeta> pictures = mapper.readValue(PICTURES_JSON,
                    new TypeReference<List<GalleryPictureMeta>>() {});
            return pictures.stream()
                    .filter(p -> pictureId.equals(p.id()))
                    .findFirst()
                    .orElse(new GalleryPictureMeta(pictureId, "", "", "", List.of()));
        } catch (IOException e) {
            log.error("[ProfileService] 解析 pictures.json 失败", e);
            return new GalleryPictureMeta(pictureId, "", "", "", List.of());
        }
    }

    private String readImageBase64(Long pictureId) {
        if (!IMAGES_DIR.exists()) {
            throw new BusinessException(ErrorCode.PROFILE_OPERATION_FAILED,
                    "图片目录不存在: " + IMAGES_DIR.getAbsolutePath());
        }
        File[] candidates = IMAGES_DIR.listFiles((dir, name) -> {
            String base = name.contains(".") ? name.substring(0, name.lastIndexOf('.')) : name;
            return base.equals(String.valueOf(pictureId));
        });
        if (candidates == null || candidates.length == 0) {
            throw new BusinessException(ErrorCode.PROFILE_OPERATION_FAILED,
                    "图片文件不存在 pictureId=" + pictureId + " 目录: " + IMAGES_DIR.getAbsolutePath());
        }
        File imageFile = candidates[0];
        try {
            byte[] bytes = Files.readAllBytes(imageFile.toPath());
            String mimeType = probeMimeType(imageFile.getName());
            String encoded = Base64.getEncoder().encodeToString(bytes);
            return "data:" + mimeType + ";base64," + encoded;
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.PROFILE_OPERATION_FAILED,
                    "读取图片失败 pictureId=" + pictureId + ": " + e.getMessage());
        }
    }

    private String probeMimeType(String fileName) {
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".gif")) return "image/gif";
        return "image/png";
    }

    private String buildIndexText(GalleryPictureMeta meta, VisionAnalysisResult analysis) {
        StringBuilder sb = new StringBuilder();
        appendIfPresent(sb, "名称", meta.name());
        appendIfPresent(sb, "简介", meta.description());
        appendIfPresent(sb, "分类", meta.category());
        if (meta.tags() != null && !meta.tags().isEmpty()) {
            appendIfPresent(sb, "标签", String.join(", ", meta.tags()));
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

    /**
     * 本地 GalleryPicture 元数据记录，用于解析 pictures.json。
     * 字段对齐 Agent A 的 GalleryPicture（id / name / description / category / tags）。
     */
    private record GalleryPictureMeta(
            Long id,
            String name,
            String description,
            String category,
            List<String> tags
    ) {}
}
