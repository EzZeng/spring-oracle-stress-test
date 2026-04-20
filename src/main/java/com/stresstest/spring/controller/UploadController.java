package com.stresstest.spring.controller;

import com.stresstest.spring.entity.UploadCase;
import com.stresstest.spring.model.ProcessResult;
import com.stresstest.spring.service.CaseService;
import com.stresstest.spring.service.DatabaseService;
import com.stresstest.spring.service.JpaDatabaseService;
import com.stresstest.spring.service.LatencySimulator;
import com.stresstest.spring.service.MemoryMonitor;
import com.stresstest.spring.service.PhaseMonitor;
import com.stresstest.spring.strategy.JpaListPagedStrategy;
import com.stresstest.spring.strategy.PipelinedPagedStrategy;
import com.stresstest.spring.strategy.ProcessingStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * 檔案上傳 REST Controller。
 * POST /api/upload?strategy=1  → 上傳檔案並以指定策略處理
 * GET  /api/strategies         → 列出所有可用策略
 * GET  /api/count              → 查詢目前 Oracle 中的記錄數
 */
@RestController
@RequestMapping("/api")
public class UploadController {

    private static final Logger log = LoggerFactory.getLogger(UploadController.class);

    @Autowired
    private DatabaseService databaseService;

    @Autowired
    private JpaDatabaseService jpaDatabaseService;

    @Autowired
    private CaseService caseService;

    @Autowired
    private com.stresstest.spring.service.TxStrategyExecutionService txStrategyExecutionService;

    @Autowired
    private List<ProcessingStrategy> strategies;

