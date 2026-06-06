package com.zzp.aiagent.service;

import com.zzp.aiagent.domain.gallery.GalleryImportUrlRequest;
import com.zzp.aiagent.domain.gallery.GalleryPageResult;
import com.zzp.aiagent.model.entity.GalleryPicture;
import com.zzp.aiagent.domain.gallery.GalleryQueryRequest;
import com.zzp.aiagent.domain.gallery.GalleryUploadRequest;

import java.util.List;

public interface GalleryService {

    GalleryPicture upload(GalleryUploadRequest request);

    GalleryPicture importUrl(GalleryImportUrlRequest request);

    GalleryPageResult listAll(GalleryQueryRequest request);

    GalleryPicture getById(Long id);

    List<GalleryPicture> listByIds(List<Long> ids);

    GalleryPicture favorite(Long id, boolean favorited);

    void delete(Long id);

    /** Download original picture bytes from object storage. */
    byte[] downloadPicture(Long pictureId);

    /**
     * Search gallery pictures via hybrid retrieval (vector + keyword + metadata).
     * Delegates to {@link HybridGalleryRetriever#retrieve} for relevance-ranked results.
     */
    List<GalleryPicture> search(String query, int limit);

    /**
     * Update a picture's metadata (name, introduction, category, tags).
     * Only non-null, non-blank fields are updated.
     */
    GalleryPicture update(Long id, String name, String introduction, String category, List<String> tags);
}
