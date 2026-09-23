package org.example.mcp;

import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

class StdioProcessTest {
    @TempDir Path temp;
    ObjectMapper mapper = new ObjectMapper();

    @Test void startsWithoutHttpBeansAndStdoutContainsOnlyProtocolMessages() throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        Path errorLog = temp.resolve("stderr.log");
        var builder = new ProcessBuilder(java, "-cp", classpath, "org.example.McpServerApplication",
                "--spring.profiles.active=stdio", "--app.db.data-sources.primary.url=jdbc:postgresql://127.0.0.1:1/regression",
                "--app.db.data-sources.primary.username=regression", "--app.db.data-sources.primary.password=unused",
                "--app.db.data-sources.reporting.type=SQLSERVER",
                "--app.db.data-sources.reporting.url=jdbc:h2:mem:stdio-approval;MODE=MSSQLServer;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "--app.db.data-sources.reporting.driver-class-name=org.h2.Driver",
                "--app.db.data-sources.reporting.username=sa", "--app.db.data-sources.reporting.password=",
                "--app.db.data-sources.reporting.default-schema=public", "--app.db.data-sources.reporting.allow-writes=true",
                "--app.db.allow-ddl=true", "--app.security.approval-key=" + "c".repeat(32),
                "--logging.level.root=WARN");
        builder.environment().put("LOG_PATH", temp.resolve("logs").toString());
        builder.redirectError(errorLog.toFile());
        Process process = builder.start();
        ExecutorService reads = Executors.newSingleThreadExecutor();
        try (var input = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
             var output = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            send(input, """
                    {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"stdio-regression","version":"1"}}}
                    """);
            JsonNode initialized = response(output, reads, errorLog);
            assertThat(initialized.path("result").path("serverInfo").path("name").asText()).isEqualTo("db-mcp-server");
            send(input, "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
            send(input, "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}");
            var tools = response(output, reads, errorLog);
            assertThat(tools.path("result").path("tools")).hasSize(7);
            assertThat(tools.toString()).doesNotContain("\"context\"", "\"caller\"");
            send(input, "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"db_list_data_sources\",\"arguments\":{}}}");
            var sources = response(output, reads, errorLog);
            assertThat(sources.path("result").path("isError").asBoolean()).as(sources.toString()).isFalse();
            assertThat(sources.toString()).contains("primary", "reporting", "allowWrites").doesNotContain("jdbc:postgresql", "unused");
            var prepared = callTool(input, output, reads, errorLog, 4, "db_prepare_write_sql",
                    Map.of("dataSourceId", "reporting", "sql", "CREATE TABLE public.approval_probe(id int)"));
            assertThat(prepared.path("result").path("isError").asBoolean()).as(prepared.toString()).isFalse();
            String token = mapper.readTree(prepared.path("result").path("content").get(0).path("text").asText()).path("token").asText();
            var denied = callTool(input, output, reads, errorLog, 5, "db_confirm_write_sql", Map.of("token", token, "confirm", true));
            assertThat(denied.toString()).contains("审批密钥");
            var executed = callTool(input, output, reads, errorLog, 6, "db_confirm_write_sql",
                    Map.of("token", token, "confirm", true, "approvalKey", "c".repeat(32)));
            assertThat(executed.path("result").path("isError").asBoolean()).as(executed.toString()).isFalse();
            var queried = callTool(input, output, reads, errorLog, 7, "db_query_sql",
                    Map.of("dataSourceId", "reporting", "sql", "SELECT COUNT(*) AS total FROM public.approval_probe"));
            assertThat(queried.path("result").path("isError").asBoolean()).as(queried.toString()).isFalse();
        } finally {
            process.destroy();
            if (!process.waitFor(5, TimeUnit.SECONDS)) { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS); }
            reads.shutdownNow();
        }
    }

    private void send(BufferedWriter input, String json) throws IOException {
        input.write(json.strip()); input.newLine(); input.flush();
    }

    private JsonNode callTool(BufferedWriter input, BufferedReader output, ExecutorService reads, Path errorLog,
                              int id, String name, Map<String, Object> arguments) throws Exception {
        send(input, mapper.writeValueAsString(Map.of("jsonrpc", "2.0", "id", id, "method", "tools/call",
                "params", Map.of("name", name, "arguments", arguments))));
        return response(output, reads, errorLog);
    }

    private JsonNode response(BufferedReader output, ExecutorService reads, Path errorLog) throws Exception {
        String line = reads.submit(output::readLine).get(20, TimeUnit.SECONDS);
        assertThat(line).as(Files.readString(errorLog)).isNotNull().startsWith("{");
        return mapper.readTree(line);
    }
}
