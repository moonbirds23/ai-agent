package com.zzp.aiagent.vector;

import com.zzp.aiagent.vector.model.VectorSearchHit;

import java.util.List;
import java.util.Map;

public interface VectorIndexService {

    void upsertPictureVector(Long pictureId, String indexText, Map<String, Object> metadata);

    void deletePictureVector(Long pictureId);

    List<VectorSearchHit> search(String query, int topK, double minScore);
}
