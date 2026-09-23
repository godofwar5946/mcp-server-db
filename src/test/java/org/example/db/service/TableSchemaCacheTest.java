package org.example.db.service;

import org.example.db.model.*;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

class TableSchemaCacheTest {
    @Test void inFlightOldLoadCannotRepopulateCurrentGenerationAfterDdl() throws Exception {
        var cache = new TableSchemaCache(Duration.ofMinutes(1), 20);
        var old = new TableSchema(new TableInfo("public", "users", "table", "old"), List.of());
        var fresh = new TableSchema(new TableInfo("public", "users", "table", "new"), List.of());
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        try (var pool = Executors.newSingleThreadExecutor()) {
            var inFlight = pool.submit(() -> cache.getOrLoad("primary", "public", "users", false, () -> {
                started.countDown();
                try { if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("timeout"); }
                catch (InterruptedException e) { throw new RuntimeException(e); }
                return old;
            }));
            try {
                assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
                cache.invalidateDataSource("primary");
                assertThat(cache.getOrLoad("primary", "public", "users", false, () -> fresh)).isSameAs(fresh);
            } finally { release.countDown(); }
            inFlight.get(5, TimeUnit.SECONDS);
            assertThat(cache.getOrLoad("primary", "public", "users", false, () -> old)).isSameAs(fresh);
        }
    }
}
