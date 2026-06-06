package com.lespinel.camel.mcp.registry;

import com.lespinel.camel.mcp.model.ApiOperation;
import com.lespinel.camel.mcp.model.mcp.ToolDefinition;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Component
public class ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

    private final Map<String, ToolEntry> tools = new ConcurrentHashMap<>();

    @PostConstruct
    void init() {
        log.info("ToolRegistry initialized");
    }

    public void registerOperation(ApiOperation operation) {
        String toolName = buildToolName(operation);
        ToolDefinition.InputSchema inputSchema = buildInputSchema(operation);

        ToolDefinition definition = ToolDefinition.builder()
                .name(toolName)
                .description(buildDescription(operation))
                .inputSchema(inputSchema)
                .build();

        tools.put(toolName, new ToolEntry(definition, operation));
        log.debug("Registered tool: {} ({} {})", toolName, operation.getHttpMethod(), operation.getPath());
    }

    public ToolEntry getTool(String name) {
        return tools.get(name);
    }

    public List<ToolDefinition> listAllTools() {
        return tools.values().stream()
                .map(entry -> entry.definition())
                .collect(Collectors.toList());
    }

    public int count() {
        return tools.size();
    }

    private String buildToolName(ApiOperation op) {
        return op.getServiceName() + "_" + op.getOperationId();
    }

    private String buildDescription(ApiOperation op) {
        StringBuilder sb = new StringBuilder();
        if (op.getSummary() != null && !op.getSummary().isBlank()) {
            sb.append(op.getSummary());
        }
        if (op.getDescription() != null && !op.getDescription().isBlank()) {
            if (sb.length() > 0) sb.append(" - ");
            sb.append(op.getDescription());
        }
        sb.append(" [").append(op.getHttpMethod()).append(" ").append(op.getPath()).append("]");
        return sb.toString();
    }

    private ToolDefinition.InputSchema buildInputSchema(ApiOperation op) {
        Map<String, ToolDefinition.PropertySchema> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();

        for (var param : op.getParameters()) {
            String jsonType = mapType(param.getType());
            properties.put(param.getName(), ToolDefinition.PropertySchema.builder()
                    .type(jsonType)
                    .description(param.getDescription() != null ? param.getDescription() : param.getName())
                    .build());
            if (param.isRequired()) {
                required.add(param.getName());
            }
        }

        if (op.isHasRequestBody()) {
            String bodyField = op.getHttpMethod().equalsIgnoreCase("GET") ? "body" : "body";
            properties.put(bodyField, ToolDefinition.PropertySchema.builder()
                    .type("object")
                    .description("Request body")
                    .build());
        }

        return ToolDefinition.InputSchema.builder()
                .type("object")
                .properties(properties)
                .required(required.isEmpty() ? null : required)
                .build();
    }

    private String mapType(String openApiType) {
        if (openApiType == null) return "string";
        switch (openApiType.toLowerCase()) {
            case "integer":
            case "number":
                return openApiType.toLowerCase();
            case "boolean":
                return "boolean";
            case "array":
                return "array";
            default:
                return "string";
        }
    }

    public record ToolEntry(ToolDefinition definition, ApiOperation operation) {}
}
