package org.example.mcp;

import org.example.db.datasource.DatabaseClientRegistry;
import org.example.db.datasource.DatabaseType;
import org.example.db.model.TableSchema;
import org.example.db.service.SqlExecutionService;
import org.example.db.service.TableSchemaService;
import org.example.db.service.dto.DataSourceInfo;
import org.example.db.service.dto.SqlExecuteResult;
import org.example.db.service.dto.SqlPrepareResult;
import org.example.db.service.dto.SqlQueryResult;
import org.example.db.service.dto.TableListResult;
import org.example.db.service.dto.TableSchemaBatchResult;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 数据库相关 MCP Tools。
 * <p>
 * 设计目标：
 * <ul>
 *   <li>先获取表结构（字段/备注）再生成 SQL，降低“列名写错”的概率</li>
 *   <li>查询（SELECT）可直接执行；写入（INSERT/UPDATE/DELETE/DDL）必须“两段式确认”</li>
 *   <li>支持多数据源：通过 dataSourceId 选择连接，并按数据库类型自动选择兼容 SQL</li>
 * </ul>
 */
@Component
public class DatabaseMcpTools {

    private final DatabaseClientRegistry clientRegistry;
    private final TableSchemaService tableSchemaService;
    private final SqlExecutionService sqlExecutionService;

    public DatabaseMcpTools(DatabaseClientRegistry clientRegistry, TableSchemaService tableSchemaService, SqlExecutionService sqlExecutionService) {
        this.clientRegistry = clientRegistry;
        this.tableSchemaService = tableSchemaService;
        this.sqlExecutionService = sqlExecutionService;
    }

    @Tool(
            name = "db_list_data_sources",
            description = "列出当前 MCP Server 已配置的数据源（用于选择 dataSourceId）"
    )
    public List<DataSourceInfo> listDataSources() {
        return clientRegistry.listDataSourceIds().stream().map(id -> {
            var cfg = clientRegistry.getDataSourceConfig(id);
            DatabaseType resolvedType;
            try {
                resolvedType = clientRegistry.resolveDatabaseType(id);
            } catch (Exception e) {
                resolvedType = DatabaseType.UNKNOWN;
            }
            return new DataSourceInfo(
                    id,
                    cfg.getType(),
                    resolvedType,
                    maskJdbcUrl(cfg.getUrl()),
                    cfg.getUsername(),
                    cfg.getDefaultSchema(),
                    cfg.getAllowedSchemas()
            );
        }).toList();
    }

    @Tool(
            name = "db_list_tables",
            description = "列出指定 schema 下的表/视图（支持关键字过滤与分页）；schema 为空则使用该数据源 defaultSchema。schema 下表很多时强烈建议传 keyword + limit/offset"
    )
    public TableListResult listTables(
            @ToolParam(required = false, description = "数据源ID（可选，默认使用 app.db.default-data-source）") String dataSourceId,
            @ToolParam(required = false, description = "schema（可选，默认使用该数据源 defaultSchema）") String schema,
            @ToolParam(required = false, description = "表名关键字（可选，模糊匹配表名，例如 user / order / log）") String keyword,
            @ToolParam(required = false, description = "分页大小（可选，默认 app.db.table-list-max-rows，且会被上限保护）") Integer limit,
            @ToolParam(required = false, description = "偏移量（可选，从 0 开始；分页时使用）") Integer offset,
            @ToolParam(required = false, description = "是否返回表备注（可选，默认 false；开启可能增加系统表开销）") Boolean includeComments
    ) {
        return tableSchemaService.listTables(dataSourceId, schema, keyword, limit, offset, includeComments);
    }

    @Tool(
            name = "db_get_table_schema",
            description = "获取表结构（字段/类型/是否可空/默认值/字段备注等）；会做 TTL 缓存，可按需强制刷新"
    )
    public TableSchema getTableSchema(
            @ToolParam(required = false, description = "数据源ID（可选，默认使用 app.db.default-data-source）") String dataSourceId,
            @ToolParam(required = false, description = "schema（可选，默认使用该数据源 defaultSchema）") String schema,
            @ToolParam(description = "表名") String table,
            @ToolParam(required = false, description = "是否强制刷新缓存（true/false），默认 false") Boolean refresh
    ) {
        return tableSchemaService.getTableSchema(dataSourceId, schema, table, Boolean.TRUE.equals(refresh));
    }

