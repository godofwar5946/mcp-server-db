package org.example.db.datasource;

import org.example.db.config.DbExplorerProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;

/**
 * 多数据源配置：
 * <ul>
 *   <li>构建 {@link DatabaseClientRegistry}（管理全部数据源）</li>
 *   <li>暴露一个主 {@link DataSource} Bean（兼容部分依赖 DataSource 的自动装配）</li>
 * </ul>
 */
@Configuration
public class MultiDataSourceConfiguration {

    @Bean
    public DatabaseClientRegistry databaseClientRegistry(DbExplorerProperties properties) {
        return new DatabaseClientRegistry(properties);
    }

    @Bean
    @Primary
    public DataSource dataSource(DatabaseClientRegistry registry) {
        return registry.getPrimaryDataSource();
    }
}

