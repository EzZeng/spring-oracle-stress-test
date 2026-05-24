package com.stresstest.spring.service;

import com.stresstest.spring.entity.UploadCase;
import com.stresstest.spring.model.AreaUploadResult;
import com.stresstest.spring.model.ProcessResult;
import com.stresstest.spring.strategy.ProcessingStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.sql.DataSource;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;

/**
 * US 客戶跨區上傳情境。
 *
 * GLOBAL Area 使用 primary DataSource；US Area 使用另一個 XA DataSource。
 * upload、US result、GLOBAL operation log 由同一個 JTA transaction 管理。
 */
@Service
public class AreaUploadService {

    private static final Logger log = LoggerFactory.getLogger(AreaUploadService.class);

    private static final String AREA_US = "US";
    private static final String OPERATION_UPLOAD = "UPLOAD";
    private static final String STATUS_COMMIT = "COMMIT";

    @Autowired
    private DatabaseService databaseService;

    @Autowired
    private JpaDatabaseService jpaDatabaseService;

    @Autowired
    private CaseService caseService;

    @Autowired
    private List<ProcessingStrategy> strategies;

    @Autowired
    @Qualifier("dataSource")
    private DataSource globalAreaDataSource;

    @Autowired
    @Qualifier("usAreaDataSource")
    private DataSource usAreaDataSource;

    private volatile boolean schemaReady = false;

    @PostConstruct
    public void init() throws SQLException {
        ensureAreaSchema();
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void prepareForUpload(int strategyIndex, boolean skipInit) throws Exception {
        ProcessingStrategy strategy = resolveStrategy(strategyIndex);
        rejectUnsupportedStrategy(strategy);

        if (isJpaStrategy(strategy)) {
            if (!skipInit) {
                jpaDatabaseService.initSchema();
            }
        } else {
            databaseService.initMasterDetailSchema();
        }
    }

    @Transactional(timeout = 1800, rollbackFor = Exception.class)
    public AreaUploadResult uploadUs(Path filePath,
                                     String fileName,
                                     int strategyIndex,
                                     String bizType,
                                     String userId,
                                     String simulateFailure) throws Exception {
        ProcessingStrategy strategy = resolveStrategy(strategyIndex);
        rejectUnsupportedStrategy(strategy);
        String failureMode = normalizeFailureMode(simulateFailure);
        String requestId = UUID.randomUUID().toString();

        log.info("[US-Area] requestId={} userId={} strategy={} fileName={} 開始跨區上傳",
                requestId, userId, strategy.getName(), fileName);

        long processStart = System.nanoTime();
        ProcessResult processResult = strategy.process(filePath, databaseService, fileName);
        long processMs = (System.nanoTime() - processStart) / 1_000_000;

        UploadCase uploadCase = caseService.createCase(
                processResult.getMasterId(),
                fileName,
                isJpaStrategy(strategy) ? "JPA" : "TX",
                bizType,
                processResult.getTotalCount(),
                processResult.getSuccessCount(),
                processResult.getFailCount());

        writeUsUploadResult(requestId, userId, uploadCase, strategy.getName(), STATUS_COMMIT);
        if ("afterus".equals(failureMode)) {
            throw new IllegalStateException("simulateFailure=afterUs: US upload result 已寫入，強制觸發 XA rollback");
        }

        writeGlobalOperationLog(requestId, userId, uploadCase, strategy.getName(), STATUS_COMMIT);
        if ("afterglobal".equals(failureMode)) {
            throw new IllegalStateException("simulateFailure=afterGlobal: GLOBAL operation log 已寫入，強制觸發 XA rollback");
        }

        log.info("[US-Area] requestId={} caseId={} masterId={} XA transaction ready to commit",
                requestId, uploadCase.getId(), processResult.getMasterId());

        return new AreaUploadResult(
                requestId,
                AREA_US,
                userId,
                strategy.getName(),
                processResult.getMasterId(),
                uploadCase,
                processResult.getTotalCount(),
                processResult.getSuccessCount(),
                processResult.getFailCount(),
                processMs);
    }

    private synchronized void ensureAreaSchema() throws SQLException {
        if (schemaReady) {
            return;
        }

        try (Connection conn = globalAreaDataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            execIgnore(stmt,
                    "CREATE TABLE global_user_operation_log (" +
                            "id NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY, " +
                            "request_id VARCHAR2(64) NOT NULL, " +
                            "user_id VARCHAR2(100) NOT NULL, " +
                            "client_area VARCHAR2(20) NOT NULL, " +
                            "operation VARCHAR2(50) NOT NULL, " +
                            "case_id NUMBER, " +
                            "master_id NUMBER, " +
                            "file_name VARCHAR2(255), " +
                            "strategy_name VARCHAR2(200), " +
                            "biz_type VARCHAR2(10), " +
                            "result_status VARCHAR2(20), " +
                            "total_count NUMBER DEFAULT 0, " +
                            "success_count NUMBER DEFAULT 0, " +
                            "fail_count NUMBER DEFAULT 0, " +
                            "message VARCHAR2(500), " +
                            "created_at TIMESTAMP DEFAULT SYSTIMESTAMP" +
                            ")",
                    955);
        }

        try (Connection conn = usAreaDataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            execIgnore(stmt,
                    "CREATE TABLE us_upload_result (" +
                            "id NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY, " +
                            "request_id VARCHAR2(64) NOT NULL, " +
                            "user_id VARCHAR2(100) NOT NULL, " +
                            "area VARCHAR2(20) DEFAULT 'US' NOT NULL, " +
                            "case_id NUMBER NOT NULL, " +
                            "master_id NUMBER NOT NULL, " +
                            "file_name VARCHAR2(255), " +
                            "strategy_name VARCHAR2(200), " +
                            "biz_type VARCHAR2(10), " +
                            "upload_status VARCHAR2(20) NOT NULL, " +
                            "total_count NUMBER DEFAULT 0, " +
                            "success_count NUMBER DEFAULT 0, " +
                            "fail_count NUMBER DEFAULT 0, " +
                            "created_at TIMESTAMP DEFAULT SYSTIMESTAMP" +
                            ")",
                    955);
        }

        schemaReady = true;
        log.info("area upload schema ready: global_user_operation_log / us_upload_result");
    }

    private void writeUsUploadResult(String requestId,
                                     String userId,
                                     UploadCase uploadCase,
                                     String strategyName,
                                     String uploadStatus) throws SQLException {
        String sql = "INSERT INTO us_upload_result " +
                "(request_id, user_id, area, case_id, master_id, file_name, strategy_name, biz_type, " +
                "upload_status, total_count, success_count, fail_count) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection conn = usAreaDataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, requestId);
            ps.setString(2, userId);
            ps.setString(3, AREA_US);
            ps.setLong(4, uploadCase.getId());
            ps.setLong(5, uploadCase.getMasterId());
            ps.setString(6, uploadCase.getFileName());
            ps.setString(7, strategyName);
            ps.setString(8, uploadCase.getBizType());
            ps.setString(9, uploadStatus);
            ps.setLong(10, nvl(uploadCase.getTotalCount()));
            ps.setLong(11, nvl(uploadCase.getSuccessCount()));
            ps.setLong(12, nvl(uploadCase.getFailCount()));
            ps.executeUpdate();
        }
    }

