package com.zzp.aiagent.service;

import com.zzp.aiagent.model.entity.GalleryPicture;
import com.zzp.aiagent.model.entity.PictureAiProfile;

import java.util.List;

public interface PictureAiProfileService {
    PictureAiProfile analyze(Long pictureId);

    /**
     * 直接分析图片（不依赖本地文件系统），适用于上传后自动触发。
     */
    PictureAiProfile analyzeDirect(GalleryPicture picture, byte[] imageBytes, String contentType);

    /**
     * 直接分析图片（使用原始 Base64 字符串），避免解码后重新编码的开销。
     */
    PictureAiProfile analyzeDirectWithBase64(GalleryPicture picture, String base64Data, String contentType);

    PictureAiProfile getByPictureId(Long pictureId);
    List<PictureAiProfile> listByPictureIds(List<Long> pictureIds);
    void index(Long pictureId);
    void removeIndex(Long pictureId);
}
