#!/bin/bash
#
# 完整流程壓力測試：上傳 → 建立案件 → 待辦查詢 → 放行（寫入兩個網域DB）
# 重複 50 次，統計每次完整流程耗時（平均/最快/最慢）
#
# 使用方式：
#   bash run-flow-stress-test.sh              # 預設策略 1，50 次
#   bash run-flow-stress-test.sh 3            # 策略 3，50 次
#   bash run-flow-stress-test.sh 1 10         # 策略 1，10 次
#

set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$PROJECT_DIR"

JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk}"
JAVA="$JAVA_HOME/bin/java"
MVN="mvn"

JVM_OPTS="-Xms512m -Xmx4g -XX:+UseG1GC -XX:ActiveProcessorCount=4 -XX:MaxGCPauseMillis=200"
DATA_DIR="$PROJECT_DIR/stress-test-data"
DATA_FILE="$DATA_DIR/test_data_1200000.dat"
SPRING_JAR="$PROJECT_DIR/target/spring-oracle-stress-test-1.0-SNAPSHOT.jar"
PORT=8080
BASE_URL="http://localhost:$PORT"

STRATEGY=${1:-1}
ITERATIONS=${2:-50}
TOTAL_ROWS=1200000

# 顏色
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[0;33m'
CYAN='\033[0;36m'
BOLD='\033[1m'
NC='\033[0m'

echo -e "${CYAN}╔══════════════════════════════════════════════════════════════════╗${NC}"
echo -e "${CYAN}║   完整流程壓力測試：上傳 → 案件 → 待辦 → 放行 → 雙DB寫入     ║${NC}"
echo -e "${CYAN}║   策略: $STRATEGY | 次數: $ITERATIONS | 每次: ${TOTAL_ROWS} 筆 (120萬)         ║${NC}"
echo -e "${CYAN}║   JVM: $JVM_OPTS${NC}"
echo -e "${CYAN}╚══════════════════════════════════════════════════════════════════╝${NC}"

# ===== 檢查 Oracle 容器 =====
echo -e "\n${YELLOW}═══ 檢查 Oracle 容器 ═══${NC}"
if podman ps --filter name=oracle-xe --format "{{.Names}}" 2>/dev/null | grep -q oracle-xe; then
    echo -e "${GREEN}Oracle 容器正在運行${NC}"
else
    echo -e "${YELLOW}啟動 Oracle 容器...${NC}"
    podman start oracle-xe 2>/dev/null || {
        echo -e "${RED}Oracle 容器不存在，請先建立${NC}"
        exit 1
    }
    echo "等待 Oracle 啟動..."
    for i in $(seq 1 30); do
        if podman logs oracle-xe 2>&1 | grep -q "DATABASE IS READY TO USE"; then
            echo -e "${GREEN}Oracle 已就緒${NC}"
            break
        fi
        sleep 2
    done
fi

# ===== 產生 120 萬筆測試資料 =====
echo -e "\n${YELLOW}═══ 準備測試資料 (${TOTAL_ROWS} 筆) ═══${NC}"
mkdir -p "$DATA_DIR"
if [ -f "$DATA_FILE" ]; then
    FILE_SIZE=$(stat -f%z "$DATA_FILE" 2>/dev/null || stat -c%s "$DATA_FILE" 2>/dev/null)
    echo -e "${GREEN}測試檔案已存在: $DATA_FILE ($(echo "scale=2; $FILE_SIZE / 1048576" | bc) MB)${NC}"
else
    echo "產生 ${TOTAL_ROWS} 筆測試資料..."
    "$MVN" -q compile exec:java \
        -Dexec.mainClass="com.stresstest.spring.service.FileGenerator" \
        -Dexec.args="$DATA_DIR $TOTAL_ROWS" 2>&1 || {
        echo -e "${RED}無法產生測試資料${NC}"
        exit 1
    }
    echo -e "${GREEN}測試資料產生完成${NC}"
fi

# ===== 編譯 =====
echo -e "\n${YELLOW}═══ 編譯 Spring Boot 專案 ═══${NC}"
"$MVN" -q clean package -DskipTests 2>&1
echo -e "${GREEN}編譯完成${NC}"

# ===== 啟動 Spring Boot =====
echo -e "\n${YELLOW}═══ 啟動 Spring Boot ═══${NC}"
$JAVA $JVM_OPTS -jar "$SPRING_JAR" --server.port=$PORT > /tmp/spring-flow-stress-test.log 2>&1 &
SPRING_PID=$!

STARTED=false
for i in $(seq 1 60); do
    if curl -s "$BASE_URL/api/strategies" > /dev/null 2>&1; then
        echo -e "${GREEN}Spring Boot 已啟動 (PID: $SPRING_PID)${NC}"
        STARTED=true
        break
    fi
    if ! kill -0 $SPRING_PID 2>/dev/null; then
        echo -e "${RED}Spring Boot 啟動失敗！${NC}"
        tail -30 /tmp/spring-flow-stress-test.log
        exit 1
    fi
    sleep 1
done
if [ "$STARTED" = false ]; then
    echo -e "${RED}Spring Boot 啟動超時${NC}"
    kill $SPRING_PID 2>/dev/null || true
    exit 1
fi

cleanup() {
    echo -e "\n${YELLOW}停止 Spring Boot (PID: $SPRING_PID)...${NC}"
    kill $SPRING_PID 2>/dev/null || true
    wait $SPRING_PID 2>/dev/null || true
}
trap cleanup EXIT

# ===== 結果收集陣列 =====
declare -a UPLOAD_TIMES
declare -a APPROVE_TIMES
declare -a TOTAL_TIMES
declare -a STATUSES
PASS_COUNT=0
FAIL_COUNT=0

FILE_SIZE=$(stat -f%z "$DATA_FILE" 2>/dev/null || stat -c%s "$DATA_FILE" 2>/dev/null)

echo -e "\n${CYAN}════════════════════════════════════════════════════════════════════${NC}"
echo -e "${CYAN}  開始壓力測試：${ITERATIONS} 次完整流程（策略 ${STRATEGY}）${NC}"
echo -e "${CYAN}════════════════════════════════════════════════════════════════════${NC}"
echo ""

printf "${BOLD}%-6s │ %12s │ %12s │ %12s │ %10s │ %s${NC}\n" \
    "# 次" "上傳(ms)" "放行(ms)" "總計(ms)" "案件ID" "狀態"
echo "───────┼──────────────┼──────────────┼──────────────┼────────────┼──────────"

# ===== SQL 清理函式（TRUNCATE + 回收空間）=====
cleanup_db() {
    podman exec oracle-xe bash -c "sqlplus -s '/ as sysdba' << 'INNEREOF'
ALTER SESSION SET CONTAINER=FREEPDB1;
SET HEADING OFF FEEDBACK OFF
BEGIN EXECUTE IMMEDIATE 'TRUNCATE TABLE stresstest.approved_file_detail DROP ALL STORAGE'; EXCEPTION WHEN OTHERS THEN NULL; END;
/
BEGIN EXECUTE IMMEDIATE 'TRUNCATE TABLE stresstest.file_detail DROP ALL STORAGE'; EXCEPTION WHEN OTHERS THEN NULL; END;
/
BEGIN EXECUTE IMMEDIATE 'TRUNCATE TABLE stresstest.jpa_records DROP ALL STORAGE'; EXCEPTION WHEN OTHERS THEN NULL; END;
/
BEGIN EXECUTE IMMEDIATE 'TRUNCATE TABLE stresstest.records DROP ALL STORAGE'; EXCEPTION WHEN OTHERS THEN NULL; END;
/
BEGIN DELETE FROM stresstest.upload_case; COMMIT; EXCEPTION WHEN OTHERS THEN NULL; END;
/
BEGIN DELETE FROM stresstest.file_master; COMMIT; EXCEPTION WHEN OTHERS THEN NULL; END;
/
BEGIN
  EXECUTE IMMEDIATE 'ALTER DATABASE DATAFILE ''/opt/oracle/oradata/FREE/FREEPDB1/users01.dbf'' RESIZE 100M';
EXCEPTION WHEN OTHERS THEN NULL;
END;
/
EXIT;
INNEREOF" > /dev/null 2>&1
}

for (( i=1; i<=ITERATIONS; i++ )); do
    ITER_STATUS="OK"

    # ===== Step 0：清理上一次的資料（每次迭代前都清理）=====
    cleanup_db

    # ===== Step 1：上傳檔案 =====
    UPLOAD_START=$(python3 -c "import time; print(int(time.time()*1000))")
    UPLOAD_RESP=$(curl -s -w "\n%{http_code}" \
        -X POST "$BASE_URL/api/upload?strategy=$STRATEGY" \
        -F "file=@$DATA_FILE" \
        --max-time 1200)
    UPLOAD_END=$(python3 -c "import time; print(int(time.time()*1000))")

    UPLOAD_HTTP=$(echo "$UPLOAD_RESP" | tail -1)
    UPLOAD_BODY=$(echo "$UPLOAD_RESP" | sed '$d')
    UPLOAD_MS=$((UPLOAD_END - UPLOAD_START))

    if [ "$UPLOAD_HTTP" != "200" ]; then
        ITER_STATUS="UPLOAD_FAIL"
        APPROVE_MS=0
        TOTAL_MS=$UPLOAD_MS
        CASE_ID="N/A"
    else
        CASE_ID=$(echo "$UPLOAD_BODY" | python3 -c "import sys,json; print(json.load(sys.stdin).get('caseId','?'))" 2>/dev/null || echo "?")

        # ===== Step 2：放行案件 =====
        APPROVE_START=$(python3 -c "import time; print(int(time.time()*1000))")
        APPROVE_RESP=$(curl -s -w "\n%{http_code}" \
            -X POST "$BASE_URL/api/todo/$CASE_ID/approve" \
            --max-time 1200)
        APPROVE_END=$(python3 -c "import time; print(int(time.time()*1000))")

        APPROVE_HTTP=$(echo "$APPROVE_RESP" | tail -1)
        APPROVE_MS=$((APPROVE_END - APPROVE_START))
        TOTAL_MS=$((UPLOAD_MS + APPROVE_MS))

        if [ "$APPROVE_HTTP" != "200" ]; then
            ITER_STATUS="APPROVE_FAIL"
        fi
    fi

    UPLOAD_TIMES[$((i-1))]=$UPLOAD_MS
    APPROVE_TIMES[$((i-1))]=$APPROVE_MS
    TOTAL_TIMES[$((i-1))]=$TOTAL_MS
    STATUSES[$((i-1))]="$ITER_STATUS"

    if [ "$ITER_STATUS" = "OK" ]; then
        PASS_COUNT=$((PASS_COUNT + 1))
        STATUS_COLOR="${GREEN}"
    else
        FAIL_COUNT=$((FAIL_COUNT + 1))
        STATUS_COLOR="${RED}"
    fi

    printf "%-6s │ %12d │ %12d │ %12d │ %10s │ ${STATUS_COLOR}%s${NC}\n" \
        "$i/$ITERATIONS" "$UPLOAD_MS" "$APPROVE_MS" "$TOTAL_MS" "$CASE_ID" "$ITER_STATUS"
done

# ===== 統計計算 =====
echo ""
echo -e "${CYAN}════════════════════════════════════════════════════════════════════${NC}"
echo -e "${CYAN}  壓力測試結果統計${NC}"
echo -e "${CYAN}════════════════════════════════════════════════════════════════════${NC}"

# 用 python3 計算統計
UPLOAD_CSV=$(IFS=,; echo "${UPLOAD_TIMES[*]}")
APPROVE_CSV=$(IFS=,; echo "${APPROVE_TIMES[*]}")
TOTAL_CSV=$(IFS=,; echo "${TOTAL_TIMES[*]}")

STATS=$(python3 << PYEOF
import json

upload_times = [${UPLOAD_CSV}]
approve_times = [${APPROVE_CSV}]
total_times = [${TOTAL_CSV}]
statuses = $(printf '['; for s in "${STATUSES[@]}"; do printf '"%s",' "$s"; done; printf ']')

ok_indices = [i for i, s in enumerate(statuses) if s == "OK"]
if ok_indices:
    ok_upload = [upload_times[i] for i in ok_indices]
    ok_approve = [approve_times[i] for i in ok_indices]
    ok_total = [total_times[i] for i in ok_indices]

    result = {
        "count": len(ok_indices),
        "upload_avg": sum(ok_upload) / len(ok_upload),
        "upload_min": min(ok_upload),
        "upload_max": max(ok_upload),
        "upload_min_idx": ok_indices[ok_upload.index(min(ok_upload))] + 1,
        "upload_max_idx": ok_indices[ok_upload.index(max(ok_upload))] + 1,
        "approve_avg": sum(ok_approve) / len(ok_approve),
        "approve_min": min(ok_approve),
        "approve_max": max(ok_approve),
        "approve_min_idx": ok_indices[ok_approve.index(min(ok_approve))] + 1,
        "approve_max_idx": ok_indices[ok_approve.index(max(ok_approve))] + 1,
        "total_avg": sum(ok_total) / len(ok_total),
        "total_min": min(ok_total),
        "total_max": max(ok_total),
        "total_min_idx": ok_indices[ok_total.index(min(ok_total))] + 1,
        "total_max_idx": ok_indices[ok_total.index(max(ok_total))] + 1,
        # 中位數
        "upload_median": sorted(ok_upload)[len(ok_upload)//2],
        "approve_median": sorted(ok_approve)[len(ok_approve)//2],
        "total_median": sorted(ok_total)[len(ok_total)//2],
    }
else:
    result = {"count": 0}

print(json.dumps(result))
PYEOF
)

SUCCESS_COUNT=$(echo "$STATS" | python3 -c "import sys,json; print(json.load(sys.stdin)['count'])")

if [ "$SUCCESS_COUNT" -gt 0 ]; then
    echo ""
    echo -e "${BOLD}測試參數：${NC}"
    echo "  策略：$STRATEGY"
    echo "  次數：${ITERATIONS} （成功: ${GREEN}${PASS_COUNT}${NC} / 失敗: ${RED}${FAIL_COUNT}${NC}）"
    echo "  每次上傳筆數：${TOTAL_ROWS} (120萬)"
    echo "  檔案大小：$(echo "scale=2; $FILE_SIZE / 1048576" | bc) MB"
    echo ""

    # 上傳階段統計
    UPLOAD_AVG=$(echo "$STATS" | python3 -c "import sys,json; d=json.load(sys.stdin); print(f\"{d['upload_avg']:.0f}\")")
    UPLOAD_MIN=$(echo "$STATS" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d['upload_min'])")
    UPLOAD_MAX=$(echo "$STATS" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d['upload_max'])")
    UPLOAD_MED=$(echo "$STATS" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d['upload_median'])")
    UPLOAD_MIN_IDX=$(echo "$STATS" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d['upload_min_idx'])")
    UPLOAD_MAX_IDX=$(echo "$STATS" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d['upload_max_idx'])")

    # 放行階段統計
    APPROVE_AVG=$(echo "$STATS" | python3 -c "import sys,json; d=json.load(sys.stdin); print(f\"{d['approve_avg']:.0f}\")")
    APPROVE_MIN=$(echo "$STATS" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d['approve_min'])")
    APPROVE_MAX=$(echo "$STATS" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d['approve_max'])")
    APPROVE_MED=$(echo "$STATS" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d['approve_median'])")
    APPROVE_MIN_IDX=$(echo "$STATS" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d['approve_min_idx'])")
    APPROVE_MAX_IDX=$(echo "$STATS" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d['approve_max_idx'])")

    # 總計統計
    TOTAL_AVG=$(echo "$STATS" | python3 -c "import sys,json; d=json.load(sys.stdin); print(f\"{d['total_avg']:.0f}\")")
    TOTAL_MIN=$(echo "$STATS" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d['total_min'])")
    TOTAL_MAX=$(echo "$STATS" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d['total_max'])")
    TOTAL_MED=$(echo "$STATS" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d['total_median'])")
    TOTAL_MIN_IDX=$(echo "$STATS" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d['total_min_idx'])")
    TOTAL_MAX_IDX=$(echo "$STATS" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d['total_max_idx'])")

    echo -e "${CYAN}╔════════════════════╤════════════════╤════════════════╤════════════════╤════════════════╗${NC}"
    printf "${CYAN}║${NC} %-18s │ %14s │ %14s │ %14s │ %14s ${CYAN}║${NC}\n" "階段" "平均 (ms)" "最快 (ms)" "最慢 (ms)" "中位數 (ms)"
    echo -e "${CYAN}╠════════════════════╪════════════════╪════════════════╪════════════════╪════════════════╣${NC}"
    printf "${CYAN}║${NC} %-18s │ %14d │ %11s${GREEN}#%-2s${NC} │ %11s${RED}#%-2s${NC} │ %14d ${CYAN}║${NC}\n" \
        "上傳+入庫" "$UPLOAD_AVG" "$UPLOAD_MIN" "$UPLOAD_MIN_IDX" "$UPLOAD_MAX" "$UPLOAD_MAX_IDX" "$UPLOAD_MED"
    printf "${CYAN}║${NC} %-18s │ %14d │ %11s${GREEN}#%-2s${NC} │ %11s${RED}#%-2s${NC} │ %14d ${CYAN}║${NC}\n" \
        "放行+雙DB寫入" "$APPROVE_AVG" "$APPROVE_MIN" "$APPROVE_MIN_IDX" "$APPROVE_MAX" "$APPROVE_MAX_IDX" "$APPROVE_MED"
    echo -e "${CYAN}╠════════════════════╪════════════════╪════════════════╪════════════════╪════════════════╣${NC}"
    printf "${CYAN}║${NC} ${BOLD}%-18s${NC} │ ${BOLD}%14d${NC} │ %11s${GREEN}#%-2s${NC} │ %11s${RED}#%-2s${NC} │ ${BOLD}%14d${NC} ${CYAN}║${NC}\n" \
        "完整流程合計" "$TOTAL_AVG" "$TOTAL_MIN" "$TOTAL_MIN_IDX" "$TOTAL_MAX" "$TOTAL_MAX_IDX" "$TOTAL_MED"
    echo -e "${CYAN}╚════════════════════╧════════════════╧════════════════╧════════════════╧════════════════╝${NC}"

    # 換算秒
    echo ""
    echo -e "${BOLD}換算為秒：${NC}"
    printf "  完整流程 — 平均: %.2fs | 最快: %.2fs (第%s次) | 最慢: %.2fs (第%s次) | 中位數: %.2fs\n" \
        "$(echo "scale=2; $TOTAL_AVG / 1000" | bc)" \
        "$(echo "scale=2; $TOTAL_MIN / 1000" | bc)" "$TOTAL_MIN_IDX" \
        "$(echo "scale=2; $TOTAL_MAX / 1000" | bc)" "$TOTAL_MAX_IDX" \
        "$(echo "scale=2; $TOTAL_MED / 1000" | bc)"
    printf "  上傳入庫 — 平均: %.2fs | 最快: %.2fs (第%s次) | 最慢: %.2fs (第%s次)\n" \
        "$(echo "scale=2; $UPLOAD_AVG / 1000" | bc)" \
        "$(echo "scale=2; $UPLOAD_MIN / 1000" | bc)" "$UPLOAD_MIN_IDX" \
        "$(echo "scale=2; $UPLOAD_MAX / 1000" | bc)" "$UPLOAD_MAX_IDX"
    printf "  放行寫入 — 平均: %.2fs | 最快: %.2fs (第%s次) | 最慢: %.2fs (第%s次)\n" \
        "$(echo "scale=2; $APPROVE_AVG / 1000" | bc)" \
        "$(echo "scale=2; $APPROVE_MIN / 1000" | bc)" "$APPROVE_MIN_IDX" \
        "$(echo "scale=2; $APPROVE_MAX / 1000" | bc)" "$APPROVE_MAX_IDX"
else
    echo -e "${RED}所有測試均失敗！${NC}"
fi

echo ""
echo -e "${GREEN}壓力測試完成。${NC}"
