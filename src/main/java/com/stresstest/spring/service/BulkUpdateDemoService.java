package com.stresstest.spring.service;

import org.hibernate.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import java.sql.PreparedStatement;
import java.util.List;

/**
 * 大筆數 UPDATE 三種寫法示範。
 *
 *  目標表：jpa_records（id, master_id, field_a ... field_l, ...）
 *  情境：把指定 master_id 範圍的所有 row 的 field_a 換成新值。
 *
 *  1) loopNativeUpdate   — 反例：JPA native query 每列一條 SQL（容易 timeout / prepare no vote）
 *  2) setBasedUpdate     — 推薦：一句 set-based UPDATE，DB 內部完成
 *  3) jdbcBatchUpdate    — 必須逐筆時：unwrap Session.doWork + addBatch（單次往返多筆）
 *
 *  三個方法都標 @Transactional(timeout=1800)，避免 Atomikos 預設 120s 早於業務完成。
 */
@Service
public class BulkUpdateDemoService {

    private static final Logger log = LoggerFactory.getLogger(BulkUpdateDemoService.class);

    /** JDBC batch 每批送出筆數。建議 500 ~ 2000。 */
    private static final int BATCH_SIZE = 1000;

    /** 期間性 flush/clear 的門檻（避免 persistence context 膨脹）。 */
    private static final int FLUSH_EVERY = 5000;

    @PersistenceContext
    private EntityManager entityManager;

    /** Self-injection 用於呼叫 @Transactional(REQUIRES_NEW) 子方法時走 AOP 代理。 */
    @Autowired
    @Lazy
    private BulkUpdateDemoService self;

