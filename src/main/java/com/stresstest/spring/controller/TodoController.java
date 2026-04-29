package com.stresstest.spring.controller;

import com.stresstest.spring.entity.UploadCase;
import com.stresstest.spring.service.AsyncApprovalService;
import com.stresstest.spring.service.CaseService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * 待辦事項 REST Controller。
 * <p>
 * GET  /api/todo            → 列出所有待辦案件（PENDING）
 * GET  /api/todo/all        → 列出所有案件（含已放行/已駁回）
 * POST /api/todo/{id}/approve → 放行案件 → 寫入兩個網域資料庫
 * POST /api/todo/{id}/reject  → 駁回案件
 */
@RestController
@RequestMapping("/api/todo")
public class TodoController {

    private static final Logger log = LoggerFactory.getLogger(TodoController.class);

    @Autowired
    private CaseService caseService;

    @Autowired
    private AsyncApprovalService asyncApprovalService;

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> listPending() {
        List<UploadCase> cases = caseService.getPendingCases();
        List<Map<String, Object>> result = new ArrayList<>();
        for (UploadCase c : cases) {
            result.add(caseToMap(c));
        }
        return ResponseEntity.ok(result);
    }

    @GetMapping("/all")
    public ResponseEntity<List<Map<String, Object>>> listAll() {
        List<UploadCase> cases = caseService.getAllCases();
        List<Map<String, Object>> result = new ArrayList<>();
        for (UploadCase c : cases) {
            result.add(caseToMap(c));
        }
        return ResponseEntity.ok(result);
    }

    @PostMapping("/{id}/approve")
    public ResponseEntity<Map<String, Object>> approve(@PathVariable Long id) {
        try {
            UploadCase c = caseService.approveCase(id);
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("status", "APPROVED");
            response.put("case", caseToMap(c));
            response.put("message", "案件已放行，檔案資料已寫入兩個網域資料庫");
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Collections.singletonMap("error", (Object) e.getMessage()));
        } catch (Exception e) {
            log.error("放行失敗: {}", e.getMessage(), e);
            return ResponseEntity.status(500).body(Collections.singletonMap("error", (Object) ("放行失敗: " + e.getMessage())));
        }
    }

    /**
     * 模擬「@Async 放行」：在獨立執行緒裡跑兩個獨立 @Transactional 交易。
     * 觀察 transaction 行為（部分 commit、部分 rollback、tx context 不傳遞等）。
     *
     * <pre>
     *   POST /api/todo/{id}/approve-async                          → 兩個 tx 都 commit
     *   POST /api/todo/{id}/approve-async?simulateFailure=true     → Tx1 commit, Tx2 故意失敗 rollback
     * </pre>
     *
     * 端點立即回應 ACCEPTED；非同步結果請查 server log 與 approval_async_log 表。
     */
    @PostMapping("/{id}/approve-async")
    public ResponseEntity<Map<String, Object>> approveAsync(
            @PathVariable Long id,
            @RequestParam(value = "simulateFailure", defaultValue = "false") boolean simulateFailure) {
        asyncApprovalService.asyncApproveTwoTransactions(id, simulateFailure);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "ACCEPTED");
        response.put("caseId", id);
        response.put("simulateFailure", simulateFailure);
        response.put("message", "已派送非同步放行，請查 server log 與 approval_async_log 表");
        return ResponseEntity.accepted().body(response);
    }

    /**
     * POST /api/todo/{id}/master-detail-ab
     *  4 步驟流程：master → detail_a → detail_b → UPDATE detail_b.detail_a_id
     *  全部包在同一個 JTA / XA 交易內，timeout=1800s。
     */
    @PostMapping("/{id}/master-detail-ab")
    public ResponseEntity<Map<String, Object>> masterDetailAB(@PathVariable Long id) {
        try {
            long t0 = System.currentTimeMillis();
            long[] ids = asyncApprovalService.runMasterDetailABFlow(id);
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("status", "OK");
            r.put("caseId", id);
            r.put("masterId", ids[0]);
            r.put("detailAId", ids[1]);
            r.put("detailBId", ids[2]);
            r.put("elapsedMs", System.currentTimeMillis() - t0);
            return ResponseEntity.ok(r);
        } catch (Exception e) {
            log.error("master-detail-ab failed: {}", e.getMessage(), e);
            return ResponseEntity.status(500)
                    .body(Collections.singletonMap("error", (Object) e.getMessage()));
        }
    }

    @PostMapping("/{id}/reject")
    public ResponseEntity<Map<String, Object>> reject(@PathVariable Long id) {
        try {
            UploadCase c = caseService.rejectCase(id);
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("status", "REJECTED");
            response.put("case", caseToMap(c));
            response.put("message", "案件已駁回");
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Collections.singletonMap("error", (Object) e.getMessage()));
        }
    }

    /**
     * GET /api/todo/{id}/download
     * 從業務目標表讀取已放行資料，組成 TXT 回傳給前端下載，同時以 BLOB 存入 download_file 表。
     */
    @GetMapping("/{id}/download")
    public ResponseEntity<byte[]> download(@PathVariable Long id) {
        try {
            byte[] content = caseService.generateDownloadFile(id);
            String filename = "case_" + id + "_download.txt";
            return ResponseEntity.ok()
                    .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
                    .header("Content-Type", "text/plain; charset=UTF-8")
                    .body(content);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest()
                    .<byte[]>body(e.getMessage().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.error("下載失敗: {}", e.getMessage(), e);
            return ResponseEntity.status(500)
                    .<byte[]>body(("下載失敗: " + e.getMessage()).getBytes(StandardCharsets.UTF_8));
        }
    }

    private Map<String, Object> caseToMap(UploadCase c) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("caseId", c.getId());
        map.put("masterId", c.getMasterId());
        map.put("fileName", c.getFileName());
        map.put("strategyType", c.getStrategyType());
        map.put("bizType", c.getBizType());
        map.put("status", c.getStatus());
        map.put("totalCount", c.getTotalCount());
        map.put("successCount", c.getSuccessCount());
        map.put("failCount", c.getFailCount());
        map.put("createTime", c.getCreateTime());
        map.put("approveTime", c.getApproveTime());
        return map;
    }
}
