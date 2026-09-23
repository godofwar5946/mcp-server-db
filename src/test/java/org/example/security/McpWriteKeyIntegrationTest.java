package org.example.security;

import org.example.db.datasource.DatabaseClientRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.main.banner-mode=off",
        "spring.ai.mcp.server.streamable-http.keep-alive-interval=10m",
        "app.db.data-sources.primary.url=jdbc:postgresql://127.0.0.1:1/regression",
        "app.db.data-sources.writer.type=SQLSERVER",
        "app.db.data-sources.writer.url=jdbc:h2:mem:mcp-writer;MODE=MSSQLServer;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "app.db.data-sources.writer.driver-class-name=org.h2.Driver",
        "app.db.data-sources.writer.username=sa",
        "app.db.data-sources.writer.password=",
        "app.db.data-sources.writer.default-schema=public",
        "app.db.data-sources.writer.allow-writes=true",
        "app.security.clients.local.token=",
        "app.security.approval-key=cccccccccccccccccccccccccccccccc"
})
class McpWriteKeyIntegrationTest extends McpHttpTestSupport {
    @Autowired DatabaseClientRegistry registry;

    @Test void correctApprovalKeyExecutesDeleteOnlyOnceWithoutPerCallerPermissions() throws Exception {
        var jdbc = new JdbcTemplate(registry.getClient("writer").dataSource());
        jdbc.execute("CREATE TABLE public.users(id int primary key)");
        jdbc.update("INSERT INTO public.users VALUES (1), (2)");
        String session = initialize(null);
        assertReportingIsReadableButNotWritable(null, session);
        var prepared = tool(null, session, "db_prepare_write_sql",
                Map.of("dataSourceId", "writer", "sql", "DELETE FROM public.users WHERE id=1"));
        assertThat(prepared.path("result").path("isError").asBoolean()).as(prepared.toString()).isFalse();
        String token = mapper.readTree(prepared.path("result").path("content").get(0).path("text").asText()).path("token").asText();
        for (var arguments : java.util.List.of(Map.<String, Object>of("token", token, "confirm", true),
                Map.<String, Object>of("token", token, "confirm", true, "approvalKey", "wrong-key"))) {
            var refused = tool(null, session, "db_confirm_write_sql", arguments);
            assertThat(refused.toString()).contains("审批密钥").doesNotContain("wrong-key", "c".repeat(32));
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM public.users", Integer.class)).isEqualTo(2);
        }
        var arguments = Map.<String, Object>of("token", token, "confirm", true, "approvalKey", "c".repeat(32));
        var executed = tool(null, session, "db_confirm_write_sql", arguments);
        assertThat(executed.path("result").path("isError").asBoolean()).as(executed.toString()).isFalse();
        assertThat(executed.toString()).doesNotContain("c".repeat(32));
        assertThat(jdbc.queryForList("SELECT id FROM public.users", Integer.class)).containsExactly(2);
        var replay = tool(null, session, "db_confirm_write_sql", arguments);
        assertThat(replay.path("result").path("isError").asBoolean()).isTrue();
        assertThat(send("/mcp", "DELETE", null, null, session, null, null).statusCode()).isBetween(200, 299);
    }
}
