package org.example.db.model;

/**
 * 表的基础信息（表名/类型/备注）。
 */
public record TableInfo(
        String schema,
        String name,
        String type,
        String comment
) {
}

