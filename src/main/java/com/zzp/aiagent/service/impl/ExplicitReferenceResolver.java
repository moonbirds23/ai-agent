package com.zzp.aiagent.service.impl;

import com.zzp.aiagent.service.GalleryService;
import com.zzp.aiagent.service.PictureAiProfileService;
import com.zzp.aiagent.model.entity.GalleryPicture;
import com.zzp.aiagent.model.entity.PictureAiProfile;
import com.zzp.aiagent.domain.rag.RagContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 明确参考图解析器：根据用户指定的图片 ID 列表，从图库和画像服务中加载完整的参考图数据。
 */
@Service
@Profile("!test")
@Slf4j
@RequiredArgsConstructor
public class ExplicitReferenceResolver {

    private final GalleryService galleryService;
    private final PictureAiProfileService profileService;

    /**
     * 根据图片 ID 列表解析参考图。
     * 图片不存在时静默跳过；画像不存在时创建 stub（仅含图库元数据）。
     */
    public List<RagContext.ReferencePicture> resolve(List<Long> pictureIds) {
        if (pictureIds == null || pictureIds.isEmpty()) {
            return List.of();
        }
        try {
            List<GalleryPicture> pictures = galleryService.listByIds(pictureIds);
            Map<Long, GalleryPicture> pictureMap = pictures.stream()
                    .collect(Collectors.toMap(GalleryPicture::id, p -> p));

            List<RagContext.ReferencePicture> result = new ArrayList<>();
            for (Long pictureId : pictureIds) {
                GalleryPicture picture = pictureMap.get(pictureId);
                if (picture == null) {
                    log.warn("[ExplicitRef] 图库中未找到图片 pictureId={}", pictureId);
                    continue;
                }
                PictureAiProfile profile = null;
                try {
                    profile = profileService.getByPictureId(pictureId);
                } catch (Exception e) {
                    log.debug("[ExplicitRef] 图片画像不存在 pictureId={}，使用 stub", pictureId);
                }
                if (profile == null) {
                    profile = createStubProfile(picture);
                }
                result.add(new RagContext.ReferencePicture(picture, profile));
            }
            log.info("[ExplicitRef] 解析 {} 张明确参考图", result.size());
            return result;
        } catch (Exception e) {
            log.warn("[ExplicitRef] 解析参考图失败", e);
            return List.of();
        }
    }

    /**
     * 为尚无画像的图片创建一个轻量 stub，仅包含图库元数据。
     */
    private PictureAiProfile createStubProfile(GalleryPicture picture) {
        return PictureAiProfile.pending(
                picture.id(),
                picture.name(),
                picture.category(),
                null,   // style
                null,   // colors
                null,   // composition
                null,   // lighting
                null,   // mood
                null,   // imagePrompt
                null    // indexText
        );
    }
}
