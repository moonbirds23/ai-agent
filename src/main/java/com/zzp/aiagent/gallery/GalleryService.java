package com.zzp.aiagent.gallery;

import com.zzp.aiagent.gallery.model.*;

import java.util.List;

public interface GalleryService {

    GalleryPicture upload(GalleryUploadRequest request);

    GalleryPicture importUrl(GalleryImportUrlRequest request);

    List<GalleryPicture> listAll(GalleryQueryRequest request);

    GalleryPicture getById(Long id);

    List<GalleryPicture> listByIds(List<Long> ids);

    GalleryPicture favorite(Long id, boolean favorited);

    void delete(Long id);
}
