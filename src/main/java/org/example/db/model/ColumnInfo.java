package org.example.db.model;

/**
 * 字段信息（包含字段备注）。
 * <p>
 * 注意：不同数据库“字段类型/默认值/备注”取值方式不同；已统一 JDBC 数值读取方式。
 */
public record ColumnInfo(
        int ordinalPosition,
        String name,
        String dataType,
        String udtName,
        Long characterMaximumLength,
        Integer numericPrecision,
        Integer numericScale,
        boolean nullable,
        String columnDefault,
        String comment
) {
}

