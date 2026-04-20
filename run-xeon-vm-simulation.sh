#!/bin/zsh
#
# 生產環境 VM 模擬 — Intel Xeon Gold 6338 (8 vCPU @ 2.0 GHz)
#
# 目標：在 Apple M2 Ultra 上模擬以下生產環境
#   CPU  : Intel Xeon Gold 6338 (Ice Lake-SP), VM 分配 8 vCPU @ 2.00 GHz
#   情境 : 1 台 Tomcat 掛載 10 個 Java 服務 + Node.js/Angular
#
# ╔══════════════════════════════════════════════════════════════════════════╗
# ║  CPU 模擬方式                                                           ║
# ║                                                                         ║
# ║  1. -XX:ActiveProcessorCount=8 → 限制 JVM 只看到 8 顆 CPU              ║
# ║  2. cpulimit 限制 CPU% → 模擬 2.0 GHz 時脈                             ║
# ║                                                                         ║
# ║  M2 Ultra P-core: ~3.49 GHz, IPC 約為 Ice Lake-SP 的 1.4~1.6 倍        ║
# ║  等效降速比: 2.0 / 3.49 × (1/1.5 IPC) ≈ 38%                           ║
# ║  → 用 8 核 × 38% ≈ cpulimit 304% (8 核滿載 = 800%, 打 38 折 = ~300%)  ║
# ║                                                                         ║
# ║  RAM 模型 (同 run-production-threshold.sh):                             ║
# ║    伺服器 RAM - OS(2G) - Node.js(1.5G) - Off-heap(0.5G) = Tomcat Heap  ║
# ║    Tomcat Heap - 框架(300M) - 其他9服務(1.35~4.5G) = 本服務可用         ║
# ╚══════════════════════════════════════════════════════════════════════════╝
#
# 使用方式：
#   zsh run-xeon-vm-simulation.sh            # 全部 6 策略
#   zsh run-xeon-vm-simulation.sh 2 6        # 只測策略 2 和 6
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

# ===== Xeon VM 模擬參數 =====
VM_CPUS=8                 # VM 分配 8 vCPU
XEON_GHZ="2.00"           # Xeon Gold 6338 base clock
M2_ULTRA_GHZ="3.49"       # M2 Ultra P-core clock

# cpulimit 百分比計算:
#   時脈比 = 2.0 / 3.49 = 57.3%
#   IPC 修正 (M2 IPC > Ice Lake): × 0.67 (保守估計)
#   等效 = 57.3% × 67% ≈ 38% per core
#   8 核滿載 = 800%, × 38% ≈ 304% → 用 300%
CPULIMIT_PCT=300

# ===== 測試情境 =====
# 模擬不同伺服器 RAM 下，本服務可用的 Heap
typeset -A SCENARIO_DESC
HEAP_SIZES=(128 192 256 384 512 768 1024 1536 2048)

SCENARIO_DESC[128]="極限測試"
SCENARIO_DESC[192]="極限測試"
SCENARIO_DESC[256]="8GB VM(極壓)"
SCENARIO_DESC[384]="8GB VM(緊張)"
SCENARIO_DESC[512]="8GB VM(一般)"
SCENARIO_DESC[768]="8GB VM(寬鬆)"
SCENARIO_DESC[1024]="16GB VM(緊張)"
SCENARIO_DESC[1536]="16GB VM(一般)"
SCENARIO_DESC[2048]="16GB VM(寬鬆)/32GB"

RED=$'\033[0;31m'
GREEN=$'\033[0;32m'
YELLOW=$'\033[0;33m'
CYAN=$'\033[0;36m'
BOLD=$'\033[1m'
NC=$'\033[0m'

