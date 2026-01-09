package org.example.db.service;

import org.example.db.audit.SqlAuditAction;
import org.example.db.audit.SqlAuditLogger;
import org.example.db.config.DbExplorerProperties;
import org.example.db.datasource.DatabaseClient;
import org.example.db.datasource.DatabaseClientRegistry;
import org.example.db.model.TableSchema;
import org.example.db.service.PendingSqlStore.PendingSql;
import org.example.db.service.dto.SqlExecuteResult;
import org.example.db.service.dto.SqlPrepareResult;
import org.example.db.service.dto.SqlQueryResult;
import org.example.db.sql.SqlCategory;
import org.example.db.sql.SqlStatementInfo;
import org.example.db.sql.SqlUtils;
import org.example.db.sql.TableRef;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SQL 执行服务：
 * <ul>
 *   <li>只读/写入分流（SELECT 可直接执行；写入必须“两段式确认”）</li>
 *   <li>写入确认（prepare -> confirm）</li>
 *   <li>执行前可预加载相关表结构（用于缓存与辅助校验）</li>
 *   <li>支持多数据源：根据 dataSourceId 选择连接</li>
 * </ul>
 */
@Service
public class SqlExecutionService {

    private final DatabaseClientRegistry clientRegistry;
    private final TableSchemaService tableSchemaService;
    private final DbExplorerProperties properties;
    private final PendingSqlStore pendingSqlStore;
    private final SqlAuditLogger sqlAuditLogger;

    public SqlExecutionService(DatabaseClientRegistry clientRegistry, TableSchemaService tableSchemaService, DbExplorerProperties properties, SqlAuditLogger sqlAuditLogger) {
        this.clientRegistry = clientRegistry;
        this.tableSchemaService = tableSchemaService;
        this.properties = properties;
        this.pendingSqlStore = new PendingSqlStore(properties.getPendingSqlTtl());
        this.sqlAuditLogger = sqlAuditLogger;
    }

    /**
     * 执行只读 SQL（只允许 SELECT）。
     */
    public SqlQueryResult query(String dataSourceId, String sql, Integer maxRows, Boolean refreshSchema) {
        String resolvedDataSourceId = clientRegistry.resolveDataSourceId(dataSourceId);
        long startNs = System.nanoTime();
        try {
            validateSingleStatement(sql);

            SqlStatementInfo statementInfo = SqlUtils.classify(sql);
            if (statementInfo.category() != SqlCategory.READ) {
                throw new IllegalArgumentException("只读查询仅允许 SELECT；如需写入请先调用 prepareWrite 再 confirm 执行。当前语句类型=" + statementInfo.mainKeyword());
            }

            boolean refresh = Boolean.TRUE.equals(refreshSchema);
            int resolvedMaxRows = resolveMaxRows(maxRows);

            DatabaseClient client = clientRegistry.getClient(resolvedDataSourceId);
            PreloadResult preload = preloadSchemas(resolvedDataSourceId, sql, refresh);
            ExecutionInternalResult exec = execute(client.jdbcTemplate(), sql, resolvedMaxRows);

            sqlAuditLogger.logSuccess(
                    SqlAuditAction.QUERY,
                    resolvedDataSourceId,
                    null,
                    sql,
                    statementInfo,
                    resolvedMaxRows,
                    exec.rows().size(),
                    null,
                    exec.limited(),
                    durationMs(startNs),
                    preload.warnings()
            );

            return new SqlQueryResult(
                    resolvedDataSourceId,
                    sql,
                    statementInfo,
                    preload.refs(),
                    preload.schemas(),
                    exec.rows(),
                    exec.limited(),
                    exec.rows().size(),
                    Instant.now(),
                    preload.warnings()
            );
        } catch (Exception e) {
            // 尽量记录 SQL（即使被校验拦截也要留痕，便于追溯）
            SqlStatementInfo info;
            try {
                info = SqlUtils.classify(sql);
            } catch (Exception ignored) {
                info = null;
            }
            sqlAuditLogger.logFailure(
                    SqlAuditAction.QUERY,
                    resolvedDataSourceId,
                    null,
                    sql,
                    info,
                    maxRows,
                    durationMs(startNs),
                    e,
                    List.of()
            );
            throw e;
        }
    }

