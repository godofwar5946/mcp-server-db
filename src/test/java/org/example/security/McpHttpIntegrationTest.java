package org.example.security;

import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.main.banner-mode=off",
        "spring.ai.mcp.server.streamable-http.keep-alive-interval=10m",
        "app.db.data-sources.primary.url=jdbc:postgresql://127.0.0.1:1/regression",
        "app.db.data-sources.primary.allow-writes=true",
        "app.security.clients.local.token=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
        "app.security.clients.other.token=bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
        "app.security.approval-key=cccccccccccccccccccccccccccccccc"
})
class McpHttpIntegrationTest extends McpHttpTestSupport {
    static final String ALICE = "a".repeat(32), BOB = "b".repeat(32), APPROVER = "c".repeat(32);
    @Test void requiresAuthenticationAndRejectsExposedActuatorEndpoints() throws Exception {
        assertThat(send("/mcp","POST","{}",null,null,null,null).statusCode()).isEqualTo(401);
        assertThat(send("/actuator/env","GET",null,ALICE,null,null,null).statusCode()).isEqualTo(404);
        assertThat(send("/actuator/health","GET",null,null,null,null,null).body()).doesNotContain("details", "jdbc");
    }

    @Test void propagatesIdentityAndScopesApprovalAndTokensToSession() throws Exception {
        String alice = initialize(ALICE), bob = initialize(BOB);
        var tools = rpc(ALICE, alice, "tools/list", Map.of());
        assertThat(tools.path("result").path("tools").toString()).contains("db_query_sql").doesNotContain("\"context\"", "\"caller\"");
        var list = tool(ALICE, alice, "db_list_data_sources", Map.of());
        assertThat(list.path("result").path("isError").asBoolean()).isFalse();
        assertThat(list.toString()).contains("primary", "reporting", "allowWrites").doesNotContain("jdbc:postgresql", "password");
        assertReportingIsReadableButNotWritable(ALICE, alice);
        assertReportingIsReadableButNotWritable(BOB, bob);
        var prepared = tool(ALICE, alice, "db_prepare_write_sql",
                Map.of("sql", "UPDATE public.users SET name='review' WHERE id=1"));
        assertThat(prepared.path("result").path("isError").asBoolean()).as(prepared.toString()).isFalse();
        JsonNode payload = mapper.readTree(prepared.path("result").path("content").get(0).path("text").asText());
        String token = payload.path("token").asText();
        assertThat(token).isNotBlank();
        var noApproval = tool(ALICE, alice, "db_confirm_write_sql", Map.of("token", token, "confirm", true));
        assertThat(noApproval.toString()).containsAnyOf("独立审批", "用户确认");
        var foreign = tool(BOB, bob, "db_confirm_write_sql", Map.of("token", token, "confirm", false));
        assertThat(foreign.toString()).contains("不属于当前会话");
        assertThat(send("/mcp", "POST", "{}", BOB, alice, null, null).statusCode()).isEqualTo(404);
        assertThat(send("/admin/sql/" + token, "GET", null, ALICE, null, null, null).statusCode()).isEqualTo(401);
        var preview = send("/admin/sql/" + token, "GET", null, null, null, APPROVER, null);
        assertThat(preview.statusCode()).isEqualTo(200);
        String hash = mapper.readTree(preview.body()).path("sqlHash").asText();
        assertThat(send("/admin/sql/" + token + "/approval", "POST", "{\"sqlHash\":\"wrong\"}", null, null, APPROVER, null).statusCode()).isEqualTo(400);
        assertThat(send("/admin/sql/" + token + "/approval", "POST", "{\"sqlHash\":\"" + hash + "\"}", null, null, APPROVER, null).statusCode()).isEqualTo(200);
        // Cancel instead of touching any external database.
        var cancelled = tool(ALICE, alice, "db_confirm_write_sql", Map.of("token", token, "confirm", false));
        assertThat(cancelled.toString()).contains("已取消");
        assertThat(send("/admin/sql/" + token, "GET", null, null, null, APPROVER, null).statusCode()).isEqualTo(400);
        assertThat(send("/mcp", "DELETE", null, ALICE, alice, null, null).statusCode()).isBetween(200,299);
    }
}
