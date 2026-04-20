package com.stresstest.spring.config;

import com.atomikos.jdbc.AtomikosDataSourceBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.util.Properties;

/**
 * Atomikos XA DataSource 設定。
 * <p>
 * 使用 oracle.jdbc.xa.client.OracleXADataSource 提供三個 XA 連線池：
 * <ul>
 *   <li>primaryDataSource  — 主資料庫（upload_case、file_detail、biz_x_detail）</li>
 *   <li>domainADataSource  — Domain-A（approved_file_detail）</li>
 *   <li>domainBDataSource  — Domain-B（approved_file_detail）</li>
 * </ul>
 * <p>
 * 非 DBA 角色說明：
 * Oracle XA 的 in-doubt transaction 恢復需要 SELECT ON sys.dba_pending_transactions 等 DBA 授權。
 * 本設定透過 {@code spring.jta.atomikos.properties.enable-logging=false} 停用 Atomikos
 * 持久化日誌及自動恢復，使應用程式以一般非 DBA 使用者（stresstest）即可完整運作。
 */
@Configuration
public class AtomikosDataSourceConfig {

    // ── 主資料庫 ────────────────────────────────────────────────────────────
    @Value("${spring.datasource.url}")
    private String primaryUrl;
    @Value("${spring.datasource.username}")
    private String primaryUsername;
    @Value("${spring.datasource.password}")
    private String primaryPassword;

    // ── Domain-A 資料庫 ────────────────────────────────────────────────────
    @Value("${domain.datasource.a.jdbc-url}")
    private String domainAUrl;
    @Value("${domain.datasource.a.username}")
    private String domainAUsername;
    @Value("${domain.datasource.a.password}")
    private String domainAPassword;

    // ── Domain-B 資料庫 ────────────────────────────────────────────────────
    @Value("${domain.datasource.b.jdbc-url}")
    private String domainBUrl;
    @Value("${domain.datasource.b.username}")
    private String domainBUsername;
    @Value("${domain.datasource.b.password}")
    private String domainBPassword;

    @Bean(name = "dataSource", initMethod = "init", destroyMethod = "close")
    @Primary
    public AtomikosDataSourceBean dataSource() {
        return buildAtomikosDs(
                "primaryXA",
                primaryUrl, primaryUsername, primaryPassword,
                10, 2);
    }

    @Bean(name = "domainADataSource", initMethod = "init", destroyMethod = "close")
    public AtomikosDataSourceBean domainADataSource() {
        return buildAtomikosDs(
                "domainAXA",
                domainAUrl, domainAUsername, domainAPassword,
                5, 1);
    }

    @Bean(name = "domainBDataSource", initMethod = "init", destroyMethod = "close")
    public AtomikosDataSourceBean domainBDataSource() {
        return buildAtomikosDs(
                "domainBXA",
                domainBUrl, domainBUsername, domainBPassword,
                5, 1);
    }

    private AtomikosDataSourceBean buildAtomikosDs(
            String uniqueName,
            String url, String username, String password,
            int maxPool, int minPool) {

        AtomikosDataSourceBean ds = new AtomikosDataSourceBean();
        ds.setUniqueResourceName(uniqueName);
        ds.setXaDataSourceClassName("oracle.jdbc.xa.client.OracleXADataSource");

        Properties xaProps = new Properties();
        xaProps.put("URL", url);
        xaProps.put("user", username);
        xaProps.put("password", password);
        ds.setXaProperties(xaProps);

        ds.setMaxPoolSize(maxPool);
        ds.setMinPoolSize(minPool);
        ds.setTestQuery("SELECT 1 FROM DUAL");
        // 借出連線時等待時間（秒）
        ds.setBorrowConnectionTimeout(30);
        // 連線最長閒置時間（秒）
        ds.setMaxIdleTime(300);

        return ds;
    }
}
