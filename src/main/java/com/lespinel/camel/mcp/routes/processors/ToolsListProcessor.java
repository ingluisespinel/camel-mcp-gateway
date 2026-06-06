package com.lespinel.camel.mcp.routes.processors;

import com.lespinel.camel.mcp.model.mcp.McpRequest;
import com.lespinel.camel.mcp.registry.ToolRegistry;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.springframework.stereotype.Component;

@Component
public class ToolsListProcessor implements Processor {

    private final ToolRegistry toolRegistry;

    public ToolsListProcessor(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    @Override
    public void process(Exchange exchange) {
        McpRequest request = exchange.getIn().getBody(McpRequest.class);

        var toolList = toolRegistry.listAllTools();
        var result = new java.util.HashMap<String, Object>();
        result.put("tools", toolList);

        exchange.getIn().setBody(
                com.lespinel.camel.mcp.model.mcp.McpResponse.success(request.getId(), result)
        );
    }
}
