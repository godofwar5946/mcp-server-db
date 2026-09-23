package org.example.security;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.main.banner-mode=off",
        "spring.ai.mcp.server.streamable-http.keep-alive-interval=10m",
        "app.db.data-sources.primary.url=jdbc:postgresql://127.0.0.1:1/regression",
        "app.db.data-sources.primary.allow-writes=true",
        "app.security.clients.local.token=",
        "app.security.approval-key=cccccccccccccccccccccccccccccccc"
})
class McpAnonymousHttpIntegrationTest extends McpHttpTestSupport {
    @Test void initializesListsToolsAndClosesSessionWithoutAuthorization() throws Exception {
        String session = initialize(null);
        assertThat(rpc(null, session, "tools/list", Map.of()).path("result").path("tools").size()).isEqualTo(7);
        var list = tool(null, session, "db_list_data_sources", Map.of());
        assertThat(list.path("result").path("isError").asBoolean()).isFalse();
        assertThat(list.toString()).contains("primary", "reporting", "allowWrites").doesNotContain("jdbc:postgresql", "password");
        assertReportingIsReadableButNotWritable(null, session);
        var denied = tool(null, session, "db_query_sql", Map.of("dataSourceId", "private", "sql", "SELECT 1"));
        assertThat(denied.toString()).contains("未知的数据源");
        assertThat(send("/actuator/env", "GET", null, null, null, null, null).statusCode()).isEqualTo(404);
        var ping = send("/mcp", "POST", "{\"jsonrpc\":\"2.0\",\"id\":8,\"method\":\"ping\"}", null, session, null, "https://client.example");
        assertThat(ping.statusCode()).isEqualTo(200);
        assertThat(send("/mcp", "DELETE", null, null, session, null, null).statusCode()).isBetween(200, 299);
        assertThat(send("/mcp", "POST", "{}", null, session, null, null).statusCode()).isEqualTo(404);
    }

    @Test void anonymousWritesStillRequireApprovalAndStayBoundToTheirSession() throws Exception {
        String first = initialize(null), second = initialize(null);
        var prepared = tool(null, first, "db_prepare_write_sql",
                Map.of("sql", "DELETE FROM public.users WHERE id=1"));
        assertThat(prepared.path("result").path("isError").asBoolean()).as(prepared.toString()).isFalse();
        JsonNode payload = mapper.readTree(prepared.path("result").path("content").get(0).path("text").asText());
        String token = payload.path("token").asText();
        assertThat(token).isNotBlank();
        var unapproved = tool(null, first, "db_confirm_write_sql", Map.of("token", token, "confirm", true));
        assertThat(unapproved.toString()).containsAnyOf("独立审批", "用户确认");
        var foreign = tool(null, second, "db_confirm_write_sql", Map.of("token", token, "confirm", false));
        assertThat(foreign.toString()).contains("不属于当前会话");
        assertThat(send("/admin/sql/" + token, "GET", null, null, null, null, null).statusCode()).isEqualTo(401);
        var preview = send("/admin/sql/" + token, "GET", null, null, null, "c".repeat(32), null);
        assertThat(preview.statusCode()).isEqualTo(200);
        assertThat(mapper.readTree(preview.body()).path("owner").asText()).startsWith("anonymous:");
        // Cancel the prepared DELETE without ever accessing an external database.
        assertThat(tool(null, first, "db_confirm_write_sql", Map.of("token", token, "confirm", false)).toString()).contains("已取消");
        assertThat(send("/mcp", "DELETE", null, null, first, null, null).statusCode()).isBetween(200, 299);
        assertThat(send("/mcp", "DELETE", null, null, second, null, null).statusCode()).isBetween(200, 299);
    }
}
