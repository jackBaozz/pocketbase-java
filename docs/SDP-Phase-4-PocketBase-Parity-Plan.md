# SDP Phase 4: PocketBase Parity And Release Hardening Plan

Updated: 2026-06-28
Baseline: PocketBase official API and Admin UI behavior compatibility.

This document outlines the roadmap for Phase 4. It excludes all tasks successfully completed during Phase 3 and breaks down the remaining uncompleted requirements into granular, actionable tasks.

---

## 1. Phase 4 Goals

The primary goal of Phase 4 is to transition the Java implementation from an SQLite MVP to a robust, multi-database production-ready runtime.

- **Storage Parity**: SQLite relational storage replaces the JSON file store as the default, and MySQL/PostgreSQL are verified as fully-supported production backends.
- **Durable Auth & Lifecycle**: Implement persistent tokens, OTP, OAuth2 code flows, and SMTP mail delivery.
- **Unified File & Backup SPI**: Support S3 and local storage, complete with transactions, thumbnails, and ZIP-based backups.
- **Rule, SSE, Batch Parity**: Implement the complete rule query parameters, SSE realtime updates, transactional batch changes, and raw SQL queries.
- **Admin UI Parity**: Deep links, index/schema editor, relation selector, logs stats, S3 controls, and mail template visual designer.
- **GraalVM Release Verification**: Ensure native-image runs correctly with dynamic JDBC profiles and S3/mail dependencies.

---

## 2. Phase 4 Work Breakdown

### Stream A: Relational Storage & Database Matrix

This workstream focuses on database compatibility, dialect abstractions, and schema migrations.

#### [ ] P4-A01: Dialect-Aware Query Compilation & SQL Endpoint
- [ ] **SQL query validation**: Design query validation parser to reject invalid or dangerous statements in raw SQL/view SQL.
- [ ] **Dialect-aware JSON extraction**: Replace SQLite-only `json_extract(...)` calls in `FilterToSqlCompiler` with a dialect abstraction supporting:
  - SQLite: `json_extract(column, '$.path')` or `column ->> '$.path'`
  - MySQL: `JSON_EXTRACT(column, '$.path')` or `column->>'$.path'`
  - PostgreSQL: `column ->> 'path'` (for json/jsonb columns)
