package org.example.db.dialect;

import org.example.db.datasource.DatabaseType;
import org.example.db.model.ColumnInfo;
import org.example.db.util.JdbcValues;
import org.example.db.model.TableInfo;
import org.example.db.model.TableSchema;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/**
 * PostgreSQL 方言：读取表结构/备注等元数据。
 * <p>
 * 说明：
 * <ul>
 *   <li>表备注：obj_description(pg_class.oid)</li>
 *   <li>字段备注：pg_description + pg_attribute.attnum</li>
 * </ul>
 */
@Component
public class PostgresDialect implements DatabaseDialect {

    @Override
    public DatabaseType getType() {
        return DatabaseType.POSTGRESQL;
    }

    @Override
    public List<TableInfo> listTables(JdbcTemplate jdbcTemplate, String schema, String keyword, int limit, int offset, boolean includeComments) {
        String commentSelect = includeComments ? "obj_description(c.oid, 'pg_class')" : "NULL";

        StringBuilder sql = new StringBuilder();
        sql.append("""
                SELECT
                  c.relname AS table_name,
                  CASE c.relkind
                    WHEN 'r' THEN 'table'
                    WHEN 'p' THEN 'partitioned_table'
                    WHEN 'v' THEN 'view'
                    WHEN 'm' THEN 'materialized_view'
                    WHEN 'f' THEN 'foreign_table'
                    ELSE c.relkind::text
                  END AS table_type,
                """);
        sql.append("  ").append(commentSelect).append(" AS table_comment\n");
        sql.append("""
                FROM pg_catalog.pg_class c
                JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = ?
                  AND c.relkind IN ('r','p','v','m','f')
                """);

        List<Object> params = new java.util.ArrayList<>();
        params.add(schema);

        if (keyword != null && !keyword.isBlank()) {
            sql.append("  AND c.relname ILIKE ?\n");
            params.add("%" + keyword + "%");
        }

        sql.append("""
                ORDER BY c.relname
                LIMIT ? OFFSET ?
                """);
        params.add(limit);
        params.add(offset);

        return jdbcTemplate.query(sql.toString(), (rs, rowNum) -> new TableInfo(
                schema,
                rs.getString("table_name"),
                rs.getString("table_type"),
                rs.getString("table_comment")
        ), params.toArray());
    }

    @Override
    public TableSchema getTableSchema(JdbcTemplate jdbcTemplate, String schema, String table) {
        TableInfo tableInfo = findTableInfo(jdbcTemplate, schema, table);
        if (tableInfo == null) {
            throw new IllegalArgumentException("表不存在: " + schema + "." + table);
        }

        // 字段结构：information_schema 负责类型/默认值等，pg_catalog 负责字段备注。
        var columnSql = """
                SELECT
                  cols.ordinal_position,
                  cols.column_name,
                  cols.data_type,
                  cols.udt_name,
                  cols.character_maximum_length,
                  cols.numeric_precision,
                  cols.numeric_scale,
                  cols.is_nullable,
                  cols.column_default,
                  pgd.description AS column_comment
                FROM information_schema.columns cols
                JOIN pg_catalog.pg_class c
                  ON c.relname = cols.table_name
                JOIN pg_catalog.pg_namespace n
                  ON n.oid = c.relnamespace AND n.nspname = cols.table_schema
                JOIN pg_catalog.pg_attribute a
                  ON a.attrelid = c.oid AND a.attname = cols.column_name
                LEFT JOIN pg_catalog.pg_description pgd
                  ON pgd.objoid = c.oid AND pgd.objsubid = a.attnum
                WHERE cols.table_schema = ?
                  AND cols.table_name = ?
                ORDER BY cols.ordinal_position
                """;

        List<ColumnInfo> columns = jdbcTemplate.query(columnSql, (rs, rowNum) -> new ColumnInfo(
                rs.getInt("ordinal_position"),
                rs.getString("column_name"),
                rs.getString("data_type"),
                rs.getString("udt_name"),
                JdbcValues.nullableLong(rs, "character_maximum_length"),
                JdbcValues.nullableInt(rs, "numeric_precision"),
                JdbcValues.nullableInt(rs, "numeric_scale"),
                Objects.equals("YES", rs.getString("is_nullable")),
                rs.getString("column_default"),
                rs.getString("column_comment")
        ), schema, table);

        return new TableSchema(tableInfo, columns);
    }

    private TableInfo findTableInfo(JdbcTemplate jdbcTemplate, String schema, String table) {
        var sql = """
                SELECT
                  c.relname AS table_name,
                  CASE c.relkind
                    WHEN 'r' THEN 'table'
                    WHEN 'p' THEN 'partitioned_table'
                    WHEN 'v' THEN 'view'
                    WHEN 'm' THEN 'materialized_view'
                    WHEN 'f' THEN 'foreign_table'
                    ELSE c.relkind::text
                  END AS table_type,
                  obj_description(c.oid, 'pg_class') AS table_comment
                FROM pg_catalog.pg_class c
                JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = ?
                  AND c.relname = ?
                  AND c.relkind IN ('r','p','v','m','f')
                """;
        var list = jdbcTemplate.query(sql, (rs, rowNum) -> new TableInfo(
                schema,
                rs.getString("table_name"),
                rs.getString("table_type"),
                rs.getString("table_comment")
        ), schema, table);
        return list.isEmpty() ? null : list.getFirst();
    }
}
