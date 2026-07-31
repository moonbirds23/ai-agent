package com.zzp.aiagent.integration.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzp.aiagent.domain.pexels.PexelsPhotoService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ImageRetrievalGatewayConditionTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(
                    LocalImageRetrievalGateway.class,
                    McpImageRetrievalGateway.class)
            .withBean(PexelsPhotoService.class, () -> mock(PexelsPhotoService.class))
            .withBean(McpToolInvoker.class, () -> mock(McpToolInvoker.class))
            .withBean(ObjectMapper.class, ObjectMapper::new);

    @Test
    void localModeRegistersOnlyLocalGateway() {
        contextRunner
                .withPropertyValues("app.integrations.image-retrieval.mode=local")
                .run(context -> {
                    assertThat(context).hasSingleBean(ImageRetrievalGateway.class);
                    assertThat(context).hasSingleBean(LocalImageRetrievalGateway.class);
                    assertThat(context).doesNotHaveBean(McpImageRetrievalGateway.class);
                });
    }

    @Test
    void mcpModeRegistersOnlyMcpGateway() {
        contextRunner
                .withPropertyValues("app.integrations.image-retrieval.mode=mcp")
                .run(context -> {
                    assertThat(context).hasSingleBean(ImageRetrievalGateway.class);
                    assertThat(context).hasSingleBean(McpImageRetrievalGateway.class);
                    assertThat(context).doesNotHaveBean(LocalImageRetrievalGateway.class);
                });
    }
}
