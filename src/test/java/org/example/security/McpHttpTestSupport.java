package org.example.security;

import com.fasterxml.jackson.databind.*;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@TestPropertySource(properties = {
        "app.db.data-sources.reporting.type=SQLSERVER",
        "app.db.data-sources.reporting.url=jdbc:h2:mem:mcp-reporting;MODE=MSSQLServer;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "app.db.data-sources.reporting.driver-class-name=org.h2.Driver",
        "app.db.data-sources.reporting.username=sa",
        "app.db.data-sources.reporting.password=",
        "app.db.data-sources.reporting.default-schema=public",
        "app.db.data-sources.reporting.allow-writes=false"
})
abstract class McpHttpTestSupport {
    @LocalServerPort int port;
    ObjectMapper mapper = new ObjectMapper();
    HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    HttpResponse<String> send(String path, String method, String body, String token, String session, String approval, String origin) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(15))
                .header("Accept", "application/json, text/event-stream").header("Content-Type", "application/json");
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (session != null) request.header("Mcp-Session-Id", session).header("MCP-Protocol-Version", "2025-06-18");
        if (approval != null) request.header("X-Approval-Key", approval);
        if (origin != null) request.header("Origin", origin);
        return client.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    String initialize(String token) throws Exception {
        var response = send("/mcp", "POST", """
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"regression","version":"1"}}}
                """, token, null, null, null);
        assertThat(response.statusCode()).isEqualTo(200);
        String session = response.headers().firstValue("Mcp-Session-Id").orElseThrow();
        var initialized = send("/mcp", "POST", """
                {"jsonrpc":"2.0","method":"notifications/initialized"}
                """, token, session, null, null);
        assertThat(initialized.statusCode()).isBetween(200, 299);
        return session;
    }

    JsonNode rpc(String token, String session, String method, Map<String, Object> params) throws Exception {
        var response = send("/mcp", "POST", mapper.writeValueAsString(Map.of("jsonrpc", "2.0", "id", 2, "method", method, "params", params)),
                token, session, null, null);
        assertThat(response.statusCode()).isEqualTo(200);
        String body = response.body();
        if (body.stripLeading().startsWith("{")) return mapper.readTree(body);
        String data = body.lines().filter(line -> line.startsWith("data:")).map(line -> line.substring(5).trim()).findFirst().orElseThrow();
        return mapper.readTree(data);
    }

    JsonNode tool(String token, String session, String name, Map<String, Object> arguments) throws Exception {
        return rpc(token, session, "tools/call", Map.of("name", name, "arguments", arguments));
    }

    void assertReportingIsReadableButNotWritable(String token, String session) throws Exception {
        var result = tool(token, session, "db_query_sql", Map.of("dataSourceId", "reporting", "sql", "SELECT 42 AS answer"));
        assertThat(result.path("result").path("isError").asBoolean()).as(result.toString()).isFalse();
        var payload = mapper.readTree(result.path("result").path("content").get(0).path("text").asText());
        assertThat(payload.path("rows").get(0).path("answer").asInt()).isEqualTo(42);
        var write = tool(token, session, "db_prepare_write_sql",
                Map.of("dataSourceId", "reporting", "sql", "DELETE FROM public.users WHERE id=1"));
        assertThat(write.toString()).contains("该数据源未开启写入");
    }
}
