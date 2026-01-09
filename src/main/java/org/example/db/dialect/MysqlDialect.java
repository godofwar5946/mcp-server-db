package org.example.db.dialect;

import org.example.db.datasource.DatabaseType;
import org.example.db.model.ColumnInfo;
import org.example.db.model.TableInfo;
import org.example.db.model.TableSchema;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * MySQL 方言：读取表结构/备注等元数据。
 * <p>
 * 说明：
 * <ul>
 *   <li>表备注：information_schema.tables.table_comment</li>
 *   <li>字段备注：information_schema.columns.column_comment</li>
 * </ul>
 */
@Component
public class MysqlDialect implements DatabaseDialect {

    @Override
    public DatabaseType getType() {
        return DatabaseType.MYSQL;
    }

    @Override
    public List<TableInfo> listTables(JdbcTemplate jdbcTemplate, String schema, String keyword, int limit, int offset, boolean includeComments) {
        String commentSelect = includeComments ? "t.table_comment" : "NULL";

        StringBuilder sql = new StringBuilder();
        sql.append("""
                SELECT
                  t.table_name,
                  t.table_type,
                """);
        sql.append("  ").append(commentSelect).append(" AS table_comment\n");
        sql.append("""
                FROM information_schema.tables t
                WHERE t.table_schema = ?
                """);

        List<Object> params = new java.util.ArrayList<>();
        params.add(schema);

        if (keyword != null && !keyword.isBlank()) {
            sql.append("  AND t.table_name LIKE ?\n");
            params.add("%" + keyword + "%");
        }

        sql.append("""
                ORDER BY t.table_name
                LIMIT ? OFFSET ?
                """);
        params.add(limit);
        params.add(offset);

        return jdbcTemplate.query(sql.toString(), (rs, rowNum) -> new TableInfo(
                schema,
                rs.getString("table_name"),
                normalizeType(rs.getString("table_type")),
                rs.getString("table_comment")
        ), params.toArray());
    }

    @Override
    public TableSchema getTableSchema(JdbcTemplate jdbcTemplate, String schema, String table) {
        TableInfo tableInfo = findTableInfo(jdbcTemplate, schema, table);
        if (tableInfo == null) {
            throw new IllegalArgumentException("表不存在: " + schema + "." + table);
        }

        var columnSql = """
                SELECT
                  c.ordinal_position,
                  c.column_name,
                  c.data_type,
                  c.column_type,
                  c.character_maximum_length,
                  c.numeric_precision,
                  c.numeric_scale,
                  c.is_nullable,
                  c.column_default,
                  c.column_comment
                FROM information_schema.columns c
                WHERE c.table_schema = ?
                  AND c.table_name = ?
                ORDER BY c.ordinal_position
                """;

        List<ColumnInfo> columns = jdbcTemplate.query(columnSql, (rs, rowNum) -> new ColumnInfo(
                rs.getInt("ordinal_position"),
                rs.getString("column_name"),
                rs.getString("data_type"),
                rs.getString("column_type"),
                (Integer) rs.getObject("character_maximum_length"),
                (Integer) rs.getObject("numeric_precision"),
                (Integer) rs.getObject("numeric_scale"),
                Objects.equals("YES", rs.getString("is_nullable")),
                rs.getString("column_default"),
                rs.getString("column_comment")
        ), schema, table);

        return new TableSchema(tableInfo, columns);
    }

    private TableInfo findTableInfo(JdbcTemplate jdbcTemplate, String schema, String table) {
        var sql = """
                SELECT
                  t.table_name,
                  t.table_type,
                  t.table_comment
                FROM information_schema.tables t
                WHERE t.table_schema = ?
                  AND t.table_name = ?
                """;
        var list = jdbcTemplate.query(sql, (rs, rowNum) -> new TableInfo(
                schema,
                rs.getString("table_name"),
                normalizeType(rs.getString("table_type")),
                rs.getString("table_comment")
        ), schema, table);
        return list.isEmpty() ? null : list.getFirst();
    }

    private String normalizeType(String tableType) {
        if (tableType == null) {
            return null;
        }
        String t = tableType.toUpperCase(Locale.ROOT);
        if ("BASE TABLE".equals(t)) {
            return "table";
        }
        if ("VIEW".equals(t)) {
            return "view";
        }
        return tableType;
    }
}
