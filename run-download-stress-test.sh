#!/usr/bin/env bash
#
# 原檔下載壓力測試：3 種業務同時執行「上傳 → 放行 → 下載」完整流程
#
# 下載流程（GET /api/todo/{caseId}/download）：
#   1. 從 biz_x_detail 讀取 120 萬筆資料
#   2. 組成 pipe-separated TXT（~156 MB）
#   3. 以 BLOB 儲存至 download_file 表
#   4. 將 TXT bytes 回傳給前端
#
# 使用方式：
#   bash run-download-stress-test.sh             # 策略 1，3 次迭代
#   bash run-download-stress-test.sh 3 5         # 策略 3，5 次迭代
#

set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$PROJECT_DIR"

JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk}"
JAVA="$JAVA_HOME/bin/java"
MVN="mvn"

# 下載需要更多 heap（三個並行下載各約 320 MB）
JVM_OPTS="-Xms512m -Xmx6g -XX:+UseG1GC -XX:ActiveProcessorCount=4 -XX:MaxGCPauseMillis=200"
DATA_DIR="$PROJECT_DIR/stress-test-data"
DATA_FILE="$DATA_DIR/test_data_1200000.dat"
SPRING_JAR="$PROJECT_DIR/target/spring-oracle-stress-test-1.0-SNAPSHOT.jar"
PORT=8080
BASE_URL="http://localhost:$PORT"

STRATEGY=${1:-1}
ITERATIONS=${2:-3}
TOTAL_ROWS=1200000
BIZ_TYPES=("BIZ_A" "BIZ_B" "BIZ_C")

# 下載的 TXT 檔案儲存目錄
DOWNLOAD_OUT_DIR="$PROJECT_DIR/downloaded-files"
mkdir -p "$DOWNLOAD_OUT_DIR"

