package org.example.db.service;

import org.example.db.config.DbExplorerProperties;
import org.example.db.datasource.DatabaseClient;
import org.example.db.datasource.DatabaseClientRegistry;
import org.example.db.datasource.DatabaseType;
import org.example.db.dialect.DatabaseDialect;
import org.example.db.dialect.DatabaseDialectRegistry;
import org.example.db.model.TableInfo;
import org.example.db.model.TableSchema;
import org.example.db.service.dto.TableListResult;
import org.example.db.service.dto.TableSchemaBatchItem;
import org.example.db.service.dto.TableSchemaBatchResult;
import org.example.db.util.IdentifierUtils;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/**
 * 表结构服务：统一处理 schema 白名单校验 + 表结构缓存 + 多数据源/多方言路由。
 */
@Service
public class TableSchemaService {

    private final DbExplorerProperties properties;
    private final DatabaseClientRegistry clientRegistry;
    private final DatabaseDialectRegistry dialectRegistry;
    private final TableSchemaCache cache;
    private final ExecutorService schemaFetchExecutor;

    public TableSchemaService(
            DbExplorerProperties properties,
            DatabaseClientRegistry clientRegistry,
            DatabaseDialectRegistry dialectRegistry,
            @Qualifier("schemaFetchExecutor") ExecutorService schemaFetchExecutor
    ) {
        this.properties = properties;
        this.clientRegistry = clientRegistry;
        this.dialectRegistry = dialectRegistry;
        this.cache = new TableSchemaCache(properties.getSchemaCacheTtl(), properties.getSchemaCacheMaxSize());
        this.schemaFetchExecutor = schemaFetchExecutor;
    }

    /**
     * 列出 schema 下的表/视图（分页 + 关键字过滤）。
     */
    public TableListResult listTables(String dataSourceId, String schema, String keyword, Integer limit, Integer offset, Boolean includeComments) {
        String resolvedDataSourceId = clientRegistry.resolveDataSourceId(dataSourceId);
        String resolvedSchema = resolveAndValidateSchema(resolvedDataSourceId, schema);

        DatabaseClient client = clientRegistry.getClient(resolvedDataSourceId);
        DatabaseDialect dialect = resolveDialect(resolvedDataSourceId);

        int resolvedLimit = resolveTableListLimit(limit);
        int resolvedOffset = resolveOffset(offset);
        boolean resolvedIncludeComments = Boolean.TRUE.equals(includeComments);

        // 为了判断 hasMore：向数据库多取 1 行
        int fetchLimit = resolvedLimit + 1;
        List<TableInfo> fetched = dialect.listTables(
                client.jdbcTemplate(),
                resolvedSchema,
                keyword,
                fetchLimit,
                resolvedOffset,
                resolvedIncludeComments
        );

        boolean hasMore = fetched.size() > resolvedLimit;
        List<TableInfo> result = hasMore ? fetched.subList(0, resolvedLimit) : fetched;
        Integer nextOffset = hasMore ? resolvedOffset + resolvedLimit : null;

        return new TableListResult(
                resolvedDataSourceId,
                resolvedSchema,
                keyword,
                resolvedLimit,
                resolvedOffset,
                result.size(),
                hasMore,
                nextOffset,
                result
        );
    }

    /**
     * 获取表结构（带缓存）。
     *
     * @param refresh 是否强制刷新缓存
     */
    public TableSchema getTableSchema(String dataSourceId, String schema, String table, boolean refresh) {
        String resolvedDataSourceId = clientRegistry.resolveDataSourceId(dataSourceId);
        String resolvedSchema = resolveAndValidateSchema(resolvedDataSourceId, schema);
        String safeTable = IdentifierUtils.requireSafeIdentifier(table, "table");

        DatabaseClient client = clientRegistry.getClient(resolvedDataSourceId);
        DatabaseDialect dialect = resolveDialect(resolvedDataSourceId);
        return cache.getOrLoad(resolvedDataSourceId, resolvedSchema, safeTable, refresh,
                () -> dialect.getTableSchema(client.jdbcTemplate(), resolvedSchema, safeTable));
    }

