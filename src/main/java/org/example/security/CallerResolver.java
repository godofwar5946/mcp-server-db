package org.example.security;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.mcp.McpToolUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class CallerResolver {
    public static final String CONTEXT_KEY = Caller.class.getName();
    private final boolean stdio;
    public CallerResolver(@Value("${spring.ai.mcp.server.stdio:false}") boolean stdio) {
        this.stdio = stdio;
    }
    public Caller resolve(ToolContext context) {
        var exchange = McpToolUtils.getMcpExchange(context).orElseThrow(() -> new SecurityException("缺少 MCP 调用上下文"));
        if (stdio) return new Caller("stdio", exchange.sessionId());
        Object value = exchange.transportContext().get(CONTEXT_KEY);
        if (!(value instanceof Caller caller)) throw new SecurityException("缺少已认证身份");
        return new Caller(caller.subject(), exchange.sessionId());
    }
}
