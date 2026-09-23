package org.example.mcp.keepalive;

import io.modelcontextprotocol.server.transport.WebMvcStreamableServerTransportProvider;
import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerStreamableHttpProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;

@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(name = "spring.ai.mcp.server.stdio", havingValue = "false", matchIfMissing = true)
public class McpKeepAliveConfiguration {
    @Bean
    McpKeepAliveCleaner mcpKeepAliveCleaner(McpServerStreamableHttpProperties http,
                                          McpKeepAliveCleanupProperties cleanup) {
        return new McpKeepAliveCleaner(http, cleanup);
    }
    @Bean
    Attachment keepAliveAttachment(McpKeepAliveCleaner cleaner, WebMvcStreamableServerTransportProvider provider) {
        return new Attachment(cleaner, provider);
    }
    public record Attachment(McpKeepAliveCleaner cleaner, WebMvcStreamableServerTransportProvider provider) {
        @EventListener(ContextRefreshedEvent.class)
        public void attach() { cleaner.attach(provider); }
    }
}
