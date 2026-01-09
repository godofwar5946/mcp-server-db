package org.example.db.audit;

/**
 * SQL 审计动作类型。
 * <p>
 * 说明：用于区分“查询/写入准备/写入执行/取消”等不同操作，方便后续检索与追溯。
 */
public enum SqlAuditAction {

    /**
     * 只读查询（SELECT）。
     */
    QUERY,

    /**
     * 写入 SQL 的预执行（仅生成 token/回显 SQL，不真正执行）。
     */
    WRITE_PREPARE,

    /**
     * 写入 SQL 的确认执行（confirm=true 后真正执行）。
     */
    WRITE_EXECUTE,

    /**
     * 写入 SQL 的取消（confirm=false）。
     */
    WRITE_CANCEL
}

