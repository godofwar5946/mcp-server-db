package org.example.db.service;

import org.example.db.sql.SqlStatementInfo;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 待确认 SQL 存储（内存版）。
 * <p>
 * 工作流：
 * <ol>
 *   <li>prepare：生成 token + 保存 SQL</li>
 *   <li>confirm：用户确认后，用 token 取出 SQL 执行</li>
 * </ol>
 * <p>
 * 说明：
 * <ul>
 *   <li>为简单起见，这里用内存实现；如果你需要多实例部署，请替换为 Redis。</li>
 *   <li>每个 token 有 TTL，过期后自动失效。</li>
 * </ul>
 */
public class PendingSqlStore {

    private final Duration ttl;
    private final ConcurrentHashMap<String, PendingSql> store = new ConcurrentHashMap<>();

    public PendingSqlStore(Duration ttl) {
        this.ttl = ttl;
    }

    public PendingSql create(String dataSourceId, String sql, SqlStatementInfo statementInfo) {
        cleanupExpired();
        String token = UUID.randomUUID().toString();
        Instant now = Instant.now();
        PendingSql pendingSql = new PendingSql(token, dataSourceId, sql, statementInfo, now, now.plus(ttl));
        store.put(token, pendingSql);
        return pendingSql;
    }

    public PendingSql get(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        PendingSql pendingSql = store.get(token);
        if (pendingSql == null) {
            return null;
        }
        if (pendingSql.isExpired()) {
            store.remove(token);
            return null;
        }
        return pendingSql;
    }

    public PendingSql remove(String token) {
        PendingSql pendingSql = store.remove(token);
        if (pendingSql == null) {
            return null;
        }
        return pendingSql.isExpired() ? null : pendingSql;
    }

    private void cleanupExpired() {
        Instant now = Instant.now();
        for (Map.Entry<String, PendingSql> entry : store.entrySet()) {
            PendingSql value = entry.getValue();
            if (value.expiresAt().isBefore(now)) {
                store.remove(entry.getKey());
            }
        }
    }

    /**
     * 待确认 SQL 条目。
     */
    public record PendingSql(
            String token,
            String dataSourceId,
            String sql,
            SqlStatementInfo statementInfo,
            Instant createdAt,
            Instant expiresAt
    ) {
        public boolean isExpired() {
            return Instant.now().isAfter(expiresAt);
        }
    }
}
