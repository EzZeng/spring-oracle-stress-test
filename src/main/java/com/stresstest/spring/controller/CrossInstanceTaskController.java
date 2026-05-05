package com.stresstest.spring.controller;

import com.stresstest.spring.service.CrossInstanceTaskService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.sql.SQLException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 跨 Oracle instance 雙向任務 demo 端點。
 *
 *  使用順序：
 *    1. POST /api/cross/seed?payload=foo
 *         → 在 Primary / Instance-A 各建一筆，回傳 primaryId / instanceAId
 *    2. POST /api/cross/run?primaryId=…&instanceAId=…&payloadA=…&payloadP=…
 *         → 執行兩個 cross-DB XA 交易：
 *             TX-A2P: 在 Instance-A 新增一筆 → 回填 primary.peer_a_id
 *             TX-P2A: 在 Primary 新增一筆 → 回填 instance_a.peer_primary_id
 */
@RestController
@RequestMapping("/api/cross")
public class CrossInstanceTaskController {

    @Autowired
    private CrossInstanceTaskService svc;

    @PostMapping("/seed")
    public ResponseEntity<Map<String, Object>> seed(
            @RequestParam(defaultValue = "demo") String payload) {
        try {
            long[] ids = svc.seed(payload);
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("primaryId", ids[0]);
            r.put("instanceAId", ids[1]);
            return ResponseEntity.ok(r);
        } catch (SQLException e) {
            return ResponseEntity.status(500).body(Collections.singletonMap("error", (Object) e.getMessage()));
        }
    }

    @PostMapping("/run")
    public ResponseEntity<Map<String, Object>> run(
            @RequestParam long primaryId,
            @RequestParam long instanceAId,
            @RequestParam(defaultValue = "PAYLOAD-A") String payloadA,
            @RequestParam(defaultValue = "PAYLOAD-P") String payloadP) {
        try {
            long t0 = System.currentTimeMillis();
            long[] ids = svc.runTask(primaryId, instanceAId, payloadA, payloadP);
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("status", "OK");
            r.put("inputPrimaryId", primaryId);
            r.put("inputInstanceAId", instanceAId);
            r.put("newInstanceAId", ids[0]);   // TX-A2P 新增
            r.put("newPrimaryId", ids[1]);     // TX-P2A 新增
            r.put("elapsedMs", System.currentTimeMillis() - t0);
            return ResponseEntity.ok(r);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Collections.singletonMap("error", (Object) e.getMessage()));
        }
    }
}
