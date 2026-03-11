# MySQL Connector/J 9.x — OpenTelemetry Memory Leak Reproducer

Minimal reproducer for **Oracle Bug #118528**: `com.mysql.cj.otel.OpenTelemetryHandler` retains span entries in its internal `spans` map after `Connection.close()`, causing a memory leak in long-running applications.

Originally reported by Ünal Polat, Jun 26 2025.

## The Bug

MySQL Connector/J 9.x ships a built-in OpenTelemetry tracing handler (`com.mysql.cj.otel.OpenTelemetryHandler`) that tracks in-flight spans in a static/shared `Map`. When a connection is closed, the handler does not clear its span entries. The entries also survive GC because the map holds references that prevent collection.

**Expected:** `spans.size() == 0` immediately after `conn.close()`
**Actual:** Spans persist after `close()` and even after forced GC

## Prerequisites

- Java 17+
- Maven 3.x (`brew install maven` on macOS)
- Access to any MySQL 8+ database (local or remote)

## Run

Pass your JDBC URL, username, and password as system properties:

```bash
mvn compile exec:java \
  -Ddb.url="jdbc:mysql://<host>:<port>/<database>?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC" \
  -Ddb.user=<user> \
  -Ddb.pass=<password>
```

The reproducer only runs `SELECT 1` — no schema or tables are required in the target database.

## Expected Output (demonstrating the bug)

```
spans.size() BEFORE conn.close()             : 14
spans.size() IMMEDIATELY after conn.close()  : 14
             (no GC — close() alone should clear this)

spans.size() AFTER conn.close() + 3x GC      : 1

BUG CONFIRMED — spans map not cleared by conn.close() or GC.

spans.size() AFTER AbandonedConnectionCleanupThread + 2x GC      : 1
```

## Dependencies

| Artifact | Version | Purpose |
|---|---|---|
| `com.mysql:mysql-connector-j` | 9.6.0 | JDBC driver under test |
| `io.opentelemetry:opentelemetry-api` | 1.47.0 | Required by Connector/J 9.x OTel handler |
| `io.opentelemetry:opentelemetry-sdk` | 1.47.0 | OTel SDK implementation |

## Workarounds

None confirmed.

## Affected Versions

- MySQL Connector/J 9.x (confirmed on 9.6.0)
- MySQL Connector/J 8.x is **not affected** (no built-in OTel handler)