# 顏色
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[0;33m'
CYAN='\033[0;36m'
BOLD='\033[1m'
NC='\033[0m'

echo -e "${CYAN}╔══════════════════════════════════════════════════════════════════════╗${NC}"
echo -e "${CYAN}║   原檔下載壓力測試：上傳 → 放行 → 下載（BIZ_A / BIZ_B / BIZ_C）   ║${NC}"
echo -e "${CYAN}║   策略: $STRATEGY | 迭代: $ITERATIONS | 每業務每次: ${TOTAL_ROWS} 筆 (120萬)      ║${NC}"
echo -e "${CYAN}╚══════════════════════════════════════════════════════════════════════╝${NC}"

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

# ===== 準備測試資料 =====
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
$JAVA $JVM_OPTS -jar "$SPRING_JAR" --server.port=$PORT > /tmp/spring-download-test.log 2>&1 &
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
        tail -30 /tmp/spring-download-test.log
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

# ===== 清理 DB 函式（含 download_file）=====
cleanup_db() {
    podman exec oracle-xe bash -c "sqlplus -s '/ as sysdba' << 'INNEREOF'
ALTER SESSION SET CONTAINER=FREEPDB1;
SET HEADING OFF FEEDBACK OFF
BEGIN EXECUTE IMMEDIATE 'TRUNCATE TABLE stresstest.biz_a_detail DROP ALL STORAGE'; EXCEPTION WHEN OTHERS THEN NULL; END;
/
BEGIN EXECUTE IMMEDIATE 'TRUNCATE TABLE stresstest.biz_b_detail DROP ALL STORAGE'; EXCEPTION WHEN OTHERS THEN NULL; END;
/
BEGIN EXECUTE IMMEDIATE 'TRUNCATE TABLE stresstest.biz_c_detail DROP ALL STORAGE'; EXCEPTION WHEN OTHERS THEN NULL; END;
/
BEGIN EXECUTE IMMEDIATE 'TRUNCATE TABLE stresstest.download_file DROP ALL STORAGE'; EXCEPTION WHEN OTHERS THEN NULL; END;
/
BEGIN EXECUTE IMMEDIATE 'TRUNCATE TABLE stresstest.approved_file_detail DROP ALL STORAGE'; EXCEPTION WHEN OTHERS THEN NULL; END;
/
BEGIN EXECUTE IMMEDIATE 'TRUNCATE TABLE stresstest.file_detail DROP ALL STORAGE'; EXCEPTION WHEN OTHERS THEN NULL; END;
/
BEGIN EXECUTE IMMEDIATE 'TRUNCATE TABLE stresstest.jpa_records DROP ALL STORAGE'; EXCEPTION WHEN OTHERS THEN NULL; END;
/
BEGIN DELETE FROM stresstest.upload_case; COMMIT; EXCEPTION WHEN OTHERS THEN NULL; END;
/
BEGIN DELETE FROM stresstest.file_master; COMMIT; EXCEPTION WHEN OTHERS THEN NULL; END;
/
EXIT;
INNEREOF" > /dev/null 2>&1
}

# ===== 單業務完整流程：上傳 → 放行 → 下載 =====
# 輸出格式：BIZ_TYPE UPLOAD_MS APPROVE_MS DOWNLOAD_MS CASE_ID STATUS
run_biz_flow() {
    local BIZ="$1"
    local RESULT_FILE="$2"

    # Step 1: 上傳
    local UPLOAD_START
    UPLOAD_START=$(python3 -c "import time; print(int(time.time()*1000))")
    local UPLOAD_RESP
    UPLOAD_RESP=$(curl -s -w "\n%{http_code}" \
        -X POST "$BASE_URL/api/upload?strategy=$STRATEGY&bizType=$BIZ" \
        -F "file=@$DATA_FILE" \
        --max-time 1200)
    local UPLOAD_END
    UPLOAD_END=$(python3 -c "import time; print(int(time.time()*1000))")
    local UPLOAD_MS=$(( UPLOAD_END - UPLOAD_START ))

    local UPLOAD_HTTP
    UPLOAD_HTTP=$(echo "$UPLOAD_RESP" | tail -1)
    local UPLOAD_BODY
    UPLOAD_BODY=$(echo "$UPLOAD_RESP" | sed '$d')

    if [ "$UPLOAD_HTTP" != "200" ]; then
        echo "$BIZ $UPLOAD_MS 0 0 N/A UPLOAD_FAIL" > "$RESULT_FILE"
        return
    fi

    local CASE_ID
    CASE_ID=$(echo "$UPLOAD_BODY" | python3 -c "import sys,json; print(json.load(sys.stdin).get('caseId','?'))" 2>/dev/null || echo "?")

    # Step 2: 放行
    local APPROVE_START
    APPROVE_START=$(python3 -c "import time; print(int(time.time()*1000))")
    local APPROVE_RESP
    APPROVE_RESP=$(curl -s -w "\n%{http_code}" \
        -X POST "$BASE_URL/api/todo/$CASE_ID/approve" \
        --max-time 1200)
    local APPROVE_END
    APPROVE_END=$(python3 -c "import time; print(int(time.time()*1000))")
    local APPROVE_MS=$(( APPROVE_END - APPROVE_START ))

    local APPROVE_HTTP
    APPROVE_HTTP=$(echo "$APPROVE_RESP" | tail -1)

    if [ "$APPROVE_HTTP" != "200" ]; then
        echo "$BIZ $UPLOAD_MS $APPROVE_MS 0 $CASE_ID APPROVE_FAIL" > "$RESULT_FILE"
        return
    fi

    # Step 3: 下載（GET /api/todo/{id}/download，TXT 儲存至 downloaded-files/）
    local OUT_FILE="$DOWNLOAD_OUT_DIR/case_${CASE_ID}_${BIZ}.txt"
    local DOWNLOAD_START
    DOWNLOAD_START=$(python3 -c "import time; print(int(time.time()*1000))")
    local DOWNLOAD_HTTP
    DOWNLOAD_HTTP=$(curl -s -o "$OUT_FILE" -w "%{http_code}" \
        -X GET "$BASE_URL/api/todo/$CASE_ID/download" \
        --max-time 1200)
    local DOWNLOAD_END
    DOWNLOAD_END=$(python3 -c "import time; print(int(time.time()*1000))")
    local DOWNLOAD_MS=$(( DOWNLOAD_END - DOWNLOAD_START ))

    if [ "$DOWNLOAD_HTTP" != "200" ]; then
        rm -f "$OUT_FILE"
        echo "$BIZ $UPLOAD_MS $APPROVE_MS $DOWNLOAD_MS $CASE_ID DOWNLOAD_FAIL" > "$RESULT_FILE"
        return
    fi

    # 驗證下載的檔案
    local FILE_SIZE
    FILE_SIZE=$(stat -f%z "$OUT_FILE" 2>/dev/null || stat -c%s "$OUT_FILE" 2>/dev/null || echo 0)
    local FILE_LINES
    FILE_LINES=$(wc -l < "$OUT_FILE" 2>/dev/null || echo 0)
    local HEADER_LINE
    HEADER_LINE=$(head -1 "$OUT_FILE" 2>/dev/null || echo "")

    echo "$BIZ $UPLOAD_MS $APPROVE_MS $DOWNLOAD_MS $CASE_ID OK $FILE_SIZE $FILE_LINES $OUT_FILE" > "$RESULT_FILE"
}

# ===== 結果收集（平坦變數，相容 bash 3.2）=====
BIZ_A_UPLOAD_TIMES="";   BIZ_A_APPROVE_TIMES="";   BIZ_A_DOWNLOAD_TIMES="";   BIZ_A_TOTAL_TIMES=""
BIZ_B_UPLOAD_TIMES="";   BIZ_B_APPROVE_TIMES="";   BIZ_B_DOWNLOAD_TIMES="";   BIZ_B_TOTAL_TIMES=""
BIZ_C_UPLOAD_TIMES="";   BIZ_C_APPROVE_TIMES="";   BIZ_C_DOWNLOAD_TIMES="";   BIZ_C_TOTAL_TIMES=""
BIZ_A_PASS=0; BIZ_A_FAIL=0
BIZ_B_PASS=0; BIZ_B_FAIL=0
BIZ_C_PASS=0; BIZ_C_FAIL=0
ITER_PASS=0
ITER_FAIL=0

TMPDIR_RESULTS=$(mktemp -d)

echo -e "\n${CYAN}════════════════════════════════════════════════════════════════════════${NC}"
echo -e "${CYAN}  開始並行壓力測試：${ITERATIONS} 次迭代，每次 3 業務同時進行（上傳→放行→下載）${NC}"
echo -e "${CYAN}════════════════════════════════════════════════════════════════════════${NC}\n"

printf "${BOLD}%-6s │ %-6s │ %11s │ %11s │ %11s │ %11s │ %10s │ %s${NC}\n" \
    "# 次" "業務" "上傳(ms)" "放行(ms)" "下載(ms)" "總計(ms)" "案件ID" "狀態"
echo "───────┼────────┼─────────────┼─────────────┼─────────────┼─────────────┼────────────┼──────────"

for (( i=1; i<=ITERATIONS; i++ )); do

    cleanup_db

    PID_A=""; PID_B=""; PID_C=""
    FILE_A="$TMPDIR_RESULTS/BIZ_A_${i}.txt"
    FILE_B="$TMPDIR_RESULTS/BIZ_B_${i}.txt"
    FILE_C="$TMPDIR_RESULTS/BIZ_C_${i}.txt"

    run_biz_flow "BIZ_A" "$FILE_A" & PID_A=$!
    run_biz_flow "BIZ_B" "$FILE_B" & PID_B=$!
    run_biz_flow "BIZ_C" "$FILE_C" & PID_C=$!

    wait "$PID_A" 2>/dev/null || true
    wait "$PID_B" 2>/dev/null || true
    wait "$PID_C" 2>/dev/null || true

    ITER_OK=true
    for PAIR in "BIZ_A:$FILE_A" "BIZ_B:$FILE_B" "BIZ_C:$FILE_C"; do
        BIZ="${PAIR%%:*}"
        RESULT_FILE="${PAIR#*:}"

        if [ ! -f "$RESULT_FILE" ]; then
            printf "%-6s │ %-6s │ %11s │ %11s │ %11s │ %11s │ %10s │ ${RED}%s${NC}\n" \
                "$i/$ITERATIONS" "$BIZ" "-" "-" "-" "-" "N/A" "NO_RESULT"
            eval "${BIZ//-/_}_FAIL=\$(( \${${BIZ//-/_}_FAIL} + 1 ))"
            ITER_OK=false
            continue
        fi

        read -r RES_BIZ RES_UPLOAD RES_APPROVE RES_DOWNLOAD RES_CASE RES_STATUS RES_FILE_SIZE RES_FILE_LINES RES_FILE_PATH < "$RESULT_FILE"
        RES_TOTAL=$(( RES_UPLOAD + RES_APPROVE + RES_DOWNLOAD ))
        VAR_PREFIX="${BIZ//-/_}"

        if [ "$RES_STATUS" = "OK" ]; then
            STATUS_COLOR="${GREEN}"
            eval "${VAR_PREFIX}_PASS=\$(( \${${VAR_PREFIX}_PASS} + 1 ))"
            eval "${VAR_PREFIX}_UPLOAD_TIMES=\"\${${VAR_PREFIX}_UPLOAD_TIMES}${RES_UPLOAD},\""
            eval "${VAR_PREFIX}_APPROVE_TIMES=\"\${${VAR_PREFIX}_APPROVE_TIMES}${RES_APPROVE},\""
            eval "${VAR_PREFIX}_DOWNLOAD_TIMES=\"\${${VAR_PREFIX}_DOWNLOAD_TIMES}${RES_DOWNLOAD},\""
            eval "${VAR_PREFIX}_TOTAL_TIMES=\"\${${VAR_PREFIX}_TOTAL_TIMES}${RES_TOTAL},\""
        else
            STATUS_COLOR="${RED}"
            eval "${VAR_PREFIX}_FAIL=\$(( \${${VAR_PREFIX}_FAIL} + 1 ))"
            ITER_OK=false
        fi

        printf "%-6s │ %-6s │ %11d │ %11d │ %11d │ %11d │ %10s │ ${STATUS_COLOR}%s${NC}\n" \
            "$i/$ITERATIONS" "$BIZ" "$RES_UPLOAD" "$RES_APPROVE" "$RES_DOWNLOAD" "$RES_TOTAL" "$RES_CASE" "$RES_STATUS"
        if [ "$RES_STATUS" = "OK" ] && [ -n "${RES_FILE_PATH:-}" ]; then
            SIZE_MB=$(python3 -c "print('%.2f' % (${RES_FILE_SIZE:-0}/1048576))")
            printf "       │        │             │             │             │             │            │ ${CYAN}  ↳ 檔案: %s${NC}\n" \
                "$(basename "$RES_FILE_PATH") (${SIZE_MB} MB, ${RES_FILE_LINES} 行)"
        fi
    done

    if [ "$ITER_OK" = true ]; then
        ITER_PASS=$(( ITER_PASS + 1 ))
    else
        ITER_FAIL=$(( ITER_FAIL + 1 ))
    fi
    echo "───────┼────────┼─────────────┼─────────────┼─────────────┼─────────────┼────────────┼──────────"
done

rm -rf "$TMPDIR_RESULTS"

echo -e "\n${CYAN}下載的 TXT 檔案儲存於: ${BOLD}$DOWNLOAD_OUT_DIR${NC}"
ls -lh "$DOWNLOAD_OUT_DIR"/*.txt 2>/dev/null | awk '{print "  " $5 "  " $9}' || true

# ===== 統計 =====
echo ""
echo -e "${CYAN}════════════════════════════════════════════════════════════════════════${NC}"
echo -e "${CYAN}  壓力測試結果統計（各業務獨立）${NC}"
echo -e "${CYAN}════════════════════════════════════════════════════════════════════════${NC}"
printf "${BOLD}%-8s │ %5s │ %5s │ %10s │ %10s │ %10s │ %10s │ %10s │ %10s${NC}\n" \
    "業務" "成功" "失敗" "上傳平均" "放行平均" "下載平均" "總計平均" "最快(ms)" "最慢(ms)"
echo "─────────┼───────┼───────┼────────────┼────────────┼────────────┼────────────┼────────────┼────────────"

for BIZ in "${BIZ_TYPES[@]}"; do
    VAR_PREFIX="${BIZ//-/_}"
    eval "PASS=\${${VAR_PREFIX}_PASS}"
    eval "FAIL=\${${VAR_PREFIX}_FAIL}"
    eval "UCSVRAW=\${${VAR_PREFIX}_UPLOAD_TIMES}"
    eval "ACSVRAW=\${${VAR_PREFIX}_APPROVE_TIMES}"
    eval "DCSVRAW=\${${VAR_PREFIX}_DOWNLOAD_TIMES}"
    eval "TCSVRAW=\${${VAR_PREFIX}_TOTAL_TIMES}"

    if [ "$PASS" -gt 0 ]; then
        STATS=$(python3 -c "
upload   = [int(x) for x in '${UCSVRAW%,}'.split(',') if x]
approve  = [int(x) for x in '${ACSVRAW%,}'.split(',') if x]
download = [int(x) for x in '${DCSVRAW%,}'.split(',') if x]
total    = [int(x) for x in '${TCSVRAW%,}'.split(',') if x]
if upload:
    print(int(sum(upload)/len(upload)), int(sum(approve)/len(approve)),
          int(sum(download)/len(download)),
          int(sum(total)/len(total)), min(total), max(total))
else:
    print(0,0,0,0,0,0)
")
        read -r U_AVG A_AVG D_AVG T_AVG T_MIN T_MAX <<< "$STATS"
        printf "%-8s │ %5d │ %5d │ %10d │ %10d │ %10d │ %10d │ %10d │ %10d\n" \
            "$BIZ" "$PASS" "$FAIL" "$U_AVG" "$A_AVG" "$D_AVG" "$T_AVG" "$T_MIN" "$T_MAX"
    else
        printf "%-8s │ %5d │ %5d │ %10s │ %10s │ %10s │ %10s │ %10s │ %10s\n" \
            "$BIZ" "$PASS" "$FAIL" "-" "-" "-" "-" "-" "-"
    fi
done

echo ""
echo -e "${BOLD}迭代總計：${GREEN}成功 $ITER_PASS 次（全部業務均 OK）${NC}│ ${RED}失敗 $ITER_FAIL 次${NC}"
echo ""
