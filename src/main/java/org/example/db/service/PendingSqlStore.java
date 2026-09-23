package org.example.db.service;

import com.github.benmanes.caffeine.cache.*;
import org.example.db.config.DbExplorerProperties;
import org.example.db.sql.SqlUtils.CheckedSql;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.HexFormat;
import java.util.UUID;

@Component
public class PendingSqlStore {
    private final Cache<String, PendingSql> store;
    private final Duration ttl;
    private final int maximumSize;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public PendingSqlStore(DbExplorerProperties properties) {
        this(properties.getPendingSqlTtl(), properties.getPendingSqlMaxSize(), Clock.systemUTC());
    }

    PendingSqlStore(Duration ttl, int maximumSize, Clock clock) {
        this(ttl, maximumSize, clock, Ticker.systemTicker());
    }

    PendingSqlStore(Duration ttl, int maximumSize, Clock clock, Ticker ticker) {
        this.ttl = ttl;
        this.maximumSize = maximumSize;
        this.clock = clock;
        store = Caffeine.newBuilder().ticker(ticker).expireAfter(new Expiry<String, PendingSql>() {
                    @Override public long expireAfterCreate(String key, PendingSql value, long now) { return ttl.toNanos(); }
                    @Override public long expireAfterUpdate(String key, PendingSql value, long now, long currentDuration) { return currentDuration; }
                    @Override public long expireAfterRead(String key, PendingSql value, long now, long currentDuration) { return currentDuration; }
                }).maximumSize(maximumSize)
                .scheduler(Scheduler.systemScheduler()).build();
    }

    public synchronized PendingSql create(String owner, String dataSourceId, CheckedSql sql) {
        store.cleanUp();
        if (store.estimatedSize() >= maximumSize) throw new IllegalStateException("待确认 SQL 已达容量上限，请稍后重试");
        Instant now = clock.instant();
        PendingSql pending = new PendingSql(UUID.randomUUID().toString(), owner, dataSourceId, sql,
                hash(sql.sql()), now.plus(ttl), false);
        store.put(pending.token(), pending);
        return pending;
    }

    public PendingSql get(String token, String owner) {
        PendingSql pending = preview(token);
        if (!pending.owner().equals(owner)) throw invalid();
        return pending;
    }

    public PendingSql preview(String token) {
        PendingSql pending = token == null ? null : store.getIfPresent(token);
        if (pending == null || !clock.instant().isBefore(pending.expiresAt())) {
            if (token != null) store.invalidate(token);
            throw invalid();
        }
        return pending;
    }

    public PendingSql approve(String token, String sqlHash) {
        PendingSql pending = preview(token);
        if (!pending.sqlHash().equals(sqlHash)) throw new IllegalArgumentException("SQL 摘要不匹配，请重新查看审批内容");
        PendingSql approved = new PendingSql(pending.token(), pending.owner(), pending.dataSourceId(), pending.sql(),
                pending.sqlHash(), pending.expiresAt(), true);
        if (!store.asMap().replace(token, pending, approved)) throw invalid();
        return approved;
    }

    public PendingSql consume(String token, String owner, boolean requireApproval) {
        PendingSql pending = get(token, owner);
        if (requireApproval && !pending.approved()) throw new SecurityException("SQL 尚未获用户批准");
        if (!store.asMap().remove(token, pending)) throw invalid();
        return pending;
    }

    private static IllegalArgumentException invalid() { return new IllegalArgumentException("token 无效、已过期或不属于当前会话"); }

    public static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    public record PendingSql(String token, String owner, String dataSourceId, CheckedSql sql,
                             String sqlHash, Instant expiresAt, boolean approved) {}
}
