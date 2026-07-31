package com.zzp.aiagent.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class AgentTelemetry {

    private static final Set<String> LOW_CARDINALITY_KEYS = Set.of(
            AgentObservationKeys.Low.TASK_TYPE,
            AgentObservationKeys.Low.TASK_OUTCOME,
            AgentObservationKeys.Low.REQUEST_MODE,
            AgentObservationKeys.Low.REQUEST_CONTENT_CAPTURED,
            AgentObservationKeys.Low.EXECUTOR_TYPE,
            AgentObservationKeys.Low.PLAN_SOURCE,
            AgentObservationKeys.Low.PLAN_VALIDATION,
            AgentObservationKeys.Low.PLAN_REPAIR_ATTEMPTED,
            AgentObservationKeys.Low.PLAN_FALLBACK_USED,
            AgentObservationKeys.Low.OUTCOME,
            AgentObservationKeys.Low.TOOL_NAME,
            AgentObservationKeys.Low.TOOL_OUTCOME,
            AgentObservationKeys.Low.TOOL_MODE,
            AgentObservationKeys.Low.TOOL_SIDE_EFFECT,
            AgentObservationKeys.Low.TOOL_RECOVERABLE,
            AgentObservationKeys.Low.RAG_PATH,
            AgentObservationKeys.Low.RAG_EMPTY,
            AgentObservationKeys.Low.RAG_REFERENCE_MODE,
            AgentObservationKeys.Low.VERIFICATION_STATUS,
            AgentObservationKeys.Low.VERIFY_VERDICT,
            AgentObservationKeys.Low.VERIFY_NO_SAVE,
            AgentObservationKeys.Low.VERIFY_RESULT_COUNT,
            AgentObservationKeys.Low.MEMORY_WRITE,
            AgentObservationKeys.Low.MEMORY_WRITE_REASON,
            AgentObservationKeys.Low.RECOVERY_TYPE,
            AgentObservationKeys.Low.MCP_SERVER_NAME,
            AgentObservationKeys.Low.MCP_TRANSPORT,
            AgentObservationKeys.Low.MCP_TRACE_CONTEXT_PROPAGATED,
            AgentObservationKeys.Low.COST_STATUS,
            "gen_ai.request.model"
    );

    private final ObservationRegistry observationRegistry;
    private final MeterRegistry meterRegistry;
    private final Tracer tracer;
    private final AgentObservabilityProperties properties;

    public AgentTelemetry(ObservationRegistry observationRegistry,
                          MeterRegistry meterRegistry,
                          ObjectProvider<Tracer> tracerProvider,
                          AgentObservabilityProperties properties) {
        this.observationRegistry = observationRegistry;
        this.meterRegistry = meterRegistry;
        this.tracer = tracerProvider.getIfAvailable();
        this.properties = properties;
    }

    public AgentObservation start(String name) {
        ObservationRegistry registry = properties.enabled()
                ? observationRegistry
                : ObservationRegistry.NOOP;
        return new AgentObservation(Observation.start(name, registry));
    }

    public String currentTraceId() {
        if (!properties.enabled() || tracer == null) {
            return null;
        }
        Span span = tracer.currentSpan();
        return span == null ? null : span.context().traceId();
    }

    public String currentSpanId() {
        if (!properties.enabled() || tracer == null) {
            return null;
        }
        Span span = tracer.currentSpan();
        return span == null ? null : span.context().spanId();
    }

    /**
     * Returns the current span as a W3C traceparent value.
     *
     * <p>This is used only as link metadata when a transport cannot propagate
     * trace context as HTTP headers. Receiving services must not install it as
     * their parent context.</p>
     */
    public String currentTraceParent() {
        if (!properties.enabled() || tracer == null) {
            return null;
        }
        Span span = tracer.currentSpan();
        if (span == null || span.context() == null) {
            return null;
        }
        String traceId = span.context().traceId();
        String spanId = span.context().spanId();
        if (traceId == null || traceId.length() != 32 || spanId == null || spanId.length() != 16) {
            return null;
        }
        return "00-" + traceId + "-" + spanId + (Boolean.TRUE.equals(span.context().sampled()) ? "-01" : "-00");
    }

    public void increment(String metricName, String... lowCardinalityTags) {
        if (properties.enabled()) {
            meterRegistry.counter(metricName, validatedTags(lowCardinalityTags)).increment();
        }
    }

    public void record(String metricName, Duration duration, String... lowCardinalityTags) {
        if (properties.enabled() && duration != null && !duration.isNegative()) {
            meterRegistry.timer(metricName, validatedTags(lowCardinalityTags)).record(duration);
        }
    }

    public void recordAmount(String metricName, double amount, String... lowCardinalityTags) {
        if (properties.enabled() && Double.isFinite(amount) && amount >= 0) {
            meterRegistry.summary(metricName, validatedTags(lowCardinalityTags)).record(amount);
        }
    }

    private Iterable<Tag> validatedTags(String... keyValues) {
        if (keyValues == null || keyValues.length % 2 != 0) {
            throw new IllegalArgumentException("Metric tags must be key/value pairs");
        }
        for (int index = 0; index < keyValues.length; index += 2) {
            if (!LOW_CARDINALITY_KEYS.contains(keyValues[index])) {
                throw new IllegalArgumentException(
                        "Metric tag is not approved as low cardinality: " + keyValues[index]);
            }
        }
        return Tags.of(keyValues);
    }

    public static final class AgentObservation implements AutoCloseable {
        private final Observation observation;
        private final AtomicBoolean stopped = new AtomicBoolean();

        private AgentObservation(Observation observation) {
            this.observation = observation;
        }

        public AgentObservation lowCardinality(String key, Object value) {
            observation.lowCardinalityKeyValue(key, String.valueOf(value));
            return this;
        }

        public AgentObservation highCardinality(String key, Object value) {
            observation.highCardinalityKeyValue(key, String.valueOf(value));
            return this;
        }

        public AgentObservation event(String name) {
            observation.event(Observation.Event.of(name));
            return this;
        }

        public AgentObservation error(Throwable error) {
            if (error != null) {
                observation.error(error);
            }
            return this;
        }

        public Observation.Scope openScope() {
            return observation.openScope();
        }

        public Observation observation() {
            return observation;
        }

        public void stop() {
            if (stopped.compareAndSet(false, true)) {
                observation.stop();
            }
        }

        @Override
        public void close() {
            stop();
        }
    }
}
