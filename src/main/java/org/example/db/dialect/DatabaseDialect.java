package org.example.db.dialect;

import org.example.db.datasource.DatabaseType;
import org.example.db.model.TableInfo;
import org.example.db.model.TableSchema;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/**
 * 数据库方言：用于“表结构元数据”的差异化查询。
 * <p>
 * 说明：
 * <ul>
 *   <li>不同数据库对“表备注/字段备注/默认值”等元数据存储位置不同</li>
 *   <li>本接口只负责读取元数据，不负责执行用户 SQL</li>
 * </ul>
 */
public interface DatabaseDialect {

    /**
     * 方言对应的数据库类型。
     */
    DatabaseType getType();

    /**
     * 列出 schema 下的表/视图（包含备注）。
     */
    List<TableInfo> listTables(JdbcTemplate jdbcTemplate, String schema, String keyword, int limit, int offset, boolean includeComments);

    /**
     * 获取单表结构（包含字段备注）。
     */
    TableSchema getTableSchema(JdbcTemplate jdbcTemplate, String schema, String table);
}
