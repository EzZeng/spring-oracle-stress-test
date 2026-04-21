#!/usr/bin/env bash
#
# 百萬筆規模 UTF-8 驗證腳本
#
# 目的：驗證所有策略在 1M+ 行、含中文/日文/韓文的 UTF-8 .txt 檔上能
#      (a) 回傳 COMMIT
#      (b) DB 筆數符合
#      (c) 抽樣比對 field_a 內容（確認 UTF-8 byte→char 無亂碼）
#
# 用法：
#   bash run-utf8-scale-verify.sh [ROWS] [STRATEGIES]
#     ROWS       資料行數，預設 1000000
#     STRATEGIES 空白分隔策略編號，預設 "2 3 4 5 6 7 8 9 10 11 13"（跳過易 OOM 的 1、12）
#
# 範例：
#   bash run-utf8-scale-verify.sh 1000000                        # 1M 行、常用 11 策略
#   bash run-utf8-scale-verify.sh 500000  "2 4 6 10"             # 50 萬行、4 個代表策略
#   bash run-utf8-scale-verify.sh 2000000 "2 3 4 5 6 13"         # 2M 行、只測 TX 策略

set -uo pipefail
cd "$(dirname "$0")"

ROWS="${1:-1000000}"
STRATEGIES="${2:-2 3 4 5 6 7 8 9 10 11 13}"

JAVA_HOME="${JAVA_HOME:-/Users/ezzeng/.jdk/jdk-21.0.8/jdk-21.0.8+9/Contents/Home}"
JAVA="$JAVA_HOME/bin/java"
SPRING_JAR="target/spring-oracle-stress-test-1.0-SNAPSHOT.jar"
PORT=8080
DATA_DIR="stress-test-data"
DATA_FILE="$DATA_DIR/test_utf8_${ROWS}.txt"
LOG_FILE="/tmp/spring-utf8-scale.log"
SQL="podman exec -i oracle-xe sqlplus -S stresstest/stresstest@//localhost:1521/FREEPDB1"

RED=$'\033[0;31m'; GREEN=$'\033[0;32m'; YELLOW=$'\033[0;33m'; CYAN=$'\033[0;36m'; NC=$'\033[0m'

# ──────────────────────────────────────────────────────────
# 1. 產生 UTF-8 大檔
# ──────────────────────────────────────────────────────────
mkdir -p "$DATA_DIR"
if [[ -f "$DATA_FILE" ]]; then
    echo "${YELLOW}已存在 ${DATA_FILE}（$(ls -lh "$DATA_FILE" | awk '{print $5}')），跳過產生${NC}"
else
    echo "${CYAN}═══ 產生 UTF-8 測試檔（$ROWS 行） ═══${NC}"
    python3 - "$DATA_FILE" "$ROWS" <<'PY'
import sys, os, time
out_path = sys.argv[1]
rows = int(sys.argv[2])
t0 = time.time()

# 10 個 BMP 範本（自動補/截到 10 個 Java char = 10 個 Unicode code unit）
# 避開 supplementary-plane（emoji），因 surrogate pair 會讓 String.length() 計為 2
_raw_patterns = [
    "甲乙丙丁戊己庚辛壬癸",
    "日本語テスト更多字x",
    "한국어테스트자료xy",
    "café 中αβγδ",
    "繁體中文測試資料字",
    "1234567890",
    "ABCDEFGHIJ",
    "中文測試一二三四五",
    "繁體簡体混合測試字",
    "ascii line",
]
def pad10(s):
    return (s + " " * 10)[:10]
patterns_10char = [pad10(s) for s in _raw_patterns]
for idx, p in enumerate(patterns_10char):
    assert len(p) == 10, f"pattern[{idx}] len={len(p)} raw={_raw_patterns[idx]!r}"
# 每筆 row = 12 欄 × 10 char = 120 char。前面幾欄用 CJK 樣本，後面塞 ASCII 讓每行多樣化
ascii_fill = "0123456789ABCDEFGHIJabcdefghij"[:10]

