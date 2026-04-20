package com.stresstest.spring.config;

import com.stresstest.spring.service.LatencySimulator;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DelegatingDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * 自動將 DataSource 包裝為 LatencyDataSource，
 * 使所有 Connection（含 Hibernate / EntityManager 內部取得的）都經過延遲模擬。
 * <p>
 * 當 {@code LatencySimulator.getLatencyMs() == 0} 時，
 * Connection 仍會通過 Proxy，但不會有任何 sleep，overhead 幾乎為零。
 */
@Configuration
public class LatencyDataSourceConfig {

    @Bean
    public static BeanPostProcessor latencyDataSourcePostProcessor() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
                if (bean instanceof DataSource && !(bean instanceof LatencyDataSource)) {
                    return new LatencyDataSource((DataSource) bean);
                }
                return bean;
            }
        };
    }

    /**
     * 繼承 Spring 的 DelegatingDataSource，僅覆寫 getConnection() 以包裝 Connection。
     */
    static class LatencyDataSource extends DelegatingDataSource {

        LatencyDataSource(DataSource delegate) {
            super(delegate);
        }

        @Override
        public Connection getConnection() throws SQLException {
            return LatencySimulator.wrapConnection(super.getConnection());
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return LatencySimulator.wrapConnection(super.getConnection(username, password));
        }
    }
}
