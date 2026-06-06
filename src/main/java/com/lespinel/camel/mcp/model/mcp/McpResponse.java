package com.lespinel.camel.mcp.model.mcp;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class McpResponse {

    @JsonProperty("jsonrpc")
    private String jsonrpc = "2.0";

    private Object id;

    private Object result;

    private McpError error;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class McpError {
        private int code;
        private String message;
        private Object data;
    }

    public static McpResponse success(Object id, Object result) {
        return new McpResponse("2.0", id, result, null);
    }

    public static McpResponse error(Object id, int code, String message, Object data) {
        return new McpResponse("2.0", id, null, new McpError(code, message, data));
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ToolListResult {
        private List<ToolDefinition> tools;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ToolCallResult {
        private List<ContentItem> content;
        @JsonInclude(JsonInclude.Include.NON_NULL)
        private Boolean isError;

        public ToolCallResult(List<ContentItem> content) {
            this.content = content;
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ContentItem {
        private String type;
        private String text;
        private Map<String, Object> resource;
    }
}
