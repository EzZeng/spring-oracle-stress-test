package com.stresstest.spring.service;

import com.stresstest.spring.dao.CaseRepository;
import com.stresstest.spring.entity.UploadCase;
import com.stresstest.spring.model.DomainRecordA;
import com.stresstest.spring.model.DomainRecordB;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.sql.DataSource;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * 案件管理服務。
 * <p>
 * 功能：
 *   1. 上傳完成後建立案件（狀態 = PENDING）
 *   2. 提供待辦清單查詢（所有 PENDING 案件）
 *   3. 放行（APPROVE）後，將上傳檔案資料寫入兩個不同網域的資料庫
 *   4. 駁回（REJECT）案件
 * <p>
 * 兩個網域資料庫（Domain-A / Domain-B）透過獨立的 HikariCP 連線池管理，
 * 延遲初始化（第一次放行時才建立連線），避免影響原有壓力測試功能。
 */
@Service
public class CaseService {

    private static final Logger log = LoggerFactory.getLogger(CaseService.class);

    @Autowired
    private DataSource primaryDataSource;

    @Autowired
    @Qualifier("domainADataSource")
    private DataSource domainADataSource;

    @Autowired
    @Qualifier("domainBDataSource")
    private DataSource domainBDataSource;

    @Autowired
    private CaseRepository caseRepository;

    @PostConstruct
    public void init() throws SQLException {
        initCaseSchema();
        initDomainSchema(domainADataSource, "Domain-A");
        initDomainSchema(domainBDataSource, "Domain-B");
    }

    /**
     * 在主資料庫建立 upload_case 表、序列，以及三個業務目標表（冪等）。
     */
    private void initCaseSchema() throws SQLException {
        try (Connection conn = primaryDataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            try {
                stmt.execute("CREATE SEQUENCE upload_case_seq START WITH 1 INCREMENT BY 1 NOCACHE");
            } catch (SQLException e) {
                if (e.getErrorCode() != 955) throw e;
            }
            try {
                stmt.execute("CREATE TABLE upload_case (" +
                        "id NUMBER DEFAULT upload_case_seq.NEXTVAL PRIMARY KEY, " +
                        "master_id NUMBER NOT NULL, " +
                        "file_name VARCHAR2(255), " +
                        "strategy_type VARCHAR2(10), " +
                        "biz_type VARCHAR2(10), " +
                        "status VARCHAR2(20) DEFAULT 'PENDING', " +
                        "total_count NUMBER DEFAULT 0, " +
                        "success_count NUMBER DEFAULT 0, " +
                        "fail_count NUMBER DEFAULT 0, " +
                        "create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                        "approve_time TIMESTAMP" +
                        ")");
            } catch (SQLException e) {
                if (e.getErrorCode() != 955) throw e;
                // 表已存在時補上 biz_type 欄位（冪等）
                try {
                    stmt.execute("ALTER TABLE upload_case ADD (biz_type VARCHAR2(10))");
                } catch (SQLException e2) {
                    if (e2.getErrorCode() != 1430) throw e2; // ORA-01430: column already exists
                }
            }
            // 三個業務目標表
            String bizDetailDdl =
                    "id NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY, " +
                    "case_id NUMBER NOT NULL, " +
                    "master_id NUMBER NOT NULL, " +
                    "field_a VARCHAR2(10), field_b VARCHAR2(10), field_c VARCHAR2(10), " +
                    "field_d VARCHAR2(10), field_e VARCHAR2(10), field_f VARCHAR2(10), " +
                    "field_g VARCHAR2(10), field_h VARCHAR2(10), field_i VARCHAR2(10), " +
                    "field_j VARCHAR2(10), field_k VARCHAR2(10), field_l VARCHAR2(10), " +
                    "approve_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP";
            for (String tbl : new String[]{"biz_a_detail", "biz_b_detail", "biz_c_detail"}) {
                try {
                    stmt.execute("CREATE TABLE " + tbl + " (" + bizDetailDdl + ")");
                } catch (SQLException e) {
                    if (e.getErrorCode() != 955) throw e;
                }
            }
            // 原檔下載快取表
            try {
                stmt.execute("CREATE TABLE download_file (" +
                        "id NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY, " +
                        "case_id NUMBER NOT NULL, " +
                        "biz_type VARCHAR2(10), " +
                        "file_name VARCHAR2(255), " +
                        "file_content BLOB, " +
                        "row_count NUMBER DEFAULT 0, " +
                        "create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                        ")");
            } catch (SQLException e) {
                if (e.getErrorCode() != 955) throw e;
            }
            log.info("案件 Schema 初始化完成（upload_case + biz_a/b/c_detail + download_file）");
        }
    }

