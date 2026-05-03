package com.stresstest.spring.controller;

import com.stresstest.spring.service.BulkUpdateDemoService;
import com.stresstest.spring.service.BulkUpdateRollbackService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 大筆數 UPDATE 三種寫法的 demo 端點。
 *
 *  POST /api/bulk-update/loop?masterId=1&value=NEW    → 反例：JPA native query 逐筆迴圈
 *  POST /api/bulk-update/set-based?masterId=1&value=NEW → 推薦：set-based 一句 UPDATE
 *  POST /api/bulk-update/jdbc-batch?masterId=1&value=NEW → 必須逐筆時：JDBC addBatch
 *
 *  使用前提：jpa_records 已透過原本的上傳流程灌入大量資料（同一 master_id）。
 */
@RestController
@RequestMapping("/api/bulk-update")
public class BulkUpdateController {

    private static final Logger log = LoggerFactory.getLogger(BulkUpdateController.class);

    @Autowired
    private BulkUpdateDemoService svc;

    @Autowired
    private BulkUpdateRollbackService rollbackSvc;

    @PostMapping("/loop")
    public ResponseEntity<Map<String, Object>> loop(
            @RequestParam long masterId,
            @RequestParam(defaultValue = "NEW") String value) {
        long t0 = System.currentTimeMillis();
        long n = svc.loopNativeUpdate(masterId, value);
        return ok("loopNativeUpdate", masterId, value, n, System.currentTimeMillis() - t0);
    }

    @PostMapping("/set-based")
    public ResponseEntity<Map<String, Object>> setBased(
            @RequestParam long masterId,
            @RequestParam(defaultValue = "NEW") String value) {
        long t0 = System.currentTimeMillis();
        int n = svc.setBasedUpdate(masterId, value);
        return ok("setBasedUpdate", masterId, value, n, System.currentTimeMillis() - t0);
    }

    @PostMapping("/jdbc-batch")
    public ResponseEntity<Map<String, Object>> jdbcBatch(
            @RequestParam long masterId,
            @RequestParam(defaultValue = "NEW") String value) {
        long t0 = System.currentTimeMillis();
        long n = svc.jdbcBatchUpdate(masterId, value);
        return ok("jdbcBatchUpdate", masterId, value, n, System.currentTimeMillis() - t0);
    }

    /**
     *  限制：每個 transaction timeout = 10s，要 update 100 萬筆。
     *  做法：分批送出 → 每批一個 REQUIRES_NEW 子交易，各自 10s 計時。
     *  POST /api/bulk-update/chunked?masterId=…&value=…
     */
    @PostMapping("/chunked")
    public ResponseEntity<Map<String, Object>> chunked(
            @RequestParam long masterId,
            @RequestParam(defaultValue = "NEW") String value) {
        long t0 = System.currentTimeMillis();
        long n = svc.chunkedUpdate(masterId, value);
        return ok("chunkedUpdate", masterId, value, n, System.currentTimeMillis() - t0);
    }

    @GetMapping("/count")
    public ResponseEntity<Map<String, Object>> count(@RequestParam long masterId) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("masterId", masterId);
        r.put("count", svc.countByMaster(masterId));
        return ResponseEntity.ok(r);
    }

    /**
     * 分批 commit + 補償交易 rollback。
     *  POST /api/bulk-update/with-rollback?masterId=…&value=…&simulateFailure=true
     *    simulateFailure=true → 第 3 批故意丟例外 → 觸發 rollback 還原前 2 批
     */
    @PostMapping("/with-rollback")
    public ResponseEntity<Map<String, Object>> withRollback(
            @RequestParam long masterId,
            @RequestParam(defaultValue = "NEW") String value,
            @RequestParam(defaultValue = "false") boolean simulateFailure) throws java.sql.SQLException {
        long t0 = System.currentTimeMillis();
        long n = rollbackSvc.runChunkedUpdateWithRollback(masterId, value, simulateFailure);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("mode", "withRollback");
        r.put("masterId", masterId);
        r.put("newValue", value);
        r.put("simulateFailure", simulateFailure);
        r.put("affectedOrRolledBack", n); // 負數 = rollback；正數 = 成功 affected
        r.put("elapsedMs", System.currentTimeMillis() - t0);
        return ResponseEntity.ok(r);
    }

    /** 手動觸發某 job 的 rollback（從 backup 還原）。 */
    @PostMapping("/rollback/{jobId}")
    public ResponseEntity<Map<String, Object>> rollback(@PathVariable long jobId) {
        long n = rollbackSvc.rollbackJob(jobId);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("jobId", jobId);
        r.put("restored", n);
        return ResponseEntity.ok(r);
    }

    /** 重跑某 job 中尚未 DONE 的 chunk。 */
    @PostMapping("/resume/{jobId}")
    public ResponseEntity<Map<String, Object>> resume(@PathVariable long jobId) {
        long n = rollbackSvc.resumeJob(jobId);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("jobId", jobId);
        r.put("resumedAffected", n);
        return ResponseEntity.ok(r);
    }

    private ResponseEntity<Map<String, Object>> ok(
            String mode, long masterId, String value, long updated, long elapsedMs) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("mode", mode);
        r.put("masterId", masterId);
        r.put("newValue", value);
        r.put("updated", updated);
        r.put("elapsedMs", elapsedMs);
        log.info("[bulk-update] mode={} masterId={} updated={} elapsed={}ms",
                mode, masterId, updated, elapsedMs);
        return ResponseEntity.ok(r);
    }
}
