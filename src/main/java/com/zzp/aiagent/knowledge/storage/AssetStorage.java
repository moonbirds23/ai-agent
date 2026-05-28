package com.zzp.aiagent.knowledge.storage;

import java.io.InputStream;

public interface AssetStorage {

    StoredAsset store(InputStream input, String key, String contentType);

    InputStream load(String key);

    void delete(String key);
}
