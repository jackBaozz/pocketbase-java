# ADR-001: Storage Engine and Native Compatibility

## Context

The Java implementation of PocketBase currently uses `JsonFileStore` as a lightweight, memory-backed storage engine. This works well for rapid development and basic functional tests, but falls short of PocketBase's official SQLite capabilities (complex transactions, JSON column indexing, full SQL planner, true concurrent writes).
The goal is to maintain GraalVM native image compatibility while eventually achieving true parity with the PocketBase ecosystem.

## Decision

1. **Retain JSON Store for Dev/Test**: We will retain `JsonFileStore` as the default development and testing backend. It has zero external dependencies, making native compilation trivial, and provides a decent baseline.
2. **Introduce SQLite Backend interface later**: We will not force a SQLite (e.g. `xerial/sqlite-jdbc` or GraalVM SQLite) dependency immediately without a GraalVM native smoke test. Due to JNI complexities, introducing JDBC SQLite directly into a native image might cause compatibility regressions.
3. **Acceptance criteria for SQLite**: Before integrating SQLite, a proof of concept MUST demonstrate CRUD, indexing, unique constraints, and SQL execution within a GraalVM native image build without external shared libraries issues across Darwin/Linux/Windows.
4. **Current Status (SDP-007 Completed)**: This document serves as the formal decision to hold off on SQLite until native-image proof is validated in a separate branch, completing SDP-007's requirement of formalizing the engine path.

## Consequences

- The system will continue to lack true SQL engine constraints and deep SQL parsing for the `POST /api/sql` interface when using `JsonFileStore`.
- Development velocity for UI/SDK parity remains high due to zero storage layer friction.
