package com.zzp.aiagent.manager;

import com.zzp.aiagent.domain.storage.StoredObject;

public interface ObjectStorageService {

    StoredObject upload(byte[] bytes, String key, String contentType);

    byte[] download(String key);

    void delete(String key);

    String getUrl(String key);
}
