package com.stresstest.spring.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * 分批 commit 模式下的「邏輯 Rollback」機制。
 *
 *  問題：題目限制每個 transaction timeout = 10 秒，必須切批；
 *        每批一旦 commit，無法用 JTA rollback 撤銷。
 *  解法：補償交易（Compensating Transaction）
 *        1. 每批 update 前先把要被改的 (id, old_value) 寫入 backup 表
 *        2. 失敗時用 backup 表的 old_value 反向 UPDATE 已成功的 chunk
 *        3. backup / restore 都仍受 10s 限制 → 同樣分批 commit
 *
 *  保證：
 *    - 冪等：每個 chunk 用 status 欄位標記，重跑只處理 PENDING / FAILED
 *    - 可恢復：JVM 死後可用 resumeJob(jobId) / rollbackJob(jobId) 繼續
 *    - 順序：rollback 從最後一個 DONE chunk 往前還原（雖然方向不影響結果，方便 log 追蹤）
 *
 *  限制：
 *    - 不防併發改寫（其他 session 在我 backup 後又改了同一 row → restore 會覆蓋掉）
 *      若需嚴格隔離：backup 時用 SELECT ... FOR UPDATE，或在 row 上加版本欄位 + WHERE 條件守
 *    - backup 表本身的寫入也是 commit；若連 backup 都失敗，沒救（用試前對照 throw 即可）
 */
@Service
public class BulkUpdateRollbackService {

    private static final Logger log = LoggerFactory.getLogger(BulkUpdateRollbackService.class);

    /** 每批 id 寬度，依 10s 限制與實測 throughput 調整。 */
    private static final long CHUNK_ID_WIDTH = 10_000L;

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private DataSource dataSource;

    @Autowired
    @Lazy
    private BulkUpdateRollbackService self;

    private volatile boolean schemaReady = false;

    // ─────────────────────────────────────────────────────────────────────────
    // Schema：job / progress / backup
    // ─────────────────────────────────────────────────────────────────────────
    private synchronized void ensureSchema() throws SQLException {
        if (schemaReady) return;
        try (Connection conn = dataSource.getConnection();
             Statement st = conn.createStatement()) {

            execIgnore(st, "CREATE SEQUENCE bulk_update_job_seq START WITH 1 INCREMENT BY 1 NOCACHE", 955);

            execIgnore(st,
                    "CREATE TABLE bulk_update_job (" +
                            "  id           NUMBER PRIMARY KEY, " +
                            "  master_id    NUMBER NOT NULL, " +
                            "  new_value    VARCHAR2(200), " +
                            "  status       VARCHAR2(20) NOT NULL, " +    // RUNNING / DONE / FAILED / ROLLED_BACK
                            "  total_chunks NUMBER DEFAULT 0, " +
                            "  done_chunks  NUMBER DEFAULT 0, " +
                            "  failed_chunks NUMBER DEFAULT 0, " +
                            "  created_at   TIMESTAMP DEFAULT SYSTIMESTAMP, " +
                            "  updated_at   TIMESTAMP DEFAULT SYSTIMESTAMP)",
                    955);

            execIgnore(st,
                    "CREATE TABLE bulk_update_progress (" +
                            "  job_id    NUMBER NOT NULL, " +
                            "  chunk_no  NUMBER NOT NULL, " +
                            "  id_lo     NUMBER NOT NULL, " +
                            "  id_hi     NUMBER NOT NULL, " +
                            "  status    VARCHAR2(20) NOT NULL, " +    // PENDING / DONE / FAILED / RESTORED
                            "  affected  NUMBER DEFAULT 0, " +
                            "  err_msg   VARCHAR2(2000), " +
                            "  updated_at TIMESTAMP DEFAULT SYSTIMESTAMP, " +
                            "  CONSTRAINT pk_bup PRIMARY KEY (job_id, chunk_no))",
                    955);

            // backup：保存被改前的 field_a 值，供 rollback 還原
            execIgnore(st,
                    "CREATE TABLE bulk_update_backup (" +
                            "  job_id     NUMBER NOT NULL, " +
                            "  record_id  NUMBER NOT NULL, " +
                            "  old_value  VARCHAR2(40), " +
                            "  CONSTRAINT pk_bub PRIMARY KEY (job_id, record_id))",
                    955);

            log.info("rollback schema ready");
        }
        schemaReady = true;
    }

