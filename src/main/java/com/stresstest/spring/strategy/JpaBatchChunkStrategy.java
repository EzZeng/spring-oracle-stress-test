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
 * JPA 策略 — BatchChunk 分批（List<RecordEntity> 收集 → EntityManager persistBatch）。
 * 每次讀 CHUNK_SIZE 行 → 收集到 List<RecordEntity> → 每 BATCH_SIZE 筆 persistBatch → 最後 COMMIT。
 */
@Component
@Order(8)
public class JpaBatchChunkStrategy implements ProcessingStrategy {

    private static final Logger log = LoggerFactory.getLogger(JpaBatchChunkStrategy.class);
    private static final int CHUNK_SIZE = 10_000;
    private static final int BATCH_SIZE = 5000;

    @Autowired
    private JpaDatabaseService jpaDb;

    @Override
    public String getName() {
        return "[JPA] BatchChunk 分批";
    }

    @Override
    @Transactional
    public ProcessResult process(Path filePath, DatabaseService db, String fileName) throws Exception {
        long totalCount = 0;
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
                    batch.add(RecordEntity.fromLine(prevLine, totalCount + batch.size() + 1, masterId));

                    if (batch.size() >= BATCH_SIZE) {
                        long startId = jpaDb.reserveIdRange(batch.size());
                        for (int i = 0; i < batch.size(); i++) {
                            batch.get(i).setId(startId + i);
                        }
                        jpaDb.persistBatch(batch);
                        totalCount += batch.size();
                        batch.clear();
                        if (totalCount % 200_000 == 0) {
                            log.info("[JPA-BatchChunk] 已送出 {} 筆", totalCount);
                        }
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
                totalCount += batch.size();
                batch.clear();
            }

            // Trailer 檢核
            FileValidator.validateTrailer(prevLine, totalCount, totalCharSum);
        }

        jpaDb.updateMasterSummary(masterId, totalCount, 0);
        log.info("[JPA-BatchChunk] COMMIT 成功，共 {} 筆 (masterId={})", totalCount, masterId);
        return new ProcessResult(masterId, totalCount, 0);
    }
}
