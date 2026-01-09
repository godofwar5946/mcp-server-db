package org.example.db.service.dto;

import org.example.db.model.TableSchema;

/**
 * 批量获取表结构的单项结果。
 */
public record TableSchemaBatchItem(
        String table,
        boolean success,
        TableSchema schema,
        String errorMessage
) {
}

