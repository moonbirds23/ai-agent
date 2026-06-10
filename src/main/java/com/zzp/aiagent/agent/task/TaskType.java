package com.zzp.aiagent.agent.task;

/**
 * Classifies a user request into a known task category for verification.
 * <p>
 * Phase C+ determines the primary task type before execution via
 * {@link TaskPlanner}; post-hoc inference from tool calls remains as a
 * compatibility fallback.
 */
public enum TaskType {

    /** AI image generation via {@code generateImage}. */
    IMAGE_GENERATION,

    /** Visual analysis of an uploaded image via {@code analyzeImage}. */
    IMAGE_ANALYSIS,

    /** Search the local gallery via {@code searchGallery}. */
    GALLERY_SEARCH,

    /** Web image search via {@code imageSearch} / {@code pexelsSearchPhotos}. */
    WEB_IMAGE_SEARCH,

    /** Search + download into gallery. */
    REFERENCE_COLLECTION,

    /** Gallery operations: favorite / unfavorite. */
    GALLERY_MANAGEMENT,

    /** Style template browsing via {@code listStyleTemplates}. */
    STYLE_DISCOVERY,

    /** Web research via {@code webSearch} / {@code webFetch}. */
    WEB_RESEARCH,

    /** Multi-tool creative workflow (search → analyze → generate). */
    CREATIVE_WORKFLOW,

    /** Simple conversation — no tools were called, pass through model response. */
    CHAT,

    /** Not enough information — need to ask the user for clarification. */
    NEED_CLARIFICATION
}
