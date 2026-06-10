package com.zzp.aiagent.agent.task;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
public class TaskPromptBuilder {

    public String build(TaskPlan plan) {
        if (plan == null || plan.taskType() == TaskType.CHAT) {
            return "";
        }
        if (plan.taskType() == TaskType.NEED_CLARIFICATION) {
            return "【本轮任务】信息不足，请向用户追问必要信息（图片、参考图或更具体的目标描述），不要调用工具。";
        }

        StringBuilder sb = new StringBuilder("【本轮任务计划】\n");
        sb.append("任务类型：").append(plan.taskType()).append("\n");
        sb.append("目标：").append(plan.userGoal()).append("\n");

        List<TaskStep> steps = plan.steps();
        if (steps != null && !steps.isEmpty()) {
            sb.append("步骤：\n");
            for (int i = 0; i < steps.size(); i++) {
                TaskStep step = steps.get(i);
                sb.append(i + 1).append(". ");
                if (step.toolName() != null) {
                    sb.append(step.toolName()).append("：");
                }
                sb.append(step.description());
                if (!step.required()) {
                    sb.append("（可选）");
                }
                if (step.dependsOn() != null && !step.dependsOn().isEmpty()) {
                    sb.append(" [依赖：").append(String.join(", ", step.dependsOn())).append("]");
                }
                sb.append("\n");
            }
        }

        sb.append("交付条件：\n");
        for (TaskStep step : steps) {
            if (step.required() && step.toolName() != null) {
                sb.append("- ").append(step.toolName()).append(" 必须返回真实结果\n");
            }
        }
        sb.append("- 未调用工具则不得声称完成\n");
        sb.append("- 工具结果优先于猜测\n");

        return sb.toString().trim();
    }
}
