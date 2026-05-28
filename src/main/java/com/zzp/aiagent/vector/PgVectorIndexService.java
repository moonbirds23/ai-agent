package com.zzp.aiagent.vector;

import com.zzp.aiagent.vector.model.VectorSearchHit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Map;

@Component
@Profile("postgres")
@Slf4j
public class PgVectorIndexService implements VectorIndexService {

    private final VectorStore vectorStore;

    public PgVectorIndexService(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    @Override
    public void upsertPictureVector(Long pictureId, String indexText, Map<String, Object> metadata) {
        Document doc = Document.builder()
                .id("pic-" + pictureId)
                .text(indexText)
                .metadata(metadata)
                .build();
        vectorStore.add(List.of(doc));
        log.debug("[PgVectorIndex] 索引写入 pictureId={}", pictureId);
    }

    @Override
    public void deletePictureVector(Long pictureId) {
        vectorStore.delete("pic-" + pictureId);
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
                        Long pictureId = extractPictureId(doc.getId());
                        return new VectorSearchHit(pictureId, (double) doc.getScore(), doc.getMetadata());
                    })
                    .filter(hit -> hit.pictureId() != null)
                    .toList();
        } catch (Exception e) {
            log.warn("[PgVectorIndex] 检索失败 query={}", query, e);
            return Collections.emptyList();
        }
    }

    private Long extractPictureId(String docId) {
        if (docId == null || !docId.startsWith("pic-")) return null;
        try {
            return Long.parseLong(docId.substring(4));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
