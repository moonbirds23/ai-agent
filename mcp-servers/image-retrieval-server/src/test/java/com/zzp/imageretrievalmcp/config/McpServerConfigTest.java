package com.zzp.imageretrievalmcp.config;

import com.zzp.imageretrievalmcp.ImageRetrievalMcpApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Integration test verifying Spring context loads successfully
 * with the test profile (no DB/Redis/MCP server auto-config).
 */
@SpringBootTest(classes = ImageRetrievalMcpApplication.class)
class McpServerConfigTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void shouldLoadApplicationContext() {
        assertNotNull(context);
    }

    @Test
    void shouldHaveMcpServerConfigBean() {
        assertNotNull(context.getBean(McpServerConfig.class));
    }

    @Test
    void shouldHaveHealthCheckToolBean() {
        Object bean = context.getBean("healthCheckTool");
        assertNotNull(bean);
    }
}
