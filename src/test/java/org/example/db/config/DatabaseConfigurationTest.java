package org.example.db.config;

import com.alibaba.druid.pool.DruidDataSource;
import jakarta.validation.Validation;
import org.example.db.datasource.*;
import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

class DatabaseConfigurationTest {
    @Test void metadataHasNoGlobalRowLimitAndOracleHasAppropriateValidation() {
        var properties = new DbExplorerProperties();
        var cfg = new DbExplorerProperties.DataSourceProperties();
        cfg.setUrl("jdbc:oracle:thin:@127.0.0.1:1/test");
        cfg.setType(DatabaseType.ORACLE);
        properties.setDataSources(Map.of("primary", cfg));
        properties.setQueryMaxRows(1);
        var registry = new DatabaseClientRegistry(properties);
        try {
            assertThat(registry.getClient("primary").jdbcTemplate().getMaxRows()).isEqualTo(-1);
            assertThat(registry.getClient("primary").jdbcTemplate().getQueryTimeout()).isEqualTo(30);
            var ds = (DruidDataSource) registry.getClient("primary").dataSource();
            assertThat(ds.getValidationQuery()).isEqualTo("SELECT 1 FROM DUAL");
            assertThat(ds.getConnectTimeout()).isEqualTo(10000);
            assertThat(ds.getSocketTimeout()).isEqualTo(35000);
            assertThat(ds.isInited()).isFalse();
        } finally { registry.close(); }
    }

    @Test void cascadesValidationIntoDatasourceAndPool() {
        var properties = new DbExplorerProperties();
        properties.setDataSources(Map.of("primary", new DbExplorerProperties.DataSourceProperties()));
        properties.getPool().setMaxActive(0);
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var errors = factory.getValidator().validate(properties);
            assertThat(errors).anyMatch(e -> e.getPropertyPath().toString().contains("url"));
            assertThat(errors).anyMatch(e -> e.getPropertyPath().toString().contains("pool"));
        }
    }

    @Test void boundedExecutorRejectsExcessWork() throws Exception {
        var props = new DbExplorerProperties();
        props.setSchemaFetchParallelism(1);
        props.setSchemaQueueCapacity(1);
        var executor = new DbExecutorConfiguration().schemaFetchExecutor(props);
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try {
            executor.submit(() -> { started.countDown(); try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } });
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            executor.submit(() -> {});
            assertThatThrownBy(() -> executor.submit(() -> {})).isInstanceOf(RejectedExecutionException.class);
        } finally { release.countDown(); executor.shutdownNow(); }
    }
}
