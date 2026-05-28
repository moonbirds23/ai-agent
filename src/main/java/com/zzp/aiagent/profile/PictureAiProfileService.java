package com.zzp.aiagent.profile;

import com.zzp.aiagent.gallery.model.GalleryPicture;
import com.zzp.aiagent.profile.model.PictureAiProfile;

import java.util.List;

public interface PictureAiProfileService {
    PictureAiProfile analyze(Long pictureId);

    /**
     * 直接分析图片（不依赖本地文件系统），适用于上传后自动触发。
     */
    PictureAiProfile analyzeDirect(GalleryPicture picture, byte[] imageBytes, String contentType);

    PictureAiProfile getByPictureId(Long pictureId);
    List<PictureAiProfile> listByPictureIds(List<Long> pictureIds);
    void index(Long pictureId);
    void removeIndex(Long pictureId);
}
