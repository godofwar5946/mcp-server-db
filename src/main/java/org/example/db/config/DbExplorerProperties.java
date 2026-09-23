package org.example.db.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.example.db.datasource.DatabaseType;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;
import java.time.Duration;
import java.util.*;

@Validated
@ConfigurationProperties(prefix = "app.db")
public class DbExplorerProperties {
    @NotBlank
    private String defaultDataSource = "primary";

    @NotEmpty @Valid
    private Map<String, DataSourceProperties> dataSources = new LinkedHashMap<>();

    @NotNull
    private Duration schemaCacheTtl = Duration.ofMinutes(20);

    @Min(10) @Max(10000)
    private int schemaCacheMaxSize = 5000;

    @Min(1) @Max(10000)
    private int tableListMaxRows = 200;

    @Min(1) @Max(64)
    private int schemaFetchParallelism = 4;

    @Min(1) @Max(10000)
    private int schemaQueueCapacity = 200;

    @Min(1) @Max(1000)
    private int schemaBatchMaxTables = 50;

    @NotNull
    private Duration schemaBatchTimeout = Duration.ofSeconds(35);

    @Min(1) @Max(100000)
    private int queryMaxRows = 500;

    @Min(1) @Max(3600)
    private int queryTimeoutSeconds = 30;

    @Min(1) @Max(10000)
    private int queryFetchSize = 100;

    @Min(1024) @Max(1048576)
    private int sqlMaxLength = 65536;

    @Min(1024) @Max(16777216)
    private int resultMaxBytes = 1048576;

    @Min(64) @Max(1048576)
    private int fieldMaxLength = 16384;

    @NotNull
    private Duration pendingSqlTtl = Duration.ofMinutes(10);

    @Min(1) @Max(10000)
    private int pendingSqlMaxSize = 1000;


    private boolean allowDdl = false;


    private boolean allowFullTableWrite = false;


    private boolean auditIncludeSql = false;

    @NotNull @Valid
    private PoolProperties pool = new PoolProperties();

    @NotEmpty
    private Set<String> allowedFunctions = new HashSet<>(List.of("count", "sum", "avg", "min", "max", "coalesce", "nullif",
            "lower", "upper", "length", "char_length", "abs", "round", "ceil", "ceiling", "floor",
            "substring", "substr", "trim", "ltrim", "rtrim", "concat", "replace", "cast", "convert",
            "date_trunc", "date_part", "extract", "to_char", "to_date", "to_timestamp", "now",
            "current_date", "current_timestamp", "getdate", "isnull", "ifnull", "nvl",
            "row_number", "rank", "dense_rank", "lag", "lead", "first_value", "last_value",
            "string_agg", "group_concat", "listagg", "array_agg", "json_agg", "jsonb_agg",
            "greatest", "least", "mod", "power", "sqrt", "dateadd", "datediff", "date_format",
            // Text length, slicing and searching across the supported databases.
            "left", "right", "mid", "substring_index", "split_part", "len", "datalength",
            "character_length", "octet_length", "bit_length", "lengthb", "lengthc", "length2", "length4",
            "substrb", "substrc", "substr2", "substr4", "position", "strpos", "charindex", "patindex",
            "instr", "instrb", "instrc", "instr2", "instr4", "locate", "starts_with",
            // Text cleanup, regular expressions and character construction.
            "btrim", "initcap", "concat_ws", "translate", "reverse", "stuff", "overlay",
            "lpad", "rpad", "repeat", "replicate", "space", "chr", "char", "nchar", "ascii", "unicode",
            "regexp_like", "regexp_count", "regexp_instr", "regexp_substr", "regexp_replace",
            "regexp_match", "regexp_split_to_array", "string_to_array", "array_to_string", "string_escape",
            // Read-only text table functions and Oracle LOB accessors.
            "string_to_table", "regexp_split_to_table", "string_split",
            "dbms_lob.substr", "dbms_lob.getlength", "dbms_lob.instr",
            // PostgreSQL JSONPath inspection and extraction, including timezone-aware variants.
            "jsonb_path_exists", "jsonb_path_match", "jsonb_path_query", "jsonb_path_query_array", "jsonb_path_query_first",
            "jsonb_path_exists_tz", "jsonb_path_match_tz", "jsonb_path_query_tz",
            "jsonb_path_query_array_tz", "jsonb_path_query_first_tz"));

