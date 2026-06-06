package com.lespinel.camel.mcp.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApiOperation {
    private String serviceName;
    private String operationId;
    private String httpMethod;
    private String path;
    private String summary;
    private String description;
    private List<ParameterInfo> parameters;
    private boolean hasRequestBody;
    private Object requestBodySchema;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ParameterInfo {
        private String name;
        private String in;
        private boolean required;
        private String type;
        private String description;
    }
}
