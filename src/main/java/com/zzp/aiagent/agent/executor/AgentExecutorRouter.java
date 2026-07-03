package com.zzp.aiagent.agent.executor;

import com.zzp.aiagent.agent.task.TaskPlan;
import com.zzp.aiagent.agent.task.TaskType;
import com.zzp.aiagent.agent.task.ToolCapabilityRegistry;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
public class AgentExecutorRouter {

    private final SpringAiAutoToolExecutor autoExecutor;
    private final ManualReactExecutor manualExecutor;
    private final AgentExecutionProperties properties;
    private final ToolCapabilityRegistry capabilities;

    public AgentExecutorRouter(SpringAiAutoToolExecutor autoExecutor,
                               ManualReactExecutor manualExecutor,
                               AgentExecutionProperties properties,
                               ToolCapabilityRegistry capabilities) {
        this.autoExecutor = autoExecutor;
        this.manualExecutor = manualExecutor;
        this.properties = properties;
        this.capabilities = capabilities;
    }

    public AgentExecutor select(TaskPlan plan) {
        return switch (properties.executionMode().toLowerCase()) {
            case "auto" -> autoExecutor;
            case "react" -> manualExecutor;
            default -> requiresDeterministicExecution(plan) && supportsManualPlan(plan)
                    ? manualExecutor : autoExecutor;
        };
    }

    public boolean isManual(TaskPlan plan) {
        return select(plan) == manualExecutor;
    }

    private static boolean requiresDeterministicExecution(TaskPlan plan) {
        if (plan == null) return false;
        return plan.taskType() == TaskType.CREATIVE_WORKFLOW
                || plan.steps().stream().anyMatch(step ->
                step.required() && step.dependsOn() != null && !step.dependsOn().isEmpty());
    }

    private boolean supportsManualPlan(TaskPlan plan) {
        if (plan == null) return false;
        return plan.steps().stream()
                .filter(step -> step.toolName() != null)
                .allMatch(step -> capabilities.supportsManual(step.toolName()));
    }
}
