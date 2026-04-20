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
import java.util.List;

/**
 * JPA 策略 — Repository save 逐筆。
 * 收集到 List<RecordEntity> → 逐筆呼叫 save()，每 BATCH_SIZE 筆 flush/clear。
 */
@Component
@Order(11)
public class JpaSaveStrategy implements ProcessingStrategy {

    private static final Logger log = LoggerFactory.getLogger(JpaSaveStrategy.class);
    private static final int BATCH_SIZE = 5000;

    @Autowired
    private JpaDatabaseService jpaDb;

    @Override
    public String getName() {
        return "[JPA] Repository save 逐筆";
    }

    @Override
    @Transactional
    public ProcessResult process(Path filePath, DatabaseService db, String fileName) throws Exception {
        long count = 0;
        long masterId = jpaDb.insertMaster(fileName);

        try (BufferedReader reader = new BufferedReader(new FileReader(filePath.toFile()), 8 * 1024 * 1024)) {
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
                        for (RecordEntity e : batch) {
                            jpaDb.saveViaRepository(e);
                        }
                        jpaDb.flushAndClear();
                        count += batch.size();
                        batch.clear();

                        if (count % 200_000 == 0) {
                            log.info("[JPA-Save] 已送出 {} 筆", count);
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
                for (RecordEntity e : batch) {
                    jpaDb.saveViaRepository(e);
                }
                jpaDb.flushAndClear();
                count += batch.size();
                batch.clear();
            }

            // Trailer 檢核
            FileValidator.validateTrailer(prevLine, count, totalCharSum);
        }

        jpaDb.updateMasterSummary(masterId, count, 0);
        log.info("[JPA-Save] COMMIT 成功，共 {} 筆 (masterId={})", count, masterId);
        return new ProcessResult(masterId, count, 0);
    }
}
