package com.zzp.aiagent.model.dto.memory;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 图片记忆引用：图库图片存 pictureId，外部图片存文字描述。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ImageRef(
        String type,        // "GALLERY" | "TEXT_DESCRIPTION"
        Long pictureId,     // type=GALLERY 时有效
        String description  // type=TEXT_DESCRIPTION 时有效
) {
    public static final String TYPE_GALLERY = "GALLERY";
    public static final String TYPE_TEXT_DESCRIPTION = "TEXT_DESCRIPTION";

    public static ImageRef gallery(Long pictureId) {
        return new ImageRef(TYPE_GALLERY, pictureId, null);
    }

    public static ImageRef textDescription(String description) {
        return new ImageRef(TYPE_TEXT_DESCRIPTION, null, description);
    }
}
