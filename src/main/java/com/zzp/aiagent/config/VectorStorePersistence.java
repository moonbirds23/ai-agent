package com.zzp.aiagent.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import java.io.File;
import java.nio.file.Path;

@Component
@Profile("!test & !postgres")
@Slf4j
public class VectorStorePersistence {

    private final SimpleVectorStore store;
    private final File saveFile;

    public VectorStorePersistence(VectorStore vectorStore) {
        this.saveFile = Path.of("./data", "vector-store.json").toFile();
        if (vectorStore instanceof SimpleVectorStore svs) {
            this.store = svs;
            if (saveFile.exists() && saveFile.length() > 0) {
                store.load(saveFile);
                log.info("[VectorStore] 从磁盘恢复向量库: {}", saveFile.getAbsolutePath());
            }
        } else {
            this.store = null;
            log.warn("[VectorStore] 不是 SimpleVectorStore，跳过持久化");
        }
    }

    @PreDestroy
    public void persist() {
        if (store != null) {
            saveFile.getParentFile().mkdirs();
            store.save(saveFile);
            log.info("[VectorStore] 已保存到磁盘: {}", saveFile.getAbsolutePath());
        }
    }
}