    public String getDefaultDataSource() { return defaultDataSource; }
    public void setDefaultDataSource(String value) { this.defaultDataSource = value; }

    public Map<String, DataSourceProperties> getDataSources() { return dataSources; }
    public void setDataSources(Map<String, DataSourceProperties> value) { this.dataSources = value; }

    public Duration getSchemaCacheTtl() { return schemaCacheTtl; }
    public void setSchemaCacheTtl(Duration value) { this.schemaCacheTtl = value; }

    public int getSchemaCacheMaxSize() { return schemaCacheMaxSize; }
    public void setSchemaCacheMaxSize(int value) { this.schemaCacheMaxSize = value; }

    public int getTableListMaxRows() { return tableListMaxRows; }
    public void setTableListMaxRows(int value) { this.tableListMaxRows = value; }

    public int getSchemaFetchParallelism() { return schemaFetchParallelism; }
    public void setSchemaFetchParallelism(int value) { this.schemaFetchParallelism = value; }

    public int getSchemaQueueCapacity() { return schemaQueueCapacity; }
    public void setSchemaQueueCapacity(int value) { this.schemaQueueCapacity = value; }

    public int getSchemaBatchMaxTables() { return schemaBatchMaxTables; }
    public void setSchemaBatchMaxTables(int value) { this.schemaBatchMaxTables = value; }

    public Duration getSchemaBatchTimeout() { return schemaBatchTimeout; }
    public void setSchemaBatchTimeout(Duration value) { this.schemaBatchTimeout = value; }

    public int getQueryMaxRows() { return queryMaxRows; }
    public void setQueryMaxRows(int value) { this.queryMaxRows = value; }

    public int getQueryTimeoutSeconds() { return queryTimeoutSeconds; }
    public void setQueryTimeoutSeconds(int value) { this.queryTimeoutSeconds = value; }

    public int getQueryFetchSize() { return queryFetchSize; }
    public void setQueryFetchSize(int value) { this.queryFetchSize = value; }

    public int getSqlMaxLength() { return sqlMaxLength; }
    public void setSqlMaxLength(int value) { this.sqlMaxLength = value; }

    public int getResultMaxBytes() { return resultMaxBytes; }
    public void setResultMaxBytes(int value) { this.resultMaxBytes = value; }

    public int getFieldMaxLength() { return fieldMaxLength; }
    public void setFieldMaxLength(int value) { this.fieldMaxLength = value; }

    public Duration getPendingSqlTtl() { return pendingSqlTtl; }
    public void setPendingSqlTtl(Duration value) { this.pendingSqlTtl = value; }

    public int getPendingSqlMaxSize() { return pendingSqlMaxSize; }
    public void setPendingSqlMaxSize(int value) { this.pendingSqlMaxSize = value; }

    public boolean isAllowDdl() { return allowDdl; }
    public void setAllowDdl(boolean value) { this.allowDdl = value; }

    public boolean isAllowFullTableWrite() { return allowFullTableWrite; }
    public void setAllowFullTableWrite(boolean value) { this.allowFullTableWrite = value; }

    public boolean isAuditIncludeSql() { return auditIncludeSql; }
    public void setAuditIncludeSql(boolean value) { this.auditIncludeSql = value; }

    public PoolProperties getPool() { return pool; }
    public void setPool(PoolProperties value) { this.pool = value; }

    public Set<String> getAllowedFunctions() { return allowedFunctions; }
    public void setAllowedFunctions(Set<String> value) { this.allowedFunctions = value; }

