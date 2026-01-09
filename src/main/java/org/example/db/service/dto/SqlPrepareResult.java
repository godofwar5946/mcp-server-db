package org.example.db.service.dto;

import org.example.db.model.TableSchema;
import org.example.db.sql.SqlStatementInfo;
import org.example.db.sql.TableRef;

import java.time.Instant;
import java.util.List;

/**
 * 写入 SQL 的“预执行/待确认”结果。
 * <p>
 * token 是后续 confirm 的凭证；如果超时失效，需要重新 prepare。
 */
public record SqlPrepareResult(
        String dataSourceId,
        String token,
        String sql,
        SqlStatementInfo statementInfo,
        Instant expiresAt,
        List<TableRef> referencedTables,
        List<TableSchema> referencedTableSchemas,
        List<String> warnings
) {
}
