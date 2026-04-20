package com.stresstest.spring.strategy;

import com.stresstest.spring.entity.RecordEntity;
import com.stresstest.spring.model.ProcessResult;
import com.stresstest.spring.service.DatabaseService;
import com.stresstest.spring.service.FileValidator;
import com.stresstest.spring.service.JpaDatabaseService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.BufferedReader;
import java.io.FileReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JPA 策略 — List分頁（推薦方案）。
 * 每頁 PAGE_SIZE 筆讀取 → 收集到 List<RecordEntity> → persistBatch → 下一頁。
 * 整個檔案一個 transaction。
 * 內建各階段計時（phaseTimings），供效能瓶頸分析。
 */
@Component
@Order(9)
public class JpaListPagedStrategy implements ProcessingStrategy {

    private static final Logger log = LoggerFactory.getLogger(JpaListPagedStrategy.class);
    private static final int PAGE_SIZE = 5000;

    @Autowired
    private JpaDatabaseService jpaDb;

    /** 最近一次 process() 的各階段累計耗時（毫秒） */
    private volatile Map<String, Object> lastPhaseTimings;

    public Map<String, Object> getLastPhaseTimings() {
        return lastPhaseTimings;
    }

    @Override
    public String getName() {
        return "[JPA] List分頁（推薦）";
    }

    @Override
    @Transactional
    public ProcessResult process(Path filePath, DatabaseService db, String fileName) throws Exception {
        long totalCount = 0;
        long totalErrors = 0;
        long masterId = jpaDb.insertMaster(fileName);

        // 各階段累計 (nanos)
        long nsFileRead = 0;
        long nsParse = 0;
        long nsReserveId = 0;
        long nsSetId = 0;
        long nsPersistLoop = 0;
        long nsFlush = 0;

        long t0, t1;

        try (BufferedReader reader = new BufferedReader(new FileReader(filePath.toFile()), 8 * 1024 * 1024)) {
            // Header 檢核
            String headerLine = reader.readLine();
            FileValidator.validateHeader(headerLine);

            long totalCharSum = 0;
            String pendingTrailer = null;

            List<String> page;
            while (true) {
                // ── 階段 1：讀檔 ──
                t0 = System.nanoTime();
                page = readPage(reader);
                nsFileRead += System.nanoTime() - t0;

                if (page.isEmpty()) break;

                if (pendingTrailer != null) {
                    page.add(0, pendingTrailer);
                    pendingTrailer = null;
                }
                pendingTrailer = page.remove(page.size() - 1);

                // ── 階段 2：解析成 Entity ──
                t0 = System.nanoTime();
                List<RecordEntity> entities = new ArrayList<>(page.size());
                long pageCharSum = 0;
                for (String line : page) {
                    if (line.length() >= 120) {
                        entities.add(RecordEntity.fromLine(line, totalCount + entities.size() + 1, masterId));
                        pageCharSum += line.length();
                    } else {
                        totalErrors++;
                    }
                }
                nsParse += System.nanoTime() - t0;

                if (!entities.isEmpty()) {
                    // ── 階段 3：保留 ID 範圍 (SELECT FOR UPDATE + UPDATE) ──
                    t0 = System.nanoTime();
                    long startId = jpaDb.reserveIdRange(entities.size());
                    nsReserveId += System.nanoTime() - t0;

                    // ── 階段 4：設定 ID ──
                    t0 = System.nanoTime();
                    for (int i = 0; i < entities.size(); i++) {
                        entities.get(i).setId(startId + i);
                    }
                    nsSetId += System.nanoTime() - t0;

                    // ── 階段 5 & 6：persist 迴圈 + flush/clear ──
                    long[] times = jpaDb.persistBatchTimed(entities);
                    nsPersistLoop += times[0];
                    nsFlush += times[1];

                    totalCount += entities.size();
                }
                totalCharSum += pageCharSum;
                if (totalCount % 200_000 == 0 && totalCount > 0) {
                    log.info("[JPA-ListPaged] 已送出 {} 筆", totalCount);
                }
            }

            // Trailer 檢核
            FileValidator.validateTrailer(pendingTrailer, totalCount, totalCharSum);
        }

        // 轉換成毫秒並記錄
        long totalNs = nsFileRead + nsParse + nsReserveId + nsSetId + nsPersistLoop + nsFlush;
        Map<String, Object> timings = new LinkedHashMap<>();
        timings.put("fileReadMs", nsFileRead / 1_000_000);
        timings.put("parseMs", nsParse / 1_000_000);
        timings.put("reserveIdMs", nsReserveId / 1_000_000);
        timings.put("setIdMs", nsSetId / 1_000_000);
        timings.put("persistLoopMs", nsPersistLoop / 1_000_000);
        timings.put("flushMs", nsFlush / 1_000_000);
        timings.put("measuredTotalMs", totalNs / 1_000_000);

        // 百分比
        if (totalNs > 0) {
            timings.put("fileReadPct", String.format("%.1f%%", nsFileRead * 100.0 / totalNs));
            timings.put("parsePct", String.format("%.1f%%", nsParse * 100.0 / totalNs));
            timings.put("reserveIdPct", String.format("%.1f%%", nsReserveId * 100.0 / totalNs));
            timings.put("setIdPct", String.format("%.1f%%", nsSetId * 100.0 / totalNs));
            timings.put("persistLoopPct", String.format("%.1f%%", nsPersistLoop * 100.0 / totalNs));
            timings.put("flushPct", String.format("%.1f%%", nsFlush * 100.0 / totalNs));
        }

        this.lastPhaseTimings = timings;

        jpaDb.updateMasterSummary(masterId, totalCount, totalErrors);
        log.info("[JPA-ListPaged] COMMIT 成功，共 {} 筆（驗證錯誤: {}）(masterId={})", totalCount, totalErrors, masterId);
        log.info("[JPA-ListPaged] 瓶頸分析: fileRead={}ms parse={}ms reserveId={}ms setId={}ms persist={}ms flush={}ms",
                timings.get("fileReadMs"), timings.get("parseMs"), timings.get("reserveIdMs"),
                timings.get("setIdMs"), timings.get("persistLoopMs"), timings.get("flushMs"));

        return new ProcessResult(masterId, totalCount, totalErrors);
    }

    private List<String> readPage(BufferedReader reader) throws Exception {
        List<String> page = new ArrayList<>(PAGE_SIZE);
        String line;
        while (page.size() < PAGE_SIZE && (line = reader.readLine()) != null) {
            page.add(line);
        }
        return page;
    }
}
