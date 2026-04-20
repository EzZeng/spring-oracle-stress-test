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

/**
 * 單一交易 - Streaming 逐行處理。
 * 逐行讀取 → 解析 → flush batch → 最後才 COMMIT，失敗整個 ROLLBACK。
 */
@Component
@Order(6)
public class TxStreamingStrategy implements ProcessingStrategy {

    private static final Logger log = LoggerFactory.getLogger(TxStreamingStrategy.class);
    private static final int BATCH_SIZE = 5000;

    @Override
    public String getName() {
        return "[TX] Streaming 逐行";
    }

    @Override
    public ProcessResult process(Path filePath, DatabaseService db, String fileName) throws Exception {
        db.initMasterDetailSchema();

        Connection conn = db.getConnection();
        try {
            long masterId = DatabaseService.insertMaster(conn, fileName);
            PreparedStatement ps = DatabaseService.prepareDetailInsert(conn);
            long count = 0;

            try (BufferedReader reader = new BufferedReader(new FileReader(filePath.toFile()), 8 * 1024 * 1024)) {
                // ===== 檢核 Header（第一行）=====
                String headerLine = reader.readLine();
                FileValidator.validateHeader(headerLine);

                String line;
                String prevLine = null;
                long totalCharSum = 0;

                while ((line = reader.readLine()) != null) {
                    if (prevLine != null) {
                        // prevLine 是資料行
                        totalCharSum += prevLine.length();
                        RowRecord record = RowRecord.parse(prevLine);
                        DatabaseService.setDetailParams(ps, record, masterId);
                        count++;

                        if (count % BATCH_SIZE == 0) {
                            DatabaseService.flushBatch(ps);
                        }
                        if (count % 200_000 == 0) {
                            log.info("[TX-Streaming] 已送出 {} 筆", count);
                        }
                    }
                    prevLine = line;
                }

                // ===== 檢核 Trailer（最後一行）=====
                FileValidator.validateTrailer(prevLine, count, totalCharSum);
            }

            if (count % BATCH_SIZE != 0) {
                DatabaseService.flushBatch(ps);
            }

            DatabaseService.updateMasterSummary(conn, masterId, count, 0);
            log.info("[TX-Streaming] 寫入完成，共 {} 筆 (masterId={})", count, masterId);
            ps.close();
            return new ProcessResult(masterId, count, 0);

        } catch (Exception e) {
            log.error("[TX-Streaming] 發生錯誤: {}", e.getMessage());
            throw e;
        } finally {
            conn.close();
        }
    }
}
