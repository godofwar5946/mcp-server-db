package org.example.db.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.db.config.DbExplorerProperties;
import org.example.db.service.PendingSqlStore;
import org.example.security.Caller;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.*;

@Component
public class SqlAuditLogger {
    private static final Logger AUDIT = LoggerFactory.getLogger("SQL_AUDIT");
    private final ObjectMapper mapper;
    private final DbExplorerProperties properties;
    public SqlAuditLogger(ObjectMapper mapper, DbExplorerProperties properties) {
        this.mapper = mapper;
        this.properties = properties;
    }

    public void log(SqlAuditAction action, Caller caller, String dataSourceId, String token, String sql,
                    long durationMs, Exception failure, Map<String, Object> outcome) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("eventId", UUID.randomUUID().toString());
        entry.put("timestamp", Instant.now());
        entry.put("action", action);
        entry.put("subject", caller.subject());
        entry.put("sessionId", caller.sessionId());
        entry.put("dataSourceId", dataSourceId);
        entry.put("tokenHash", token == null ? null : PendingSqlStore.hash(token));
        entry.put("sqlHash", sql == null ? null : PendingSqlStore.hash(sql));
        if (properties.isAuditIncludeSql()) entry.put("sql", sql);
        entry.put("durationMs", durationMs);
        entry.put("success", failure == null);
        entry.put("errorType", failure == null ? null : failure.getClass().getSimpleName());
        entry.put("outcome", outcome);
        try {
            AUDIT.info(mapper.writeValueAsString(entry));
        } catch (JsonProcessingException e) {
            // Audit encoding must not turn a committed write into a reported execution failure.
            AUDIT.error("{\"error\":\"audit_encoding_failed\"}");
        }
    }
}
