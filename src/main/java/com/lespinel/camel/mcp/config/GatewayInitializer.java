package com.lespinel.camel.mcp.config;

import com.lespinel.camel.mcp.model.ApiOperation;
import com.lespinel.camel.mcp.openapi.OpenApiSpecLoader;
import com.lespinel.camel.mcp.registry.ToolRegistry;
import com.lespinel.camel.mcp.routes.DynamicRouteManager;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class GatewayInitializer {

    private static final Logger log = LoggerFactory.getLogger(GatewayInitializer.class);

    private final McpGatewayConfig config;
    private final OpenApiSpecLoader specLoader;
    private final ToolRegistry toolRegistry;
    private final DynamicRouteManager dynamicRouteManager;

    public GatewayInitializer(McpGatewayConfig config,
                              OpenApiSpecLoader specLoader,
                              ToolRegistry toolRegistry,
                              DynamicRouteManager dynamicRouteManager) {
        this.config = config;
        this.specLoader = specLoader;
        this.toolRegistry = toolRegistry;
        this.dynamicRouteManager = dynamicRouteManager;
    }

    @PostConstruct
    public void initialize() {
        log.info("========================================");
        log.info("Initializing Camel MCP Gateway...");
        log.info("========================================");

        List<com.lespinel.camel.mcp.config.ServiceDefinition> services = config.getServices();
        if (services == null || services.isEmpty()) {
            log.warn("No services configured in app.mcp-gateway.services");
            return;
        }

        int totalOperations = 0;
        int totalErrors = 0;

        for (var service : services) {
            log.info("Loading service: {} (base-url: {})", service.getName(), service.getBaseUrl());
            log.info("  OpenAPI source: {}", describeSource(service));

            try {
                List<ApiOperation> operations = specLoader.load(service);

                if (operations.isEmpty()) {
                    log.warn("  No operations found for service: {}", service.getName());
                    totalErrors++;
                    continue;
                }

                for (var op : operations) {
                    toolRegistry.registerOperation(op);
                }

                dynamicRouteManager.createRoutesForService(operations, service.getBaseUrl());

                totalOperations += operations.size();
                log.info("  Registered {} operations", operations.size());

            } catch (Exception e) {
                log.error("  Failed to initialize service '{}': {}", service.getName(), e.getMessage());
                totalErrors++;
            }
        }

        log.info("========================================");
        log.info("Initialization complete:");
        log.info("  Services configured: {}", services.size());
        log.info("  Operations registered: {}", totalOperations);
        log.info("  Errors: {}", totalErrors);
        log.info("  MCP endpoint: POST /mcp (JSON-RPC)");
        log.info("  MCP endpoint: GET /mcp/sse + POST /mcp/message (SSE Transport)");
        log.info("========================================");
    }

    private String describeSource(com.lespinel.camel.mcp.config.ServiceDefinition service) {
        if (service.getOpenapiUrl() != null && !service.getOpenapiUrl().isBlank()) {
            return "URL: " + service.getOpenapiUrl();
        }
        if (service.getOpenapiPath() != null && !service.getOpenapiPath().isBlank()) {
            return "Path: " + service.getOpenapiPath();
        }
        return "NOT CONFIGURED";
    }
}
