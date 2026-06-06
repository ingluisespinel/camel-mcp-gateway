package com.lespinel.camel.mcp.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

@Data
@Component
@ConfigurationProperties(prefix = "app.mcp-gateway")
public class McpGatewayConfig {
    private List<ServiceDefinition> services;
    private McpConfig mcp;

    @Data
    public static class McpConfig {
        private String endpoint = "/mcp";
    }
}
