package com.stresstest.spring.model;

import com.stresstest.spring.entity.UploadCase;

/**
 * US Area 上傳情境的服務層結果。
 */
public class AreaUploadResult {

    private final String requestId;
    private final String area;
    private final String userId;
    private final String strategyName;
    private final long masterId;
    private final UploadCase uploadCase;
    private final long totalCount;
    private final long successCount;
    private final long failCount;
    private final long processMs;

    public AreaUploadResult(String requestId,
                            String area,
                            String userId,
                            String strategyName,
                            long masterId,
                            UploadCase uploadCase,
                            long totalCount,
                            long successCount,
                            long failCount,
                            long processMs) {
        this.requestId = requestId;
        this.area = area;
        this.userId = userId;
        this.strategyName = strategyName;
        this.masterId = masterId;
        this.uploadCase = uploadCase;
        this.totalCount = totalCount;
        this.successCount = successCount;
        this.failCount = failCount;
        this.processMs = processMs;
    }

    public String getRequestId() { return requestId; }
    public String getArea() { return area; }
    public String getUserId() { return userId; }
    public String getStrategyName() { return strategyName; }
    public long getMasterId() { return masterId; }
    public UploadCase getUploadCase() { return uploadCase; }
    public long getTotalCount() { return totalCount; }
    public long getSuccessCount() { return successCount; }
    public long getFailCount() { return failCount; }
    public long getProcessMs() { return processMs; }
}
