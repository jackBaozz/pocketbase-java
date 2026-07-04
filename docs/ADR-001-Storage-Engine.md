# ADR-001: Relational Storage Engine and Multi-Database Parity

## Context

The Java implementation of PocketBase initially used `JsonFileStore` (writing plain JSON arrays) as a lightweight, memory-backed storage engine. While sufficient for early prototyping and basic testing, it lacked robust transaction boundaries, dynamic database schema migrations, and standard SQL filter compiling required for true parity with PocketBase.

The objective was to introduce a relational database storage engine using SQLite as the default embedded engine (and MySQL/PostgreSQL as external databases) while preserving GraalVM native image compatibility.

## Decision

1. **Implement Unified `StorageEngine` SPI**: We introduced a clean storage boundary that abstracts database interactions. This allows the runtime to switch dynamically between JSON Lines file storage and relational databases (SQLite, MySQL, PostgreSQL).
2. **Transition JSON Store to JSON Lines (`.jsonl`)**: To improve performance and prevent OOM issues under large collections, the default local file-store format was refactored to write each record on a new line (JSON Lines), with backward compatibility to read older JSON arrays during migration.
3. **Adopt jOOQ and HikariCP for Relational Engines**: We integrated jOOQ for dialect-aware query compilation and schema generation, alongside HikariCP for connection pooling. Relational implementations are:
   - `SqliteStorageEngine` (SQLite) - the default relational engine baseline.
   - `MysqlStorageEngine` (MySQL) - external database support.
   - `PostgresStorageEngine` (PostgreSQL) - external database support.
4. **Achieve GraalVM Native Image Support**: We successfully configured and tested Native Image compilation for all three relational dialects, registering dynamic reflection profiles for the JDBC drivers (`sqlite-jdbc`, MySQL, and PostgreSQL) and HikariCP.
5. **Establish Relational Matrix CI Testing**: Relational engines are verified via automated CI test suites running SQLite, MySQL, and PostgreSQL test profiles.

## Consequences

- The server defaults to the lightweight JSONL storage provider for zero-dependency development/testing, but production-grade deployments can seamlessly opt into fully-compliant Relational Storage engines (SQLite/MySQL/PostgreSQL) via `-Dstorage` flag.
- PocketBase-style rules and filters are compiled into SQL queries dynamically based on the active database dialect.
- The `POST /api/sql` endpoint enables full transactional query execution against relational engines for superusers.
