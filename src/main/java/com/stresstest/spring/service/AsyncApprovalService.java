package com.stresstest.spring.service;

import com.stresstest.spring.dao.CaseRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;

/**
 * 模擬「@Async 放行」並在內部執行兩個獨立 @Transactional 的情境：
 * <ul>
 *   <li><b>Tx1</b> 寫 parent table {@code approval_master}，PK 由 Oracle sequence
 *       {@code approval_master_seq} 產生 — Tx1 必須 commit 後把該 key 回傳。</li>
 *   <li><b>Tx2</b> 寫 child table {@code approval_detail}，其中 {@code master_id}
 *       欄位必須帶入 Tx1 產生的 key（FK 指向 approval_master.id）。</li>
 * </ul>
 *
 * <h3>觀察重點</h3>
 * <ol>
 *   <li>Tx1 與 Tx2 各自獨立（{@code REQUIRES_NEW}），分屬兩個 JTA / Atomikos XA 交易。</li>
 *   <li>Tx1 commit 後，sequence-generated key 才能被 Tx2 看到（FK 才存在）。</li>
 *   <li>{@code simulateFailure=true}：Tx2 在寫完 child row 後拋 RuntimeException →
 *       Atomikos rollback Tx2，但 Tx1 已 commit → 出現「parent 有、child 沒有」的孤兒 master row。</li>
 * </ol>
 */
@Service
public class AsyncApprovalService {

    private static final Logger log = LoggerFactory.getLogger(AsyncApprovalService.class);

    @Autowired
    @Qualifier("dataSource")
    private DataSource primaryDataSource;

    @Autowired
    private CaseRepository caseRepository;

    /** Self-injection：透過代理呼叫，確保 @Transactional AOP 生效。 */
    @Autowired
    @Lazy
    private AsyncApprovalService self;

    private volatile boolean schemaReady = false;

    private synchronized void ensureSchema() throws SQLException {
        if (schemaReady) return;
        try (Connection conn = primaryDataSource.getConnection();
             Statement stmt = conn.createStatement()) {

            execIgnoreExists(stmt,
                    "CREATE SEQUENCE approval_master_seq START WITH 1 INCREMENT BY 1 NOCACHE");

            execIgnoreExists(stmt,
                    "CREATE TABLE approval_master (" +
                            "id NUMBER PRIMARY KEY, " +
                            "case_id NUMBER NOT NULL, " +
                            "tx_label VARCHAR2(40), " +
                            "thread_name VARCHAR2(120), " +
                            "log_time TIMESTAMP DEFAULT SYSTIMESTAMP)");

            execIgnoreExists(stmt,
                    "CREATE TABLE approval_detail (" +
                            "id NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY, " +
                            "master_id NUMBER NOT NULL, " +
                            "case_id NUMBER NOT NULL, " +
                            "tx_label VARCHAR2(40), " +
                            "thread_name VARCHAR2(120), " +
                            "log_time TIMESTAMP DEFAULT SYSTIMESTAMP, " +
                            "FOREIGN KEY (master_id) REFERENCES approval_master(id))");

            // ── 新增：approval_detail_a / approval_detail_b ─────────────────────
            // detail_a 的 PK 由 sequence 產生，detail_b 之後要回填這個 PK
            execIgnoreExists(stmt,
                    "CREATE SEQUENCE approval_detail_a_seq START WITH 1 INCREMENT BY 1 NOCACHE");

            execIgnoreExists(stmt,
                    "CREATE TABLE approval_detail_a (" +
                            "id NUMBER PRIMARY KEY, " +
                            "master_id NUMBER NOT NULL, " +
                            "case_id NUMBER NOT NULL, " +
                            "payload VARCHAR2(200), " +
                            "log_time TIMESTAMP DEFAULT SYSTIMESTAMP, " +
                            "CONSTRAINT fk_det_a_master FOREIGN KEY (master_id) REFERENCES approval_master(id))");

            // detail_b.detail_a_id 一開始為 NULL，最後一步才 UPDATE 回填
            execIgnoreExists(stmt,
                    "CREATE TABLE approval_detail_b (" +
                            "id NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY, " +
                            "master_id NUMBER NOT NULL, " +
                            "case_id NUMBER NOT NULL, " +
                            "detail_a_id NUMBER, " +
                            "payload VARCHAR2(200), " +
                            "log_time TIMESTAMP DEFAULT SYSTIMESTAMP, " +
                            "CONSTRAINT fk_det_b_master FOREIGN KEY (master_id) REFERENCES approval_master(id), " +
                            "CONSTRAINT fk_det_b_det_a FOREIGN KEY (detail_a_id) REFERENCES approval_detail_a(id))");

            log.info("schema ready: approval_master(_seq) / approval_detail / approval_detail_a(_seq) / approval_detail_b");
        }
        schemaReady = true;
    }

