package com.stresstest.spring.strategy;

import com.stresstest.spring.model.ProcessResult;
import com.stresstest.spring.model.RowRecord;
import com.stresstest.spring.service.DatabaseService;
import com.stresstest.spring.service.FileValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.FileReader;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;

/**
 * 單一交易 - BatchChunk 分批處理。
 * 每次讀 10,000 行到 List → 解析 → flush batch → 下一批 → 最後才 COMMIT。
 */
@Component
@Order(3)
public class TxBatchChunkStrategy implements ProcessingStrategy {

    private static final Logger log = LoggerFactory.getLogger(TxBatchChunkStrategy.class);
    private static final int CHUNK_SIZE = 10_000;
    private static final int BATCH_SIZE = 5000;

    @Override
    public String getName() {
        return "[TX] BatchChunk 分批";
    }

    @Override
    public ProcessResult process(Path filePath, DatabaseService db, String fileName) throws Exception {
        db.initMasterDetailSchema();

        Connection conn = db.getConnection();
        try {
            long masterId = DatabaseService.insertMaster(conn, fileName);
            PreparedStatement ps = DatabaseService.prepareDetailInsert(conn);
            long totalCount = 0;

            try (BufferedReader reader = new BufferedReader(new FileReader(filePath.toFile()), 8 * 1024 * 1024)) {
                // ===== 檢核 Header（第一行）=====
                String headerLine = reader.readLine();
                FileValidator.validateHeader(headerLine);

                List<String> chunk = new ArrayList<>(CHUNK_SIZE);
                String line;
                String prevLine = null;
                long totalCharSum = 0;

                while ((line = reader.readLine()) != null) {
                    if (prevLine != null) {
                        totalCharSum += prevLine.length();
                        chunk.add(prevLine);
                        if (chunk.size() >= CHUNK_SIZE) {
                            totalCount += processChunk(chunk, ps, masterId);
                            chunk.clear();
                            if (totalCount % 200_000 == 0) {
                                log.info("[TX-BatchChunk] 已送出 {} 筆", totalCount);
                            }
                        }
                    }
                    prevLine = line;
                }
                if (!chunk.isEmpty()) {
                    totalCount += processChunk(chunk, ps, masterId);
                }

                // ===== 檢核 Trailer（最後一行）=====
                FileValidator.validateTrailer(prevLine, totalCount, totalCharSum);
            }

            DatabaseService.updateMasterSummary(conn, masterId, totalCount, 0);
            log.info("[TX-BatchChunk] 寫入完成，共 {} 筆 (masterId={})", totalCount, masterId);
            ps.close();
            return new ProcessResult(masterId, totalCount, 0);

        } catch (Exception e) {
            log.error("[TX-BatchChunk] 發生錯誤: {}", e.getMessage());
            throw e;
        } finally {
            conn.close();
        }
    }

    private long processChunk(List<String> chunk, PreparedStatement ps, long masterId) throws Exception {
        long count = 0;
        for (String line : chunk) {
            RowRecord record = RowRecord.parse(line);
            DatabaseService.setDetailParams(ps, record, masterId);
            count++;
            if (count % BATCH_SIZE == 0) {
                DatabaseService.flushBatch(ps);
            }
        }
        if (count % BATCH_SIZE != 0) {
            DatabaseService.flushBatch(ps);
        }
        return count;
    }
}
