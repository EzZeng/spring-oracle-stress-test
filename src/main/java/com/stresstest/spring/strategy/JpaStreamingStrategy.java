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
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * JPA 策略 — Streaming 逐行（List<RecordEntity> 收集 → EntityManager persistBatch）。
 * 逐行讀取 → 收集到 List → 每 BATCH_SIZE 筆 persistBatch + flush/clear → 最後 COMMIT。
 */
@Component
@Order(7)
public class JpaStreamingStrategy implements ProcessingStrategy {

    private static final Logger log = LoggerFactory.getLogger(JpaStreamingStrategy.class);
    private static final int BATCH_SIZE = 5000;

    @Autowired
    private JpaDatabaseService jpaDb;

    @Override
    public String getName() {
        return "[JPA] Streaming 逐行";
    }

    @Override
    @Transactional
    public ProcessResult process(Path filePath, DatabaseService db, String fileName) throws Exception {
        long count = 0;
        long masterId = jpaDb.insertMaster(fileName);

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(filePath.toFile()), StandardCharsets.UTF_8), 8 * 1024 * 1024)) {
            // Header 檢核
            String headerLine = reader.readLine();
            FileValidator.validateHeader(headerLine);

            String line;
            String prevLine = null;
            long totalCharSum = 0;
            List<RecordEntity> batch = new ArrayList<>(BATCH_SIZE);

            while ((line = reader.readLine()) != null) {
                if (prevLine != null) {
                    totalCharSum += prevLine.length();
                    batch.add(RecordEntity.fromLine(prevLine, count + batch.size() + 1, masterId));

                    if (batch.size() >= BATCH_SIZE) {
                        long startId = jpaDb.reserveIdRange(batch.size());
                        for (int i = 0; i < batch.size(); i++) {
                            batch.get(i).setId(startId + i);
                        }
                        jpaDb.persistBatch(batch);
                        count += batch.size();
                        batch.clear();
                    }
                    if (count % 200_000 == 0 && count > 0 && batch.isEmpty()) {
                        log.info("[JPA-Streaming] 已送出 {} 筆", count);
                    }
                }
                prevLine = line;
            }

            if (!batch.isEmpty()) {
                long startId = jpaDb.reserveIdRange(batch.size());
                for (int i = 0; i < batch.size(); i++) {
                    batch.get(i).setId(startId + i);
                }
                jpaDb.persistBatch(batch);
                count += batch.size();
                batch.clear();
            }

            // Trailer 檢核
            FileValidator.validateTrailer(prevLine, count, totalCharSum);
        }

        jpaDb.updateMasterSummary(masterId, count, 0);
        log.info("[JPA-Streaming] COMMIT 成功，共 {} 筆 (masterId={})", count, masterId);
        return new ProcessResult(masterId, count, 0);
    }
}
