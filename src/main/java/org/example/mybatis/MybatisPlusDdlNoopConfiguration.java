package org.example.mybatis;

import com.baomidou.mybatisplus.extension.ddl.IDdl;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.util.List;
import java.util.function.Consumer;

/**
 * MyBatis-Plus 与 Spring Boot 3.4 的兼容性配置。
 * <p>
 * 背景：
 * <ul>
 *   <li>mybatis-plus-boot-starter 3.5.3 内置了一个名为 {@code ddlApplicationRunner} 的 @Bean。</li>
 *   <li>当 Spring 容器里没有任何 {@link IDdl} 实现时，该 @Bean 会返回 {@code null}（Spring 会注册为 NullBean）。</li>
 *   <li>在 Spring Boot 3.4 引入 {@code org.springframework.boot.Runner} 之后，
 *   启动阶段会统一收集并执行 Runner；此时 NullBean 会导致类型不匹配异常：</li>
 * </ul>
 * <pre>
 * BeanNotOfRequiredTypeException: Bean named 'ddlApplicationRunner' is expected to be of type
 * 'org.springframework.boot.Runner' but was actually of type 'org.springframework.beans.factory.support.NullBean'
 * </pre>
 * 解决方式：
 * <ul>
 *   <li>提供一个“空实现”的 {@link IDdl}，避免 mybatis-plus 返回 null，从而不产生 NullBean。</li>
 *   <li>该空实现不会执行任何 DDL（runScript 直接 no-op）。</li>
 * </ul>
 * <p>
 * 如果你的项目确实需要 MyBatis-Plus DDL 自动执行，请自行提供真实的 {@link IDdl} 实现并移除此兼容 Bean。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(IDdl.class)
public class MybatisPlusDdlNoopConfiguration {

    @Bean
    @ConditionalOnMissingBean(IDdl.class)
    public IDdl noopDdl() {
        return new IDdl() {
            @Override
            public void runScript(Consumer<DataSource> consumer) {
                // no-op：不执行任何 DDL
            }

            @Override
            public List<String> getSqlFiles() {
                // 不提供任何 SQL 文件
                return List.of();
            }
        };
    }
}