    /**
     * 预执行写入 SQL：仅返回 token + SQL + 风险提示，不真正执行。
     */
    public SqlPrepareResult prepareWrite(String dataSourceId, String sql, Boolean refreshSchema) {
        String resolvedDataSourceId = clientRegistry.resolveDataSourceId(dataSourceId);
        long startNs = System.nanoTime();
        try {
            validateSingleStatement(sql);

            SqlStatementInfo statementInfo = SqlUtils.classify(sql);
            if (statementInfo.category() == SqlCategory.READ) {
                throw new IllegalArgumentException("prepareWrite 只用于写入/变更语句；当前语句类型=SELECT，请改用 query");
            }
            if (statementInfo.category() == SqlCategory.DDL && !properties.isAllowDdl()) {
                throw new IllegalArgumentException("当前配置不允许执行 DDL（CREATE/ALTER/DROP/TRUNCATE）。如需开启，请设置 app.db.allow-ddl=true");
            }
            if (statementInfo.category() == SqlCategory.OTHER) {
                throw new IllegalArgumentException("不支持的 SQL 类型（为安全起见已拒绝）: " + statementInfo.mainKeyword());
            }

            boolean refresh = Boolean.TRUE.equals(refreshSchema);
            PreloadResult preload = preloadSchemas(resolvedDataSourceId, sql, refresh);

            List<String> warnings = new ArrayList<>(preload.warnings());
            if (("UPDATE".equalsIgnoreCase(statementInfo.mainKeyword()) || "DELETE".equalsIgnoreCase(statementInfo.mainKeyword()))
                    && !containsWhere(sql)) {
                warnings.add("未检测到 WHERE 条件：可能会影响整张表，请务必确认！");
            }

            PendingSql pendingSql = pendingSqlStore.create(resolvedDataSourceId, sql, statementInfo);

            // prepare 阶段也保留 SQL 语句（用于审计：记录“曾经尝试执行过什么”）
            sqlAuditLogger.logSuccess(
                    SqlAuditAction.WRITE_PREPARE,
                    resolvedDataSourceId,
                    pendingSql.token(),
                    sql,
                    statementInfo,
                    null,
                    null,
                    null,
                    null,
                    durationMs(startNs),
                    warnings
            );

            return new SqlPrepareResult(
                    resolvedDataSourceId,
                    pendingSql.token(),
                    pendingSql.sql(),
                    pendingSql.statementInfo(),
                    pendingSql.expiresAt(),
                    preload.refs(),
                    preload.schemas(),
                    warnings
            );
        } catch (Exception e) {
            SqlStatementInfo info;
            try {
                info = SqlUtils.classify(sql);
            } catch (Exception ignored) {
                info = null;
            }
            sqlAuditLogger.logFailure(
                    SqlAuditAction.WRITE_PREPARE,
                    resolvedDataSourceId,
                    null,
                    sql,
                    info,
                    null,
                    durationMs(startNs),
                    e,
                    List.of()
            );
            throw e;
        }
    }

    /**
     * 确认并执行写入 SQL（两段式确认的第二步）。
     *
     * @param confirm true 才会真正执行；false 表示取消
     */
    public SqlExecuteResult confirmWrite(String token, Boolean confirm, Integer maxRows, Boolean refreshSchema) {
        if (!Boolean.TRUE.equals(confirm)) {
            pendingSqlStore.remove(token);
            sqlAuditLogger.logSuccess(
                    SqlAuditAction.WRITE_CANCEL,
                    null,
                    token,
                    null,
                    new SqlStatementInfo("CANCELLED", SqlCategory.OTHER),
                    null,
                    null,
                    null,
                    null,
                    0L,
                    List.of()
            );
            return new SqlExecuteResult(
                    null,
                    null,
                    new SqlStatementInfo("CANCELLED", SqlCategory.OTHER),
                    List.of(),
                    List.of(),
                    null,
                    List.of(),
                    false,
                    Instant.now(),
                    List.of(),
                    "已取消执行（token 已失效）"
            );
        }

        long startNs = System.nanoTime();
        PendingSql pendingSql = pendingSqlStore.remove(token);
        if (pendingSql == null) {
            sqlAuditLogger.logFailure(
                    SqlAuditAction.WRITE_EXECUTE,
                    null,
                    token,
                    null,
                    null,
                    maxRows,
                    durationMs(startNs),
                    new IllegalArgumentException("token 无效或已过期"),
                    List.of()
            );
            throw new IllegalArgumentException("token 无效或已过期，请重新 prepareWrite。token=" + token);
        }

        String dataSourceId = pendingSql.dataSourceId();
        DatabaseClient client = clientRegistry.getClient(dataSourceId);

        SqlStatementInfo statementInfo = pendingSql.statementInfo();
        if (statementInfo.category() == SqlCategory.READ) {
            throw new IllegalArgumentException("confirmWrite 不允许执行只读 SQL，请使用 query");
        }
        if (statementInfo.category() == SqlCategory.DDL && !properties.isAllowDdl()) {
            throw new IllegalArgumentException("当前配置不允许执行 DDL（CREATE/ALTER/DROP/TRUNCATE）");
        }
        if (statementInfo.category() == SqlCategory.OTHER) {
            throw new IllegalArgumentException("不支持的 SQL 类型（为安全起见已拒绝）: " + statementInfo.mainKeyword());
        }

        boolean refresh = Boolean.TRUE.equals(refreshSchema);
        PreloadResult preload = preloadSchemas(dataSourceId, pendingSql.sql(), refresh);
        int resolvedMaxRows = resolveMaxRows(maxRows);
        try {
            ExecutionInternalResult exec = execute(client.jdbcTemplate(), pendingSql.sql(), resolvedMaxRows);

            sqlAuditLogger.logSuccess(
                    SqlAuditAction.WRITE_EXECUTE,
                    dataSourceId,
                    token,
                    pendingSql.sql(),
                    statementInfo,
                    resolvedMaxRows,
                    exec.rows() == null ? null : exec.rows().size(),
                    exec.updateCount(),
                    exec.limited(),
                    durationMs(startNs),
                    preload.warnings()
            );

            // DDL 可能导致结构变化：让相关表的结构缓存失效，下一次会重新加载。
            if (statementInfo.category() == SqlCategory.DDL) {
                for (TableRef ref : preload.refs()) {
                    if (ref.table() == null) {
                        continue;
                    }
                    try {
                        tableSchemaService.invalidate(dataSourceId, ref.schema(), ref.table());
                    } catch (Exception ignored) {
                        // 表名无法校验/不在白名单时，invalidate 可能抛异常；这里不影响实际执行结果
                    }
                }
            }

            String msg;
            if (exec.rows() != null && !exec.rows().isEmpty()) {
                msg = "SQL 已执行，返回结果集行数=" + exec.rows().size();
            } else {
                msg = "SQL 已执行，影响行数=" + exec.updateCount();
            }

            return new SqlExecuteResult(
                    dataSourceId,
                    pendingSql.sql(),
                    statementInfo,
                    preload.refs(),
                    preload.schemas(),
                    exec.updateCount(),
                    exec.rows(),
                    exec.limited(),
                    Instant.now(),
                    preload.warnings(),
                    msg
            );
        } catch (Exception e) {
            sqlAuditLogger.logFailure(
                    SqlAuditAction.WRITE_EXECUTE,
                    dataSourceId,
                    token,
                    pendingSql.sql(),
                    statementInfo,
                    maxRows,
                    durationMs(startNs),
                    e,
                    preload.warnings()
            );
            throw e;
        }
    }

