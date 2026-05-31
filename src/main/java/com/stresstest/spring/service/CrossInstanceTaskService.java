package com.stresstest.spring.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * 跨 Oracle instance 的雙向任務示範。
 *
 *  情境：「現有 instance」(primaryDataSource) 與「新加入的 Instance-A」(instanceADataSource)
 *        各自獨立，但需要互相引用對方 row 的 PK。
 *
 *  Task 包含 2 個 transaction：
 *
 *    TX-A2P (forward / A → Primary)
 *      step1  在 Instance-A insert 一筆 → 由 sequence 取得 instanceAId
 *      step2  在 Primary update cross_link_record.peer_a_id = instanceAId WHERE id = primaryId
 *      （兩個 DB 的 SQL 包在同一 JTA / XA 交易，2PC commit 同生同死）
 *
 *    TX-P2A (reverse / Primary → A)
 *      step1  在 Primary insert 一筆 → 由 sequence 取得 primaryId
 *      step2  在 Instance-A update iax_record.peer_primary_id = primaryId WHERE id = instanceAId
 *      （同樣是同一 JTA / XA 交易）
 *
 *  注意：
 *    - 兩個資料源都是 XA (AtomikosDataSourceBean)，由 Atomikos 透過 2PC 保證跨庫原子性
 *    - prepare/commit 任一方失敗 → 兩邊都 rollback
 *    - 大筆數時切勿把 1M 筆 update 包成單一 cross-DB XA：必須分批（題目限制 timeout=10s）
 */
@Service
public class CrossInstanceTaskService {

    private static final Logger log = LoggerFactory.getLogger(CrossInstanceTaskService.class);

    @Autowired
    @Qualifier("dataSource")
    private DataSource primaryDs;

    @Autowired
    @Qualifier("instanceADataSource")
    private DataSource instanceADs;

    @Autowired
    @Lazy
    private CrossInstanceTaskService self;

    private volatile boolean schemaReady = false;

    // ─────────────────────────────────────────────────────────────────────────
    // Schema：兩邊各建一張對映表
    //   Primary.cross_link_record   (id PK by seq, payload, peer_a_id)
    //   Instance-A.iax_record       (id PK by seq, payload, peer_primary_id)
    // ─────────────────────────────────────────────────────────────────────────
    private synchronized void ensureSchema() throws SQLException {
        if (schemaReady) return;

        // Primary 端
        try (Connection c = primaryDs.getConnection();
             Statement st = c.createStatement()) {
            execIgnore(st, "CREATE SEQUENCE cross_link_record_seq START WITH 1 INCREMENT BY 1 NOCACHE", 955);
            execIgnore(st,
                    "CREATE TABLE cross_link_record (" +
                            "  id          NUMBER PRIMARY KEY, " +
                            "  payload     VARCHAR2(200), " +
                            "  peer_a_id   NUMBER, " +                // 指向 instance-A.iax_record.id
                            "  created_at  TIMESTAMP DEFAULT SYSTIMESTAMP, " +
                            "  updated_at  TIMESTAMP)",
                    955);
        }

        // Instance-A 端
        try (Connection c = instanceADs.getConnection();
             Statement st = c.createStatement()) {
            execIgnore(st, "CREATE SEQUENCE iax_record_seq START WITH 1 INCREMENT BY 1 NOCACHE", 955);
            execIgnore(st,
                    "CREATE TABLE iax_record (" +
                            "  id              NUMBER PRIMARY KEY, " +
                            "  payload         VARCHAR2(200), " +
                            "  peer_primary_id NUMBER, " +            // 指向 primary.cross_link_record.id
                            "  created_at      TIMESTAMP DEFAULT SYSTIMESTAMP, " +
                            "  updated_at      TIMESTAMP)",
                    955);
        }

        log.info("cross-instance schema ready: primary.cross_link_record / instance_a.iax_record");
        schemaReady = true;
    }

