package org.example.mcp.keepalive;

import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.spec.McpStreamableServerSession;
import io.modelcontextprotocol.server.transport.WebMvcStreamableServerTransportProvider;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerStreamableHttpProperties;
import reactor.core.scheduler.Schedulers;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * MCP KeepAlive 断连会话清理器（补丁逻辑）。
 * <p>
 * 目标：
 * <ul>
 *   <li>Codex/客户端异常断开后，避免 {@code Failed to send keep-alive ping...} 日志持续刷屏</li>
 *   <li>及时移除失效会话，避免 keep-alive 定时任务持续对“僵尸会话”执行 ping 占用资源</li>
 * </ul>
 *
 * <p>实现方式：</p>
 * <ul>
 *   <li>通过 {@link WebMvcStreamableServerTransportProvider} 的私有字段获取会话 Map（反射）。</li>
 *   <li>停止 SDK 内置的 {@code KeepAliveScheduler}（反射调用 shutdown），避免重复 ping。</li>
 *   <li>使用本项目的单线程定时任务发送 ping；当 ping 失败/超时，移除并关闭会话。</li>
 * </ul>
 *
 * <p>为什么需要反射？</p>
 * <ul>
 *   <li>当前版本 SDK 未提供“keep-alive 失败后自动删除会话”的钩子/配置项。</li>
 *   <li>服务端会话仅在客户端显式 DELETE 时移除；异常断开时会变成“僵尸会话”。</li>
 * </ul>
 */
public class McpKeepAliveCleaner {

    private static final Logger log = LoggerFactory.getLogger(McpKeepAliveCleaner.class);

    private static final String PING_METHOD = "ping";
    private static final TypeRef<Object> OBJECT_TYPE_REF = new TypeRef<>() {
    };

    private final McpServerStreamableHttpProperties streamableHttpProperties;
    private final McpKeepAliveCleanupProperties cleanupProperties;

    private final AtomicBoolean attached = new AtomicBoolean(false);

    private volatile WebMvcStreamableServerTransportProvider provider;
    private volatile ConcurrentHashMap<String, McpStreamableServerSession> sessions;

    /**
     * 防止同一会话在上一次 ping 尚未结束时又被重复 ping（避免并发堆积）。
     */
    private final ConcurrentHashMap<String, AtomicBoolean> pingInFlight = new ConcurrentHashMap<>();

    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> future;

    public McpKeepAliveCleaner(McpServerStreamableHttpProperties streamableHttpProperties,
                              McpKeepAliveCleanupProperties cleanupProperties) {
        this.streamableHttpProperties = streamableHttpProperties;
        this.cleanupProperties = cleanupProperties;
    }

    /**
     * 绑定 Streamable HTTP Transport Provider（由 BeanPostProcessor 调用）。
     */
    public void attach(WebMvcStreamableServerTransportProvider provider) {
        if (!cleanupProperties.isEnabled()) {
            return;
        }
        if (!attached.compareAndSet(false, true)) {
            return;
        }

        this.provider = provider;
        this.sessions = tryExtractSessions(provider);
        if (this.sessions == null) {
            log.warn("无法获取 MCP sessions 容器，keep-alive 会话清理器不生效（请检查 SDK 版本是否变更）。");
            return;
        }

        // 停止 SDK 自带 keep-alive，避免重复 ping + 重复日志
        // 注意：这里调用 stop() 而不是 shutdown()，避免把 Reactor 的 boundedElastic 全局调度器一并 dispose 掉
        if (!stopSdkKeepAliveIfPresent(provider)) {
            log.warn("无法停止 SDK keep-alive，自定义清理器不启动，避免重复心跳。");
            return;
        }

        Duration interval = streamableHttpProperties.getKeepAliveInterval();
        if (interval == null || interval.isZero() || interval.isNegative()) {
            log.info("未配置 spring.ai.mcp.server.streamable-http.keep-alive-interval，自定义 keep-alive 清理器不启动。");
            return;
        }

        startScheduler(interval);
        log.info("MCP keep-alive 会话清理器已启用：interval={}, pingTimeout={}, cleanupOnFailure={}",
                interval, cleanupProperties.getPingTimeout(), cleanupProperties.isCleanupOnFailure());
    }

