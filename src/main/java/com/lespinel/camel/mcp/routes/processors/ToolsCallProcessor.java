package com.lespinel.camel.mcp.routes.processors;

import com.lespinel.camel.mcp.model.mcp.McpRequest;
import com.lespinel.camel.mcp.model.mcp.McpResponse;
import com.lespinel.camel.mcp.registry.ToolRegistry;
import org.apache.camel.Exchange;
import org.apache.camel.ProducerTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class ToolsCallProcessor {

    private static final Logger log = LoggerFactory.getLogger(ToolsCallProcessor.class);

    private final ToolRegistry toolRegistry;
    private final ProducerTemplate producerTemplate;

    public ToolsCallProcessor(ToolRegistry toolRegistry, ProducerTemplate producerTemplate) {
        this.toolRegistry = toolRegistry;
        this.producerTemplate = producerTemplate;
    }

    @SuppressWarnings("unchecked")
    public McpResponse process(McpRequest request) {
        Object id = request.getId();
        Map<String, Object> params = request.getParams();

        if (params == null) {
            return McpResponse.error(id, -32602, "Invalid params: params is required", null);
        }

        String toolName = (String) params.get("name");
        if (toolName == null || toolName.isBlank()) {
            return McpResponse.error(id, -32602, "Invalid params: tool name is required", null);
        }

        ToolRegistry.ToolEntry entry = toolRegistry.getTool(toolName);
        if (entry == null) {
            return McpResponse.error(id, -32604, "Tool not found: " + toolName, null);
        }

        Map<String, Object> arguments = (Map<String, Object>) params.get("arguments");
        if (arguments == null) {
            arguments = Map.of();
        }

        try {
            String resultBody = executeToolCall(entry, arguments);

            McpResponse.ContentItem contentItem = new McpResponse.ContentItem("text", resultBody, null);
            McpResponse.ToolCallResult result = new McpResponse.ToolCallResult(List.of(contentItem));

            return McpResponse.success(id, result);

        } catch (Exception e) {
            log.error("Error executing tool {}: {}", toolName, e.getMessage());
            McpResponse.ContentItem contentItem = new McpResponse.ContentItem("text",
                    "Error: " + e.getMessage(), null);
            McpResponse.ToolCallResult result = new McpResponse.ToolCallResult(List.of(contentItem));
            result.setIsError(true);
            return McpResponse.success(id, result);
        }
    }

    @SuppressWarnings("unchecked")
    String executeToolCall(ToolRegistry.ToolEntry entry, Map<String, Object> arguments) {
        var operation = entry.operation();
        String routeId = "mcp-tool:" + entry.definition().getName();

        var headers = new java.util.LinkedHashMap<String, Object>();
        headers.put("CamelHttpMethod", operation.getHttpMethod());
        headers.put("tool-operation-path", operation.getPath());
        headers.put("tool-arguments", arguments);
        headers.put("tool-service-name", operation.getServiceName());

        Object result = producerTemplate.requestBodyAndHeaders("direct:" + routeId, arguments, headers);

        if (result == null) {
            return "null";
        }
        if (result instanceof String) {
            return (String) result;
        }
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writerWithDefaultPrettyPrinter()
                    .writeValueAsString(result);
        } catch (Exception e) {
            return result.toString();
        }
    }
}
