package com.zzp.aiagent.storage;

import com.zzp.aiagent.storage.model.StoredObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Component
@Profile("!cos")
@Slf4j
public class LocalObjectStorageService implements ObjectStorageService {

    private final Path root;

    public LocalObjectStorageService(StorageProperties props) {
        this.root = props.local() != null && props.local().root() != null
                ? Paths.get(props.local().root())
                : Paths.get("gallery-data", "images");
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            log.error("[LocalStorage] 无法创建存储目录: {}", root, e);
        }
    }

    @Override
    public StoredObject upload(byte[] bytes, String key, String contentType) {
        try {
            Path target = root.resolve(key);
            Files.createDirectories(target.getParent());
            Files.write(target, bytes);
            String url = getUrl(key);
            log.debug("[LocalStorage] 上传成功 key={} size={}", key, bytes.length);
            return new StoredObject(key, url, contentType, (long) bytes.length);
        } catch (IOException e) {
            log.error("[LocalStorage] 上传失败 key={}", key, e);
            throw new RuntimeException("本地文件写入失败: " + key, e);
        }
    }

    @Override
    public byte[] download(String key) {
        try {
            Path target = root.resolve(key);
            return Files.readAllBytes(target);
        } catch (IOException e) {
            log.warn("[LocalStorage] 下载失败 key={}", key, e);
            throw new RuntimeException("本地文件读取失败: " + key, e);
        }
    }

    @Override
    public void delete(String key) {
        try {
            Path target = root.resolve(key);
            Files.deleteIfExists(target);
            log.debug("[LocalStorage] 已删除 key={}", key);
        } catch (IOException e) {
            log.warn("[LocalStorage] 删除失败 key={}", key, e);
        }
    }

    @Override
    public String getUrl(String key) {
        // For local storage, extract pictureId from the key pattern "gallery/{userId}/{pictureId}/origin.{ext}"
        String pictureId = extractPictureId(key);
        if (pictureId != null) {
            return "/api/gallery/files/" + pictureId;
        }
        return "/api/gallery/files/" + key;
    }

    private String extractPictureId(String key) {
        if (key == null) return null;
        // Expected format: gallery/{userId}/{pictureId}/origin.{ext}
        String[] parts = key.split("/");
        if (parts.length >= 3) {
            return parts[2]; // pictureId is the third segment
        }
        return null;
    }
}
