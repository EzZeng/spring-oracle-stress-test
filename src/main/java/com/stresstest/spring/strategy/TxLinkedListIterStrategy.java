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
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

/**
 * 單一交易 - LinkedList + Iterator 處理。
 * 全部讀入 LinkedList → Iterator.remove() 邊消費邊釋放 → 最後才 COMMIT。
 */
@Component
@Order(5)
public class TxLinkedListIterStrategy implements ProcessingStrategy {

    private static final Logger log = LoggerFactory.getLogger(TxLinkedListIterStrategy.class);
    private static final int BATCH_SIZE = 5000;

    @Override
    public String getName() {
        return "[TX] LinkedList+Iter";
    }

    @Override
    public ProcessResult process(Path filePath, DatabaseService db, String fileName) throws Exception {
        db.initMasterDetailSchema();

        log.info("[TX-LLIter] 讀取所有行到 LinkedList...");
        List<String> allLines = new LinkedList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(filePath.toFile()), StandardCharsets.UTF_8), 8 * 1024 * 1024)) {
            String line;
            while ((line = reader.readLine()) != null) {
                allLines.add(line);
            }
        }
        log.info("[TX-LLIter] 讀取完成，共 {} 行（含 header/trailer）", allLines.size());

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

        Connection conn = db.getConnection();
        try {
            long masterId = DatabaseService.insertMaster(conn, fileName);
            PreparedStatement ps = DatabaseService.prepareDetailInsert(conn);
            long count = 0;

            Iterator<String> it = allLines.iterator();
            while (it.hasNext()) {
                String line = it.next();
                it.remove();

                RowRecord record = RowRecord.parse(line);
                DatabaseService.setDetailParams(ps, record, masterId);
                count++;

                if (count % BATCH_SIZE == 0) {
                    DatabaseService.flushBatch(ps);
                }
                if (count % 200_000 == 0) {
                    log.info("[TX-LLIter] 已送出 {} 筆", count);
                }
            }

            if (count % BATCH_SIZE != 0) {
                DatabaseService.flushBatch(ps);
            }

            DatabaseService.updateMasterSummary(conn, masterId, count, 0);
            log.info("[TX-LLIter] 寫入完成，共 {} 筆 (masterId={})", count, masterId);
            ps.close();
            return new ProcessResult(masterId, count, 0);

        } catch (Exception e) {
            log.error("[TX-LLIter] 發生錯誤: {}", e.getMessage());
            throw e;
        } finally {
            conn.close();
        }
    }
}
