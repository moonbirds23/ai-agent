package com.zzp.aiagent.agent.task;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Decides how the backend should explain or recover from a failed task.
 */
@Component
public class RecoveryPolicy {

    public RecoveryAction decide(TaskPlan plan, VerificationResult result, List<ToolExecutionRecord> records) {
        if (result == null || result.deliverable()) {
            return RecoveryAction.none();
        }
        if (plan == null) {
            return RecoveryAction.askUser("任务未完成，请补充更明确的需求后重试");
        }
        if (plan.taskType() == TaskType.NEED_CLARIFICATION) {
            return RecoveryAction.askUser("需要补充图片、参考图或更具体的目标描述");
        }

        Map<String, Boolean> stepResults = TaskVerifier.verifySteps(plan, records);
        long passedCount = stepResults.values().stream().filter(Boolean::booleanValue).count();
        long totalRequired = plan.steps().stream().filter(TaskStep::required).count();

        if (passedCount > 0 && passedCount < totalRequired) {
            List<String> failedSteps = stepResults.entrySet().stream()
                    .filter(e -> !e.getValue())
                    .map(Map.Entry::getKey)
                    .toList();
            return RecoveryAction.partial(
                    "部分步骤已完成，以下步骤未成功：" + String.join("、", failedSteps)
                    + "。可以尝试简化需求或单独重试失败步骤。");
        }

        if (hasFailed(records, "pexelsSearchPhotos")) {
            return RecoveryAction.fallback("网络图片搜索失败，可降级使用图库搜索或更换关键词", "searchGallery");
        }
        if (hasFailed(records, "searchGallery")) {
            return RecoveryAction.fallback("图库搜索失败，可降级使用网络图片搜索", "pexelsSearchPhotos");
        }
        if (hasFailed(records, "generateImage")) {
            return RecoveryAction.retry("图片生成失败，可稍后重试或简化生成描述", "generateImage");
        }
        if (plan.requiresImage()) {
            return RecoveryAction.askUser("当前任务需要图片输入，请上传图片或选择图库参考图");
        }

        if (passedCount == 0 && totalRequired > 0) {
            return RecoveryAction.askUser("所有步骤均未成功执行，请检查需求是否明确，或换个方式描述任务");
        }

        return RecoveryAction.askUser(result.userMessage());
    }

    private static boolean hasFailed(List<ToolExecutionRecord> records, String toolName) {
        return records != null && records.stream()
                .anyMatch(r -> toolName.equals(r.toolName()) && !r.success());
    }
}
