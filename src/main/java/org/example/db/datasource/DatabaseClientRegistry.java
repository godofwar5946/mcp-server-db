package org.example.db.datasource;

import com.alibaba.druid.pool.DruidDataSource;
import org.example.db.config.DbExplorerProperties;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 多数据源注册表：按 dataSourceId 管理多个不同类型的数据库连接。
 * <p>
 * 注意：
 * <ul>
 *   <li>数据源信息来自 application.yml（app.db.data-sources.*）</li>
 *   <li>这里不在启动阶段强制连通性检查：避免“某个备用库不可用导致服务无法启动”</li>
 *   <li>数据库类型识别（DatabaseType）在首次需要方言能力时再做（惰性）</li>
 * </ul>
 */
public class DatabaseClientRegistry {

    private final DbExplorerProperties properties;
    private final Map<String, DatabaseClient> clients;
    private final Map<String, DatabaseType> resolvedTypes = new LinkedHashMap<>();

    public DatabaseClientRegistry(DbExplorerProperties properties) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.clients = Collections.unmodifiableMap(createClients(properties));
    }

    @PreDestroy
    public void close() {
        // 关闭连接池，避免应用停止后仍持有连接
        for (DatabaseClient client : clients.values()) {
            try {
                DataSource ds = client.dataSource();
                if (ds instanceof AutoCloseable closeable) {
                    closeable.close();
                }
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * 列出所有配置的数据源 ID（用于给 MCP 客户端展示/选择）。
     */
    public List<String> listDataSourceIds() {
        return new ArrayList<>(clients.keySet());
    }

    /**
     * 获取指定 dataSourceId 的 client。
     */
    public DatabaseClient getClient(String dataSourceId) {
        String resolved = resolveDataSourceId(dataSourceId);
        DatabaseClient client = clients.get(resolved);
        if (client == null) {
            throw new IllegalArgumentException("未知的数据源: " + resolved + "，已配置数据源=" + clients.keySet());
        }
        return client;
    }

    /**
     * 解析 dataSourceId：为空则使用默认值。
     */
    public String resolveDataSourceId(String requested) {
        return (requested == null || requested.isBlank()) ? properties.getDefaultDataSource() : requested;
    }

    /**
     * 获取某个数据源的数据库类型：
     * <ul>
     *   <li>如果 app.db.data-sources.{id}.type 配置了，则直接使用</li>
     *   <li>否则通过 JDBC 元数据自动识别并缓存</li>
     * </ul>
     */
    public synchronized DatabaseType resolveDatabaseType(String dataSourceId) {
        String resolvedId = resolveDataSourceId(dataSourceId);
        DatabaseType cached = resolvedTypes.get(resolvedId);
        if (cached != null) {
            return cached;
        }

        DbExplorerProperties.DataSourceProperties cfg = properties.getDataSources().get(resolvedId);
        if (cfg == null) {
            throw new IllegalArgumentException("未知的数据源: " + resolvedId + "，已配置数据源=" + properties.getDataSources().keySet());
        }

        DatabaseType type = cfg.getType();
        if (type == null || type == DatabaseType.UNKNOWN) {
            DatabaseClient client = getClient(resolvedId);
            type = DatabaseTypeDetector.detect(client.dataSource());
        }
        resolvedTypes.put(resolvedId, type);
        return type;
    }

    /**
     * 供 Spring Boot / MyBatis 等框架使用的“主数据源”（默认数据源）。
     * <p>
     * 说明：本项目的 MCP 逻辑走 Registry 自己选择数据源；这里提供主数据源主要是为了兼容
     * 一些依赖 DataSource Bean 的自动装配（例如 mybatis-plus）。
     */
    public DataSource getPrimaryDataSource() {
        return getClient(properties.getDefaultDataSource()).dataSource();
    }

    public DbExplorerProperties.DataSourceProperties getDataSourceConfig(String dataSourceId) {
        String resolvedId = resolveDataSourceId(dataSourceId);
        DbExplorerProperties.DataSourceProperties cfg = properties.getDataSources().get(resolvedId);
        if (cfg == null) {
            throw new IllegalArgumentException("未知的数据源: " + resolvedId);
        }
        return cfg;
    }

    private Map<String, DatabaseClient> createClients(DbExplorerProperties properties) {
        Map<String, DbExplorerProperties.DataSourceProperties> dataSources = properties.getDataSources();
        if (dataSources == null || dataSources.isEmpty()) {
            throw new IllegalStateException("未配置任何数据源，请在 application.yml 配置 app.db.data-sources");
        }
        String defaultId = properties.getDefaultDataSource();
        if (!dataSources.containsKey(defaultId)) {
            throw new IllegalStateException("默认数据源不存在: app.db.default-data-source=" + defaultId + "，已配置数据源=" + dataSources.keySet());
        }

        Map<String, DatabaseClient> map = new LinkedHashMap<>();
        for (Map.Entry<String, DbExplorerProperties.DataSourceProperties> entry : dataSources.entrySet()) {
            String id = entry.getKey();
            DbExplorerProperties.DataSourceProperties cfg = entry.getValue();
            DataSource ds = createDruidDataSource(cfg, properties.getPool());
            JdbcTemplate jdbcTemplate = new JdbcTemplate(ds);
            // 统一通过 JDBC Statement#setMaxRows 控制最大返回行数（跨数据库通用）
            jdbcTemplate.setMaxRows(properties.getQueryMaxRows() + 1);
            map.put(id, new DatabaseClient(id, ds, jdbcTemplate));
        }
        return map;
    }

    private DataSource createDruidDataSource(DbExplorerProperties.DataSourceProperties cfg, DbExplorerProperties.PoolProperties pool) {
        DruidDataSource ds = new DruidDataSource();
        ds.setUrl(cfg.getUrl());
        ds.setUsername(cfg.getUsername());
        ds.setPassword(cfg.getPassword());
        if (cfg.getDriverClassName() != null && !cfg.getDriverClassName().isBlank()) {
            ds.setDriverClassName(cfg.getDriverClassName());
        }

        ds.setInitialSize(pool.getInitialSize());
        ds.setMinIdle(pool.getMinIdle());
        ds.setMaxActive(pool.getMaxActive());
        ds.setMaxWait(pool.getMaxWait().toMillis());
        ds.setValidationQuery(pool.getValidationQuery());
        ds.setTestWhileIdle(pool.isTestWhileIdle());
        ds.setTestOnBorrow(pool.isTestOnBorrow());
        ds.setTestOnReturn(pool.isTestOnReturn());
        return ds;
    }
}
