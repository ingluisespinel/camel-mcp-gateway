package com.lespinel.camel.mcp.sse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
public class SseSessionManager {

    private static final Logger log = LoggerFactory.getLogger(SseSessionManager.class);

    private final ConcurrentMap<String, SseEmitter> sessions = new ConcurrentHashMap<>();

    public SseEmitter createSession(String sessionId) {
        SseEmitter emitter = new SseEmitter(0L);
        sessions.put(sessionId, emitter);
        log.debug("SSE session created: {}", sessionId);
        return emitter;
    }

    public SseEmitter getSession(String sessionId) {
        return sessions.get(sessionId);
    }

    public void removeSession(String sessionId) {
        SseEmitter emitter = sessions.remove(sessionId);
        if (emitter != null) {
            try {
                emitter.complete();
            } catch (Exception e) {
                // ignore
            }
            log.debug("SSE session removed: {}", sessionId);
        }
    }

    public int activeSessions() {
        return sessions.size();
    }
}
