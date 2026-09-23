package org.example.db.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.example.db.model.TableSchema;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Bounded Caffeine cache with TTL and per-datasource generations.
 * Generation changes keep old in-flight loads from repopulating caches after DDL.
 */
public class TableSchemaCache {

    private final Cache<CacheKey, TableSchema> cache;
    private final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong> generations = new java.util.concurrent.ConcurrentHashMap<>();
    private long generation(String id) {
        return generations.computeIfAbsent(id, k -> new java.util.concurrent.atomic.AtomicLong()).get();
    }

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
        CacheKey key = new CacheKey(dataSourceId, schema, table, generation(dataSourceId));
        if (refresh) {
            cache.invalidate(key);
        }
        return cache.get(key, k -> loader.get());
    }

    public void invalidate(String dataSourceId, String schema, String table) {
        cache.invalidate(new CacheKey(dataSourceId, schema, table, generation(dataSourceId)));
    }

    public void invalidateDataSource(String id) {
        generations.computeIfAbsent(id, k -> new java.util.concurrent.atomic.AtomicLong()).incrementAndGet();
        cache.asMap().keySet().removeIf(key -> key.dataSourceId().equals(id));
    }

    public void clear() {
        cache.invalidateAll();
    }

    private record CacheKey(String dataSourceId, String schema, String table, long generation) {
    }
}