    private void startScheduler(Duration interval) {
        if (scheduler != null) {
            return;
        }
        this.scheduler = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
            private final AtomicInteger seq = new AtomicInteger(1);

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "mcp-keepalive-cleaner-" + seq.getAndIncrement());
                t.setDaemon(true);
                return t;
            }
        });

        long initialDelayMs = interval.toMillis(); // 与 SDK 行为保持一致：首次延迟=interval
        long intervalMs = interval.toMillis();
        this.future = scheduler.scheduleWithFixedDelay(this::tickSafely, initialDelayMs, intervalMs, TimeUnit.MILLISECONDS);
    }

    private void tickSafely() {
        try {
            tick();
        } catch (Throwable t) {
            log.warn("MCP keep-alive 清理任务执行异常：{}", t.getMessage(), t);
        }
    }

    private void tick() {
        ConcurrentHashMap<String, McpStreamableServerSession> currentSessions = this.sessions;
        if (currentSessions == null || currentSessions.isEmpty()) {
            pingInFlight.clear();
            return;
        }
        pingInFlight.keySet().removeIf(id -> !currentSessions.containsKey(id));

        Duration timeout = Objects.requireNonNullElse(cleanupProperties.getPingTimeout(), Duration.ofSeconds(5));

        for (Map.Entry<String, McpStreamableServerSession> entry : currentSessions.entrySet()) {
            String sessionId = entry.getKey();
            McpStreamableServerSession session = entry.getValue();
            if (session == null) {
                continue;
            }

            AtomicBoolean inFlight = pingInFlight.computeIfAbsent(sessionId, k -> new AtomicBoolean(false));
            if (!inFlight.compareAndSet(false, true)) {
                continue;
            }

            session.sendRequest(PING_METHOD, null, OBJECT_TYPE_REF)
                    .timeout(timeout)
                    .doOnError(ex -> onPingFailure(sessionId, session, ex))
                    .doFinally(signalType -> {
                        AtomicBoolean flag = pingInFlight.get(sessionId);
                        if (flag != null) {
                            flag.set(false);
                        }
                    })
                    .onErrorComplete()
                    // 让 ping 的发送逻辑在 Reactor 线程池中执行，避免阻塞定时线程
                    .subscribeOn(Schedulers.boundedElastic())
                    .subscribe();
        }
    }

    /**
     * ping 失败后的清理逻辑。
     *
     * @return 是否成功移除了会话（便于测试/诊断）
     */
    boolean onPingFailure(String sessionId, McpStreamableServerSession session, Throwable ex) {
        if (!cleanupProperties.isCleanupOnFailure()) {
            return false;
        }

        ConcurrentHashMap<String, McpStreamableServerSession> currentSessions = this.sessions;
        if (currentSessions == null) {
            return false;
        }

        // remove(key, value) 能避免并发下误删新会话/替换值
        boolean removed = currentSessions.remove(sessionId, session);
        if (!removed) {
            return false;
        }
        pingInFlight.remove(sessionId);

        try {
            // 断连场景下 closeGracefully 可能也会失败；这里用 close() 做兜底释放
            session.close();
        } catch (Exception ignore) {
            // ignore
        }

        log.info("检测到 MCP 客户端已断开，已清理会话 sessionId={}，原因：{}", sessionId, ex.getMessage());
        return true;
    }

    @SuppressWarnings("unchecked")
    private static ConcurrentHashMap<String, McpStreamableServerSession> tryExtractSessions(WebMvcStreamableServerTransportProvider provider) {
        try {
            Field f = WebMvcStreamableServerTransportProvider.class.getDeclaredField("sessions");
            f.setAccessible(true);
            return (ConcurrentHashMap<String, McpStreamableServerSession>) f.get(provider);
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean stopSdkKeepAliveIfPresent(WebMvcStreamableServerTransportProvider provider) {
        try {
            Field f = WebMvcStreamableServerTransportProvider.class.getDeclaredField("keepAliveScheduler");
            f.setAccessible(true);
            Object keepAliveScheduler = f.get(provider);
            if (keepAliveScheduler == null) {
                return true;
            }
            keepAliveScheduler.getClass().getMethod("stop").invoke(keepAliveScheduler);
            return true;
        } catch (Exception e) {
            // 关闭失败不影响启动：最多回退到 SDK 默认行为
            log.debug("停止 SDK KeepAliveScheduler 失败（将回退为默认 keep-alive 行为）：{}", e.getMessage());
            return false;
        }
    }

    @PreDestroy
    public void shutdown() {
        if (future != null) {
            future.cancel(true);
        }
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }
}
