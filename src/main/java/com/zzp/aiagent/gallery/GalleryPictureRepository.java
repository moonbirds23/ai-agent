package com.zzp.aiagent.gallery;

import com.zzp.aiagent.gallery.model.GalleryPicture;

import java.util.List;
import java.util.Optional;

public interface GalleryPictureRepository {

    GalleryPicture save(GalleryPicture picture);

    Optional<GalleryPicture> findById(Long id);

    List<GalleryPicture> findByIds(List<Long> ids);

    List<GalleryPicture> findAll();

    void deleteById(Long id);
}
