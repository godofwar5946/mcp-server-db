package org.example.db.model;

import java.util.List;

/**
 * 表结构：包含表信息 + 字段列表。
 */
public record TableSchema(
        TableInfo table,
        List<ColumnInfo> columns
) {
}

