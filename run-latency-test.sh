#!/bin/bash
#
# 網路延遲模擬測試腳本
# 模擬 DB 與 AP 服務分別在不同網段時，各策略的寫入效能差異
#
# 使用方式：
#   bash run-latency-test.sh              # 預設策略 6（推薦JDBC）對比 0/1/2/5/10/20ms
#   bash run-latency-test.sh 6 9          # 測試策略 6 和 9
#   bash run-latency-test.sh all          # 測試所有策略（耗時長）
#
# 延遲對照：
#   0ms  = 同機（localhost / 同 VLAN）
#   1ms  = 同機房跨 VLAN
#   2ms  = 同機房不同子網段
#   5ms  = 跨機房（同 Region）
#   10ms = 跨可用區（AZ）
#   20ms = 跨 Region

set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$PROJECT_DIR"

JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk}"
JAVA="$JAVA_HOME/bin/java"
MVN="mvn"

JVM_OPTS="-Xms512m -Xmx4g -XX:+UseG1GC -XX:ActiveProcessorCount=4"
DATA_DIR="$PROJECT_DIR/stress-test-data"
DATA_FILE="$DATA_DIR/test_data.dat"
SPRING_JAR="$PROJECT_DIR/target/spring-oracle-stress-test-1.0-SNAPSHOT.jar"
PORT=8080

LATENCY_VALUES="0 1 2 5 10 20"

