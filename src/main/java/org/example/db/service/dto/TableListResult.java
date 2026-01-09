package org.example.db.service.dto;

import org.example.db.model.TableInfo;

import java.util.List;

/**
 * 表/视图列表（带分页信息）。
 * <p>
 * 说明：当 schema 下对象非常多（上千张表）时，必须分页/筛选，否则：
 * <ul>
 *   <li>数据库查询本身会慢</li>
 *   <li>MCP 返回体过大，AI 侧处理效率也会下降</li>
 * </ul>
 */
public record TableListResult(
        String dataSourceId,
        String schema,
        String keyword,
        int limit,
        int offset,
        int returned,
        boolean hasMore,
        Integer nextOffset,
        List<TableInfo> tables
) {
}

