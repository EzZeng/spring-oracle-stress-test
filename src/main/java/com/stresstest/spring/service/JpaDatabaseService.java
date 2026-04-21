package com.stresstest.spring.service;

import com.stresstest.spring.dao.RecordRepository;
import com.stresstest.spring.entity.RecordEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * JPA 資料庫操作服務。
 * 使用 EntityManager 搭配 batch flush 進行高效寫入。
 */
@Service
public class JpaDatabaseService {

    private static final Logger log = LoggerFactory.getLogger(JpaDatabaseService.class);

    /** 確保 initSchema() 只執行一次（並行請求防護） */
    private volatile boolean schemaInitialized = false;

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private RecordRepository recordRepository;

    @Autowired
    private DataSource dataSource;

    /**
     * 初始化 Schema：DROP + CREATE jpa_records 表。
     * 使用原生 SQL 確保表結構一致（因 Hibernate ddl-auto=none）。
     * 同時確保 file_master 表存在（與 JDBC 策略共用）。
     */
    public synchronized void initSchema() throws SQLException {
        if (schemaInitialized) return;
        // 先確保 file_master 表存在（冪等）
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            try {
                stmt.execute("CREATE SEQUENCE file_master_seq START WITH 1 INCREMENT BY 1 NOCACHE");
            } catch (SQLException e) {
                if (e.getErrorCode() != 955) throw e;
            }
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
        }

