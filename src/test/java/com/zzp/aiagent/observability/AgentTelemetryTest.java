package com.zzp.aiagent.observability;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.tck.TestObservationRegistry;
import io.micrometer.observation.tck.TestObservationRegistryAssert;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentTelemetryTest {

    @Test
    void recordsObservationAttributesEventsAndMetrics() {
        TestObservationRegistry observationRegistry = TestObservationRegistry.create();
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        AgentTelemetry telemetry = telemetry(observationRegistry, meterRegistry, true);

        try (AgentTelemetry.AgentObservation observation = telemetry.start(AgentObservationNames.PLANNER)
                .lowCardinality(AgentObservationKeys.Low.PLAN_SOURCE, "llm")
                .highCardinality(AgentObservationKeys.High.TURN_ID, "turn-1")
                .event("plan.generated")) {
            try (Observation.Scope ignored = observation.openScope()) {
                assertThat(observationRegistry.getCurrentObservation()).isNotNull();
            }
        }
        telemetry.increment("agent.planner.calls", AgentObservationKeys.Low.PLAN_SOURCE, "llm");
        telemetry.record("agent.planner.duration", Duration.ofMillis(12),
                AgentObservationKeys.Low.PLAN_SOURCE, "llm");
        telemetry.recordAmount("agent.rag.candidates", 5,
                AgentObservationKeys.Low.RAG_PATH, "vector");

        TestObservationRegistryAssert.assertThat(observationRegistry)
                .hasObservationWithNameEqualTo(AgentObservationNames.PLANNER);
        assertThat(meterRegistry.get("agent.planner.calls").counter().count()).isEqualTo(1);
        assertThat(meterRegistry.get("agent.planner.duration").timer().count()).isEqualTo(1);
        assertThat(meterRegistry.get("agent.rag.candidates").summary().totalAmount()).isEqualTo(5);
    }

    @Test
    void rejectsHighCardinalityMetricTags() {
        AgentTelemetry telemetry = telemetry(
                TestObservationRegistry.create(), new SimpleMeterRegistry(), true);

        assertThatThrownBy(() -> telemetry.increment(
                "agent.turns", AgentObservationKeys.High.TURN_ID, "turn-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not approved as low cardinality");
        assertThatThrownBy(() -> telemetry.recordAmount(
                "agent.rag.candidates", 1, AgentObservationKeys.High.RAG_CANDIDATE_COUNT, "1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not approved as low cardinality");
    }

    @Test
    void disabledTelemetryDoesNotCreateMeters() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        AgentTelemetry telemetry = telemetry(
                TestObservationRegistry.create(), meterRegistry, false);

        telemetry.increment("agent.turns", AgentObservationKeys.Low.OUTCOME, "success");
        telemetry.record("agent.turn.duration", Duration.ofMillis(1),
                AgentObservationKeys.Low.OUTCOME, "success");

        assertThat(meterRegistry.getMeters()).isEmpty();
        assertThat(telemetry.currentTraceId()).isNull();
    }

    private AgentTelemetry telemetry(ObservationRegistry observationRegistry,
                                     SimpleMeterRegistry meterRegistry,
                                     boolean enabled) {
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        return new AgentTelemetry(
                observationRegistry,
                meterRegistry,
                beanFactory.getBeanProvider(Tracer.class),
                new AgentObservabilityProperties(enabled, true)
        );
    }
}