    @AssertTrue(message = "缓存和批量请求 TTL / 超时必须大于零")
    public boolean isDurationsValid() {
        return positive(schemaCacheTtl) && positive(pendingSqlTtl) && positive(schemaBatchTimeout);
    }
    private static boolean positive(Duration value) { return value != null && !value.isZero() && !value.isNegative(); }

    public static class DataSourceProperties {

    private DatabaseType type;

    @NotBlank
    private String url;


    private String username;


    private String password;


    private String driverClassName;

    @NotBlank
    private String defaultSchema = "public";


    private List<String> allowedSchemas;


    private boolean allowWrites = false;


    private String validationQuery;

    public DatabaseType getType() { return type; }
    public void setType(DatabaseType value) { this.type = value; }

    public String getUrl() { return url; }
    public void setUrl(String value) { this.url = value; }

    public String getUsername() { return username; }
    public void setUsername(String value) { this.username = value; }

    public String getPassword() { return password; }
    public void setPassword(String value) { this.password = value; }

    public String getDriverClassName() { return driverClassName; }
    public void setDriverClassName(String value) { this.driverClassName = value; }

    public String getDefaultSchema() { return defaultSchema; }
    public void setDefaultSchema(String value) { this.defaultSchema = value; }

    public List<String> getAllowedSchemas() { return allowedSchemas; }
    public void setAllowedSchemas(List<String> value) { this.allowedSchemas = value; }

    public boolean isAllowWrites() { return allowWrites; }
    public void setAllowWrites(boolean value) { this.allowWrites = value; }

    public String getValidationQuery() { return validationQuery; }
    public void setValidationQuery(String value) { this.validationQuery = value; }

    }
    public static class PoolProperties {
    @Min(0)
    private int initialSize = 0;

    @Min(0)
    private int minIdle = 0;

    @Min(1) @Max(200)
    private int maxActive = 20;

    @NotNull
    private Duration maxWait = Duration.ofSeconds(10);

    @NotNull
    private Duration connectTimeout = Duration.ofSeconds(10);

    @NotNull
    private Duration socketTimeout = Duration.ofSeconds(35);


    private String validationQuery;


    private boolean testWhileIdle = true;


    private boolean testOnBorrow = false;


    private boolean testOnReturn = false;

    public int getInitialSize() { return initialSize; }
    public void setInitialSize(int value) { this.initialSize = value; }

    public int getMinIdle() { return minIdle; }
    public void setMinIdle(int value) { this.minIdle = value; }

    public int getMaxActive() { return maxActive; }
    public void setMaxActive(int value) { this.maxActive = value; }

    public Duration getMaxWait() { return maxWait; }
    public void setMaxWait(Duration value) { this.maxWait = value; }

    public Duration getConnectTimeout() { return connectTimeout; }
    public void setConnectTimeout(Duration value) { this.connectTimeout = value; }

    public Duration getSocketTimeout() { return socketTimeout; }
    public void setSocketTimeout(Duration value) { this.socketTimeout = value; }

    public String getValidationQuery() { return validationQuery; }
    public void setValidationQuery(String value) { this.validationQuery = value; }

    public boolean isTestWhileIdle() { return testWhileIdle; }
    public void setTestWhileIdle(boolean value) { this.testWhileIdle = value; }

    public boolean isTestOnBorrow() { return testOnBorrow; }
    public void setTestOnBorrow(boolean value) { this.testOnBorrow = value; }

    public boolean isTestOnReturn() { return testOnReturn; }
    public void setTestOnReturn(boolean value) { this.testOnReturn = value; }

        @AssertTrue(message = "连接池大小、连接等待时间和网络超时无效")
        public boolean isPoolValid() {
            return initialSize <= maxActive && minIdle <= maxActive
                    && positive(maxWait) && positive(connectTimeout) && positive(socketTimeout)
                    && connectTimeout.toMillis() <= Integer.MAX_VALUE && socketTimeout.toMillis() <= Integer.MAX_VALUE;
        }
    }
}
