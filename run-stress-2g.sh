#!/bin/bash
#
# Spring Boot + Oracle 壓力測試（JVM 上限 2GB）
#
# 條件：
#   - JVM: -Xms512m -Xmx2048m -XX:+UseG1GC
#   - 檔案: stress-test-data/test_data.dat (1M 行 × 120 字元 ≈ 115MB)
#   - 對所有可用策略逐一測試，失敗也照實記錄
#
# 使用：
#   bash run-stress-2g.sh                       # 測全部策略（用 1M 行檔案）
#   bash run-stress-2g.sh test_data_1200000.dat # 改用 1.2M 行檔案
#

set -uo pipefail   # 不用 -e，因為單一策略失敗不應中斷整體

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$PROJECT_DIR"

JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk}"
JAVA="$JAVA_HOME/bin/java"
SPRING_JAR="$PROJECT_DIR/target/spring-oracle-stress-test-1.0-SNAPSHOT.jar"
DATA_DIR="$PROJECT_DIR/stress-test-data"
DATA_FILE_NAME="${1:-test_data.dat}"
DATA_FILE="$DATA_DIR/$DATA_FILE_NAME"
PORT=8080
LOG_FILE="$PROJECT_DIR/stress-2g-$(date +%Y%m%d-%H%M%S).log"

# 嚴格 2GB 上限
JVM_OPTS=(-Xms512m -Xmx2048m -XX:+UseG1GC -XX:MaxGCPauseMillis=200
          -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/tmp/spring-oom.hprof
          -XX:+ExitOnOutOfMemoryError)

RED=$'\033[0;31m'
GREEN=$'\033[0;32m'
YELLOW=$'\033[0;33m'
CYAN=$'\033[0;36m'
BOLD=$'\033[1m'
NC=$'\033[0m'

echo -e "${CYAN}╔══════════════════════════════════════════════════════════════════════╗${NC}"
echo -e "${CYAN}║   Spring Boot + Oracle 壓力測試（嚴格 -Xmx2048m）                  ║${NC}"
echo -e "${CYAN}║   檔案: $DATA_FILE_NAME${NC}"
echo -e "${CYAN}╚══════════════════════════════════════════════════════════════════════╝${NC}"
echo "完整 server log: $LOG_FILE"

# ===== 前置檢查 =====
[[ -f "$DATA_FILE" ]] || { echo -e "${RED}找不到測試資料: $DATA_FILE${NC}"; exit 1; }
[[ -f "$SPRING_JAR" ]] || { echo "編譯中..."; mvn -q clean package -DskipTests; }
podman ps --filter name=oracle-xe --format "{{.Names}}" | grep -q oracle-xe || {
    echo "啟動 Oracle..."
    podman start oracle-xe >/dev/null
    for i in $(seq 1 30); do
        podman logs oracle-xe 2>&1 | grep -q "DATABASE IS READY TO USE" && break
        sleep 2
    done
}

FILE_BYTES=$(stat -f%z "$DATA_FILE" 2>/dev/null || stat -c%s "$DATA_FILE" 2>/dev/null)
FILE_MB=$(echo "scale=2; $FILE_BYTES / 1048576" | bc)
echo "檔案大小: ${FILE_MB} MB ($FILE_BYTES bytes)"

# ===== 啟動一次 Spring Boot，取得策略列表 =====
echo -e "\n${YELLOW}═══ 啟動 Spring Boot 取得策略列表 ═══${NC}"
$JAVA "${JVM_OPTS[@]}" -jar "$SPRING_JAR" --server.port=$PORT > "$LOG_FILE" 2>&1 &
SPRING_PID=$!
trap "kill $SPRING_PID 2>/dev/null || true" EXIT

for i in $(seq 1 60); do
    if curl -s "http://localhost:$PORT/api/strategies" > /dev/null 2>&1; then
        break
    fi
    if ! kill -0 $SPRING_PID 2>/dev/null; then
        echo -e "${RED}Spring Boot 啟動失敗${NC}"
        tail -30 "$LOG_FILE"
        exit 1
    fi
    sleep 1
done