    private void validateSingleStatement(String sql) {
        if (sql == null || sql.isBlank()) {
            throw new IllegalArgumentException("SQL 不能为空");
        }
        if (SqlUtils.hasMultipleStatements(sql)) {
            throw new IllegalArgumentException("为安全起见，仅允许单条 SQL（不允许包含多条语句/分号分隔）");
        }
    }

    private int resolveMaxRows(Integer requested) {
        int max = properties.getQueryMaxRows();
        if (requested == null || requested <= 0) {
            return max;
        }
        return Math.min(requested, max);
    }

    /**
     * 执行 SQL（可能返回 ResultSet 或 updateCount）。
     */
    private ExecutionInternalResult execute(JdbcTemplate jdbcTemplate, String sql, int maxRows) {
        int maxRowsPlusOne = Math.max(1, maxRows) + 1;
        return jdbcTemplate.execute((Connection connection) -> {
            try (Statement stmt = connection.createStatement()) {
                stmt.setMaxRows(maxRowsPlusOne);
                boolean hasResultSet = stmt.execute(sql);
                if (!hasResultSet) {
                    return new ExecutionInternalResult(stmt.getUpdateCount(), null, false);
                }
                try (ResultSet rs = stmt.getResultSet()) {
                    List<Map<String, Object>> rows = readRows(rs, maxRowsPlusOne);
                    boolean limited = rows.size() > maxRows;
                    if (limited) {
                        rows = rows.subList(0, maxRows);
                    }
                    return new ExecutionInternalResult(null, rows, limited);
                }
            }
        });
    }

    private List<Map<String, Object>> readRows(ResultSet rs, int maxRowsPlusOne) throws java.sql.SQLException {
        ResultSetMetaData meta = rs.getMetaData();
        int columnCount = meta.getColumnCount();

        List<Map<String, Object>> rows = new ArrayList<>();
        while (rs.next() && rows.size() < maxRowsPlusOne) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (int i = 1; i <= columnCount; i++) {
                String label = meta.getColumnLabel(i);
                Object value = rs.getObject(i);
                row.put(label, value);
            }
            rows.add(row);
        }
        return rows;
    }

    /**
     * 尝试预加载表结构（用于缓存与辅助校验）。
     * <p>
     * 若解析失败/不在白名单：不会阻塞 SQL 执行，只会输出 warning。
     */
    private PreloadResult preloadSchemas(String dataSourceId, String sql, boolean refreshSchema) {
        List<TableRef> refs = SqlUtils.extractTableRefs(sql);
        List<TableSchema> schemas = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        for (TableRef ref : refs) {
            try {
                TableSchema schema = tableSchemaService.getTableSchema(dataSourceId, ref.schema(), ref.table(), refreshSchema);
                schemas.add(schema);
            } catch (Exception e) {
                warnings.add("未能加载表结构（将跳过缓存预热）: "
                        + (ref.schema() == null ? "" : ref.schema() + ".") + ref.table()
                        + "，原因=" + e.getMessage());
            }
        }

        return new PreloadResult(refs, schemas, warnings);
    }

    private boolean containsWhere(String sql) {
        return sql != null && sql.toLowerCase().contains(" where ");
    }

    private long durationMs(long startNs) {
        return (System.nanoTime() - startNs) / 1_000_000;
    }

    private record ExecutionInternalResult(Integer updateCount, List<Map<String, Object>> rows, boolean limited) {
    }

    private record PreloadResult(List<TableRef> refs, List<TableSchema> schemas, List<String> warnings) {
    }
}