    /**
     * 批量获取多个表的结构（支持并发）。
     * <p>
     * 用途：当一个业务 SQL 可能涉及多张表时，减少 MCP 往返次数，提高 AI 使用效率。
     */
    public TableSchemaBatchResult getTableSchemas(String dataSourceId, String schema, List<String> tables, Boolean refresh) {
        String resolvedDataSourceId = clientRegistry.resolveDataSourceId(dataSourceId);
        String resolvedSchema = resolveAndValidateSchema(resolvedDataSourceId, schema);
        boolean resolvedRefresh = Boolean.TRUE.equals(refresh);

        if (tables == null || tables.isEmpty()) {
            throw new IllegalArgumentException("tables 不能为空");
        }

        // 去重 + 去空白
        List<String> requestedTables = tables.stream()
                .filter(t -> t != null && !t.isBlank())
                .distinct()
                .toList();

        int maxTables = properties.getSchemaBatchMaxTables();
        if (requestedTables.size() > maxTables) {
            throw new IllegalArgumentException("单次最多允许获取 " + maxTables + " 张表结构，请缩小范围或分页调用。requested=" + requestedTables.size());
        }

        long startNs = System.nanoTime();

        DatabaseClient client = clientRegistry.getClient(resolvedDataSourceId);
        DatabaseDialect dialect = resolveDialect(resolvedDataSourceId);

        List<CompletableFuture<TableSchemaBatchItem>> futures = new ArrayList<>(requestedTables.size());
        for (String table : requestedTables) {
            futures.add(CompletableFuture.supplyAsync(() -> loadSingleTableSchema(resolvedDataSourceId, resolvedSchema, table, resolvedRefresh, client, dialect), schemaFetchExecutor));
        }

        List<TableSchemaBatchItem> items = futures.stream().map(CompletableFuture::join).toList();
        int succeeded = (int) items.stream().filter(TableSchemaBatchItem::success).count();
        int failed = items.size() - succeeded;

        return new TableSchemaBatchResult(
                resolvedDataSourceId,
                resolvedSchema,
                requestedTables.size(),
                succeeded,
                failed,
                resolvedRefresh,
                durationMs(startNs),
                items
        );
    }

    public void invalidate(String dataSourceId, String schema, String table) {
        String resolvedDataSourceId = clientRegistry.resolveDataSourceId(dataSourceId);
        String resolvedSchema = resolveAndValidateSchema(resolvedDataSourceId, schema);
        String safeTable = IdentifierUtils.requireSafeIdentifier(table, "table");
        cache.invalidate(resolvedDataSourceId, resolvedSchema, safeTable);
    }

    private DatabaseDialect resolveDialect(String dataSourceId) {
        DatabaseType type = clientRegistry.resolveDatabaseType(dataSourceId);
        return dialectRegistry.getDialect(type);
    }

    private int resolveTableListLimit(Integer requested) {
        int max = properties.getTableListMaxRows();
        if (requested == null || requested <= 0) {
            return max;
        }
        return Math.min(requested, max);
    }

    private int resolveOffset(Integer requested) {
        if (requested == null || requested < 0) {
            return 0;
        }
        return requested;
    }

    private TableSchemaBatchItem loadSingleTableSchema(
            String dataSourceId,
            String schema,
            String table,
            boolean refresh,
            DatabaseClient client,
            DatabaseDialect dialect
    ) {
        String safeTable;
        try {
            safeTable = IdentifierUtils.requireSafeIdentifier(table, "table");
        } catch (Exception e) {
            return new TableSchemaBatchItem(table, false, null, e.getMessage());
        }

        try {
            TableSchema tableSchema = cache.getOrLoad(dataSourceId, schema, safeTable, refresh,
                    () -> dialect.getTableSchema(client.jdbcTemplate(), schema, safeTable));
            return new TableSchemaBatchItem(table, true, tableSchema, null);
        } catch (Exception e) {
            return new TableSchemaBatchItem(table, false, null, e.getMessage());
        }
    }

    private long durationMs(long startNs) {
        return (System.nanoTime() - startNs) / 1_000_000;
    }

    private String resolveAndValidateSchema(String dataSourceId, String schema) {
        DbExplorerProperties.DataSourceProperties cfg = clientRegistry.getDataSourceConfig(dataSourceId);

        String resolved = (schema == null || schema.isBlank()) ? cfg.getDefaultSchema() : schema;
        String safeSchema = IdentifierUtils.requireSafeIdentifier(resolved, "schema");

        List<String> allowed = cfg.getAllowedSchemas();
        if (allowed == null || allowed.isEmpty()) {
            if (!safeSchema.equalsIgnoreCase(cfg.getDefaultSchema())) {
                throw new IllegalArgumentException("不允许访问 schema: " + safeSchema + "（仅允许 defaultSchema=" + cfg.getDefaultSchema() + "）");
            }
            return safeSchema;
        }

        // 支持 "*" 表示放开所有 schema（不推荐生产环境）
        Set<String> allowedSet = new HashSet<>(allowed);
        if (allowedSet.contains("*")) {
            return safeSchema;
        }

        boolean ok = allowed.stream().anyMatch(s -> s != null && s.equalsIgnoreCase(safeSchema));
        if (!ok) {
            throw new IllegalArgumentException("不允许访问 schema: " + safeSchema + "（允许列表=" + allowed + "）");
        }
        return safeSchema;
    }
}
