package com.stresstest.spring.service;

import com.stresstest.spring.model.ProcessResult;
import com.stresstest.spring.strategy.ProcessingStrategy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;

/**
 * TX 策略的 JTA 交易包裝服務。
 *
 * TX 策略使用原始 JDBC 管理資料寫入，但在 Atomikos XA 架構下不允許
 * 直接呼叫 conn.setAutoCommit(false) / conn.commit() / conn.rollback()。
 * 改由此服務透過 @Transactional 啟動 JTA 交易，讓 Spring AOP 在
 * execute() 正常返回時 commit、拋出例外時 rollback。
 */
@Service
public class TxStrategyExecutionService {

    @Transactional
    public ProcessResult execute(ProcessingStrategy strategy,
                                 Path filePath,
                                 DatabaseService db,
                                 String fileName) throws Exception {
        return strategy.process(filePath, db, fileName);
    }
}
