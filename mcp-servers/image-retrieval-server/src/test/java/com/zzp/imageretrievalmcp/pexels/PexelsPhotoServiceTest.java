package com.zzp.imageretrievalmcp.pexels;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzp.imageretrievalmcp.config.PexelsConfig;
import com.zzp.imageretrievalmcp.contract.PexelsPhotoDTO;
import com.zzp.imageretrievalmcp.contract.PexelsSearchRequest;
import com.zzp.imageretrievalmcp.contract.PexelsSearchResponse;
import com.zzp.imageretrievalmcp.observability.McpServerTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link PexelsPhotoServiceImpl} using a local fake HTTP server.
 * No real Pexels API calls are made.
 */
class PexelsPhotoServiceTest {

    private HttpServer server;
    private PexelsPhotoServiceImpl service;
    private int serverPort;

    private static final String VALID_SEARCH_RESPONSE = """
            {
              "total_results": 42,
              "page": 1,
              "per_page": 5,
              "photos": [
                {
                  "id": 12345,
                  "width": 4000,
                  "height": 3000,
                  "url": "https://www.pexels.com/photo/test-12345/",
                  "photographer": "Test Photographer",
                  "photographer_url": "https://www.pexels.com/@test",
                  "photographer_id": 100,
                  "avg_color": "#AABBCC",
                  "alt": "A test photo",
                  "src": {
                    "original": "https://images.pexels.com/photos/12345/pexels-photo-12345.jpeg",
                    "large2x": "https://images.pexels.com/photos/12345/pexels-photo-12345.jpeg?auto=compress&cs=tinysrgb&dpr=2&h=650&w=940",
                    "large": "https://images.pexels.com/photos/12345/pexels-photo-12345.jpeg?auto=compress&cs=tinysrgb&h=650&w=940",
                    "medium": "https://images.pexels.com/photos/12345/pexels-photo-12345.jpeg?auto=compress&cs=tinysrgb&h=350",
                    "small": "https://images.pexels.com/photos/12345/pexels-photo-12345.jpeg?auto=compress&cs=tinysrgb&h=130",
                    "portrait": "https://images.pexels.com/photos/12345/pexels-photo-12345.jpeg?auto=compress&cs=tinysrgb&fit=crop&h=1200&w=800",
                    "landscape": "https://images.pexels.com/photos/12345/pexels-photo-12345.jpeg?auto=compress&cs=tinysrgb&fit=crop&h=627&w=1200",
                    "tiny": "https://images.pexels.com/photos/12345/pexels-photo-12345.jpeg?auto=compress&cs=tinysrgb&dpr=1&fit=crop&h=200&w=280"
                  },
                  "liked": false
                }
              ],
              "next_page": "https://api.pexels.com/v1/search/?page=2&per_page=5&query=nature"
            }
            """;

    private static final String EMPTY_SEARCH_RESPONSE = """
            {
              "total_results": 0,
              "page": 1,
              "per_page": 5,
              "photos": []
            }
            """;

    private static final String SINGLE_PHOTO_RESPONSE = """
            {
              "id": 12345,
              "width": 4000,
              "height": 3000,
              "url": "https://www.pexels.com/photo/test-12345/",
              "photographer": "Test Photographer",
              "photographer_url": "https://www.pexels.com/@test",
              "photographer_id": 100,
              "avg_color": "#AABBCC",
              "alt": "A test photo",
              "src": {
                "original": "https://images.pexels.com/photos/12345/pexels-photo-12345.jpeg",
                "large2x": "https://images.pexels.com/photos/12345/pexels-photo-12345.jpeg?auto=compress&cs=tinysrgb&dpr=2&h=650&w=940",
                "large": "https://images.pexels.com/photos/12345/pexels-photo-12345.jpeg?auto=compress&cs=tinysrgb&h=650&w=940",
                "medium": "https://images.pexels.com/photos/12345/pexels-photo-12345.jpeg?auto=compress&cs=tinysrgb&h=350",
                "small": "https://images.pexels.com/photos/12345/pexels-photo-12345.jpeg?auto=compress&cs=tinysrgb&h=130",
                "portrait": "https://images.pexels.com/photos/12345/pexels-photo-12345.jpeg?auto=compress&cs=tinysrgb&fit=crop&h=1200&w=800",
                "landscape": "https://images.pexels.com/photos/12345/pexels-photo-12345.jpeg?auto=compress&cs=tinysrgb&fit=crop&h=627&w=1200",
                "tiny": "https://images.pexels.com/photos/12345/pexels-photo-12345.jpeg?auto=compress&cs=tinysrgb&dpr=1&fit=crop&h=200&w=280"
              },
              "liked": false
            }
            """;

