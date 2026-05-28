package com.zzp.aiagent.storage;

import com.zzp.aiagent.storage.model.StoredObject;

public interface ObjectStorageService {

    StoredObject upload(byte[] bytes, String key, String contentType);

    byte[] download(String key);

    void delete(String key);

    String getUrl(String key);
}
