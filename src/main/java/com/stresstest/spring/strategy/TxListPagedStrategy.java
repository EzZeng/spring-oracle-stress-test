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
import java.util.Collections;
import java.util.List;

/**
 * 單一交易 - List 分頁處理（推薦方案）。
 *
 * 最接近原始 List 寫法、又能在單一交易中安全處理 100 萬筆的方案。
 * 每頁 5000 筆：驗證 → 解析 → 排序 → flush batch。
 * 整個檔案 = 一個 Oracle transaction。
 */
@Component
@Order(2)
public class TxListPagedStrategy implements ProcessingStrategy {

    private static final Logger log = LoggerFactory.getLogger(TxListPagedStrategy.class);
    private static final int PAGE_SIZE = 5000;
    private static final int BATCH_SIZE = 1000;

    @Override
    public String getName() {
        return "[TX] List分頁（推薦方案）";
    }

    @Override
    public ProcessResult process(Path filePath, DatabaseService db, String fileName) throws Exception {
        db.initMasterDetailSchema();

        Connection conn = db.getConnection();
        try {
            long masterId = DatabaseService.insertMaster(conn, fileName);
            PreparedStatement ps = DatabaseService.prepareDetailInsert(conn);
            long totalCount = 0;
            long totalErrors = 0;

            try (BufferedReader reader = new BufferedReader(new FileReader(filePath.toFile()), 8 * 1024 * 1024)) {
                // ===== 檢核 Header（第一行）=====
                String headerLine = reader.readLine();
                FileValidator.validateHeader(headerLine);

                long totalCharSum = 0;
                String pendingTrailer = null;

                List<String> page;
                while (!(page = readPage(reader)).isEmpty()) {
                    // 如果上一輪有 pending trailer，那它其實是資料行，插入本頁前面
                    if (pendingTrailer != null) {
                        page.add(0, pendingTrailer);
                        pendingTrailer = null;
                    }

                    // 如果已經無更多資料，最後一行可能是 trailer
                    // 先假設最後一行是 trailer（最後一個 page 才會驗證）
                    // 先把最後一行拿出來暫存
                    pendingTrailer = page.remove(page.size() - 1);

                    // 步驟 1：驗證
                    long pageCharSum = 0;
                    List<String> validLines = new ArrayList<>(page.size());
                    for (String line : page) {
                        if (line.length() >= 120) {
                            validLines.add(line);
                            pageCharSum += line.length();
                        } else {
                            totalErrors++;
                        }
                    }

                    // 步驟 2：解析
                    List<RowRecord> records = new ArrayList<>(validLines.size());
                    for (String line : validLines) {
                        records.add(RowRecord.parse(line));
                    }

                    // 步驟 3：業務邏輯（排序）
                    Collections.sort(records, new java.util.Comparator<RowRecord>() {
                        @Override
                        public int compare(RowRecord r1, RowRecord r2) {
                            return r1.getA().compareTo(r2.getA());
                        }
                    });

                    // 步驟 4：寫入 DB（只 flush，不 commit）
                    long pageCount = 0;
                    for (RowRecord record : records) {
                        DatabaseService.setDetailParams(ps, record, masterId);
                        pageCount++;
                        if (pageCount % BATCH_SIZE == 0) {
                            DatabaseService.flushBatch(ps);
                        }
                    }
                    if (pageCount % BATCH_SIZE != 0) {
                        DatabaseService.flushBatch(ps);
                    }

                    totalCount += pageCount;
                    totalCharSum += pageCharSum;
                    if (totalCount % 200_000 == 0) {
                        log.info("[TX-ListPaged] 已送出 {} 筆", totalCount);
                    }
                }

                // ===== 檢核 Trailer（pendingTrailer 是最後一行）=====
                FileValidator.validateTrailer(pendingTrailer, totalCount, totalCharSum);
            }

            DatabaseService.updateMasterSummary(conn, masterId, totalCount, totalErrors);
            log.info("[TX-ListPaged] 寫入完成，共 {} 筆（驗證錯誤: {}）(masterId={})", totalCount, totalErrors, masterId);
            ps.close();
            return new ProcessResult(masterId, totalCount, totalErrors);

        } catch (Exception e) {
            log.error("[TX-ListPaged] 發生錯誤: {}", e.getMessage());
            throw e;
        } finally {
            conn.close();
        }
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
