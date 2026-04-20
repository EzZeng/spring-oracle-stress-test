package com.stresstest.spring.dao;

import com.stresstest.spring.entity.RecordEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;

/**
 * JPA Repository（DAO）：RecordEntity 的 CRUD 操作。
 */
@Repository
public interface RecordRepository extends JpaRepository<RecordEntity, Long> {

    @Modifying
    @Query(value = "TRUNCATE TABLE jpa_records", nativeQuery = true)
    void truncateTable();

    /**
     * 原子性標記下載時間：僅更新 download_time IS NULL 的記錄。
     * 回傳受影響筆數（已被標記的記錄不會重複標記）。
     */
    @Modifying
    @Query(value = "UPDATE jpa_records SET download_time = :ts WHERE id >= :minId AND id <= :maxId AND download_time IS NULL", nativeQuery = true)
    int markDownloaded(@Param("ts") Timestamp ts, @Param("minId") long minId, @Param("maxId") long maxId);

    /**
     * 查詢指定 ID 範圍中已被下載的筆數。
     */
    @Query(value = "SELECT COUNT(*) FROM jpa_records WHERE id >= :minId AND id <= :maxId AND download_time IS NOT NULL", nativeQuery = true)
    long countDownloaded(@Param("minId") long minId, @Param("maxId") long maxId);

    /**
     * 查詢未下載的筆數。
     */
    @Query(value = "SELECT COUNT(*) FROM jpa_records WHERE download_time IS NULL", nativeQuery = true)
    long countNotDownloaded();
}
