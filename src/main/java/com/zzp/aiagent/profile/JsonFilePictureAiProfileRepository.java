package com.zzp.aiagent.profile;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.zzp.aiagent.profile.model.PictureAiProfile;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;

@Component
@Profile("!test & !postgres")
@Slf4j
public class JsonFilePictureAiProfileRepository implements PictureAiProfileRepository {

    private static final File DATA_DIR = new File("./gallery-data");
    private static final File PROFILES_FILE = new File(DATA_DIR, "ai-profiles.json");

    private final ObjectMapper mapper;
    private final Map<Long, PictureAiProfile> cache;
    private final ReentrantReadWriteLock lock;

    public JsonFilePictureAiProfileRepository(ObjectMapper mapper) {
        this.mapper = mapper;
        this.cache = new ConcurrentHashMap<>();
        this.lock = new ReentrantReadWriteLock();
        loadFromDisk();
    }

    @Override
    public PictureAiProfile save(PictureAiProfile profile) {
        lock.writeLock().lock();
        try {
            PictureAiProfile toSave = profile.withAnalyzedAt(java.time.LocalDateTime.now());
            cache.put(toSave.pictureId(), toSave);
            persist();
            log.info("[ProfileRepo] 保存画像 pictureId={}", toSave.pictureId());
            return toSave;
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public Optional<PictureAiProfile> findByPictureId(Long pictureId) {
        lock.readLock().lock();
        try {
            return Optional.ofNullable(cache.get(pictureId));
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public List<PictureAiProfile> findByPictureIds(List<Long> pictureIds) {
        lock.readLock().lock();
        try {
            return pictureIds.stream()
                    .map(cache::get)
                    .filter(java.util.Objects::nonNull)
                    .collect(Collectors.toList());
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public void deleteByPictureId(Long pictureId) {
        lock.writeLock().lock();
        try {
            cache.remove(pictureId);
            persist();
            log.info("[ProfileRepo] 删除画像 pictureId={}", pictureId);
        } finally {
            lock.writeLock().unlock();
        }
    }

    private void loadFromDisk() {
        if (!PROFILES_FILE.exists() || PROFILES_FILE.length() == 0) {
            log.info("[ProfileRepo] 画像文件不存在或为空，使用空缓存");
            return;
        }
        try {
            List<PictureAiProfile> profiles = mapper.readValue(PROFILES_FILE,
                    new TypeReference<List<PictureAiProfile>>() {});
            for (PictureAiProfile profile : profiles) {
                cache.put(profile.pictureId(), profile);
            }
            log.info("[ProfileRepo] 从磁盘加载 {} 条画像", profiles.size());
        } catch (IOException e) {
            log.error("[ProfileRepo] 加载画像文件失败", e);
        }
    }

    private void persist() {
        try {
            DATA_DIR.mkdirs();
            List<PictureAiProfile> profiles = new ArrayList<>(cache.values());
            mapper.writerWithDefaultPrettyPrinter().writeValue(PROFILES_FILE, profiles);
        } catch (IOException e) {
            log.error("[ProfileRepo] 持久化画像文件失败", e);
        }
    }
}