    @Tool(
            name = "db_get_table_schema_batch",
            description = "批量获取多张表的结构（并发获取，减少 MCP 往返次数）。注意：单次最大表数量受 app.db.schema-batch-max-tables 限制"
    )
    public TableSchemaBatchResult getTableSchemaBatch(
            @ToolParam(required = false, description = "数据源ID（可选，默认使用 app.db.default-data-source）") String dataSourceId,
            @ToolParam(required = false, description = "schema（可选，默认使用该数据源 defaultSchema）") String schema,
            @ToolParam(description = "表名列表（数组），建议一次不要太多") List<String> tables,
            @ToolParam(required = false, description = "是否强制刷新缓存（true/false），默认 false") Boolean refresh
    ) {
        return tableSchemaService.getTableSchemas(dataSourceId, schema, tables, refresh);
    }

    @Tool(
            name = "db_query_sql",
            description = "执行只读查询 SQL（仅允许 SELECT），返回查询结果；执行前会尝试解析 SQL 涉及的表并预加载表结构缓存"
    )
    public SqlQueryResult querySql(
            @ToolParam(required = false, description = "数据源ID（可选，默认使用 app.db.default-data-source）") String dataSourceId,
            @ToolParam(description = "查询 SQL（仅允许 SELECT）") String sql,
            @ToolParam(required = false, description = "最大返回行数（可选，默认 app.db.query-max-rows，且会被上限保护）") Integer maxRows,
            @ToolParam(required = false, description = "是否强制刷新表结构缓存（true/false），默认 false") Boolean refreshSchema
    ) {
        return sqlExecutionService.query(dataSourceId, sql, maxRows, refreshSchema);
    }

    @Tool(
            name = "db_prepare_write_sql",
            description = "准备执行写入 SQL（INSERT/UPDATE/DELETE/DDL），仅返回 token + 即将执行的 SQL + 风险提示；不会真正执行"
    )
    public SqlPrepareResult prepareWriteSql(
            @ToolParam(required = false, description = "数据源ID（可选，默认使用 app.db.default-data-source）") String dataSourceId,
            @ToolParam(description = "写入 SQL（INSERT/UPDATE/DELETE/DDL），不允许多语句") String sql,
            @ToolParam(required = false, description = "是否强制刷新表结构缓存（true/false），默认 false") Boolean refreshSchema
    ) {
        return sqlExecutionService.prepareWrite(dataSourceId, sql, refreshSchema);
    }

    @Tool(
            name = "db_confirm_write_sql",
            description = "确认并执行 prepare 返回 token 对应的 SQL；confirm=true 才会执行，confirm=false 则取消"
    )
    public SqlExecuteResult confirmWriteSql(
            @ToolParam(description = "prepare 返回的 token") String token,
            @ToolParam(required = false, description = "是否确认执行（true 执行；false 取消），默认 false") Boolean confirm,
            @ToolParam(required = false, description = "最大返回行数（当写入语句有 RETURNING/OUTPUT 时用于限制返回行数）") Integer maxRows,
            @ToolParam(required = false, description = "是否强制刷新表结构缓存（true/false），默认 false") Boolean refreshSchema
    ) {
        return sqlExecutionService.confirmWrite(token, confirm, maxRows, refreshSchema);
    }

    /**
     * 简单脱敏：隐藏 URL 中的 password/pwd 参数（避免把敏感信息暴露给 MCP 客户端）。
     */
    private static String maskJdbcUrl(String url) {
        if (url == null) {
            return null;
        }
        String masked = url;
        masked = masked.replaceAll("(?i)(password|pwd)=([^;&]+)", "$1=***");
        return masked;
    }
}
