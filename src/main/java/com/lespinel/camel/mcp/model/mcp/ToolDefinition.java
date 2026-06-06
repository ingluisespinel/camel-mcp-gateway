package com.lespinel.camel.mcp.model.mcp;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ToolDefinition {
    private String name;
    private String description;
    private InputSchema inputSchema;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class InputSchema {
        private String type;
        private Map<String, PropertySchema> properties;
        private List<String> required;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PropertySchema {
        private String type;
        private String description;
        private Object defaultValue;

        @JsonInclude(JsonInclude.Include.NON_NULL)
        private Map<String, PropertySchema> properties;

        @JsonInclude(JsonInclude.Include.NON_NULL)
        private List<String> required;
    }
}
