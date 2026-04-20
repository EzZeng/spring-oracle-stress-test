package com.stresstest.spring.controller;

import com.stresstest.spring.dao.RecordRepository;
import com.stresstest.spring.entity.RecordEntity;
import com.stresstest.spring.service.MemoryMonitor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;

/**
 * CSV 下載 REST Controller。
 * GET /api/download?threads=1  → 從 jpa_records 匯出 CSV，回傳效能統計
 */
@RestController
@RequestMapping("/api")
public class DownloadController {

    private static final Logger log = LoggerFactory.getLogger(DownloadController.class);
    private static final int FETCH_SIZE = 10_000;

    @Autowired
    private EntityManagerFactory emf;

    @Autowired
    private RecordRepository recordRepository;

    @GetMapping("/download")
    @Transactional
    public ResponseEntity<Map<String, Object>> download(
            @RequestParam(value = "threads", defaultValue = "1") int threads) {

        if (threads < 1 || threads > 4) {
            return ResponseEntity.badRequest().body(
                    Collections.singletonMap("error", (Object) "threads 必須在 1~4 之間"));
        }

        String strategyName = "Download (" + threads + " thread" + (threads > 1 ? "s" : "") + ")";
        log.info("=== 開始下載：{} ===", strategyName);

        MemoryMonitor monitor = new MemoryMonitor();
        monitor.start();

        Path csvFile = null;
        try {
            // 取得 ID 範圍與總筆數
            EntityManager em = emf.createEntityManager();
            Object[] range;
            try {
                range = (Object[]) em.createQuery(
                        "SELECT MIN(r.id), MAX(r.id), COUNT(r) FROM RecordEntity r")
                        .getSingleResult();
            } finally {
                em.close();
            }

            if (range[0] == null) {
                return ResponseEntity.badRequest().body(
                        Collections.singletonMap("error", (Object) "jpa_records 無資料，請先上傳"));
            }

            long minId = ((Number) range[0]).longValue();
            long maxId = ((Number) range[1]).longValue();
            long totalCount = ((Number) range[2]).longValue();

            // 檢查已下載筆數
            long alreadyDownloaded = recordRepository.countDownloaded(minId, maxId);
            if (alreadyDownloaded > 0) {
                log.warn("偵測到 {} 筆已下載記錄（共 {} 筆），拒絕重複下載", alreadyDownloaded, totalCount);
                Map<String, Object> dupError = new LinkedHashMap<>();
                dupError.put("status", "REJECTED");
                dupError.put("error", "重複下載：已有 " + alreadyDownloaded + " / " + totalCount + " 筆已被下載");
                dupError.put("alreadyDownloaded", alreadyDownloaded);
                dupError.put("totalRecords", totalCount);
                return ResponseEntity.status(409).body(dupError);
            }

            // 原子性標記下載時間（WHERE download_time IS NULL）
            Timestamp downloadTs = new Timestamp(System.currentTimeMillis());
            int marked = recordRepository.markDownloaded(downloadTs, minId, maxId);
            log.info("已標記 {} 筆（預期 {}）download_time = {}", marked, totalCount, downloadTs);

            if (marked == 0) {
                Map<String, Object> dupError = new LinkedHashMap<>();
                dupError.put("status", "REJECTED");
                dupError.put("error", "所有記錄已被其他 AP Server 標記下載");
                dupError.put("markedCount", 0);
                return ResponseEntity.status(409).body(dupError);
            }

            // 產生 CSV
            csvFile = Files.createTempFile("download-", ".csv");

            if (threads == 1) {
                generateCsvSingleThread(csvFile, minId, maxId, totalCount);
            } else {
                generateCsvMultiThread(csvFile, minId, maxId, totalCount, threads);
            }

            long fileSize = csvFile.toFile().length();

            MemoryMonitor.BenchmarkResult result = monitor.stop(strategyName, totalCount);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("strategy", strategyName);
            response.put("status", "OK");
            response.put("rowsExported", totalCount);
            response.put("rowsMarked", marked);
            response.put("downloadTime", downloadTs.toString());
            response.put("fileSizeBytes", fileSize);
            response.put("fileSizeMB", String.format("%.2f", fileSize / (1024.0 * 1024.0)));
            response.put("csvPath", csvFile.toString());
            response.put("elapsedMs", result.elapsedMs);
            response.put("elapsedSec", String.format("%.2f", result.elapsedMs / 1000.0));
            response.put("peakMemoryMB", String.format("%.2f", result.peakMemory / (1024.0 * 1024.0)));
            response.put("throughput", String.format("%.0f", totalCount / (result.elapsedMs / 1000.0)));
            response.put("maxHeapMB", String.format("%.0f", result.maxMemory / (1024.0 * 1024.0)));

            log.info("=== 完成：{} | 耗時: {}ms | 峰值: {} MB | 筆數: {} | 標記: {} | 檔案: {} MB ===",
                    strategyName, result.elapsedMs,
                    String.format("%.2f", result.peakMemory / (1024.0 * 1024.0)),
                    totalCount, marked,
                    String.format("%.2f", fileSize / (1024.0 * 1024.0)));

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            monitor.stop(strategyName, 0);
            log.error("下載失敗: {}", e.getMessage(), e);
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("status", "ERROR");
            error.put("error", e.getMessage());
            return ResponseEntity.status(500).body(error);
        } finally {
            if (csvFile != null) {
                try { Files.deleteIfExists(csvFile); } catch (Exception ignored) {}
            }
        }
    }

