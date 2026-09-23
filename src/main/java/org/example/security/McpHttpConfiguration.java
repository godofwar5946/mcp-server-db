package org.example.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.transport.WebMvcStreamableServerTransportProvider;
import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerStreamableHttpProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.*;
import org.springframework.core.Ordered;
import java.util.Map;

@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(name = "spring.ai.mcp.server.stdio", havingValue = "false", matchIfMissing = true)
@EnableConfigurationProperties(McpServerStreamableHttpProperties.class)
public class McpHttpConfiguration {
    @Bean
    WebMvcStreamableServerTransportProvider webMvcStreamableServerTransportProvider(
            ObjectMapper mapper, McpServerStreamableHttpProperties properties) {
        return WebMvcStreamableServerTransportProvider.builder()
                .jsonMapper(new JacksonMcpJsonMapper(mapper))
                .mcpEndpoint(properties.getMcpEndpoint())
                .disallowDelete(false)
                .keepAliveInterval(properties.getKeepAliveInterval())
                .contextExtractor(request -> {
                    Object caller = request.servletRequest().getAttribute(CallerResolver.CONTEXT_KEY);
                    if (!(caller instanceof Caller)) throw new SecurityException("缺少已认证身份");
                    return McpTransportContext.create(Map.of(CallerResolver.CONTEXT_KEY, caller));
                }).build();
    }

    @Bean
    FilterRegistrationBean<McpAccessFilter> mcpAccessFilter(AccessProperties security, McpServerStreamableHttpProperties http) {
        var registration = new FilterRegistrationBean<>(new McpAccessFilter(security, http.getMcpEndpoint()));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.setAsyncSupported(true);
        registration.addUrlPatterns("/*");
        return registration;
    }
}