    private static void execIgnore(Statement st, String ddl, int ignoreCode) throws SQLException {
        try { st.execute(ddl); }
        catch (SQLException e) { if (e.getErrorCode() != ignoreCode) throw e; }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 入口：執行兩個 transaction（依序）
    //   外層不開交易，分別呼叫 self.txA2P(...) 與 self.txP2A(...)
    //   各自為獨立的 XA 交易，互不影響
    // ─────────────────────────────────────────────────────────────────────────
    @Transactional(propagation = Propagation.NEVER)
    public long[] runTask(long existingPrimaryId, long existingInstanceAId,
                          String payloadForA, String payloadForPrimary) throws SQLException {
        ensureSchema();

        long newAId = self.txA2P_insertA_updatePrimary(existingPrimaryId, payloadForA);
        log.info("[Task] TX-A2P done: 在 Instance-A 新增 id={}，並回填 primary.id={}.peer_a_id", newAId, existingPrimaryId);

        long newPrimaryId = self.txP2A_insertPrimary_updateA(existingInstanceAId, payloadForPrimary);
        log.info("[Task] TX-P2A done: 在 Primary 新增 id={}，並回填 instance_a.id={}.peer_primary_id", newPrimaryId, existingInstanceAId);

        return new long[]{newAId, newPrimaryId};
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TX-A2P：Instance-A insert → Primary update
    //   單一 JTA/XA 交易，兩邊任一失敗 → 雙邊 rollback
    // ─────────────────────────────────────────────────────────────────────────
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public long txA2P_insertA_updatePrimary(long primaryId, String payload) throws SQLException {
        long instanceAId;

        // step1: Instance-A INSERT
        try (Connection ca = instanceADs.getConnection()) {
            instanceAId = nextVal(ca, "iax_record_seq");
            try (PreparedStatement ps = ca.prepareStatement(
                    "INSERT INTO iax_record (id, payload) VALUES (?, ?)")) {
                ps.setLong(1, instanceAId);
                ps.setString(2, payload);
                ps.executeUpdate();
            }
        }
        log.info("[TX-A2P] step1 instance_a.iax_record inserted id={}", instanceAId);

        // step2: Primary UPDATE
        try (Connection cp = primaryDs.getConnection();
             PreparedStatement ps = cp.prepareStatement(
                     "UPDATE cross_link_record SET peer_a_id = ?, updated_at = SYSTIMESTAMP WHERE id = ?")) {
            ps.setLong(1, instanceAId);
            ps.setLong(2, primaryId);
            int n = ps.executeUpdate();
            if (n != 1) {
                throw new SQLException("[TX-A2P] step2 primary.cross_link_record id=" + primaryId
                        + " 更新筆數 " + n + " ≠ 1（可能 row 不存在）→ 整個 XA 交易 rollback");
            }
        }
        log.info("[TX-A2P] step2 primary.cross_link_record id={} peer_a_id={} updated", primaryId, instanceAId);
        return instanceAId;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TX-P2A：反向處理 — Primary insert → Instance-A update
    // ─────────────────────────────────────────────────────────────────────────
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public long txP2A_insertPrimary_updateA(long instanceAId, String payload) throws SQLException {
        long primaryId;

        // step1: Primary INSERT
        try (Connection cp = primaryDs.getConnection()) {
            primaryId = nextVal(cp, "cross_link_record_seq");
            try (PreparedStatement ps = cp.prepareStatement(
                    "INSERT INTO cross_link_record (id, payload) VALUES (?, ?)")) {
                ps.setLong(1, primaryId);
                ps.setString(2, payload);
                ps.executeUpdate();
            }
        }
        log.info("[TX-P2A] step1 primary.cross_link_record inserted id={}", primaryId);

        // step2: Instance-A UPDATE
        try (Connection ca = instanceADs.getConnection();
             PreparedStatement ps = ca.prepareStatement(
                     "UPDATE iax_record SET peer_primary_id = ?, updated_at = SYSTIMESTAMP WHERE id = ?")) {
            ps.setLong(1, primaryId);
            ps.setLong(2, instanceAId);
            int n = ps.executeUpdate();
            if (n != 1) {
                throw new SQLException("[TX-P2A] step2 instance_a.iax_record id=" + instanceAId
                        + " 更新筆數 " + n + " ≠ 1（可能 row 不存在）→ 整個 XA 交易 rollback");
            }
        }
        log.info("[TX-P2A] step2 instance_a.iax_record id={} peer_primary_id={} updated", instanceAId, primaryId);
        return primaryId;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 種子資料：建立兩邊的初始 row（供 demo 端點使用）
    // ─────────────────────────────────────────────────────────────────────────
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public long[] seed(String payload) throws SQLException {
        ensureSchema();
        long primaryId, instanceAId;
        try (Connection cp = primaryDs.getConnection()) {
            primaryId = nextVal(cp, "cross_link_record_seq");
            try (PreparedStatement ps = cp.prepareStatement(
                    "INSERT INTO cross_link_record (id, payload) VALUES (?, ?)")) {
                ps.setLong(1, primaryId);
                ps.setString(2, "SEED-P-" + payload);
                ps.executeUpdate();
            }
        }
        try (Connection ca = instanceADs.getConnection()) {
            instanceAId = nextVal(ca, "iax_record_seq");
            try (PreparedStatement ps = ca.prepareStatement(
                    "INSERT INTO iax_record (id, payload) VALUES (?, ?)")) {
                ps.setLong(1, instanceAId);
                ps.setString(2, "SEED-A-" + payload);
                ps.executeUpdate();
            }
        }
        log.info("[seed] primaryId={} instanceAId={}", primaryId, instanceAId);
        return new long[]{primaryId, instanceAId};
    }

    private static long nextVal(Connection c, String seqName) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT " + seqName + ".NEXTVAL FROM dual");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
