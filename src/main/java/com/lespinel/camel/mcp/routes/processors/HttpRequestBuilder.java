package com.lespinel.camel.mcp.routes.processors;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lespinel.camel.mcp.model.ApiOperation;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.stream.Collectors;

@Component
public class HttpRequestBuilder implements Processor {

    private final ObjectMapper objectMapper;

    public HttpRequestBuilder(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void process(Exchange exchange) {
        ApiOperation operation = exchange.getIn().getHeader("tool-operation", ApiOperation.class);
        String baseUrl = exchange.getIn().getHeader("tool-base-url", String.class);
        Map<String, Object> arguments = exchange.getIn().getHeader("tool-arguments", Map.class);
        if (arguments == null) arguments = Map.of();

        String resolvedPath = resolvePath(operation.getPath(), arguments, operation);
        String queryString = buildQueryString(arguments, operation);

        StringBuilder url = new StringBuilder(baseUrl);
        if (resolvedPath != null && !resolvedPath.isEmpty()) {
            url.append(resolvedPath);
        }
        if (!queryString.isEmpty()) {
            url.append("?").append(queryString);
        }

        exchange.getIn().setHeader(Exchange.HTTP_METHOD, operation.getHttpMethod());
        exchange.getIn().setHeader(Exchange.HTTP_URI, url.toString());

        if (operation.isHasRequestBody() && arguments.get("body") != null) {
            try {
                Object body = arguments.get("body");
                String json = body instanceof String s ? s : objectMapper.writeValueAsString(body);
                exchange.getIn().setBody(json);
                exchange.getIn().setHeader(Exchange.CONTENT_TYPE, "application/json");
            } catch (Exception e) {
                throw new RuntimeException("Failed to serialize request body", e);
            }
        }
    }

    private String resolvePath(String pathTemplate, Map<String, Object> arguments, ApiOperation operation) {
        String resolved = pathTemplate;
        for (var param : operation.getParameters().stream()
                .filter(p -> "path".equals(p.getIn())).collect(Collectors.toList())) {
            Object value = arguments.get(param.getName());
            if (value != null) {
                resolved = resolved.replace("{" + param.getName() + "}", value.toString());
            }
        }
        return resolved;
    }

    private String buildQueryString(Map<String, Object> arguments, ApiOperation operation) {
        var queryParams = operation.getParameters().stream()
                .filter(p -> "query".equals(p.getIn())).collect(Collectors.toList());
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