echo ""
echo "${CYAN}${BOLD}╔══════════════════════════════════════════════════════════════════════════╗${NC}"
echo "${CYAN}${BOLD}║   Intel Xeon Gold 6338 VM 模擬 — Heap 閾值測試                         ║${NC}"
echo "${CYAN}${BOLD}║                                                                         ║${NC}"
echo "${CYAN}${BOLD}║   模擬目標:                                                             ║${NC}"
echo "${CYAN}${BOLD}║     CPU : Intel Xeon Gold 6338, 8 vCPU @ ${XEON_GHZ} GHz               ║${NC}"
echo "${CYAN}${BOLD}║     情境: Tomcat × 10 服務 + Node.js/Angular                            ║${NC}"
echo "${CYAN}${BOLD}║                                                                         ║${NC}"
echo "${CYAN}${BOLD}║   模擬方式:                                                             ║${NC}"
echo "${CYAN}${BOLD}║     JVM : -XX:ActiveProcessorCount=${VM_CPUS}                                    ║${NC}"
echo "${CYAN}${BOLD}║     CPU : cpulimit -l ${CPULIMIT_PCT} (8核 × 38% ≈ Xeon@2GHz)                   ║${NC}"
echo "${CYAN}${BOLD}╚══════════════════════════════════════════════════════════════════════════╝${NC}"
echo ""

# ===== 前置檢查 =====
if ! command -v cpulimit &>/dev/null; then
    echo "${RED}需要安裝 cpulimit: brew install cpulimit${NC}"; exit 1
fi

if ! podman ps --filter name=oracle-xe --format "{{.Names}}" 2>/dev/null | grep -q oracle-xe; then
    echo "${RED}Oracle 容器未運行${NC}"; exit 1
fi
[[ -f "$DATA_FILE" ]] || { echo "${RED}找不到測試資料: $DATA_FILE${NC}"; exit 1; }
[[ -f "$SPRING_JAR" ]] || { echo "編譯中..."; mvn -q clean package -DskipTests; }

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
typeset -A TIME_MATRIX

echo "${YELLOW}Heap vs 生產環境對照:${NC}"
for H in "${HEAP_SIZES[@]}"; do
    printf "  %6dMB  →  %s\n" "$H" "${SCENARIO_DESC[$H]}"
done
echo ""

for STRAT in "${STRATEGIES[@]}"; do
    SNAME="${STRATEGY_NAMES[$STRAT]}"
    echo ""
    echo "${CYAN}${BOLD}══════════ 策略 $STRAT: $SNAME ══════════${NC}"

    for HEAP in "${HEAP_SIZES[@]}"; do
        printf "  Heap %5dMB [%-20s]: " "$HEAP" "${SCENARIO_DESC[$HEAP]}"

        # JVM 參數：限制 8 CPU, G1GC
        JVM_OPTS=(
            -Xms${HEAP}m -Xmx${HEAP}m
            -XX:+UseG1GC
            -XX:ActiveProcessorCount=${VM_CPUS}
            -XX:MaxGCPauseMillis=200
            -XX:ParallelGCThreads=8
            -XX:ConcGCThreads=2
        )

        # 啟動 Spring Boot
        $JAVA "${JVM_OPTS[@]}" -jar "$SPRING_JAR" --server.port=$PORT > /tmp/spring-xeon-sim.log 2>&1 &
        SPRING_PID=$!

        # 立即用 cpulimit 限速
        cpulimit -p $SPRING_PID -l $CPULIMIT_PCT -b 2>/dev/null || true

        STARTED=false
        for i in {1..60}; do
            if curl -s "http://localhost:$PORT/api/strategies" > /dev/null 2>&1; then
                STARTED=true; break
            fi
            if ! kill -0 $SPRING_PID 2>/dev/null; then
                break
            fi
            sleep 1
        done

        if [[ "$STARTED" == "false" ]]; then
            echo "${RED}啟動失敗 (Heap 不足)${NC}"
            RESULT_MATRIX[${STRAT}_${HEAP}]="BOOT-FAIL"
            PEAK_MATRIX[${STRAT}_${HEAP}]="-"
            TIME_MATRIX[${STRAT}_${HEAP}]="-"
            # 清理 cpulimit 和 java
            pkill -f "cpulimit.*$SPRING_PID" 2>/dev/null || true
            kill $SPRING_PID 2>/dev/null || true
            wait $SPRING_PID 2>/dev/null || true
            sleep 1
            continue
        fi

        RESP_FILE="/tmp/spring-xeon-sim-resp.json"
        HTTP_CODE=$(curl -s -w "%{http_code}" \
            -X POST "http://localhost:$PORT/api/upload?strategy=$STRAT" \
            -F "file=@$DATA_FILE" \
            --max-time 900 \
            -o "$RESP_FILE" 2>/dev/null || echo "000")

        if [[ "$HTTP_CODE" == "200" ]]; then
            ELAPSED=$(python3 -c "import json; d=json.load(open('$RESP_FILE')); print(d.get('elapsedSec','?'))" 2>/dev/null || echo "?")
            PEAK=$(python3 -c "import json; d=json.load(open('$RESP_FILE')); print(d.get('peakMemoryMB','?'))" 2>/dev/null || echo "?")
            DBCNT=$(python3 -c "import json; d=json.load(open('$RESP_FILE')); print(d.get('dbCount','?'))" 2>/dev/null || echo "?")
            THRU=$(python3 -c "import json; d=json.load(open('$RESP_FILE')); print(d.get('throughput','?'))" 2>/dev/null || echo "?")
            echo "${GREEN}OK${NC} (${ELAPSED}s, Peak: ${PEAK}MB, TPS: ${THRU}, DB: ${DBCNT})"
            RESULT_MATRIX[${STRAT}_${HEAP}]="OK"
            PEAK_MATRIX[${STRAT}_${HEAP}]="${PEAK}"
            TIME_MATRIX[${STRAT}_${HEAP}]="${ELAPSED}"
        else
            if [[ "$HTTP_CODE" == "000" ]]; then
                echo "${RED}FAIL (OOM crash)${NC}"
                RESULT_MATRIX[${STRAT}_${HEAP}]="OOM"
            else
                echo "${RED}FAIL (HTTP $HTTP_CODE)${NC}"
                RESULT_MATRIX[${STRAT}_${HEAP}]="FAIL"
            fi
            PEAK_MATRIX[${STRAT}_${HEAP}]="-"
            TIME_MATRIX[${STRAT}_${HEAP}]="-"
        fi

        # 清理：停止 cpulimit + Spring Boot
        pkill -f "cpulimit.*$SPRING_PID" 2>/dev/null || true
        kill $SPRING_PID 2>/dev/null || true
        wait $SPRING_PID 2>/dev/null || true
        lsof -ti:$PORT 2>/dev/null | xargs kill -9 2>/dev/null || true
        sleep 2
    done
