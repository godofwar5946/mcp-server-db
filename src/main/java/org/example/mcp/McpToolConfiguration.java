package org.example.mcp;

import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;
import java.util.List;

/**
 * MCP Tools 注册配置。
 * <p>
 * Spring AI MCP Server 会从 Spring 容器中收集 ToolCallback，
 * 并以 MCP 协议的“tools”能力暴露给客户端（例如 Cursor/Claude Desktop 等）。
 */
@Configuration
public class McpToolConfiguration {

    /**
     * 将 {@link DatabaseMcpTools} 中的 @Tool 方法转换为 ToolCallback 列表注册到容器。
     */
    @Bean
    public List<ToolCallback> databaseToolCallbacks(DatabaseMcpTools tools) {
        return Arrays.asList(ToolCallbacks.from(tools));
    }
}

