package com.zzp.aiagent.manager;

import com.zzp.aiagent.domain.vector.VectorSearchHit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@Profile("!test")
@Slf4j
public class PgVectorIndexService implements VectorIndexService {

    private final VectorStore vectorStore;

    public PgVectorIndexService(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    private static final String METADATA_KEY_PICTURE_ID = "_pictureId";

    @Override
    public void upsertPictureVector(Long pictureId, String indexText, Map<String, Object> metadata) {
        String docId = uuidForPicture(pictureId).toString();
        Map<String, Object> enriched = new java.util.LinkedHashMap<>(metadata != null ? metadata : Map.of());
        enriched.put(METADATA_KEY_PICTURE_ID, pictureId);
        Document doc = Document.builder()
                .id(docId)
                .text(indexText)
                .metadata(enriched)
                .build();
        vectorStore.add(List.of(doc));
        log.debug("[PgVectorIndex] 索引写入 pictureId={} docId={}", pictureId, docId);
    }

    @Override
    public void deletePictureVector(Long pictureId) {
        String docId = uuidForPicture(pictureId).toString();
        vectorStore.delete(docId);
        log.debug("[PgVectorIndex] 索引删除 pictureId={}", pictureId);
    }

    @Override
    public List<VectorSearchHit> search(String query, int topK, double minScore) {
        if (query == null || query.isBlank()) {
            return Collections.emptyList();
        }
        try {
            List<Document> docs = vectorStore.similaritySearch(
                    org.springframework.ai.vectorstore.SearchRequest.builder()
                            .query(query)
                            .topK(topK)
                            .similarityThreshold(minScore)
                            .build());
            return docs.stream()
                    .map(doc -> {
                        Long pictureId = extractPictureId(doc);
                        return new VectorSearchHit(pictureId, (double) doc.getScore(), doc.getMetadata());
                    })
                    .filter(hit -> hit.pictureId() != null)
                    .toList();
        } catch (Exception e) {
            log.warn("[PgVectorIndex] 检索失败 query={}", query, e);
            return Collections.emptyList();
        }
    }

    /**
     * 从 Document 中提取 pictureId，优先读取 metadata，回退解析 docId。
     */
    private Long extractPictureId(Document doc) {
        // 优先从 metadata 读取（uuid 格式的 docId 无法反解）
        if (doc.getMetadata() != null && doc.getMetadata().containsKey(METADATA_KEY_PICTURE_ID)) {
            Object val = doc.getMetadata().get(METADATA_KEY_PICTURE_ID);
            if (val instanceof Long l) return l;
            if (val instanceof Number n) return n.longValue();
            if (val instanceof String s) {
                try { return Long.parseLong(s); } catch (NumberFormatException ignored) {}
            }
        }
        // 回退：兼容旧格式 "pic-{id}"
        String docId = doc.getId();
        if (docId != null && docId.startsWith("pic-")) {
            try { return Long.parseLong(docId.substring(4)); } catch (NumberFormatException ignored) {}
        }
        return null;
    }

    /**
     * 为 pictureId 生成确定性 UUID（PgVectorStore 要求 UUID 格式）。
     */
    static UUID uuidForPicture(Long pictureId) {
        return UUID.nameUUIDFromBytes(("picture:" + pictureId).getBytes(StandardCharsets.UTF_8));
    }
}