    // ─────────────────────────────────────────────────────────────────────────
    // 1) 反例：JPA native query 在 Java 迴圈逐筆 update
    //    觀察：N 次網路往返 + 每次 auto-flush。100 萬列 ≈ 數十分鐘 → 超過 JTA timeout
    //          → "prepare no vote" 或 ROLLBACK
    // ─────────────────────────────────────────────────────────────────────────
    @Transactional(timeout = 1800)
    public long loopNativeUpdate(long masterId, String newValue) {
        // 先撈 id 清單
        @SuppressWarnings("unchecked")
        List<Number> ids = entityManager.createNativeQuery(
                "SELECT id FROM jpa_records WHERE master_id = ?")
                .setParameter(1, masterId)
                .getResultList();

        long t0 = System.currentTimeMillis();
        long updated = 0;
        for (Number id : ids) {
            // 每一次 executeUpdate = 一次 round-trip + 一次 auto-flush 檢查
            int n = entityManager.createNativeQuery(
                    "UPDATE jpa_records SET field_a = ? WHERE id = ?")
                    .setParameter(1, newValue)
                    .setParameter(2, id.longValue())
                    .executeUpdate();
            updated += n;

            // 防止 persistence context 膨脹（雖然 native query 不會 manage entity，
            // 但其他被 load 的 entity 仍會留在 context）
            if (updated % FLUSH_EVERY == 0) {
                entityManager.flush();
                entityManager.clear();
                log.info("[loopNativeUpdate] 已更新 {} 筆", updated);
            }
        }
        long elapsed = System.currentTimeMillis() - t0;
        log.warn("[loopNativeUpdate] 反例！共 {} 筆，耗時 {} ms（{} ms/列）",
                updated, elapsed, ids.isEmpty() ? 0 : elapsed / ids.size());
        return updated;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 2) 推薦：set-based 一句 UPDATE
    //    1 次網路往返；optimizer 在 DB 內部完成；redo / undo 連續寫入；最快
    // ─────────────────────────────────────────────────────────────────────────
    @Transactional(timeout = 1800)
    public int setBasedUpdate(long masterId, String newValue) {
        long t0 = System.currentTimeMillis();
        int n = entityManager.createNativeQuery(
                "UPDATE jpa_records SET field_a = ? WHERE master_id = ?")
                .setParameter(1, newValue)
                .setParameter(2, masterId)
                .executeUpdate();
        long elapsed = System.currentTimeMillis() - t0;
        log.info("[setBasedUpdate] 共 {} 筆，耗時 {} ms", n, elapsed);
        return n;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 3) 必須逐筆時：JDBC batch
    //    透過 Hibernate Session.doWork() 拿到底層 Connection；addBatch + executeBatch
    //    把 N 次 round-trip 壓成 N / BATCH_SIZE 次。
    //    注意：JPA executeUpdate() 不會自動 batch；hibernate.jdbc.batch_size 只對
    //          entity persist/merge 生效，對 native query 無效。
    // ─────────────────────────────────────────────────────────────────────────
    @Transactional(timeout = 1800)
    public long jdbcBatchUpdate(long masterId, String newValue) {
        @SuppressWarnings("unchecked")
        List<Number> ids = entityManager.createNativeQuery(
                "SELECT id FROM jpa_records WHERE master_id = ?")
                .setParameter(1, masterId)
                .getResultList();

        final long[] updatedHolder = new long[1];
        long t0 = System.currentTimeMillis();

        // unwrap 到 Hibernate Session 才能 doWork() 拿到 java.sql.Connection
        // 這個 Connection 會綁在當前 JTA / Hibernate transaction 上，不需自己 commit
        entityManager.unwrap(Session.class).doWork(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "UPDATE jpa_records SET field_a = ? WHERE id = ?")) {
                int inBatch = 0;
                for (Number id : ids) {
                    ps.setString(1, newValue);
                    ps.setLong(2, id.longValue());
                    ps.addBatch();
                    inBatch++;
                    if (inBatch >= BATCH_SIZE) {
                        int[] r = ps.executeBatch();
                        ps.clearBatch();
                        updatedHolder[0] += r.length;
                        inBatch = 0;
                    }
                }
                if (inBatch > 0) {
                    int[] r = ps.executeBatch();
                    ps.clearBatch();
                    updatedHolder[0] += r.length;
                }
            }
        });

        long elapsed = System.currentTimeMillis() - t0;
        log.info("[jdbcBatchUpdate] 共 {} 筆 / batch={}，耗時 {} ms",
                updatedHolder[0], BATCH_SIZE, elapsed);
        return updatedHolder[0];
    }

    /** 取得指定 master_id 的列數（供 demo 端點顯示前後比對）。 */
    public long countByMaster(long masterId) {
        Number n = (Number) entityManager.createNativeQuery(
                "SELECT COUNT(*) FROM jpa_records WHERE master_id = ?")
                .setParameter(1, masterId)
                .getSingleResult();
        return n.longValue();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 4) 限制：每個 transaction timeout = 10 秒，要 update 100 萬筆
    //
    //    解法：把 1 個大交易切成 N 個小交易，每個都 < 10 秒。
    //
    //    關鍵：
    //      a) 外層方法不可開交易 (Propagation.NEVER)，否則 10s 會把整批計時
    //      b) 每批呼叫 self.updateChunk(...)，由 AOP 代理開新的
    //         REQUIRES_NEW + timeout=10 子交易，跑完即 commit，計時歸零
    //      c) 用 id 範圍切 (WHERE id BETWEEN lo AND hi) — 走 PK index、避免 full scan
    //         不要用 OFFSET 翻頁（越往後越慢、且每批讀的 row 不穩定）
    //      d) 每批用 set-based UPDATE 一句搞定，不在 Java 端做 row-level loop
    //      e) 失敗的 batch 記錄起來可重跑（這裡簡化為 throw + log）
    //
    //    調 CHUNK_SIZE：
    //      實測你的 DB / 硬體在 10s 內能處理多少 row（含 index 維護），
    //      抓 1/3 ~ 1/2 上限為 CHUNK_SIZE，留安全邊際。
    //      一般 OLTP 表單欄位更新：5,000 ~ 50,000 row 在 10s 內可完成。
    // ─────────────────────────────────────────────────────────────────────────

    /** 每批處理的 id 範圍寬度。可依實際 throughput 調整。 */
    private static final long CHUNK_ID_WIDTH = 10_000L;

    /**
     * 外層 NEVER：本身不開交易，純粹做切片 + 串接子交易。
     * 100 萬筆 / CHUNK_ID_WIDTH = 100 個子交易，每個 < 10s。
     *
     * @return 累計成功更新筆數
     */
    @Transactional(propagation = Propagation.NEVER)
    public long chunkedUpdate(long masterId, String newValue) {
        // 取 id 範圍（這個 SELECT 也不在交易內 → 不吃 10s）
        long[] range = findIdRange(masterId);
        long minId = range[0];
        long maxId = range[1];
        if (minId == 0 && maxId == 0) {
            log.warn("[chunkedUpdate] master_id={} 無資料", masterId);
            return 0;
        }

        long totalUpdated = 0;
        int chunkNo = 0;
        int failedChunks = 0;
        long t0 = System.currentTimeMillis();

        for (long lo = minId; lo <= maxId; lo += CHUNK_ID_WIDTH) {
            long hi = Math.min(lo + CHUNK_ID_WIDTH - 1, maxId);
            chunkNo++;
            long ts = System.currentTimeMillis();
            try {
                // 透過 self proxy 呼叫，AOP 才會啟動 REQUIRES_NEW + timeout=10
                int n = self.updateChunk(masterId, newValue, lo, hi);
                totalUpdated += n;
                log.info("[chunkedUpdate] chunk#{} id[{}..{}] updated={} elapsed={}ms",
                        chunkNo, lo, hi, n, System.currentTimeMillis() - ts);
            } catch (Exception e) {
                failedChunks++;
                // 子交易已自行 rollback；記下 range 便於補跑，繼續下一批
                log.error("[chunkedUpdate] chunk#{} id[{}..{}] 失敗：{} - {}（已 rollback，繼續下一批）",
                        chunkNo, lo, hi, e.getClass().getSimpleName(), e.getMessage());
            }
        }
        log.info("[chunkedUpdate] DONE master_id={} chunks={} failed={} updated={} totalElapsed={}ms",
                masterId, chunkNo, failedChunks, totalUpdated, System.currentTimeMillis() - t0);
        return totalUpdated;
    }

    /**
     * 子交易：每批一個獨立 JTA / XA 交易，timeout = 10 秒（符合題目硬性限制）。
     * 用 id 範圍 + set-based UPDATE，不做 row-level loop。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 10)
    public int updateChunk(long masterId, String newValue, long idLo, long idHi) {
        return entityManager.createNativeQuery(
                "UPDATE jpa_records SET field_a = ? " +
                "WHERE master_id = ? AND id BETWEEN ? AND ?")
                .setParameter(1, newValue)
                .setParameter(2, masterId)
                .setParameter(3, idLo)
                .setParameter(4, idHi)
                .executeUpdate();
    }

    /** 取 master 範圍的 [minId, maxId]。NEVER：不在交易內。 */
    @Transactional(propagation = Propagation.NEVER)
    public long[] findIdRange(long masterId) {
        Object[] row = (Object[]) entityManager.createNativeQuery(
                "SELECT NVL(MIN(id),0), NVL(MAX(id),0) FROM jpa_records WHERE master_id = ?")
                .setParameter(1, masterId)
                .getSingleResult();
        long min = ((Number) row[0]).longValue();
        long max = ((Number) row[1]).longValue();
        return new long[]{min, max};
    }
}
