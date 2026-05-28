package com.zzp.aiagent.gallery.model;

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
        LocalDateTime updateTime
) {}
