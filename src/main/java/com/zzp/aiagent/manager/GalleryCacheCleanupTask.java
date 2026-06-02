package com.zzp.aiagent.manager;

import com.zzp.aiagent.domain.gallery.GalleryProperties;
import com.zzp.aiagent.model.entity.GalleryPicture;
import com.zzp.aiagent.model.enums.StorageLocation;
import com.zzp.aiagent.repository.GalleryPictureRepository;
import com.zzp.aiagent.service.GalleryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Component
@Profile("!test")
@Slf4j
public class GalleryCacheCleanupTask {

    private final GalleryService galleryService;
    private final GalleryPictureRepository repository;
    private final GalleryProperties properties;

    public GalleryCacheCleanupTask(GalleryService galleryService,
                                   GalleryPictureRepository repository,
                                   GalleryProperties properties) {
        this.galleryService = galleryService;
        this.repository = repository;
        this.properties = properties;
    }

    @Scheduled(cron = "${app.gallery.cache-cleanup-cron:0 0 3 * * ?}")
    public void cleanupExpiredCache() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(properties.cacheMaxAgeDays());
        List<GalleryPicture> expired = repository.findExpiredCache(cutoff);

        if (expired.isEmpty()) {
            log.debug("[CacheCleanup] 无过期缓存图片，cutoff={}", cutoff);
            return;
        }

        log.info("[CacheCleanup] 发现 {} 张过期缓存图片(cutoff={})，开始清理", expired.size(), cutoff);
        int deleted = 0;
        for (GalleryPicture pic : expired) {
            try {
                galleryService.delete(pic.id());
                deleted++;
                log.debug("[CacheCleanup] 已删除缓存图片 id={} name={} createTime={}",
                        pic.id(), pic.name(), pic.createTime());
            } catch (Exception e) {
                log.warn("[CacheCleanup] 删除缓存图片失败 id={}: {}", pic.id(), e.getMessage());
            }
        }
        log.info("[CacheCleanup] 清理完成，成功 {}/{}", deleted, expired.size());
    }
}
