package org.example.db.dialect;

import org.example.db.datasource.DatabaseType;
import org.example.db.model.ColumnInfo;
import org.example.db.model.TableInfo;
import org.example.db.model.TableSchema;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * SQLServer 方言：读取表结构/备注等元数据。
 * <p>
 * 说明：
 * <ul>
 *   <li>表/字段备注：sys.extended_properties（常见使用 MS_Description）</li>
 *   <li>字段类型/默认值/是否可空：sys.columns + sys.types + sys.default_constraints</li>
 * </ul>
 */
@Component
public class SqlServerDialect implements DatabaseDialect {

    @Override
    public DatabaseType getType() {
        return DatabaseType.SQLSERVER;
    }

    @Override
    public List<TableInfo> listTables(JdbcTemplate jdbcTemplate, String schema, String keyword, int limit, int offset, boolean includeComments) {
        String commentSelect = includeComments ? "CAST(ep.value AS NVARCHAR(4000))" : "CAST(NULL AS NVARCHAR(4000))";

        StringBuilder sql = new StringBuilder();
        sql.append("""
                SELECT
                  s.name AS schema_name,
                  o.name AS table_name,
                  o.type AS object_type,
                """);
        sql.append("  ").append(commentSelect).append(" AS table_comment\n");
        sql.append("""
                FROM sys.objects o
                JOIN sys.schemas s ON s.schema_id = o.schema_id
                """);
        if (includeComments) {
            sql.append("""
                LEFT JOIN sys.extended_properties ep
                  ON ep.major_id = o.object_id AND ep.minor_id = 0 AND ep.name = 'MS_Description'
                """);
        }
        sql.append("""
                WHERE s.name = ?
                  AND o.type IN ('U','V')
                """);

        List<Object> params = new java.util.ArrayList<>();
        params.add(schema);

        if (keyword != null && !keyword.isBlank()) {
            sql.append("  AND o.name LIKE ?\n");
            params.add("%" + keyword + "%");
        }

        sql.append("""
                ORDER BY o.name
                OFFSET ? ROWS FETCH NEXT ? ROWS ONLY
                """);
        params.add(offset);
        params.add(limit);

        return jdbcTemplate.query(sql.toString(), (rs, rowNum) -> new TableInfo(
                rs.getString("schema_name"),
                rs.getString("table_name"),
                normalizeType(rs.getString("object_type")),
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
                  c.column_id AS ordinal_position,
                  c.name AS column_name,
                  t.name AS data_type,
                  t.name AS udt_name,
                  c.max_length AS character_maximum_length,
                  c.precision AS numeric_precision,
                  c.scale AS numeric_scale,
                  c.is_nullable AS is_nullable,
                  dc.definition AS column_default,
                  CAST(ep.value AS NVARCHAR(4000)) AS column_comment
                FROM sys.objects o
                JOIN sys.schemas s ON s.schema_id = o.schema_id
                JOIN sys.columns c ON c.object_id = o.object_id
                JOIN sys.types t ON t.user_type_id = c.user_type_id
                LEFT JOIN sys.default_constraints dc
                  ON dc.parent_object_id = c.object_id AND dc.parent_column_id = c.column_id
                LEFT JOIN sys.extended_properties ep
                  ON ep.major_id = c.object_id AND ep.minor_id = c.column_id AND ep.name = 'MS_Description'
                WHERE s.name = ?
                  AND o.name = ?
                  AND o.type IN ('U','V')
                ORDER BY c.column_id
                """;

        List<ColumnInfo> columns = jdbcTemplate.query(columnSql, (rs, rowNum) -> {
            Integer rawMaxLen = (Integer) rs.getObject("character_maximum_length");
            Integer normalizedMaxLen = normalizeMaxLength(rs.getString("data_type"), rawMaxLen);

            Integer nullableInt = (Integer) rs.getObject("is_nullable");
            boolean nullable = nullableInt != null && nullableInt != 0;

            return new ColumnInfo(
                    rs.getInt("ordinal_position"),
                    rs.getString("column_name"),
                    rs.getString("data_type"),
                    rs.getString("udt_name"),
                    normalizedMaxLen,
                    (Integer) rs.getObject("numeric_precision"),
                    (Integer) rs.getObject("numeric_scale"),
                    nullable,
                    rs.getString("column_default"),
                    rs.getString("column_comment")
            );
        }, schema, table);

        return new TableSchema(tableInfo, columns);
    }

    private TableInfo findTableInfo(JdbcTemplate jdbcTemplate, String schema, String table) {
        var sql = """
                SELECT
                  s.name AS schema_name,
                  o.name AS table_name,
                  o.type AS object_type,
                  CAST(ep.value AS NVARCHAR(4000)) AS table_comment
                FROM sys.objects o
                JOIN sys.schemas s ON s.schema_id = o.schema_id
                LEFT JOIN sys.extended_properties ep
                  ON ep.major_id = o.object_id AND ep.minor_id = 0 AND ep.name = 'MS_Description'
                WHERE s.name = ?
                  AND o.name = ?
                  AND o.type IN ('U','V')
                """;
        var list = jdbcTemplate.query(sql, (rs, rowNum) -> new TableInfo(
                rs.getString("schema_name"),
                rs.getString("table_name"),
                normalizeType(rs.getString("object_type")),
                rs.getString("table_comment")
        ), schema, table);
        return list.isEmpty() ? null : list.getFirst();
    }

    private String normalizeType(String objectType) {
        if (objectType == null) {
            return null;
        }
        String t = objectType.toUpperCase(Locale.ROOT);
        return switch (t) {
            case "U" -> "table";
            case "V" -> "view";
            default -> objectType;
        };
    }

    /**
     * SQLServer 的 max_length 是“字节长度”，nvarchar/nchar 需要 /2 才是字符长度。
     * <p>
     * 另外：max_length = -1 表示 MAX（此处返回 null 代表不确定/不限制）。
     */
    private Integer normalizeMaxLength(String dataType, Integer rawMaxLength) {
        if (rawMaxLength == null) {
            return null;
        }
        if (rawMaxLength < 0) {
            return null;
        }
        if (dataType == null) {
            return rawMaxLength;
        }
        String t = dataType.toLowerCase(Locale.ROOT);
        if (t.contains("nvarchar") || t.contains("nchar")) {
            return rawMaxLength / 2;
        }
        return rawMaxLength;
    }
}
