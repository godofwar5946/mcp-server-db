package org.example.db.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 数据库工具相关的线程池配置。
 * <p>
 * 说明：
 * <ul>
 *   <li>批量获取表结构时会并发访问数据库系统表</li>
 *   <li>使用固定线程池避免无限制并发压垮数据库</li>
 * </ul>
 */
@Configuration
public class DbExecutorConfiguration {

    @Bean(name = "schemaFetchExecutor", destroyMethod = "shutdown")
    public ExecutorService schemaFetchExecutor(DbExplorerProperties properties) {
        int parallelism = Math.max(1, properties.getSchemaFetchParallelism());
        return Executors.newFixedThreadPool(parallelism, new NamedThreadFactory("schema-fetch-"));
    }

    private static class NamedThreadFactory implements ThreadFactory {
        private final String prefix;
        private final AtomicInteger index = new AtomicInteger(1);

        private NamedThreadFactory(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r);
            t.setName(prefix + index.getAndIncrement());
            t.setDaemon(true);
            return t;
        }
    }
}