done

# ===== 彙總表 =====
echo ""
echo "${CYAN}${BOLD}══════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════${NC}"
echo "${CYAN}${BOLD}  Intel Xeon Gold 6338 VM 模擬測試結果${NC}"
echo "${CYAN}${BOLD}  CPU: 8 vCPU @ 2.00 GHz | Tomcat × 10 服務 + Node.js/Angular${NC}"
echo "${CYAN}${BOLD}══════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════${NC}"
echo ""

# ===== 耗時表 =====
echo "${BOLD}【耗時 (秒)】${NC}"
printf "${BOLD}%-28s${NC}" "策略 \\ Heap"
for H in "${HEAP_SIZES[@]}"; do
    printf "%10s" "${H}MB"
done
echo ""
echo "──────────────────────────────────────────────────────────────────────────────────────────────────────────────"

for STRAT in "${STRATEGIES[@]}"; do
    SNAME="${STRATEGY_NAMES[$STRAT]}"
    printf "%-28s" "$SNAME"
    for HEAP in "${HEAP_SIZES[@]}"; do
        RES="${RESULT_MATRIX[${STRAT}_${HEAP}]:-N/A}"
        T="${TIME_MATRIX[${STRAT}_${HEAP}]:--}"
        if [[ "$RES" == "OK" ]]; then
            printf "${GREEN}%10s${NC}" "${T}s"
        else
            printf "${RED}%10s${NC}" "$RES"
        fi
    done
    echo ""
done
echo ""

# ===== Peak Memory 表 =====
echo "${BOLD}【Peak Memory (MB)】${NC}"
printf "${BOLD}%-28s${NC}" "策略 \\ Heap"
for H in "${HEAP_SIZES[@]}"; do
    printf "%10s" "${H}MB"
done
echo ""
echo "──────────────────────────────────────────────────────────────────────────────────────────────────────────────"

