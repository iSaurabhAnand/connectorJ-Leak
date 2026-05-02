package com.example.springprobe;

import jakarta.persistence.Entity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.Session;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@SpringBootApplication
@EntityScan(basePackageClasses = SpringBootHibernateOTelProbe.ProbeRecord.class)
@EnableJpaRepositories(considerNestedRepositories = true)
public class SpringBootHibernateOTelProbe {

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
        System.setProperty("spring.datasource.url", JDBC_URL);
        System.setProperty("spring.datasource.username", DB_USER);
        System.setProperty("spring.datasource.password", DB_PASS);
        System.setProperty("spring.datasource.hikari.minimum-idle", String.valueOf(POOL_SIZE));
        System.setProperty("spring.datasource.hikari.maximum-pool-size", String.valueOf(POOL_SIZE));
        System.setProperty("spring.jpa.hibernate.ddl-auto", "create-drop");
        System.setProperty("spring.jpa.open-in-view", "false");
        System.setProperty("spring.main.web-application-type", "none");
        System.setProperty("logging.level.root", "WARN");
        System.setProperty("logging.level.com.zaxxer.hikari", "INFO");

        System.out.printf("url=%s%npool.size=%d iterations=%d report.every=%d%n%n",
                JDBC_URL, POOL_SIZE, ITERATIONS, REPORT_EVERY);

        Map<Integer, PhysicalConnectionObservation> observations = new LinkedHashMap<>();
        ConfigurableApplicationContext context = SpringApplication.run(SpringBootHibernateOTelProbe.class, args);
        try {
            ProbeService service = context.getBean(ProbeService.class);
            DataSource dataSource = context.getBean(DataSource.class);

            warmPool(dataSource, observations);
            printSnapshot("after warmup, before GC", new ArrayList<>(observations.values()));
            forceGc();
            printSnapshot("after warmup + GC", new ArrayList<>(observations.values()));

            for (int i = 1; i <= ITERATIONS; i++) {
                service.doJpaUnitOfWork(observations);
                if (i % REPORT_EVERY == 0 || i == ITERATIONS) {
                    printSnapshot("spring alive before GC, tx=" + i, new ArrayList<>(observations.values()));
                    forceGc();
                    printSnapshot("spring alive after GC, tx=" + i, new ArrayList<>(observations.values()));
                }
            }
        } finally {
            context.close();
        }

        forceGc();
        List<PhysicalConnectionObservation> finalObservations = new ArrayList<>(observations.values());
        printSnapshot("after Spring context close + GC", finalObservations);

        long stillAlive = finalObservations.stream().filter(o -> o.connectionRef.get() != null).count();
        long retainedEntries = finalObservations.stream().mapToLong(o -> o.spans.size()).sum();

        System.out.printf("%nphysical connections still reachable after context close + GC: %d/%d%n",
                stillAlive, finalObservations.size());
        System.out.printf("total retained span entries after context close + GC      : %d%n", retainedEntries);

        if (retainedEntries == 0) {
            System.out.println("RESULT: no post-context retained Connector/J span entries observed.");
        } else if (stillAlive > 0) {
            System.out.println("RESULT: retained entries are still coupled to reachable physical ConnectionImpl objects.");
        } else {
            System.out.println("RESULT: possible leak: span entries remain after physical ConnectionImpl objects became unreachable.");
        }
    }

    private static void warmPool(DataSource dataSource, Map<Integer, PhysicalConnectionObservation> observations) throws Exception {
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
        System.out.printf("%-42s physical=%d totalSpans=%d maxSpansPerPhysical=%d%n",
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

    @Service
    static class ProbeService {
        private final EntityManager entityManager;
        private final ProbeRecordRepository repository;

        ProbeService(EntityManager entityManager, ProbeRecordRepository repository) {
            this.entityManager = entityManager;
            this.repository = repository;
        }

        @Transactional
        void doJpaUnitOfWork(Map<Integer, PhysicalConnectionObservation> observations) throws Exception {
            Session session = entityManager.unwrap(Session.class);
            session.doWork(connection -> {
                try {
                    Object physicalConnection = connection.unwrap(Class.forName("com.mysql.cj.jdbc.ConnectionImpl"));
                    observe(observations, physicalConnection);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            ProbeRecord saved = repository.save(new ProbeRecord("payload"));
            repository.findById(saved.id).orElseThrow();
        }
    }

    interface ProbeRecordRepository extends JpaRepository<ProbeRecord, Long> {
    }

    @Entity
    @Table(name = "probe_record")
    static class ProbeRecord {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        Long id;

        String payload;

        ProbeRecord() {
        }

        ProbeRecord(String payload) {
            this.payload = payload;
        }
    }
}
