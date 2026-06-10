package com.zzp.aiagent.agent.task;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Slf4j
@Component
public class TaskPlanValidator {

    private static final Set<String> ALLOWED_TOOLS = Set.of(
            "searchGallery", "getPictureInfo", "analyzeImage", "generateImage",
            "pexelsSearchPhotos", "pexelsCuratedPhotos", "pexelsSearchAndImport",
            "pexelsGetPhoto", "webSearch", "webFetch", "imageSearch",
            "searchAndDownload", "downloadImage", "importImage",
            "manageFavorite", "listStyleTemplates"
    );

    private static final int MAX_GENERATE_PER_PLAN = 1;
    private static final int MAX_DOWNLOAD_PER_PLAN = 5;
    private static final int MAX_SEARCH_PER_PLAN = 3;

    public PlanValidationResult validate(TaskPlan plan) {
        if (plan == null) {
            return PlanValidationResult.invalid(List.of("TaskPlan is null"));
        }

        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        for (TaskStep step : plan.steps()) {
            if (step.toolName() != null && !ALLOWED_TOOLS.contains(step.toolName())) {
                errors.add("Unknown tool: " + step.toolName());
            }
        }

        if (plan.requiresGeneration()) {
            boolean hasGen = plan.steps().stream()
                    .anyMatch(s -> "generateImage".equals(s.toolName()));
            if (!hasGen) {
                errors.add("Task requires image generation but plan lacks generateImage step");
            }
        }

        long genCount = plan.steps().stream()
                .filter(s -> "generateImage".equals(s.toolName())).count();
        if (genCount > MAX_GENERATE_PER_PLAN) {
            errors.add("Plan has " + genCount + " generateImage steps, max is " + MAX_GENERATE_PER_PLAN);
        }

        long dlCount = plan.steps().stream()
                .filter(s -> isDownloadTool(s.toolName())).count();
        if (dlCount > MAX_DOWNLOAD_PER_PLAN) {
            errors.add("Plan has " + dlCount + " download/import steps, max is " + MAX_DOWNLOAD_PER_PLAN);
        }

        long searchCount = plan.steps().stream()
                .filter(s -> isSearchTool(s.toolName())).count();
        if (searchCount > MAX_SEARCH_PER_PLAN) {
            warnings.add("Plan has " + searchCount + " search steps, consider consolidating");
        }

        for (TaskStep step : plan.steps()) {
            if (step.dependsOn() != null) {
                for (String dep : step.dependsOn()) {
                    boolean depExists = plan.steps().stream()
                            .anyMatch(s -> dep.equals(s.code()));
                    if (!depExists) {
                        errors.add("Step '" + step.code() + "' depends on unknown step '" + dep + "'");
                    }
                }
            }
        }

        for (TaskStep step : plan.steps()) {
            if (step.dependsOn() != null && step.dependsOn().contains(step.code())) {
                errors.add("Step '" + step.code() + "' depends on itself");
            }
        }

        if (!errors.isEmpty()) {
            log.warn("[PlanValidator] {} errors: {}", errors.size(), errors);
            return PlanValidationResult.invalid(errors, warnings);
        }
        return PlanValidationResult.passed();
    }

    private static boolean isSearchTool(String name) {
        return "imageSearch".equals(name) || "pexelsSearchPhotos".equals(name)
                || "webSearch".equals(name) || "searchGallery".equals(name);
    }

    private static boolean isDownloadTool(String name) {
        return "downloadImage".equals(name) || "searchAndDownload".equals(name)
                || "pexelsSearchAndImport".equals(name) || "importImage".equals(name);
    }
}
