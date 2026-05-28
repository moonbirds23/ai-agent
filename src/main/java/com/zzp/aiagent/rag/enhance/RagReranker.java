package com.zzp.aiagent.rag.enhance;

import java.util.List;

public interface RagReranker {

    List<RagCandidate> rerank(List<RagCandidate> candidates, RagSearchCriteria criteria);
}
