package com.stresstest.spring.strategy;

import com.stresstest.spring.model.ProcessResult;
import com.stresstest.spring.model.RowRecord;
import com.stresstest.spring.service.DatabaseService;
import com.stresstest.spring.service.FileValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.RandomAccessFile;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;

/**
 * 單一交易 - MemoryMapped 處理。
 * NIO MappedByteBuffer 讀取 → 解析 → flush batch → 最後才 COMMIT。
 */
@Component
@Order(4)
public class TxMemoryMappedStrategy implements ProcessingStrategy {

    private static final Logger log = LoggerFactory.getLogger(TxMemoryMappedStrategy.class);
    private static final int BATCH_SIZE = 5000;

    @Override
    public String getName() {
        return "[TX] MemoryMapped";
    }

    @Override
    public ProcessResult process(Path filePath, DatabaseService db, String fileName) throws Exception {
        db.initMasterDetailSchema();

        Connection conn = db.getConnection();
        try {
            long masterId = DatabaseService.insertMaster(conn, fileName);
            PreparedStatement ps = DatabaseService.prepareDetailInsert(conn);
            long totalCount = 0;

            try (RandomAccessFile raf = new RandomAccessFile(filePath.toFile(), "r");
                 FileChannel channel = raf.getChannel()) {

                long fileSize = channel.size();
                long segmentSize = 256L * 1024 * 1024;
                long position = 0;
                // 用 byte buffer 收集整行 bytes，line-break 再以 UTF-8 一次解碼
                // （UTF-8 中 \n/\r 僅會以 0x0A/0x0D 單 byte 出現，不會是多位元組的一部分）
                ByteArrayOutputStream lineBytes = new ByteArrayOutputStream(256);
                boolean headerValidated = false;
                String lastLine = null;
                long totalCharSum = 0;

                while (position < fileSize) {
                    long remaining = fileSize - position;
                    long mapSize = Math.min(segmentSize, remaining);
                    MappedByteBuffer buffer = channel.map(FileChannel.MapMode.READ_ONLY, position, mapSize);

                    while (buffer.hasRemaining()) {
                        byte b = buffer.get();
                        if (b == '\n') {
                            // Java 8: ByteArrayOutputStream.toString(Charset) 是 Java 10+，改用 String 名稱版
                            String line = lineBytes.toString("UTF-8");
                            lineBytes.reset();
                            if (line.length() > 0 && line.charAt(line.length() - 1) == '\r') {
                                line = line.substring(0, line.length() - 1);
                            }
                            if (line.length() > 0) {
                                if (!headerValidated) {
                                    // 第一行 = Header
                                    FileValidator.validateHeader(line);
                                    headerValidated = true;
                                } else {
                                    // 處理前一行（資料行）
                                    if (lastLine != null) {
                                        totalCharSum += lastLine.length();
                                        RowRecord record = RowRecord.parse(lastLine);
                                        DatabaseService.setDetailParams(ps, record, masterId);
                                        totalCount++;

                                        if (totalCount % BATCH_SIZE == 0) {
                                            DatabaseService.flushBatch(ps);
                                        }
                                        if (totalCount % 200_000 == 0) {
                                            log.info("[TX-MMap] 已送出 {} 筆", totalCount);
                                        }
                                    }
                                    lastLine = line;
                                }
                            }
                        } else {
                            lineBytes.write(b);
                        }
                    }
                    position += mapSize;
                }

                // 處理最後一行（如果檔案結尾無換行）
                if (lineBytes.size() > 0) {
                    // Java 8: ByteArrayOutputStream.toString(Charset) 是 Java 10+
                    String line = lineBytes.toString("UTF-8");
                    if (line.charAt(line.length() - 1) == '\r') {
                        line = line.substring(0, line.length() - 1);
                    }
                    if (lastLine != null) {
                        totalCharSum += lastLine.length();
                        RowRecord record = RowRecord.parse(lastLine);
                        DatabaseService.setDetailParams(ps, record, masterId);
                        totalCount++;
                    }
                    lastLine = line;
                }

                if (totalCount % BATCH_SIZE != 0) {
                    DatabaseService.flushBatch(ps);
                }

                // ===== 檢核 Trailer（lastLine 是最後一行）=====
                FileValidator.validateTrailer(lastLine, totalCount, totalCharSum);
            }

            DatabaseService.updateMasterSummary(conn, masterId, totalCount, 0);
            log.info("[TX-MMap] 寫入完成，共 {} 筆 (masterId={})", totalCount, masterId);
            ps.close();
            return new ProcessResult(masterId, totalCount, 0);

        } catch (Exception e) {
            log.error("[TX-MMap] 發生錯誤: {}", e.getMessage());
            throw e;
        } finally {
            conn.close();
        }
    }
}