for STRAT in "${STRATEGIES[@]}"; do
    SNAME="${STRATEGY_NAMES[$STRAT]}"
    printf "%-28s" "$SNAME"
    for HEAP in "${HEAP_SIZES[@]}"; do
        RES="${RESULT_MATRIX[${STRAT}_${HEAP}]:-N/A}"
        P="${PEAK_MATRIX[${STRAT}_${HEAP}]:--}"
        if [[ "$RES" == "OK" ]]; then
            printf "${GREEN}%10s${NC}" "$P"
        else
            printf "${RED}%10s${NC}" "-"
        fi
    done
    echo ""
done
echo ""

# ===== 最低需求彙整 =====
echo "${CYAN}${BOLD}【各策略最低 Heap 需求 & 建議】${NC}"
echo ""
printf "${BOLD}  %-28s %-12s %-14s %-10s  %s${NC}\n" "策略" "最低 Heap" "Peak Memory" "耗時" "8GB VM 可用？"
echo "  ─────────────────────────────────────────────────────────────────────────────────────"

for STRAT in "${STRATEGIES[@]}"; do
    SNAME="${STRATEGY_NAMES[$STRAT]}"
    MIN_HEAP="N/A"
    MIN_PEAK="-"
    MIN_TIME="-"
    for HEAP in "${HEAP_SIZES[@]}"; do
        VAL="${RESULT_MATRIX[${STRAT}_${HEAP}]:-N/A}"
        if [[ "$VAL" == "OK" ]]; then
            MIN_HEAP="${HEAP}MB"
            MIN_PEAK="${PEAK_MATRIX[${STRAT}_${HEAP}]:-?}MB"
            MIN_TIME="${TIME_MATRIX[${STRAT}_${HEAP}]:-?}s"
            break
        fi
    done

    # 8GB VM 判斷 (可用 heap ~256-768MB)
    MIN_NUM=${MIN_HEAP%MB}
    if [[ "$MIN_NUM" =~ ^[0-9]+$ ]] && (( MIN_NUM <= 512 )); then
        VERDICT="${GREEN}✓ 可用${NC}"
    elif [[ "$MIN_NUM" =~ ^[0-9]+$ ]] && (( MIN_NUM <= 768 )); then
        VERDICT="${YELLOW}△ 勉強${NC}"
    else
        VERDICT="${RED}✗ 不可用${NC}"
    fi

    printf "  %-28s %-12s %-14s %-10s  %b\n" "$SNAME" "$MIN_HEAP" "$MIN_PEAK" "$MIN_TIME" "$VERDICT"
done

echo ""
echo "${CYAN}${BOLD}【生產環境配置建議 — Intel Xeon Gold 6338 VM】${NC}"
echo ""
echo "  ┌──────────────┬──────────┬──────────────┬─────────────────────────────────────────────┐"
echo "  │ VM RAM       │ 可用CPU  │ 本服務 Heap  │ 建議                                        │"
echo "  ├──────────────┼──────────┼──────────────┼─────────────────────────────────────────────┤"
echo "  │  8GB         │ 8 vCPU   │ 256~768MB    │ 推薦 Streaming/BatchChunk/MemoryMapped      │"
echo "  │ 16GB         │ 8 vCPU   │ 1~2GB        │ 推薦 List分頁, 全部策略皆可                  │"
echo "  │ 32GB         │ 8 vCPU   │ 2~4GB        │ 任何策略皆可, 建議 List分頁 最穩定           │"
echo "  └──────────────┴──────────┴──────────────┴─────────────────────────────────────────────┘"
echo ""
echo "  ${YELLOW}注意: 此模擬透過 cpulimit 限制 CPU 使用率 (~${CPULIMIT_PCT}%) 近似 8×Xeon@2GHz${NC}"
echo "  ${YELLOW}      實際效能受 VM hypervisor overhead、NUMA 拓撲、磁碟 I/O 等因素影響${NC}"
echo "  ${YELLOW}      建議以此結果為基準，在實際 VM 上做最終驗證${NC}"
echo ""
echo "${GREEN}${BOLD}Xeon VM 模擬測試完成。${NC}"
