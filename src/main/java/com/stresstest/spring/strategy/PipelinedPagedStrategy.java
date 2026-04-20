package com.stresstest.spring.strategy;

import com.stresstest.spring.model.ProcessResult;
import com.stresstest.spring.model.RowRecord;
import com.stresstest.spring.service.DatabaseService;
import com.stresstest.spring.service.FileValidator;
import com.stresstest.spring.service.PhaseMonitor;
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
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 優化策略 — 管線化分頁處理 + 分階段 CPU/RAM 監控。
 *
 * ┌────────────────────┐   BlockingQueue(2)   ┌────────────────────┐
 * │   Reader Thread    │ ──────────────────▶  │    Main Thread     │
 * │  讀取 + 解析行資料  │                      │  DB batch insert   │
 * └────────────────────┘                      └────────────────────┘
 *
 * 優化重點：
 *   1. 分頁讀取：每頁 PAGE_SIZE 行，記憶體上限 ≈ PAGE_SIZE × 120B × QUEUE_CAPACITY
 *   2. 管線化：Reader 讀下一頁 與 DB 寫當前頁「重疊執行」（I/O parallelism）
 *   3. UTF-8 byte stream：使用 InputStreamReader(UTF-8) 明確指定編碼
 *   4. 內建 PhaseMonitor：自動追蹤每階段 CPU / RAM 使用狀況
 *
 * 避免 OOM 的關鍵：
 *   - 永遠不將整個檔案載入記憶體
 *   - BlockingQueue 容量 = 2，最多緩衝 2 頁（~10,000 行 ≈ 1.2 MB）
 *   - 每批 flush 後已處理的 RowRecord 可被 GC 回收
 *   - 檔案透過 8MB BufferedReader 串流，不一次讀入全部 byte[]
 */
@Component
@Order(13)
public class PipelinedPagedStrategy implements ProcessingStrategy {

    private static final Logger log = LoggerFactory.getLogger(PipelinedPagedStrategy.class);
    private static final int PAGE_SIZE = 5000;
    private static final int BATCH_SIZE = 1000;
    private static final int QUEUE_CAPACITY = 2;

    /** 最近一次 process() 的 PhaseMonitor，供 Controller 取用 */
    private volatile PhaseMonitor lastMonitor;

    public PhaseMonitor getLastMonitor() {
        return lastMonitor;
    }

    @Override
    public String getName() {
        return "[TX] 管線化分頁（含監控）";
    }

