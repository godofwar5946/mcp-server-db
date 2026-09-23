package org.example.db.dialect;

import org.example.db.datasource.DatabaseType;
import org.example.db.model.ColumnInfo;
import org.example.db.util.JdbcValues;
import org.example.db.model.TableInfo;
import org.example.db.model.TableSchema;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * Oracle 方言：读取表结构/备注等元数据。
 * <p>
 * 说明：
 * <ul>
 *   <li>表备注：ALL_TAB_COMMENTS.COMMENTS</li>
 *   <li>字段备注：ALL_COL_COMMENTS.COMMENTS</li>
 *   <li>字段类型/默认值/是否可空：ALL_TAB_COLUMNS</li>
 * </ul>
 * <p>
 * 注意：Oracle 的数据字典通常使用大写对象名（未加引号创建的对象），元数据工具使用实际对象名（普通对象通常应传大写）。
 */
@Component
public class OracleDialect implements DatabaseDialect {

    @Override
    public DatabaseType getType() {
        return DatabaseType.ORACLE;
    }

    @Override
    public List<TableInfo> listTables(JdbcTemplate jdbcTemplate, String schema, String keyword, int limit, int offset, boolean includeComments) {
        String owner = normalizeOwner(schema);
        String commentSelect = includeComments ? "tc.comments" : "NULL";

        StringBuilder sql = new StringBuilder();
        sql.append("""
                SELECT
                  tc.table_name,
                  tc.table_type,
                """);
        sql.append("  ").append(commentSelect).append(" AS table_comment\n");
        sql.append("""
                FROM all_tab_comments tc
                WHERE tc.owner = ?
                  AND tc.table_type IN ('TABLE','VIEW')
                """);

        List<Object> params = new java.util.ArrayList<>();
        params.add(owner);

        if (keyword != null && !keyword.isBlank()) {
            sql.append("  AND tc.table_name LIKE ?\n");
            params.add("%" + keyword.toUpperCase(Locale.ROOT) + "%");
        }

        sql.append("""
                ORDER BY tc.table_name
                OFFSET ? ROWS FETCH NEXT ? ROWS ONLY
                """);
        params.add(offset);
        params.add(limit);

        return jdbcTemplate.query(sql.toString(), (rs, rowNum) -> new TableInfo(
                owner,
                rs.getString("table_name"),
                normalizeType(rs.getString("table_type")),
                rs.getString("table_comment")
        ), params.toArray());
    }

    @Override
    public TableSchema getTableSchema(JdbcTemplate jdbcTemplate, String schema, String table) {
        String owner = normalizeOwner(schema);
        String tableName = normalizeObjectName(table);

        TableInfo tableInfo = findTableInfo(jdbcTemplate, owner, tableName);
        if (tableInfo == null) {
            throw new IllegalArgumentException("表不存在: " + owner + "." + tableName);
        }

        var columnSql = """
                SELECT
                  c.column_id AS ordinal_position,
                  c.column_name,
                  c.data_type,
                  c.data_type AS udt_name,
                  c.char_length AS character_maximum_length,
                  c.data_precision AS numeric_precision,
                  c.data_scale AS numeric_scale,
                  c.nullable AS is_nullable,
                  c.data_default AS column_default,
                  cc.comments AS column_comment
                FROM all_tab_columns c
                LEFT JOIN all_col_comments cc
                  ON cc.owner = c.owner
                 AND cc.table_name = c.table_name
                 AND cc.column_name = c.column_name
                WHERE c.owner = ?
                  AND c.table_name = ?
                ORDER BY c.column_id
                """;

        List<ColumnInfo> columns = jdbcTemplate.query(columnSql, (rs, rowNum) -> new ColumnInfo(
                rs.getInt("ordinal_position"),
                rs.getString("column_name"),
                rs.getString("data_type"),
                rs.getString("udt_name"),
                JdbcValues.nullableLong(rs, "character_maximum_length"),
                JdbcValues.nullableInt(rs, "numeric_precision"),
                JdbcValues.nullableInt(rs, "numeric_scale"),
                "Y".equalsIgnoreCase(rs.getString("is_nullable")),
                rs.getString("column_default"),
                rs.getString("column_comment")
        ), owner, tableName);

        return new TableSchema(tableInfo, columns);
    }

    private TableInfo findTableInfo(JdbcTemplate jdbcTemplate, String owner, String tableName) {
        var sql = """
                SELECT
                  tc.table_name,
                  tc.table_type,
                  tc.comments AS table_comment
                FROM all_tab_comments tc
                WHERE tc.owner = ?
                  AND tc.table_name = ?
                  AND tc.table_type IN ('TABLE','VIEW')
                """;
        var list = jdbcTemplate.query(sql, (rs, rowNum) -> new TableInfo(
                owner,
                rs.getString("table_name"),
                normalizeType(rs.getString("table_type")),
                rs.getString("table_comment")
        ), owner, tableName);
        return list.isEmpty() ? null : list.getFirst();
    }

    private String normalizeOwner(String schema) {
        if (schema == null) {
            return null;
        }
        return schema;
    }

    private String normalizeObjectName(String objectName) {
        if (objectName == null) {
            return null;
        }
        return objectName;
    }

    private String normalizeType(String tableType) {
        if (tableType == null) {
            return null;
        }
        String t = tableType.toUpperCase(Locale.ROOT);
        if ("TABLE".equals(t)) {
            return "table";
        }
        if ("VIEW".equals(t)) {
            return "view";
        }
        return tableType;
    }
}
