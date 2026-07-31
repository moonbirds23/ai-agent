package com.zzp.aiagent.agent.executor;

import com.zzp.aiagent.agent.AgentResult;
import com.zzp.aiagent.agent.AgentState;
import com.zzp.aiagent.agent.task.TaskLedger;
import com.zzp.aiagent.agent.task.TaskPlan;
import com.zzp.aiagent.agent.task.TaskStatus;
import com.zzp.aiagent.agent.task.TaskType;
import com.zzp.aiagent.agent.task.TaskVerifier;
import com.zzp.aiagent.agent.task.ToolExecutionRecord;
import com.zzp.aiagent.model.dto.chat.ChatRequest;
import com.zzp.aiagent.tool.ToolExecutor;
import com.zzp.aiagent.observability.AgentTelemetry;
import com.zzp.aiagent.observability.AgentObservationNames;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.tck.TestObservationRegistry;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ManualReactExecutorTest {

    @Test
    void failedBackendToolCannotBecomeSuccessEvidence() {
        TaskLedger ledger = new TaskLedger();
        ToolExecutor toolExecutor = (turnId, step, context) ->
                ToolExecutionRecord.failure(turnId, step.toolName(), step.input(), "backend failed");
        TestObservationRegistry observations = TestObservationRegistry.create();
        AgentTelemetry telemetry = telemetry(observations, new SimpleMeterRegistry());
        ManualReactExecutor executor = new ManualReactExecutor(ledger, toolExecutor, telemetry);
        TaskPlan plan = new com.zzp.aiagent.agent.task.TaskPlanner().plan(
                new ChatRequest("先在图库找雪景再生成海报", null, null, null, null), "turn-1");
        ledger.startPlan(plan);

        AgentResult result = executor.execute(AgentInput.of(
                "先在图库找雪景再生成海报", "", List.of(), null,
                Map.of("turnId", "turn-1"), "chat-1", plan));

        assertThat(result.state()).isEqualTo(AgentState.ERROR);
        assertThat(ledger.countSuccess("turn-1", "searchGallery")).isZero();
        assertThat(TaskVerifier.verify(plan, ledger.getRecords("turn-1")).status())
                .isEqualTo(TaskStatus.FAILED);
        observations.assertThat().hasObservationWithNameEqualTo(AgentObservationNames.STEP);
        observations.assertThat().hasObservationWithNameEqualTo(AgentObservationNames.TOOL);
    }

    @Test
    void preservesRealToolTimingInLedger() {
        TaskLedger ledger = new TaskLedger();
        ToolExecutor toolExecutor = (turnId, step, context) ->
                ToolExecutionRecord.success(turnId, step.toolName(), step.input(), Map.of("ok", true),
                        ToolExecutionRecord.NONE, 1_000L, 1_075L);
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        ManualReactExecutor executor = new ManualReactExecutor(
                ledger, toolExecutor, telemetry(TestObservationRegistry.create(), meters));
        TaskPlan plan = new TaskPlan("turn-timing", TaskType.CHAT, "test",
                List.of(com.zzp.aiagent.agent.task.TaskStep.of(
                        "search", "search", true, "searchGallery")),
                false, false, false, Map.of());
        ledger.startPlan(plan);

        AgentResult result = executor.execute(AgentInput.of(
                "test", "", List.of(), null, Map.of("turnId", "turn-timing"), "chat-1", plan));

        assertThat(result.state()).isEqualTo(AgentState.FINISHED);
        assertThat(ledger.getRecords("turn-timing")).singleElement()
                .extracting(ToolExecutionRecord::elapsedMs)
                .isEqualTo(75L);
        assertThat(meters.get("agent.tool.calls").counter().count()).isEqualTo(1);
        assertThat(meters.get("agent.tool.duration").timer().totalTime(
                java.util.concurrent.TimeUnit.MILLISECONDS)).isEqualTo(75.0);
    }

    private static AgentTelemetry telemetry(ObservationRegistry observations,
                                            SimpleMeterRegistry meters) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        return new AgentTelemetry(observations, meters,
                beans.getBeanProvider(io.micrometer.tracing.Tracer.class),
                new com.zzp.aiagent.observability.AgentObservabilityProperties(true, false));
    }
}