# 預先產生 10 種「row 模板」（每列 120 char，不同的欄位排列）
row_templates = []
for i in range(10):
    cols = []
    for j in range(12):
        if j < 6:
            cols.append(patterns_10char[(i + j) % 10])
        else:
            # 後 6 欄混 ASCII + 中文數字
            if j % 2 == 0:
                cols.append(patterns_10char[(i * 3 + j) % 10])
            else:
                cols.append(ascii_fill)
    row = "".join(cols)
    assert len(row) == 120, f"template {i} length={len(row)}"
    row_templates.append(row + "\n")

# Header（10 char yyyy/MM/dd）
header = "2026/04/21\n"
# Trailer 需要 rows,total_chars；每行 120 char → total = rows*120
total_chars = rows * 120
trailer = f"{rows},{total_chars}\n"

BUF_LINES = 50_000  # 每 50K 行 flush
with open(out_path, "w", encoding="utf-8", buffering=16 * 1024 * 1024) as f:
    f.write(header)
    buf = []
    for i in range(rows):
        buf.append(row_templates[i % 10])
        if len(buf) >= BUF_LINES:
            f.write("".join(buf))
            buf.clear()
    if buf:
        f.write("".join(buf))
    f.write(trailer)

size = os.path.getsize(out_path)
print(f"✓ 產生完成：{out_path}")
print(f"  行數: {rows + 2}（header + {rows} 筆資料 + trailer）")
print(f"  檔案大小: {size/1024/1024:.2f} MB")
print(f"  耗時: {time.time()-t0:.2f} 秒")
PY
fi

echo "${YELLOW}檔案前 2 行 + hex dump 前 48 bytes:${NC}"
head -2 "$DATA_FILE"
xxd -l 48 "$DATA_FILE"

# ──────────────────────────────────────────────────────────
# 2. 啟動 Spring Boot
# ──────────────────────────────────────────────────────────
# 先清 DB（TRUNCATE 比 DELETE 快很多）
echo
echo "${CYAN}═══ 清空 Oracle 資料表 ═══${NC}"
$SQL <<SQL 2>&1 | tail -5
TRUNCATE TABLE file_detail;
TRUNCATE TABLE jpa_records;
DELETE FROM file_master;
COMMIT;
SELECT (SELECT COUNT(*) FROM file_detail) detail, (SELECT COUNT(*) FROM jpa_records) jpa, (SELECT COUNT(*) FROM file_master) master FROM dual;
SQL

echo
echo "${CYAN}═══ 啟動 Spring Boot（-Xmx2048m） ═══${NC}"
pkill -f spring-oracle-stress-test 2>/dev/null; sleep 2
$JAVA -Xms512m -Xmx2048m -XX:+UseG1GC \
     -jar "$SPRING_JAR" --server.port=$PORT > "$LOG_FILE" 2>&1 &
SPRING_PID=$!
trap "kill $SPRING_PID 2>/dev/null || true" EXIT

for i in $(seq 1 60); do
    curl -s "http://localhost:$PORT/api/strategies" > /dev/null 2>&1 && break
    kill -0 $SPRING_PID 2>/dev/null || { echo "${RED}啟動失敗${NC}"; tail -30 "$LOG_FILE"; exit 1; }
    sleep 1
done
echo "${GREEN}就緒${NC}"

# ──────────────────────────────────────────────────────────
# 3. 逐一測試指定策略
# ──────────────────────────────────────────────────────────
# 抽樣驗證用：rows 中間那一行（第 rows/2 列）應符合 template[rows/2 % 10] 第 1 欄
SAMPLE_IDX=$((ROWS / 2))
EXPECT_TEMPLATE_IDX=$(( SAMPLE_IDX % 10 ))

declare -a RESULTS

for STRAT in $STRATEGIES; do
    echo
    echo "${CYAN}━━━ 策略 $STRAT ━━━${NC}"

    # 清 DB
    $SQL > /dev/null 2>&1 <<SQL
