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
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;

/**
 * 單一交易 - List 全載入。
 * 全部讀入 List → 全部寫入 → 最後才 COMMIT，失敗整個 ROLLBACK。
 */
@Component
@Order(1)
public class TxListStrategy implements ProcessingStrategy {

    private static final Logger log = LoggerFactory.getLogger(TxListStrategy.class);
    private static final int BATCH_SIZE = 5000;

    @Override
    public String getName() {
        return "[TX] List 全載入";
    }

    @Override
    public ProcessResult process(Path filePath, DatabaseService db, String fileName) throws Exception {
        db.initMasterDetailSchema();

        log.info("[TX-List] 讀取所有行到 List...");
        List<String> allLines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(filePath.toFile()), StandardCharsets.UTF_8), 8 * 1024 * 1024)) {
            String line;
            while ((line = reader.readLine()) != null) {
                allLines.add(line);
            }
        }
        log.info("[TX-List] 讀取完成，共 {} 行（含 header/trailer）", allLines.size());

        // ===== 檢核 Header（第一行）=====
        String headerLine = allLines.remove(0);
        FileValidator.validateHeader(headerLine);

        // ===== 取出 Trailer（最後一行）=====
        String trailerLine = allLines.remove(allLines.size() - 1);

        // ===== 計算字元數總和並檢核 Trailer =====
        long totalCharSum = 0;
        for (String line : allLines) {
            totalCharSum += line.length();
        }
        FileValidator.validateTrailer(trailerLine, allLines.size(), totalCharSum);

        log.info("[TX-List] 解析為 RowRecord...");
        List<RowRecord> allRecords = new ArrayList<>(allLines.size());
        for (String line : allLines) {
            allRecords.add(RowRecord.parse(line));
        }
        allLines.clear();
        allLines = null;

        log.info("[TX-List] 開始寫入 Oracle（單一交易）...");
        Connection conn = db.getConnection();
        try {
            long masterId = DatabaseService.insertMaster(conn, fileName);
            PreparedStatement ps = DatabaseService.prepareDetailInsert(conn);
            long count = 0;

            for (RowRecord record : allRecords) {
                DatabaseService.setDetailParams(ps, record, masterId);
                count++;
                if (count % BATCH_SIZE == 0) {
                    DatabaseService.flushBatch(ps);
                }
                if (count % 200_000 == 0) {
                    log.info("[TX-List] 已送出 {} 筆", count);
                }
            }
            if (count % BATCH_SIZE != 0) {
                DatabaseService.flushBatch(ps);
            }

            DatabaseService.updateMasterSummary(conn, masterId, count, 0);
            log.info("[TX-List] 寫入完成，共 {} 筆 (masterId={})", count, masterId);
            ps.close();
            return new ProcessResult(masterId, count, 0);

        } catch (Exception e) {
            log.error("[TX-List] 發生錯誤: {}", e.getMessage());
            throw e;
        } finally {
            conn.close();
        }
    }
}
