package org.example.db.datasource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.util.Locale;

/**
 * 数据库类型识别工具：通过 JDBC 元数据自动识别。
 */
public final class DatabaseTypeDetector {

    private DatabaseTypeDetector() {
    }

    public static DatabaseType detect(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metaData = connection.getMetaData();
            String productName = metaData.getDatabaseProductName();
            if (productName == null) {
                return DatabaseType.UNKNOWN;
            }
            String normalized = productName.toLowerCase(Locale.ROOT);
            if (normalized.contains("postgresql")) {
                return DatabaseType.POSTGRESQL;
            }
            // MySQL 驱动返回 "MySQL"，MariaDB 可能返回 "MariaDB"
            if (normalized.contains("mysql") || normalized.contains("mariadb")) {
                return DatabaseType.MYSQL;
            }
            if (normalized.contains("oracle")) {
                return DatabaseType.ORACLE;
            }
            if (normalized.contains("microsoft sql server") || normalized.contains("sql server")) {
                return DatabaseType.SQLSERVER;
            }
            return DatabaseType.UNKNOWN;
        } catch (Exception e) {
            // 不在此处吞异常：上层决定是启动失败还是延迟到首次调用失败
            throw new IllegalStateException("无法识别数据库类型（请检查连接信息/网络/账号权限）: " + e.getMessage(), e);
        }
    }
}