    private static void execIgnore(Statement st, String ddl, int ignoreCode) throws SQLException {
        try { st.execute(ddl); }
        catch (SQLException e) { if (e.getErrorCode() != ignoreCode) throw e; }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 主流程：runChunkedUpdateWithRollback
    //   外層 NEVER：本身不在交易內，逐批呼叫子交易
    // ─────────────────────────────────────────────────────────────────────────
    @Transactional(propagation = Propagation.NEVER)
    public long runChunkedUpdateWithRollback(long masterId, String newValue, boolean simulateFailureAtChunk) throws SQLException {
        ensureSchema();
        long[] range = self.findIdRange(masterId);
        long minId = range[0], maxId = range[1];
        if (minId == 0 && maxId == 0) {
            log.warn("master_id={} 無資料", masterId);
            return 0;
        }

        long jobId = self.createJob(masterId, newValue);
        log.info("[Job#{}] 開始：master_id={} value={} idRange=[{},{}]", jobId, masterId, newValue, minId, maxId);

        long totalAffected = 0;
        int chunkNo = 0;
        boolean failed = false;

        for (long lo = minId; lo <= maxId; lo += CHUNK_ID_WIDTH) {
            long hi = Math.min(lo + CHUNK_ID_WIDTH - 1, maxId);
            chunkNo++;

            // 故意失敗演示：第 3 批拋例外
            if (simulateFailureAtChunk && chunkNo == 3) {
                self.markChunk(jobId, chunkNo, lo, hi, "FAILED", 0, "SIMULATED FAILURE");
                self.bumpJob(jobId, 0, 1);
                log.error("[Job#{}] chunk#{} 模擬失敗 → 觸發 rollback", jobId, chunkNo);
                failed = true;
                break;
            }

            try {
                // ① backup → ② update → ③ markDone，全在同一子交易內 (10s)
                int n = self.backupAndUpdateChunk(jobId, masterId, newValue, chunkNo, lo, hi);
                totalAffected += n;
                self.bumpJob(jobId, 1, 0);
                log.info("[Job#{}] chunk#{} id[{}..{}] DONE affected={}", jobId, chunkNo, lo, hi, n);
            } catch (Exception e) {
                self.markChunk(jobId, chunkNo, lo, hi, "FAILED", 0, truncate(e.getMessage(), 1500));
                self.bumpJob(jobId, 0, 1);
                log.error("[Job#{}] chunk#{} id[{}..{}] FAILED：{} → 觸發 rollback",
                        jobId, chunkNo, lo, hi, e.getMessage());
                failed = true;
                break;
            }
        }

        if (failed) {
            log.warn("[Job#{}] 進入 rollback 階段 ...", jobId);
            long restored = rollbackJob(jobId);
            self.markJobStatus(jobId, "ROLLED_BACK");
            log.warn("[Job#{}] rollback 完成，還原 {} 筆", jobId, restored);
            return -restored; // 負數代表已 rollback
        }

        self.markJobStatus(jobId, "DONE");
        log.info("[Job#{}] 全部成功，共 affected={} jobStatus=DONE", jobId, totalAffected);
        return totalAffected;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 單批：backup + update + markDone（同一個 10s 子交易）
    //   若任一步炸掉 → 整批 rollback（連 backup 也不留）→ 不會殘留錯誤狀態
    // ─────────────────────────────────────────────────────────────────────────
    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 10)
    public int backupAndUpdateChunk(long jobId, long masterId, String newValue,
                                    int chunkNo, long lo, long hi) {
        // step1: backup（INSERT … SELECT），冪等：用 PK (job_id, record_id) 去重
        entityManager.createNativeQuery(
                "INSERT INTO bulk_update_backup (job_id, record_id, old_value) " +
                "SELECT ?, id, field_a FROM jpa_records " +
                "WHERE master_id = ? AND id BETWEEN ? AND ? " +
                "AND NOT EXISTS (SELECT 1 FROM bulk_update_backup b " +
                "                WHERE b.job_id = ? AND b.record_id = jpa_records.id)")
                .setParameter(1, jobId)
                .setParameter(2, masterId)
                .setParameter(3, lo)
                .setParameter(4, hi)
                .setParameter(5, jobId)
                .executeUpdate();

        // step2: 真正的 set-based UPDATE
        int affected = entityManager.createNativeQuery(
                "UPDATE jpa_records SET field_a = ? " +
                "WHERE master_id = ? AND id BETWEEN ? AND ?")
                .setParameter(1, newValue)
                .setParameter(2, masterId)
                .setParameter(3, lo)
                .setParameter(4, hi)
                .executeUpdate();

        // step3: 寫 progress = DONE（MERGE 以支援重跑）
        entityManager.createNativeQuery(
                "MERGE INTO bulk_update_progress p " +
                "USING (SELECT ? job_id, ? chunk_no FROM dual) s " +
                "ON (p.job_id = s.job_id AND p.chunk_no = s.chunk_no) " +
                "WHEN MATCHED THEN UPDATE SET status='DONE', affected=?, err_msg=NULL, updated_at=SYSTIMESTAMP, id_lo=?, id_hi=? " +
                "WHEN NOT MATCHED THEN INSERT (job_id, chunk_no, id_lo, id_hi, status, affected) " +
                "VALUES (?, ?, ?, ?, 'DONE', ?)")
                .setParameter(1, jobId).setParameter(2, chunkNo)
                .setParameter(3, affected).setParameter(4, lo).setParameter(5, hi)
                .setParameter(6, jobId).setParameter(7, chunkNo)
                .setParameter(8, lo).setParameter(9, hi).setParameter(10, affected)
                .executeUpdate();

        return affected;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Rollback：把所有 DONE chunk 用 backup.old_value 還原
    //   外層 NEVER；逐 chunk 呼叫子交易
    // ─────────────────────────────────────────────────────────────────────────
    @Transactional(propagation = Propagation.NEVER)
    public long rollbackJob(long jobId) {
        // 取所有 DONE chunk（從新到舊，方便觀察 log）
        @SuppressWarnings("unchecked")
        java.util.List<Object[]> chunks = entityManager.createNativeQuery(
                "SELECT chunk_no, id_lo, id_hi FROM bulk_update_progress " +
                "WHERE job_id = ? AND status = 'DONE' " +
                "ORDER BY chunk_no DESC")
                .setParameter(1, jobId)
                .getResultList();

        long totalRestored = 0;
        for (Object[] r : chunks) {
            int chunkNo = ((Number) r[0]).intValue();
            long lo = ((Number) r[1]).longValue();
            long hi = ((Number) r[2]).longValue();
            try {
                int n = self.restoreChunk(jobId, chunkNo, lo, hi);
                totalRestored += n;
                log.info("[Rollback Job#{}] chunk#{} id[{}..{}] restored={}", jobId, chunkNo, lo, hi, n);
            } catch (Exception e) {
                // 還原失敗：標 FAILED，繼續還原其他 chunk（避免一個壞蘋果擋住全部）
                self.markChunk(jobId, chunkNo, lo, hi, "FAILED", 0,
                        "RESTORE FAIL: " + truncate(e.getMessage(), 1400));
                log.error("[Rollback Job#{}] chunk#{} 還原失敗：{}", jobId, chunkNo, e.getMessage());
            }
        }
        return totalRestored;
    }

    /** 子交易：用 backup 的 old_value 反向 UPDATE 一個 chunk。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 10)
    public int restoreChunk(long jobId, int chunkNo, long lo, long hi) {
        // 用 backup join 還原
        int n = entityManager.createNativeQuery(
                "UPDATE jpa_records r SET field_a = (" +
                "  SELECT b.old_value FROM bulk_update_backup b " +
                "  WHERE b.job_id = ? AND b.record_id = r.id) " +
                "WHERE r.id BETWEEN ? AND ? " +
                "AND EXISTS (SELECT 1 FROM bulk_update_backup b2 " +
                "            WHERE b2.job_id = ? AND b2.record_id = r.id)")
                .setParameter(1, jobId)
                .setParameter(2, lo)
                .setParameter(3, hi)
                .setParameter(4, jobId)
                .executeUpdate();

        // 標記 chunk 已還原；backup 留著供查證 / 二次 rollback
        entityManager.createNativeQuery(
                "UPDATE bulk_update_progress SET status='RESTORED', updated_at=SYSTIMESTAMP " +
                "WHERE job_id = ? AND chunk_no = ?")
                .setParameter(1, jobId).setParameter(2, chunkNo)
                .executeUpdate();
        return n;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Resume：重跑 job 中還沒 DONE 的 chunk（PENDING / FAILED）
    // ─────────────────────────────────────────────────────────────────────────
    @Transactional(propagation = Propagation.NEVER)
    public long resumeJob(long jobId) {
        Object[] meta = (Object[]) entityManager.createNativeQuery(
                "SELECT master_id, new_value FROM bulk_update_job WHERE id = ?")
                .setParameter(1, jobId).getSingleResult();
        long masterId = ((Number) meta[0]).longValue();
        String newValue = (String) meta[1];

        @SuppressWarnings("unchecked")
        java.util.List<Object[]> todo = entityManager.createNativeQuery(
                "SELECT chunk_no, id_lo, id_hi FROM bulk_update_progress " +
                "WHERE job_id = ? AND status IN ('PENDING','FAILED') ORDER BY chunk_no")
                .setParameter(1, jobId).getResultList();

        long total = 0;
        for (Object[] r : todo) {
            int no = ((Number) r[0]).intValue();
            long lo = ((Number) r[1]).longValue();
            long hi = ((Number) r[2]).longValue();
            try {
                int n = self.backupAndUpdateChunk(jobId, masterId, newValue, no, lo, hi);
                total += n;
                self.bumpJob(jobId, 1, 0);
            } catch (Exception e) {
                self.markChunk(jobId, no, lo, hi, "FAILED", 0, truncate(e.getMessage(), 1500));
            }
        }
        return total;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 小工具子交易（每個都 < 10s）
    // ─────────────────────────────────────────────────────────────────────────
    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 10)
    public long createJob(long masterId, String newValue) {
        Number id = (Number) entityManager.createNativeQuery(
                "SELECT bulk_update_job_seq.NEXTVAL FROM dual").getSingleResult();
        entityManager.createNativeQuery(
                "INSERT INTO bulk_update_job (id, master_id, new_value, status) VALUES (?, ?, ?, 'RUNNING')")
                .setParameter(1, id.longValue())
                .setParameter(2, masterId)
                .setParameter(3, newValue)
                .executeUpdate();
        return id.longValue();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 10)
    public void markChunk(long jobId, int chunkNo, long lo, long hi, String status, int affected, String errMsg) {
        entityManager.createNativeQuery(
                "MERGE INTO bulk_update_progress p " +
                "USING (SELECT ? job_id, ? chunk_no FROM dual) s " +
                "ON (p.job_id = s.job_id AND p.chunk_no = s.chunk_no) " +
                "WHEN MATCHED THEN UPDATE SET status=?, affected=?, err_msg=?, updated_at=SYSTIMESTAMP, id_lo=?, id_hi=? " +
                "WHEN NOT MATCHED THEN INSERT (job_id, chunk_no, id_lo, id_hi, status, affected, err_msg) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?)")
                .setParameter(1, jobId).setParameter(2, chunkNo)
                .setParameter(3, status).setParameter(4, affected).setParameter(5, errMsg)
                .setParameter(6, lo).setParameter(7, hi)
                .setParameter(8, jobId).setParameter(9, chunkNo)
                .setParameter(10, lo).setParameter(11, hi)
                .setParameter(12, status).setParameter(13, affected).setParameter(14, errMsg)
                .executeUpdate();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 10)
    public void bumpJob(long jobId, int doneDelta, int failedDelta) {
        entityManager.createNativeQuery(
                "UPDATE bulk_update_job SET done_chunks = done_chunks + ?, " +
                "failed_chunks = failed_chunks + ?, updated_at = SYSTIMESTAMP WHERE id = ?")
                .setParameter(1, doneDelta).setParameter(2, failedDelta).setParameter(3, jobId)
                .executeUpdate();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 10)
    public void markJobStatus(long jobId, String status) {
        entityManager.createNativeQuery(
                "UPDATE bulk_update_job SET status = ?, updated_at = SYSTIMESTAMP WHERE id = ?")
                .setParameter(1, status).setParameter(2, jobId).executeUpdate();
    }

    @Transactional(propagation = Propagation.NEVER)
    public long[] findIdRange(long masterId) {
        Object[] row = (Object[]) entityManager.createNativeQuery(
                "SELECT NVL(MIN(id),0), NVL(MAX(id),0) FROM jpa_records WHERE master_id = ?")
                .setParameter(1, masterId).getSingleResult();
        return new long[]{((Number) row[0]).longValue(), ((Number) row[1]).longValue()};
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
