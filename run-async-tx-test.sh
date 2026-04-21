#!/usr/bin/env bash
#
# @Async + 兩個獨立 @Transactional 模擬測試
#
# 流程：
#   1. 上傳一筆 BIZ_A 小資料（用 1000 筆即可，重點不是壓測而是觀察 tx 行為）
#   2. 觸發 POST /api/todo/{id}/approve-async?simulateFailure=true
#   3. 等 async 執行完成
#   4. 查 approval_async_log：應該看到 TX1_WRITE_LOG 已 commit、TX2_BEFORE_BOOM 被 rollback
#   5. 查 upload_case 狀態：應該為 APPROVED_ASYNC_TX1（Tx1 已 commit）
#   6. 顯示 Spring Boot log 中的 [ASYNC] / [Tx1] / [Tx2] 訊息
#
# 使用方式：
#   bash run-async-tx-test.sh             # simulateFailure=true（預設，會看到 Tx2 rollback）
#   bash run-async-tx-test.sh false       # 兩個 tx 都成功 commit
#

set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$PROJECT_DIR"

JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk}"
JAVA="$JAVA_HOME/bin/java"
MVN="mvn"

JVM_OPTS="-Xms512m -Xmx2g"
DATA_DIR="$PROJECT_DIR/stress-test-data"
DATA_FILE="$DATA_DIR/test_data_1000.dat"
SPRING_JAR="$PROJECT_DIR/target/spring-oracle-stress-test-1.0-SNAPSHOT.jar"
PORT=8080
BASE_URL="http://localhost:$PORT"

