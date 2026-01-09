package org.example.db.service.dto;

import org.example.db.model.TableSchema;
import org.example.db.sql.SqlStatementInfo;
import org.example.db.sql.TableRef;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * SQL 查询结果（只读）。
 */
public record SqlQueryResult(
        String dataSourceId,
        String sql,
        SqlStatementInfo statementInfo,
        List<TableRef> referencedTables,
        List<TableSchema> referencedTableSchemas,
        List<Map<String, Object>> rows,
        boolean limited,
        int returnedRows,
        Instant executedAt,
        List<String> warnings
) {
}
