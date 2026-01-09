package org.example.db.sql;

/**
 * SQL 中引用到的表（schema 可为空，表示 defaultSchema）。
 */
public record TableRef(String schema, String table) {
}