    @GetMapping("/strategies")
    public ResponseEntity<List<Map<String, Object>>> listStrategies() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (int i = 0; i < strategies.size(); i++) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("index", i + 1);
            m.put("name", strategies.get(i).getName());
            result.add(m);
        }
        return ResponseEntity.ok(result);
    }

    @PostMapping("/upload")
    public ResponseEntity<Map<String, Object>> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "strategy", defaultValue = "1") int strategyIndex,
            @RequestParam(value = "skipInit", defaultValue = "false") boolean skipInit,
            @RequestParam(value = "latencyMs", defaultValue = "0") int latencyMs,
            @RequestParam(value = "bizType", required = false) String bizType) {

        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(Collections.singletonMap("error", (Object) "檔案為空"));
        }

        if (strategyIndex < 1 || strategyIndex > strategies.size()) {
            return ResponseEntity.badRequest().body(Collections.singletonMap("error",
                    (Object) ("策略編號無效。可用: 1~" + strategies.size())));
        }

        ProcessingStrategy strategy = strategies.get(strategyIndex - 1);
        log.info("=== 開始處理：策略 {} - {} ===", strategyIndex, strategy.getName());

        Path tempFile = null;
        try {
            // 儲存上傳檔案到暫存路徑
            tempFile = Files.createTempFile("upload-", ".dat");
            file.transferTo(tempFile.toFile());
            long fileSize = tempFile.toFile().length();
            log.info("檔案大小: {} bytes ({} MB)", fileSize, String.format("%.2f", fileSize / (1024.0 * 1024.0)));

            // JPA 策略需要在 @Transactional 之前初始化 Schema（避免 DDL 鎖衝突）
            boolean isJpa = strategy.getName().startsWith("[JPA]");
            if (isJpa && !skipInit) {
                jpaDatabaseService.initSchema();
            }

            // 設定網路延遲模擬（0 = 關閉）
            LatencySimulator.configure(latencyMs);
            if (latencyMs > 0) {
                log.info("已啟用網路延遲模擬: {}ms per round-trip", latencyMs);
            }

            // 執行策略 + 效能監控
            MemoryMonitor monitor = new MemoryMonitor();
            monitor.start();
            long processStart = System.nanoTime();
            ProcessResult result2 = isJpa
                    ? strategy.process(tempFile, databaseService, file.getOriginalFilename())
                    : txStrategyExecutionService.execute(strategy, tempFile, databaseService, file.getOriginalFilename());
            long processEnd = System.nanoTime();
            // @Transactional commit 發生在 process() 返回後、Spring AOP proxy 中
            // 我們在此測量到的是 process() 內部耗時，commit 開銷體現在 monitor.stop() 的總時間差
            MemoryMonitor.BenchmarkResult result = monitor.stop(strategy.getName(), result2.getSuccessCount());
            long wallEnd = System.nanoTime();

            long processMs = (processEnd - processStart) / 1_000_000;
            long commitMs = result.elapsedMs - processMs; // 差值 ≈ Spring @Transactional commit + overhead

            // 驗證：JPA 策略查 jpa_records，JDBC 策略查 file_detail
            long dbCount = isJpa ? jpaDatabaseService.countRecords() : databaseService.countFileDetails(result2.getMasterId());

            // 建立案件（加入待辦事項，等待放行）
            UploadCase uploadCase = caseService.createCase(
                    result2.getMasterId(), file.getOriginalFilename(),
                    isJpa ? "JPA" : "TX", bizType,
                    result2.getTotalCount(), result2.getSuccessCount(), result2.getFailCount());

            // 回傳結果
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("strategy", strategy.getName());
            response.put("status", "COMMIT");
            response.put("caseId", uploadCase.getId());
            response.put("caseStatus", uploadCase.getStatus());
            response.put("bizType", uploadCase.getBizType());
            response.put("masterId", result2.getMasterId());
            response.put("successCount", result2.getSuccessCount());
            response.put("failCount", result2.getFailCount());
            response.put("rowsProcessed", result2.getTotalCount());
            response.put("dbCount", dbCount);
            response.put("elapsedMs", result.elapsedMs);
            response.put("elapsedSec", String.format("%.2f", result.elapsedMs / 1000.0));
            response.put("peakMemoryMB", String.format("%.2f", result.peakMemory / (1024.0 * 1024.0)));
            response.put("memoryUsagePercent", String.format("%.2f", result.peakMemory * 100.0 / result.maxMemory));
            response.put("throughput", String.format("%.0f", result2.getSuccessCount() / (result.elapsedMs / 1000.0)));
            response.put("maxHeapMB", String.format("%.0f", result.maxMemory / (1024.0 * 1024.0)));
            response.put("processMs", processMs);
            response.put("commitMs", commitMs);

            // 網路延遲模擬指標
            long dbRoundTrips = LatencySimulator.getRoundTripCount();
            long networkOverheadMs = dbRoundTrips * latencyMs;
            response.put("simulatedLatencyMs", latencyMs);
            response.put("dbRoundTrips", dbRoundTrips);
            response.put("estimatedNetworkOverheadMs", networkOverheadMs);
            if (latencyMs > 0) {
                response.put("networkOverheadPct",
                        String.format("%.1f%%", networkOverheadMs * 100.0 / result.elapsedMs));
            }

            // 如果是策略 9 (JpaListPagedStrategy)，附加各階段瓶頸分析
            if (strategy instanceof JpaListPagedStrategy) {
                Map<String, Object> phaseTimings = ((JpaListPagedStrategy) strategy).getLastPhaseTimings();
                if (phaseTimings != null) {
                    response.put("phaseTimings", phaseTimings);
                }
            }

            // 如果是管線化策略 (PipelinedPagedStrategy)，附加分階段 CPU/RAM 監控報告
            if (strategy instanceof PipelinedPagedStrategy) {
                PhaseMonitor phaseMonitor = ((PipelinedPagedStrategy) strategy).getLastMonitor();
                if (phaseMonitor != null) {
                    response.put("phaseMonitor", phaseMonitor.getReport());
                }
            }

            log.info("=== 完成：{} | 耗時: {}ms | 峰值: {} MB | DB: {} 筆 ===",
                    strategy.getName(), result.elapsedMs,
                    String.format("%.2f", result.peakMemory / (1024.0 * 1024.0)), dbCount);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("處理失敗: {}", e.getMessage(), e);
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("strategy", strategy.getName());
            error.put("status", "ROLLBACK");
            error.put("error", e.getMessage());
            return ResponseEntity.status(500).body(error);
        } finally {
            LatencySimulator.configure(0);
            if (tempFile != null) {
                try { Files.deleteIfExists(tempFile); } catch (Exception ignored) {}
            }
        }
    }

    @GetMapping("/count")
    public ResponseEntity<Map<String, Object>> count() {
        try {
            long dbCount = databaseService.countRecords();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("count", dbCount);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Collections.singletonMap("error", (Object) e.getMessage()));
        }
    }
}
