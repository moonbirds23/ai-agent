package com.zzp.aiagent.service;

/**
 * Hybrid (vector + keyword + metadata) gallery reference retriever.
 * Extends {@link ReferenceRetriever} — the single {@code retrieve()} method
 * is inherited from the parent interface.
 */
public interface HybridGalleryRetriever extends ReferenceRetriever {
    // All retriever contract methods are inherited from ReferenceRetriever.
}