    /**
     * 在網域資料庫建立 approved_file_detail 表（冪等）。
     * 兩個網域的 table / 欄位結構完全相同。
     */
    private void initDomainSchema(DataSource ds, String label) throws SQLException {
        try (Connection conn = ds.getConnection();
             Statement stmt = conn.createStatement()) {
            try {
                stmt.execute("CREATE TABLE approved_file_detail (" +
                        "id NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY, " +
                        "case_id NUMBER NOT NULL, " +
                        "master_id NUMBER NOT NULL, " +
                        "field_a VARCHAR2(10), " +
                        "field_b VARCHAR2(10), " +
                        "field_c VARCHAR2(10), " +
                        "field_d VARCHAR2(10), " +
                        "field_e VARCHAR2(10), " +
                        "field_f VARCHAR2(10), " +
                        "field_g VARCHAR2(10), " +
                        "field_h VARCHAR2(10), " +
                        "field_i VARCHAR2(10), " +
                        "field_j VARCHAR2(10), " +
                        "field_k VARCHAR2(10), " +
                        "field_l VARCHAR2(10), " +
                        "approve_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                        ")");
            } catch (SQLException e) {
                if (e.getErrorCode() != 955) throw e;
            }
            log.info("[{}] approved_file_detail 表初始化完成", label);
        }
    }

    /**
     * 上傳完成後建立案件（待辦事項）。
     */
    @Transactional
    public UploadCase createCase(long masterId, String fileName, String strategyType, String bizType,
                                 long totalCount, long successCount, long failCount) {
        UploadCase uploadCase = new UploadCase();
        uploadCase.setMasterId(masterId);
        uploadCase.setFileName(fileName);
        uploadCase.setStrategyType(strategyType);
        uploadCase.setBizType(bizType);
        uploadCase.setStatus("PENDING");
        uploadCase.setTotalCount(totalCount);
        uploadCase.setSuccessCount(successCount);
        uploadCase.setFailCount(failCount);
        uploadCase.setCreateTime(new Timestamp(System.currentTimeMillis()));
        UploadCase saved = caseRepository.save(uploadCase);
        log.info("案件已建立 - caseId={}, masterId={}, bizType={}, fileName={}", saved.getId(), masterId, bizType, fileName);
        return saved;
    }

    /**
     * 查詢所有待辦案件（PENDING）。
     */
    public List<UploadCase> getPendingCases() {
        return caseRepository.findByStatusOrderByCreateTimeDesc("PENDING");
    }

    /**
     * 查詢所有案件。
     */
    public List<UploadCase> getAllCases() {
        return caseRepository.findAll();
    }

