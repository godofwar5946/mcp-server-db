package org.example.db.service.dto;

import org.example.db.datasource.DatabaseType;

import java.util.List;

/**
 * 数据源信息（给 MCP 客户端展示用）。
 * <p>
 * 注意：不会返回密码。
 */
public record DataSourceInfo(
        String id,
        DatabaseType configuredType,
        DatabaseType resolvedType,
        String url,
        String username,
        String defaultSchema,
        List<String> allowedSchemas
) {
}

