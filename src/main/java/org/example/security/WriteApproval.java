package org.example.security;

import org.example.db.service.PendingSqlStore;
import org.springframework.stereotype.Component;

@Component
public class WriteApproval {
    private final PendingSqlStore store;
    private final AccessProperties properties;
    public WriteApproval(PendingSqlStore store, AccessProperties properties) {
        this.store = store;
        this.properties = properties;
    }

    public void requireApproval(PendingSqlStore.PendingSql pending, String approvalKey) {
        if (properties.getApprovalKey().isBlank())
            throw new SecurityException("服务端未配置 approval-key，无法批准写入");
        if (pending.approved()) return;
        if (!McpAccessFilter.matches(properties.getApprovalKey(), approvalKey))
            throw new SecurityException("审批密钥缺失或不正确；请提供 approvalKey，或通过独立审批接口批准后再调用 confirm");
        store.approve(pending.token(), pending.sqlHash());
    }
}
