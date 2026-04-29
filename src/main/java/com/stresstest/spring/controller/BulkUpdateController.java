package com.stresstest.spring.controller;

import com.stresstest.spring.service.BulkUpdateDemoService;
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

    @GetMapping("/count")
    public ResponseEntity<Map<String, Object>> count(@RequestParam long masterId) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("masterId", masterId);
        r.put("count", svc.countByMaster(masterId));
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
