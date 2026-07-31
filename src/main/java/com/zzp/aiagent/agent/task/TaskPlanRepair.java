package com.zzp.aiagent.agent.task;

import com.zzp.aiagent.model.dto.chat.ChatRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Slf4j
@Component
public class TaskPlanRepair {

    private final TaskPlanValidator validator;

    public TaskPlanRepair(TaskPlanValidator validator) {
        this.validator = validator;
    }

    public TaskPlan repair(TaskPlan plan, ChatRequest request) {
        if (plan == null) {
            return null;
        }

        PlanValidationResult validation = validator.validate(plan, request);
        if (validation.valid()) {
            return plan;
        }

        log.info("[PlanRepair] Attempting repair for plan type={} errors={}",
                plan.taskType(), validation.errors());

        String text = request != null && request.message() != null
                ? request.message().toLowerCase(Locale.ROOT) : "";
        boolean asksGeneration = containsAny(text,
                "生成", "画一张", "请画", "绘制", "做一张", "出一张", "生图",
                "generate", "draw", "create an image");
        boolean asksGallerySearch = containsAny(text,
                "图库", "搜索图库", "图库里", "从图库");
        boolean asksSearch = asksGallerySearch || containsAny(text,
                "搜索图片", "找图片", "找几张", "搜索参考图", "网络图片", "pexels",
                "search images", "find images", "reference images");

        List<TaskStep> steps = new ArrayList<>(plan.steps());

        if (plan.taskType() == TaskType.WEB_IMAGE_SEARCH) {
            java.util.Set<String> seenSearchTools = new java.util.LinkedHashSet<>();
            steps.removeIf(step -> TaskPlanValidator.isSearchTool(step.toolName())
                    && !seenSearchTools.add(step.toolName()));
        }

        if (asksGeneration && !asksSearch) {
            steps.removeIf(step -> TaskPlanValidator.isSearchTool(step.toolName()));
        }

        if (asksGallerySearch && steps.stream()
                .noneMatch(step -> "searchGallery".equals(step.toolName()))) {
            steps.add(0, new TaskStep(
                    "search_gallery", "搜索本地图库", true, "searchGallery",
                    List.of(), Map.of("query", request.message(), "limit", 5),
                    StepStatus.PENDING));
        }

        if (asksGeneration && steps.stream()
                .noneMatch(step -> "generateImage".equals(step.toolName()))) {
            steps.add(new TaskStep(
                    "generate_image", "生成图片", true, "generateImage",
                    List.of(), Map.of("promptIntent", request.message()),
                    StepStatus.PENDING));
        }

        String searchStepCode = asksSearch
                ? steps.stream()
                .filter(step -> TaskPlanValidator.isSearchTool(step.toolName()))
                .map(TaskStep::code)
                .findFirst()
                .orElse(null)
                : null;

        boolean explicitWorkflow = asksGeneration && asksSearch;
        List<TaskStep> repairedSteps = steps.stream()
                .map(step -> repairStep(step, asksGeneration, explicitWorkflow, searchStepCode))
                .toList();

        boolean hasGeneration = repairedSteps.stream()
                .anyMatch(step -> "generateImage".equals(step.toolName()));
        boolean hasSearch = repairedSteps.stream()
                .anyMatch(step -> TaskPlanValidator.isSearchTool(step.toolName()));
        TaskType taskType = hasGeneration && hasSearch
                ? TaskType.CREATIVE_WORKFLOW
                : hasGeneration ? TaskType.IMAGE_GENERATION : plan.taskType();

        TaskPlan repaired = new TaskPlan(
                plan.turnId(), taskType, plan.userGoal(), List.copyOf(repairedSteps),
                plan.requiresImage(), hasGeneration,
                repairedSteps.stream().anyMatch(step ->
                        "pexelsSearchPhotos".equals(step.toolName())
                                || "webSearch".equals(step.toolName())
                                || "imageSearch".equals(step.toolName())),
                plan.slots());

        PlanValidationResult recheck = validator.validate(repaired, request);
        if (recheck.valid()) {
            log.info("[PlanRepair] Repair successful");
            return repaired;
        }

        log.warn("[PlanRepair] Repair failed, remaining errors: {}", recheck.errors());
        return null;
    }

    private static TaskStep repairStep(TaskStep step, boolean asksGeneration,
                                       boolean explicitWorkflow, String searchStepCode) {
        if (explicitWorkflow && TaskPlanValidator.isSearchTool(step.toolName())) {
            return new TaskStep(
                    step.code(), step.description(), true, step.toolName(),
                    step.dependsOn() != null ? step.dependsOn() : List.of(),
                    step.input() != null ? step.input() : Map.of(),
                    StepStatus.PENDING);
        }
        if (!"generateImage".equals(step.toolName())) {
            return step;
        }
        List<String> dependencies = searchStepCode != null
                ? List.of(searchStepCode) : List.of();
        return new TaskStep(
                step.code(), step.description(), asksGeneration || step.required(),
                step.toolName(), dependencies,
                step.input() != null ? step.input() : Map.of(),
                StepStatus.PENDING);
    }

    private static boolean containsAny(String text, String... needles) {
        if (text == null || text.isBlank()) {
            return false;
        }
        for (String needle : needles) {
            if (text.contains(needle.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
