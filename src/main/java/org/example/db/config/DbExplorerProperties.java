package org.example.db.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.example.db.datasource.DatabaseType;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP DB Server 的业务配置项。
 * <p>
 * 设计目标：
 * <ul>
 *   <li>支持多数据源（可同时配置 PostgreSQL/MySQL/Oracle/SQLServer 等）</li>
 *   <li>每个数据源都有自己的 defaultSchema / allowedSchemas（白名单控制，避免越权访问）</li>
 *   <li>表结构缓存（TTL）减少频繁访问系统表</li>
 *   <li>写入 SQL 采用“两段式确认”（prepare -> confirm）降低误操作风险</li>
 * </ul>
 */
@Validated
@ConfigurationProperties(prefix = "app.db")
public class DbExplorerProperties {

    /**
     * 默认使用的数据源 ID（当 MCP 工具入参未指定 dataSourceId 时使用）。
     */
    @NotBlank
    private String defaultDataSource = "primary";

    /**
     * 多数据源配置。
     * <p>
     * key 为 dataSourceId（例如：primary、pg1、mysql1、oracle1、mssql1），value 为连接信息与 schema 白名单等。
     */
    @NotNull
    private Map<String, DataSourceProperties> dataSources = new LinkedHashMap<>();

    /**
     * 表结构缓存 TTL（减少频繁查询系统表）。
     */
    @NotNull
    private Duration schemaCacheTtl = Duration.ofMinutes(10);

    /**
     * 表结构缓存最大条数（避免极端情况下内存持续增长）。
     */
    @Min(10)
    @Max(10_000)
    private int schemaCacheMaxSize = 500;

    /**
     * 表/视图列表最大返回条数（用于 db_list_tables 的分页上限保护）。
     * <p>
     * 当一个 schema 下有几千张表时，强烈建议：
     * <ul>
     *   <li>通过 keyword 过滤（模糊匹配表名）</li>
     *   <li>通过 limit/offset 分页拉取</li>
     * </ul>
     */
    @Min(1)
    @Max(10_000)
    private int tableListMaxRows = 200;

    /**
     * 批量获取表结构时的并发度（线程池大小）。
     * <p>
     * 建议：
     * <ul>
     *   <li>不要超过连接池最大连接数（app.db.pool.max-active）</li>
     *   <li>大多数场景 4~8 足够</li>
     * </ul>
     */
    @Min(1)
    @Max(64)
    private int schemaFetchParallelism = 4;

    /**
     * 单次批量获取表结构允许的最大表数量（防止一次请求过大）。
     */
    @Min(1)
    @Max(1000)
    private int schemaBatchMaxTables = 50;

    /**
     * 查询最大返回行数（工具侧保护，避免一次拉取过多数据导致内存/网络压力）。
     */
    @Min(1)
    @Max(100_000)
    private int queryMaxRows = 500;

    /**
     * 待确认 SQL 的有效期；超时 token 失效，需要重新 prepare。
     */
    @NotNull
    private Duration pendingSqlTtl = Duration.ofMinutes(10);

    /**
     * 是否允许执行 DDL（例如：CREATE/ALTER/DROP/TRUNCATE）。
     * <p>
     * 默认关闭：避免模型误生成 DDL 破坏结构。
     */
    private boolean allowDdl = false;

    /**
     * 连接池配置（当前项目使用 Druid）。
     * <p>
     * 说明：为了简化管理，这里提供“全局默认池配置”，各数据源共用；
     * 如果你需要“每个数据源独立的池参数”，可以再加一层覆盖配置。
     */
    @NotNull
    private PoolProperties pool = new PoolProperties();

    public String getDefaultDataSource() {
        return defaultDataSource;
    }

    public void setDefaultDataSource(String defaultDataSource) {
        this.defaultDataSource = defaultDataSource;
    }

    public Map<String, DataSourceProperties> getDataSources() {
        return dataSources;
    }

    public void setDataSources(Map<String, DataSourceProperties> dataSources) {
        this.dataSources = dataSources;
    }

    public Duration getSchemaCacheTtl() {
        return schemaCacheTtl;
    }

    public void setSchemaCacheTtl(Duration schemaCacheTtl) {
        this.schemaCacheTtl = schemaCacheTtl;
    }

    public int getSchemaCacheMaxSize() {
        return schemaCacheMaxSize;
    }

    public void setSchemaCacheMaxSize(int schemaCacheMaxSize) {
        this.schemaCacheMaxSize = schemaCacheMaxSize;
    }

    public int getTableListMaxRows() {
        return tableListMaxRows;
    }

    public void setTableListMaxRows(int tableListMaxRows) {
        this.tableListMaxRows = tableListMaxRows;
    }

    public int getSchemaFetchParallelism() {
        return schemaFetchParallelism;
    }

    public void setSchemaFetchParallelism(int schemaFetchParallelism) {
        this.schemaFetchParallelism = schemaFetchParallelism;
    }

    public int getSchemaBatchMaxTables() {
        return schemaBatchMaxTables;
    }

