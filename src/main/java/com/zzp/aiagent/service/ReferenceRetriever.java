package com.zzp.aiagent.service;

import com.zzp.aiagent.domain.rag.RagCandidate;
import com.zzp.aiagent.domain.rag.RagSearchCriteria;

import java.util.List;

/**
 * Core abstraction for reference picture retrieval.
 * Implementations can use vector search, keyword search, or hybrid approaches.
 */
public interface ReferenceRetriever {

    /**
     * Retrieve reference candidates matching the given search criteria.
     *
     * @param criteria search parameters (query, filters, limits, mode)
     * @return list of RAG candidates (may be empty)
     */
    List<RagCandidate> retrieve(RagSearchCriteria criteria);
}
