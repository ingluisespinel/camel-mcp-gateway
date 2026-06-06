package com.lespinel.camel.mcp.config;

import lombok.Data;

@Data
public class ServiceDefinition {
    private String name;
    private String baseUrl;
    private String openapiUrl;
    private String openapiPath;
}
