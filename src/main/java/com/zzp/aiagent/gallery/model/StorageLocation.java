package com.zzp.aiagent.gallery.model;

/**
 * 图库图片存储位置常量。
 */
public final class StorageLocation {

    /** 主图库：用户手动上传/导入，永久保存 */
    public static final String MAIN = "MAIN";

    /** 缓存图库：对话窗口自动缓存，定时清理 */
    public static final String CACHE = "CACHE";

    private StorageLocation() {}
}
