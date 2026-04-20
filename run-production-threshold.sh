#!/bin/zsh
#
# 生產環境模擬 — Heap 閾值測試
#
# 情境：一台 Tomcat 掛載 10 個 Java 服務 + Node.js(Angular)
#
# ╔══════════════════════════════════════════════════════════════════════╗
# ║  伺服器 RAM 配置模型                                                ║
# ║                                                                     ║
# ║  總 RAM ─┬─ OS + 系統保留       ~2GB                               ║
# ║           ├─ Node.js (Angular)   ~1.5GB (--max-old-space-size)     ║
# ║           ├─ Tomcat JVM Heap     = 剩餘                            ║
# ║           │   ├─ Spring Boot 基礎框架 + 連線池  ~300MB             ║
# ║           │   ├─ 其他 9 個服務 (idle baseline)  ~9×150MB = 1350MB  ║
# ║           │   └─ ★ 本服務可用 = Tomcat Heap - 1650MB              ║
# ║           └─ Metaspace / off-heap / NIO    ~500MB                  ║
# ╚══════════════════════════════════════════════════════════════════════╝
#
# 測試依據不同伺服器 RAM，計算出本服務實際可用 heap 來測試。
#
# 使用方式：
#   zsh run-production-threshold.sh            # 全部 6 策略
#   zsh run-production-threshold.sh 2 6        # 只測策略 2 和 6
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

# ===== 生產環境 RAM 模型 =====
# 格式: "伺服器RAM(GB)|Tomcat_Heap(MB)|本服務可用Heap(MB)"
#
# 計算公式:
#   Tomcat Heap = (伺服器RAM - OS 2GB - Node.js 1.5GB - Off-heap 0.5GB) * 1024
#   本服務可用  = Tomcat Heap - Spring框架 300MB - 其他9服務 1350MB
#
# 伺服器RAM  Tomcat Heap   本服務可用  
#   8GB       4096MB        2396MB  → 測試 2048MB
#  16GB      12288MB       10288MB  → 測試 4096MB (足夠)
#  32GB      28672MB       26722MB  → 測試 4096MB (足夠)
#
# 但實際中其他服務也會活躍，所以保守估計:
#   其他9服務活躍時 = 9 × 300~500MB = 2700~4500MB
#
# 保守模型（其他服務也在處理請求時）:
#   8GB 伺服器 → 可用 ~768MB ~ 1200MB
#  16GB 伺服器 → 可用 ~2048MB ~ 4096MB
#  32GB 伺服器 → 可用 ~4096MB+

# 我們測試以下 heap 大小，對應不同生產環境情境
typeset -A SCENARIO_DESC
HEAP_SIZES=(128 192 256 384 512 768 1024 1536 2048)

SCENARIO_DESC[128]="極限測試"
SCENARIO_DESC[192]="極限測試"
SCENARIO_DESC[256]="8GB伺服器(極壓)"
SCENARIO_DESC[384]="8GB伺服器(緊張)"
SCENARIO_DESC[512]="8GB伺服器(一般)"
SCENARIO_DESC[768]="8GB伺服器(寬鬆)"
SCENARIO_DESC[1024]="16GB伺服器(緊張)"
SCENARIO_DESC[1536]="16GB伺服器(一般)"
SCENARIO_DESC[2048]="16GB伺服器(寬鬆)/32GB"

RED=$'\033[0;31m'
GREEN=$'\033[0;32m'
YELLOW=$'\033[0;33m'
CYAN=$'\033[0;36m'
BOLD=$'\033[1m'
NC=$'\033[0m'

echo ""
echo "${CYAN}${BOLD}╔══════════════════════════════════════════════════════════════════════════╗${NC}"
echo "${CYAN}${BOLD}║   生產環境模擬 — Heap 閾值測試                                         ║${NC}"
echo "${CYAN}${BOLD}║                                                                         ║${NC}"
echo "${CYAN}${BOLD}║   模擬情境:                                                             ║${NC}"
echo "${CYAN}${BOLD}║     • 1 台 Tomcat 掛載 10 個 Java 服務                                  ║${NC}"
echo "${CYAN}${BOLD}║     • Node.js + Angular 前端 (~1.5GB)                                   ║${NC}"
echo "${CYAN}${BOLD}║     • OS + 系統保留 (~2GB)                                              ║${NC}"
echo "${CYAN}${BOLD}║     • Off-heap / Metaspace (~0.5GB)                                     ║${NC}"
echo "${CYAN}${BOLD}║                                                                         ║${NC}"
echo "${CYAN}${BOLD}║   本服務可用 Heap = 伺服器 RAM - 上述開銷 - 其他9服務                    ║${NC}"
echo "${CYAN}${BOLD}╚══════════════════════════════════════════════════════════════════════════╝${NC}"
echo ""

