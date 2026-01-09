package org.example.db.sql;

/**
 * SQL 大类（用于决定是否需要“确认”）。
 */
public enum SqlCategory {

    /**
     * 只读查询（不需要确认）。
     */
    READ,

    /**
     * 写入 DML（必须确认后执行）。
     */
    WRITE_DML,

    /**
     * DDL（是否允许由配置控制；允许时也必须确认后执行）。
     */
    DDL,

    /**
     * 未识别/不支持（默认拒绝执行）。
     */
    OTHER
}

