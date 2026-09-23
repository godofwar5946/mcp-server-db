package org.example.db.service;

import org.example.db.audit.*;
import org.example.db.config.DbExplorerProperties;
import org.example.db.datasource.*;
import org.example.db.model.TableSchema;
import org.example.db.service.dto.*;
import org.example.db.sql.*;
import org.example.db.sql.SqlUtils.CheckedSql;
import org.example.security.Caller;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.*;
import java.time.Instant;
import java.util.*;

@Service
public class SqlExecutionService {
    private final DatabaseClientRegistry clients;
    private final TableSchemaService schemas;
    private final DbExplorerProperties properties;
    private final PendingSqlStore pending;
    private final SqlAuditLogger audit;
    private final QueryResultReader reader;

    public SqlExecutionService(DatabaseClientRegistry clients, TableSchemaService schemas, DbExplorerProperties properties,
                               PendingSqlStore pending, SqlAuditLogger audit, QueryResultReader reader) {
        this.clients = clients;
        this.schemas = schemas;
        this.properties = properties;
        this.pending = pending;
        this.audit = audit;
        this.reader = reader;
    }

    public SqlQueryResult query(Caller caller, String dataSourceId, String sql, Integer maxRows, Boolean refreshSchema) {
        String id = clients.resolveDataSourceId(dataSourceId);
        long start = System.nanoTime();
        try {
            CheckedSql checked = check(id, sql);
            if (checked.info().category() != SqlCategory.READ) throw new IllegalArgumentException("查询工具仅允许无副作用的 SELECT");
            List<String> warnings = new ArrayList<>();
            List<TableSchema> loaded = refresh(id, checked, refreshSchema, warnings);
            Executed result = execute(id, checked, resolveMaxRows(maxRows), true);
            warnings.addAll(result.result().warnings());
            audit.log(SqlAuditAction.QUERY, caller, id, null, checked.sql(), elapsed(start), null,
                    Map.of("returnedRows", result.result().rows().size(), "limited", result.result().limited()));
            return new SqlQueryResult(id, checked.sql(), checked.info(), checked.refs(), loaded, result.result().rows(),
                    result.result().limited(), result.result().rows().size(), Instant.now(), warnings, result.result().columns());
        } catch (RuntimeException e) {
            audit.log(SqlAuditAction.QUERY, caller, id, null, bounded(sql), elapsed(start), e, Map.of());
            throw safe(e);
        }
    }

    public SqlPrepareResult prepareWrite(Caller caller, String dataSourceId, String sql, Boolean refreshSchema) {
        String id = clients.resolveDataSourceId(dataSourceId);
        long start = System.nanoTime();
        try {
            CheckedSql checked = check(id, sql);
            requireWritePolicy(id, checked);
            List<String> warnings = new ArrayList<>();
            if (checked.withoutWhere()) warnings.add("SQL 不含 WHERE，将影响整张表");
            warnings.add("执行需要正确的 approvalKey 或独立审批；confirm=true 本身不构成批准");
            List<TableSchema> loaded = refresh(id, checked, refreshSchema, warnings);
            var item = pending.create(caller.owner(), id, checked);
            audit.log(SqlAuditAction.WRITE_PREPARE, caller, id, item.token(), checked.sql(), elapsed(start), null, Map.of());
            return new SqlPrepareResult(id, item.token(), checked.sql(), checked.info(), item.expiresAt(), checked.refs(), loaded, warnings);
        } catch (RuntimeException e) {
            audit.log(SqlAuditAction.WRITE_PREPARE, caller, id, null, bounded(sql), elapsed(start), e, Map.of());
            throw safe(e);
        }
    }

    public PendingSqlStore.PendingSql pendingWrite(Caller caller, String token) {
        var item = pending.get(token, caller.owner());
        requireWritePolicy(item.dataSourceId(), check(item.dataSourceId(), item.sql().sql()));
        return item;
    }

    public SqlExecuteResult confirmWrite(Caller caller, String token, Boolean confirm, Integer maxRows, Boolean refreshSchema) {
        long start = System.nanoTime();
        PendingSqlStore.PendingSql item = null;
        boolean attemptedDdl = false;
        try {
            item = pending.get(token, caller.owner());
            if (!Boolean.TRUE.equals(confirm)) {
                pending.consume(token, caller.owner(), false);
                audit.log(SqlAuditAction.WRITE_CANCEL, caller, item.dataSourceId(), token, item.sql().sql(), elapsed(start), null, Map.of());
                return new SqlExecuteResult(item.dataSourceId(), null, new SqlStatementInfo("CANCELLED", SqlCategory.OTHER),
                        List.of(), List.of(), null, List.of(), false, Instant.now(), List.of(), "已取消执行", List.of());
            }
            CheckedSql checked = check(item.dataSourceId(), item.sql().sql());
            requireWritePolicy(item.dataSourceId(), checked);
            if (!PendingSqlStore.hash(checked.sql()).equals(item.sqlHash()))
                throw new IllegalArgumentException("SQL 执行内容已变化，请重新 prepare");
            // Revalidate permissions before consuming. Atomic removal prevents duplicate execution.
            item = pending.consume(token, caller.owner(), true);
            attemptedDdl = checked.info().category() == SqlCategory.DDL;
            Executed result = execute(item.dataSourceId(), checked, resolveMaxRows(maxRows), false);
            audit.log(SqlAuditAction.WRITE_EXECUTE, caller, item.dataSourceId(), token, checked.sql(), elapsed(start), null,
                    Map.of("returnedRows", result.result().rows().size(), "updateCount", Objects.requireNonNullElse(result.updateCount(), -1)));
            return new SqlExecuteResult(item.dataSourceId(), checked.sql(), checked.info(), checked.refs(), List.of(),
                    result.updateCount(), result.result().rows(), result.result().limited(), Instant.now(),
                    result.result().warnings(), "SQL 已执行", result.result().columns());
        } catch (RuntimeException e) {
            audit.log(SqlAuditAction.WRITE_EXECUTE, caller, item == null ? null : item.dataSourceId(), token,
                    item == null ? null : item.sql().sql(), elapsed(start), e, Map.of());
            throw safe(e);
        } finally {
            // DDL may auto-commit or change dependent objects; clear this datasource even after a failed attempt.
            if (attemptedDdl && item != null) schemas.invalidateDataSource(item.dataSourceId());
        }
    }

