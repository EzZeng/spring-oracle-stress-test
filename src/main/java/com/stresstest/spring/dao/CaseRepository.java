package com.stresstest.spring.dao;

import com.stresstest.spring.entity.UploadCase;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import javax.persistence.LockModeType;
import java.util.List;
import java.util.Optional;

public interface CaseRepository extends JpaRepository<UploadCase, Long> {
    List<UploadCase> findByStatusOrderByCreateTimeDesc(String status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM UploadCase c WHERE c.id = :id")
    Optional<UploadCase> findByIdForUpdate(@Param("id") Long id);
}
