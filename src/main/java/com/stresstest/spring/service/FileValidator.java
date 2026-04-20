package com.stresstest.spring.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.text.ParseException;
import java.text.SimpleDateFormat;

/**
 * 檔案格式檢核工具。
 *
 * 檔案格式：
 *   第 1 行（Header）：日期，固定 10 字元，格式 yyyy/MM/dd
 *   中間行（Data）：每行 120 字元的資料
 *   最後一行（Trailer）：格式 "筆數,字元數總和"
 *
 * 檢核規則：
 *   1. Header 必須恰好 10 字元，且為合法日期（yyyy/MM/dd）
 *   2. Trailer 中的筆數必須等於實際資料行數
 *   3. Trailer 中的字元數總和必須等於所有資料行字元數的加總
 */
public class FileValidator {

    private static final Logger log = LoggerFactory.getLogger(FileValidator.class);

    /**
     * 檢核 Header（第一行）。
     * @param headerLine 第一行內容
     * @throws IllegalArgumentException 格式不正確時拋出
     */
    public static void validateHeader(String headerLine) {
        if (headerLine == null) {
            throw new IllegalArgumentException("檔案為空，缺少 Header 行");
        }
        if (headerLine.length() != 10) {
            throw new IllegalArgumentException(
                    "Header 長度錯誤：預期 10 字元，實際 " + headerLine.length() + " 字元，內容: [" + headerLine + "]");
        }
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy/MM/dd");
        sdf.setLenient(false);
        try {
            sdf.parse(headerLine);
        } catch (ParseException e) {
            throw new IllegalArgumentException(
                    "Header 日期格式錯誤：預期 yyyy/MM/dd，實際: [" + headerLine + "]");
        }
        log.info("Header 檢核通過: {}", headerLine);
    }

    /**
     * 檢核 Trailer（最後一行）。
     * @param trailerLine 最後一行內容
     * @param actualRowCount 實際讀取的資料行數
     * @param actualCharSum 實際資料行的字元數總和
     * @throws IllegalArgumentException 檢核失敗時拋出
     */
    public static void validateTrailer(String trailerLine, long actualRowCount, long actualCharSum) {
        if (trailerLine == null || trailerLine.isEmpty()) {
            throw new IllegalArgumentException("缺少 Trailer 行");
        }

        String[] parts = trailerLine.split(",");
        if (parts.length != 2) {
            throw new IllegalArgumentException(
                    "Trailer 格式錯誤：預期 '筆數,字元數總和'，實際: [" + trailerLine + "]");
        }

        long expectedRowCount;
        long expectedCharSum;
        try {
            expectedRowCount = Long.parseLong(parts[0].trim());
            expectedCharSum = Long.parseLong(parts[1].trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "Trailer 數值解析失敗: [" + trailerLine + "]");
        }

        if (expectedRowCount != actualRowCount) {
            throw new IllegalArgumentException(
                    "Trailer 筆數檢核失敗：預期 " + expectedRowCount + "，實際 " + actualRowCount);
        }
        if (expectedCharSum != actualCharSum) {
            throw new IllegalArgumentException(
                    "Trailer 字元數檢核失敗：預期 " + expectedCharSum + "，實際 " + actualCharSum);
        }

        log.info("Trailer 檢核通過: 筆數={}, 字元數={}", actualRowCount, actualCharSum);
    }
}
