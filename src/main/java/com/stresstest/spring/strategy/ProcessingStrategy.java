package com.stresstest.spring.strategy;

import com.stresstest.spring.model.ProcessResult;
import com.stresstest.spring.service.DatabaseService;

import java.nio.file.Path;

/**
 * 處理策略介面。
 * 所有策略使用單一交易（Oracle 語意）：成功 COMMIT，失敗 ROLLBACK。
 *
 * 寫入流程：
 *   1. 建立 FILE_MASTER（案件）
 *   2. 解析檔案行資料 → 寫入 FILE_DETAIL（FK = FILE_MASTER.ID）
 *   3. 更新 FILE_MASTER summary（成功/失敗筆數）
 *   4. COMMIT
 */
public interface ProcessingStrategy {
    String getName();
    ProcessResult process(Path filePath, DatabaseService db, String fileName) throws Exception;
}
