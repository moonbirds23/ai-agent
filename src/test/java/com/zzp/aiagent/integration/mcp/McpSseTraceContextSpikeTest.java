package com.zzp.aiagent.integration.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Characterization spike for Spring AI 1.0.0's MCP SDK 0.10.0 SSE transport.
 *
 * <p>The production client reuses one SSE transport and sends each JSON-RPC message
 * as an HTTP POST. This test proves whether the transport reads the active W3C context
 * for each POST. It deliberately exercises the SDK transport directly so Spring MVC
 * server instrumentation cannot hide missing client-side injection.</p>
 */
class McpSseTraceContextSpikeTest {

    private final List<CapturedRequest> capturedRequests = new ArrayList<>();
    private final CountDownLatch releaseSse = new CountDownLatch(1);
    private HttpServer server;
    private HttpClientSseClientTransport transport;
    private SdkTracerProvider tracerProvider;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/sse", this::serveEndpointEvent);
        server.createContext("/mcp/message", this::captureMessage);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();

        transport = HttpClientSseClientTransport
                .builder("http://127.0.0.1:" + server.getAddress().getPort())
                .sseEndpoint("/sse")
                .objectMapper(new ObjectMapper())
                .build();
        transport.connect(message -> Mono.empty()).block(Duration.ofSeconds(5));
    }

    @AfterEach
    void tearDown() {
        if (transport != null) {
            transport.closeGracefully().block(Duration.ofSeconds(2));
        }
        releaseSse.countDown();
        if (server != null) {
            server.stop(0);
        }
        if (tracerProvider != null) {
            tracerProvider.close();
        }
    }

    @Test
    @DisplayName("MCP SSE POST does not inject the active W3C context per tool call")
    void shouldCharacterizePerCallTraceContextPropagation() {
        tracerProvider = SdkTracerProvider.builder().build();
        OpenTelemetry openTelemetry = OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .setPropagators(ContextPropagators.create(
                        W3CTraceContextPropagator.getInstance()))
                .build();
        Tracer tracer = openTelemetry.getTracer("mcp-sse-trace-context-spike");

        List<String> activeTraceIds = new ArrayList<>();
        List<String> manuallyInjectedTraceparents = new ArrayList<>();
        for (int attempt = 1; attempt <= 2; attempt++) {
            Span parent = tracer.spanBuilder("parent-" + attempt).startSpan();
            activeTraceIds.add(parent.getSpanContext().getTraceId());
            try (Scope ignored = parent.makeCurrent()) {
                Map<String, String> carrier = new LinkedHashMap<>();
                openTelemetry.getPropagators().getTextMapPropagator().inject(
                        Context.current(), carrier, Map::put);
                manuallyInjectedTraceparents.add(carrier.get("traceparent"));

                transport.sendMessage(new McpSchema.JSONRPCRequest(
                                "2.0",
                                "tools/call",
                                attempt,
                                Map.of("name", "healthCheck", "arguments", Map.of())))
                        .block(Duration.ofSeconds(5));
            } finally {
                parent.end();
            }
        }

        assertThat(activeTraceIds).hasSize(2).doesNotHaveDuplicates();
        assertThat(manuallyInjectedTraceparents)
                .allSatisfy(traceparent -> assertThat(traceparent).isNotBlank());
        for (int index = 0; index < activeTraceIds.size(); index++) {
            assertThat(manuallyInjectedTraceparents.get(index))
                    .contains(activeTraceIds.get(index));
        }

        assertThat(capturedRequests).hasSize(2);
        assertThat(capturedRequests)
                .extracting(CapturedRequest::method)
                .containsOnly("POST");
        assertThat(capturedRequests)
                .extracting(CapturedRequest::traceparent)
                .containsOnlyNulls();
        assertThat(capturedRequests)
                .extracting(CapturedRequest::tracestate)
                .containsOnlyNulls();
    }

    private void serveEndpointEvent(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.sendResponseHeaders(200, 0);
        try (var body = exchange.getResponseBody()) {
            body.write("event: endpoint\ndata: /mcp/message\n\n"
                    .getBytes(StandardCharsets.UTF_8));
            body.flush();
            try {
                releaseSse.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void captureMessage(HttpExchange exchange) throws IOException {
        byte[] requestBody = exchange.getRequestBody().readAllBytes();
        synchronized (capturedRequests) {
            capturedRequests.add(new CapturedRequest(
                    exchange.getRequestMethod(),
                    exchange.getRequestHeaders().getFirst("traceparent"),
                    exchange.getRequestHeaders().getFirst("tracestate"),
                    new String(requestBody, StandardCharsets.UTF_8)));
        }
        exchange.sendResponseHeaders(202, -1);
        exchange.close();
    }

    private record CapturedRequest(
            String method,
            String traceparent,
            String tracestate,
            String body
    ) {
    }
}
