package org.example.security;

import org.example.db.config.DbExplorerProperties;
import org.example.db.service.PendingSqlStore;
import org.example.db.sql.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class WriteApprovalTest {
    final PendingSqlStore store = new PendingSqlStore(new DbExplorerProperties());
    final PendingSqlStore.PendingSql item = store.create("alice:session", "primary",
            new SqlUtils.CheckedSql("UPDATE public.users SET name='x' WHERE id=1",
                    new SqlStatementInfo("UPDATE", SqlCategory.WRITE_DML), List.of(), false));
    final AccessProperties properties = new AccessProperties();

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"wrong", "   "})
    void missingOrWrongKeyCannotApproveSql(String key) {
        properties.setApprovalKey("c".repeat(32));
        assertThatThrownBy(() -> new WriteApproval(store, properties).requireApproval(item, key))
                .isInstanceOf(SecurityException.class).hasMessageContaining("审批密钥");
        assertThat(store.preview(item.token()).approved()).isFalse();
    }

    @Test void correctKeyApprovesOnlyThePreparedSql() {
        properties.setApprovalKey("c".repeat(32));
        var other = store.create("alice:session", "primary", item.sql());
        new WriteApproval(store, properties).requireApproval(item, "c".repeat(32));
        assertThat(store.preview(item.token()).approved()).isTrue();
        assertThat(store.preview(other.token()).approved()).isFalse();
    }

    @Test void configuringAKeyAloneDoesNotApproveWrites() {
        properties.setApprovalKey("c".repeat(32));
        assertThatThrownBy(() -> new WriteApproval(store, properties).requireApproval(item, null))
                .isInstanceOf(SecurityException.class);
        assertThat(store.preview(item.token()).approved()).isFalse();
    }

    @Test void independentApprovalDoesNotNeedTheKeyAgain() {
        properties.setApprovalKey("c".repeat(32));
        var approved = store.approve(item.token(), item.sqlHash());
        assertThatCode(() -> new WriteApproval(store, properties).requireApproval(approved, null)).doesNotThrowAnyException();
    }

    @Test void noConfiguredKeyRejectsApproval() {
        assertThatThrownBy(() -> new WriteApproval(store, properties).requireApproval(item, "c".repeat(32)))
                .isInstanceOf(SecurityException.class).hasMessageContaining("未配置 approval-key");
        assertThat(store.preview(item.token()).approved()).isFalse();
    }
}
