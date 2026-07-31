package com.zzp.imageretrievalmcp.observability;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class McpServerTelemetryTest {

    @Test
    void createsNewServerTraceWithRealLinkAndPexelsChild() {
        CapturingExporter exporter = new CapturingExporter();
        SdkTracerProvider provider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        OpenTelemetry openTelemetry = OpenTelemetrySdk.builder()
                .setTracerProvider(provider)
                .build();
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        beans.registerSingleton("openTelemetry", openTelemetry);
        McpServerTelemetry telemetry = new McpServerTelemetry(
                beans.getBeanProvider(OpenTelemetry.class));

        SpanContext clientContext = SpanContext.create(
                "11111111111111111111111111111111",
                "2222222222222222",
                TraceFlags.getSampled(),
                TraceState.getDefault());
        String traceparent = "00-" + clientContext.getTraceId() + "-"
                + clientContext.getSpanId() + "-01";

        try (var server = telemetry.startToolCall("pexelsSearchPhotos", traceparent)) {
            try (var http = telemetry.startPexelsHttp("search")) {
                // The scopes are the behavior under test.
            }
        } finally {
            provider.close();
        }

        SpanData serverSpan = exporter.spans.stream()
                .filter(span -> span.getName().equals("mcp.server.tools/call"))
                .findFirst()
                .orElseThrow();
        SpanData httpSpan = exporter.spans.stream()
                .filter(span -> span.getName().equals("http.client.pexels"))
                .findFirst()
                .orElseThrow();

        assertThat(serverSpan.getTraceId()).isNotEqualTo(clientContext.getTraceId());
        assertThat(serverSpan.getParentSpanId()).isEqualTo(SpanContext.getInvalid().getSpanId());
        assertThat(serverSpan.getLinks()).singleElement()
                .satisfies(link -> {
                    assertThat(link.getSpanContext().getTraceId()).isEqualTo(clientContext.getTraceId());
                    assertThat(link.getSpanContext().getSpanId()).isEqualTo(clientContext.getSpanId());
                });
        assertThat(serverSpan.getAttributes().get(
                AttributeKey.booleanKey("agent.mcp.trace_context_propagated"))).isFalse();
        assertThat(serverSpan.getAttributes().get(
                AttributeKey.booleanKey("agent.mcp.span_link.created"))).isTrue();

        assertThat(httpSpan.getTraceId()).isEqualTo(serverSpan.getTraceId());
        assertThat(httpSpan.getParentSpanId()).isEqualTo(serverSpan.getSpanId());
    }

    private static final class CapturingExporter implements SpanExporter {
        private final List<SpanData> spans = new ArrayList<>();

        @Override
        public CompletableResultCode export(Collection<SpanData> spans) {
            this.spans.addAll(spans);
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode flush() {
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode shutdown() {
            return CompletableResultCode.ofSuccess();
        }
    }
}
