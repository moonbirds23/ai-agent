package com.zzp.aiagent.knowledge;

import com.zzp.aiagent.exception.BusinessException;
import com.zzp.aiagent.exception.ErrorCode;
import com.zzp.aiagent.image.ImageDownloadService;
import com.zzp.aiagent.image.VisionAnalysisService;
import com.zzp.aiagent.knowledge.model.AddKnowledgeRequest;
import com.zzp.aiagent.knowledge.model.KnowledgeAsset;
import com.zzp.aiagent.knowledge.storage.AssetStorage;
import com.zzp.aiagent.knowledge.storage.StoredAsset;
import com.zzp.aiagent.model.dto.image.DownloadedImage;
import com.zzp.aiagent.model.dto.image.VisionAnalysisResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
@Profile("!test")
@Slf4j
public class KnowledgeService {

    private static final String DEFAULT_USER = "default";

    private final AssetStorage assetStorage;
    private final VectorStore vectorStore;
    private final VisionAnalysisService visionService;
    private final ImageDownloadService downloadService;

    public KnowledgeService(AssetStorage assetStorage,
                            VectorStore vectorStore,
                            VisionAnalysisService visionService,
                            ImageDownloadService downloadService) {
        this.assetStorage = assetStorage;
        this.vectorStore = vectorStore;
        this.visionService = visionService;
        this.downloadService = downloadService;
    }

    public KnowledgeAsset add(AddKnowledgeRequest request) {
        String assetId = UUID.randomUUID().toString();

        DownloadedImage image = downloadImage(request);
        StoredAsset stored = assetStorage.store(
                new ByteArrayInputStream(image.bytes()), assetId, image.contentType());

        String description;
        String title;
        List<String> tags;
        if (request.description() != null && !request.description().isBlank()) {
            description = request.description();
            title = request.title() != null ? request.title() : "";
            tags = request.tags() != null ? request.tags() : List.of();
        } else {
            VisionAnalysisResult analysis = visionService.analyze(
                    request.title(), request.imageBase64(), request.imageUrl());
            description = buildDescription(analysis);
            title = analysis.subject() != null ? analysis.subject() : "未命名";
            tags = buildTags(analysis);
        }

        Document doc = Document.builder()
                .id(assetId)
                .text(description)
                .metadata(Map.of(
                        "userId", DEFAULT_USER,
                        "title", title,
                        "tags", String.join(",", tags),
                        "storageKey", stored.key(),
                        "storageUrl", stored.url()
                ))
                .build();
        vectorStore.add(List.of(doc));

        log.info("[KnowledgeService] 入库成功 assetId={} title={}", assetId, title);
        return new KnowledgeAsset(assetId, DEFAULT_USER, title, description, tags, stored.url());
    }

    public List<KnowledgeAsset> semanticSearch(String query, int topK) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        try {
            List<Document> docs = vectorStore.similaritySearch(
                    SearchRequest.builder()
                            .query(query)
                            .topK(topK)
                            .similarityThreshold(0.4)
                            .build());
            return docs.stream()
                    .map(this::toAsset)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("[KnowledgeService] 搜索失败 query={}", query, e);
            return List.of();
        }
    }

    public void remove(String assetId) {
        try {
            vectorStore.delete(assetId);
        } catch (Exception e) {
            log.warn("[KnowledgeService] 向量删除失败 assetId={}", assetId, e);
        }
        assetStorage.delete(assetId);
        log.info("[KnowledgeService] 删除成功 assetId={}", assetId);
    }

    private KnowledgeAsset toAsset(Document doc) {
        Map<String, Object> meta = doc.getMetadata();
        String tags = meta.getOrDefault("tags", "").toString();
        List<String> tagList = tags.isBlank()
                ? List.of()
                : List.of(tags.split(","));
        return new KnowledgeAsset(
                doc.getId(),
                meta.getOrDefault("userId", DEFAULT_USER).toString(),
                meta.getOrDefault("title", "").toString(),
                doc.getText(),
                tagList,
                meta.getOrDefault("storageUrl", "").toString()
        );
    }

    private DownloadedImage downloadImage(AddKnowledgeRequest request) {
        if (request.imageUrl() != null && !request.imageUrl().isBlank()) {
            return downloadService.download(request.imageUrl());
        }
        if (request.imageBase64() != null && !request.imageBase64().isBlank()) {
            String stripped = request.imageBase64().trim();
            int comma = stripped.indexOf(',');
            byte[] bytes = Base64.getDecoder().decode(
                    comma >= 0 && stripped.startsWith("data:image/")
                            ? stripped.substring(comma + 1)
                            : stripped);
            return new DownloadedImage(bytes, "image/png", "image.png");
        }
        throw new BusinessException(ErrorCode.PARAMS_ERROR, "请提供图片URL或Base64数据");
    }

    private String buildDescription(VisionAnalysisResult analysis) {
        List<String> parts = new ArrayList<>();
        addPart(parts, "主体", analysis.subject());
        addPart(parts, "场景", analysis.scene());
        addPart(parts, "风格", analysis.style());
        addPart(parts, "色彩", analysis.colors());
        addPart(parts, "构图", analysis.composition());
        addPart(parts, "光影", analysis.lighting());
        addPart(parts, "情绪氛围", analysis.mood());
        if (analysis.imagePrompt() != null && !analysis.imagePrompt().isBlank()) {
            parts.add("生图Prompt: " + analysis.imagePrompt());
        }
        return String.join("; ", parts);
    }

    private List<String> buildTags(VisionAnalysisResult analysis) {
        List<String> tags = new ArrayList<>();
        addTag(tags, analysis.style());
        addTag(tags, analysis.scene());
        addTag(tags, analysis.mood());
        return tags;
    }

    private void addPart(List<String> parts, String label, String value) {
        if (value != null && !value.isBlank()) {
            parts.add(label + ": " + value);
        }
    }

    private void addTag(List<String> tags, String value) {
        if (value != null && !value.isBlank() && value.length() <= 20) {
            tags.add(value.trim());
        }
    }
}
