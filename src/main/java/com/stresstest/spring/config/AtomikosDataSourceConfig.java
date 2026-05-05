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
 * 使用 oracle.jdbc.xa.client.OracleXADataSource 提供多個 XA 連線池：
 * <ul>
 *   <li>primaryDataSource  — 主資料庫 / GLOBAL Area（upload_case、file_detail、biz_x_detail）</li>
 *   <li>domainADataSource  — Domain-A（approved_file_detail）</li>
 *   <li>domainBDataSource  — Domain-B（approved_file_detail）</li>
 *   <li>twAreaDataSource   — Host A / TW Area（本情境先保留配置）</li>
 *   <li>usAreaDataSource   — Host B / US Area（US 上傳結果）</li>
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

    // ── Instance-A 資料庫（第二個 Oracle instance，cross-DB task 用） ────
    @Value("${instance.a.jdbc-url}")
    private String instanceAUrl;
    @Value("${instance.a.username}")
    private String instanceAUsername;
    @Value("${instance.a.password}")
    private String instanceAPassword;

    // ── Area 資料庫：Host A / TW、Host B / US ────────────────────────
    @Value("${area.datasource.tw.jdbc-url}")
    private String twAreaUrl;
    @Value("${area.datasource.tw.username}")
    private String twAreaUsername;
    @Value("${area.datasource.tw.password}")
    private String twAreaPassword;

    @Value("${area.datasource.us.jdbc-url}")
    private String usAreaUrl;
    @Value("${area.datasource.us.username}")
    private String usAreaUsername;
    @Value("${area.datasource.us.password}")
    private String usAreaPassword;

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

    /** 第二個 Oracle instance — 跨庫任務的對端。 */
    @Bean(name = "instanceADataSource", initMethod = "init", destroyMethod = "close")
    public AtomikosDataSourceBean instanceADataSource() {
        return buildAtomikosDs(
                "instanceAXA",
                instanceAUrl, instanceAUsername, instanceAPassword,
                5, 1);
    }

<<<<<<< HEAD
    /** Host A / TW Area — 本次 US 上傳情境先保留 XA Resource 配置。 */
    @Bean(name = "twAreaDataSource", initMethod = "init", destroyMethod = "close")
    public AtomikosDataSourceBean twAreaDataSource() {
        return buildAtomikosDs(
                "twAreaXA",
                twAreaUrl, twAreaUsername, twAreaPassword,
                5, 1);
    }

    /** Host B / US Area — US 客戶上傳結果寫入目標。 */
    @Bean(name = "usAreaDataSource", initMethod = "init", destroyMethod = "close")
    public AtomikosDataSourceBean usAreaDataSource() {
        return buildAtomikosDs(
                "usAreaXA",
                usAreaUrl, usAreaUsername, usAreaPassword,
                5, 1);
    }

=======
>>>>>>> aa4f7e2 (555)
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