    public void setSchemaBatchMaxTables(int schemaBatchMaxTables) {
        this.schemaBatchMaxTables = schemaBatchMaxTables;
    }

    public int getQueryMaxRows() {
        return queryMaxRows;
    }

    public void setQueryMaxRows(int queryMaxRows) {
        this.queryMaxRows = queryMaxRows;
    }

    public Duration getPendingSqlTtl() {
        return pendingSqlTtl;
    }

    public void setPendingSqlTtl(Duration pendingSqlTtl) {
        this.pendingSqlTtl = pendingSqlTtl;
    }

    public boolean isAllowDdl() {
        return allowDdl;
    }

    public void setAllowDdl(boolean allowDdl) {
        this.allowDdl = allowDdl;
    }

    public PoolProperties getPool() {
        return pool;
    }

    public void setPool(PoolProperties pool) {
        this.pool = pool;
    }

    /**
     * 单个数据源配置项。
     */
    public static class DataSourceProperties {

        /**
         * 数据库类型（可选）。
         * <p>
         * - 不填：运行时通过 JDBC 元数据自动识别（推荐）。
         * - 填了：按填写值强制使用（适合某些驱动 productName 不稳定的情况）。
         */
        private DatabaseType type;

        /**
         * JDBC URL（必填）。
         */
        @NotBlank
        private String url;

        /**
         * 用户名（可选，部分数据库允许通过 URL 携带）。
         */
        private String username;

        /**
         * 密码（可选，建议仅用于本地测试；生产建议走环境变量/密钥管理）。
         */
        private String password;

        /**
         * JDBC Driver（可选，不填通常也能靠 SPI 自动加载；但某些场景建议显式指定）。
         */
        private String driverClassName;

        /**
         * 默认 schema（当工具入参 schema 为空时使用）。
         * <p>
         * - PostgreSQL/SQLServer：常见为 public / dbo
         * - MySQL：这里对应“database”（即 information_schema.columns.table_schema）
         * - Oracle：这里对应“owner/schema”（通常为用户名，建议大写）
         */
        @NotBlank
        private String defaultSchema = "public";

        /**
         * 允许访问的 schema 白名单：
         * <ul>
         *   <li>null/空：表示仅允许 defaultSchema</li>
         *   <li>非空：必须在白名单中才能访问</li>
         * </ul>
         */
        private List<String> allowedSchemas;

        public DatabaseType getType() {
            return type;
        }

        public void setType(DatabaseType type) {
            this.type = type;
        }

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public String getDriverClassName() {
            return driverClassName;
        }

        public void setDriverClassName(String driverClassName) {
            this.driverClassName = driverClassName;
        }

        public String getDefaultSchema() {
            return defaultSchema;
        }

        public void setDefaultSchema(String defaultSchema) {
            this.defaultSchema = defaultSchema;
        }

        public List<String> getAllowedSchemas() {
            return allowedSchemas;
        }

        public void setAllowedSchemas(List<String> allowedSchemas) {
            this.allowedSchemas = allowedSchemas;
        }
    }

    /**
     * Druid 连接池参数（全局默认）。
     */
    public static class PoolProperties {

        @Min(0)
        private int initialSize = 5;

        @Min(0)
        private int minIdle = 5;

        @Min(1)
        private int maxActive = 20;

        /**
         * 最大等待时间。
         */
        @NotNull
        private Duration maxWait = Duration.ofSeconds(60);

        /**
         * 连接校验 SQL：不同库通用用法是 SELECT 1。
         */
        @NotBlank
        private String validationQuery = "SELECT 1";

        private boolean testWhileIdle = true;
        private boolean testOnBorrow = false;
        private boolean testOnReturn = false;

        public int getInitialSize() {
            return initialSize;
        }

        public void setInitialSize(int initialSize) {
            this.initialSize = initialSize;
        }

        public int getMinIdle() {
            return minIdle;
        }

        public void setMinIdle(int minIdle) {
            this.minIdle = minIdle;
        }

        public int getMaxActive() {
            return maxActive;
        }

        public void setMaxActive(int maxActive) {
            this.maxActive = maxActive;
        }

        public Duration getMaxWait() {
            return maxWait;
        }

        public void setMaxWait(Duration maxWait) {
            this.maxWait = maxWait;
        }

        public String getValidationQuery() {
            return validationQuery;
        }

        public void setValidationQuery(String validationQuery) {
            this.validationQuery = validationQuery;
        }

        public boolean isTestWhileIdle() {
            return testWhileIdle;
        }

        public void setTestWhileIdle(boolean testWhileIdle) {
            this.testWhileIdle = testWhileIdle;
        }

        public boolean isTestOnBorrow() {
            return testOnBorrow;
        }

        public void setTestOnBorrow(boolean testOnBorrow) {
            this.testOnBorrow = testOnBorrow;
        }

        public boolean isTestOnReturn() {
            return testOnReturn;
        }

        public void setTestOnReturn(boolean testOnReturn) {
            this.testOnReturn = testOnReturn;
        }
    }
}