    /**
     * 放行案件。
     * <p>
     * 若案件有指定 bizType（BIZ_A / BIZ_B / BIZ_C），則從主 DB 讀取暫存資料後
     * 寫入對應的業務目標表（biz_a_detail / biz_b_detail / biz_c_detail）。
     * 未指定 bizType 時維持原有行為：同時寫入兩個網域資料庫的 approved_file_detail。
     */
    @Transactional
    public UploadCase approveCase(Long caseId) throws SQLException {
        // SELECT ... FOR UPDATE：同一案件只有一個 thread 能取得鎖，避免重複放行
        UploadCase uploadCase = caseRepository.findByIdForUpdate(caseId)
                .orElseThrow(() -> new IllegalArgumentException("案件不存在: " + caseId));

        if (!"PENDING".equals(uploadCase.getStatus())) {
            throw new IllegalStateException("案件狀態非 PENDING，無法放行: " + uploadCase.getStatus());
        }

        // 根據策略類型決定暫存來源表
        String sourceTable = "JPA".equals(uploadCase.getStrategyType()) ? "jpa_records" : "file_detail";
        log.info("放行案件 {} (bizType={}) - 從 {} 讀取資料（master_id={}）",
                caseId, uploadCase.getBizType(), sourceTable, uploadCase.getMasterId());

        String bizType = uploadCase.getBizType();
        if (bizType != null && !bizType.isEmpty()) {
            // ===== 業務路由模式：根據 bizType 寫入對應目標表 =====
            String bizTable = resolveBizTable(bizType);
            List<DomainRecordA> records = new ArrayList<>();
            loadSourceDataSingle(uploadCase, sourceTable, records);
            log.info("案件 {} - 已載入 {} 筆到記憶體（bizType={}）", caseId, records.size(), bizType);
            long count = writeToBizTable(primaryDataSource, bizTable, records);

            uploadCase.setStatus("APPROVED");
            uploadCase.setApproveTime(new Timestamp(System.currentTimeMillis()));
            caseRepository.save(uploadCase);
            log.info("案件 {} 放行完成 - {}: {} 筆", caseId, bizTable, count);
        } else {
            // ===== 原有模式：同時寫入 Domain-A / Domain-B 的 approved_file_detail =====
            List<DomainRecordA> recordsForA = new ArrayList<>();
            List<DomainRecordB> recordsForB = new ArrayList<>();
            loadSourceData(uploadCase, sourceTable, recordsForA, recordsForB);
            log.info("案件 {} - 已載入 {} 筆到記憶體（DomainRecordA={}, DomainRecordB={}）",
                    caseId, recordsForA.size(), recordsForA.size(), recordsForB.size());
            long countA = writeToDomainA(domainADataSource, "Domain-A", recordsForA);
            long countB = writeToDomainB(domainBDataSource, "Domain-B", recordsForB);

            uploadCase.setStatus("APPROVED");
            uploadCase.setApproveTime(new Timestamp(System.currentTimeMillis()));
            caseRepository.save(uploadCase);
            log.info("案件 {} 放行完成 - Domain-A: {} 筆, Domain-B: {} 筆", caseId, countA, countB);
        }
        return uploadCase;
    }

    /**
     * 將 bizType 字串（BIZ_A / BIZ_B / BIZ_C）對應到目標表名稱。
     */
    private String resolveBizTable(String bizType) {
        switch (bizType.toUpperCase()) {
            case "BIZ_A": return "biz_a_detail";
            case "BIZ_B": return "biz_b_detail";
            case "BIZ_C": return "biz_c_detail";
            default: throw new IllegalArgumentException("不支援的 bizType: " + bizType);
        }
    }

    /**
     * 駁回案件。
     */
    @Transactional
    public UploadCase rejectCase(Long caseId) {
        UploadCase uploadCase = caseRepository.findById(caseId)
                .orElseThrow(() -> new IllegalArgumentException("案件不存在: " + caseId));

        if (!"PENDING".equals(uploadCase.getStatus())) {
            throw new IllegalStateException("案件狀態非 PENDING，無法駁回: " + uploadCase.getStatus());
        }

        uploadCase.setStatus("REJECTED");
        uploadCase.setApproveTime(new Timestamp(System.currentTimeMillis()));
        UploadCase saved = caseRepository.save(uploadCase);
        log.info("案件 {} 已駁回", caseId);
        return saved;
    }

