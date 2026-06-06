package com.lespinel.camel.mcp.model.mcp;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class McpRequest {
    @JsonProperty("jsonrpc")
    private String jsonrpc = "2.0";

    private String method;

    private Map<String, Object> params;

    private Object id;

    @JsonProperty("jsonrpc")
    public String getJsonrpc() { return jsonrpc; }
}
