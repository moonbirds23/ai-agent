package com.zzp.aiagent.gallery;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.zzp.aiagent.gallery.model.GalleryPicture;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

@Profile("!test & !postgres")
@Repository
@Slf4j
public class JsonFileGalleryPictureRepository implements GalleryPictureRepository {

    private static final Path DATA_FILE = Paths.get("gallery-data", "pictures.json");

    private final ObjectMapper objectMapper;
    private final ReentrantLock lock = new ReentrantLock();

    private long nextId;
    private List<GalleryPicture> pictures;

    public JsonFileGalleryPictureRepository(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void init() {
        lock.lock();
        try {
            if (Files.exists(DATA_FILE)) {
                PictureStore store = objectMapper.readValue(DATA_FILE.toFile(), PictureStore.class);
                this.nextId = store.getNextId();
                this.pictures = new ArrayList<>(store.getPictures() != null ? store.getPictures() : Collections.emptyList());
                log.info("[GalleryRepo] 已加载 {} 条图库记录, nextId={}", pictures.size(), nextId);
            } else {
                this.nextId = 1;
                this.pictures = new ArrayList<>();
                Files.createDirectories(DATA_FILE.getParent());
                persist();
                log.info("[GalleryRepo] 初始化空图库");
            }
        } catch (IOException e) {
            log.error("[GalleryRepo] 初始化失败", e);
            this.nextId = 1;
            this.pictures = new ArrayList<>();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public GalleryPicture save(GalleryPicture picture) {
        lock.lock();
        try {
            if (picture.id() == null) {
                GalleryPicture withId = withId(picture, nextId++);
                pictures.add(withId);
                persist();
                return withId;
            } else {
                int index = indexOf(picture.id());
                if (index >= 0) {
                    pictures.set(index, picture);
                } else {
                    pictures.add(picture);
                }
                persist();
                return picture;
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Optional<GalleryPicture> findById(Long id) {
        lock.lock();
        try {
            return pictures.stream().filter(p -> id.equals(p.id())).findFirst();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public List<GalleryPicture> findByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Collections.emptyList();
        }
        lock.lock();
        try {
            return pictures.stream().filter(p -> ids.contains(p.id())).toList();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public List<GalleryPicture> findAll() {
        lock.lock();
        try {
            return new ArrayList<>(pictures);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void deleteById(Long id) {
        lock.lock();
        try {
            pictures.removeIf(p -> id.equals(p.id()));
            persist();
        } finally {
            lock.unlock();
        }
    }

    private int indexOf(Long id) {
        for (int i = 0; i < pictures.size(); i++) {
            if (id.equals(pictures.get(i).id())) {
                return i;
            }
        }
        return -1;
    }

    private void persist() {
        try {
            PictureStore store = new PictureStore();
            store.setNextId(nextId);
            store.setPictures(pictures);
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(DATA_FILE.toFile(), store);
        } catch (IOException e) {
            log.error("[GalleryRepo] 持久化失败", e);
        }
    }

    private static GalleryPicture withId(GalleryPicture picture, long id) {
        return new GalleryPicture(
                id, picture.url(), picture.thumbnailUrl(), picture.name(),
                picture.introduction(), picture.category(), picture.tags(),
                picture.picSize(), picture.picWidth(), picture.picHeight(),
                picture.picScale(), picture.picFormat(), picture.userId(),
                picture.spaceId(), picture.reviewStatus(), picture.picColor(),
                picture.sourceType(), picture.favorited(),
                picture.createTime(), picture.updateTime()
        );
    }

    /*
    Internal JSON structure: { "nextId": N, "pictures": [...] }
    */
    private static class PictureStore {
        private long nextId;
        private List<GalleryPicture> pictures;

        public long getNextId() {
            return nextId;
        }

        public void setNextId(long nextId) {
            this.nextId = nextId;
        }

        public List<GalleryPicture> getPictures() {
            return pictures;
        }

        public void setPictures(List<GalleryPicture> pictures) {
            this.pictures = pictures;
        }
    }
}
