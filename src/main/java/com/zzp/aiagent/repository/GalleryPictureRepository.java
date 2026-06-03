package com.zzp.aiagent.repository;

import com.zzp.aiagent.model.entity.GalleryPicture;

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

    /** Keyword-based fallback search when vector search is unavailable */
    List<GalleryPicture> searchByKeyword(String query, int limit);

    /** Paginated query with filters applied at the SQL level. */
    List<GalleryPicture> findAllPaged(int offset, int limit, String keyword, String category,
            List<String> tags, Boolean favoritedOnly, String sourceType);

    /** Count of records matching the same filters (for total in pagination). */
    int countFiltered(String keyword, String category, List<String> tags,
            Boolean favoritedOnly, String sourceType);
}
