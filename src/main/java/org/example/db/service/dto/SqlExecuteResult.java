package org.example.db.service.dto;

import org.example.db.model.TableSchema;
import org.example.db.sql.SqlStatementInfo;
import org.example.db.sql.TableRef;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * SQL 执行结果（用于 query 或 confirm-write）。
 * <p>
 * 说明：
 * <ul>
 *   <li>如果 SQL 返回 ResultSet（例如 SELECT，或 INSERT ... RETURNING），rows 会有值。</li>
 *   <li>如果 SQL 不返回 ResultSet（例如 UPDATE/DELETE/DDL），updateCount 会有值（可能为 0 或 -1）。</li>
 * </ul>
 */
public record SqlExecuteResult(
        String dataSourceId,
        String sql,
        SqlStatementInfo statementInfo,
        List<TableRef> referencedTables,
        List<TableSchema> referencedTableSchemas,
        Integer updateCount,
        List<Map<String, Object>> rows,
        boolean limited,
        Instant executedAt,
        List<String> warnings,
        String message,
        List<QueryColumn> columns
) {
}
