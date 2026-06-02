package com.zzp.aiagent.service;

import com.zzp.aiagent.domain.gallery.GalleryImportUrlRequest;
import com.zzp.aiagent.model.entity.GalleryPicture;
import com.zzp.aiagent.domain.gallery.GalleryQueryRequest;
import com.zzp.aiagent.domain.gallery.GalleryUploadRequest;

import java.util.List;

public interface GalleryService {

    GalleryPicture upload(GalleryUploadRequest request);

    GalleryPicture importUrl(GalleryImportUrlRequest request);

    List<GalleryPicture> listAll(GalleryQueryRequest request);

    GalleryPicture getById(Long id);

    List<GalleryPicture> listByIds(List<Long> ids);

    GalleryPicture favorite(Long id, boolean favorited);

    void delete(Long id);

    /** Download original picture bytes from object storage. */
    byte[] downloadPicture(Long pictureId);

    /** Keyword-based search for fallback when vector search is unavailable. */
    List<GalleryPicture> searchByKeyword(String query, int limit);
}
