#!/bin/zsh
#
# Spring Boot + Oracle Heap 閾值測試
# zsh run-spring-threshold.sh            # 全部 6 策略
# zsh run-spring-threshold.sh 2 6        # 只測策略 2 和 6
#

set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$PROJECT_DIR"

JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk}"
JAVA="$JAVA_HOME/bin/java"
SPRING_JAR="$PROJECT_DIR/target/spring-oracle-stress-test-1.0-SNAPSHOT.jar"
DATA_DIR="$PROJECT_DIR/stress-test-data"
DATA_FILE="$DATA_DIR/test_data.dat"
PORT=8080

HEAP_SIZES=(128 256 384 512 768 1024 1536 2048 4096)

RED=$'\033[0;31m'
GREEN=$'\033[0;32m'
YELLOW=$'\033[0;33m'
CYAN=$'\033[0;36m'
NC=$'\033[0m'

echo "${CYAN}Spring Boot + Oracle Heap 閾值測試${NC}"
echo "${CYAN}Heap sizes: ${HEAP_SIZES[*]} MB${NC}"
echo ""

if ! podman ps --filter name=oracle-xe --format "{{.Names}}" 2>/dev/null | grep -q oracle-xe; then
    echo "${RED}Oracle 容器未運行${NC}"; exit 1
fi

[[ -f "$DATA_FILE" ]] || { echo "${RED}找不到測試資料${NC}"; exit 1; }
[[ -f "$SPRING_JAR" ]] || { echo "編譯中..."; mvn -q clean package -DskipTests; }

if [[ $# -gt 0 ]]; then
    STRATEGIES=($@)
else
    STRATEGIES=(1 2 3 4 5 6)
fi

# zsh 1-indexed: STRATEGY_NAMES[1] = strategy 1
STRATEGY_NAMES=("[TX] List 全載入" "[TX] List分頁(推薦)" "[TX] BatchChunk 分批" "[TX] MemoryMapped" "[TX] LinkedList+Iter" "[TX] Streaming 逐行")

typeset -A RESULT_MATRIX

for STRAT in "${STRATEGIES[@]}"; do
    SNAME="${STRATEGY_NAMES[$STRAT]}"
    echo ""
    echo "${CYAN}══════════ 策略 $STRAT: $SNAME ══════════${NC}"

    for HEAP in "${HEAP_SIZES[@]}"; do
        printf "  Heap %4dMB: " "$HEAP"

        JVM_OPTS=(-Xms${HEAP}m -Xmx${HEAP}m -XX:+UseG1GC -XX:ActiveProcessorCount=4)

        $JAVA "${JVM_OPTS[@]}" -jar "$SPRING_JAR" --server.port=$PORT > /tmp/spring-threshold.log 2>&1 &
        SPRING_PID=$!

        STARTED=false
        for i in {1..30}; do
            if curl -s "http://localhost:$PORT/api/strategies" > /dev/null 2>&1; then
                STARTED=true; break
            fi
            kill -0 $SPRING_PID 2>/dev/null || break
            sleep 1
        done

        if [[ "$STARTED" == "false" ]]; then
            echo "${RED}啟動失敗 (OOM?)${NC}"
            RESULT_MATRIX[${STRAT}_${HEAP}]="BOOT-FAIL"
            kill $SPRING_PID 2>/dev/null || true
            wait $SPRING_PID 2>/dev/null || true
            sleep 1
            continue
        fi

        RESP_FILE="/tmp/spring-threshold-resp.json"
        HTTP_CODE=$(curl -s -w "%{http_code}" \
            -X POST "http://localhost:$PORT/api/upload?strategy=$STRAT" \
            -F "file=@$DATA_FILE" \
            --max-time 600 \
            -o "$RESP_FILE" 2>/dev/null || echo "000")

        if [[ "$HTTP_CODE" == "200" ]]; then
            ELAPSED=$(python3 -c "import json; d=json.load(open('$RESP_FILE')); print(d.get('elapsedSec','?'))" 2>/dev/null || echo "?")
            PEAK=$(python3 -c "import json; d=json.load(open('$RESP_FILE')); print(d.get('peakMemoryMB','?'))" 2>/dev/null || echo "?")
            echo "${GREEN}OK${NC} (${ELAPSED}s, Peak: ${PEAK}MB)"
            RESULT_MATRIX[${STRAT}_${HEAP}]="OK:${ELAPSED}s"
        else
            echo "${RED}FAIL (HTTP $HTTP_CODE)${NC}"
            RESULT_MATRIX[${STRAT}_${HEAP}]="FAIL"
        fi

        kill $SPRING_PID 2>/dev/null || true
        wait $SPRING_PID 2>/dev/null || true
        sleep 2
    done
done

# ===== 彙總 =====
echo ""
echo "${CYAN}═══════════════════════════════════════════════════════════════════════════════════════════════════════════${NC}"
echo "${CYAN}  Spring Boot + Oracle  Heap 閾值測試結果${NC}"
echo "${CYAN}═══════════════════════════════════════════════════════════════════════════════════════════════════════════${NC}"

# Header
printf "%-28s" "策略"
for H in "${HEAP_SIZES[@]}"; do
    printf "%12s" "${H}MB"
done
echo ""
echo "─────────────────────────────────────────────────────────────────────────────────────────────────────────────"

for STRAT in "${STRATEGIES[@]}"; do
    SNAME="${STRATEGY_NAMES[$STRAT]}"
    printf "%-28s" "$SNAME"
    for HEAP in "${HEAP_SIZES[@]}"; do
        VAL="${RESULT_MATRIX[${STRAT}_${HEAP}]:-N/A}"
        if [[ "$VAL" == OK* ]]; then
            printf "${GREEN}%12s${NC}" "$VAL"
        else
            printf "${RED}%12s${NC}" "$VAL"
        fi
    done
    echo ""
done
echo "─────────────────────────────────────────────────────────────────────────────────────────────────────────────"

echo ""
echo "${GREEN}閾值測試完成。${NC}"
