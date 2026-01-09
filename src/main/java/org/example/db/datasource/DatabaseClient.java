package org.example.db.datasource;

import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * 单个数据源的运行时对象封装。
 *
 * @param id           数据源 ID（MCP 入参 dataSourceId 对应）
 * @param dataSource   JDBC DataSource
 * @param jdbcTemplate JdbcTemplate（执行 SQL 使用）
 */
public record DatabaseClient(
        String id,
        DataSource dataSource,
        JdbcTemplate jdbcTemplate
) {
}