- [ ] **Dialect-specific operators & quoting**: Implement dialect-specific text concatenation, quoting, pagination, and LIKE escaping (`%`, `_`, `\`) for MySQL and PostgreSQL.
- [ ] **SQL Exec API validation**: Enable the official raw SQL executing API for superusers with parameter bindings, enforcing transaction security.
- *Acceptance Criteria*: SQL execution and view dry-runs pass correctly on all active relational dialects (SQLite, MySQL, PostgreSQL).

#### [ ] P4-A02: SQL Type Mapping & Value Normalization
- [ ] **Migration tests for field types**: Create migration tests covering all field types (text, editor, email, url, number, bool, date, autodate, select, json, file, relation, password, geoPoint).
- [ ] **Output type normalization**: Verify that values read from the database are correctly coerced into official JSON data types (e.g. converting numeric booleans, mapping JSON strings to list arrays).
- [ ] **Array serialization consistency**: Verify that list fields (`select` multiple, `json` arrays, `relation` ID lists) serialize and roundtrip consistently across SQLite, MySQL, and PostgreSQL.
- *Acceptance Criteria*: Integration tests verify field storage mapping and value normalization pass for all database dialects.

#### [ ] P4-A03: Schema Migration Planner
- [ ] **Migration plan generator**: Build an engine that calculates diffs between schema states and outputs safe migration SQL.
- [ ] **Field structural modifications**: Implement field addition, removal, renaming, and type modification.
- [ ] **Index manager operations**: Implement DDL commands to create, alter, and drop collection database indexes.
- [ ] **Dynamic View DDL**: Handle schema migration updates when collections of type `view` change their underlying SQL definition.
- [ ] **Dry-run schema output**: Provide a structural migration diff payload mapping directly to the Admin UI review screen.
- [ ] **DDL Transaction safety**: Wrap DDL migration queries in database transactions where supported (PostgreSQL/SQLite) and add transactional rollback workarounds for MySQL.
- [ ] **DDL Dialect documentation**: Document specific DDL limitations (such as SQLite column type alteration limits).
- *Acceptance Criteria*: Applying or rolling back a migration plan behaves atomically and leaves the database schema in sync with `_collections` metadata.

#### [x] P4-A04: MySQL and PostgreSQL Database Profiles
- [x] **Startup permission probes**: Add startup validation to verify that the DSN credentials have the necessary database permissions (CREATE, SELECT, INSERT, UPDATE, DELETE, INDEX, ALTER, DROP).
- [x] **Collation & encoding validation**: Verify that database connection character encoding is UTF-8 (utf8mb4 for MySQL) and collations match PocketBase requirements on startup.
- [ ] **CI pipeline integration**: Configure optional/required MySQL and PostgreSQL test execution in the project's CI configuration.
- *Acceptance Criteria*: Verification suites run on real MySQL and PostgreSQL databases.

#### [x] P4-A05: SQLite Default Engine & Migration Paths
- [x] **Default SQLite configuration**: Switch the server's default configuration to use the SQLite relational store instead of the JSON file store.
- [x] **JSON-to-SQLite migrator**: Write an automated command/bootstrapper that reads existing JSON file stores and writes them into SQLite tables.
- [x] **JSON storage deprecation**: Remove or mark the legacy JSON storage engine as deprecated, maintaining it only as a fallback adapter.
- [x] **Documentation updates**: Revise the README, setup guides, and CLI parameter descriptions to reflect the SQLite-first default.
- *Acceptance Criteria*: A fresh server launch bootstraps using SQLite without requiring extra flags, and existing JSON databases migrate without data loss.

---

### Stream B: Official Behavior Fixtures & Testing

This workstream improves the testing coverage to verify compatibility against official specs.

#### [x] P4-B01: API Fixture Harness Expansion
- [x] **Modular fixture groups**: Restructure behavior fixtures to match official API groups (auth, MFA, OAuth2, collections, records, files, settings, logs, backups, crons, SQL, batch, realtime).
- [x] **Negative test cases**: Write negative tests for each endpoint covering unauthorized access, not found, forbidden, invalid JSON bodies, and invalid query structures.
- *Acceptance Criteria*: Test suites execute the same fixture assertions across all active storage engines.

#### [x] P4-B02: Official Version Baseline Management
- [x] **Version baseline pin**: Explicitly pin the official PocketBase version baseline (e.g. v0.22.x) in all project documentation and test profiles.
- [x] **Route manifest sync script**: Implement a script or workflow that compares current project routes against the official route/method manifest.
- [x] **Custom route separation**: Isolate and document custom Java-only routes so they are clearly marked.
- *Acceptance Criteria*: Running the route validation helper produces a diff showing exactly which routes are verified, missing, or customized.

#### [x] P4-B03: Client SDK Compatibility Testing
- [x] **Patch-free JS SDK validation**: Run the official JavaScript SDK test suites against the running server without any custom client-side request modifications.
- [x] **Expand JS SDK coverage**: Add test coverage for file uploads/downloads, batch operations, realtime subscriptions, token refresh, OAuth2, and MFA.
- [x] **Dart SDK smoke tests**: Write basic client tests using the official Dart/Flutter SDK to verify mobile client compatibility.
- *Acceptance Criteria*: Official JS and Dart SDKs can establish connections and perform CRUD, auth, and realtime operations without client code modifications.

---

### Stream C: Authentication, MFA, OAuth2, and Mail

This workstream completes the authentication, OAuth2, and email workflows.

#### [x] P4-C01: Durable Auth Action Persistence
- [x] **Auth tokens persistence**: Store action tokens (password resets, email verification, email changes) in the database with strict expiry limits.
- [x] **One-time-use validation**: Implement atomic checks to ensure action tokens are invalidated upon first use.
- [x] **Transactional updates**: Perform token validation, user record updates, and token invalidation in a single database transaction.
- *Acceptance Criteria*: Password resets, email verification, and email updates behave atomically and validate against real database records.

#### [x] P4-C02: OTP Token Persistence
- [x] **OTP persistence schema**: Implement the `_otps` table schema for SQLite, MySQL, and PostgreSQL.
- [x] **OTP atomic check & store**: Save generated OTP states (code hashes, retry counts, expiration, matching auth record) in the database.
- [x] **Stale OTP cleanup**: Implement a scheduled clean-up job for expired OTP records.
- *Acceptance Criteria*: OTP tokens survive server restarts and expire according to configurations.

#### [x] P4-C03: Full OAuth2 Provider Flow
- [x] **Local mock OAuth2 server**: Implement a local test OAuth2 mock server to simulate OAuth provider exchanges.
- [x] **Config validation**: Validate configuration settings for standard providers (Google, GitHub, OIDC).
- [x] **OAuth2 lifecycle**: Implement the full OAuth2 authentication flow (redirect generation, token exchange, id_token verification, user profile fetching).
- [x] **Account linking & conflict management**: Handle email duplication, link new OAuth providers to existing records, and handle unlinking.
- [x] **Browser redirect/popup response**: Render redirect success/failure pages and handle `postMessage` outputs for popup authentication.
- *Acceptance Criteria*: The mock OAuth2 server handles authentication flows, and accounts link or fail according to configuration rules.

#### [x] P4-C04: SMTP Mail Delivery
- [x] **SMTP Client integration**: Implement SMTP client support with TLS, SSL, and authentication options.
- [x] **Outbox/Dry-run mail log**: Implement a dry-run or local mail directory logger to capture email outputs for testing.
- [x] **Template compilation**: Compile official mail templates using variables (e.g. `{APP_NAME}`, `{RECORD_EMAIL}`, `{ACTION_URL}`).
- [x] **UI template designer integration**: Connect backend template storage to the Admin UI email designer endpoints.
- *Acceptance Criteria*: The server sends emails via SMTP and outputs templates correctly in dry-run modes.

---

### Stream D: Files, Storage Providers, and Backups

This workstream manages assets, S3 compatibility, and database backups.

#### [x] P4-D01: Unified FileStorageProvider SPI
- [x] **Storage interface definition**: Define `FileStorageProvider` SPI with operations (put, get, delete, list, stat, signed/proxied read, and temporary staging).
- [x] **Local storage provider**: Implement the SPI targeting local file systems.
- [x] **S3 storage provider**: Implement the SPI targeting AWS S3 and S3-compatible APIs.
- [x] **Transaction rollback integration**: Implement file cleanup routines if the database transaction creating the record fails.
- [x] **Orphaned files cleanup**: Implement scheduled clean-up tasks to remove staged files that were never finalized.
- *Acceptance Criteria*: Files are uploaded and deleted consistently, rolling back from file storage if database writes fail.

#### [x] P4-D02: File HTTP API Parity
- [x] **filePath resolution**: Implement path generation for files stored in relational databases.
- [x] **File token validation**: Enforce file token validations on protected file URLs.
- [x] **Rule-based access control**: Enforce collection `viewRule` requirements on protected file access.
- [x] **HTTP range & caching**: Implement HTTP range requests, cache-control headers, ETag validation, and MIME-type assertions.
- [x] **Thumbnail generation & cache**: Generate and cache file thumbnails, returning fallback assets for unsupported files.
- *Acceptance Criteria*: Protected files are readable only with valid tokens and rules, and thumbnails cache as expected.

#### [x] P4-D03: Backup Provider Parity
- [x] **backupFile resolver**: Implement backup file resolvers for relational storage.
- [x] **Relational backup operations**: Implement backup creation, list, download, upload, delete, and restore workflows.
- [x] **Multi-dialect support**: Document or implement backup/restore strategies for MySQL and PostgreSQL.
- [x] **S3 Backup storage**: Support backing up files directly to AWS S3/S3-compatible storage.
- [x] **Restore validation & safety**: Validate the backup ZIP archive structure before running restorations, and implement rollback fallbacks if the restore fails.
- [x] **Scheduled auto-backup cron**: Integrate backups with the scheduler to run automatic backups.
- *Acceptance Criteria*: ZIP backups can be created, uploaded, downloaded, and restored successfully.

---

### Stream E: Rules Engine, Realtime, Batch, and SQL Endpoints

This workstream completes advanced API features such as rules compilation, realtime streams, and batch actions.

#### [x] P4-E01: Rule Engine Parity & Compiler
- [ ] **Grammar validation**: Audit the project's `RuleEvaluator` and `FilterToSqlCompiler` against official rule specifications.
- [ ] **Context variables support**: Implement context variables: `@request.auth.*`, `@request.body.*`, `@request.query.*`, `@request.headers.*`, `@request.method`, and `@collection.*` relation fields.
- [ ] **Logical & type assertions**: Add tests verifying null, empty string, arrays, relation fields, date comparisons, and operator priorities.
- *Acceptance Criteria*: Compiled rule filters output valid SQL and match the evaluation results of the official engine.

#### [ ] P4-E02: Realtime SSE Protocol Parity
- [ ] **SSE format validation**: Format SSE response structures to match official PocketBase clients.
- [ ] **Auth refresh validation**: Validate auth tokens on open SSE connections, disconnecting expired or revoked sessions.
- [ ] **Access filter broadcasting**: Filter change notifications before broadcasting to ensure clients have permission via collection rules.
- [ ] **Connection pool management**: Implement connection cleanup, reconnect handlers, and backpressure management.
- *Acceptance Criteria*: Clients subscribe and receive updates over SSE, filtering out unauthorized events.

#### [ ] P4-E03: Batch API Parity
- [ ] **Sub-request routing**: Route batch requests to appropriate endpoint handlers.
- [ ] **System limits**: Enforce batch request limits (max requests, payload sizes, timeout, and authorization).
- [ ] **Multipart batch payloads**: Parse multipart files within batch updates.
- [ ] **Atomic transactions**: Ensure database transactions and uploaded files roll back completely if any batch sub-request fails.
- *Acceptance Criteria*: Batch API requests process atomically, rolling back all modifications on failures.

#### [ ] P4-E04: Direct SQL Endpoint
- [ ] **SQL request API**: Implement the raw SQL executing endpoint for superusers.
- [ ] **Query syntax analyzer**: Restrict execution to allowed query types (SELECT, INSERT, UPDATE, DELETE) and handle safety validations.
- [ ] **Dialect normalizer**: Format returned column types and errors to match database dialects.
- *Acceptance Criteria*: Superusers can query databases directly through the SQL endpoint with structured outputs and error handling.

---

### Stream F: Admin UI Parity

This workstream brings the Admin UI visual and functional flows closer to official layouts.

#### [x] P4-F01: Hash Routing & Shell
- [x] **Hash routing implementation**: Implement hash routes matching official path structures (login, collection views, record editors, settings pages, logs, backups, and OAuth configurations).
- [x] **Browser history support**: Integrate browser back/forward navigation within the shell layout.
- [x] **Direct deep-links**: Enable deep linking to specific collections, records, logs, and settings tabs.
- *Acceptance Criteria*: Deep links load the correct screens, and back/forward navigation functions as expected.

#### [x] P4-F02: Collection Schema Editor UI
- [x] **Field option forms**: Implement editor inputs for all field types (validation properties, default values, required constraints).
- [x] **Index builder**: Add a visual interface to manage custom indexes on collections.
- [x] **Collection settings forms**: Add settings forms for collection auth rules, MFA setups, and OTP properties.
- [x] **SQL view editor**: Add an editor for view-type collections with query execution previews.
- [x] **Migration diff preview**: Render schema changes with color-coded diff displays.
- *Acceptance Criteria*: Collection schemas can be edited, showing validation errors and index configurations in the UI.

#### [x] P4-F03: Record Manager & Editor UI
- [x] **Relation selector**: Implement a relation search and selection picker for relation fields.
- [x] **File field manager**: Add visual inputs for file uploads, preview thumbnails, file clearing, and file reordering.
- [x] **JSON validation editor**: Add a formatted JSON editor with syntax validation for JSON fields.
- [x] **Action commands**: Add options to Duplicate, Impersonate, and Preview JSON records in the UI.
- [x] **Advanced search filter**: Support filter syntax, search fields, and sorting controls in record list views.
- *Acceptance Criteria*: Users can search, filter, edit relation fields, upload files, and duplicate records in the UI.

#### [x] P4-F04: System Settings, Logs, and Backups UI
- [x] **System configuration forms**: Render forms matching the official system configuration layout.
- [x] **Log details inspector**: Render detailed log properties, filtering options, and timeline stats.
- [x] **Backup console**: Add lists for backups, restore triggers with confirmation modals, and S3 backup toggles.
- [x] **Email designer**: Create a text and template editor for system emails.
- [x] **OAuth2 configuration forms**: Add provider setup forms with mock login buttons.
- *Acceptance Criteria*: UI forms update backend settings, display request logs, and trigger backups.

#### [x] P4-F05: Visual QA & Asset Integrity
- [x] **Responsive display checks**: Verify layout alignment on desktop and mobile breakpoints.
- [x] **Compilation verification**: Build and verify production assets in the distribution folder (`src/main/resources/pocketbase-admin`) upon UI changes.
- *Acceptance Criteria*: UI builds run from clean checkouts and package correctly inside final application builds.

---

### Stream G: Native Compilation & Release Engineering

This workstream maintains GraalVM compatibility and prepares release pipelines.

#### [ ] P4-G01: GraalVM Native Image Validation
- [ ] **Native compilation check**: Run native image builds with all dependencies (jOOQ dialects, JDBC drivers, S3 packages, mail, and image thumbnail encoders).
- [ ] **Minimize native configs**: Update and minimize files under `src/main/resources/META-INF/native-image`.
- [ ] **Native verification tests**: Execute integration tests against compiled native binaries.
- [ ] **Conditional database drivers**: Ensure optional drivers (MySQL, PostgreSQL) do not impact compilation size or startup validation when using SQLite.
- *Acceptance Criteria*: Native image compilation succeeds and passes core integration tests on SQLite.

#### [ ] P4-G02: CLI & Configuration Parity
- [ ] **CLI commands**: Match official startup options and flags (`--dir`, `--encryptionEnv`, etc.).
- [ ] **Data masking**: Redact credentials, API keys, and email passwords from system logs and settings responses.
- *Acceptance Criteria*: Startup flags behave consistently, and logs redact sensitive credentials.

#### [ ] P4-G03: Build & Release Gates
- [ ] **Required CI gates**: Configure CI pipeline checks for JVM test execution, SQLite tests, MySQL tests, PostgreSQL tests, and UI asset compilation.
- [ ] **Native binary gate**: Include a compilation step verifying release branches compile on GraalVM.
- *Acceptance Criteria*: Merges to main require successful test coverage passes across all database dialects.

---

## 3. Recommended Execution Order

1. **Database dialect configuration** (P4-A01, P4-A02)
2. **Schema migration compiler** (P4-A03)
3. **Multi-database integration and SQLite defaults** (P4-A04, P4-A05)
4. **Auth actions, OTP persistence, and OAuth2 flow** (P4-C01, P4-C02, P4-C03)
5. **SMTP mail delivery** (P4-C04)
6. **File Storage provider and backup integration** (P4-D01, P4-D02, P4-D03)
7. **Rules compilation and SSE realtime** (P4-E01, P4-E02)
8. **Batch actions and SQL endpoint** (P4-E03, P4-E04)
9. **Admin UI hash routing, schemas, and record editors** (P4-F01 to P4-F04)
10. **GraalVM native configuration and release gates** (P4-G01 to P4-G03)
