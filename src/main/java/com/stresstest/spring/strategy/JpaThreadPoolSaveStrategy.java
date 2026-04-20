package com.stresstest.spring.strategy;

import com.stresstest.spring.entity.RecordEntity;
import com.stresstest.spring.model.ProcessResult;
import com.stresstest.spring.service.DatabaseService;
import com.stresstest.spring.service.FileValidator;
import com.stresstest.spring.service.JpaDatabaseService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import javax.persistence.EntityTransaction;
import java.io.BufferedReader;
import java.io.FileReader;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * JPA 策略 — 多執行緒 save（SEQUENCE=1 + no batch 優化版）。
 * 主執行緒讀檔 → 分 chunk 丟入 ThreadPool → 每個 worker 獨立 EntityManager + Transaction。
 * 適合 SEQUENCE=1 場景：多條連線並行 INSERT 可隱藏 NEXTVAL 往返延遲。
 */
@Component
@Order(12)
public class JpaThreadPoolSaveStrategy implements ProcessingStrategy {

    private static final Logger log = LoggerFactory.getLogger(JpaThreadPoolSaveStrategy.class);
    private static final int THREAD_COUNT = 4;
    private static final int CHUNK_SIZE = 5000;

    @Autowired
    private EntityManagerFactory emf;

    @Autowired
    private JpaDatabaseService jpaDb;

    @Override
    public String getName() {
        return "[JPA] ThreadPool save (多執行緒)";
    }

    @Override
    public ProcessResult process(Path filePath, DatabaseService db, String fileName) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
        List<Future<Integer>> futures = new ArrayList<>();
        AtomicLong totalSubmitted = new AtomicLong(0);

        // 建立 FILE_MASTER（獨立交易，因本策略無 @Transactional）
        long masterId;
        try (Connection masterConn = db.getConnection()) {
            masterConn.setAutoCommit(false);
            masterId = DatabaseService.insertMaster(masterConn, fileName);
            masterConn.commit();
        }

        try (BufferedReader reader = new BufferedReader(new FileReader(filePath.toFile()), 8 * 1024 * 1024)) {
            // Header 檢核
            String headerLine = reader.readLine();
            FileValidator.validateHeader(headerLine);

            String line;
            String prevLine = null;
            long totalCharSum = 0;
            long totalCount = 0;
            List<RecordEntity> chunk = new ArrayList<>(CHUNK_SIZE);

            while ((line = reader.readLine()) != null) {
                if (prevLine != null) {
                    totalCharSum += prevLine.length();
                        totalCount++;
                        chunk.add(RecordEntity.fromLine(prevLine, totalCount, masterId));
                    if (chunk.size() >= CHUNK_SIZE) {
                        final List<RecordEntity> batch = chunk;
                        long startId = jpaDb.reserveIdRange(batch.size());
                        for (int i = 0; i < batch.size(); i++) {
                            batch.get(i).setId(startId + i);
                        }
                        chunk = new ArrayList<>(CHUNK_SIZE);
                        futures.add(executor.submit(() -> persistChunk(batch)));
                        long submitted = totalSubmitted.addAndGet(batch.size());
                        if (submitted % 200_000 == 0) {
                            log.info("[JPA-ThreadPool] 已提交 {} 筆", submitted);
                        }
                    }
                }
                prevLine = line;
            }

            // 剩餘不足 CHUNK_SIZE 的
            if (!chunk.isEmpty()) {
                final List<RecordEntity> batch = chunk;
                long startId = jpaDb.reserveIdRange(batch.size());
                for (int i = 0; i < batch.size(); i++) {
                    batch.get(i).setId(startId + i);
                }
                futures.add(executor.submit(() -> persistChunk(batch)));
                totalSubmitted.addAndGet(batch.size());
            }

            // Trailer 檢核
            FileValidator.validateTrailer(prevLine, totalCount, totalCharSum);

            // 等待所有 worker 完成
            executor.shutdown();
            long totalInserted = 0;
            for (Future<Integer> f : futures) {
                totalInserted += f.get();
            }

            // 更新 FILE_MASTER summary
            try (Connection summaryConn = db.getConnection()) {
                summaryConn.setAutoCommit(false);
                DatabaseService.updateMasterSummary(summaryConn, masterId, totalInserted, 0);
                summaryConn.commit();
            }

            log.info("[JPA-ThreadPool] COMMIT 成功，共 {} 筆 ({} threads) (masterId={})", totalInserted, THREAD_COUNT, masterId);
            return new ProcessResult(masterId, totalInserted, 0);

        } catch (Exception e) {
            executor.shutdownNow();
            throw e;
        }
    }

    /**
     * 每個 chunk 在獨立 EntityManager + Transaction 中 persist。
     */
    private int persistChunk(List<RecordEntity> entities) {
        EntityManager em = emf.createEntityManager();
        EntityTransaction tx = em.getTransaction();
        try {
            tx.begin();
            for (RecordEntity entity : entities) {
                em.persist(entity);
            }
            em.flush();
            tx.commit();
            return entities.size();
        } catch (Exception e) {
            if (tx.isActive()) tx.rollback();
            throw new RuntimeException("Thread persist 失敗: " + e.getMessage(), e);
        } finally {
            em.close();
        }
    }
}
