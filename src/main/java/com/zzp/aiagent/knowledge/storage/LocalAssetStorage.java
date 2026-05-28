package com.zzp.aiagent.knowledge.storage;

import com.zzp.aiagent.exception.BusinessException;
import com.zzp.aiagent.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

@Component
@Slf4j
public class LocalAssetStorage implements AssetStorage {

    private static final String STORAGE_ROOT = "./kb-data";
    private static final String DEFAULT_USER = "default";

    private final Path baseDir;

    public LocalAssetStorage() {
        this.baseDir = Path.of(STORAGE_ROOT, DEFAULT_USER);
        try {
            Files.createDirectories(baseDir);
            log.info("[LocalAssetStorage] 存储目录: {}", baseDir.toAbsolutePath());
        } catch (IOException e) {
            throw new RuntimeException("无法创建知识库存储目录: " + baseDir.toAbsolutePath(), e);
        }
    }

    @Override
    public StoredAsset store(InputStream input, String key, String contentType) {
        Path target = baseDir.resolve(key);
        try {
            Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
            log.info("[LocalAssetStorage] 保存文件 key={} size={}", key, Files.size(target));
            String url = "/api/knowledge/files/" + key;
            return new StoredAsset(key, url);
        } catch (IOException e) {
            log.error("[LocalAssetStorage] 保存失败 key={}", key, e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "文件保存失败");
        }
    }

    @Override
    public InputStream load(String key) {
        Path file = baseDir.resolve(key);
        if (!Files.exists(file)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "文件不存在: " + key);
        }
        try {
            return Files.newInputStream(file);
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "文件读取失败");
        }
    }

    @Override
    public void delete(String key) {
        try {
            Files.deleteIfExists(baseDir.resolve(key));
            log.info("[LocalAssetStorage] 删除文件 key={}", key);
        } catch (IOException e) {
            log.warn("[LocalAssetStorage] 删除失败 key={}", key, e);
        }
    }

}