    private void execIgnoreExists(Statement stmt, String ddl) throws SQLException {
        try {
            stmt.execute(ddl);
        } catch (SQLException e) {
            // ORA-00955 name already used / ORA-02264 constraint name already in use
            int code = e.getErrorCode();
            if (code != 955 && code != 2264) throw e;
        }
    }

    /**
     * @Async 入口：Tx1 → Tx2，並把 Tx1 產生的 PK 傳給 Tx2 當 FK。
     */
    @Async
    public void asyncApproveTwoTransactions(Long caseId, boolean simulateFailure) {
        log.info("[ASYNC ENTRY] thread={} caseId={} simulateFailure={}",
                Thread.currentThread().getName(), caseId, simulateFailure);

        try {
            ensureSchema();
        } catch (SQLException e) {
            log.error("[ASYNC] 初始化 schema 失敗", e);
            return;
        }

        Long masterId;
        try {
            masterId = self.tx1InsertMaster(caseId);
            log.info("[ASYNC] Tx1 commit 完成，masterId={}", masterId);
        } catch (Exception e) {
            log.error("[ASYNC] Tx1 失敗：{} - {}",
                    e.getClass().getSimpleName(), e.getMessage(), e);
            return;
        }

        try {
            self.tx2InsertDetail(caseId, masterId, simulateFailure);
            log.info("[ASYNC] Tx2 commit 完成，detail 寫入 master_id={}", masterId);
        } catch (Exception e) {
            log.warn("[ASYNC] Tx2 例外（預期 child rollback）：{} - {}",
                    e.getClass().getName(), e.getMessage());
        }

        log.info("[ASYNC EXIT] thread={} caseId={} masterId={} —— "
                        + "若 simulateFailure=true，approval_master 留下孤兒 row，approval_detail 無對應 row。",
                Thread.currentThread().getName(), caseId, masterId);
    }

    /**
     * Tx1：寫 parent，PK 由 sequence 產生並回傳。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Long tx1InsertMaster(Long caseId) throws SQLException {
        log.info("[Tx1] 進入交易 thread={}", Thread.currentThread().getName());

        long masterId;
        try (Connection conn = primaryDataSource.getConnection()) {

            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT approval_master_seq.NEXTVAL FROM dual");
                 ResultSet rs = ps.executeQuery()) {
                rs.next();
                masterId = rs.getLong(1);
            }
            log.info("[Tx1] sequence 取得 masterId={}", masterId);

            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO approval_master (id, case_id, tx_label, thread_name) " +
                            "VALUES (?, ?, ?, ?)")) {
                ps.setLong(1, masterId);
                ps.setLong(2, caseId);
                ps.setString(3, "TX1_MASTER");
                ps.setString(4, Thread.currentThread().getName());
                ps.executeUpdate();
            }
        }

        caseRepository.findById(caseId).ifPresent(c -> {
            c.setStatus("APPROVED_ASYNC_TX1");
            c.setApproveTime(new Timestamp(System.currentTimeMillis()));
            caseRepository.save(c);
        });

        log.info("[Tx1] 結束（即將 commit）masterId={}", masterId);
        return masterId;
    }

    /**
     * Tx2：寫 child，FK = Tx1 回傳的 masterId。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void tx2InsertDetail(Long caseId, Long masterId, boolean simulateFailure) throws SQLException {
        log.info("[Tx2] 進入交易 thread={} masterId={}",
                Thread.currentThread().getName(), masterId);

        try (Connection conn = primaryDataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO approval_detail (master_id, case_id, tx_label, thread_name) " +
                             "VALUES (?, ?, ?, ?)")) {
            ps.setLong(1, masterId);
            ps.setLong(2, caseId);
            ps.setString(3, simulateFailure ? "TX2_BEFORE_BOOM" : "TX2_DETAIL");
            ps.setString(4, Thread.currentThread().getName());
            ps.executeUpdate();
        }

        if (simulateFailure) {
            log.warn("[Tx2] 模擬失敗：拋 RuntimeException → child row 將 rollback，parent 已 commit 變孤兒");
            throw new RuntimeException(
                    "Tx2 故意失敗 — child rollback，但 master_id=" + masterId + " 已存在 parent 表");
        }

        log.info("[Tx2] 結束（即將 commit）master_id={}", masterId);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 4 步驟流程：master → detail_a → detail_b → UPDATE detail_b.detail_a_id
    //
    //  說明：
    //   step1  寫 approval_master，PK 由 sequence 產生 → masterId
    //   step2  寫 approval_detail_a，PK 由 sequence 產生 → detailAId
    //   step3  寫 approval_detail_b（detail_a_id 暫為 NULL）→ detailBId（IDENTITY）
    //   step4  UPDATE approval_detail_b SET detail_a_id = detailAId WHERE id = detailBId
    //
    //  整個流程包在一個 @Transactional 內，要嘛全部 commit，要嘛全部 rollback。
    //  注意 timeout：被 application.yml 的 atomikos.max-timeout 限制（已調為 1800000 ms）。
    // ─────────────────────────────────────────────────────────────────────────

    /** REST 入口：同步執行（要看 commit 結果）。 */
    public long[] runMasterDetailABFlow(Long caseId) throws SQLException {
        ensureSchema();
        return self.tx4StepsMasterDetailAB(caseId);
    }

