package com.zzp.aiagent.agent.task;

/**
 * Resource created or touched by a tool call.
 */
public record ResourceRef(
        String type,
        String id,
        String url,
        String name
) {
    public static ResourceRef image(String url) {
        return new ResourceRef("IMAGE", null, url, null);
    }

    public static ResourceRef galleryPicture(Object id, String name) {
        return new ResourceRef("GALLERY_PICTURE", id != null ? String.valueOf(id) : null, null, name);
    }
}
