package com.zzp.aiagent.agent;

import com.zzp.aiagent.agent.executor.AgentExecutor;
import com.zzp.aiagent.agent.executor.AgentExecutorRouter;
import com.zzp.aiagent.agent.executor.AgentInput;
import com.zzp.aiagent.agent.task.LlmTaskPlanner;
import com.zzp.aiagent.agent.task.PlanSource;
import com.zzp.aiagent.agent.task.PlanningResult;
import com.zzp.aiagent.agent.task.RecoveryPolicy;
import com.zzp.aiagent.agent.task.TaskLedger;
import com.zzp.aiagent.agent.task.TaskPlan;
import com.zzp.aiagent.model.dto.chat.ChatRequest;
import com.zzp.aiagent.observability.AgentObservabilityProperties;
import com.zzp.aiagent.observability.AgentObservationKeys;
import com.zzp.aiagent.observability.AgentObservationNames;
import com.zzp.aiagent.observability.AgentTelemetry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.tck.TestObservationRegistry;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkflowEngineObservabilityTest {

    @Test
    void usesCallerTurnIdAndRecordsPlannerDecisionAndExecutor() {
        LlmTaskPlanner planner = mock(LlmTaskPlanner.class);
        AgentExecutorRouter router = mock(AgentExecutorRouter.class);
        TaskLedger ledger = new TaskLedger();
        TestObservationRegistry registry = TestObservationRegistry.create();
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        AgentTelemetry telemetry = new AgentTelemetry(
                registry, new SimpleMeterRegistry(), beans.getBeanProvider(Tracer.class),
                new AgentObservabilityProperties(true, false));
        WorkflowEngine engine = new WorkflowEngine(
                planner, router, ledger, new RecoveryPolicy(), telemetry);
        ChatRequest request = new ChatRequest("hello", null, null, null, null);
        TaskPlan expected = TaskPlan.chat("chat-1:turn-1", "hello");
        when(planner.planWithMetadata(request, expected.turnId()))
                .thenReturn(new PlanningResult(expected, PlanSource.REPAIRED, true, true));

        TaskPlan actual = engine.plan(request, "chat-1", expected.turnId());

        assertThat(actual.turnId()).isEqualTo(expected.turnId());
        verify(planner).planWithMetadata(request, expected.turnId());
        registry.assertThat()
                .hasObservationWithNameEqualTo(AgentObservationNames.PLANNER);
        registry.assertThat().hasAnObservationWithAKeyValue(
                AgentObservationKeys.Low.PLAN_SOURCE, "repaired");

        AgentExecutor executor = mock(AgentExecutor.class);
        AgentInput input = AgentInput.of(
                "hello", "", List.of(), null, Map.of(), "chat-1", expected);
        when(executor.execute(input))
                .thenReturn(new AgentResult(AgentState.FINISHED, "ok", null));

        AgentResult result = engine.execute(executor, input, expected);

        assertThat(result.content()).isEqualTo("ok");
        registry.assertThat()
                .hasObservationWithNameEqualTo(AgentObservationNames.EXECUTOR);
        registry.assertThat().hasAnObservationWithAKeyValue(
                AgentObservationKeys.Low.OUTCOME, "success");
    }
}