    /**
     * 單執行緒：逐批查詢 → 寫入 CSV。
     */
    private void generateCsvSingleThread(Path csvFile, long minId, long maxId, long totalCount) throws Exception {
        EntityManager em = emf.createEntityManager();
        try (BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(csvFile.toFile()), StandardCharsets.UTF_8), 8 * 1024 * 1024)) {
            // Header: 下載日期
            writer.write(new SimpleDateFormat("yyyy/MM/dd").format(new Date()));
            writer.newLine();

            // Data
            long currentMin = minId;
            long exported = 0;
            while (currentMin <= maxId) {
                long currentMax = Math.min(currentMin + FETCH_SIZE - 1, maxId);
                List<RecordEntity> page = em.createQuery(
                        "SELECT r FROM RecordEntity r WHERE r.id >= :min AND r.id <= :max ORDER BY r.id",
                        RecordEntity.class)
                        .setParameter("min", currentMin)
                        .setParameter("max", currentMax)
                        .getResultList();

                for (RecordEntity r : page) {
                    writer.write(r.toCsvLine());
                    writer.newLine();
                    exported++;
                }
                em.clear();
                currentMin = currentMax + 1;
                if (exported % 200_000 == 0) {
                    log.info("[Download-1T] 已匯出 {} 筆", exported);
                }
            }

            // Trailer: 總筆數
            writer.write(String.valueOf(totalCount));
            writer.newLine();
        } finally {
            em.close();
        }
    }

    /**
     * 多執行緒：每個 thread 負責一段 ID 範圍 → 寫入個別暫存檔 → 合併。
     */
    private void generateCsvMultiThread(Path csvFile, long minId, long maxId, long totalCount, int threadCount) throws Exception {
        long range = maxId - minId + 1;
        long chunkSize = range / threadCount;

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        List<Future<Path>> futures = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            long start = minId + i * chunkSize;
            long end = (i == threadCount - 1) ? maxId : start + chunkSize - 1;
            final int threadIdx = i;
            futures.add(executor.submit(() -> generateChunk(start, end, threadIdx)));
        }

        executor.shutdown();

        // 合併：Header + 各 chunk 檔案 + Trailer
        try (OutputStream out = new BufferedOutputStream(
                new FileOutputStream(csvFile.toFile()), 8 * 1024 * 1024)) {
            String header = new SimpleDateFormat("yyyy/MM/dd").format(new Date()) + "\n";
            out.write(header.getBytes(StandardCharsets.UTF_8));

            for (Future<Path> f : futures) {
                Path chunkFile = f.get();
                try (InputStream in = new BufferedInputStream(
                        new FileInputStream(chunkFile.toFile()), 4 * 1024 * 1024)) {
                    byte[] buf = new byte[65536];
                    int len;
                    while ((len = in.read(buf)) != -1) {
                        out.write(buf, 0, len);
                    }
                }
                Files.deleteIfExists(chunkFile);
            }

            String trailer = totalCount + "\n";
            out.write(trailer.getBytes(StandardCharsets.UTF_8));
        }
    }

    /**
     * 每個 thread 的 chunk：獨立 EntityManager 查詢 ID 範圍 → 寫入暫存檔。
     */
    private Path generateChunk(long startId, long endId, int threadIdx) throws Exception {
        Path chunkFile = Files.createTempFile("chunk-" + threadIdx + "-", ".csv");
        EntityManager em = emf.createEntityManager();
        try (BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(chunkFile.toFile()), StandardCharsets.UTF_8), 4 * 1024 * 1024)) {
            long currentMin = startId;
            long exported = 0;
            while (currentMin <= endId) {
                long currentMax = Math.min(currentMin + FETCH_SIZE - 1, endId);
                List<RecordEntity> page = em.createQuery(
                        "SELECT r FROM RecordEntity r WHERE r.id >= :min AND r.id <= :max ORDER BY r.id",
                        RecordEntity.class)
                        .setParameter("min", currentMin)
                        .setParameter("max", currentMax)
                        .getResultList();

                for (RecordEntity r : page) {
                    writer.write(r.toCsvLine());
                    writer.newLine();
                    exported++;
                }
                em.clear();
                currentMin = currentMax + 1;
            }
            log.info("[Download-Thread-{}] 完成 {} 筆 (ID {}~{})", threadIdx, exported, startId, endId);
        } finally {
            em.close();
        }
        return chunkFile;
    }
}
