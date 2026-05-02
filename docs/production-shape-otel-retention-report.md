# Connector/J OpenTelemetry Production-Shape Retention Report

This report summarizes two production-shaped probes for MySQL Connector/J Bug #118528. The goal is to distinguish a permanent leak from GC-dependent transient retention while application frameworks keep physical JDBC connections alive.

## Running the probes

Compile and build the runtime classpath before launching the direct Java commands:

```bash
mvn -q -DskipTests compile dependency:build-classpath -Dmdep.outputFile=cp.txt
```

## Environment

- MySQL: `mysql:8.0` Docker container
- JDBC URL: `jdbc:mysql://localhost:3307/leaktest?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC`
- Connector/J: `9.6.0`
- OpenTelemetry API/SDK: `1.47.0`
- Java source/target: `17`

## Probe 1: HikariCP Pool

Class: `com.example.PooledOTelLeakProbe`

Shape:

- HikariCP fixed pool size: `4`
- Logical connection cycles: `2,000`
- Each cycle obtains a logical connection, runs `SELECT 1`, and closes the logical connection.
- The probe unwraps physical `com.mysql.cj.jdbc.ConnectionImpl` instances and reads each `OpenTelemetryHandler.spans` map by reflection.

Command:

```bash
java '-Ddb.url=jdbc:mysql://localhost:3307/leaktest?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC' \
  -Ddb.user=leaktest \
  -Ddb.pass=leaktest \
  -Dpool.size=4 \
  -Diterations=2000 \
  -Dreport.every=250 \
  -cp target/classes:$(cat cp.txt) \
  com.example.PooledOTelLeakProbe
```

Observed result:

```text
after warmup + GC                    physical=4 totalSpans=4 maxSpansPerPhysical=1
pool alive before GC, logical closes=250 physical=4 totalSpans=255 maxSpansPerPhysical=252
pool alive after GC, logical closes=250 physical=4 totalSpans=4 maxSpansPerPhysical=1
pool alive before GC, logical closes=2000 physical=4 totalSpans=255 maxSpansPerPhysical=252
pool alive after GC, logical closes=2000 physical=4 totalSpans=4 maxSpansPerPhysical=1
after pool close + GC                physical=4 totalSpans=0 maxSpansPerPhysical=0
RESULT: no post-pool retained Connector/J span entries observed.
```

Interpretation:

- While the pool is alive, completed operation spans accumulate until GC runs.
- After forced GC, retained entries are bounded at one span per physical connection.
- After closing the pool, retained entries drop to zero.

## Probe 2: Spring Boot + HikariCP + Hibernate/JPA

Class: `com.example.springprobe.SpringBootHibernateOTelProbe`

Shape:

- Spring Boot: `3.4.4`
- HikariCP fixed pool size: `4`
- JPA/Hibernate transactional units of work: `2,000`
- Each transaction saves and reads an entity through Spring Data JPA.
- The probe unwraps the physical Connector/J connection inside the Hibernate transaction and reads each `OpenTelemetryHandler.spans` map by reflection.

Command:

```bash
java '-Ddb.url=jdbc:mysql://localhost:3307/leaktest?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC' \
  -Ddb.user=leaktest \
  -Ddb.pass=leaktest \
  -Dpool.size=4 \
  -Diterations=2000 \
  -Dreport.every=250 \
  -cp target/classes:$(cat cp.txt) \
  com.example.springprobe.SpringBootHibernateOTelProbe
```

Observed result:

```text
after warmup + GC                          physical=4 totalSpans=4 maxSpansPerPhysical=1
spring alive before GC, tx=250             physical=4 totalSpans=1255 maxSpansPerPhysical=1252
spring alive after GC, tx=250              physical=4 totalSpans=4 maxSpansPerPhysical=1
spring alive before GC, tx=2000            physical=4 totalSpans=1255 maxSpansPerPhysical=1252
spring alive after GC, tx=2000             physical=4 totalSpans=4 maxSpansPerPhysical=1
after Spring context close + GC            physical=4 totalSpans=0 maxSpansPerPhysical=0
RESULT: no post-context retained Connector/J span entries observed.
```

Interpretation:

- Spring/Hibernate produces more transient Connector/J spans than the simple Hikari-only probe.
- The transient map size grows substantially between GC cycles.
- After forced GC, retained entries are still bounded at one span per physical connection.
- After the Spring context closes, retained entries drop to zero.

## Conclusion

These probes do not demonstrate a permanent Connector/J memory leak. They do demonstrate GC-dependent transient retention: `OpenTelemetryHandler.spans` can grow with workload volume between GC cycles, especially under Spring Boot/Hibernate, but collapses back to one retained entry per live physical connection after GC.

A stronger production bug claim would require showing one of these conditions:

1. Post-GC retained span entries grow beyond physical pool size.
2. Retained entries remain after physical `ConnectionImpl` objects become unreachable.
3. Heap usage grows with request count even after major/full GC cycles.
