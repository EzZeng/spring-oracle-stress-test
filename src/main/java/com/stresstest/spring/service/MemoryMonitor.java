package com.stresstest.spring.service;

/**
 * 記憶體與效能監控工具。
 */
public class MemoryMonitor {

    private long startTime;
    private long startMemory;
    private long peakMemory;
    private volatile boolean monitoring;
    private Thread monitorThread;

    public void start() {
        Runtime runtime = Runtime.getRuntime();
        runtime.gc();
        try { Thread.sleep(200); } catch (InterruptedException ignored) {}

        startTime = System.currentTimeMillis();
        startMemory = runtime.totalMemory() - runtime.freeMemory();
        peakMemory = startMemory;
        monitoring = true;

        monitorThread = new Thread(new Runnable() {
            @Override
            public void run() {
                while (monitoring) {
                    long used = runtime.totalMemory() - runtime.freeMemory();
                    if (used > peakMemory) {
                        peakMemory = used;
                    }
                    try { Thread.sleep(100); } catch (InterruptedException ignored) { break; }
                }
            }
        }, "memory-monitor");
        monitorThread.setDaemon(true);
        monitorThread.start();
    }

    public BenchmarkResult stop(String strategyName, long rowsProcessed) {
        monitoring = false;
        if (monitorThread != null) {
            try { monitorThread.join(1000); } catch (InterruptedException ignored) {}
        }

        long endTime = System.currentTimeMillis();
        Runtime runtime = Runtime.getRuntime();
        long endMemory = runtime.totalMemory() - runtime.freeMemory();

        return new BenchmarkResult(
                strategyName,
                endTime - startTime,
                startMemory,
                endMemory,
                peakMemory,
                runtime.maxMemory(),
                runtime.totalMemory(),
                rowsProcessed
        );
    }

    public static class BenchmarkResult {
        public final String strategyName;
        public final long elapsedMs;
        public final long startMemory;
        public final long endMemory;
        public final long peakMemory;
        public final long maxMemory;
        public final long totalMemory;
        public final long rowsProcessed;

        public BenchmarkResult(String strategyName, long elapsedMs,
                               long startMemory, long endMemory, long peakMemory,
                               long maxMemory, long totalMemory, long rowsProcessed) {
            this.strategyName = strategyName;
            this.elapsedMs = elapsedMs;
            this.startMemory = startMemory;
            this.endMemory = endMemory;
            this.peakMemory = peakMemory;
            this.maxMemory = maxMemory;
            this.totalMemory = totalMemory;
            this.rowsProcessed = rowsProcessed;
        }

        public String toDisplayString() {
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("策略: %s\n", strategyName));
            sb.append(String.format("處理行數: %,d\n", rowsProcessed));
            sb.append(String.format("總耗時: %,d ms (%.2f 秒)\n", elapsedMs, elapsedMs / 1000.0));
            if (elapsedMs > 0) {
                sb.append(String.format("吞吐量: %,.0f 行/秒\n", rowsProcessed / (elapsedMs / 1000.0)));
            }
            sb.append(String.format("峰值記憶體: %.2f MB\n", peakMemory / (1024.0 * 1024.0)));
            sb.append(String.format("記憶體使用率: %.2f%%\n", peakMemory * 100.0 / maxMemory));
            return sb.toString();
        }
    }
}
