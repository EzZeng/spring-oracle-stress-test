package com.stresstest.spring.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 分階段 CPU / RAM 監控器。
 *
 * 每個處理階段記錄：
 *   - 耗時 (ms)
 *   - 峰值 heap 使用量 (MB)
 *   - 結束時 heap 使用量 (MB)
 *   - 即時 CPU 使用率 (%)
 *   - Heap 使用率 (% of max)
 *
 * 背景取樣執行緒每 200ms 追蹤峰值 heap。
 *
 * 用法：
 *   PhaseMonitor monitor = new PhaseMonitor();
 *   monitor.start();
 *   monitor.beginPhase("讀取檔案");
 *   // ... 做事 ...
 *   monitor.beginPhase("DB寫入");
 *   // ... 做事 ...
 *   monitor.stop();
 *   Map&lt;String, Object&gt; report = monitor.getReport();
 */
public class PhaseMonitor {

    private static final Logger log = LoggerFactory.getLogger(PhaseMonitor.class);

    private final Runtime runtime = Runtime.getRuntime();
    private final com.sun.management.OperatingSystemMXBean osMxBean;

    private final List<PhaseSnapshot> phases = new ArrayList<>();
    private volatile boolean sampling;
    private Thread samplerThread;

    // 當前階段追蹤
    private String currentPhaseName;
    private long currentPhaseStartNano;
    private volatile long currentPhasePeakHeap;

    public PhaseMonitor() {
        this.osMxBean = (com.sun.management.OperatingSystemMXBean)
                ManagementFactory.getOperatingSystemMXBean();
    }

    /** 啟動背景取樣（建議在 process 前呼叫） */
    public void start() {
        runtime.gc();
        try { Thread.sleep(100); } catch (InterruptedException ignored) {}

        sampling = true;
        samplerThread = new Thread(new Runnable() {
            @Override
            public void run() {
                while (sampling) {
                    long used = runtime.totalMemory() - runtime.freeMemory();
                    if (used > currentPhasePeakHeap) {
                        currentPhasePeakHeap = used;
                    }
                    try { Thread.sleep(200); } catch (InterruptedException e) { break; }
                }
            }
        }, "phase-monitor");
        samplerThread.setDaemon(true);
        samplerThread.start();
    }

    /** 開始一個新階段（自動結束上一個階段） */
    public void beginPhase(String name) {
        if (currentPhaseName != null) {
            recordCurrentPhase();
        }
        currentPhaseName = name;
        currentPhaseStartNano = System.nanoTime();
        currentPhasePeakHeap = runtime.totalMemory() - runtime.freeMemory();
    }

    private void recordCurrentPhase() {
        long elapsedMs = (System.nanoTime() - currentPhaseStartNano) / 1_000_000;
        long endHeap = runtime.totalMemory() - runtime.freeMemory();
        if (endHeap > currentPhasePeakHeap) {
            currentPhasePeakHeap = endHeap;
        }
        double cpuPct = osMxBean.getProcessCpuLoad() * 100.0;

        phases.add(new PhaseSnapshot(
                currentPhaseName, elapsedMs,
                currentPhasePeakHeap, endHeap,
                cpuPct, runtime.maxMemory()));

        log.info("[PhaseMonitor] {} | {}ms | peak={}MB | heap%={} | cpu={}%",
                currentPhaseName, elapsedMs,
                String.format("%.1f", currentPhasePeakHeap / (1024.0 * 1024.0)),
                String.format("%.1f", currentPhasePeakHeap * 100.0 / runtime.maxMemory()),
                String.format("%.1f", cpuPct));
    }

    /** 停止監控並記錄最後一個階段 */
    public void stop() {
        if (currentPhaseName != null) {
            recordCurrentPhase();
            currentPhaseName = null;
        }
        sampling = false;
        if (samplerThread != null) {
            try { samplerThread.join(1000); } catch (InterruptedException ignored) {}
        }
    }

    /** 產生結構化報告（可直接轉為 JSON） */
    public Map<String, Object> getReport() {
        Map<String, Object> report = new LinkedHashMap<>();

        long totalMs = 0;
        long globalPeak = 0;
        for (PhaseSnapshot p : phases) {
            totalMs += p.elapsedMs;
            if (p.peakHeap > globalPeak) globalPeak = p.peakHeap;
        }

        List<Map<String, Object>> phaseList = new ArrayList<>();
        for (PhaseSnapshot p : phases) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("phase", p.name);
            m.put("elapsedMs", p.elapsedMs);
            m.put("pct", totalMs > 0 ? String.format("%.1f%%", p.elapsedMs * 100.0 / totalMs) : "N/A");
            m.put("peakHeapMB", String.format("%.2f", p.peakHeap / (1024.0 * 1024.0)));
            m.put("endHeapMB", String.format("%.2f", p.endHeap / (1024.0 * 1024.0)));
            m.put("heapUsagePct", String.format("%.1f%%", p.peakHeap * 100.0 / p.maxHeap));
            m.put("cpuPct", String.format("%.1f%%", p.cpuPct));
            phaseList.add(m);
        }

        report.put("phases", phaseList);
        report.put("totalMs", totalMs);
        report.put("globalPeakHeapMB", String.format("%.2f", globalPeak / (1024.0 * 1024.0)));
        report.put("maxHeapMB", String.format("%.0f", runtime.maxMemory() / (1024.0 * 1024.0)));

        return report;
    }

    static class PhaseSnapshot {
        final String name;
        final long elapsedMs;
        final long peakHeap;
        final long endHeap;
        final double cpuPct;
        final long maxHeap;

        PhaseSnapshot(String name, long elapsedMs, long peakHeap, long endHeap,
                      double cpuPct, long maxHeap) {
            this.name = name;
            this.elapsedMs = elapsedMs;
            this.peakHeap = peakHeap;
            this.endHeap = endHeap;
            this.cpuPct = cpuPct;
            this.maxHeap = maxHeap;
        }
    }
}