    private static final String MINIMAL_PHOTO_RESPONSE = """
            {
              "total_results": 1,
              "page": 1,
              "per_page": 5,
              "photos": [
                {
                  "id": 1,
                  "width": 100,
                  "height": 100,
                  "url": "",
                  "photographer": "",
                  "photographer_url": "",
                  "avg_color": "",
                  "alt": "",
                  "src": {}
                }
              ]
            }
            """;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        serverPort = server.getAddress().getPort();
        server.setExecutor(null);

        PexelsConfig config = new PexelsConfig(
                "test-api-key",
                "http://localhost:" + serverPort,
                10,
                10,
                5
        );
        McpServerTelemetry telemetry = new McpServerTelemetry(
                new DefaultListableBeanFactory().getBeanProvider(OpenTelemetry.class));
        service = new PexelsPhotoServiceImpl(config, new ObjectMapper(), telemetry);
        server.start();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    // --- Helper: respond with JSON body and 200 status ---
    private static void respondJson(HttpExchange exchange, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    // --- Helper: respond with status code and no body ---
    private static void respondStatus(HttpExchange exchange, int statusCode) throws IOException {
        exchange.sendResponseHeaders(statusCode, -1);
        exchange.close();
    }

    @Test
    void shouldParseNormalSearchResponse() {
        server.createContext("/v1/search", exchange -> respondJson(exchange, VALID_SEARCH_RESPONSE));

        PexelsSearchRequest request = new PexelsSearchRequest("nature", 5, 1);
        PexelsSearchResponse response = service.searchPhotos(request);

        assertNotNull(response);
        assertEquals(42, response.totalResults());
        assertEquals(1, response.page());
        assertEquals(5, response.perPage());
        assertEquals("nature", response.query());
        assertEquals("pexels", response.source());
        assertEquals("1.0", response.schemaVersion());
        assertTrue(response.latencyMs() >= 0);
        assertEquals(1, response.photos().size());

        PexelsPhotoDTO photo = response.photos().get(0);
        assertEquals(12345L, photo.id());
        assertEquals(4000, photo.width());
        assertEquals(3000, photo.height());
        assertEquals("A test photo", photo.alt());
        assertEquals("Test Photographer", photo.photographer());
        assertEquals("https://www.pexels.com/@test", photo.photographerUrl());
        assertEquals("#AABBCC", photo.avgColor());
        assertNotNull(photo.src());
        assertNotNull(photo.src().original());
    }

    @Test
    void shouldHandleEmptySearchResults() {
        server.createContext("/v1/search", exchange -> respondJson(exchange, EMPTY_SEARCH_RESPONSE));

        PexelsSearchRequest request = new PexelsSearchRequest("nonexistent_xyz_999", 5, 1);
        PexelsSearchResponse response = service.searchPhotos(request);

        assertNotNull(response);
        assertEquals(0, response.totalResults());
        assertTrue(response.photos().isEmpty());
    }

    @Test
    void shouldGetSinglePhoto() {
        server.createContext("/v1/photos/12345", exchange -> respondJson(exchange, SINGLE_PHOTO_RESPONSE));

        PexelsPhotoDTO photo = service.getPhoto(12345);

        assertNotNull(photo);
        assertEquals(12345L, photo.id());
        assertEquals(4000, photo.width());
        assertEquals(3000, photo.height());
        assertEquals("Test Photographer", photo.photographer());
        assertNotNull(photo.src());
    }

    @Test
    void shouldThrowPexelsRateLimitExceptionOn429() {
        server.createContext("/v1/search", exchange -> {
            exchange.getResponseHeaders().add("Retry-After", "60");
            respondStatus(exchange, 429);
        });

        PexelsSearchRequest request = new PexelsSearchRequest("test", 5, 1);
        assertThrows(PexelsRateLimitException.class, () -> service.searchPhotos(request));
    }

    @Test
    void shouldThrowPexelsAuthExceptionOn401() {
        server.createContext("/v1/search", exchange -> respondStatus(exchange, 401));

        PexelsSearchRequest request = new PexelsSearchRequest("test", 5, 1);
        assertThrows(PexelsAuthException.class, () -> service.searchPhotos(request));
    }

    @Test
    void shouldThrowPexelsAuthExceptionOn403() {
        server.createContext("/v1/search", exchange -> respondStatus(exchange, 403));

        PexelsSearchRequest request = new PexelsSearchRequest("test", 5, 1);
        assertThrows(PexelsAuthException.class, () -> service.searchPhotos(request));
    }

    @Test
    void shouldThrowRuntimeExceptionOn500() {
        server.createContext("/v1/search", exchange -> respondStatus(exchange, 500));

        PexelsSearchRequest request = new PexelsSearchRequest("test", 5, 1);
        RuntimeException ex = assertThrows(RuntimeException.class, () -> service.searchPhotos(request));
        assertTrue(ex.getMessage().contains("500"));
    }

    @Test
    void shouldRespectSearchMaxResultsFromConfig() {
        server.createContext("/v1/search", exchange -> respondJson(exchange, VALID_SEARCH_RESPONSE));

        // Request perPage=80 but config searchMaxResults=5 → should be capped at 5
        PexelsSearchRequest request = new PexelsSearchRequest("nature", 80, 1);
        PexelsSearchResponse response = service.searchPhotos(request);

        assertEquals(5, response.perPage());
    }

    @Test
    void shouldHandleMissingJsonFieldsGracefully() {
        server.createContext("/v1/search", exchange -> respondJson(exchange, MINIMAL_PHOTO_RESPONSE));

        PexelsSearchRequest request = new PexelsSearchRequest("missing", 5, 1);
        PexelsSearchResponse response = service.searchPhotos(request);

        assertNotNull(response);
        assertEquals(1, response.totalResults());
        PexelsPhotoDTO photo = response.photos().get(0);
        assertEquals(1L, photo.id());
        assertEquals(100, photo.width());
        assertEquals(100, photo.height());
        assertEquals("", photo.alt());
        assertEquals("", photo.photographerUrl());
        assertNotNull(photo.src());
        assertEquals("", photo.src().original());
    }

    @Test
    void shouldUrlEncodeChineseQuery() {
        server.createContext("/v1/search", exchange -> {
            String query = exchange.getRequestURI().getRawQuery();

            // Verify the query is URL-encoded (not raw Chinese characters)
            assertNotNull(query);
            // "自然风光" URL-encoded
            assertTrue(query.contains("%"),
                    "Query should be URL-encoded, got: " + query);

            respondJson(exchange, VALID_SEARCH_RESPONSE);
        });

        PexelsSearchRequest request = new PexelsSearchRequest("自然风光", 5, 1);
        PexelsSearchResponse response = service.searchPhotos(request);

        assertNotNull(response);
    }

    @Test
    void shouldGetCuratedPhotos() {
        server.createContext("/v1/curated", exchange -> respondJson(exchange, VALID_SEARCH_RESPONSE));

        PexelsSearchResponse response = service.curatedPhotos(5, 1);

        assertNotNull(response);
        assertEquals(42, response.totalResults());
        assertEquals(1, response.photos().size());
        assertEquals("pexels", response.source());
    }

    @Test
    void shouldSetLatencyMsInResponse() {
        server.createContext("/v1/search", exchange -> {
            // Simulate a small delay
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            respondJson(exchange, VALID_SEARCH_RESPONSE);
        });

        PexelsSearchRequest request = new PexelsSearchRequest("test", 5, 1);
        PexelsSearchResponse response = service.searchPhotos(request);

        assertTrue(response.latencyMs() >= 50, "Latency should be at least 50ms, was " + response.latencyMs());
    }
}
