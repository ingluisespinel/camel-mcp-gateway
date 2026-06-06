package com.lespinel.camel.mcp.sse;

import com.lespinel.camel.mcp.model.mcp.McpRequest;
import com.lespinel.camel.mcp.model.mcp.McpResponse;
import com.lespinel.camel.mcp.registry.ToolRegistry;
import com.lespinel.camel.mcp.routes.processors.ToolsCallProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;

@RestController
public class SseController {

    private static final Logger log = LoggerFactory.getLogger(SseController.class);

    private final SseSessionManager sessionManager;
    private final ToolRegistry toolRegistry;
    private final ToolsCallProcessor toolsCallProcessor;

    public SseController(SseSessionManager sessionManager,
                         ToolRegistry toolRegistry,
                         ToolsCallProcessor toolsCallProcessor) {
        this.sessionManager = sessionManager;
        this.toolRegistry = toolRegistry;
        this.toolsCallProcessor = toolsCallProcessor;
    }

    @GetMapping(value = "/mcp/sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter handleSse() {
        String sessionId = UUID.randomUUID().toString();
        log.info("New SSE connection established: session={}", sessionId);

        SseEmitter emitter = sessionManager.createSession(sessionId);

        try {
            emitter.send(SseEmitter.event()
                    .name("endpoint")
                    .data("/mcp/message?sessionId=" + sessionId));
        } catch (IOException e) {
            log.error("Failed to send endpoint event for session {}: {}", sessionId, e.getMessage());
            sessionManager.removeSession(sessionId);
            return emitter;
        }

        emitter.onCompletion(() -> {
            log.info("SSE connection completed: session={}", sessionId);
            sessionManager.removeSession(sessionId);
        });
        emitter.onTimeout(() -> {
            log.info("SSE connection timed out: session={}", sessionId);
            sessionManager.removeSession(sessionId);
        });
        emitter.onError(e -> {
            log.warn("SSE connection error for session {}: {}", sessionId, e.getMessage());
            sessionManager.removeSession(sessionId);
        });

        return emitter;
    }

    @PostMapping("/mcp/message")
    public void handleMessage(@RequestParam String sessionId, @RequestBody Map<String, Object> body) {
        McpRequest request = new McpRequest();
        request.setJsonrpc((String) body.getOrDefault("jsonrpc", "2.0"));
        request.setMethod((String) body.get("method"));
        request.setId(body.get("id"));

        @SuppressWarnings("unchecked")
        Map<String, Object> params = (Map<String, Object>) body.get("params");
        request.setParams(params);

        log.debug("Received message via SSE transport: method={}, session={}", request.getMethod(), sessionId);

        McpResponse response = handleMcpMethod(request);

        SseEmitter emitter = sessionManager.getSession(sessionId);
        if (emitter == null) {
            log.warn("No SSE session found for sessionId: {}", sessionId);
            return;
        }

        try {
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            String json = mapper.writeValueAsString(response);
            emitter.send(SseEmitter.event()
                    .name("message")
                    .data(json));
        } catch (IOException e) {
            log.error("Failed to send response via SSE for session {}: {}", sessionId, e.getMessage());
            sessionManager.removeSession(sessionId);
        }
    }

    McpResponse handleMcpMethod(McpRequest request) {
        String method = request.getMethod();
        Object id = request.getId();

        if (method == null) {
            return McpResponse.error(id, -32600, "Invalid Request: method is required", null);
        }

        return switch (method) {
            case "tools/list" -> McpResponse.success(id,
                    Map.of("tools", toolRegistry.listAllTools()));
            case "tools/call" -> toolsCallProcessor.process(request);
            default -> McpResponse.error(id, -32601, "Method not found: " + method, null);
        };
    }
}