echo "${YELLOW}Heap 大小 vs 生產環境對照:${NC}"
for H in "${HEAP_SIZES[@]}"; do
    printf "  %6dMB  →  %s\n" "$H" "${SCENARIO_DESC[$H]}"
done
echo ""

# ===== 前置檢查 =====
if ! podman ps --filter name=oracle-xe --format "{{.Names}}" 2>/dev/null | grep -q oracle-xe; then
    echo "${RED}Oracle 容器未運行，請先啟動${NC}"; exit 1
fi
[[ -f "$DATA_FILE" ]] || { echo "${RED}找不到測試資料: $DATA_FILE${NC}"; exit 1; }
[[ -f "$SPRING_JAR" ]] || { echo "編譯中..."; mvn -q clean package -DskipTests; }

# 確保 port 空閒
lsof -ti:$PORT 2>/dev/null | xargs kill -9 2>/dev/null || true
sleep 1

if [[ $# -gt 0 ]]; then
    STRATEGIES=($@)
else
    STRATEGIES=(1 2 3 4 5 6)
fi

STRATEGY_NAMES=("[TX] List 全載入" "[TX] List分頁(推薦)" "[TX] BatchChunk 分批" "[TX] MemoryMapped" "[TX] LinkedList+Iter" "[TX] Streaming 逐行")

typeset -A RESULT_MATRIX
typeset -A PEAK_MATRIX

for STRAT in "${STRATEGIES[@]}"; do
    SNAME="${STRATEGY_NAMES[$STRAT]}"
    echo ""
    echo "${CYAN}${BOLD}══════════ 策略 $STRAT: $SNAME ══════════${NC}"

    for HEAP in "${HEAP_SIZES[@]}"; do
        printf "  Heap %5dMB [%-20s]: " "$HEAP" "${SCENARIO_DESC[$HEAP]}"

        JVM_OPTS=(-Xms${HEAP}m -Xmx${HEAP}m -XX:+UseG1GC -XX:ActiveProcessorCount=4 -XX:MaxGCPauseMillis=200)

        $JAVA "${JVM_OPTS[@]}" -jar "$SPRING_JAR" --server.port=$PORT > /tmp/spring-prod-threshold.log 2>&1 &
        SPRING_PID=$!

        STARTED=false
        for i in {1..45}; do
            if curl -s "http://localhost:$PORT/api/strategies" > /dev/null 2>&1; then
                STARTED=true; break
            fi
            if ! kill -0 $SPRING_PID 2>/dev/null; then
                break
            fi
            sleep 1
        done

        if [[ "$STARTED" == "false" ]]; then
            echo "${RED}啟動失敗 (Heap 不足以啟動 Spring Boot)${NC}"
            RESULT_MATRIX[${STRAT}_${HEAP}]="BOOT-FAIL"
            PEAK_MATRIX[${STRAT}_${HEAP}]="-"
            kill $SPRING_PID 2>/dev/null || true
            wait $SPRING_PID 2>/dev/null || true
            sleep 1
            continue
        fi

        RESP_FILE="/tmp/spring-prod-threshold-resp.json"
        HTTP_CODE=$(curl -s -w "%{http_code}" \
            -X POST "http://localhost:$PORT/api/upload?strategy=$STRAT" \
            -F "file=@$DATA_FILE" \
            --max-time 600 \
            -o "$RESP_FILE" 2>/dev/null || echo "000")

        if [[ "$HTTP_CODE" == "200" ]]; then
            ELAPSED=$(python3 -c "import json; d=json.load(open('$RESP_FILE')); print(d.get('elapsedSec','?'))" 2>/dev/null || echo "?")
            PEAK=$(python3 -c "import json; d=json.load(open('$RESP_FILE')); print(d.get('peakMemoryMB','?'))" 2>/dev/null || echo "?")
            DBCNT=$(python3 -c "import json; d=json.load(open('$RESP_FILE')); print(d.get('dbCount','?'))" 2>/dev/null || echo "?")
            echo "${GREEN}OK${NC} (${ELAPSED}s, Peak: ${PEAK}MB, DB: ${DBCNT})"
            RESULT_MATRIX[${STRAT}_${HEAP}]="OK:${ELAPSED}s"
            PEAK_MATRIX[${STRAT}_${HEAP}]="${PEAK}"
        else
            ERRMSG=""
            if [[ -f "$RESP_FILE" ]]; then
                ERRMSG=$(python3 -c "
import json
try:
    d=json.load(open('$RESP_FILE'))
    print(d.get('error','') or d.get('message',''))
except: pass
" 2>/dev/null)
            fi
            if [[ "$HTTP_CODE" == "000" ]]; then
                echo "${RED}FAIL (連線失敗/OOM crash)${NC}"
                RESULT_MATRIX[${STRAT}_${HEAP}]="OOM-CRASH"
            else
                echo "${RED}FAIL (HTTP $HTTP_CODE)${NC} ${ERRMSG}"
                RESULT_MATRIX[${STRAT}_${HEAP}]="FAIL:$HTTP_CODE"
            fi
            PEAK_MATRIX[${STRAT}_${HEAP}]="-"
        fi

        kill $SPRING_PID 2>/dev/null || true
        wait $SPRING_PID 2>/dev/null || true
        sleep 2

        # 確保 port 釋放
        lsof -ti:$PORT 2>/dev/null | xargs kill -9 2>/dev/null || true
        sleep 1
    done
done

# ===== 彙總表 =====
echo ""
echo "${CYAN}${BOLD}════════════════════════════════════════════════════════════════════════════════════════════════════════════════${NC}"
echo "${CYAN}${BOLD}  生產環境模擬 — Heap 閾值測試結果${NC}"
echo "${CYAN}${BOLD}  (Tomcat × 10 服務 + Node.js/Angular)${NC}"
echo "${CYAN}${BOLD}════════════════════════════════════════════════════════════════════════════════════════════════════════════════${NC}"
echo ""

# Header
printf "${BOLD}%-28s${NC}" "策略 \\ Heap"
for H in "${HEAP_SIZES[@]}"; do
    printf "%12s" "${H}MB"
done
echo ""
echo "────────────────────────────────────────────────────────────────────────────────────────────────────────────────"

for STRAT in "${STRATEGIES[@]}"; do
    SNAME="${STRATEGY_NAMES[$STRAT]}"
    printf "%-28s" "$SNAME"
    for HEAP in "${HEAP_SIZES[@]}"; do
        VAL="${RESULT_MATRIX[${STRAT}_${HEAP}]:-N/A}"
        if [[ "$VAL" == OK* ]]; then
            printf "${GREEN}%12s${NC}" "$VAL"
        elif [[ "$VAL" == BOOT* ]]; then
            printf "${RED}%12s${NC}" "BOOT-FAIL"
        else
            printf "${RED}%12s${NC}" "$VAL"
        fi
    done
    echo ""
done

echo "────────────────────────────────────────────────────────────────────────────────────────────────────────────────"
echo ""

# ===== 最低需求表 =====
echo "${CYAN}${BOLD}  各策略最低 Heap 需求:${NC}"
echo ""
for STRAT in "${STRATEGIES[@]}"; do
    SNAME="${STRATEGY_NAMES[$STRAT]}"
    MIN_HEAP="N/A"
    MIN_PEAK="-"
    for HEAP in "${HEAP_SIZES[@]}"; do
        VAL="${RESULT_MATRIX[${STRAT}_${HEAP}]:-N/A}"
        if [[ "$VAL" == OK* ]]; then
            MIN_HEAP="${HEAP}MB"
            MIN_PEAK="${PEAK_MATRIX[${STRAT}_${HEAP}]:-?}"
            break
        fi
    done
    printf "  %-28s 最低 Heap: %-8s (Peak: %sMB)\n" "$SNAME" "$MIN_HEAP" "$MIN_PEAK"
done

echo ""
echo "${CYAN}${BOLD}  生產環境建議:${NC}"
echo ""
echo "  ┌────────────────┬────────────────────────────────────────────────────────────────┐"
echo "  │ 伺服器 RAM     │ 說明                                                          │"
echo "  ├────────────────┼────────────────────────────────────────────────────────────────┤"
echo "  │  8GB           │ Tomcat Heap ~4GB, 本服務可用 ~256-768MB (視其他服務負載)       │"
echo "  │ 16GB           │ Tomcat Heap ~12GB, 本服務可用 ~1-2GB                           │"
echo "  │ 32GB           │ Tomcat Heap ~28GB, 本服務可用 ~2-4GB                           │"
echo "  └────────────────┴────────────────────────────────────────────────────────────────┘"
echo ""
echo "${GREEN}${BOLD}測試完成。${NC}"
