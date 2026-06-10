package com.zzp.aiagent.agent.task;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Slf4j
@Component
public class TaskPlanRepair {

    private final TaskPlanValidator validator;

    public TaskPlanRepair(TaskPlanValidator validator) {
        this.validator = validator;
    }

    public TaskPlan repair(TaskPlan plan, String userText) {
        if (plan == null) return null;

        PlanValidationResult result = validator.validate(plan);
        if (result.valid()) return plan;

        log.info("[PlanRepair] Attempting repair for plan type={} errors={}", plan.taskType(), result.errors());

        List<TaskStep> steps = new ArrayList<>(plan.steps());
        String lower = userText != null ? userText.toLowerCase(Locale.ROOT) : "";

        if (containsAny(lower, "图库", "搜索图库", "找一下图库", "参考图", "收藏")
                && steps.stream().noneMatch(s -> "searchGallery".equals(s.toolName()))) {
            steps.add(0, TaskStep.of("search_gallery", "搜索本地图库", true, "searchGallery"));
            log.info("[PlanRepair] Added missing searchGallery step");
        }

        if (containsAny(lower, "生成", "画", "绘制", "做一张", "出一张", "海报", "壁纸", "生图")
                && steps.stream().noneMatch(s -> "generateImage".equals(s.toolName()))) {
            TaskStep genStep = TaskStep.of("generate_image", "生成图片", true, "generateImage");
            if (steps.stream().anyMatch(s -> "searchGallery".equals(s.toolName()))) {
                genStep = new TaskStep("generate_image", "生成图片", true, "generateImage",
                        List.of("search_gallery"), java.util.Map.of(), StepStatus.PENDING);
            }
            steps.add(steps.size(), genStep);
            log.info("[PlanRepair] Added missing generateImage step");
        }

        boolean hasSearch = steps.stream().anyMatch(s -> "searchGallery".equals(s.toolName()));
        boolean hasGen = steps.stream().anyMatch(s -> "generateImage".equals(s.toolName()));
        if (hasSearch && hasGen) {
            steps = steps.stream().map(s -> {
                if ("generateImage".equals(s.toolName())
                        && (s.dependsOn() == null || s.dependsOn().isEmpty())) {
                    return new TaskStep(s.code(), s.description(), s.required(), s.toolName(),
                            List.of("search_gallery"), s.input(), StepStatus.PENDING);
                }
                return s;
            }).toList();
        }

        TaskType taskType = plan.taskType();
        if (hasSearch && hasGen && taskType == TaskType.IMAGE_GENERATION) {
            taskType = TaskType.CREATIVE_WORKFLOW;
        }

        TaskPlan repaired = new TaskPlan(plan.turnId(), taskType, plan.userGoal(),
                List.copyOf(steps), plan.requiresImage(), hasGen,
                plan.requiresExternalSearch(), plan.slots());

        PlanValidationResult recheck = validator.validate(repaired);
        if (recheck.valid()) {
            log.info("[PlanRepair] Repair successful");
            return repaired;
        }

        log.warn("[PlanRepair] Repair failed, remaining errors: {}", recheck.errors());
        return null;
    }

    private static boolean containsAny(String text, String... needles) {
        if (text == null || text.isBlank()) return false;
        for (String needle : needles) {
            if (text.contains(needle.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }
}
