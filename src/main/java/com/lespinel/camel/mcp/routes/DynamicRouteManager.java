package com.lespinel.camel.mcp.routes;

import com.lespinel.camel.mcp.model.ApiOperation;
import com.lespinel.camel.mcp.registry.ToolRegistry;
import com.lespinel.camel.mcp.routes.processors.HttpRequestBuilder;
import com.lespinel.camel.mcp.routes.processors.HttpResponseValidator;
import jakarta.annotation.PostConstruct;
import org.apache.camel.CamelContext;
import org.apache.camel.builder.RouteBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class DynamicRouteManager {

    private static final Logger log = LoggerFactory.getLogger(DynamicRouteManager.class);

    private final CamelContext camelContext;
    private final ToolRegistry toolRegistry;
    private final HttpRequestBuilder httpRequestBuilder;
    private final HttpResponseValidator httpResponseValidator;

    public DynamicRouteManager(CamelContext camelContext,
                               ToolRegistry toolRegistry,
                               HttpRequestBuilder httpRequestBuilder,
                               HttpResponseValidator httpResponseValidator) {
        this.camelContext = camelContext;
        this.toolRegistry = toolRegistry;
        this.httpRequestBuilder = httpRequestBuilder;
        this.httpResponseValidator = httpResponseValidator;
    }

    @PostConstruct
    void init() {
        log.info("DynamicRouteManager initialized.");
    }

    public void createRoutesForService(List<ApiOperation> operations, String baseUrl) {
        for (ApiOperation op : operations) {
            createRoute(op, baseUrl);
        }
    }

    void createRoute(ApiOperation operation, String baseUrl) {
        String toolName = operation.getServiceName() + "_" + operation.getOperationId();
        String routeId = "mcp-tool:" + toolName;
        String directUri = "direct:" + routeId;

        try {
            camelContext.addRoutes(new RouteBuilder() {
                @Override
                public void configure() {
                    from(directUri)
                            .routeId(routeId)
                            .setHeader("tool-operation", constant(operation))
                            .setHeader("tool-base-url", constant(baseUrl))
                            .process(httpRequestBuilder)
                            .toD("${header.CamelHttpUri}")
                            .convertBodyTo(String.class)
                            .process(httpResponseValidator);
                }
            });
            log.info("Created route {} -> {}", routeId, baseUrl + operation.getPath());
        } catch (Exception e) {
            log.error("Failed to create route {}: {}", routeId, e.getMessage());
        }
    }
}
