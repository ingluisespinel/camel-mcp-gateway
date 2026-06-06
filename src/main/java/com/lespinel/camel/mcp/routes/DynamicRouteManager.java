package com.lespinel.camel.mcp.routes;

import com.lespinel.camel.mcp.model.ApiOperation;
import com.lespinel.camel.mcp.registry.ToolRegistry;
import jakarta.annotation.PostConstruct;
import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class DynamicRouteManager {

    private static final Logger log = LoggerFactory.getLogger(DynamicRouteManager.class);

    private final CamelContext camelContext;
    private final ToolRegistry toolRegistry;

    public DynamicRouteManager(CamelContext camelContext, ToolRegistry toolRegistry) {
        this.camelContext = camelContext;
        this.toolRegistry = toolRegistry;
    }

    @PostConstruct
    void init() {
        log.info("DynamicRouteManager initialized. Registered tools will have routes created dynamically.");
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
                            .process(exchange -> {
                                String path = operation.getPath();
                                Map<String, Object> arguments = exchange.getIn().getHeader("tool-arguments", Map.class);
                                if (arguments == null) {
                                    arguments = Map.of();
                                }

                                String resolvedPath = resolvePath(path, arguments, operation);
                                exchange.getIn().setHeader(Exchange.HTTP_METHOD, operation.getHttpMethod());

                                String queryString = buildQueryString(arguments, operation);
                                StringBuilder fullUrl = new StringBuilder(baseUrl);
                                if (resolvedPath != null && !resolvedPath.isEmpty()) {
                                    fullUrl.append(resolvedPath);
                                }
                                if (!queryString.isEmpty()) {
                                    fullUrl.append("?").append(queryString);
                                }
                                exchange.getIn().setHeader("fullUrl", fullUrl.toString());

                                if (operation.isHasRequestBody() && arguments.get("body") != null) {
                                    Object body = arguments.get("body");
                                    if (body instanceof String s) {
                                        exchange.getIn().setBody(s);
                                    } else {
                                        exchange.getIn().setBody(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(body));
                                    }
                                    exchange.getIn().setHeader(Exchange.CONTENT_TYPE, "application/json");
                                }
                            })
                            .toD("${header.fullUrl}")
                            .convertBodyTo(String.class)
                            .process(exchange -> {
                                String responseBody = exchange.getIn().getBody(String.class);
                                int responseCode = exchange.getIn().getHeader(Exchange.HTTP_RESPONSE_CODE, 200, Integer.class);
                                if (responseCode >= 400) {
                                    throw new RuntimeException("Backend returned HTTP " + responseCode + ": " + responseBody);
                                }
                            });
                }
            });
            log.info("Created dynamic route {} -> {}", routeId, baseUrl + operation.getPath());
        } catch (Exception e) {
            log.error("Failed to create route {}: {}", routeId, e.getMessage());
        }
    }

    String resolvePath(String pathTemplate, Map<String, Object> arguments, ApiOperation operation) {
        String resolved = pathTemplate;

        List<ApiOperation.ParameterInfo> pathParams = operation.getParameters().stream()
                .filter(p -> "path".equals(p.getIn()))
                .collect(Collectors.toList());

        for (var param : pathParams) {
            Object value = arguments.get(param.getName());
            if (value != null) {
                resolved = resolved.replace("{" + param.getName() + "}", value.toString());
            }
        }

        return resolved;
    }

    String buildQueryString(Map<String, Object> arguments, ApiOperation operation) {
        List<ApiOperation.ParameterInfo> queryParams = operation.getParameters().stream()
                .filter(p -> "query".equals(p.getIn()))
                .collect(Collectors.toList());

        StringBuilder qs = new StringBuilder();
        for (var param : queryParams) {
            Object value = arguments.get(param.getName());
            if (value != null) {
                if (qs.length() > 0) qs.append("&");
                qs.append(param.getName()).append("=").append(value.toString());
            }
        }
        return qs.toString();
    }
}
