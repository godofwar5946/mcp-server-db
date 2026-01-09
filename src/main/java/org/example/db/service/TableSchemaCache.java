package org.example.db.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.example.db.model.TableSchema;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * 表结构缓存（本地内存版，LRU + TTL）。
 * <p>
 * 为什么要用 LRU：
 * <ul>
 *   <li>schema 下可能有几千张表，如果超过阈值直接“清空全量缓存”，会造成缓存抖动与性能下降</li>
 *   <li>LRU 会优先淘汰不常用的表结构，更符合真实访问模式</li>
 * </ul>
 * <p>
 * 说明：
 * <ul>
 *   <li>当前实现为单实例内存缓存；多实例部署可替换为 Redis/分布式缓存</li>
 *   <li>缓存 key 包含 dataSourceId/schema/table，支持多数据源隔离</li>
 * </ul>
 */
public class TableSchemaCache {

    private final Cache<CacheKey, TableSchema> cache;

    public TableSchemaCache(Duration ttl, int maxSize) {
        Objects.requireNonNull(ttl, "ttl must not be null");
        if (maxSize <= 0) {
            throw new IllegalArgumentException("maxSize must be > 0");
        }
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(ttl)
                .maximumSize(maxSize)
                .build();
    }

    public TableSchema getOrLoad(String dataSourceId, String schema, String table, boolean refresh, Supplier<TableSchema> loader) {
        Objects.requireNonNull(loader, "loader must not be null");
        CacheKey key = new CacheKey(dataSourceId, schema, table);
        if (refresh) {
            cache.invalidate(key);
        }
        return cache.get(key, k -> loader.get());
    }

    public void invalidate(String dataSourceId, String schema, String table) {
        cache.invalidate(new CacheKey(dataSourceId, schema, table));
    }

    public void clear() {
        cache.invalidateAll();
    }

    private record CacheKey(String dataSourceId, String schema, String table) {
    }
}