        // 重建 jpa_records（含 master_id FK）
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            // Drop table
            try {
                stmt.execute("DROP TABLE jpa_records PURGE");
            } catch (SQLException e) {
                if (e.getErrorCode() != 942) throw e; // ORA-00942: table does not exist
            }
            // Drop sequence (no longer used, but clean up if exists)
            try {
                stmt.execute("DROP SEQUENCE jpa_records_seq");
            } catch (SQLException e) {
                if (e.getErrorCode() != 2289) throw e; // ORA-02289: sequence does not exist
            }
            // Drop counter table
            try {
                stmt.execute("DROP TABLE jpa_id_alloc PURGE");
            } catch (SQLException e) {
                if (e.getErrorCode() != 942) throw e;
            }
            // Create counter table for gap-free ID allocation
            stmt.execute("CREATE TABLE jpa_id_alloc (next_id NUMBER NOT NULL)");
            stmt.execute("INSERT INTO jpa_id_alloc VALUES (1)");
            // Create table (id managed by application via jpa_id_alloc, master_id FK to file_master)
            // VARCHAR2(10 CHAR)：以字元（code unit）計長度，UTF-8 下可存 10 個中/日/韓字
            stmt.execute("CREATE TABLE jpa_records (" +
                    "id NUMBER PRIMARY KEY, " +
                    "master_id NUMBER, " +
                    "rowno NUMBER, " +
                    "field_a VARCHAR2(10 CHAR), " +
                    "field_b VARCHAR2(10 CHAR), " +
                    "field_c VARCHAR2(10 CHAR), " +
                    "field_d VARCHAR2(10 CHAR), " +
                    "field_e VARCHAR2(10 CHAR), " +
                    "field_f VARCHAR2(10 CHAR), " +
                    "field_g VARCHAR2(10 CHAR), " +
                    "field_h VARCHAR2(10 CHAR), " +
                    "field_i VARCHAR2(10 CHAR), " +
                    "field_j VARCHAR2(10 CHAR), " +
                    "field_k VARCHAR2(10 CHAR), " +
                    "field_l VARCHAR2(10 CHAR), " +
                    "download_time TIMESTAMP, " +
                    "CONSTRAINT fk_jpa_records_master FOREIGN KEY (master_id) REFERENCES file_master(id)" +
                    ")");
            log.info("JPA Schema 初始化完成（file_master + jpa_records + jpa_id_alloc）");
        }
        schemaInitialized = true;
    }

    /**
     * 原子性保留一段連續 ID 範圍（無跳號）。
     * 使用 REQUIRES_NEW 開啟獨立 JTA XA 交易，確保 ID 計數器的提交不受外部交易影響。
     * @param count 需要保留的 ID 數量
     * @return 保留範圍的起始 ID（含）
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public long reserveIdRange(long count) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            ResultSet rs = stmt.executeQuery(
                    "SELECT next_id FROM jpa_id_alloc FOR UPDATE");
            if (!rs.next()) throw new SQLException("jpa_id_alloc is empty");
            long startId = rs.getLong(1);
            rs.close();
            stmt.executeUpdate(
                    "UPDATE jpa_id_alloc SET next_id = " + (startId + count));
            // REQUIRES_NEW JTA 交易由 Spring AOP 在方法返回後自動 commit
            return startId;
        }
    }

    /**
     * 使用 EntityManager persist + batch flush/clear。
     */
    public void persistEntity(RecordEntity entity) {
        entityManager.persist(entity);
    }

    /**
     * flush + clear 以釋放一級快取。
     */
    public void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    /**
     * 批次 persist：整批 persist 後 flush + clear。
     * 回傳 [persistNanos, flushNanos] 供效能分析。
     */
    public long[] persistBatchTimed(List<RecordEntity> entities) {
        long t0 = System.nanoTime();
        for (RecordEntity e : entities) {
            entityManager.persist(e);
        }
        long t1 = System.nanoTime();
        entityManager.flush();
        entityManager.clear();
        long t2 = System.nanoTime();
        return new long[]{ t1 - t0, t2 - t1 };
    }

    /**
     * 批次 persist：整批 persist 後 flush + clear。
     */
    public void persistBatch(List<RecordEntity> entities) {
        for (RecordEntity e : entities) {
            entityManager.persist(e);
        }
        entityManager.flush();
        entityManager.clear();
    }

    /**
     * save 單筆透過 Repository。
     */
    public RecordEntity saveViaRepository(RecordEntity entity) {
        return recordRepository.save(entity);
    }

    /**
     * saveAll 透過 Repository（Spring Data 內建）。
     */
    public void saveAllViaRepository(List<RecordEntity> entities) {
        recordRepository.saveAll(entities);
    }

    /**
     * 查詢總筆數。
     */
    public long countRecords() {
        return recordRepository.count();
    }

    public EntityManager getEntityManager() {
        return entityManager;
    }

    /**
     * 在當前 @Transactional 範圍內建立 FILE_MASTER（案件），回傳 ID。
     * 使用 file_master_seq NEXTVAL 確保 ID 唯一。
     */
    public long insertMaster(String fileName) {
        Number id = (Number) entityManager.createNativeQuery(
                "SELECT file_master_seq.NEXTVAL FROM dual")
                .getSingleResult();
        entityManager.createNativeQuery(
                "INSERT INTO file_master (id, file_name) VALUES (?, ?)")
                .setParameter(1, id.longValue())
                .setParameter(2, fileName)
                .executeUpdate();
        return id.longValue();
    }

    /**
     * 更新 FILE_MASTER 的 summary（成功/失敗筆數 + 狀態）。
     */
    public void updateMasterSummary(long masterId, long successCount, long failCount) {
        entityManager.createNativeQuery(
                "UPDATE file_master SET status = 'COMPLETED', " +
                "total_count = ?, success_count = ?, fail_count = ? WHERE id = ?")
                .setParameter(1, successCount + failCount)
                .setParameter(2, successCount)
                .setParameter(3, failCount)
                .setParameter(4, masterId)
                .executeUpdate();
    }

    /**
     * 查詢指定案件的 jpa_records 筆數。
     */
    public long countRecordsByMaster(long masterId) {
        Number count = (Number) entityManager.createNativeQuery(
                "SELECT COUNT(*) FROM jpa_records WHERE master_id = ?")
                .setParameter(1, masterId)
                .getSingleResult();
        return count.longValue();
    }
}
