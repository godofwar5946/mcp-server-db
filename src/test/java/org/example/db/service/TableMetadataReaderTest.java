package org.example.db.service;

import org.example.db.datasource.*;
import org.example.db.model.*;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class TableMetadataReaderTest {
    @Test void returnsCompositeKeysForeignKeysAndUniqueIndexesFromJdbcMetadata() throws Exception {
        var ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";DATABASE_TO_LOWER=TRUE");
        try (var anchor = ds.getConnection()) {
            var jdbc = new JdbcTemplate(ds);
            jdbc.execute("CREATE TABLE users(id int primary key, email varchar(80) unique)");
            jdbc.execute("CREATE TABLE orders(id int, user_id int, primary key(id,user_id), constraint user_fk foreign key(user_id) references users(id))");
            var registry = mock(DatabaseClientRegistry.class);
            when(registry.resolveDatabaseType("primary")).thenReturn(DatabaseType.POSTGRESQL);
            var reader = new TableMetadataReader(registry);
            var result = reader.enrich(new DatabaseClient("primary", ds, jdbc),
                    new TableSchema(new TableInfo("public", "orders", "table", null), List.of()));
            assertThat(result.primaryKeys()).hasSize(1);
            assertThat(result.primaryKeys().getFirst().columns()).extracting(TableSchema.KeyColumn::name).containsExactly("id", "user_id");
            assertThat(result.foreignKeys()).hasSize(1);
            assertThat(result.foreignKeys().getFirst().referencedTable()).isEqualTo("users");
            assertThat(result.foreignKeys().getFirst().columns().getFirst().column()).isEqualTo("user_id");
            assertThat(result.indexes()).anyMatch(TableSchema.IndexInfo::unique);
            assertThat(result.metadataWarnings()).isEmpty();
        }
    }
}
