package com.zzp.aiagent.rag.enhance;

import java.util.List;

public interface HybridGalleryRetriever {

    List<RagCandidate> retrieve(RagSearchCriteria criteria);
}
