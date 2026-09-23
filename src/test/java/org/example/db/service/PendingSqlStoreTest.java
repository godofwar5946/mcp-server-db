package org.example.db.service;

import org.example.db.sql.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PendingSqlStoreTest {
    private SqlUtils.CheckedSql sql() {
        return new SqlUtils.CheckedSql("DELETE FROM public.users WHERE id=1", new SqlStatementInfo("DELETE", SqlCategory.WRITE_DML), List.of(), false);
    }
    @Test void boundsCapacityAndExpiresAtDeadline() {
        Clock clock = mock(Clock.class);
        Instant now = Instant.parse("2026-09-06T00:00:00Z");
        when(clock.instant()).thenReturn(now);
        var store = new PendingSqlStore(Duration.ofMinutes(1), 1, clock);
        var pending = store.create("owner", "primary", sql());
        assertThatThrownBy(() -> store.create("owner", "primary", sql())).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> store.approve(pending.token(), "wrong")).isInstanceOf(IllegalArgumentException.class);
        when(clock.instant()).thenReturn(now.plusSeconds(60));
        assertThatThrownBy(() -> store.get(pending.token(), "owner")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.get(null, "owner")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void approvalDoesNotExtendStorageLifetimeOrBlockNewRequestsAfterExpiry() {
        var nanos = new java.util.concurrent.atomic.AtomicLong();
        Clock clock = mock(Clock.class);
        Instant base = Instant.parse("2026-09-06T00:00:00Z");
        when(clock.instant()).thenAnswer(i -> base.plusNanos(nanos.get()));
        var store = new PendingSqlStore(Duration.ofMinutes(1), 1, clock, nanos::get);
        var pending = store.create("owner", "primary", sql());
        nanos.set(Duration.ofSeconds(50).toNanos());
        store.approve(pending.token(), pending.sqlHash());
        nanos.set(Duration.ofSeconds(61).toNanos());
        assertThatCode(() -> store.create("owner", "primary", sql())).doesNotThrowAnyException();
    }
}
