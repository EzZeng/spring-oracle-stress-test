package com.stresstest.spring.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import com.stresstest.spring.model.RowRecord;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Oracle 資料庫操作服務。
 * 使用 Spring 管理的 DataSource（Atomikos XA）。
 *
 * 資料模型：
 *   FILE_MASTER —— 每次上傳檔案 = 一個案件
 *   FILE_DETAIL —— 解析出的行資料，FK 為 FILE_MASTER.ID
 */
@Service
public class DatabaseService {

    private static final Logger log = LoggerFactory.getLogger(DatabaseService.class);

    /** 確保 initMasterDetailSchema() 只執行一次（並行請求防護） */
    private volatile boolean masterDetailInitialized = false;

    private static final String DETAIL_INSERT_SQL =
            "INSERT INTO file_detail (master_id, field_a, field_b, field_c, field_d, field_e, field_f, " +
            "field_g, field_h, field_i, field_j, field_k, field_l) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    @Autowired
    private DataSource dataSource;

    /**
     * 初始化 Master-Detail Schema（冪等：只建立不存在的物件）。
     * NOT_SUPPORTED：DDL 在 Oracle 會隱式 commit，必須在 JTA 交易外執行，
     * 避免影響進行中的 XA 交易。
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public synchronized void initMasterDetailSchema() throws SQLException {
        if (masterDetailInitialized) return;
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            // file_master_seq
            try {
                stmt.execute("CREATE SEQUENCE file_master_seq START WITH 1 INCREMENT BY 1 NOCACHE");
            } catch (SQLException e) {
                if (e.getErrorCode() != 955) throw e; // ORA-00955: name already used
            }
            // file_master
            try {
                stmt.execute("CREATE TABLE file_master (" +
                        "id NUMBER DEFAULT file_master_seq.NEXTVAL PRIMARY KEY, " +
                        "file_name VARCHAR2(255), " +
                        "upload_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                        "status VARCHAR2(20) DEFAULT 'PROCESSING', " +
                        "total_count NUMBER DEFAULT 0, " +
                        "success_count NUMBER DEFAULT 0, " +
                        "fail_count NUMBER DEFAULT 0" +
                        ")");
            } catch (SQLException e) {
                if (e.getErrorCode() != 955) throw e;
            }
            // file_detail
            try {
                stmt.execute("CREATE TABLE file_detail (" +
                        "id NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY, " +
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
                        "CONSTRAINT fk_detail_master FOREIGN KEY (master_id) REFERENCES file_master(id)" +
                        ")");
            } catch (SQLException e) {
                if (e.getErrorCode() != 955) throw e;
            }
            log.info("Master-Detail Schema 初始化完成（file_master + file_detail）");
        }
        masterDetailInitialized = true;
    }

    public Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    /**
     * 新增一筆 FILE_MASTER（案件），回傳自動產生的 ID。
     * 必須在策略的 Connection 上執行（同一交易）。
     */
    public static long insertMaster(Connection conn, String fileName) throws SQLException {
        PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO file_master (file_name) VALUES (?)",
                new String[]{"ID"});
        ps.setString(1, fileName);
        ps.executeUpdate();
        ResultSet rs = ps.getGeneratedKeys();
        rs.next();
        long id = rs.getLong(1);
        rs.close();
        ps.close();
        return id;
    }

    /**
     * 準備 FILE_DETAIL INSERT（含 master_id）。
     */
    public static PreparedStatement prepareDetailInsert(Connection conn) throws SQLException {
        return conn.prepareStatement(DETAIL_INSERT_SQL);
    }

    /**
     * 設定 FILE_DETAIL INSERT 參數並加入 batch。
     */
    public static void setDetailParams(PreparedStatement ps, RowRecord record, long masterId) throws SQLException {
        ps.setLong(1, masterId);
        for (int i = 0; i < 12; i++) {
            ps.setString(i + 2, record.getField(i));
        }
        ps.addBatch();
    }

    public static void flushBatch(PreparedStatement ps) throws SQLException {
        ps.executeBatch();
        ps.clearBatch();
    }

    /**
     * 更新 FILE_MASTER 的 summary（成功/失敗筆數 + 狀態）。
     * 在所有 FILE_DETAIL 寫入完成後呼叫。
     */
    public static void updateMasterSummary(Connection conn, long masterId,
                                           long successCount, long failCount) throws SQLException {
        PreparedStatement ps = conn.prepareStatement(
                "UPDATE file_master SET status = 'COMPLETED', " +
                "total_count = ?, success_count = ?, fail_count = ? WHERE id = ?");
        ps.setLong(1, successCount + failCount);
        ps.setLong(2, successCount);
        ps.setLong(3, failCount);
        ps.setLong(4, masterId);
        ps.executeUpdate();
        ps.close();
    }

    /**
     * 查詢指定案件的 FILE_DETAIL 筆數。
     */
    public long countFileDetails(long masterId) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT COUNT(*) FROM file_detail WHERE master_id = ?")) {
            ps.setLong(1, masterId);
            ResultSet rs = ps.executeQuery();
            rs.next();
            return rs.getLong(1);
        }
    }

    /**
     * 查詢所有 FILE_DETAIL 筆數。
     */
    public long countRecords() throws SQLException {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM file_detail")) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
