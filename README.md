# Spring Boot Oracle 壓力測試

Spring Boot 2.7.18 + Oracle XA（Atomikos JTA）批次寫入壓力測試工具。

模擬以下場景：

- 上傳 **120 萬筆** CSV 資料（~138 MB），以不同策略寫入 Oracle
- 多業務類型（BIZ_A / BIZ_B / BIZ_C）並行上傳、放行、雙 DB 寫入
- 原檔下載（BLOB 儲存，~156 MB TXT 回傳）
- 網路延遲模擬（0 / 1 / 2 / 5 / 10 / 20 ms per round-trip）
- Heap 閾值測試（各策略最低可用記憶體）
- 生產環境 VM 模擬（Intel Xeon Gold 6338, 8 vCPU @ 2.0 GHz）

---

## 目錄

- [系統需求](#系統需求)
- [Oracle 容器設定](#oracle-容器設定)
- [建置](#建置)
- [執行測試](#執行測試)
- [測試腳本說明](#測試腳本說明)
- [寫入策略清單](#寫入策略清單)
- [REST API](#rest-api)
- [專案結構](#專案結構)

---

## 系統需求

| 工具 | 版本 |
|------|------|
| Java | 21（OpenJDK 或 GraalVM） |
| Maven | 3.8+ |
| Podman 或 Docker | 任意版本（用於啟動 Oracle XE） |
| OS | macOS / Linux |
| RAM | 建議 8 GB 以上（下載測試需 -Xmx6g） |

macOS 安裝建議：

```bash
brew install openjdk@21 maven podman
```

---

## Oracle 容器設定

### 1. 建立並啟動容器（首次執行）

```bash
podman run -d \
  --name oracle-xe \
  -p 1521:1521 \
  -e ORACLE_PASSWORD=test1234 \
  -e APP_USER=stresstest \
  -e APP_USER_PASSWORD=stresstest \
  gvenzl/oracle-free:23-slim-faststart
```

等待 Oracle 就緒（約 30–90 秒）：

```bash
podman logs -f oracle-xe | grep "DATABASE IS READY TO USE"
```

### 2. 後續啟動

```bash
podman machine start   # macOS 需要先啟動 Podman 虛擬機
podman start oracle-xe
```

### 3. 資料庫連線資訊

| 項目 | 值 |
|------|----|
| Host | localhost:1521 |
| Service | FREEPDB1 |
| User | stresstest |
| Password | stresstest |

> **注意**：應用程式啟動時會自動建立所需資料表（`upload_case`、`biz_a_detail`、`biz_b_detail`、`biz_c_detail`、`download_file`、`file_master`、`file_detail`、`jpa_records` 等），無需手動執行 DDL。

---

## 建置

```bash
cd spring-oracle-stress-test
mvn clean package -DskipTests
```

產出：`target/spring-oracle-stress-test-1.0-SNAPSHOT.jar`

---

## 執行測試

所有腳本均會自動完成以下步驟：

1. 檢查/啟動 Oracle 容器
2. 產生測試資料（若不存在）
3. 編譯打包（若 JAR 不存在）
4. 啟動 Spring Boot 服務
5. 執行壓力測試
6. 停止 Spring Boot 服務並輸出報告

### 快速開始（基本功能測試）

```bash
# 策略 1，執行 3 次完整流程（上傳 → 放行 → 雙DB寫入）
bash run-flow-stress-test.sh 1 3
```

---

## 測試腳本說明

### `run-spring-test.sh` — 基礎上傳測試

測試單次 100 萬筆 CSV 上傳寫入，比較各策略效能。

```bash
bash run-spring-test.sh          # 全部策略
bash run-spring-test.sh 2 6      # 只跑策略 2 和 6
```

---

### `run-flow-stress-test.sh` — 完整流程壓力測試

模擬完整業務流程：**上傳 → 建立案件 → 待辦查詢 → 放行（寫入雙 DB）**

重複指定次數，統計每次完整流程耗時。

```bash
bash run-flow-stress-test.sh            # 策略 1，50 次
bash run-flow-stress-test.sh 3          # 策略 3，50 次
bash run-flow-stress-test.sh 1 10       # 策略 1，10 次
```

---

### `run-multi-biz-stress-test.sh` — 多業務並行壓力測試

3 種業務（BIZ_A / BIZ_B / BIZ_C）同時上傳即放行，每次迭代並行執行，統計各業務耗時分佈。

```bash
bash run-multi-biz-stress-test.sh          # 策略 1，10 次迭代
bash run-multi-biz-stress-test.sh 3 5      # 策略 3，5 次迭代
```

---

### `run-download-stress-test.sh` — 原檔下載壓力測試

完整流程加上下載：**上傳 → 放行 → 下載（~156 MB TXT）**

下載檔案儲存至 `downloaded-files/`。

```bash
bash run-download-stress-test.sh          # 策略 1，3 次迭代
bash run-download-stress-test.sh 3 5      # 策略 3，5 次迭代
```

> RAM 需求：此測試需 `-Xmx6g`（三個並行下載各約 320 MB）

---

### `run-latency-test.sh` — 網路延遲模擬測試

在每次 DB round-trip 注入固定延遲，模擬跨網段部署情境。

```bash
bash run-latency-test.sh             # 策略 6，延遲 0/1/2/5/10/20 ms
bash run-latency-test.sh 6 9         # 策略 6 和 9
bash run-latency-test.sh all         # 全部策略（耗時長）
```

延遲情境對照：

| 延遲 | 情境 |
|------|------|
| 0 ms | 同機（localhost / 同 VLAN） |
| 1 ms | 同機房跨 VLAN |
| 2 ms | 同機房不同子網段 |
| 5 ms | 跨機房（同 Region） |
| 10 ms | 跨可用區（AZ） |
| 20 ms | 跨 Region |

---

### `run-spring-threshold.sh` — Heap 閾值測試

測試各策略在不同 Heap 大小（128 MB ~ 4 GB）下是否成功，找出最低記憶體需求。

```bash
zsh run-spring-threshold.sh            # 全部策略
zsh run-spring-threshold.sh 2 6        # 只測策略 2 和 6
```

---

### `run-production-threshold.sh` — 生產環境模擬閾值測試

依不同伺服器 RAM 配置（含 OS、Node.js、其他服務）計算本服務實際可用 Heap，進行閾值測試。

```bash
zsh run-production-threshold.sh            # 全部策略
zsh run-production-threshold.sh 2 6        # 只測策略 2 和 6
```

---

### `run-xeon-vm-simulation.sh` — Intel Xeon VM 模擬

在 Apple Silicon 上模擬 Intel Xeon Gold 6338（8 vCPU @ 2.0 GHz）的效能特性。

- `-XX:ActiveProcessorCount=8`：限制 JVM 只看到 8 核
- `cpulimit`：限制 CPU% 模擬 2.0 GHz 時脈

```bash
zsh run-xeon-vm-simulation.sh            # 全部策略
zsh run-xeon-vm-simulation.sh 2 6        # 只測策略 2 和 6
```

---

## 寫入策略清單

啟動應用程式後可呼叫 `GET /api/strategies` 查看編號與策略名稱。共 13 種策略：

| 類型 | 策略名稱 | 說明 |
|------|----------|------|
| TX | `[TX] List 全載入` | 一次讀入全部資料到 List 再批次寫入 |
| TX | `[TX] List分頁（推薦方案）` | 分頁讀取，每頁批次寫入 |
| TX | `[TX] BatchChunk 分批` | 固定大小 chunk 批次寫入 |
| TX | `[TX] Streaming 逐行` | Stream 逐行讀取寫入 |
| TX | `[TX] MemoryMapped` | Memory-mapped file 讀取 |
| TX | `[TX] LinkedList+Iter` | LinkedList 迭代器寫入 |
| TX | `[TX] 管線化分頁（含監控）` | 生產者-消費者 pipeline，含階段計時 |
| JPA | `[JPA] Repository save 逐筆` | JPA save() 逐筆（最慢，供對比） |
| JPA | `[JPA] Repository saveAll` | JPA saveAll() 批次 |
| JPA | `[JPA] BatchChunk 分批` | JPA 分批 chunk |
| JPA | `[JPA] Streaming 逐行` | JPA Stream 逐行 |
| JPA | `[JPA] ThreadPool save (多執行緒)` | JPA 多執行緒 save |
| JPA | `[JPA] List分頁（推薦）` | JPA 分頁，含詳細瓶頸分析 |

---

## REST API

服務預設跑在 `http://localhost:8080`。

| Method | Path | 說明 |
|--------|------|------|
| `GET` | `/api/strategies` | 列出所有策略 |
| `POST` | `/api/upload` | 上傳 CSV 並以指定策略寫入 |
| `GET` | `/api/count` | 查詢目前 Oracle 記錄數 |
| `POST` | `/api/todo/{caseId}/approve` | 放行案件（寫入雙 DB） |
| `GET` | `/api/todo/{caseId}/download` | 下載原檔（回傳 ~156 MB TXT） |

#### POST `/api/upload` 參數

| 參數 | 類型 | 預設 | 說明 |
|------|------|------|------|
| `file` | MultipartFile | 必填 | 上傳的 CSV 資料檔 |
| `strategy` | int | 1 | 策略編號 1~13 |
| `skipInit` | boolean | false | 跳過資料表初始化 |
| `latencyMs` | int | 0 | 注入延遲 ms |
| `bizType` | string | 無 | 業務類型（BIZ_A/BIZ_B/BIZ_C） |

---

## 專案結構

```
spring-oracle-stress-test/
├── src/main/java/com/stresstest/spring/
│   ├── Application.java
│   ├── config/               # DataSource、JTA 設定
│   ├── controller/           # REST API
│   ├── dao/                  # JPA Repository
│   ├── entity/               # JPA Entity
│   ├── model/                # DTO / 值物件
│   ├── service/              # 業務邏輯、DB 操作、監控
│   └── strategy/             # 13 種寫入策略實作
├── src/main/resources/
│   └── application.yml       # Spring Boot 設定（DB 連線、JTA）
├── pom.xml
├── run-spring-test.sh            # 基礎上傳測試
├── run-flow-stress-test.sh       # 完整流程壓力測試
├── run-multi-biz-stress-test.sh  # 多業務並行測試
├── run-download-stress-test.sh   # 原檔下載測試
├── run-latency-test.sh           # 網路延遲模擬測試
├── run-spring-threshold.sh       # Heap 閾值測試
├── run-production-threshold.sh   # 生產環境模擬閾值測試
└── run-xeon-vm-simulation.sh     # Intel Xeon VM 模擬測試
```

---

## 設定說明

### 修改資料庫連線

編輯 `src/main/resources/application.yml`：

```yaml
spring:
  datasource:
    url: jdbc:oracle:thin:@<host>:<port>/<service>
    username: <user>
    password: <password>

domain:
  datasource:
    a:
      jdbc-url: jdbc:oracle:thin:@<host_a>:<port>/<service>
      username: <user>
      password: <password>
    b:
      jdbc-url: jdbc:oracle:thin:@<host_b>:<port>/<service>
      username: <user>
      password: <password>
```

### JVM 調整

各腳本頂部有 `JVM_OPTS` 變數可調整：

```bash
JVM_OPTS="-Xms512m -Xmx4g -XX:+UseG1GC -XX:ActiveProcessorCount=4 -XX:MaxGCPauseMillis=200"
```

---

## 注意事項

- `stress-test-data/` 目錄（測試資料檔）與 `downloaded-files/` 目錄（下載輸出）不需提交版本控制，腳本執行時會自動建立。
- 首次執行腳本時，Maven 會下載相依套件（需網路連線）。
- Atomikos 設定 `enable-logging: false`，JVM crash 後 in-doubt transaction 需手動處理。