    /**
     * 從主資料庫讀取全部檔案明細到記憶體，同時填充 DomainRecordA 與 DomainRecordB 列表。
     */
    private void loadSourceData(UploadCase uploadCase, String sourceTable,
                                List<DomainRecordA> recordsForA,
                                List<DomainRecordB> recordsForB) throws SQLException {
        String selectSql = "SELECT field_a, field_b, field_c, field_d, field_e, field_f, " +
                "field_g, field_h, field_i, field_j, field_k, field_l FROM " + sourceTable +
                " WHERE master_id = ?";

        try (Connection readConn = primaryDataSource.getConnection();
             PreparedStatement readPs = readConn.prepareStatement(selectSql)) {
            readPs.setLong(1, uploadCase.getMasterId());
            readPs.setFetchSize(10000);
            try (ResultSet rs = readPs.executeQuery()) {
                long count = 0;
                while (rs.next()) {
                    String a = rs.getString(1), b = rs.getString(2), c = rs.getString(3);
                    String d = rs.getString(4), e = rs.getString(5), f = rs.getString(6);
                    String g = rs.getString(7), h = rs.getString(8), i = rs.getString(9);
                    String j = rs.getString(10), k = rs.getString(11), l = rs.getString(12);

                    recordsForA.add(new DomainRecordA(
                            uploadCase.getId(), uploadCase.getMasterId(),
                            a, b, c, d, e, f, g, h, i, j, k, l));
                    recordsForB.add(new DomainRecordB(
                            uploadCase.getId(), uploadCase.getMasterId(),
                            a, b, c, d, e, f, g, h, i, j, k, l));

                    count++;
                    if (count % 200000 == 0) {
                        log.info("[載入] 已讀取 {} 筆到記憶體", count);
                    }
                }
            }
        }
    }

    /**
     * 將 List&lt;DomainRecordA&gt; 批次寫入 Domain-A 資料庫。
     */
    private long writeToDomainA(DataSource domainDs, String label,
                                List<DomainRecordA> records) throws SQLException {
        String insertSql = "INSERT INTO approved_file_detail " +
                "(case_id, master_id, field_a, field_b, field_c, field_d, field_e, field_f, " +
                "field_g, field_h, field_i, field_j, field_k, field_l) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

        long count = 0;
        try (Connection writeConn = domainDs.getConnection();
             PreparedStatement writePs = writeConn.prepareStatement(insertSql)) {

            for (DomainRecordA rec : records) {
                writePs.setLong(1, rec.getCaseId());
                writePs.setLong(2, rec.getMasterId());
                writePs.setString(3, rec.getFieldA());
                writePs.setString(4, rec.getFieldB());
                writePs.setString(5, rec.getFieldC());
                writePs.setString(6, rec.getFieldD());
                writePs.setString(7, rec.getFieldE());
                writePs.setString(8, rec.getFieldF());
                writePs.setString(9, rec.getFieldG());
                writePs.setString(10, rec.getFieldH());
                writePs.setString(11, rec.getFieldI());
                writePs.setString(12, rec.getFieldJ());
                writePs.setString(13, rec.getFieldK());
                writePs.setString(14, rec.getFieldL());
                writePs.addBatch();
                count++;

                if (count % 5000 == 0) {
                    writePs.executeBatch();
                    writePs.clearBatch();
                    log.info("[{}] 已寫入 {} 筆", label, count);
                }
            }

            if (count % 5000 != 0) {
                writePs.executeBatch();
            }
            // XA 交易由 JTA (@Transactional) 統一 commit，不呼叫 conn.commit()
        }

        log.info("[{}] 寫入完成，共 {} 筆", label, count);
        return count;
    }

    /**
     * 將 List&lt;DomainRecordB&gt; 批次寫入 Domain-B 資料庫。
     */
    private long writeToDomainB(DataSource domainDs, String label,
                                List<DomainRecordB> records) throws SQLException {
        String insertSql = "INSERT INTO approved_file_detail " +
                "(case_id, master_id, field_a, field_b, field_c, field_d, field_e, field_f, " +
                "field_g, field_h, field_i, field_j, field_k, field_l) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

        long count = 0;
        try (Connection writeConn = domainDs.getConnection();
             PreparedStatement writePs = writeConn.prepareStatement(insertSql)) {

            for (DomainRecordB rec : records) {
                writePs.setLong(1, rec.getCaseId());
                writePs.setLong(2, rec.getMasterId());
                writePs.setString(3, rec.getFieldA());
                writePs.setString(4, rec.getFieldB());
                writePs.setString(5, rec.getFieldC());
                writePs.setString(6, rec.getFieldD());
                writePs.setString(7, rec.getFieldE());
                writePs.setString(8, rec.getFieldF());
                writePs.setString(9, rec.getFieldG());
                writePs.setString(10, rec.getFieldH());
                writePs.setString(11, rec.getFieldI());
                writePs.setString(12, rec.getFieldJ());
                writePs.setString(13, rec.getFieldK());
                writePs.setString(14, rec.getFieldL());
                writePs.addBatch();
                count++;

                if (count % 5000 == 0) {
                    writePs.executeBatch();
                    writePs.clearBatch();
                    log.info("[{}] 已寫入 {} 筆", label, count);
                }
            }

            if (count % 5000 != 0) {
                writePs.executeBatch();
            }
            // XA 交易由 JTA (@Transactional) 統一 commit，不呼叫 writeConn.commit()
        }

        log.info("[{}] 寫入完成，共 {} 筆", label, count);
        return count;
    }

