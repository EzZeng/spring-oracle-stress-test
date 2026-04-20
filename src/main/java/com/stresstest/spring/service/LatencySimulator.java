package com.stresstest.spring.service;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 模擬 DB 與 AP 服務位於不同網段時的網路延遲。
 * <p>
 * 原理：透過 JDBC Dynamic Proxy 攔截每一次 DB round-trip
 * （executeXxx / executeBatch / commit / rollback），在執行前注入固定延遲。
 * <p>
 * 典型場景下的 round-trip 延遲參考值：
 * <ul>
 *   <li>同機房同 VLAN：&lt; 0.5ms</li>
 *   <li>同機房跨 VLAN：1–2ms</li>
 *   <li>跨機房（同 Region）：2–5ms</li>
 *   <li>跨 Region：10–50ms</li>
 * </ul>
 * <p>
 * 使用方式（Controller 層）：
 * <pre>
 *   LatencySimulator.configure(10);  // 設定 10ms RTT
 *   // ... 執行策略 ...
 *   long trips = LatencySimulator.getRoundTripCount();
 *   LatencySimulator.configure(0);   // 關閉
 * </pre>
 * DataSource 層的 Connection 包裝由 {@code LatencyDataSourceConfig} 自動完成。
 */
public class LatencySimulator {

    /** 模擬的每次 round-trip 延遲（毫秒） */
    private static final AtomicInteger latencyMs = new AtomicInteger(0);

    /** 本次測試累計的 DB round-trip 次數 */
    private static final AtomicLong roundTripCount = new AtomicLong(0);

    /** 代表一次 DB round-trip 的 JDBC 方法名稱 */
    private static final Set<String> ROUND_TRIP_METHODS = new HashSet<>(Arrays.asList(
            "execute", "executeQuery", "executeUpdate", "executeBatch",
            "executeLargeUpdate", "executeLargeBatch",
            "commit", "rollback"
    ));

    /** 回傳值需要再包 Proxy 的方法（回傳 Statement / PreparedStatement） */
    private static final Set<String> WRAP_RETURN_METHODS = new HashSet<>(Arrays.asList(
            "prepareStatement", "prepareCall", "createStatement"
    ));

    /**
     * 設定模擬延遲，同時重置 round-trip 計數器。
     * @param ms 每次 DB round-trip 的延遲（毫秒），0 = 關閉模擬
     */
    public static void configure(int ms) {
        latencyMs.set(ms);
        roundTripCount.set(0);
    }

    public static int getLatencyMs() {
        return latencyMs.get();
    }

    public static long getRoundTripCount() {
        return roundTripCount.get();
    }

    /**
     * 以 Dynamic Proxy 包裝 JDBC Connection。
     * 所有從此 Connection 建立的 Statement / PreparedStatement 也會自動包裝。
     */
    public static Connection wrapConnection(Connection conn) {
        if (conn == null || Proxy.isProxyClass(conn.getClass())) {
            return conn;
        }
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                new JdbcLatencyHandler(conn));
    }

    // ── 內部：注入延遲 ──────────────────────────────────────────────

    private static void applyLatency() {
        roundTripCount.incrementAndGet();
        int ms = latencyMs.get();
        if (ms > 0) {
            try {
                Thread.sleep(ms);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // ── InvocationHandler ───────────────────────────────────────────

    private static class JdbcLatencyHandler implements InvocationHandler {
        private final Object target;

        JdbcLatencyHandler(Object target) {
            this.target = target;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();

            // round-trip 方法 → 先注入延遲
            if (ROUND_TRIP_METHODS.contains(name)) {
                applyLatency();
            }

            Object result;
            try {
                result = method.invoke(target, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }

            // 包裝回傳的 Statement / PreparedStatement / CallableStatement
            if (WRAP_RETURN_METHODS.contains(name) && result != null
                    && !Proxy.isProxyClass(result.getClass())) {
                if (result instanceof CallableStatement) {
                    return Proxy.newProxyInstance(
                            CallableStatement.class.getClassLoader(),
                            new Class<?>[]{CallableStatement.class},
                            new JdbcLatencyHandler(result));
                } else if (result instanceof PreparedStatement) {
                    return Proxy.newProxyInstance(
                            PreparedStatement.class.getClassLoader(),
                            new Class<?>[]{PreparedStatement.class},
                            new JdbcLatencyHandler(result));
                } else if (result instanceof Statement) {
                    return Proxy.newProxyInstance(
                            Statement.class.getClassLoader(),
                            new Class<?>[]{Statement.class},
                            new JdbcLatencyHandler(result));
                }
            }

            return result;
        }
    }
}
