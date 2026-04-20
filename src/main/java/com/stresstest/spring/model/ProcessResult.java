package com.stresstest.spring.model;

/**
 * 策略處理結果。
 * 包含 FileMaster ID、成功/失敗寫入筆數。
 */
public class ProcessResult {

    private final long masterId;
    private final long successCount;
    private final long failCount;

    public ProcessResult(long masterId, long successCount, long failCount) {
        this.masterId = masterId;
        this.successCount = successCount;
        this.failCount = failCount;
    }

    public long getMasterId() { return masterId; }
    public long getSuccessCount() { return successCount; }
    public long getFailCount() { return failCount; }
    public long getTotalCount() { return successCount + failCount; }
}
