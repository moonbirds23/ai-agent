package com.zzp.aiagent.integration.mcp;

/**
 * Canonical MCP tool names exposed by the image-retrieval MCP server.
 * Used by {@link McpToolInvoker} to address the correct tool.
 */
public final class McpToolNames {

    public static final String PEXELS_SEARCH = "pexelsSearchPhotos";
    public static final String PEXELS_CURATED = "pexelsCuratedPhotos";
    public static final String PEXELS_GET_PHOTO = "pexelsGetPhoto";
    public static final String GALLERY_SEARCH = "gallerySearch";

    private McpToolNames() {}
}
