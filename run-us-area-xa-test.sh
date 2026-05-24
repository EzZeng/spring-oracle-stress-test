#!/usr/bin/env bash
#
# US Area 跨區上傳 XA 驗證：
#   1. 成功上傳：file_master/file_detail + upload_case + US result + GLOBAL log 一起 commit
#   2. simulateFailure=afterUs：US result 寫入後故意失敗，全部 rollback
#   3. simulateFailure=afterGlobal：GLOBAL log 寫入後故意失敗，全部 rollback
#
# 使用方式：
#   bash run-us-area-xa-test.sh              # 策略 2，10,000 筆
#   bash run-us-area-xa-test.sh 1 50000      # 策略 1，50,000 筆
#

set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$PROJECT_DIR"

JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk}"
JAVA="$JAVA_HOME/bin/java"
MVN="mvn"

JVM_OPTS="-Xms512m -Xmx3g -XX:+UseG1GC -XX:ActiveProcessorCount=4 -XX:MaxGCPauseMillis=200"
SPRING_JAR="$PROJECT_DIR/target/spring-oracle-stress-test-1.0-SNAPSHOT.jar"
DATA_DIR="$PROJECT_DIR/stress-test-data"
PORT=8080
BASE_URL="http://localhost:$PORT"

STRATEGY=${1:-2}
TOTAL_ROWS=${2:-10000}
DATA_FILE="$DATA_DIR/test_data_${TOTAL_ROWS}.dat"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[0;33m'
CYAN='\033[0;36m'
BOLD='\033[1m'
NC='\033[0m'

echo -e "${CYAN}╔══════════════════════════════════════════════════════════════╗${NC}"
echo -e "${CYAN}║   US Area 跨區上傳 JTA/XA 驗證                              ║${NC}"
echo -e "${CYAN}║   策略: $STRATEGY | 筆數: $TOTAL_ROWS                         ║${NC}"
echo -e "${CYAN}╚══════════════════════════════════════════════════════════════╝${NC}"

echo -e "\n${YELLOW}═══ 檢查 Oracle 容器 ═══${NC}"
if podman ps --filter name=oracle-xe --format "{{.Names}}" 2>/dev/null | grep -q oracle-xe; then
    echo -e "${GREEN}Oracle 容器正在運行${NC}"
else
    echo -e "${YELLOW}啟動 Oracle 容器...${NC}"
    podman start oracle-xe 2>/dev/null || {
        echo -e "${RED}Oracle 容器不存在，請先建立${NC}"
        exit 1
    }
fi

echo -e "\n${YELLOW}═══ 準備測試資料 ═══${NC}"
mkdir -p "$DATA_DIR"
if [ ! -f "$DATA_FILE" ]; then
    "$MVN" -q compile exec:java \
        -Dexec.mainClass="com.stresstest.spring.service.FileGenerator" \
        -Dexec.args="$DATA_DIR $TOTAL_ROWS"
fi
echo -e "${GREEN}測試檔案: $DATA_FILE${NC}"

echo -e "\n${YELLOW}═══ 編譯 Spring Boot 專案 ═══${NC}"
"$MVN" -q clean package -DskipTests
echo -e "${GREEN}編譯完成${NC}"

echo -e "\n${YELLOW}═══ 啟動 Spring Boot ═══${NC}"
$JAVA $JVM_OPTS -jar "$SPRING_JAR" --server.port=$PORT > /tmp/spring-us-area-xa-test.log 2>&1 &
SPRING_PID=$!

cleanup() {
    echo -e "\n${YELLOW}停止 Spring Boot (PID: $SPRING_PID)...${NC}"
    kill $SPRING_PID 2>/dev/null || true
    wait $SPRING_PID 2>/dev/null || true
}
trap cleanup EXIT

STARTED=false
for i in $(seq 1 60); do
    if curl -s "$BASE_URL/api/strategies" > /dev/null 2>&1; then
        STARTED=true
        echo -e "${GREEN}Spring Boot 已啟動 (PID: $SPRING_PID)${NC}"
        break
    fi
    if ! kill -0 $SPRING_PID 2>/dev/null; then
        echo -e "${RED}Spring Boot 啟動失敗！${NC}"
        tail -50 /tmp/spring-us-area-xa-test.log
        exit 1
    fi
    sleep 1
done
if [ "$STARTED" = false ]; then
    echo -e "${RED}Spring Boot 啟動超時${NC}"
    tail -50 /tmp/spring-us-area-xa-test.log
    exit 1
fi

cleanup_db() {
    podman exec oracle-xe bash -c "sqlplus -s '/ as sysdba' << 'INNEREOF'
ALTER SESSION SET CONTAINER=FREEPDB1;
SET HEADING OFF FEEDBACK OFF
BEGIN EXECUTE IMMEDIATE 'TRUNCATE TABLE stresstest.us_upload_result DROP ALL STORAGE'; EXCEPTION WHEN OTHERS THEN NULL; END;
/
BEGIN EXECUTE IMMEDIATE 'TRUNCATE TABLE stresstest.global_user_operation_log DROP ALL STORAGE'; EXCEPTION WHEN OTHERS THEN NULL; END;
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

summary_counts() {
    podman exec oracle-xe bash -c "sqlplus -s '/ as sysdba' << 'INNEREOF'
SET HEADING OFF FEEDBACK OFF PAGESIZE 0 VERIFY OFF ECHO OFF
ALTER SESSION SET CONTAINER=FREEPDB1;
SELECT
  (SELECT COUNT(*) FROM stresstest.us_upload_result) || ' ' ||
  (SELECT COUNT(*) FROM stresstest.global_user_operation_log) || ' ' ||
  (SELECT COUNT(*) FROM stresstest.upload_case) || ' ' ||
  (SELECT COUNT(*) FROM stresstest.file_master) || ' ' ||
  (SELECT COUNT(*) FROM stresstest.file_detail) || ' ' ||
  (SELECT COUNT(*) FROM stresstest.jpa_records)
FROM dual;
EXIT;
INNEREOF" 2>/dev/null | tr -d '\r' | awk '/^[[:space:]]*[0-9]+[[:space:]]+[0-9]+[[:space:]]+[0-9]+[[:space:]]+[0-9]+[[:space:]]+[0-9]+[[:space:]]+[0-9]+[[:space:]]*$/ {gsub(/^[[:space:]]+|[[:space:]]+$/, ""); print; exit}'
}

run_case() {
    local MODE="$1"
    local EXPECT_HTTP="$2"
    local LABEL="$3"

    local BEFORE
    BEFORE="$(summary_counts)"

    local RESP
    RESP=$(curl -s -w "\n%{http_code}" \
        -X POST "$BASE_URL/api/area/us/upload?userId=u001&strategy=$STRATEGY&simulateFailure=$MODE" \
        -F "file=@$DATA_FILE" \
        --max-time 1200)
    local HTTP
    HTTP=$(echo "$RESP" | tail -1)
    local BODY
    BODY=$(echo "$RESP" | sed '$d')
    local AFTER
    AFTER="$(summary_counts)"

    printf "%-24s │ HTTP=%-3s │ before=[%s] │ after=[%s]" "$LABEL" "$HTTP" "$BEFORE" "$AFTER"
    if [ "$HTTP" = "$EXPECT_HTTP" ]; then
        echo -e " │ ${GREEN}OK${NC}"
    else
        echo -e " │ ${RED}FAIL${NC}"
        echo "$BODY"
        return 1
    fi

    if [ "$EXPECT_HTTP" = "200" ]; then
        echo "$BODY" | python3 -c "import sys,json; d=json.load(sys.stdin); print('  requestId=%s caseId=%s masterId=%s rows=%s' % (d.get('requestId'), d.get('caseId'), d.get('masterId'), d.get('rowsProcessed')))"
    elif [ "$BEFORE" != "$AFTER" ]; then
        echo -e "${RED}  rollback 驗證失敗：失敗案例前後筆數不同${NC}"
        echo "$BODY"
        return 1
    else
        echo -e "${GREEN}  rollback 驗證通過：失敗案例前後筆數相同${NC}"
    fi
}

echo -e "\n${YELLOW}═══ 清理資料 ═══${NC}"
cleanup_db
echo -e "${GREEN}清理完成，初始筆數: [$(summary_counts)]${NC}"

echo -e "\n${CYAN}${BOLD}Case                         │ HTTP  │ DB counts: us global case master detail jpa${NC}"
echo "─────────────────────────────┼───────┼────────────────────────────────────────"
run_case "none" "200" "success commit"
run_case "afterUs" "500" "rollback after US"
run_case "afterGlobal" "500" "rollback after GLOBAL"

echo -e "\n${GREEN}US Area XA 驗證完成${NC}"
