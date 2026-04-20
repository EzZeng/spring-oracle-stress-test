package com.stresstest.spring.service;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Random;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 產生測試用的固定寬度檔案。
 *
 * 檔案格式：
 *   第 1 行：日期（固定 10 字元，格式 yyyy/MM/dd）
 *   第 2 ~ 1,000,001 行：資料（每行 120 字元，12 欄位 a~l，每欄 10 字元）
 *   最後一行：檢核行（格式 "筆數,字元數總和"，例如 "1000000,120000000"）
 */
@Service
public class FileGenerator {

    private static final Logger log = LoggerFactory.getLogger(FileGenerator.class);

    private static final int DEFAULT_TOTAL_ROWS = 1_000_000;
    private static final int FIELDS_PER_ROW = 12;
    private static final int FIELD_WIDTH = 10;
    private static final int LINE_LENGTH = FIELDS_PER_ROW * FIELD_WIDTH;
    private static final String CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

    public Path generate(String dir) throws IOException {
        return generate(dir, DEFAULT_TOTAL_ROWS);
    }

    public Path generate(String dir, int totalRows) throws IOException {
        String fileName = totalRows == DEFAULT_TOTAL_ROWS ? "test_data.dat" : "test_data_" + totalRows + ".dat";
        Path filePath = Paths.get(dir, fileName);
        log.info("開始產生測試檔案: {} ({} 筆)", filePath, totalRows);
        log.info("格式: 第1行=日期(10字元), 第2~{}行=資料(120字元), 最後行=檢核", totalRows + 1);

        long start = System.currentTimeMillis();
        Random random = new Random(42);
        long totalCharCount = 0;

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(filePath.toFile()), 8 * 1024 * 1024)) {
            // ===== 第 1 行：日期 header（固定 10 字元，yyyy/MM/dd）=====
            String dateHeader = new SimpleDateFormat("yyyy/MM/dd").format(new Date());
            writer.write(dateHeader);
            writer.newLine();
            log.info("Header: {}", dateHeader);

            // ===== 第 2 ~ 1,000,001 行：資料 =====
            char[] lineBuffer = new char[LINE_LENGTH];
            for (int row = 0; row < totalRows; row++) {
                for (int i = 0; i < LINE_LENGTH; i++) {
                    lineBuffer[i] = CHARS.charAt(random.nextInt(CHARS.length()));
                }
                writer.write(lineBuffer);
                writer.newLine();
                totalCharCount += LINE_LENGTH;

                if ((row + 1) % 200_000 == 0) {
                    log.info("已產生 {} / {} 行", row + 1, totalRows);
                }
            }

            // ===== 最後一行：檢核（筆數,字元數總和）=====
            String trailer = totalRows + "," + totalCharCount;
            writer.write(trailer);
            writer.newLine();
            log.info("Trailer: {}", trailer);
        }

        long elapsed = System.currentTimeMillis() - start;
        long fileSize = filePath.toFile().length();
        log.info("檔案產生完成。大小: {} MB, 耗時: {} 秒",
                String.format("%.2f", fileSize / (1024.0 * 1024.0)),
                String.format("%.2f", elapsed / 1000.0));
        return filePath;
    }

    public static void main(String[] args) throws IOException {
        String dir = args.length > 0 ? args[0] : "stress-test-data";
        int rows = args.length > 1 ? Integer.parseInt(args[1]) : DEFAULT_TOTAL_ROWS;
        new java.io.File(dir).mkdirs();
        new FileGenerator().generate(dir, rows);
    }
}
