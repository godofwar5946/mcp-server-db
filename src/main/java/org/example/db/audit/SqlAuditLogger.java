package org.example.db.audit;

import com.alibaba.fastjson2.JSON;
import org.example.db.sql.SqlCategory;
import org.example.db.sql.SqlStatementInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * SQL 审计日志记录器：把“执行过/尝试过”的 SQL 以结构化方式落盘，方便后续追溯。
 * <p>
 * 默认做法：
 * <ul>
 *   <li>每条记录输出为一行 JSON（便于 ELK/grep/脚本处理）</li>
 *   <li>日志输出到独立 logger：SQL_AUDIT（由 logback-spring.xml 绑定到单独文件）</li>
 * </ul>
 */
@Component
public class SqlAuditLogger {

    /**
     * 使用独立 logger，避免与业务日志混杂。
     */
    private static final Logger AUDIT = LoggerFactory.getLogger("SQL_AUDIT");

    public void logSuccess(
            SqlAuditAction action,
            String dataSourceId,
            String token,
            String sql,
            SqlStatementInfo statementInfo,
            Integer maxRows,
            Integer returnedRows,
            Integer updateCount,
            Boolean limited,
            Long durationMs,
            List<String> warnings
    ) {
        SqlAuditLogEntry entry = new SqlAuditLogEntry(
                UUID.randomUUID().toString(),
                Instant.now(),
                action,
                dataSourceId,
                token,
                sql,
                statementInfo,
                statementInfo == null ? SqlCategory.OTHER : statementInfo.category(),
                maxRows,
                returnedRows,
                updateCount,
                limited,
                durationMs,
                true,
                null,
                warnings
        );
        AUDIT.info(JSON.toJSONString(entry));
    }

    public void logFailure(
            SqlAuditAction action,
            String dataSourceId,
            String token,
            String sql,
            SqlStatementInfo statementInfo,
            Integer maxRows,
            Long durationMs,
            Exception exception,
            List<String> warnings
    ) {
        String err = exception == null ? null : exception.getMessage();
        SqlAuditLogEntry entry = new SqlAuditLogEntry(
                UUID.randomUUID().toString(),
                Instant.now(),
                action,
                dataSourceId,
                token,
                sql,
                statementInfo,
                statementInfo == null ? SqlCategory.OTHER : statementInfo.category(),
                maxRows,
                null,
                null,
                null,
                durationMs,
                false,
                err,
                warnings
        );
        // 仅把 message 放进 JSON，异常堆栈仍然单独打印，便于定位
        AUDIT.error(JSON.toJSONString(entry), exception);
    }
}

