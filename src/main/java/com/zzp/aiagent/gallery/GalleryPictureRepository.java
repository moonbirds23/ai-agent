package com.zzp.aiagent.gallery;

import com.zzp.aiagent.gallery.model.GalleryPicture;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface GalleryPictureRepository {

    GalleryPicture save(GalleryPicture picture);

    Optional<GalleryPicture> findById(Long id);

    List<GalleryPicture> findByIds(List<Long> ids);

    List<GalleryPicture> findAll();

    void deleteById(Long id);

    /** 查询过期缓存图片：storage_location='CACHE' 且 create_time < cutoffTime */
    List<GalleryPicture> findExpiredCache(LocalDateTime cutoffTime);

    /** 按图片哈希查重（MD5/SHA256） */
    List<GalleryPicture> findByHash(String picHash);
}
