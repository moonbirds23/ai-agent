package com.zzp.aiagent.storage;

import com.zzp.aiagent.storage.model.StoredObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("cos")
@Slf4j
public class CosObjectStorageService implements ObjectStorageService {

    private final StorageProperties props;

    public CosObjectStorageService(StorageProperties props) {
        this.props = props;
        log.info("[CosStorage] COS 配置已加载 region={} bucket={}",
                props.cos() != null ? props.cos().region() : "N/A",
                props.cos() != null ? props.cos().bucket() : "N/A");
    }

    @Override
    public StoredObject upload(byte[] bytes, String key, String contentType) {
        throw new UnsupportedOperationException("COS 上传尚未实现，请使用 local 存储");
    }

    @Override
    public byte[] download(String key) {
        throw new UnsupportedOperationException("COS 下载尚未实现，请使用 local 存储");
    }

    @Override
    public void delete(String key) {
        throw new UnsupportedOperationException("COS 删除尚未实现，请使用 local 存储");
    }

    @Override
    public String getUrl(String key) {
        if (props.cos() != null && props.cos().baseUrl() != null) {
            return props.cos().baseUrl() + "/" + key;
        }
        throw new UnsupportedOperationException("COS baseUrl 未配置");
    }
}
