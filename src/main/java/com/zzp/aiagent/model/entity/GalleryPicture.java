package com.zzp.aiagent.model.entity;

import com.zzp.aiagent.model.enums.StorageLocation;

import java.time.LocalDateTime;
import java.util.List;

public record GalleryPicture(
        Long id,
        String url,
        String thumbnailUrl,
        String name,
        String introduction,
        String category,
        List<String> tags,
        Long picSize,
        Integer picWidth,
        Integer picHeight,
        Double picScale,
        String picFormat,
        Long userId,
        Long spaceId,
        Integer reviewStatus,
        String picColor,
        String sourceType,
        Boolean favorited,
        LocalDateTime createTime,
        LocalDateTime updateTime,
        String storageLocation,
        String picHash
) {
    public GalleryPicture {
        if (storageLocation == null) storageLocation = StorageLocation.MAIN;
    }

    /** Build the object storage key for this picture's original file. */
    public String storageKey() {
        String ext = picFormat != null && !picFormat.isBlank() ? picFormat : "png";
        return "gallery/" + userId + "/" + id + "/origin." + ext;
    }

    /** Create a new picture record for upload (id=null, url=null, defaults applied). */
    public static GalleryPicture forUpload(String name, String introduction, String category,
            List<String> tags, long picSize, int width, int height, double scale,
            String picFormat, String sourceType, String storageLocation, String picHash) {
        return new GalleryPicture(null, null, null, name, introduction, category,
                tags != null ? List.copyOf(tags) : List.of(),
                picSize, width, height, scale, picFormat, 1L, 0L, 1, null,
                sourceType, false, LocalDateTime.now(), LocalDateTime.now(),
                storageLocation, picHash);
    }

    /** Return a copy of this picture with a new url. */
    public GalleryPicture withUrl(String url) {
        return new GalleryPicture(id, url, thumbnailUrl, name, introduction, category,
                tags, picSize, picWidth, picHeight, picScale, picFormat,
                userId, spaceId, reviewStatus, picColor, sourceType, favorited,
                createTime, updateTime, storageLocation, picHash);
    }

    /** Return a copy of this picture with the favorited flag and updateTime set to now. */
    public GalleryPicture withFavorite(boolean favorited) {
        return new GalleryPicture(id, url, thumbnailUrl, name, introduction, category,
                tags, picSize, picWidth, picHeight, picScale, picFormat,
                userId, spaceId, reviewStatus, picColor, sourceType, favorited,
                createTime, LocalDateTime.now(), storageLocation, picHash);
    }
}
