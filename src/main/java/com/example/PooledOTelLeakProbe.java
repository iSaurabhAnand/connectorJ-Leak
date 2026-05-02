package com.example;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Production-shaped probe for Connector/J OpenTelemetry span retention.
 *
 * This intentionally uses a fixed-size Hikari pool to model Spring/Hibernate style logical
 * Connection.close(), where application code does not own the physical ConnectionImpl lifetime.
 */
public class PooledOTelLeakProbe {

    private static final String JDBC_URL = System.getProperty("db.url",
            "jdbc:mysql://localhost:3307/leaktest?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC");
    private static final String DB_USER = System.getProperty("db.user", "leaktest");
    private static final String DB_PASS = System.getProperty("db.pass", "leaktest");
    private static final int POOL_SIZE = Integer.getInteger("pool.size", 4);
    private static final int ITERATIONS = Integer.getInteger("iterations", 2_000);
    private static final int REPORT_EVERY = Integer.getInteger("report.every", 250);

    private static final Field SESSION_FIELD = field("com.mysql.cj.jdbc.ConnectionImpl", "session");
    private static final Field TELEMETRY_HANDLER_FIELD = field("com.mysql.cj.CoreSession", "telemetryHandler");
    private static final Field SPANS_FIELD = field("com.mysql.cj.otel.OpenTelemetryHandler", "spans");

    public static void main(String[] args) throws Exception {
        System.out.printf("url=%s%npool.size=%d iterations=%d report.every=%d%n%n",
                JDBC_URL, POOL_SIZE, ITERATIONS, REPORT_EVERY);

        List<PhysicalConnectionObservation> observations = exercisePool();

        forceGc();
        printSnapshot("after pool close + GC", observations);

        long stillAlive = observations.stream().filter(o -> o.connectionRef.get() != null).count();
        long retainedEntries = observations.stream().mapToLong(o -> o.spans.size()).sum();

        System.out.printf("%nphysical connections still reachable after pool close + GC: %d/%d%n",
                stillAlive, observations.size());
        System.out.printf("total retained span entries after pool close + GC      : %d%n", retainedEntries);

        if (retainedEntries == 0) {
            System.out.println("RESULT: no post-pool retained Connector/J span entries observed.");
        } else if (stillAlive > 0) {
            System.out.println("RESULT: retained entries are still coupled to reachable physical ConnectionImpl objects.");
        } else {
            System.out.println("RESULT: possible leak: span entries remain after physical ConnectionImpl objects became unreachable.");
        }
    }

    private static List<PhysicalConnectionObservation> exercisePool() throws Exception {
        Map<Integer, PhysicalConnectionObservation> observations = new LinkedHashMap<>();

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(JDBC_URL);
        config.setUsername(DB_USER);
        config.setPassword(DB_PASS);
        config.setMinimumIdle(POOL_SIZE);
        config.setMaximumPoolSize(POOL_SIZE);
        config.setPoolName("otel-prod-shape-probe");

        try (HikariDataSource dataSource = new HikariDataSource(config)) {
            warmPool(dataSource, observations);
            printSnapshot("after warmup, before GC", new ArrayList<>(observations.values()));
            forceGc();
            printSnapshot("after warmup + GC", new ArrayList<>(observations.values()));

            for (int i = 1; i <= ITERATIONS; i++) {
                try (Connection logicalConnection = dataSource.getConnection();
                     Statement statement = logicalConnection.createStatement()) {
                    Object physicalConnection = logicalConnection.unwrap(Class.forName("com.mysql.cj.jdbc.ConnectionImpl"));
                    observe(observations, physicalConnection);
                    statement.execute("SELECT 1");
                }

                if (i % REPORT_EVERY == 0 || i == ITERATIONS) {
                    printSnapshot("pool alive before GC, logical closes=" + i, new ArrayList<>(observations.values()));
                    forceGc();
                    printSnapshot("pool alive after GC, logical closes=" + i, new ArrayList<>(observations.values()));
                }
            }
        }

        return new ArrayList<>(observations.values());
    }

    private static void warmPool(HikariDataSource dataSource, Map<Integer, PhysicalConnectionObservation> observations) throws Exception {
        List<Connection> connections = new ArrayList<>();
        try {
            for (int i = 0; i < POOL_SIZE; i++) {
                Connection connection = dataSource.getConnection();
                connections.add(connection);
                Object physicalConnection = connection.unwrap(Class.forName("com.mysql.cj.jdbc.ConnectionImpl"));
                observe(observations, physicalConnection);
                try (Statement statement = connection.createStatement()) {
                    statement.execute("SELECT 1");
                }
            }
        } finally {
            for (Connection connection : connections) {
                connection.close();
            }
        }
    }

    private static void observe(Map<Integer, PhysicalConnectionObservation> observations, Object physicalConnection) throws Exception {
        int id = System.identityHashCode(physicalConnection);
        PhysicalConnectionObservation existing = observations.get(id);
        if (existing != null && existing.connectionRef.get() == physicalConnection) {
            return;
        }

        Object session = SESSION_FIELD.get(physicalConnection);
        Object telemetryHandler = TELEMETRY_HANDLER_FIELD.get(session);
        Map<?, ?> spans = (Map<?, ?>) SPANS_FIELD.get(telemetryHandler);
        observations.put(id, new PhysicalConnectionObservation(id, new WeakReference<>(physicalConnection), spans));
    }

    private static void printSnapshot(String label, List<PhysicalConnectionObservation> observations) {
        int total = observations.stream().mapToInt(o -> o.spans.size()).sum();
        int max = observations.stream().mapToInt(o -> o.spans.size()).max().orElse(0);
        System.out.printf("%-36s physical=%d totalSpans=%d maxSpansPerPhysical=%d%n",
                label, observations.size(), total, max);
    }

    private static void forceGc() throws InterruptedException {
        for (int i = 0; i < 4; i++) {
            System.gc();
            Thread.sleep(200);
        }
    }

    private static Field field(String className, String fieldName) {
        try {
            Field field = Class.forName(className).getDeclaredField(fieldName);
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private record PhysicalConnectionObservation(int id, WeakReference<Object> connectionRef, Map<?, ?> spans) {
    }
}
