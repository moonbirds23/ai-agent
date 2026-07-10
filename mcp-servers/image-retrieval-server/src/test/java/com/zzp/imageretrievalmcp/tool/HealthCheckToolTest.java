package com.zzp.imageretrievalmcp.tool;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit test for HealthCheckTool — verifies JSON response structure.
 */
class HealthCheckToolTest {

    private final HealthCheckTool tool = new HealthCheckTool();

    @Test
    void shouldReturnUpStatusJson() {
        String result = tool.healthCheck();

        assertNotNull(result);
        assertTrue(result.contains("\"status\":\"UP\""));
        assertTrue(result.contains("\"server\":\"image-retrieval-mcp-server\""));
        assertTrue(result.contains("\"version\":\"1.0.0\""));
    }
}
