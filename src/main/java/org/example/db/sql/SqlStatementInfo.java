package org.example.db.sql;

/**
 * SQL 语句分析结果。
 *
 * @param mainKeyword 语句主关键字（例如 SELECT/UPDATE/...）
 * @param category    SQL 大类
 */
public record SqlStatementInfo(String mainKeyword, SqlCategory category) {
}

