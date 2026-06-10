package com.zzp.aiagent.agent.task;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Decides how the backend should explain or recover from a failed task.
 * <p>
 * The first implementation is conservative: it does not recursively run tools
 * after the model has finished, but it returns structured recovery guidance for
 * the final response and frontend.
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
        return RecoveryAction.askUser(result.userMessage());
    }

    private static boolean hasFailed(List<ToolExecutionRecord> records, String toolName) {
        return records != null && records.stream()
                .anyMatch(r -> toolName.equals(r.toolName()) && !r.success());
    }

}
