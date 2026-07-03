package com.zzp.aiagent.agent.executor;

import com.zzp.aiagent.agent.AgentResult;
import com.zzp.aiagent.agent.AgentState;
import com.zzp.aiagent.agent.task.StepStatus;
import com.zzp.aiagent.agent.task.TaskLedger;
import com.zzp.aiagent.agent.task.TaskPlan;
import com.zzp.aiagent.agent.task.TaskStep;
import com.zzp.aiagent.agent.task.ToolExecutionRecord;
import com.zzp.aiagent.tool.ToolExecutionContext;
import com.zzp.aiagent.tool.ToolExecutor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.Map;

@Slf4j
@Component
@Profile("!test")
public class ManualReactExecutor implements AgentExecutor {

    private final TaskLedger taskLedger;
    private final ToolExecutor toolExecutor;

    public ManualReactExecutor(TaskLedger taskLedger, ToolExecutor toolExecutor) {
        this.taskLedger = taskLedger;
        this.toolExecutor = toolExecutor;
    }

    @Override
    public AgentResult execute(AgentInput input) {
        TaskPlan plan = input.plan();
        if (plan == null) {
            return new AgentResult(AgentState.ERROR, "ManualReactExecutor requires a TaskPlan", null);
        }

        String turnId = turnIdFrom(input.toolContext());
        log.info("[ManualReact] execute turnId={} type={} steps={}", turnId, plan.taskType(), plan.steps().size());

        StringBuilder output = new StringBuilder();
        ToolExecutionContext executionContext = new ToolExecutionContext(input);
        for (TaskStep step : plan.steps()) {
            if (!dependenciesSatisfied(step, turnId)) {
                log.warn("[ManualReact] skipping step {}, dependencies not met", step.code());
                taskLedger.failStep(turnId, step.code(), "Dependencies not satisfied");
                if (step.required()) {
                    return new AgentResult(AgentState.ERROR,
                            "Required step '" + step.code() + "' blocked by failed dependency", null);
                }
                continue;
            }

            taskLedger.startStep(turnId, step.code());
            log.info("[ManualReact] executing step {}", step.code());

            try {
                if (step.toolName() == null) {
                    taskLedger.completeStep(turnId, step.code(), null);
                    continue;
                }
                taskLedger.beforeCall(turnId, step.toolName(), step.input());
                ToolExecutionRecord record = toolExecutor.execute(turnId, step, executionContext);
                if (!record.success()) {
                    taskLedger.recordFailure(turnId, step.toolName(), step.input(), record.errorMessage());
                    throw new IllegalStateException(record.errorMessage());
                }
                taskLedger.recordSuccess(turnId, step.toolName(), record.input(),
                        record.output(), record.sideEffect());
                taskLedger.completeStep(turnId, step.code(), null);
                executionContext.record(step.code(), record);
                output.append(formatStepResult(step, record)).append("\n");
            } catch (Exception e) {
                log.error("[ManualReact] step {} failed: {}", step.code(), e.getMessage());
                taskLedger.failStep(turnId, step.code(), e.getMessage());
                if (step.required()) {
                    return new AgentResult(AgentState.ERROR,
                            "Step '" + step.code() + "' failed: " + e.getMessage(), null);
                }
            }
        }

        return new AgentResult(AgentState.FINISHED, output.toString().trim(), null);
    }

    @Override
    public Flux<ChatClientResponse> stream(AgentInput input) {
        AgentResult result = execute(input);
        if (result.state() == AgentState.ERROR) return Flux.error(new RuntimeException(result.content()));
        return Flux.empty();
    }

    private boolean dependenciesSatisfied(TaskStep step, String turnId) {
        if (step.dependsOn() == null || step.dependsOn().isEmpty()) return true;
        for (String dep : step.dependsOn()) {
            StepStatus depStatus = taskLedger.getStepStatus(turnId, dep);
            if (depStatus != StepStatus.SUCCESS) return false;
        }
        return true;
    }

    private static String formatStepResult(TaskStep step, ToolExecutionRecord record) {
        if ("searchGallery".equals(step.toolName())) {
            return "图库搜索完成，找到 " + record.output().getOrDefault("resultCount", 0) + " 张参考图。";
        }
        if ("generateImage".equals(step.toolName())) {
            return "图片已生成。";
        }
        if ("analyzeImage".equals(step.toolName())) {
            return "图片分析完成。";
        }
        return step.description() + "已完成。";
    }

    private static String turnIdFrom(Map<String, Object> toolContext) {
        if (toolContext == null) return null;
        Object value = toolContext.get("turnId");
        return value instanceof String s ? s : null;
    }
}
