package com.zzp.aiagent.service;

import com.zzp.aiagent.domain.rag.RagCandidate;
import com.zzp.aiagent.domain.rag.RagSearchCriteria;

import java.util.List;

public interface HybridGalleryRetriever {

    List<RagCandidate> retrieve(RagSearchCriteria criteria);
}