    /**
     * 4 步驟全部包在同一個 JTA / XA 交易內。
     * 回傳 [masterId, detailAId, detailBId]。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 1800)
    public long[] tx4StepsMasterDetailAB(Long caseId) throws SQLException {
        log.info("[4Steps] 進入交易 thread={} caseId={}",
                Thread.currentThread().getName(), caseId);

        try (Connection conn = primaryDataSource.getConnection()) {
            // ── step1: master ────────────────────────────────────────────────
            long masterId = nextVal(conn, "approval_master_seq");
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO approval_master (id, case_id, tx_label, thread_name) " +
                            "VALUES (?, ?, ?, ?)")) {
                ps.setLong(1, masterId);
                ps.setLong(2, caseId);
                ps.setString(3, "STEP1_MASTER");
                ps.setString(4, Thread.currentThread().getName());
                ps.executeUpdate();
            }
            log.info("[4Steps] step1 master inserted id={}", masterId);

            // ── step2: detail_a (PK from sequence) ───────────────────────────
            long detailAId = nextVal(conn, "approval_detail_a_seq");
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO approval_detail_a (id, master_id, case_id, payload) " +
                            "VALUES (?, ?, ?, ?)")) {
                ps.setLong(1, detailAId);
                ps.setLong(2, masterId);
                ps.setLong(3, caseId);
                ps.setString(4, "DETAIL_A_PAYLOAD");
                ps.executeUpdate();
            }
            log.info("[4Steps] step2 detail_a inserted id={} (master_id={})", detailAId, masterId);

            // ── step3: detail_b (detail_a_id 暫為 NULL；PK 用 IDENTITY) ──────
            long detailBId;
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO approval_detail_b (master_id, case_id, detail_a_id, payload) " +
                            "VALUES (?, ?, NULL, ?)",
                    new String[]{"id"})) {
                ps.setLong(1, masterId);
                ps.setLong(2, caseId);
                ps.setString(3, "DETAIL_B_PAYLOAD");
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    if (!keys.next()) {
                        throw new SQLException("無法取得 detail_b 的 generated id");
                    }
                    detailBId = keys.getLong(1);
                }
            }
            log.info("[4Steps] step3 detail_b inserted id={} detail_a_id=NULL", detailBId);

            // ── step4: UPDATE detail_b.detail_a_id ───────────────────────────
            //  模擬：資料來源「不」直接用記憶體裡的 detailAId 變數，
            //        而是「重新 SELECT approval_detail_a」（同一交易內，read-your-writes）
            //        典型情境：實務上 step2 與 step4 可能拆在不同 service / 不同 module，
            //                 step4 拿到的只有 master_id / case_id，必須回查 detail_a。
            long detailAIdFromQuery;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT id FROM approval_detail_a " +
                            "WHERE master_id = ? AND case_id = ? " +
                            "ORDER BY id DESC FETCH FIRST 1 ROWS ONLY")) {
                ps.setLong(1, masterId);
                ps.setLong(2, caseId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        throw new SQLException("step4 重新查詢 approval_detail_a 找不到資料 master_id=" + masterId);
                    }
                    detailAIdFromQuery = rs.getLong(1);
                }
            }
            log.info("[4Steps] step4 重新 SELECT detail_a 取得 id={}（與 step2 變數比對：{}）",
                    detailAIdFromQuery, detailAId == detailAIdFromQuery ? "一致" : "不一致");

            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE approval_detail_b SET detail_a_id = ? WHERE id = ?")) {
                ps.setLong(1, detailAIdFromQuery);
                ps.setLong(2, detailBId);
                int n = ps.executeUpdate();
                if (n != 1) {
                    throw new SQLException("step4 UPDATE 影響筆數異常：" + n);
                }
            }
            log.info("[4Steps] step4 update detail_b.id={} 設定 detail_a_id={}", detailBId, detailAIdFromQuery);

            log.info("[4Steps] 結束（即將 commit）master={} detailA={} detailB={}",
                    masterId, detailAIdFromQuery, detailBId);
            return new long[]{masterId, detailAIdFromQuery, detailBId};
        }
    }

    /** 共用：取 sequence NEXTVAL。 */
    private long nextVal(Connection conn, String seqName) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT " + seqName + ".NEXTVAL FROM dual");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
