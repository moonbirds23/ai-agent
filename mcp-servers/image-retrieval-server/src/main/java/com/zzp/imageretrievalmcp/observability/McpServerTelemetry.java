package com.zzp.imageretrievalmcp.observability;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * MCP server tracing for the SSE transport fallback.
 *
 * <p>Spring AI 1.0.0 / MCP SDK 0.10.0 does not inject the active W3C context
 * into each SSE message POST. The server therefore starts a new trace and links
 * it to the real client span encoded by the caller. It intentionally never
 * installs that context as a parent.</p>
 */
@Component
public class McpServerTelemetry {

    public static final String LINK_TRACEPARENT_ARGUMENT = "_agent_traceparent";
    private static final AttributeKey<Boolean> CONTEXT_PROPAGATED =
            AttributeKey.booleanKey("agent.mcp.trace_context_propagated");
    private static final AttributeKey<Boolean> LINK_CREATED =
            AttributeKey.booleanKey("agent.mcp.span_link.created");
    private static final TextMapGetter<Map<String, String>> MAP_GETTER = new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(Map<String, String> carrier) {
            return carrier.keySet();
        }

        @Override
        public String get(Map<String, String> carrier, String key) {
            return carrier.get(key);
        }
    };

    private final Tracer tracer;

    public McpServerTelemetry(ObjectProvider<OpenTelemetry> openTelemetryProvider) {
        OpenTelemetry openTelemetry = openTelemetryProvider.getIfAvailable(OpenTelemetry::noop);
        this.tracer = openTelemetry.getTracer("image-retrieval-mcp-server");
    }

    public TraceScope startToolCall(String toolName, String linkTraceparent) {
        SpanBuilder builder = tracer.spanBuilder("mcp.server.tools/call")
                .setNoParent()
                .setSpanKind(SpanKind.SERVER)
                .setAttribute("agent.mcp.server.name", "image-retrieval-server")
                .setAttribute("agent.mcp.tool.name", toolName)
                .setAttribute("agent.mcp.transport", "sse")
                .setAttribute(CONTEXT_PROPAGATED, false);

        io.opentelemetry.api.trace.SpanContext linkedContext = extract(linkTraceparent);
        boolean linked = linkedContext.isValid();
        if (linked) {
            builder.addLink(linkedContext);
        }

        Span span = builder.setAttribute(LINK_CREATED, linked).startSpan();
        return new TraceScope(span, span.makeCurrent());
    }

    public TraceScope startPexelsHttp(String operation) {
        Span span = tracer.spanBuilder("http.client.pexels")
                .setSpanKind(SpanKind.CLIENT)
                .setAttribute("http.request.method", "GET")
                .setAttribute("server.address", "api.pexels.com")
                .setAttribute("agent.pexels.operation", operation)
                .startSpan();
        return new TraceScope(span, span.makeCurrent());
    }

    private io.opentelemetry.api.trace.SpanContext extract(String traceparent) {
        if (traceparent == null || traceparent.isBlank()) {
            return io.opentelemetry.api.trace.SpanContext.getInvalid();
        }
        Context extracted = W3CTraceContextPropagator.getInstance().extract(
                Context.root(), Map.of("traceparent", traceparent), MAP_GETTER);
        return Span.fromContext(extracted).getSpanContext();
    }

    public static final class TraceScope implements AutoCloseable {
        private final Span span;
        private final Scope scope;

        private TraceScope(Span span, Scope scope) {
            this.span = span;
            this.scope = scope;
        }

        public TraceScope error(Throwable error) {
            if (error != null) {
                span.recordException(error);
            }
            return this;
        }

        @Override
        public void close() {
            scope.close();
            span.end();
        }
    }
}
