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
}
