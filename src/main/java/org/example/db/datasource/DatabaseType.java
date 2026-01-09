package org.example.db.datasource;

/**
 * 数据库类型枚举。
 * <p>
 * 用途：
 * <ul>
 *   <li>选择不同数据库的“元数据查询 SQL”（表/字段/备注等）</li>
 *   <li>对某些数据库做细微差异的兼容处理</li>
 * </ul>
 */
public enum DatabaseType {

    POSTGRESQL,
    MYSQL,
    ORACLE,
    SQLSERVER,
    UNKNOWN
}

