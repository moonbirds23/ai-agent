package com.zzp.aiagent.integration.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class McpImageRetrievalGatewayTest {

    private final McpToolInvoker invoker = mock(McpToolInvoker.class);
    private final McpImageRetrievalGateway gateway =
            new McpImageRetrievalGateway(invoker, new ObjectMapper());

    @Test
    void unwrapsJsonStringToolResultBeforeReadingPhotos() throws Exception {
        String applicationJson = """
                {"photos":[{"id":42,"url":"https://example.test/photo"}]}
                """;
        String transportJson = new ObjectMapper().writeValueAsString(applicationJson);
        when(invoker.callTool(eq(McpToolNames.PEXELS_SEARCH), anyMap()))
                .thenReturn(transportJson);

        List<Map<String, Object>> photos = gateway.searchPexels("cats", 5, 1);

        assertThat(photos).singleElement().satisfies(photo ->
                assertThat(photo)
                        .containsEntry("id", 42)
                        .containsEntry("url", "https://example.test/photo"));
    }

    @Test
    void keepsPlainJsonToolResultCompatible() {
        when(invoker.callTool(eq(McpToolNames.PEXELS_SEARCH), anyMap()))
                .thenReturn("{\"photos\":[{\"id\":7}]}");

        assertThat(gateway.searchPexels("cats", 5, 1))
                .singleElement()
                .satisfies(photo -> assertThat(photo).containsEntry("id", 7));
    }
}
