package com.example;

import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Map;

/**
 * Minimal reproducer for MySQL Connector/J 9.x OpenTelemetry memory leak.
 * Oracle Bug #118528 — reported by Ünal Polat, Jun 26 2025.
 *
 */
public class OTelLeakReproducer {

    private static final String JDBC_URL = System.getProperty("db.url",
            "jdbc:mysql://localhost:3306/leaktest?useSSL=true&allowPublicKeyRetrieval=true&serverTimezone=UTC");
    private static final String DB_USER = System.getProperty("db.user", "leaktest");
    private static final String DB_PASS = System.getProperty("db.pass", "leaktest");

    public static void main(String[] args) throws Exception {

        Field sessionField        = field("com.mysql.cj.jdbc.ConnectionImpl",  "session");
        Field telemetryHandlerField = field("com.mysql.cj.CoreSession",          "telemetryHandler");
        Field spansField          = field("com.mysql.cj.otel.OpenTelemetryHandler", "spans");

        Object capturedHandler = null;
        Map<?, ?> spans = null;

        try (Connection conn = DriverManager.getConnection(JDBC_URL, DB_USER, DB_PASS);
             Statement stmt = conn.createStatement()) {

            capturedHandler = telemetryHandlerField.get(sessionField.get(conn));
            spans = (Map<?, ?>) spansField.get(capturedHandler);
            capturedHandler = null; // clear our reference to the handler, so only the spans map is keeping it alive

            for (int i = 0; i < 10; i++) stmt.execute("SELECT 1");

            System.out.printf("spans.size() BEFORE conn.close()             : %d%n", spans.size());
        }
        // conn.close() called by try-with-resources

        System.out.printf("spans.size() IMMEDIATELY after conn.close()  : %d%n", spans.size());
        System.out.printf("             (no GC — close() alone should clear this)%n%n");

        System.gc(); System.gc();
        Thread.sleep(1000);
        System.gc();
        Thread.sleep(500);

        int sizeAfterGc = spans.size();
        System.out.printf("spans.size() AFTER conn.close() + 3x GC      : %d%n%n", sizeAfterGc);

        if (spans.size() > 0) {
            System.out.println("BUG CONFIRMED — spans map not cleared by conn.close() or GC.");
        } else {
            System.out.println("PASS — spans cleared at conn.close().");
        }

        // Cleanup the AbandonedConnectionCleanupThread to prevent it from keeping the classloader alive after the test finishes.
        com.mysql.cj.jdbc.AbandonedConnectionCleanupThread.checkedShutdown();
        // Final GC to clean up the cleanup thread's reference to the classloader, if any.
        System.gc(); System.gc();
        Thread.sleep(1000);
        System.out.printf("spans.size() AFTER AbandonedConnectionCleanupThread + 2x GC      : %d%n%n", spans.size());
    }

    private static Field field(String className, String fieldName) throws Exception {
        Field f = Class.forName(className).getDeclaredField(fieldName);
        f.setAccessible(true);
        return f;
    }
}
