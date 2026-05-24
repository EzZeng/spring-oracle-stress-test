package com.stresstest.spring.controller;

import com.stresstest.spring.entity.UploadCase;
import com.stresstest.spring.model.AreaUploadResult;
import com.stresstest.spring.service.AreaUploadService;
import com.stresstest.spring.service.LatencySimulator;
import com.stresstest.spring.service.MemoryMonitor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * US 客戶跨區上傳 REST API。
 */
@RestController
@RequestMapping("/api/area/us")
public class AreaUploadController {

    private static final Logger log = LoggerFactory.getLogger(AreaUploadController.class);

    @Autowired
    private AreaUploadService areaUploadService;

    @PostMapping("/upload")
    public ResponseEntity<Map<String, Object>> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam("userId") String userId,
            @RequestParam(value = "strategy", defaultValue = "1") int strategyIndex,
            @RequestParam(value = "skipInit", defaultValue = "false") boolean skipInit,
            @RequestParam(value = "latencyMs", defaultValue = "0") int latencyMs,
            @RequestParam(value = "bizType", required = false) String bizType,
            @RequestParam(value = "simulateFailure", defaultValue = "none") String simulateFailure) {

        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(Collections.singletonMap("error", (Object) "檔案為空"));
        }
        if (userId == null || userId.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Collections.singletonMap("error", (Object) "userId 為必填"));
        }

        Path tempFile = null;
        MemoryMonitor monitor = null;
        try {
            areaUploadService.prepareForUpload(strategyIndex, skipInit);

            tempFile = Files.createTempFile("area-us-upload-", ".dat");
            file.transferTo(tempFile.toFile());
            String fileName = file.getOriginalFilename();
            if (fileName == null || fileName.trim().isEmpty()) {
                fileName = "upload.dat";
            }

            LatencySimulator.configure(latencyMs);
            monitor = new MemoryMonitor();
            monitor.start();

            AreaUploadResult result = areaUploadService.uploadUs(
                    tempFile,
                    fileName,
                    strategyIndex,
                    bizType,
                    userId.trim(),
                    simulateFailure);

            MemoryMonitor.BenchmarkResult benchmark =
                    monitor.stop(result.getStrategyName(), result.getSuccessCount());
            monitor = null;

            Map<String, Object> response = toResponse(result, benchmark, latencyMs);
            log.info("[US-Area] requestId={} 完成，耗時={}ms", result.getRequestId(), benchmark.elapsedMs);
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(errorBody(e));
        } catch (Exception e) {
            log.error("[US-Area] 上傳失敗，XA transaction rollback: {}", e.getMessage(), e);
            return ResponseEntity.status(500).body(errorBody(e));
        } finally {
            if (monitor != null) {
                monitor.stop("US Area Upload", 0);
            }
            LatencySimulator.configure(0);
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (Exception ignored) {
                }
            }
        }
    }

    private Map<String, Object> toResponse(AreaUploadResult result,
                                           MemoryMonitor.BenchmarkResult benchmark,
                                           int latencyMs) {
        UploadCase uploadCase = result.getUploadCase();
        long elapsedMs = benchmark.elapsedMs;
        long remainingMs = Math.max(0L, elapsedMs - result.getProcessMs());
        long dbRoundTrips = LatencySimulator.getRoundTripCount();
        long networkOverheadMs = dbRoundTrips * latencyMs;

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("requestId", result.getRequestId());
        response.put("area", result.getArea());
        response.put("userId", result.getUserId());
        response.put("strategy", result.getStrategyName());
        response.put("status", "COMMIT");
        response.put("caseId", uploadCase.getId());
        response.put("caseStatus", uploadCase.getStatus());
        response.put("bizType", uploadCase.getBizType());
        response.put("masterId", result.getMasterId());
        response.put("successCount", result.getSuccessCount());
        response.put("failCount", result.getFailCount());
        response.put("rowsProcessed", result.getTotalCount());
        response.put("elapsedMs", elapsedMs);
        response.put("elapsedSec", String.format("%.2f", elapsedMs / 1000.0));
        response.put("processMs", result.getProcessMs());
        response.put("postProcessAndCommitMs", remainingMs);
        response.put("peakMemoryMB", String.format("%.2f", benchmark.peakMemory / (1024.0 * 1024.0)));
        response.put("memoryUsagePercent", String.format("%.2f", benchmark.peakMemory * 100.0 / benchmark.maxMemory));
        response.put("throughput", elapsedMs > 0
                ? String.format("%.0f", result.getSuccessCount() / (elapsedMs / 1000.0))
                : "0");
        response.put("simulatedLatencyMs", latencyMs);
        response.put("dbRoundTrips", dbRoundTrips);
        response.put("estimatedNetworkOverheadMs", networkOverheadMs);
        if (latencyMs > 0 && elapsedMs > 0) {
            response.put("networkOverheadPct",
                    String.format("%.1f%%", networkOverheadMs * 100.0 / elapsedMs));
        }
        return response;
    }

    private Map<String, Object> errorBody(Exception e) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("status", "ROLLBACK");
        error.put("error", e.getMessage());
        return error;
    }
}
