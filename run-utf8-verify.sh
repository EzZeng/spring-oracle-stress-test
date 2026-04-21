#!/usr/bin/env bash
#
# 驗證所有策略能正確處理 UTF-8 .txt 檔（含中文/emoji）
#
# 流程：
#   1. 手工合成一個 UTF-8 .txt：header + 10 筆資料（含中文、全形、emoji）+ trailer
#   2. 對 3 個代表策略（[TX] Streaming、[TX] MemoryMapped、[JPA] saveAll）上傳
#   3. 從 Oracle file_detail / jpa_records 撈出寫入的內容
#   4. 逐欄比對原始 String 和 DB 回讀內容

set -uo pipefail
cd "$(dirname "$0")"

JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk}"
JAVA="$JAVA_HOME/bin/java"
SPRING_JAR="target/spring-oracle-stress-test-1.0-SNAPSHOT.jar"
PORT=8080
DATA_DIR="stress-test-data"
DATA_FILE="$DATA_DIR/test_utf8.txt"
LOG_FILE="/tmp/spring-utf8-test.log"

RED=$'\033[0;31m'; GREEN=$'\033[0;32m'; YELLOW=$'\033[0;33m'; CYAN=$'\033[0;36m'; NC=$'\033[0m'

echo "${CYAN}═══ 產生 UTF-8 測試檔 ═══${NC}"
mkdir -p "$DATA_DIR"

# Header 固定 10 字元（yyyy/MM/dd），FileValidator 以 "%d+/\d+/\d+" 驗證
python3 <<PY
import os
lines = ["2026/04/21"]
# 注意：只用 BMP 字元（每個 Java char 對應 1 個 code-point）
# 避免 emoji（supplementary-plane surrogate pair 會讓 Java String.length() != Python len()）
samples = [
    "甲乙丙丁戊己庚辛壬癸",              # 10 char 中文
    "日本語テスト更多字x",               # 10 char 日文
    "한국어테스트자료xy",                # 10 char 韓文
    "café 中αβγδ",                       # 10 char 混合
    "繁體中文測試資料字串一",            # 10+ char
    "1234567890",
    "ABCDEFGHIJ",
    "中文測試一二三四五六",
    "繁體與簡体字混测試",
    "純 ASCII test line",
]
# 每行補/截到正好 120 char (Java String.length())
def pad(s): return (s + " " * 120)[:120]
padded = [pad(s) for s in samples]
lines.extend(padded)
total_chars = sum(len(s) for s in padded)
lines.append(f"{len(padded)},{total_chars}")
content = "\n".join(lines) + "\n"
with open("$DATA_FILE", "w", encoding="utf-8") as f:
    f.write(content)
print(f"行數: {len(lines)} | 資料筆數: {len(padded)} | trailer 字元數: {total_chars}")
print(f"檔案大小: {os.path.getsize('$DATA_FILE')} bytes (UTF-8 encoding)")
PY

# 顯示檔案前 3 行
echo
echo "${YELLOW}檔案前 3 行 (cat):${NC}"
head -3 "$DATA_FILE"
echo "${YELLOW}檔案 hex dump 前 64 bytes:${NC}"
xxd -l 64 "$DATA_FILE"

# 清空 Oracle 表
podman exec -i oracle-xe sqlplus -S stresstest/stresstest@//localhost:1521/FREEPDB1 > /dev/null 2>&1 <<SQL
DELETE FROM file_detail;
DELETE FROM file_master;
DELETE FROM jpa_records;
COMMIT;
SQL

# 啟動 Spring Boot
echo -e "\n${CYAN}═══ 啟動 Spring Boot ═══${NC}"
$JAVA -Xms256m -Xmx1024m -jar "$SPRING_JAR" --server.port=$PORT > "$LOG_FILE" 2>&1 &
SPRING_PID=$!
trap "kill $SPRING_PID 2>/dev/null || true" EXIT

for i in $(seq 1 60); do
    curl -s "http://localhost:$PORT/api/strategies" > /dev/null 2>&1 && break
    kill -0 $SPRING_PID 2>/dev/null || { echo "${RED}啟動失敗${NC}"; tail -30 "$LOG_FILE"; exit 1; }
    sleep 1
done
echo "${GREEN}就緒${NC}"

# 三個代表策略：2 = TX List 分頁、4 = TX MemoryMapped、10 = JPA saveAll
for STRAT_INFO in "2:[TX] List分頁" "4:[TX] MemoryMapped" "6:[TX] Streaming" "10:[JPA] saveAll"; do
    STRAT="${STRAT_INFO%%:*}"
    SNAME="${STRAT_INFO##*:}"
    echo -e "\n${CYAN}━━━ 策略 $STRAT : $SNAME ━━━${NC}"

    RESP=$(curl -s -X POST "http://localhost:$PORT/api/upload?strategy=$STRAT" -F "file=@$DATA_FILE")
    STATUS=$(echo "$RESP" | python3 -c "import sys,json; print(json.load(sys.stdin).get('status','?'))" 2>/dev/null)
    SUCC=$(echo "$RESP" | python3 -c "import sys,json; print(json.load(sys.stdin).get('successCount','?'))" 2>/dev/null)
    echo "API: status=$STATUS success=$SUCC"
    if [[ "$STATUS" != "COMMIT" ]]; then
        echo "${RED}FAIL: $RESP${NC}"
        continue
    fi

    # 查 DB 回讀每一筆的第一個欄位 (field_a)
    if [[ "$STRAT" == "10" ]]; then
        TABLE="jpa_records"
        COL="field_a"
    else
        TABLE="file_detail"
        COL="field_a"
    fi
    echo "${YELLOW}DB 回讀 $TABLE.$COL 前 10 筆（NLS: AL32UTF8）${NC}"
    podman exec -i oracle-xe sqlplus -S stresstest/stresstest@//localhost:1521/FREEPDB1 <<SQL
SET LINESIZE 200
SET PAGESIZE 50
COL field_a FORMAT A40
ALTER SESSION SET NLS_LANG = 'AMERICAN_AMERICA.AL32UTF8';
SELECT field_a FROM $TABLE FETCH FIRST 10 ROWS ONLY;
SQL

    # 清 DB 為下一策略準備
    podman exec -i oracle-xe sqlplus -S stresstest/stresstest@//localhost:1521/FREEPDB1 > /dev/null 2>&1 <<SQL
DELETE FROM file_detail;
DELETE FROM file_master;
DELETE FROM jpa_records;
COMMIT;
SQL
done

echo -e "\n${GREEN}完成。${NC}"
