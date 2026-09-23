package org.example.db.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.db.audit.SqlAuditLogger;
import org.example.db.config.DbExplorerProperties;
import org.example.db.datasource.*;
import org.example.security.Caller;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SqlExecutionServiceTest {
    DbExplorerProperties properties;
    DbExplorerProperties.DataSourceProperties config;
    DatabaseClientRegistry registry;
    TableSchemaService schemas;
    SqlExecutionService service;
    PendingSqlStore store;
    JdbcTemplate jdbc;
    Caller caller = new Caller("alice", "session-a");

    @BeforeEach void setup() {
        properties = new DbExplorerProperties();
        properties.setQueryMaxRows(2);
        config = new DbExplorerProperties.DataSourceProperties();
        config.setDefaultSchema("public");
        config.setAllowedSchemas(List.of("public"));
        config.setAllowWrites(true);
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MSSQLServer;DATABASE_TO_LOWER=TRUE");
        jdbc = new JdbcTemplate(ds);
        // Keep one anchor connection alive for the life of this test.
        try { anchor = ds.getConnection(); } catch (Exception e) { throw new RuntimeException(e); }
        jdbc.execute("CREATE SCHEMA IF NOT EXISTS public");
        jdbc.execute("CREATE TABLE public.users(id int primary key, name varchar(100), counter int)");
        jdbc.update("INSERT INTO public.users VALUES (1,'a',0),(2,'b',0),(3,'c',0)");
        registry = mock(DatabaseClientRegistry.class);
        when(registry.resolveDataSourceId(any())).thenAnswer(inv -> inv.getArgument(0) == null ? "primary" : inv.getArgument(0));
        when(registry.getDataSourceConfig("primary")).thenReturn(config);
        when(registry.resolveDatabaseType("primary")).thenReturn(DatabaseType.SQLSERVER);
        when(registry.getClient("primary")).thenReturn(new DatabaseClient("primary", ds, jdbc));
        schemas = mock(TableSchemaService.class);
        store = new PendingSqlStore(properties);
        service = new SqlExecutionService(registry, schemas, properties, store, mock(SqlAuditLogger.class),
                new QueryResultReader(properties, new ObjectMapper()));
    }
    java.sql.Connection anchor;
    @AfterEach void close() throws Exception { anchor.close(); }

    @Test void queriesAreLimitedAndDuplicateColumnsSurvive() {
        var result = service.query(caller, null, "SELECT id AS x, id+1 AS x, id+2 AS x_2 FROM public.users ORDER BY id", 2, false);
        assertThat(result.rows()).hasSize(2);
        assertThat(result.limited()).isTrue();
        assertThat(result.rows().getFirst()).containsKeys("x", "x_2", "x_3");
        assertThat(result.rows().getFirst().values()).containsExactlyInAnyOrder(1L,2L,3L);
    }

    @Test void schemaRejectionNeverFallsBackToExecution() {
        assertThatThrownBy(() -> service.query(caller, null, "SELECT * FROM secret.users", null, false))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("schema");
        verify(registry, never()).getClient(any());
    }

    @Test void textFunctionsProcessFullClobBeforeResultTruncation() {
        String text = "开头\n" + "x".repeat(100000) + "KEYWORD" + "中".repeat(30000) + "\n结尾";
        jdbc.execute("CREATE TABLE public.documents(id int primary key, body clob)");
        jdbc.update("INSERT INTO public.documents VALUES (?, ?)", 1, text);
        var raw = service.query(caller, null, "SELECT body FROM public.documents WHERE id=1", 1, false);
        assertThat(raw.limited()).isTrue();
        assertThat((String) raw.rows().getFirst().get("body")).hasSize(properties.getFieldMaxLength());
        assertThat(raw.warnings().toString()).contains("SUBSTRING/SUBSTR", "分段");

        var slice = service.query(caller, null, """
                SELECT LEN(body) AS total_chars, CHARINDEX('KEYWORD', body) AS keyword_pos,
                       SUBSTRING(body, 100001, 4000) AS chunk, LEFT(body, 100) AS head, RIGHT(body, 100) AS tail
                FROM public.documents WHERE id=1
                """, 1, false);
        assertThat(slice.limited()).isFalse();
        assertThat(slice.rows().getFirst())
                .containsEntry("total_chars", (long) text.length())
                .containsEntry("keyword_pos", (long) text.indexOf("KEYWORD") + 1)
                .containsEntry("chunk", text.substring(100000, 104000))
                .containsEntry("head", text.substring(0, 100))
                .containsEntry("tail", text.substring(text.length() - 100));
    }

    @Test void tokenNeedsApprovalAndCannotCrossSessionsOrReplay() {
        var prepared = service.prepareWrite(caller, null, "UPDATE public.users SET counter=counter+1 WHERE id=1", false);
        assertThatThrownBy(() -> service.confirmWrite(caller, prepared.token(), true, null, false)).isInstanceOf(SecurityException.class);
        Caller other = new Caller("alice", "other-session");
        assertThatThrownBy(() -> service.confirmWrite(other, prepared.token(), false, null, false)).isInstanceOf(IllegalArgumentException.class);
        var item = store.get(prepared.token(), caller.owner());
        store.approve(item.token(), item.sqlHash());
        assertThat(service.confirmWrite(caller, item.token(), true, null, false).updateCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT counter FROM public.users WHERE id=1", Integer.class)).isEqualTo(1);
        assertThatThrownBy(() -> service.confirmWrite(caller, item.token(), true, null, false)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void concurrentConfirmExecutesOnce() throws Exception {
        var prepared = service.prepareWrite(caller, null, "UPDATE public.users SET counter=counter+1 WHERE id=1", false);
        var item = store.get(prepared.token(), caller.owner());
        store.approve(item.token(), item.sqlHash());
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Callable<Boolean> confirm = () -> {
                try { service.confirmWrite(caller, item.token(), true, null, false); return true; }
                catch (IllegalArgumentException e) { return false; }
            };
            var results = pool.invokeAll(List.of(confirm, confirm));
            assertThat(results.stream().filter(f -> { try { return f.get(); } catch (Exception e) { throw new RuntimeException(e); } }).count()).isEqualTo(1);
        }
        assertThat(jdbc.queryForObject("SELECT counter FROM public.users WHERE id=1", Integer.class)).isEqualTo(1);
    }

    @Test void rechecksWritePolicyAndInvalidatesDdlCache() {
        properties.setAllowDdl(true);
        var prepared = service.prepareWrite(caller, null, "ALTER TABLE public.users ADD note varchar(40)", false);
        var item = store.get(prepared.token(), caller.owner());
        store.approve(item.token(), item.sqlHash());
        config.setAllowWrites(false);
        assertThatThrownBy(() -> service.confirmWrite(caller, item.token(), true, null, false)).isInstanceOf(SecurityException.class);
        config.setAllowWrites(true);
        service.confirmWrite(caller, item.token(), true, null, false);
        verify(schemas).invalidateDataSource("primary");
    }

    @Test void defaultWritePolicyAndFullTableWritesAreRejected() {
        assertThatThrownBy(() -> service.prepareWrite(caller, null, "DELETE FROM public.users", false)).isInstanceOf(SecurityException.class);
        config.setAllowWrites(false);
        assertThatThrownBy(() -> service.prepareWrite(caller, null, "DELETE FROM public.users WHERE id=1", false)).isInstanceOf(SecurityException.class);
    }

    @Test void sqlTimeoutRollsBackAndRestoresConnectionState() throws Exception {
        JdbcDataSource ds = mock(JdbcDataSource.class);
        java.sql.Connection connection = mock(java.sql.Connection.class);
        java.sql.Statement statement = mock(java.sql.Statement.class);
        when(ds.getConnection()).thenReturn(connection);
        when(connection.getAutoCommit()).thenReturn(true);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.execute(anyString())).thenThrow(new java.sql.SQLTimeoutException("sensitive SQL", "57014"));
        when(registry.getClient("primary")).thenReturn(new DatabaseClient("primary", ds, new JdbcTemplate(ds)));
        assertThatThrownBy(() -> service.query(caller, "primary", "SELECT 1", 1, false))
                .hasMessageContaining("超时").hasMessageNotContaining("sensitive SQL");
        verify(statement, atLeastOnce()).setQueryTimeout(30);
        verify(connection).setReadOnly(true);
        verify(connection).rollback();
        verify(connection).setReadOnly(false);
        verify(connection).setAutoCommit(true);
        verify(statement).close();
        verify(connection).close();
    }
}