    /**
     * 從主資料庫讀取全部暫存明細到單一 List（業務路由模式使用）。
     */
    private void loadSourceDataSingle(UploadCase uploadCase, String sourceTable,
                                      List<DomainRecordA> records) throws SQLException {
        String selectSql = "SELECT field_a, field_b, field_c, field_d, field_e, field_f, " +
                "field_g, field_h, field_i, field_j, field_k, field_l FROM " + sourceTable +
                " WHERE master_id = ?";
        try (Connection conn = primaryDataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(selectSql)) {
            ps.setLong(1, uploadCase.getMasterId());
            ps.setFetchSize(10000);
            try (ResultSet rs = ps.executeQuery()) {
                long count = 0;
                while (rs.next()) {
                    records.add(new DomainRecordA(
                            uploadCase.getId(), uploadCase.getMasterId(),
                            rs.getString(1), rs.getString(2), rs.getString(3),
                            rs.getString(4), rs.getString(5), rs.getString(6),
                            rs.getString(7), rs.getString(8), rs.getString(9),
                            rs.getString(10), rs.getString(11), rs.getString(12)));
                    count++;
                    if (count % 200000 == 0) {
                        log.info("[載入] 已讀取 {} 筆到記憶體", count);
                    }
                }
            }
        }
    }

    /**
     * 批次寫入業務目標表（biz_a_detail / biz_b_detail / biz_c_detail）。
     */
    private long writeToBizTable(DataSource ds, String bizTable,
                                 List<DomainRecordA> records) throws SQLException {
        String insertSql = "INSERT INTO " + bizTable +
                " (case_id, master_id, field_a, field_b, field_c, field_d, field_e, field_f, " +
                "field_g, field_h, field_i, field_j, field_k, field_l) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        long count = 0;
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(insertSql)) {
            for (DomainRecordA rec : records) {
                ps.setLong(1, rec.getCaseId());
                ps.setLong(2, rec.getMasterId());
                ps.setString(3, rec.getFieldA());
                ps.setString(4, rec.getFieldB());
                ps.setString(5, rec.getFieldC());
                ps.setString(6, rec.getFieldD());
                ps.setString(7, rec.getFieldE());
                ps.setString(8, rec.getFieldF());
                ps.setString(9, rec.getFieldG());
                ps.setString(10, rec.getFieldH());
                ps.setString(11, rec.getFieldI());
                ps.setString(12, rec.getFieldJ());
                ps.setString(13, rec.getFieldK());
                ps.setString(14, rec.getFieldL());
                ps.addBatch();
                count++;
                if (count % 5000 == 0) {
                    ps.executeBatch();
                    ps.clearBatch();
                    log.info("[{}] 已寫入 {} 筆", bizTable, count);
                }
            }
            if (count % 5000 != 0) {
                ps.executeBatch();
            }
            // XA 交易由 JTA (@Transactional) 統一 commit，不呼叫 conn.commit()
        }
        log.info("[{}] 寫入完成，共 {} 筆", bizTable, count);
        return count;
    }

    /**
     * 從業務目標表讀取已放行資料，組成 pipe-separated TXT，
     * 以 BLOB 儲存至 download_file 表，同時回傳檔案內容 bytes。
     * <p>
     * @Transactional 確保讀取 biz_x_detail 與寫入 download_file 在同一 XA 交易中。
     */
    @Transactional
    public byte[] generateDownloadFile(Long caseId) throws SQLException {
        UploadCase uploadCase = caseRepository.findById(caseId)
                .orElseThrow(() -> new IllegalArgumentException("案件不存在: " + caseId));
        if (!"APPROVED".equals(uploadCase.getStatus())) {
            throw new IllegalStateException("案件非 APPROVED 狀態，無法下載: " + uploadCase.getStatus());
        }
        String bizType = uploadCase.getBizType();
        if (bizType == null || bizType.isEmpty()) {
            throw new IllegalStateException("此案件無 bizType，不支援原檔下載");
        }
        String bizTable = resolveBizTable(bizType);
        log.info("[下載] 案件 {} (bizType={}) - 從 {} 讀取資料", caseId, bizType, bizTable);

        // 約估 160 MB 初始容量（120 萬筆 × ~133 bytes/row）
        ByteArrayOutputStream baos = new ByteArrayOutputStream(160 * 1024 * 1024);
        byte[] header = "field_a|field_b|field_c|field_d|field_e|field_f|field_g|field_h|field_i|field_j|field_k|field_l\n"
                .getBytes(StandardCharsets.UTF_8);
        baos.write(header, 0, header.length);

        long rowCount = 0;
        String selectSql = "SELECT field_a, field_b, field_c, field_d, field_e, field_f, " +
                "field_g, field_h, field_i, field_j, field_k, field_l FROM " + bizTable +
                " WHERE case_id = ?";
        StringBuilder rowBuf = new StringBuilder(200);
        try (Connection conn = primaryDataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(selectSql)) {
            ps.setLong(1, caseId);
            ps.setFetchSize(10000);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rowBuf.setLength(0);
                    rowBuf.append(rs.getString(1)).append('|')
                          .append(rs.getString(2)).append('|')
                          .append(rs.getString(3)).append('|')
                          .append(rs.getString(4)).append('|')
                          .append(rs.getString(5)).append('|')
                          .append(rs.getString(6)).append('|')
                          .append(rs.getString(7)).append('|')
                          .append(rs.getString(8)).append('|')
                          .append(rs.getString(9)).append('|')
                          .append(rs.getString(10)).append('|')
                          .append(rs.getString(11)).append('|')
                          .append(rs.getString(12)).append('\n');
                    byte[] row = rowBuf.toString().getBytes(StandardCharsets.UTF_8);
                    baos.write(row, 0, row.length);
                    rowCount++;
                    if (rowCount % 200000 == 0) {
                        log.info("[下載] 案件 {} 已讀取 {} 筆", caseId, rowCount);
                    }
                }
            }
        }

        byte[] fileBytes = baos.toByteArray();
        log.info("[下載] 案件 {} - TXT 共 {} 筆，{} bytes，開始儲存 BLOB", caseId, rowCount, fileBytes.length);

        String fileName = (uploadCase.getFileName() != null ? uploadCase.getFileName() : "case_" + caseId)
                + "_download.txt";
        saveDownloadBlob(caseId, bizType, fileName, fileBytes, rowCount);

        return fileBytes;
    }

    /**
     * 將 TXT bytes 以 BLOB 寫入 download_file 表。
     */
    private void saveDownloadBlob(Long caseId, String bizType, String fileName,
                                  byte[] content, long rowCount) throws SQLException {
        String insertSql = "INSERT INTO download_file (case_id, biz_type, file_name, file_content, row_count) " +
                "VALUES (?, ?, ?, ?, ?)";
        try (Connection conn = primaryDataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(insertSql)) {
            ps.setLong(1, caseId);
            ps.setString(2, bizType);
            ps.setString(3, fileName);
            ps.setBinaryStream(4, new ByteArrayInputStream(content), (long) content.length);
            ps.setLong(5, rowCount);
            ps.executeUpdate();
            // XA 交易由 JTA (@Transactional) 統一 commit，不呼叫 conn.commit()
            log.info("[下載] 案件 {} - BLOB 已儲存至 download_file ({} bytes)", caseId, content.length);
        }
    }
}
