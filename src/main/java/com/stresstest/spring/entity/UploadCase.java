package com.stresstest.spring.entity;

import javax.persistence.*;
import java.sql.Timestamp;

/**
 * 案件 Entity：對應 Oracle 資料表 upload_case。
 * 上傳完成後自動建立，狀態為 PENDING（待辦）。
 * 放行後狀態改為 APPROVED，駁回則為 REJECTED。
 */
@Entity
@Table(name = "upload_case")
public class UploadCase {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "upload_case_gen")
    @SequenceGenerator(name = "upload_case_gen", sequenceName = "upload_case_seq", allocationSize = 1)
    private Long id;

    @Column(name = "master_id", nullable = false)
    private Long masterId;

    @Column(name = "file_name", length = 255)
    private String fileName;

    @Column(name = "strategy_type", length = 10)
    private String strategyType;

    @Column(name = "biz_type", length = 10)
    private String bizType;

    @Column(name = "status", length = 20, nullable = false)
    private String status;

    @Column(name = "total_count")
    private Long totalCount;

    @Column(name = "success_count")
    private Long successCount;

    @Column(name = "fail_count")
    private Long failCount;

    @Column(name = "create_time")
    private Timestamp createTime;

    @Column(name = "approve_time")
    private Timestamp approveTime;

    public UploadCase() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getMasterId() { return masterId; }
    public void setMasterId(Long masterId) { this.masterId = masterId; }
    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }
    public String getStrategyType() { return strategyType; }
    public void setStrategyType(String strategyType) { this.strategyType = strategyType; }
    public String getBizType() { return bizType; }
    public void setBizType(String bizType) { this.bizType = bizType; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Long getTotalCount() { return totalCount; }
    public void setTotalCount(Long totalCount) { this.totalCount = totalCount; }
    public Long getSuccessCount() { return successCount; }
    public void setSuccessCount(Long successCount) { this.successCount = successCount; }
    public Long getFailCount() { return failCount; }
    public void setFailCount(Long failCount) { this.failCount = failCount; }
    public Timestamp getCreateTime() { return createTime; }
    public void setCreateTime(Timestamp createTime) { this.createTime = createTime; }
    public Timestamp getApproveTime() { return approveTime; }
    public void setApproveTime(Timestamp approveTime) { this.approveTime = approveTime; }
}
