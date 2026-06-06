package com.lespinel.camel.mcp.routes;

import com.lespinel.camel.mcp.model.mcp.McpRequest;
import com.lespinel.camel.mcp.model.mcp.McpResponse;
import com.lespinel.camel.mcp.routes.processors.ToolsListProcessor;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.model.dataformat.JsonLibrary;
import org.springframework.stereotype.Component;

@Component
public class McpEndpointRoute extends RouteBuilder {

    private final ToolsListProcessor toolsListProcessor;

    public McpEndpointRoute(ToolsListProcessor toolsListProcessor) {
        this.toolsListProcessor = toolsListProcessor;
    }

    @Override
    public void configure() {
        restConfiguration()
                .component("servlet");

        rest("/mcp")
                .description("MCP JSON-RPC endpoint")
                .post()
                .consumes("application/json")
                .produces("application/json")
                .to("direct:mcpRouter");

        from("direct:mcpRouter")
                .routeId("mcp-router")
                .unmarshal().json(JsonLibrary.Jackson, McpRequest.class)
                .process(exchange -> {
                    McpRequest request = exchange.getIn().getBody(McpRequest.class);
                    if (request != null && request.getMethod() != null) {
                        exchange.getIn().setHeader("method", request.getMethod());
                    }
                })
                .choice()
                    .when(header("method").isEqualTo("tools/list"))
                        .process(toolsListProcessor)
                        .marshal().json(JsonLibrary.Jackson)
                    .endChoice()
                    .when(header("method").isEqualTo("tools/call"))
                        .to("direct:mcpToolsCall")
                    .endChoice()
                    .otherwise()
                        .process(exchange -> {
                            McpRequest request = exchange.getIn().getBody(McpRequest.class);
                            Object id = request != null ? request.getId() : null;
                            String method = request != null ? request.getMethod() : null;
                            exchange.getIn().setBody(
                                    McpResponse.error(id, -32601,
                                            "Method not found: " + method, null)
                            );
                        })
                        .marshal().json(JsonLibrary.Jackson)
                .end();

        from("direct:mcpToolsCall")
                .routeId("mcp-tools-call")
                .bean("toolsCallProcessor", "process")
                .marshal().json(JsonLibrary.Jackson);
    }
}