# 顏色
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[0;33m'
CYAN='\033[0;36m'
BOLD='\033[1m'
NC='\033[0m'

echo -e "${CYAN}╔══════════════════════════════════════════════════════════════════╗${NC}"
echo -e "${CYAN}║   DB 跨網段延遲模擬測試                                        ║${NC}"
echo -e "${CYAN}║   透過 JDBC Proxy 在每次 DB round-trip 注入固定延遲             ║${NC}"
echo -e "${CYAN}║   測試延遲值: ${LATENCY_VALUES}  (ms per round-trip)${NC}"
echo -e "${CYAN}║   JVM: $JVM_OPTS${NC}"
echo -e "${CYAN}╚══════════════════════════════════════════════════════════════════╝${NC}"

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
    if [ "$1" = "all" ]; then
        STRATEGIES=$(seq 1 13)
    else
        STRATEGIES="$@"
    fi
else
    # 預設跑推薦的 JDBC 和 JPA 各一個
    STRATEGIES="6 9"
fi

# ===== 結果收集 =====
RESULT_FILE="/tmp/latency-test-results.txt"
echo -n "" > "$RESULT_FILE"

start_spring() {
    echo -e "${YELLOW}啟動 Spring Boot...${NC}"
    $JAVA $JVM_OPTS -jar "$SPRING_JAR" --server.port=$PORT > /tmp/spring-latency-test.log 2>&1 &
    SPRING_PID=$!

    for i in $(seq 1 30); do
        if curl -s "http://localhost:$PORT/api/strategies" > /dev/null 2>&1; then
            echo -e "${GREEN}Spring Boot 已啟動 (PID: $SPRING_PID)${NC}"
            return 0
        fi
        if ! kill -0 $SPRING_PID 2>/dev/null; then
            echo -e "${RED}Spring Boot 啟動失敗！${NC}"
            tail -20 /tmp/spring-latency-test.log
            exit 1
        fi
        sleep 1
    done
    echo -e "${RED}Spring Boot 啟動逾時${NC}"
    exit 1
}

stop_spring() {
    echo -e "${YELLOW}停止 Spring Boot...${NC}"
    kill $SPRING_PID 2>/dev/null || true
    wait $SPRING_PID 2>/dev/null || true
    sleep 2
}

for STRAT in $STRATEGIES; do
    echo -e "\n${CYAN}════════════════════════════════════════════════════════════════════${NC}"
    echo -e "${CYAN}  策略 $STRAT — 延遲對比測試${NC}"
    echo -e "${CYAN}════════════════════════════════════════════════════════════════════${NC}"

    start_spring

    # 取策略名稱
    STRAT_NAME=$(curl -s "http://localhost:$PORT/api/strategies" | \
        python3 -c "import sys,json; data=json.load(sys.stdin); print([s['name'] for s in data if s['index']==$STRAT][0])" 2>/dev/null || echo "策略$STRAT")
    echo -e "${BOLD}策略: $STRAT_NAME${NC}"

    BASELINE_MS=""

    for LAT in $LATENCY_VALUES; do
        SCENARIO="同機(localhost)"
        [ "$LAT" = "1" ]  && SCENARIO="同機房跨VLAN"
        [ "$LAT" = "2" ]  && SCENARIO="同機房跨子網"
        [ "$LAT" = "5" ]  && SCENARIO="跨機房(同Region)"
        [ "$LAT" = "10" ] && SCENARIO="跨可用區(AZ)"
        [ "$LAT" = "20" ] && SCENARIO="跨Region"

        echo -e "\n  ${YELLOW}── 延遲 ${LAT}ms (${SCENARIO}) ──${NC}"

        RESPONSE=$(curl -s -w "\n%{http_code}" \
            -X POST "http://localhost:$PORT/api/upload?strategy=$STRAT&latencyMs=$LAT" \
            -F "file=@$DATA_FILE" \
            --max-time 1800)

        HTTP_CODE=$(echo "$RESPONSE" | tail -1)
        BODY=$(echo "$RESPONSE" | sed '$d')

        if [ "$HTTP_CODE" = "200" ]; then
            ELAPSED=$(echo "$BODY" | python3 -c "import sys,json; print(json.load(sys.stdin)['elapsedMs'])" 2>/dev/null)
            ELAPSED_SEC=$(echo "$BODY" | python3 -c "import sys,json; print(json.load(sys.stdin)['elapsedSec'])" 2>/dev/null)
            THROUGHPUT=$(echo "$BODY" | python3 -c "import sys,json; print(json.load(sys.stdin)['throughput'])" 2>/dev/null)
            ROUND_TRIPS=$(echo "$BODY" | python3 -c "import sys,json; print(json.load(sys.stdin)['dbRoundTrips'])" 2>/dev/null)
            NET_OVERHEAD=$(echo "$BODY" | python3 -c "import sys,json; print(json.load(sys.stdin)['estimatedNetworkOverheadMs'])" 2>/dev/null)
            ROWS=$(echo "$BODY" | python3 -c "import sys,json; print(json.load(sys.stdin)['rowsProcessed'])" 2>/dev/null)

            if [ -z "$BASELINE_MS" ]; then
                BASELINE_MS="$ELAPSED"
            fi
            DIFF_MS=$((ELAPSED - BASELINE_MS))
            if [ "$BASELINE_MS" -gt 0 ] 2>/dev/null; then
                DIFF_PCT=$(python3 -c "print(f'+{($DIFF_MS/$BASELINE_MS*100):.1f}%')" 2>/dev/null || echo "+?%")
            else
                DIFF_PCT="-"
            fi

            echo -e "    ${GREEN}耗時: ${ELAPSED_SEC}s | 吞吐量: ${THROUGHPUT}/s | RoundTrips: ${ROUND_TRIPS} | 網路開銷: ${NET_OVERHEAD}ms | 比基準: ${DIFF_PCT}${NC}"

            # 寫入結果檔
            echo "$STRAT|$STRAT_NAME|$LAT|$SCENARIO|$ELAPSED|$ELAPSED_SEC|$THROUGHPUT|$ROUND_TRIPS|$NET_OVERHEAD|$DIFF_MS|$DIFF_PCT|$ROWS" >> "$RESULT_FILE"
        else
            echo -e "    ${RED}HTTP $HTTP_CODE - 測試失敗${NC}"
            echo "$BODY" | python3 -m json.tool 2>/dev/null || echo "$BODY"
            echo "$STRAT|$STRAT_NAME|$LAT|$SCENARIO|FAIL|FAIL|FAIL|FAIL|FAIL|FAIL|FAIL|FAIL" >> "$RESULT_FILE"
        fi
    done

    stop_spring
done

# ===== 彙總報表 =====
echo -e "\n"
echo -e "${CYAN}╔══════════════════════════════════════════════════════════════════════════════════════════════════════════════════════╗${NC}"
echo -e "${CYAN}║                        DB 跨網段延遲模擬 — 測試結果彙總                                                          ║${NC}"
echo -e "${CYAN}╠════════════════════════════════════╤════════════════════╤══════════╤══════════╤════════════╤════════════╤═══════════╣${NC}"
printf "${CYAN}║${NC} %-34s │ %-18s │ %8s │ %8s │ %10s │ %10s │ %9s ${CYAN}║${NC}\n" \
    "策略" "網段情境" "延遲(ms)" "耗時(s)" "吞吐量/s" "RoundTrips" "比基準"
echo -e "${CYAN}╠════════════════════════════════════╪════════════════════╪══════════╪══════════╪════════════╪════════════╪═══════════╣${NC}"

CURRENT_STRAT=""
while IFS='|' read -r S_IDX S_NAME S_LAT S_SCENE S_MS S_SEC S_THR S_RT S_NET S_DIFF S_DPCT S_ROWS; do
    if [ "$S_NAME" != "$CURRENT_STRAT" ] && [ -n "$CURRENT_STRAT" ]; then
        echo -e "${CYAN}╟────────────────────────────────────┼────────────────────┼──────────┼──────────┼────────────┼────────────┼───────────╢${NC}"
    fi
    CURRENT_STRAT="$S_NAME"

    # 顏色：基準用綠色，延遲大的用紅色
    if [ "$S_LAT" = "0" ]; then
        COLOR="${GREEN}"
    elif [ "$S_LAT" -ge 10 ] 2>/dev/null; then
        COLOR="${RED}"
    else
        COLOR="${YELLOW}"
    fi

    # 截斷策略名
    SHORT_NAME=$(echo "$S_NAME" | cut -c1-34)
    printf "${CYAN}║${NC} %-34s │ ${COLOR}%-18s${NC} │ %8s │ %8s │ %10s │ %10s │ %9s ${CYAN}║${NC}\n" \
        "$SHORT_NAME" "$S_SCENE" "$S_LAT" "$S_SEC" "$S_THR" "$S_RT" "$S_DPCT"
done < "$RESULT_FILE"

echo -e "${CYAN}╚════════════════════════════════════╧════════════════════╧══════════╧══════════╧════════════╧════════════╧═══════════╝${NC}"

# ===== 分析說明 =====
echo -e "\n${BOLD}分析說明：${NC}"
echo "  • RoundTrips = 策略對 DB 發出的總 round-trip 次數（executeBatch/executeUpdate/commit 等）"
echo "  • 網路開銷 = RoundTrips × 延遲ms（理論值，不含 OS 排程抖動）"
echo "  • JDBC 批次策略 (BATCH_SIZE=5000): 1M rows ÷ 5000 ≈ 200 次 executeBatch + 少量控制操作"
echo "  • JPA/Hibernate (batch_size=50):  1M rows ÷ 50 ≈ 20000 次 executeBatch → 延遲影響高 100 倍"
echo "  • 結論：跨網段部署時，批次越大、round-trip 越少、延遲影響越小"
echo ""
echo "  原始結果檔: $RESULT_FILE"
echo "  Spring Boot log: /tmp/spring-latency-test.log"
echo -e "\n${GREEN}測試完成。${NC}"