    @Override
    public ProcessResult process(Path filePath, DatabaseService db, String fileName) throws Exception {
        PhaseMonitor monitor = new PhaseMonitor();
        monitor.start();

        try {
            // ── Phase 1: Schema 初始化 ──
            monitor.beginPhase("1.Schema初始化");
            db.initMasterDetailSchema();

            Connection conn = db.getConnection();
            try {
                // ── Phase 2: 建立案件 + Header 檢核 ──
                monitor.beginPhase("2.建立案件+Header檢核");
                long masterId = DatabaseService.insertMaster(conn, fileName);
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(
                                new FileInputStream(filePath.toFile()), StandardCharsets.UTF_8),
                        8 * 1024 * 1024);

                String headerLine = reader.readLine();
                FileValidator.validateHeader(headerLine);

                // ── Phase 3: 管線化讀取 + 寫入 ──
                monitor.beginPhase("3.管線化讀取+寫入");

                final BlockingQueue<ParsedBatch> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
                final AtomicReference<Exception> readerError = new AtomicReference<>();
                final BufferedReader readerRef = reader;

                // --- Reader Thread：讀取 → 驗證長度 → 解析 RowRecord → 放入 queue ---
                Thread readerThread = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            String pendingTrailer = null;
                            long validCount = 0;
                            long charSum = 0;

                            while (true) {
                                List<String> page = readPage(readerRef);
                                if (page.isEmpty()) {
                                    // 檔案讀完，傳送 trailer 資訊
                                    queue.put(ParsedBatch.trailer(pendingTrailer, validCount, charSum));
                                    break;
                                }

                                // 處理上一輪暫存的 pendingTrailer（其實是資料行）
                                if (pendingTrailer != null) {
                                    page.add(0, pendingTrailer);
                                    pendingTrailer = null;
                                }

                                // 最後一行可能是 trailer，先暫存
                                pendingTrailer = page.remove(page.size() - 1);

                                // 驗證 + 解析
                                List<RowRecord> records = new ArrayList<>(page.size());
                                long pageCharSum = 0;
                                long pageErrors = 0;

                                for (String line : page) {
                                    if (line.length() >= 120) {
                                        records.add(RowRecord.parse(line));
                                        pageCharSum += line.length();
                                    } else {
                                        pageErrors++;
                                    }
                                }

                                validCount += records.size();
                                charSum += pageCharSum;

                                queue.put(ParsedBatch.data(records, pageErrors));
                            }
                        } catch (Exception e) {
                            readerError.set(e);
                            try {
                                queue.put(ParsedBatch.poison());
                            } catch (InterruptedException ignored) {
                            }
                        }
                    }
                }, "pipeline-reader");
                readerThread.setDaemon(true);
                readerThread.start();

                // --- Main Thread：從 queue 取出 → DB batch insert ---
                PreparedStatement ps = DatabaseService.prepareDetailInsert(conn);
                long totalCount = 0;
                long totalErrors = 0;
                String trailerLine = null;
                long trailerValidCount = 0;
                long trailerCharSum = 0;

                while (true) {
                    ParsedBatch batch = queue.take();

                    if (batch.isPoison) {
                        Exception err = readerError.get();
                        if (err != null) throw err;
                        break;
                    }

                    if (batch.isTrailer) {
                        trailerLine = batch.trailerLine;
                        trailerValidCount = batch.trailerValidCount;
                        trailerCharSum = batch.trailerCharSum;
                        break;
                    }

                    // 寫入 DB
                    int batchCount = 0;
                    for (RowRecord record : batch.records) {
                        DatabaseService.setDetailParams(ps, record, masterId);
                        batchCount++;
                        if (batchCount % BATCH_SIZE == 0) {
                            DatabaseService.flushBatch(ps);
                        }
                    }
                    if (batchCount % BATCH_SIZE != 0) {
                        DatabaseService.flushBatch(ps);
                    }

                    totalCount += batch.records.size();
                    totalErrors += batch.errorCount;

                    if (totalCount % 200_000 == 0) {
                        Runtime rt = Runtime.getRuntime();
                        long usedMB = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
                        log.info("[Pipeline] 已送出 {} 筆 | heap={}MB", totalCount, usedMB);
                    }
                }

                // 等待 reader thread 結束
                readerThread.join(30_000);
                reader.close();

                // 確認 reader 沒有錯誤
                Exception err = readerError.get();
                if (err != null) throw err;

                // ── Phase 4: Trailer 檢核 ──
                monitor.beginPhase("4.Trailer檢核");
                FileValidator.validateTrailer(trailerLine, trailerValidCount, trailerCharSum);

                // ── Phase 5: 更新 Master Summary + COMMIT ──
                monitor.beginPhase("5.更新Summary+COMMIT");
                DatabaseService.updateMasterSummary(conn, masterId, totalCount, totalErrors);
                ps.close();

                log.info("[Pipeline] 寫入完成，共 {} 筆（驗證錯誤: {}）(masterId={})", totalCount, totalErrors, masterId);
                return new ProcessResult(masterId, totalCount, totalErrors);

            } catch (Exception e) {
                log.error("[Pipeline] 發生錯誤: {}", e.getMessage());
                throw e;
            } finally {
                conn.close();
            }

        } finally {
            monitor.stop();
            this.lastMonitor = monitor;
        }
    }

    private static List<String> readPage(BufferedReader reader) throws Exception {
        List<String> page = new ArrayList<>(PAGE_SIZE);
        String line;
        while (page.size() < PAGE_SIZE && (line = reader.readLine()) != null) {
            page.add(line);
        }
        return page;
    }

    // ===================== 管線內部資料結構 =====================

    /** Reader → Main 之間傳遞的批次資料 */
    static class ParsedBatch {
        final List<RowRecord> records;
        final long errorCount;
        final boolean isTrailer;
        final boolean isPoison;
        final String trailerLine;
        final long trailerValidCount;
        final long trailerCharSum;

        private ParsedBatch(List<RowRecord> records, long errorCount,
                            boolean isTrailer, boolean isPoison,
                            String trailerLine, long trailerValidCount, long trailerCharSum) {
            this.records = records;
            this.errorCount = errorCount;
            this.isTrailer = isTrailer;
            this.isPoison = isPoison;
            this.trailerLine = trailerLine;
            this.trailerValidCount = trailerValidCount;
            this.trailerCharSum = trailerCharSum;
        }

        /** 資料批次 */
        static ParsedBatch data(List<RowRecord> records, long errorCount) {
            return new ParsedBatch(records, errorCount, false, false, null, 0, 0);
        }

        /** 檔案結束，附帶 trailer 資訊 */
        static ParsedBatch trailer(String trailerLine, long validCount, long charSum) {
            return new ParsedBatch(null, 0, true, false, trailerLine, validCount, charSum);
        }

        /** 錯誤信號 */
        static ParsedBatch poison() {
            return new ParsedBatch(null, 0, false, true, null, 0, 0);
        }
    }
}
