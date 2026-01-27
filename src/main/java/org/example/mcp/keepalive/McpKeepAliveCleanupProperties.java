package org.example.mcp.keepalive;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * MCP Streamable HTTP 的 keep-alive 优化配置。
 * <p>
 * 背景：
 * <ul>
 *   <li>Spring AI MCP Server（WebMVC Streamable HTTP）底层使用 MCP SDK 的 {@code KeepAliveScheduler} 定时向会话发送 {@code ping}。</li>
 *   <li>当客户端（例如 Codex）异常断开且没有正常调用 DELETE 关闭会话时，服务端仍会保留会话并持续尝试 ping。</li>
 *   <li>SDK 默认行为是“记录告警日志 + 吞掉异常”，不会自动移除失效会话，容易导致日志刷屏与无效任务占用资源。</li>
 * </ul>
 * 本项目在不修改三方依赖的前提下做“补丁式”优化：当 ping 失败/超时时，主动关闭并移除会话。
 */
@ConfigurationProperties(prefix = "app.mcp.keep-alive")
public class McpKeepAliveCleanupProperties {

    /**
     * 是否启用 keep-alive 断连会话自动清理（默认启用）。
     */
    private boolean enabled = true;

    /**
     * ping 发送超时时间。
     * <p>
     * 超过该时间仍未完成，认为连接已异常，触发清理逻辑。
     */
    private Duration pingTimeout = Duration.ofSeconds(5);

    /**
     * ping 失败时是否立即清理会话（默认 true）。
     * <p>
     * 一般情况下，ping 失败意味着客户端已经断开或网络链路不可用，继续保留会话只会带来噪音与资源浪费。
     */
    private boolean cleanupOnFailure = true;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Duration getPingTimeout() {
        return pingTimeout;
    }

    public void setPingTimeout(Duration pingTimeout) {
        this.pingTimeout = pingTimeout;
    }

    public boolean isCleanupOnFailure() {
        return cleanupOnFailure;
    }

    public void setCleanupOnFailure(boolean cleanupOnFailure) {
        this.cleanupOnFailure = cleanupOnFailure;
    }
}