    private CheckedSql check(String id, String sql) {
        var cfg = clients.getDataSourceConfig(id);
        return SqlUtils.check(sql, clients.resolveDatabaseType(id), cfg.getDefaultSchema(), cfg.getAllowedSchemas(),
                properties.getAllowedFunctions(), properties.getSqlMaxLength());
    }

    private void requireWritePolicy(String id, CheckedSql sql) {
        if (!clients.getDataSourceConfig(id).isAllowWrites()) throw new SecurityException("该数据源未开启写入");
        if (sql.info().category() == SqlCategory.READ) throw new IllegalArgumentException("请使用 query 执行查询");
        if (sql.info().category() == SqlCategory.DDL && !properties.isAllowDdl()) throw new SecurityException("未开启 DDL");
        if (sql.withoutWhere() && !properties.isAllowFullTableWrite()) throw new SecurityException("默认拒绝不含 WHERE 的 UPDATE/DELETE");
    }

    private List<TableSchema> refresh(String id, CheckedSql sql, Boolean requested, List<String> warnings) {
        if (!Boolean.TRUE.equals(requested)) return List.of();
        List<TableSchema> loaded = new ArrayList<>();
        for (TableRef ref : sql.refs()) {
            try { loaded.add(schemas.getTableSchema(id, ref.schema(), ref.table(), true)); }
            catch (RuntimeException e) { warnings.add("未能刷新表结构: " + ref.schema() + "." + ref.table()); }
        }
        return loaded;
    }

    private Executed execute(String id, CheckedSql sql, int maxRows, boolean readOnly) {
        DatabaseClient client = clients.getClient(id);
        DataSourceTransactionManager manager = new DataSourceTransactionManager(client.dataSource());
        // SQL Server does not implement SET TRANSACTION READ ONLY; its account must have SELECT-only grants.
        manager.setEnforceReadOnly(readOnly && clients.resolveDatabaseType(id) != DatabaseType.SQLSERVER);
        TransactionTemplate transaction = new TransactionTemplate(manager);
        transaction.setReadOnly(readOnly);
        transaction.setTimeout(properties.getQueryTimeoutSeconds());
        return transaction.execute(status -> client.jdbcTemplate().execute((ConnectionCallback<Executed>) connection -> {
            try (Statement statement = connection.createStatement()) {
                org.springframework.jdbc.datasource.DataSourceUtils.applyTimeout(
                        statement, client.dataSource(), properties.getQueryTimeoutSeconds());
                statement.setFetchSize(properties.getQueryFetchSize());
                statement.setMaxRows(maxRows + 1);
                boolean hasRows = statement.execute(sql.sql());
                Integer count = null;
                QueryResultReader.Result result = new QueryResultReader.Result(List.of(), List.of(), false, List.of());
                boolean readResult = false;
                while (true) {
                    if (hasRows) {
                        if (readResult) throw new SQLException("不支持多个结果集");
                        try (ResultSet rs = statement.getResultSet()) { result = reader.read(rs, maxRows); }
                        readResult = true;
                    } else {
                        int updateCount = statement.getUpdateCount();
                        if (updateCount == -1) break;
                        count = count == null ? updateCount : Math.addExact(count, updateCount);
                    }
                    hasRows = statement.getMoreResults(Statement.CLOSE_CURRENT_RESULT);
                }
                if (readOnly && (!readResult || count != null)) throw new SQLException("只读查询出现写入结果");
                return new Executed(count, result);
            }
        }));
    }

    private int resolveMaxRows(Integer requested) {
        return requested == null || requested <= 0 ? properties.getQueryMaxRows() : Math.min(requested, properties.getQueryMaxRows());
    }
    private String bounded(String sql) { return sql == null ? null : sql.substring(0, Math.min(sql.length(), properties.getSqlMaxLength())); }
    private static long elapsed(long start) { return (System.nanoTime() - start) / 1_000_000; }
    private static RuntimeException safe(RuntimeException e) {
        if (e instanceof DataAccessException || e instanceof org.springframework.transaction.TransactionException)
            return new IllegalStateException("数据库执行失败或超时；请检查 SQL、连接及数据库权限", e);
        return e;
    }
    private record Executed(Integer updateCount, QueryResultReader.Result result) {}
}