SIMULATE_FAILURE=${1:-true}
TOTAL_ROWS=1000

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[0;33m'
CYAN='\033[0;36m'
BOLD='\033[1m'
NC='\033[0m'

echo -e "${CYAN}╔══════════════════════════════════════════════════════════════════════╗${NC}"
echo -e "${CYAN}║   @Async + 兩個獨立 @Transactional 行為測試                        ║${NC}"
echo -e "${CYAN}║   simulateFailure: $SIMULATE_FAILURE                                          ║${NC}"
echo -e "${CYAN}╚══════════════════════════════════════════════════════════════════════╝${NC}"

# ===== 檢查 Oracle =====
echo -e "\n${YELLOW}═══ 檢查 Oracle 容器 ═══${NC}"
if ! podman ps --filter name=oracle-xe --format "{{.Names}}" 2>/dev/null | grep -q oracle-xe; then
    podman start oracle-xe 2>/dev/null || { echo -e "${RED}Oracle 容器不存在${NC}"; exit 1; }
    for i in $(seq 1 30); do
        podman logs oracle-xe 2>&1 | grep -q "DATABASE IS READY TO USE" && break
        sleep 2
    done
fi
echo -e "${GREEN}Oracle 就緒${NC}"

# ===== 產生測試資料 =====
mkdir -p "$DATA_DIR"
if [ ! -f "$DATA_FILE" ]; then
    echo "產生 $TOTAL_ROWS 筆測試資料..."
    "$MVN" -q compile exec:java \
        -Dexec.mainClass=com.stresstest.spring.service.FileGenerator \
        -Dexec.args="$DATA_DIR $TOTAL_ROWS"
fi
[ -f "$DATA_FILE" ] || { echo -e "${RED}測試資料產生失敗${NC}"; exit 1; }

# ===== 編譯 =====
if [ ! -f "$SPRING_JAR" ]; then
    echo -e "\n${YELLOW}═══ 編譯 ═══${NC}"
    "$MVN" -q clean package -DskipTests
fi

# ===== 啟動 Spring Boot =====
echo -e "\n${YELLOW}═══ 啟動 Spring Boot ═══${NC}"
LOG_FILE="$PROJECT_DIR/spring-async-test.log"
"$JAVA" $JVM_OPTS -jar "$SPRING_JAR" > "$LOG_FILE" 2>&1 &
SPRING_PID=$!
trap "kill $SPRING_PID 2>/dev/null || true" EXIT

echo "等待 Spring Boot 啟動 (PID=$SPRING_PID)..."
for i in $(seq 1 60); do
    if curl -s "$BASE_URL/api/strategies" > /dev/null 2>&1; then
        echo -e "${GREEN}Spring Boot 就緒${NC}"
        break
    fi
    sleep 1
done

# ===== Step 1: 上傳建立案件 =====
echo -e "\n${YELLOW}═══ Step 1: 上傳檔案建立 PENDING 案件 ═══${NC}"
UPLOAD_RESP=$(curl -s -X POST "$BASE_URL/api/upload?strategy=2&bizType=BIZ_A" \
    -F "file=@$DATA_FILE")
echo "$UPLOAD_RESP"
CASE_ID=$(echo "$UPLOAD_RESP" | grep -oE '"caseId":[0-9]+' | head -1 | cut -d: -f2)
if [ -z "$CASE_ID" ]; then
    echo -e "${RED}上傳失敗，未取得 caseId${NC}"
    exit 1
fi
echo -e "${GREEN}案件 ID: $CASE_ID${NC}"

# ===== Step 2: 觸發 @Async 放行 =====
echo -e "\n${YELLOW}═══ Step 2: 觸發 @Async 放行（2 個獨立 tx）═══${NC}"
ASYNC_RESP=$(curl -s -X POST "$BASE_URL/api/todo/$CASE_ID/approve-async?simulateFailure=$SIMULATE_FAILURE")
echo "$ASYNC_RESP"

# ===== Step 3: 等待 async 完成 =====
echo -e "\n${YELLOW}═══ Step 3: 等待 async 任務完成 (5s) ═══${NC}"
sleep 5

# ===== Step 4: 顯示 [ASYNC] / [Tx1] / [Tx2] log =====
echo -e "\n${YELLOW}═══ Step 4: Async 執行 log ═══${NC}"
grep -E "\[ASYNC|\[Tx1\]|\[Tx2\]|approval_async" "$LOG_FILE" || echo "（無相關 log）"

# ===== Step 5: 查 parent / child 表 =====
echo -e "\n${YELLOW}═══ Step 5: approval_master (parent，Tx1 寫入) ═══${NC}"
podman exec -i oracle-xe sqlplus -S stresstest/stresstest@//localhost:1521/FREEPDB1 <<EOF
SET LINESIZE 200
SET PAGESIZE 50
COL tx_label FORMAT A18
COL thread_name FORMAT A40
SELECT id AS master_id, case_id, tx_label, thread_name, log_time
  FROM approval_master
 WHERE case_id = $CASE_ID
 ORDER BY id;
EOF

echo -e "\n${YELLOW}═══ Step 5b: approval_detail (child，Tx2 寫入；FK→master_id) ═══${NC}"
podman exec -i oracle-xe sqlplus -S stresstest/stresstest@//localhost:1521/FREEPDB1 <<EOF
SET LINESIZE 200
SET PAGESIZE 50
COL tx_label FORMAT A18
COL thread_name FORMAT A40
SELECT id, master_id, case_id, tx_label, thread_name, log_time
  FROM approval_detail
 WHERE case_id = $CASE_ID
 ORDER BY id;
EOF

echo -e "\n${YELLOW}═══ Step 5c: 孤兒檢查（master 有但 detail 沒有的 row） ═══${NC}"
podman exec -i oracle-xe sqlplus -S stresstest/stresstest@//localhost:1521/FREEPDB1 <<EOF
SET LINESIZE 200
SELECT m.id AS orphan_master_id, m.case_id
  FROM approval_master m
  LEFT JOIN approval_detail d ON d.master_id = m.id
 WHERE m.case_id = $CASE_ID
   AND d.id IS NULL;
EOF

# ===== Step 6: 查 upload_case 狀態 =====
echo -e "\n${YELLOW}═══ Step 6: upload_case 狀態 ═══${NC}"
podman exec -i oracle-xe sqlplus -S stresstest/stresstest@//localhost:1521/FREEPDB1 <<EOF
SET LINESIZE 200
COL status FORMAT A30
SELECT id, status, biz_type, approve_time
  FROM upload_case
 WHERE id = $CASE_ID;
EOF

# ===== 結論 =====
echo -e "\n${CYAN}═══ 結論 ═══${NC}"
if [ "$SIMULATE_FAILURE" = "true" ]; then
    echo -e "${BOLD}預期觀察結果：${NC}"
    echo "  - approval_master 有 1 筆（Tx1 commit；PK 來自 sequence）"
    echo "  - approval_detail  0 筆（Tx2 RuntimeException → child rollback）"
    echo "  - Step 5c 會列出孤兒 master_id（child 不存在）"
    echo "  - upload_case.status = APPROVED_ASYNC_TX1（Tx1 已 commit）"
    echo "  - log 可見 [ASYNC] Tx2 例外訊息"
else
    echo -e "${BOLD}預期觀察結果：${NC}"
    echo "  - approval_master 1 筆 + approval_detail 1 筆，detail.master_id = master.id"
    echo "  - 兩個獨立 tx 各自 commit，FK 關聯完整"
fi

echo -e "\n${GREEN}測試完成。完整 log: $LOG_FILE${NC}"
