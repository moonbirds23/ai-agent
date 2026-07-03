package com.zzp.aiagent.agent.task;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/**
 * Single source of truth for planner and executor tool capabilities.
 */
@Component
public class ToolCapabilityRegistry {

    private static final Map<String, Capability> CAPABILITIES = Map.ofEntries(
            entry("searchGallery", true, true),
            entry("getPictureInfo", true, true),
            entry("analyzeImage", true, true),
            entry("generateImage", true, true),
            entry("listStyleTemplates", true, true),
            entry("pexelsSearchPhotos", true, true),
            entry("pexelsCuratedPhotos", true, true),
            entry("pexelsSearchAndImport", true, false),
            entry("pexelsGetPhoto", true, false),
            entry("webSearch", true, false),
            entry("webFetch", true, false),
            entry("imageSearch", true, false),
            entry("searchAndDownload", true, false),
            entry("downloadImage", true, false),
            entry("importImage", true, false),
            entry("manageFavorite", true, false)
    );

    public boolean isKnown(String toolName) {
        return toolName != null && CAPABILITIES.containsKey(toolName);
    }

    public boolean supportsAuto(String toolName) {
        Capability capability = CAPABILITIES.get(toolName);
        return capability != null && capability.autoExecution();
    }

    public boolean supportsManual(String toolName) {
        Capability capability = CAPABILITIES.get(toolName);
        return capability != null && capability.manualExecution();
    }

    public Set<String> toolNames() {
        return CAPABILITIES.keySet();
    }

    private static Map.Entry<String, Capability> entry(String name, boolean auto, boolean manual) {
        return Map.entry(name, new Capability(auto, manual));
    }

    private record Capability(boolean autoExecution, boolean manualExecution) {
    }
}