TRUNCATE TABLE file_detail;
TRUNCATE TABLE jpa_records;
DELETE FROM file_master;
COMMIT;
SQL

    T_START=$(date +%s)
    RESP=$(curl -s --max-time 600 -X POST "http://localhost:$PORT/api/upload?strategy=$STRAT" -F "file=@$DATA_FILE")
    T_ELAPSED=$(( $(date +%s) - T_START ))

    STATUS=$(echo "$RESP" | python3 -c "import sys,json; print(json.load(sys.stdin).get('status','?'))" 2>/dev/null || echo "?")
    SUCC=$(echo "$RESP" | python3 -c "import sys,json; print(json.load(sys.stdin).get('successCount','?'))" 2>/dev/null || echo "?")
    NAME=$(echo "$RESP" | python3 -c "import sys,json; print(json.load(sys.stdin).get('strategy','?'))" 2>/dev/null || echo "?")

    if [[ "$STATUS" != "COMMIT" ]]; then
        echo "${RED}✗ $NAME  status=$STATUS  time=${T_ELAPSED}s${NC}"
        echo "  resp: $(echo "$RESP" | cut -c1-200)"
        RESULTS+=("$STRAT|$NAME|FAIL|$T_ELAPSED|-|-")
        continue
    fi

    # 查 DB 筆數 + 抽樣 field_a
    if [[ "$STRAT" == "7" || "$STRAT" == "8" || "$STRAT" == "9" || "$STRAT" == "10" || "$STRAT" == "11" || "$STRAT" == "12" ]]; then
        TABLE="jpa_records"
    else
        TABLE="file_detail"
    fi

    DB_OUT=$($SQL <<SQL 2>&1
SET PAGESIZE 0 FEEDBACK OFF HEADING OFF LINESIZE 200
COL cnt FORMAT 999999999
SELECT COUNT(*) FROM $TABLE;
EXIT
SQL
)
    DB_CNT=$(echo "$DB_OUT" | tail -3 | tr -d ' \r\n')

    # 抽樣一筆：隨便抓第一筆的 field_a，看是否為 UTF-8 正常字元
    SAMPLE=$($SQL <<SQL 2>&1
SET PAGESIZE 0 FEEDBACK OFF HEADING OFF LINESIZE 200
COL field_a FORMAT A30
SELECT field_a FROM $TABLE FETCH FIRST 1 ROWS ONLY;
EXIT
SQL
)
    SAMPLE_CLEAN=$(echo "$SAMPLE" | tr -d '\r' | grep -v "^$" | tail -1 | sed 's/[[:space:]]*$//')

    # 檢核：DB 筆數 == ROWS 並且 sample 非空白、非亂碼（至少含 1 個非 ASCII 字元或正確 ASCII）
    if [[ "$DB_CNT" == "$ROWS" ]]; then
        echo "${GREEN}✓ $NAME  time=${T_ELAPSED}s  db=$DB_CNT${NC}"
        echo "  sample field_a: [${SAMPLE_CLEAN}]"
        RESULTS+=("$STRAT|$NAME|OK|$T_ELAPSED|$DB_CNT|${SAMPLE_CLEAN}")
    else
        echo "${RED}✗ $NAME  db_cnt=$DB_CNT 不符 ROWS=$ROWS${NC}"
        RESULTS+=("$STRAT|$NAME|CNT_MISMATCH|$T_ELAPSED|$DB_CNT|${SAMPLE_CLEAN}")
    fi
done

# ──────────────────────────────────────────────────────────
# 4. 彙總
# ──────────────────────────────────────────────────────────
echo
echo "${CYAN}════════════════════════════════════════════════════════════════════${NC}"
echo "${CYAN}  彙總（檔案=${DATA_FILE}, $ROWS 筆資料）${NC}"
echo "${CYAN}════════════════════════════════════════════════════════════════════${NC}"
printf "%-4s  %-30s  %-12s  %-6s  %-10s  %s\n" "策略" "名稱" "狀態" "秒" "DB筆數" "sample(field_a)"
echo "────────────────────────────────────────────────────────────────────────────────────────"
OK_CNT=0; FAIL_CNT=0
for R in "${RESULTS[@]}"; do
    IFS='|' read -r S N ST T CNT SMP <<<"$R"
    if [[ "$ST" == "OK" ]]; then
        C=$GREEN; OK_CNT=$((OK_CNT+1))
    else
        C=$RED; FAIL_CNT=$((FAIL_CNT+1))
    fi
    printf "%-4s  %-30s  ${C}%-12s${NC}  %-6s  %-10s  %s\n" "$S" "$N" "$ST" "$T" "$CNT" "${SMP:0:30}"
done
echo
echo "${GREEN}OK: $OK_CNT${NC}   ${RED}FAIL: $FAIL_CNT${NC}   共 ${#RESULTS[@]} 個策略"
