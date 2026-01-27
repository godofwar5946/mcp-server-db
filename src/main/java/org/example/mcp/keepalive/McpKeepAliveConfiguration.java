package org.example.mcp.keepalive;

import io.modelcontextprotocol.server.transport.WebMvcStreamableServerTransportProvider;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MCP keep-alive 优化装配：
 * <ul>
 *   <li>监听 {@link WebMvcStreamableServerTransportProvider} Bean 初始化完成</li>
 *   <li>将其绑定到 {@link McpKeepAliveCleaner}，实现断连会话自动清理</li>
 * </ul>
 */
@Configuration
public class McpKeepAliveConfiguration {

    @Bean
    public BeanPostProcessor mcpKeepAliveCleanerPostProcessor(McpKeepAliveCleaner cleaner) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
                if (bean instanceof WebMvcStreamableServerTransportProvider provider) {
                    cleaner.attach(provider);
                }
                return bean;
            }
        };
    }
}

