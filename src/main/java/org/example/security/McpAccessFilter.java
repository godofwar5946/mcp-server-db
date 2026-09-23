package org.example.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;

public class McpAccessFilter extends OncePerRequestFilter {
    private final AccessProperties properties;
    private final String endpoint;
    private final Caller anonymousCaller;
    private final Cache<String, String> sessionOwners = Caffeine.newBuilder()
            .maximumSize(10000).expireAfterAccess(Duration.ofHours(1)).build();

    public McpAccessFilter(AccessProperties properties, String endpoint) {
        this.properties = properties;
        this.endpoint = endpoint;
        Set<String> tokens = new HashSet<>();
        properties.getClients().values().forEach(client -> {
            String token = client.getToken();
            if (token != null && !token.isBlank() && (token.length() < 32 || !tokens.add(token)))
                throw new IllegalArgumentException("HTTP 访问密钥必须至少 32 字符，各身份的密钥不能重复");
        });
        if (!properties.getApprovalKey().isBlank() && (properties.getApprovalKey().length() < 32
                || tokens.contains(properties.getApprovalKey())))
            throw new IllegalArgumentException("审批密钥必须至少 32 字符，且与 MCP 访问密钥不同");
        // Only disable HTTP authentication when no identity has a configured key.
        this.anonymousCaller = tokens.isEmpty() ? new Caller("anonymous", "") : null;
    }

    public static boolean matches(String expected, String actual) {
        return expected != null && !expected.isBlank() && actual != null
                && MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        boolean admin = path.startsWith("/admin/");
        if ("/actuator/health".equals(path) && "GET".equals(request.getMethod())) {
            chain.doFilter(request, response);
            return;
        }
        if (admin) {
            if (!matches(properties.getApprovalKey(), request.getHeader("X-Approval-Key"))) {
                reject(response, 401, "Approval authentication required");
                return;
            }
            chain.doFilter(request, response);
            return;
        }
        String authorization = request.getHeader("Authorization");
        String bearer = authorization != null && authorization.startsWith("Bearer ") ? authorization.substring(7) : null;
        Caller caller = anonymousCaller;
        if (caller == null) {
            for (var entry : properties.getClients().entrySet()) {
                var client = entry.getValue();
                if (matches(client.getToken(), bearer)) {
                    caller = new Caller(entry.getKey(), "");
                    break;
                }
            }
        }
        if (caller == null) {
            response.setHeader("WWW-Authenticate", "Bearer");
            reject(response, 401, "Authentication required");
            return;
        }
        if (!path.equals(endpoint)) {
            reject(response, 404, "Not found");
            return;
        }
        String session = request.getHeader("Mcp-Session-Id");
        if (session != null && !caller.subject().equals(sessionOwners.getIfPresent(session))) {
            reject(response, 404, "Session not found or expired");
            return;
        }
        request.setAttribute(CallerResolver.CONTEXT_KEY, caller);
        response.setHeader("Cache-Control", "no-store");
        chain.doFilter(request, response);
        String createdSession = response.getHeader("Mcp-Session-Id");
        if (createdSession != null && response.getStatus() < 400) {
            sessionOwners.asMap().putIfAbsent(createdSession, caller.subject());
        }
        if ("DELETE".equals(request.getMethod()) && session != null && response.getStatus() < 400)
            sessionOwners.invalidate(session);
    }

    private static void reject(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setHeader("Cache-Control", "no-store");
        response.getWriter().write("{\"error\":\"" + message + "\"}");
    }
}
