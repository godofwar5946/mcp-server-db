package org.example.db.audit;

import org.example.db.sql.SqlCategory;
import org.example.db.sql.SqlStatementInfo;

import java.time.Instant;
import java.util.List;

/**
 * SQL 审计日志条目（建议按 JSON 行格式落盘，便于检索与追溯）。
 * <p>
 * 注意：
 * <ul>
 *   <li>此处会记录完整 SQL（可能包含敏感数据），请结合实际合规要求控制日志权限与留存策略</li>
 *   <li>如果希望脱敏（例如 INSERT 的 values），可以在 {@link SqlAuditLogger} 中做二次处理</li>
 * </ul>
 */
public record SqlAuditLogEntry(
        String eventId,
        Instant timestamp,
        SqlAuditAction action,
        String dataSourceId,
        String token,
        String sql,
        SqlStatementInfo statementInfo,
        SqlCategory category,
        Integer maxRows,
        Integer returnedRows,
        Integer updateCount,
        Boolean limited,
        Long durationMs,
        boolean success,
        String errorMessage,
        List<String> warnings
) {
}

