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
import com.zzp.aiagent.observability.AgentObservationKeys;
import com.zzp.aiagent.observability.AgentObservationNames;
import com.zzp.aiagent.observability.AgentTelemetry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.Map;
import java.time.Duration;
import java.util.UUID;

@Slf4j
@Component
@Profile("!test")
public class ManualReactExecutor implements AgentExecutor {

    private final TaskLedger taskLedger;
    private final ToolExecutor toolExecutor;
    private final AgentTelemetry telemetry;

    public ManualReactExecutor(TaskLedger taskLedger, ToolExecutor toolExecutor,
                               AgentTelemetry telemetry) {
        this.taskLedger = taskLedger;
        this.toolExecutor = toolExecutor;
        this.telemetry = telemetry;
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
            AgentTelemetry.AgentObservation stepObservation = telemetry.start(AgentObservationNames.STEP)
                    .highCardinality(AgentObservationKeys.High.TURN_ID, turnId)
                    .highCardinality(AgentObservationKeys.High.STEP_CODE, step.code());
            if (!dependenciesSatisfied(step, turnId)) {
                stepObservation.lowCardinality(AgentObservationKeys.Low.OUTCOME, "blocked")
                        .event("step.blocked")
                        .stop();
                log.warn("[ManualReact] skipping step {}, dependencies not met", step.code());
                taskLedger.failStep(turnId, step.code(), "Dependencies not satisfied");
                if (step.required()) {
                    return new AgentResult(AgentState.ERROR,
                            "Required step '" + step.code() + "' blocked by failed dependency", null);
                }
                continue;
            }

            try (var ignored = stepObservation.openScope()) {
                taskLedger.startStep(turnId, step.code());
                log.info("[ManualReact] executing step {}", step.code());
                if (step.toolName() == null) {
                    taskLedger.completeStep(turnId, step.code(), null);
                    stepObservation.lowCardinality(AgentObservationKeys.Low.OUTCOME, "success");
                    continue;
                }
                String toolCallId = UUID.randomUUID().toString();
                AgentTelemetry.AgentObservation toolObservation = telemetry.start(AgentObservationNames.TOOL)
                        .lowCardinality(AgentObservationKeys.Low.TOOL_NAME, step.toolName())
                        .lowCardinality(AgentObservationKeys.Low.TOOL_MODE, "manual")
                        .highCardinality(AgentObservationKeys.High.TURN_ID, turnId)
                        .highCardinality(AgentObservationKeys.High.TOOL_CALL_ID, toolCallId)
                        .highCardinality(AgentObservationKeys.High.TOOL_ATTEMPT, 1);
                long toolStartedAt = System.nanoTime();
                ToolExecutionRecord record;
                try (var toolScope = toolObservation.openScope()) {
                    taskLedger.beforeCall(turnId, step.toolName(), step.input());
                    record = toolExecutor.execute(turnId, step, executionContext);
                    toolObservation
                            .lowCardinality(AgentObservationKeys.Low.OUTCOME,
                                    record.success() ? "success" : "error")
                            .lowCardinality(AgentObservationKeys.Low.TOOL_SIDE_EFFECT, record.sideEffect())
                            .lowCardinality(AgentObservationKeys.Low.TOOL_RECOVERABLE, record.recoverable());
                    if (!record.success()) {
                        toolObservation.error(new ManualToolObservationError());
                    }
                    telemetry.increment("agent.tool.calls",
                            AgentObservationKeys.Low.TOOL_NAME, step.toolName(),
                            AgentObservationKeys.Low.TOOL_MODE, "manual",
                            AgentObservationKeys.Low.OUTCOME, record.success() ? "success" : "error");
                    telemetry.record("agent.tool.duration", Duration.ofMillis(record.elapsedMs()),
                            AgentObservationKeys.Low.TOOL_NAME, step.toolName(),
                            AgentObservationKeys.Low.TOOL_MODE, "manual",
                            AgentObservationKeys.Low.OUTCOME, record.success() ? "success" : "error");
                } catch (RuntimeException error) {
                    toolObservation.lowCardinality(AgentObservationKeys.Low.OUTCOME, "error")
                            .error(new ManualToolObservationError());
                    telemetry.increment("agent.tool.calls",
                            AgentObservationKeys.Low.TOOL_NAME, step.toolName(),
                            AgentObservationKeys.Low.TOOL_MODE, "manual",
                            AgentObservationKeys.Low.OUTCOME, "error");
                    telemetry.record("agent.tool.duration",
                            Duration.ofNanos(Math.max(0, System.nanoTime() - toolStartedAt)),
                            AgentObservationKeys.Low.TOOL_NAME, step.toolName(),
                            AgentObservationKeys.Low.TOOL_MODE, "manual",
                            AgentObservationKeys.Low.OUTCOME, "error");
                    throw error;
                } finally {
                    toolObservation.stop();
                }
                if (!record.success()) {
                    taskLedger.recordExecution(record);
                    throw new IllegalStateException(record.errorMessage());
                }
                taskLedger.completeStep(turnId, step.code(), record);
                executionContext.record(step.code(), record);
                output.append(formatStepResult(step, record)).append("\n");
                stepObservation.lowCardinality(AgentObservationKeys.Low.OUTCOME, "success");
            } catch (Exception e) {
                stepObservation.lowCardinality(AgentObservationKeys.Low.OUTCOME, "error").error(e);
                log.error("[ManualReact] step {} failed: {}", step.code(), e.getMessage());
                taskLedger.failStep(turnId, step.code(), e.getMessage());
                if (step.required()) {
                    return new AgentResult(AgentState.ERROR,
                            "Step '" + step.code() + "' failed: " + e.getMessage(), null);
                }
            } finally {
                stepObservation.stop();
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

    private static final class ManualToolObservationError extends RuntimeException {
        private ManualToolObservationError() {
            super("Manual tool execution failed; details redacted");
        }
    }
}
