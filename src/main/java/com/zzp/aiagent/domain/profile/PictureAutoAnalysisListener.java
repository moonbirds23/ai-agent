package com.zzp.aiagent.domain.profile;

import com.zzp.aiagent.event.GalleryPictureSavedEvent;
import com.zzp.aiagent.service.PictureAiProfileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
@RequiredArgsConstructor
@Slf4j
public class PictureAutoAnalysisListener {
    private final PictureAiProfileService profileService;

    @Async
    @EventListener
    public void onPictureSaved(GalleryPictureSavedEvent event) {
        try {
            if (event.base64Data() != null && !event.base64Data().isBlank()) {
                profileService.analyzeDirectWithBase64(event.picture(), event.base64Data(), event.contentType());
            } else {
                profileService.analyzeDirect(event.picture(), event.imageBytes(), event.contentType());
            }
        } catch (Exception e) {
            log.warn("[AutoAnalysis] 自动画像分析失败 pictureId={}: {}", event.picture().id(), e.getMessage());
        }
    }
}
