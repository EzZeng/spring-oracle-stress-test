#!/bin/bash
#
# Spring Boot + Oracle 壓力測試腳本
# 使用方式：
#   bash run-spring-test.sh              # 執行全部 6 個策略
#   bash run-spring-test.sh 2 6          # 只執行策略 2 和 6
#

set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$PROJECT_DIR"

JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk}"
JAVA="$JAVA_HOME/bin/java"
MVN="mvn"

JVM_OPTS="-Xms512m -Xmx4g -XX:+UseG1GC -XX:ActiveProcessorCount=4 -XX:MaxGCPauseMillis=200"
DATA_DIR="$PROJECT_DIR/stress-test-data"
DATA_FILE="$DATA_DIR/test_data.dat"
SPRING_JAR="$PROJECT_DIR/target/spring-oracle-stress-test-1.0-SNAPSHOT.jar"
PORT=8080

# 顏色
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[0;33m'
CYAN='\033[0;36m'
NC='\033[0m'

echo -e "${CYAN}╔══════════════════════════════════════════════════════════════╗${NC}"
echo -e "${CYAN}║   Spring Boot 2.7.18 + Oracle DB 壓力測試                  ║${NC}"
echo -e "${CYAN}║   100萬筆 CSV 檔案上傳 (HTTP POST multipart)               ║${NC}"
echo -e "${CYAN}║   JVM: $JVM_OPTS${NC}"
echo -e "${CYAN}╚══════════════════════════════════════════════════════════════╝${NC}"

# ===== 檢查 Oracle 容器 =====
echo -e "\n${YELLOW}═══ 檢查 Oracle 容器 ═══${NC}"
if podman ps --filter name=oracle-xe --format "{{.Names}}" 2>/dev/null | grep -q oracle-xe; then
    echo -e "${GREEN}Oracle 容器正在運行${NC}"
else
    echo -e "${YELLOW}啟動 Oracle 容器...${NC}"
    podman start oracle-xe 2>/dev/null || {
        echo -e "${RED}Oracle 容器不存在，請先執行:${NC}"
        echo "  podman run -d --name oracle-xe -p 1521:1521 -e ORACLE_PASSWORD=test1234 -e APP_USER=stresstest -e APP_USER_PASSWORD=stresstest gvenzl/oracle-free:23-slim-faststart"
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

# ===== 產生測試資料 =====
echo -e "\n${YELLOW}═══ 準備測試資料 ═══${NC}"
mkdir -p "$DATA_DIR"
if [ -f "$DATA_FILE" ]; then
    FILE_SIZE=$(stat -f%z "$DATA_FILE" 2>/dev/null || stat -c%s "$DATA_FILE" 2>/dev/null)
    echo "測試檔案已存在: $DATA_FILE ($(echo "scale=2; $FILE_SIZE / 1048576" | bc) MB)"
else
    echo "產生 100 萬筆測試資料..."
    "$MVN" -q compile exec:java \
        -Dexec.mainClass="com.stresstest.spring.service.FileGenerator" \
        -Dexec.args="$DATA_DIR" 2>&1 || {
        echo -e "${RED}無法產生測試資料${NC}"
        exit 1
    }
fi

# ===== 編譯 =====
echo -e "\n${YELLOW}═══ 編譯 Spring Boot 專案 ═══${NC}"
"$MVN" -q clean package -DskipTests 2>&1
echo -e "${GREEN}編譯完成${NC}"

# ===== 決定要跑的策略 =====
if [ $# -gt 0 ]; then
    STRATEGIES="$@"
else
    STRATEGIES="1 2 3 4 5 6"
fi

# ===== 結果收集 =====
declare -a RESULTS_NAME
declare -a RESULTS_TIME
declare -a RESULTS_PEAK
declare -a RESULTS_THROUGHPUT
declare -a RESULTS_DBCOUNT
declare -a RESULTS_STATUS
IDX=0

for STRAT in $STRATEGIES; do
    echo -e "\n${CYAN}════════════════════════════════════════════════════════════════${NC}"
    echo -e "${CYAN}  策略 $STRAT${NC}"
    echo -e "${CYAN}════════════════════════════════════════════════════════════════${NC}"

    # 啟動 Spring Boot
    echo -e "${YELLOW}啟動 Spring Boot...${NC}"
    $JAVA $JVM_OPTS -jar "$SPRING_JAR" --server.port=$PORT > /tmp/spring-stress-test.log 2>&1 &
    SPRING_PID=$!

    # 等待啟動
    for i in $(seq 1 30); do
        if curl -s "http://localhost:$PORT/api/strategies" > /dev/null 2>&1; then
            echo -e "${GREEN}Spring Boot 已啟動 (PID: $SPRING_PID)${NC}"
            break
        fi
        if ! kill -0 $SPRING_PID 2>/dev/null; then
            echo -e "${RED}Spring Boot 啟動失敗！${NC}"
            cat /tmp/spring-stress-test.log | tail -20
            exit 1
        fi
        sleep 1
    done

    # 上傳檔案
    echo "上傳 $(stat -f%z "$DATA_FILE" 2>/dev/null || stat -c%s "$DATA_FILE" 2>/dev/null) bytes..."
    RESPONSE=$(curl -s -w "\n%{http_code}" \
        -X POST "http://localhost:$PORT/api/upload?strategy=$STRAT" \
        -F "file=@$DATA_FILE" \
        --max-time 600)

    HTTP_CODE=$(echo "$RESPONSE" | tail -1)
    BODY=$(echo "$RESPONSE" | sed '$d')

    if [ "$HTTP_CODE" = "200" ]; then
        echo -e "${GREEN}HTTP 200 OK${NC}"
        echo "$BODY" | python3 -m json.tool 2>/dev/null || echo "$BODY"

        SNAME=$(echo "$BODY" | python3 -c "import sys,json; print(json.load(sys.stdin)['strategy'])" 2>/dev/null || echo "?")
        STIME=$(echo "$BODY" | python3 -c "import sys,json; print(json.load(sys.stdin)['elapsedSec'])" 2>/dev/null || echo "?")
        SPEAK=$(echo "$BODY" | python3 -c "import sys,json; print(json.load(sys.stdin)['peakMemoryMB'])" 2>/dev/null || echo "?")
        STHRU=$(echo "$BODY" | python3 -c "import sys,json; print(json.load(sys.stdin)['throughput'])" 2>/dev/null || echo "?")
        SDBCN=$(echo "$BODY" | python3 -c "import sys,json; print(json.load(sys.stdin)['dbCount'])" 2>/dev/null || echo "?")
        SSTAT="COMMIT"
    else
        echo -e "${RED}HTTP $HTTP_CODE${NC}"
        echo "$BODY" | python3 -m json.tool 2>/dev/null || echo "$BODY"
        SNAME="策略$STRAT"
        STIME="?"
        SPEAK="?"
        STHRU="?"
        SDBCN="?"
        SSTAT="ROLLBACK"
    fi

    RESULTS_NAME[$IDX]="$SNAME"
    RESULTS_TIME[$IDX]="$STIME"
    RESULTS_PEAK[$IDX]="$SPEAK"
    RESULTS_THROUGHPUT[$IDX]="$STHRU"
    RESULTS_DBCOUNT[$IDX]="$SDBCN"
    RESULTS_STATUS[$IDX]="$SSTAT"
    IDX=$((IDX + 1))

    # 停止 Spring Boot
    echo -e "${YELLOW}停止 Spring Boot...${NC}"
    kill $SPRING_PID 2>/dev/null || true
    wait $SPRING_PID 2>/dev/null || true
    sleep 2
done

# ===== 彙總表格 =====
echo -e "\n${CYAN}╔══════════════════════════════════════════════════════════════════════════════════════════════════════════╗${NC}"
echo -e "${CYAN}║                    Spring Boot 2.7.18 + Oracle DB 壓力測試結果 (JVM: 4GB, CPU: 4)                       ║${NC}"
echo -e "${CYAN}╠══════════════════════════════════════════════════════╤══════════╤══════════════╤═══════════╤══════════════╣${NC}"
printf "${CYAN}║${NC} %-50s │ %8s │ %12s │ %9s │ %12s ${CYAN}║${NC}\n" "策略" "耗時" "峰值記憶體" "DB筆數" "狀態"
echo -e "${CYAN}╠══════════════════════════════════════════════════════╪══════════╪══════════════╪═══════════╪══════════════╣${NC}"
for (( i=0; i<IDX; i++ )); do
    STATUS_COLOR="${GREEN}"
    if [ "${RESULTS_STATUS[$i]}" = "ROLLBACK" ]; then
        STATUS_COLOR="${RED}"
    fi
    printf "${CYAN}║${NC} %-50s │ %6ss │ %9s MB │ %9s │ ${STATUS_COLOR}%12s${NC} ${CYAN}║${NC}\n" \
        "${RESULTS_NAME[$i]}" "${RESULTS_TIME[$i]}" "${RESULTS_PEAK[$i]}" "${RESULTS_DBCOUNT[$i]}" "${RESULTS_STATUS[$i]}"
done
echo -e "${CYAN}╚══════════════════════════════════════════════════════╧══════════╧══════════════╧═══════════╧══════════════╝${NC}"

echo -e "\n${GREEN}測試完成。${NC}"
echo "Spring Boot log: /tmp/spring-stress-test.log"
