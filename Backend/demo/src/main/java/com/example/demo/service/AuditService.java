package com.example.demo.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Emits structured (JSON line) audit records for auth attempts and chat intents.
 * Records are routed to logs/audit.log via the dedicated "AUDIT" logger.
 */
@Service
public class AuditService {

    private static final Logger AUDIT = LoggerFactory.getLogger("AUDIT");

    private final ObjectMapper objectMapper;

    public AuditService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void authEvent(String outcome, String username, Long userId) {
        Map<String, Object> record = base("AUTH");
        record.put("outcome", outcome);
        record.put("username", username);
        record.put("userId", userId);
        write(record);
    }

    public void chatEvent(String intent, Long userId, long durationMs, boolean success, boolean masked, String traceId) {
        Map<String, Object> record = base("CHAT");
        record.put("userId", userId);
        record.put("intent", intent);
        record.put("durationMs", durationMs);
        record.put("success", success);
        record.put("masked", masked);
        record.put("traceId", traceId);
        write(record);
    }

    public void sqlEvent(Long userId, String statement, long durationMs, boolean success) {
        Map<String, Object> record = base("SQL");
        record.put("userId", userId);
        record.put("statement", statement);
        record.put("durationMs", durationMs);
        record.put("success", success);
        write(record);
    }

    private Map<String, Object> base(String type) {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("type", type);
        record.put("timestamp", Instant.now().toString());
        return record;
    }

    private void write(Map<String, Object> record) {
        try {
            AUDIT.info(objectMapper.writeValueAsString(record));
        } catch (Exception ex) {
            AUDIT.warn("Failed to serialize audit record: {}", ex.getMessage());
        }
    }
}
