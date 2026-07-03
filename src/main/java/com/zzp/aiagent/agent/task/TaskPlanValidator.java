package com.zzp.aiagent.agent.task;

import com.zzp.aiagent.model.dto.chat.ChatRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Slf4j
@Component
public class TaskPlanValidator {

    private static final int MAX_GENERATE_PER_PLAN = 1;
    private static final int MAX_DOWNLOAD_PER_PLAN = 5;
    private static final int MAX_SEARCH_PER_PLAN = 3;

    private final ToolCapabilityRegistry capabilities;

    public TaskPlanValidator(ToolCapabilityRegistry capabilities) {
        this.capabilities = capabilities;
    }

    public PlanValidationResult validate(TaskPlan plan) {
        return validate(plan, null);
    }

    public PlanValidationResult validate(TaskPlan plan, ChatRequest request) {
        if (plan == null) {
            return PlanValidationResult.invalid(List.of("TaskPlan is null"));
        }

        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        for (TaskStep step : plan.steps()) {
            if (step.toolName() != null && !capabilities.isKnown(step.toolName())) {
                errors.add("Unknown tool: " + step.toolName());
            }
        }

        if (request != null) {
            validateAgainstIntent(plan, request, errors);
        }

        if (plan.requiresGeneration() && !hasTool(plan, "generateImage")) {
            errors.add("Task requires image generation but plan lacks generateImage step");
        }

        long generationCount = countTools(plan, "generateImage");
        if (generationCount > MAX_GENERATE_PER_PLAN) {
            errors.add("Plan has " + generationCount + " generateImage steps, max is "
                    + MAX_GENERATE_PER_PLAN);
        }

        long downloadCount = plan.steps().stream()
                .filter(step -> isDownloadTool(step.toolName()))
                .count();
        if (downloadCount > MAX_DOWNLOAD_PER_PLAN) {
            errors.add("Plan has " + downloadCount + " download/import steps, max is "
                    + MAX_DOWNLOAD_PER_PLAN);
        }

        long searchCount = plan.steps().stream()
                .filter(step -> isSearchTool(step.toolName()))
                .count();
        if (searchCount > MAX_SEARCH_PER_PLAN) {
            warnings.add("Plan has " + searchCount + " search steps, consider consolidating");
        }

        for (TaskStep step : plan.steps()) {
            for (String dependency : safeDependencies(step)) {
                boolean exists = plan.steps().stream()
                        .anyMatch(candidate -> dependency.equals(candidate.code()));
                if (!exists) {
                    errors.add("Step '" + step.code() + "' depends on unknown step '"
                            + dependency + "'");
                }
                if (dependency.equals(step.code())) {
                    errors.add("Step '" + step.code() + "' depends on itself");
                }
            }
        }

        if (!errors.isEmpty()) {
            log.warn("[PlanValidator] {} errors: {}", errors.size(), errors);
            return PlanValidationResult.invalid(errors, warnings);
        }
        return PlanValidationResult.passed();
    }

    private static void validateAgainstIntent(TaskPlan plan, ChatRequest request,
                                              List<String> errors) {
        String text = request.message() != null
                ? request.message().toLowerCase(Locale.ROOT) : "";
        boolean asksGeneration = containsAny(text,
                "生成", "画一张", "帮我画", "请画", "绘制", "做一张", "出一张",
                "制作一张", "生图", "generate", "draw", "create an image");
        boolean asksGallerySearch = containsAny(text,
                "图库", "搜索图库", "图库里", "从图库", "图库参考图");
        boolean asksAnySearch = asksGallerySearch || containsAny(text,
                "搜索图片", "找图片", "找几张", "搜索参考图", "网络图片", "pexels",
                "search images", "find images", "reference images");

        boolean hasGeneration = hasTool(plan, "generateImage");
        boolean hasGallerySearch = hasTool(plan, "searchGallery");
        boolean hasAnySearch = plan.steps().stream()
                .anyMatch(step -> isSearchTool(step.toolName()));
        boolean explicitlyCombinesSearchAndGeneration = asksGeneration && asksAnySearch;

        if (asksGeneration && !hasGeneration) {
            errors.add("User requests image generation but plan lacks generateImage step");
        }
        if (asksGeneration && !asksAnySearch && hasAnySearch) {
            errors.add("Pure image generation request must not add an unrequested search step");
        }
        if (explicitlyCombinesSearchAndGeneration
                && plan.taskType() != TaskType.CREATIVE_WORKFLOW) {
            errors.add("Explicit search-then-generate request must use CREATIVE_WORKFLOW");
        }
        if (explicitlyCombinesSearchAndGeneration) {
            plan.steps().stream()
                    .filter(step -> isSearchTool(step.toolName())
                            || "generateImage".equals(step.toolName()))
                    .filter(step -> !step.required())
                    .forEach(step -> errors.add(
                            "Explicit workflow step must be required: " + step.code()));
        }
        if (asksGallerySearch && asksGeneration && !hasGallerySearch) {
            errors.add("User requests gallery search before generation but plan lacks searchGallery step");
        }
        if (asksGallerySearch && asksGeneration && hasGallerySearch && hasGeneration) {
            String searchCode = plan.steps().stream()
                    .filter(step -> "searchGallery".equals(step.toolName()))
                    .map(TaskStep::code)
                    .findFirst()
                    .orElse(null);
            TaskStep generation = plan.steps().stream()
                    .filter(step -> "generateImage".equals(step.toolName()))
                    .findFirst()
                    .orElse(null);
            if (generation != null && (searchCode == null
                    || !safeDependencies(generation).contains(searchCode))) {
                errors.add("generateImage must depend on the gallery search step for this user request");
            }
        }
    }

    private static long countTools(TaskPlan plan, String toolName) {
        return plan.steps().stream().filter(step -> toolName.equals(step.toolName())).count();
    }

    private static boolean hasTool(TaskPlan plan, String toolName) {
        return countTools(plan, toolName) > 0;
    }

    private static List<String> safeDependencies(TaskStep step) {
        return step.dependsOn() != null ? step.dependsOn() : List.of();
    }

    private static boolean containsAny(String text, String... values) {
        for (String value : values) {
            if (text.contains(value.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    static boolean isSearchTool(String name) {
        return "imageSearch".equals(name)
                || "pexelsSearchPhotos".equals(name)
                || "pexelsCuratedPhotos".equals(name)
                || "webSearch".equals(name)
                || "searchGallery".equals(name);
    }

    private static boolean isDownloadTool(String name) {
        return "downloadImage".equals(name)
                || "searchAndDownload".equals(name)
                || "pexelsSearchAndImport".equals(name)
                || "importImage".equals(name);
    }
}
