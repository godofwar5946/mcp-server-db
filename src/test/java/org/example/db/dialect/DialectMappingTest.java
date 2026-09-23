package org.example.db.dialect;

import org.example.db.datasource.DatabaseType;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.jdbc.core.*;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DialectMappingTest {
    @ParameterizedTest @EnumSource(value=DatabaseType.class, names={"POSTGRESQL","MYSQL","ORACLE","SQLSERVER"})
    @SuppressWarnings("unchecked")
    void readsNumbersAndNullabilityWithoutAssumingIntegerObjects(DatabaseType type) throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("table_name")).thenReturn("users");
        when(rs.getString("table_type")).thenReturn("TABLE");
        when(rs.getString("object_type")).thenReturn("U");
        when(rs.getString("column_name")).thenReturn("note");
        when(rs.getString("data_type")).thenReturn("varchar");
        when(rs.getString("is_nullable")).thenReturn(type == DatabaseType.ORACLE ? "Y" : "YES");
        when(rs.getBoolean("is_nullable")).thenReturn(true);
        when(rs.getInt("ordinal_position")).thenReturn(1);
        when(rs.getInt("numeric_precision")).thenReturn(38);
        when(rs.getInt("numeric_scale")).thenReturn(2);
        when(rs.getLong("character_maximum_length")).thenReturn(4294967295L);
        when(rs.getObject(anyString())).thenReturn(new BigDecimal("38"));
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenAnswer(inv -> {
            RowMapper<?> mapper = inv.getArgument(1);
            return List.of(mapper.mapRow(rs, 0));
        });
        DatabaseDialect dialect = switch (type) {
            case POSTGRESQL -> new PostgresDialect();
            case MYSQL -> new MysqlDialect();
            case ORACLE -> new OracleDialect();
            case SQLSERVER -> new SqlServerDialect();
            default -> throw new AssertionError();
        };
        var column = dialect.getTableSchema(jdbc, "public", "users").columns().getFirst();
        assertThat(column.characterMaximumLength()).isEqualTo(4294967295L);
        assertThat(column.numericPrecision()).isEqualTo(38);
        assertThat(column.numericScale()).isEqualTo(2);
        assertThat(column.nullable()).isTrue();
        verify(rs, never()).getObject(anyString());
    }
}
