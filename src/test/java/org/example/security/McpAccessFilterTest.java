package org.example.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.mock.web.*;
import static org.assertj.core.api.Assertions.*;

class McpAccessFilterTest {
    private AccessProperties properties(String token) {
        var properties = new AccessProperties();
        var local = new AccessProperties.Client();
        local.setToken(token);
        properties.getClients().put("local", local);
        return properties;
    }

    private MockHttpServletRequest request(String path, String authorization) {
        var request = new MockHttpServletRequest("POST", path);
        if (authorization != null) request.addHeader("Authorization", authorization);
        return request;
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t\n"})
    void missingOrBlankKeysAllowAnonymousRequests(String token) throws Exception {
        var request = request("/mcp", null);
        var chain = new MockFilterChain();
        new McpAccessFilter(properties(token), "/mcp").doFilter(request, new MockHttpServletResponse(), chain);
        assertThat(chain.getRequest()).isSameAs(request);
        var caller = (Caller) request.getAttribute(CallerResolver.CONTEXT_KEY);
        assertThat(caller.subject()).isEqualTo("anonymous");
    }

    @Test void noClientEntriesAllowAnonymousRequests() throws Exception {
        var request = request("/mcp", null);
        new McpAccessFilter(new AccessProperties(), "/mcp")
                .doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
        var caller = (Caller) request.getAttribute(CallerResolver.CONTEXT_KEY);
        assertThat(caller.subject()).isEqualTo("anonymous");
    }

    @Test void anUnusedAuthorizationHeaderDoesNotEnableAuthentication() throws Exception {
        var request = request("/mcp", "Bearer old-client-key");
        var chain = new MockFilterChain();
        new McpAccessFilter(properties(""), "/mcp").doFilter(request, new MockHttpServletResponse(), chain);
        assertThat(chain.getRequest()).isSameAs(request);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"Bearer wrong", "Bearer ", "Basic abc"})
    void anyConfiguredKeyRequiresAuthenticationDespiteOtherEmptyKeys(String authorization) throws Exception {
        var properties = properties("");
        var secured = new AccessProperties.Client();
        secured.setToken("a".repeat(32));
        properties.getClients().put("secured", secured);
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();
        new McpAccessFilter(properties, "/mcp").doFilter(request("/mcp", authorization), response, chain);
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeader("WWW-Authenticate")).isEqualTo("Bearer");
        assertThat(chain.getRequest()).isNull();
    }

    @Test void validKeyUsesTheAuthenticatedIdentity() throws Exception {
        var request = request("/mcp", "Bearer " + "a".repeat(32));
        new McpAccessFilter(properties("a".repeat(32)), "/mcp")
                .doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
        var caller = (Caller) request.getAttribute(CallerResolver.CONTEXT_KEY);
        assertThat(caller.subject()).isEqualTo("local");
    }

    @Test void blankApprovalKeyDoesNotOpenAdminEndpoints() throws Exception {
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();
        new McpAccessFilter(properties(""), "/mcp")
                .doFilter(request("/admin/sql/token/approval", null), response, chain);
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(chain.getRequest()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"})
    void originHeaderDoesNotRequireAnAllowlist(String token) throws Exception {
        var request = request("/mcp", token.isEmpty() ? null : "Bearer " + token);
        request.addHeader("Origin", "https://client.example");
        var chain = new MockFilterChain();
        new McpAccessFilter(properties(token), "/mcp").doFilter(request, new MockHttpServletResponse(), chain);
        assertThat(chain.getRequest()).isSameAs(request);
    }
}