STRATEGY_LIST=$(curl -s "http://localhost:$PORT/api/strategies")
STRAT_COUNT=$(echo "$STRATEGY_LIST" | python3 -c "import sys,json; print(len(json.load(sys.stdin)))")
echo "找到 $STRAT_COUNT 個策略"
echo "$STRATEGY_LIST" | python3 -c "
import sys,json
for s in json.load(sys.stdin):
    print(f\"  [{s['index']}] {s['name']}\")"

kill $SPRING_PID 2>/dev/null || true
wait $SPRING_PID 2>/dev/null || true
sleep 2

# ===== 逐一測試每個策略 =====
declare -a R_NAME R_STATUS R_TIME R_PEAK R_TPUT R_DBCNT R_NOTE

for STRAT in $(seq 1 $STRAT_COUNT); do
    SNAME=$(echo "$STRATEGY_LIST" | python3 -c "
import sys,json
s = json.load(sys.stdin)[$STRAT - 1]
print(s['name'])")
    echo -e "\n${CYAN}════════════════════════════════════════════════════════════════════${NC}"
    echo -e "${CYAN}  策略 $STRAT / $STRAT_COUNT : $SNAME${NC}"
    echo -e "${CYAN}════════════════════════════════════════════════════════════════════${NC}"

    # 啟動 Spring Boot（每策略隔離，避免 heap 累積）
    > "$LOG_FILE.strat$STRAT"
    $JAVA "${JVM_OPTS[@]}" -jar "$SPRING_JAR" --server.port=$PORT > "$LOG_FILE.strat$STRAT" 2>&1 &
    SPRING_PID=$!

    STARTED=false
    for i in $(seq 1 60); do
        curl -s "http://localhost:$PORT/api/strategies" >/dev/null 2>&1 && { STARTED=true; break; }
        kill -0 $SPRING_PID 2>/dev/null || break
        sleep 1
    done

    if [[ "$STARTED" != "true" ]]; then
        echo -e "${RED}啟動失敗（可能 OOM）${NC}"
        tail -10 "$LOG_FILE.strat$STRAT"
        R_NAME[$STRAT]="$SNAME"
        R_STATUS[$STRAT]="BOOT-FAIL"
        R_TIME[$STRAT]="-"
        R_PEAK[$STRAT]="-"
        R_TPUT[$STRAT]="-"
        R_DBCNT[$STRAT]="-"
        R_NOTE[$STRAT]="Spring Boot 啟動失敗"
        kill $SPRING_PID 2>/dev/null || true
        wait $SPRING_PID 2>/dev/null || true
        sleep 2
        continue
    fi

    # 上傳 + 計時
    RESP_FILE="/tmp/spring-stress-resp-$STRAT.json"
    START_TS=$(date +%s)
    HTTP_CODE=$(curl -s -w "%{http_code}" \
        -X POST "http://localhost:$PORT/api/upload?strategy=$STRAT" \
        -F "file=@$DATA_FILE" \
        --max-time 1800 \
        -o "$RESP_FILE" 2>/dev/null || echo "000")
    END_TS=$(date +%s)
    WALL_SEC=$((END_TS - START_TS))

    # 處理結果
    if [[ "$HTTP_CODE" == "200" ]]; then
        STATUS=$(python3 -c "import json; d=json.load(open('$RESP_FILE')); print(d.get('status','?'))" 2>/dev/null || echo "?")
        STIME=$(python3 -c "import json; d=json.load(open('$RESP_FILE')); print(d.get('elapsedSec','?'))" 2>/dev/null || echo "?")
        SPEAK=$(python3 -c "import json; d=json.load(open('$RESP_FILE')); print(d.get('peakMemoryMB','?'))" 2>/dev/null || echo "?")
        STHRU=$(python3 -c "import json; d=json.load(open('$RESP_FILE')); print(d.get('throughput','?'))" 2>/dev/null || echo "?")
        SDBCN=$(python3 -c "import json; d=json.load(open('$RESP_FILE')); print(d.get('dbCount','?'))" 2>/dev/null || echo "?")
        SUCC=$(python3 -c "import json; d=json.load(open('$RESP_FILE')); print(d.get('successCount','?'))" 2>/dev/null || echo "?")
        FAIL=$(python3 -c "import json; d=json.load(open('$RESP_FILE')); print(d.get('failCount','?'))" 2>/dev/null || echo "?")
        if [[ "$STATUS" == "COMMIT" ]]; then
            echo -e "${GREEN}OK${NC}  耗時=${STIME}s  Peak=${SPEAK}MB  TPS=${STHRU}  DB筆數=${SDBCN} (success=$SUCC fail=$FAIL)"
        else
            echo -e "${YELLOW}ROLLBACK${NC} 耗時=${STIME}s  Peak=${SPEAK}MB  DB筆數=${SDBCN}"
        fi
        R_STATUS[$STRAT]="$STATUS"
        R_TIME[$STRAT]="${STIME}s"
        R_PEAK[$STRAT]="${SPEAK}MB"
        R_TPUT[$STRAT]="${STHRU}"
        R_DBCNT[$STRAT]="${SDBCN}"
        R_NOTE[$STRAT]=""
    else
        # 看是不是 OOM
        if grep -q "OutOfMemoryError" "$LOG_FILE.strat$STRAT"; then
            echo -e "${RED}OOM (HTTP=$HTTP_CODE wall=${WALL_SEC}s)${NC}"
            R_STATUS[$STRAT]="OOM"
            R_NOTE[$STRAT]="OutOfMemoryError"
        elif ! kill -0 $SPRING_PID 2>/dev/null; then
            echo -e "${RED}JVM 崩潰 (HTTP=$HTTP_CODE wall=${WALL_SEC}s)${NC}"
            R_STATUS[$STRAT]="CRASH"
            R_NOTE[$STRAT]="JVM 退出"
        else
            echo -e "${RED}HTTP $HTTP_CODE (wall=${WALL_SEC}s)${NC}"
            R_STATUS[$STRAT]="HTTP-$HTTP_CODE"
            R_NOTE[$STRAT]="$(head -c 200 "$RESP_FILE" 2>/dev/null)"
        fi
        R_TIME[$STRAT]="${WALL_SEC}s(wall)"
        R_PEAK[$STRAT]="-"
        R_TPUT[$STRAT]="-"
        R_DBCNT[$STRAT]="-"
    fi
    R_NAME[$STRAT]="$SNAME"

    kill $SPRING_PID 2>/dev/null || true
    wait $SPRING_PID 2>/dev/null || true
    sleep 3
done

# ===== 彙總 =====
echo -e "\n${CYAN}════════════════════════════════════════════════════════════════════════════════════════════════════════${NC}"
echo -e "${CYAN}  彙總（JVM=-Xmx2048m, 檔案=$DATA_FILE_NAME, ${FILE_MB}MB）${NC}"
echo -e "${CYAN}════════════════════════════════════════════════════════════════════════════════════════════════════════${NC}"
printf "${BOLD}%-3s %-32s %-12s %-12s %-10s %-10s %-12s %s${NC}\n" \
    "#" "策略" "狀態" "耗時" "Peak MB" "TPS" "DB筆數" "備註"
echo "────────────────────────────────────────────────────────────────────────────────────────────────────────"
for STRAT in $(seq 1 $STRAT_COUNT); do
    STATUS="${R_STATUS[$STRAT]}"
    case "$STATUS" in
        COMMIT)        COL="$GREEN" ;;
        ROLLBACK)      COL="$YELLOW" ;;
        OOM|CRASH|BOOT-FAIL|HTTP-*) COL="$RED" ;;
        *)             COL="$NC" ;;
    esac
    printf "%-3s %-32s ${COL}%-12s${NC} %-12s %-10s %-10s %-12s %s\n" \
        "$STRAT" "${R_NAME[$STRAT]:0:32}" "$STATUS" "${R_TIME[$STRAT]}" \
        "${R_PEAK[$STRAT]}" "${R_TPUT[$STRAT]}" "${R_DBCNT[$STRAT]}" "${R_NOTE[$STRAT]}"
done
echo "────────────────────────────────────────────────────────────────────────────────────────────────────────"

# 統計
PASS=0; FAILS=0
for STRAT in $(seq 1 $STRAT_COUNT); do
    [[ "${R_STATUS[$STRAT]}" == "COMMIT" ]] && PASS=$((PASS+1)) || FAILS=$((FAILS+1))
done
echo -e "${BOLD}結果：${GREEN}${PASS} 個 COMMIT${NC} / ${RED}${FAILS} 個失敗${NC}（共 $STRAT_COUNT 策略）"
echo "個別策略 log: $LOG_FILE.strat<N>"