    private void writeGlobalOperationLog(String requestId,
                                         String userId,
                                         UploadCase uploadCase,
                                         String strategyName,
                                         String resultStatus) throws SQLException {
        String sql = "INSERT INTO global_user_operation_log " +
                "(request_id, user_id, client_area, operation, case_id, master_id, file_name, strategy_name, " +
                "biz_type, result_status, total_count, success_count, fail_count, message, created_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection conn = globalAreaDataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, requestId);
            ps.setString(2, userId);
            ps.setString(3, AREA_US);
            ps.setString(4, OPERATION_UPLOAD);
            ps.setLong(5, uploadCase.getId());
            ps.setLong(6, uploadCase.getMasterId());
            ps.setString(7, uploadCase.getFileName());
            ps.setString(8, strategyName);
            ps.setString(9, uploadCase.getBizType());
            ps.setString(10, resultStatus);
            ps.setLong(11, nvl(uploadCase.getTotalCount()));
            ps.setLong(12, nvl(uploadCase.getSuccessCount()));
            ps.setLong(13, nvl(uploadCase.getFailCount()));
            ps.setString(14, "US customer upload recorded by GLOBAL Area");
            ps.setTimestamp(15, new Timestamp(System.currentTimeMillis()));
            ps.executeUpdate();
        }
    }

    private ProcessingStrategy resolveStrategy(int strategyIndex) {
        if (strategyIndex < 1 || strategyIndex > strategies.size()) {
            throw new IllegalArgumentException("策略編號無效。可用: 1~" + strategies.size());
        }
        return strategies.get(strategyIndex - 1);
    }

    private void rejectUnsupportedStrategy(ProcessingStrategy strategy) {
        if (strategy.getName().contains("ThreadPool")) {
            throw new IllegalArgumentException("US Area XA 上傳不支援策略「" + strategy.getName()
                    + "」：此策略使用 worker-local transaction，無法保證單一 JTA/XA transaction");
        }
    }

    private boolean isJpaStrategy(ProcessingStrategy strategy) {
        return strategy.getName().startsWith("[JPA]");
    }

    private String normalizeFailureMode(String simulateFailure) {
        String mode = simulateFailure == null || simulateFailure.trim().isEmpty()
                ? "none"
                : simulateFailure.trim().toLowerCase();
        if (!"none".equals(mode) && !"afterus".equals(mode) && !"afterglobal".equals(mode)) {
            throw new IllegalArgumentException("不支援的 simulateFailure: " + simulateFailure
                    + "，可用 none|afterUs|afterGlobal");
        }
        return mode;
    }

    private long nvl(Long value) {
        return value == null ? 0L : value;
    }

    private void execIgnore(Statement stmt, String sql, int ignoreCode) throws SQLException {
        try {
            stmt.execute(sql);
        } catch (SQLException e) {
            if (e.getErrorCode() != ignoreCode) {
                throw e;
            }
        }
    }
}
