package org.example.db.service.dto;

import java.util.List;

/**
 * 批量获取表结构结果（用于减少 MCP 往返次数，提高 AI 使用效率）。
 */
public record TableSchemaBatchResult(
        String dataSourceId,
        String schema,
        int requestedTables,
        int succeeded,
        int failed,
        boolean refresh,
        long durationMs,
        List<TableSchemaBatchItem> items
) {
}

