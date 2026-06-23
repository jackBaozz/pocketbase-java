# SDP Phase 2: PocketBase Parity Development Plan

Updated: 2026-06-22

Primary input: `docs/SDP-PocketBase-Compatibility-Plan.md` audit status on 2026-06-21.

Reference target: official `pocketbase/pocketbase` API and Admin UI behavior, with Java-native implementation choices where official behavior depends on Go runtime details.

Status legend:

- `[x]`: not started
- `[x]`: in progress or partially implemented; must be verified before closing
- `[x]`: completed and verified

## 1. Phase 2 Goal

Phase 1 proved that a Java/GraalVM-friendly PocketBase-compatible runtime can expose most official routes and basic Admin UI workflows. Phase 2 must move the project from route-level compatibility to behavior-level compatibility.

The target is:

- [x] Use a relational storage core with SQLite as the default embedded parity engine.
- [x] Support external MySQL and PostgreSQL deployments through the same storage abstraction.
- [x] Keep GraalVM native binary packaging viable for the default distribution.
- [x] Replace Go-specific runtime behavior with explicit Java equivalents instead of copying Go internals mechanically.
- [x] Align the Admin UI more closely with the official PocketBase UI in layout, navigation, workflows, and field-level editors.
- [x] Prove compatibility with official SDKs and behavior fixtures, not only with local Java tests.

## 2. Design Principles

- [x] API compatibility first: route paths, methods, query parameters, request bodies, response bodies, status codes, and error envelopes must match official PocketBase unless a documented Java/runtime limitation exists.
- [x] SQLite first, multi-database second: SQLite is the default engine and parity baseline. MySQL/PostgreSQL are implemented through dialect abstractions after SQLite behavior is stable.
- [x] No Go runtime assumptions: goroutines, Go hooks, Go filesystem behavior, and Go SQLite driver details must be mapped into Java primitives intentionally.
- [x] Native-safe dependency policy: every new runtime dependency must have a native-image proof before becoming default.
- [x] UI follows backend contracts: Admin UI pages should use real backend dry-run/validation endpoints instead of preview-only local logic.
- [x] Conformance before breadth: when a feature already exists in basic form, Phase 2 should add fixture tests and exact behavior before adding adjacent features.

## 3. Target Architecture

### 3.1 Storage Layer

Introduce a storage boundary that replaces direct `JsonFileStore` coupling:

```text
HttpApi
  -> PocketBaseRuntime
      -> StorageEngine
          -> SqliteStorageEngine
          -> MysqlStorageEngine
          -> PostgresStorageEngine
          -> JsonFileStoreAdapter (legacy/dev migration only)
      -> RuleEngine
      -> AuthService
      -> FileService
      -> RealtimeService
      -> SettingsService
```

Required interfaces:

- [x] `StorageEngine`: transaction boundary, collection metadata, record CRUD, schema migrations, logs, auth requests, backups metadata.
- [x] `SqlDialect`: jOOQ `SQLDialect` backed identifier quoting, JSON expressions, pagination, upsert, index DDL, date functions, lock semantics.
- [x] `CollectionRepository`: collection schema CRUD, import/export, dry-run migration planning.
- [x] `RecordRepository`: dynamic record table CRUD, filter/sort/expand execution, relation loading.
- [x] `MigrationPlanner`: convert collection field/index changes into ordered database operations.
- [x] `TransactionManager`: nested transaction behavior for batch, files, auth, backup restore.
- [x] `SystemCollections`: `_superusers`, `_mfas`, `_externalAuths`, `_authOrigins`, `_logs`, `_otps`, auth requests.

### 3.2 Database Support Matrix

| Engine | Role | Phase 2 expectation | Native-image requirement |
| --- | --- | --- | --- |
| SQLite | Default embedded engine | Full parity baseline for records, collections, SQL, indexes, migrations, backups | Required before merge |
| PostgreSQL | External deployment engine | Official-compatible API behavior, dialect-specific SQL and JSON operations | Required for release profile, optional for local dev |
| MySQL | External deployment engine | Official-compatible API behavior where SQL semantics can be normalized | Required for release profile, optional for local dev |
| JSON store | Legacy/dev adapter | Migration source and low-dependency fallback only | Keep tests, do not use as parity baseline |

### 3.3 Java Replacements For Go-Specific Behavior

- [x] Replace goroutine-backed jobs with `ScheduledExecutorService` plus explicit lifecycle and shutdown hooks.
- [x] Replace Go middleware chain with Java `HttpMiddleware` pipeline around `HttpApi` with deterministic order.
- [x] Replace Go hooks/events with typed Java event bus and transaction-aware pre/post callbacks.
- [x] Replace Go `database/sql` usage with jOOQ `DSLContext` plus JDBC transactions through `TransactionManager`.
- [x] Replace Go email templates with Java template renderer and fixture-tested output fields/URLs.
- [x] Replace Go filesystem abstractions with `FileStorageProvider` SPI for local and S3-compatible storage.
- [x] Replace Go panic recovery with top-level exception recovery, official error envelope, and activity logging.
- [x] Replace Go realtime broker with Java SSE hub, subscription state, auth refresh, disconnect cleanup, and backpressure limits.

## 4. Detailed Workstream Checklist

### Stream A: Conformance Harness

Goal: make compatibility measurable before replacing internals.

#### SDP2-A01: Official Route Manifest Generator

- [x] Pin an official PocketBase baseline version or commit SHA in the document header or test fixture metadata.
- [x] Generate or manually curate a route manifest with method, path, auth requirement, request shape, and response category.
- [x] Add a local route scanner for the Java server.
- [x] Compare local route list against the official manifest in a JVM test.
- [x] Mark intentionally unsupported routes with explicit reason and issue link.
- [x] Fail CI when a route is missing, has a wrong method, or uses a non-official path.
- [x] Add a short README explaining how to refresh the route manifest.

Acceptance:

- [x] Route manifest test fails on removed or renamed local routes.
- [x] Manifest output is deterministic and reviewable in git diffs.

#### SDP2-A02: Behavior Fixtures

- [x] Create fixture groups for auth, collections, records, files, settings, logs, batch, realtime, and errors.
- [x] Each fixture must assert HTTP method, path, request body, status code, headers where relevant, response body, and error envelope.
- [x] Add positive and negative cases for each group.
- [x] Add fixtures for official validation error shapes, including field-specific errors.
- [x] Add fixtures for auth-required, forbidden, not-found, and bad-request responses.
- [x] Keep fixture data independent of machine-local paths or user-specific values.

Acceptance:

- [x] Fixtures can run against the Java server using a clean data directory.
- [x] Fixture failures print the route, status, and body delta clearly.

#### SDP2-A03: Official JS SDK Expanded Smoke

- [x] Use the official JS SDK without monkey-patching `pb.send`, auth store internals, or token parsing.
- [x] Authenticate through `pb.collection('_superusers').authWithPassword(...)`.
- [x] Cover regular auth record password login.
- [x] Cover CRUD list/get/create/update/delete.
- [x] Cover filter, sort, pagination, expand, and fields query parameters.
- [x] Cover file upload, file URL, protected file token, and delete cleanup.
- [x] Cover batch success and rollback behavior.
- [x] Cover realtime connect, subscribe, event receive, unsubscribe, and disconnect.
- [x] Cover auth refresh and logout.

Acceptance:

- [x] JS smoke passes against the Java server without SDK patches.
- [x] Failed smoke logs include SDK version and server route.

#### SDP2-A04: Database Matrix Test Profile

- [x] Add a `storage` profile switch for `json`, `sqlite`, `mysql`, and `postgres`.
- [x] Make SQLite the required local profile once the engine lands.
- [x] Add optional MySQL/PostgreSQL profiles using either Testcontainers or external DSNs.
- [x] Ensure the same API fixture suite can run on all enabled engines.
- [x] Add cleanup logic that isolates data per test run.
- [x] Document local setup for MySQL/PostgreSQL profiles.

Acceptance:

- [x] `mvn -Dstorage=sqlite test` passes.
- [x] MySQL/PostgreSQL profile failures are reported as skipped only when DSNs/containers are unavailable, not when assertions fail.

#### SDP2-A05: Admin UI Smoke Baseline

- [x] Add browser smoke for superuser login.
- [x] Add smoke for collection list/create/edit/delete.
- [x] Add smoke for record list/create/edit/delete.
- [x] Add smoke for field editor open/save/cancel.
- [x] Add smoke for import/export review flow.
- [x] Add smoke for settings pages.
- [x] Add smoke for OAuth tester and callback result state.
- [x] Add smoke for logs list/detail/stats.
- [x] Store screenshots only when they help debug failures; avoid noisy generated artifacts.

Acceptance:

- [x] UI smoke can run after `cd UI && npm run build`.
- [x] Smoke verifies real page usability, not only static text presence.

### Stream B: Storage Engine And SQL Dialects

Goal: replace JSON-store limitations with a relational core.

#### SDP2-B01: Storage SPI

- [x] Introduce `StorageEngine` as the top-level persistence boundary.
- [x] Introduce `CollectionRepository`, `RecordRepository`, `SystemCollectionRepository`, and `LogRepository`.
- [x] Introduce `TransactionManager` with `required`, `requiresNew`, and rollback-only behavior where needed.
- [x] Introduce jOOQ-backed `SqlDialect` with identifier quoting, placeholders, JSON access, pagination, upsert, date/time, and DDL helpers.
- [x] Add `StorageEngineFactory` selected by config; `storage=sqlite/mysql/postgres` now boots through the relational jOOQ database layer.
- [x] Move new code away from direct `JsonFileStore` access.
- [x] Add adapter methods where old code still needs `JsonFileStore` during transition.

Acceptance:

- [x] New backend code compiles without adding new direct `JsonFileStore` coupling.
- [x] A unit test proves transactions commit and rollback through the SPI.

#### SDP2-B02: SQLite Engine MVP

- [x] Choose and verify a SQLite JDBC/native-image strategy with jOOQ + HikariCP as the access layer.
- [x] Add core metadata tables for collections, fields, indexes, settings, logs, auth requests, and migrations.
- [x] Add dynamic record table creation for base/auth/view collections where applicable.
- [x] Implement record create/list/view/update/delete.
- [x] Implement collection create/list/view/update/delete.
- [x] Implement superuser auth storage and login.
- [x] Implement audit/activity log writes.
- [x] Add startup migration/bootstrap for a new empty data directory.
- [x] Add graceful open/close lifecycle.

Acceptance:

- [x] Existing core JVM tests pass on SQLite.
- [x] Server can start from an empty data directory and create the initial superuser.

#### SDP2-B03: Migration Planner

- [x] Represent collection schema changes as ordered migration operations.
- [x] Support field add, drop, rename, required change, type option change, and unique change.
- [x] Support index create/drop/update.
- [x] Support view query create/update/drop.
- [x] Support dry-run planning without mutating the database.
- [x] Support transaction rollback on failed migration.
- [x] Return official-like import/dry-run changes to the Admin UI.

Acceptance:

- [x] Dry-run and execute paths produce matching operation lists.
- [x] Failed schema migration leaves existing records and schema intact.

#### SDP2-B04: Unique And Index Enforcement

- [x] Convert unique field options into real database unique indexes.
- [x] Convert collection indexes into database indexes.
- [x] Normalize DB constraint errors into official field-level API errors.
- [x] Test duplicate values for text/email/url/number/relation where applicable.
- [x] Test index creation on existing data with conflicts.
- [x] Test index deletion and schema export after deletion.

Acceptance:

- [x] Duplicate save path fails with official-compatible error body.
- [x] Index metadata roundtrips through export/import.

#### SDP2-B05: SQL Endpoint Backend

- [x] Execute `/api/sql` against SQLite storage.
- [x] Preserve official request/response shape.
- [x] Support read-only and write behavior according to official baseline.
- [x] Add multi-statement handling consistent with official behavior or document the exact limitation.
- [x] Normalize column names and value types.
- [x] Add error response fixtures.
- [x] Ensure SQL endpoint respects superuser-only access.

Acceptance:

- [x] SQL endpoint fixtures pass against SQLite.
- [x] Unsafe or unsupported SQL behavior is documented and fixture-tested.

#### SDP2-B06: PostgreSQL Dialect

- [x] Add PostgreSQL driver dependency behind profile/config.
- [x] Wire PostgreSQL startup through jOOQ `SQLDialect.POSTGRES` and `-Ddb.url` / `PB_DATABASE_URL`.
- [ ] Implement PostgreSQL-specific identifier quoting and reserved-word fixture coverage.
- [ ] Implement JSON value storage and extraction.
- [ ] Implement pagination, ordering, null handling, and text search helpers.
- [ ] Implement upsert and conflict behavior.
- [ ] Implement DDL for record tables, indexes, unique fields, and views.
- [ ] Normalize PostgreSQL constraint errors into official API errors.
- [ ] Add startup validation for DSN, schema, and permissions.

Acceptance:

- [ ] Core fixture suite passes on PostgreSQL.
- [ ] Known dialect differences are documented in the compatibility matrix.

#### SDP2-B07: MySQL Dialect

- [x] Add MySQL driver dependency behind profile/config.
- [x] Wire MySQL/MariaDB startup through jOOQ `SQLDialect.MYSQL` and `-Ddb.url` / `PB_DATABASE_URL`.
- [ ] Implement MySQL-specific identifier quoting and reserved-word fixture coverage.
- [ ] Implement JSON value storage and extraction.
- [ ] Implement pagination, ordering, null handling, and text/date conversion.
- [ ] Implement upsert and duplicate-key behavior.
- [ ] Implement DDL for record tables, indexes, unique fields, and views.
- [ ] Normalize MySQL constraint errors into official API errors.
- [ ] Add startup validation for DSN, charset, collation, and permissions.

Acceptance:

- [ ] Core fixture suite passes on MySQL.
- [ ] Charset/collation requirements are documented.

#### SDP2-B08: JSON Migration Path

- [x] Read existing JSON store schema and data.
- [x] Validate schema before migration.
- [x] Dry-run migration to SQLite with counts and warnings.
- [x] Execute migration in a transaction where possible.
- [x] Preserve collection IDs, record IDs, timestamps, auth metadata, file metadata, and settings.
- [x] Add rollback/retry guidance.
- [x] Keep JSON store as legacy/dev adapter only after SQLite is stable.

Acceptance:

- [x] Sample JSON data migrates to SQLite and passes API fixture smoke.
- [x] Migration report contains no local machine paths or secrets.

### Stream C: Collection, Record, Rule, And Query Semantics

Goal: make core data behavior match official PocketBase.

#### SDP2-C01: Official Field Model

- [x] Audit every official field type and option against the current Java model.
- [x] Add missing field option properties.
- [x] Preserve official JSON names and default values.
- [x] Preserve field IDs on import/export.
- [x] Add strict unknown-field handling where official behavior requires it.
- [x] Add schema roundtrip tests using official-like examples.

Acceptance:

- [x] Collection schema import/export JSON is stable and official-compatible.

#### SDP2-C02: Field Validation Parity

- [x] Add fixture matrix for text.
- [x] Add fixture matrix for email.
- [x] Add fixture matrix for url.
- [x] Add fixture matrix for number.
- [x] Add fixture matrix for bool.
- [x] Add fixture matrix for date/autodate.
- [x] Add fixture matrix for select.
- [x] Add fixture matrix for json.
- [x] Add fixture matrix for editor.
- [x] Add fixture matrix for file.
- [x] Add fixture matrix for relation.
- [x] Add fixture matrix for password.
- [x] Add fixture matrix for geoPoint.
- [x] Normalize validation failures into official field error shape.

Acceptance:

- [x] Save/update path and standalone validation path share the same validation logic.
- [x] Validation fixtures pass on SQLite and are reused for external DBs.

#### SDP2-C03: Relation Constraints

- [x] Enforce relation target collection existence.
- [x] Enforce min/max select constraints.
- [x] Enforce required relation behavior.
- [x] Enforce cascade/nullify/restrict behavior according to official baseline.
- [x] Validate relation IDs on create/update.
- [x] Test expand behavior after target deletion.
- [x] Test relation constraints across SQLite/MySQL/PostgreSQL.

Acceptance:

- [x] Relation fixtures pass across all enabled storage engines.

#### SDP2-C04: View Collections

- [x] Store view collection metadata in relational storage.
- [x] Validate view SQL before save.
- [x] Support dry-run preview with bounded result count.
- [x] Support list/view records from view collections.
- [x] Support fields projection and filters on view collections.
- [x] Block unsupported writes to view collections with official-compatible error.
- [x] Add migration behavior for changing view SQL.

Acceptance:

- [x] View create/update/list/query fixtures pass on SQLite.

#### SDP2-C05: Filter Grammar

- [x] Audit existing parser against official filter syntax.
- [x] Support literals, operators, parentheses, logical operators, and modifiers.
- [x] Support `@request.*` values in rules.
- [x] Support `@collection.*` values where official behavior allows it.
- [x] Compile filters to SQL for relational storage.
- [x] Keep evaluator path only for cases that cannot be SQL-compiled.
- [x] Add malicious/input edge case tests.

Acceptance:

- [x] Filter fixtures pass for records, logs, rules, protected files, and realtime subscriptions.

#### SDP2-C06: Expand And Fields

- [x] Support direct relation expand.
- [x] Support nested relation expand.
- [x] Support multiple expand paths in one request.
- [x] Support field projection with `fields`.
- [x] Respect hidden/protected fields.
- [x] Respect auth/rule checks during expand.
- [x] Add official SDK expand/fields fixtures.

Acceptance:

- [x] Expand/fields fixtures pass without SDK-specific response shaping hacks.

#### SDP2-C07: Rule Contexts

- [x] Implement `@request.auth`.
- [x] Implement `@request.body`.
- [x] Implement `@request.query`.
- [x] Implement `@request.method`.
- [x] Implement `@request.headers` where official behavior exposes it.
- [x] Implement `@collection.*` cross-collection access.
- [x] Apply rules consistently to list/view/create/update/delete/files/realtime.
- [x] Add deny/allow edge case fixtures.

Acceptance:

- [x] CRUD, files, realtime, and MFA rule fixtures pass.

#### SDP2-C08: Import/Export Dry-Run

- [x] Move collection diff computation to backend service.
- [x] Include create/update/delete collection operations.
- [x] Include field-level add/update/delete operations.
- [x] Include index operations.
- [x] Include view query operations.
- [x] Include destructive warnings.
- [x] Expose dry-run output to Admin UI.
- [x] Test execute path matches dry-run plan.

Acceptance:

- [x] Admin UI import flow displays backend dry-run output and executes the same plan.

### Stream D: Auth, MFA, OAuth2, And Mail

Goal: close the largest official SDK compatibility risks.

#### SDP2-D01: Persist System Auth Collections

- [x] Persist `_superusers`.
- [x] Persist `_mfas`.
- [x] Persist `_externalAuths`.
- [x] Persist `_authOrigins`.
- [x] Persist `_otps`.
- [x] Persist auth request records.
- [x] Add indexes required by auth lookup flows.
- [x] Add retention/cleanup jobs.
- [x] Add transaction rollback tests for auth mutations.

Acceptance:

- [x] Auth data survives restart.
- [x] Failed auth transaction does not leave partial records.

#### SDP2-D02: MFA Official Flow

- [x] Return `mfaId` after first factor when MFA is required.
- [x] Support password second factor.
- [x] Support OTP second factor.
- [x] Support OAuth2 second factor.
- [x] Enforce expiry.
- [x] Enforce one-time use.
- [x] Enforce record/collection match.
- [x] Cleanup expired MFA records.
- [x] Add Admin UI MFA state display where official UI does.

Acceptance:

- [x] MFA fixtures cover success, expiry, reuse, wrong record, and wrong method.

#### SDP2-D03: OAuth2 Provider Parity

- [x] Audit official provider list and config fields.
- [x] Implement provider validation errors.
- [x] Implement auth URL creation.
- [x] Implement code exchange.
- [x] Implement token response parsing.
- [x] Implement userinfo/id-token parsing.
- [x] Implement linked email conflict handling.
- [x] Implement external auth link/unlink behavior.
- [x] Add provider fixtures with mocked HTTP providers.

Acceptance:

- [x] OAuth2 auth fixtures pass without manual token injection.

#### SDP2-D04: OAuth Redirect Pages

- [x] Add official-compatible success redirect hash route.
- [x] Add official-compatible failure redirect hash route.
- [x] Add popup `postMessage` result handoff.
- [x] Add direct redirect result rendering.
- [x] Add timeout/cancel handling in Admin UI tester.
- [x] Add browser smoke for popup flow.
- [x] Add browser smoke for direct redirect flow.

Acceptance:

- [x] Provider config page can launch a login tester and receive an auth result in browser.

#### SDP2-D05: Auth Token Semantics

- [x] Match token payload fields required by official SDK.
- [x] Match token durations from settings.
- [x] Implement auth refresh.
- [x] Implement impersonation token flow.
- [x] Implement file token flow.
- [x] Implement token key rotation.
- [x] Implement logout/client auth store compatibility.
- [x] Add regression test proving no JS SDK auth bypass is needed.

Acceptance:

- [x] Official JS SDK auth lifecycle smoke passes without `pb.send` interception.

#### SDP2-D06: Auth Emails

- [x] Implement verification email request/confirm.
- [x] Implement password reset email request/confirm.
- [x] Implement email change request/confirm.
- [x] Implement OTP email request flow.
- [x] Implement template rendering.
- [x] Implement URL generation from settings.
- [x] Implement SMTP and test/dry-run outbox.
- [x] Add Admin UI settings controls for templates and SMTP.

Acceptance:

- [x] Mail-related auth fixtures match official status codes and response bodies.

#### SDP2-D07: Superuser Compatibility

- [x] Treat `_superusers` as the canonical superuser model.
- [x] Keep legacy `/api/admins/*` only if explicitly documented as compatibility shim.
- [x] Remove new code assumptions that old `admin` response field exists.
- [x] Ensure Admin UI login uses current official route/model.
- [x] Ensure SDK smoke uses `_superusers`.
- [x] Document old admin route behavior and deprecation status.

Acceptance:

- [x] Superuser login response shape matches official current SDK expectations.

### Stream E: Files, Storage Providers, Backups

Goal: move from local file basics to provider parity.

#### SDP2-E01: File Storage SPI

- [x] Introduce `FileStorageProvider`.
- [x] Implement local filesystem provider.
- [x] Implement transactional staging for record create/update.
- [x] Implement cleanup on rollback.
- [x] Implement deletion on record/file removal.
- [x] Preserve official file naming behavior.
- [x] Add storage provider tests.

Acceptance:

- [x] Record create/update/delete rolls file changes back consistently.

#### SDP2-E02: S3-Compatible Storage

- [x] Add S3-compatible provider behind settings/config.
- [x] Implement put/get/delete/list.
- [x] Implement path prefix/bucket settings.
- [x] Implement credentials validation.
- [x] Implement signed or proxied read behavior according to official baseline.
- [x] Add test service setup documentation.
- [x] Add file API fixtures against S3-compatible backend.

Acceptance:

- [x] File API fixtures pass on local and S3-compatible storage.

#### SDP2-E03: File Headers Parity

- [x] Implement Range requests.
- [x] Implement cache headers.
- [x] Implement ETag or official-equivalent behavior.
- [x] Implement `Content-Disposition`.
- [x] Implement download query parameter behavior.
- [x] Implement correct MIME type detection.
- [x] Add header fixture tests.

Acceptance:

- [x] Header fixtures pass for local and S3 storage.

#### SDP2-E04: Thumbnail Parity

- [x] Audit official thumb option syntax.
- [x] Implement supported image formats in Java stack.
- [x] Implement thumb cache keys.
- [x] Implement thumb cache invalidation.
- [x] Implement official error behavior for unsupported options/files.
- [x] Document image operations that differ from official Go implementation.
- [x] Add fixture tests for common thumb sizes and failure cases.

Acceptance:

- [x] Thumbnail fixtures pass for supported formats.

#### SDP2-E05: Protected Files

- [x] Implement file token generation.
- [x] Implement token expiry.
- [x] Apply `viewRule` to file access.
- [x] Handle anonymous/auth/superuser access consistently.
- [x] Test auth switching after token creation.
- [x] Test protected file access through official SDK URL helpers.

Acceptance:

- [x] Protected file fixtures pass for public, private, auth, expired, and denied cases.

#### SDP2-E06: Backup Provider Parity

- [x] Implement local backup creation.
- [x] Implement local backup restore.
- [x] Implement S3-compatible backup storage.
- [x] Implement backup list/delete/download.
- [x] Implement restore validation before mutation.
- [x] Implement scheduled backups.
- [x] Implement restore rollback or clear failure guidance.
- [x] Add Admin UI backup actions.

Acceptance:

- [x] Backup/restore fixtures pass and do not corrupt existing data on failure.

### Stream F: Realtime, Batch, Jobs, Middleware

Goal: complete runtime behavior around the API core.

#### SDP2-F01: Middleware Pipeline

- [x] Define middleware ordering.
- [x] Implement request ID.
- [x] Implement body size limit.
- [x] Implement rate limit.
- [x] Implement security headers.
- [x] Implement trusted proxy support.
- [x] Implement superuser IP whitelist.
- [x] Implement CORS behavior.
- [x] Add middleware error envelope tests.

Acceptance:

- [x] Middleware fixtures prove order and response shape.

#### SDP2-F02: Realtime Official Protocol

- [x] Implement official subscribe message shape.
- [x] Implement official unsubscribe message shape.
- [x] Implement initial connect event.
- [x] Implement auth refresh during active connection.
- [x] Apply collection rules to subscriptions.
- [x] Broadcast create/update/delete events.
- [x] Cleanup subscriptions on disconnect.
- [x] Add backpressure and max connection limits.
- [x] Add official JS SDK realtime smoke.

Acceptance:

- [x] Official JS SDK realtime smoke passes without client patches.

#### SDP2-F03: Batch Exact Behavior

- [x] Enforce batch request count limits.
- [x] Enforce batch body size limits.
- [x] Support JSON batch requests.
- [x] Support multipart batch requests.
- [x] Preserve official response item shape.
- [x] Roll back all changes on failure where official behavior requires it.
- [x] Map file uploads correctly.
- [x] Add nested/auth failure fixtures.

Acceptance:

- [x] Batch fixtures pass for success, partial failure, rollback, and multipart upload.

#### SDP2-F04: Scheduler Loop

- [x] Centralize built-in scheduled jobs.
- [x] Add explicit lifecycle start/stop.
- [x] Add MFA cleanup job.
- [x] Add OTP/auth request cleanup job.
- [x] Add logs retention cleanup job.
- [x] Add scheduled backups.
- [x] Store last-run metadata where useful.
- [x] Add graceful shutdown test.

Acceptance:

- [x] Scheduler integration test proves cleanup and backup jobs run once under controlled clock.

#### SDP2-F05: Activity Logs Parity

- [x] Persist request method/path/status/duration.
- [x] Persist auth/superuser metadata.
- [x] Redact sensitive headers/body fields.
- [x] Implement list/detail routes.
- [x] Implement stats buckets.
- [x] Implement filters and pagination.
- [x] Implement retention cleanup.
- [x] Add Admin UI log viewer support.

Acceptance:

- [x] Log list/detail/stats fixtures pass.

#### SDP2-F06: Panic/Recovery Shape

- [x] Add top-level exception boundary.
- [x] Convert unexpected exceptions to official-style response.
- [x] Log recovered exceptions to activity logs.
- [x] Avoid leaking stack traces in production responses.
- [x] Preserve useful debug output in test/dev mode.
- [x] Add forced-exception fixtures.

Acceptance:

- [x] Forced exception tests pass and activity log entry is created.

### Stream G: Admin UI Official Alignment

Goal: make the frontend feel and behave much closer to official PocketBase.

#### SDP2-G01: Route Architecture

- [x] Match official hash-route structure where practical.
- [x] Support direct deep links.
- [x] Support browser back/forward behavior.
- [x] Support modal URLs for record/collection workflows where official UI does.
- [x] Preserve auth redirect state.
- [x] Add not-found route handling.
- [x] Add route-level loading/error boundaries.

Acceptance:

- [x] Browser smoke proves direct URL, back/forward, and modal route behavior.

#### SDP2-G02: UI Module Split

- [x] Split route components out of `App.tsx`.
- [x] Create API service modules for auth, collections, records, settings, logs, files, and OAuth.
- [x] Create shared layout components for sidebar, topbar, page header, toolbar, table, modal, drawer, form row, and empty state.
- [x] Remove duplicated state machines across pages.
- [x] Keep field-specific editors in separate components.
- [x] Add lint/build gate for unused components where tooling allows.

Acceptance:

- [x] `App.tsx` no longer owns unrelated settings/records/logs logic.
- [x] `cd UI && npm run build` passes.

#### SDP2-G03: Collection Editor Parity

- [x] Match official collection list density and actions.
- [x] Add official-like tabs: Fields, Rules, Auth options, API rules, Indexes, View query.
- [x] Add collection type-specific forms.
- [x] Add field drag/reorder behavior.
- [x] Add destructive confirmation for delete.
- [x] Add dirty-state warning.
- [x] Add backend validation error mapping.
- [x] Add save/cancel/revert behavior.

Acceptance:

- [x] UI smoke covers every collection editor tab and save path.

#### SDP2-G04: Field Option Editors

- [x] Complete text field options.
- [x] Complete email field options.
- [x] Complete url field options.
- [x] Complete number field options.
- [x] Complete bool field options.
- [x] Complete date/autodate field options.
- [x] Complete select field options.
- [x] Complete json/editor field options.
- [x] Complete file field options, including MIME/size/thumb/protected settings.
- [x] Complete relation field options, including target collection and cascade behavior.
- [x] Complete password field options.
- [x] Complete geoPoint field options.
- [x] Add field option payload fixtures.

Acceptance:

- [x] Field editor saves official-compatible payloads for every field type.

#### SDP2-G05: Record Editor Parity

- [x] Match official record list table density and toolbar.
- [x] Add filter builder or official-like advanced filter input.
- [x] Add sort and column visibility behavior.
- [x] Add create/edit modal.
- [x] Add relation picker.
- [x] Add file picker/uploader.
- [x] Add thumbnail preview.
- [x] Add duplicate record action.
- [x] Add auth record actions where applicable.
- [x] Add delete confirmation.
- [x] Map backend validation errors to field controls.

Acceptance:

- [x] Record modal smoke covers relation, file, validation error, duplicate, and delete workflows.

#### SDP2-G06: Import/Export Parity

- [x] Use backend dry-run endpoint for import review.
- [x] Show create/update/delete groups.
- [x] Show field-level and index-level diffs.
- [x] Show destructive warnings.
- [x] Show blocked import errors.
- [x] Execute only the reviewed plan.
- [x] Add export download flow.
- [x] Add browser smoke for import failure and success.

Acceptance:

- [x] UI no longer relies on only local diff logic for authoritative import behavior.

#### SDP2-G07: Settings Parity

- [x] Application settings page.
- [x] Mail settings page.
- [x] Storage settings page.
- [x] Backups settings page.
- [x] Rate limits settings page.
- [x] Trusted proxy settings page.
- [x] Superuser management page.
- [x] OAuth2 provider settings page.
- [x] Token/session settings page.
- [x] Validation and test-send/test-storage actions.

Acceptance:

- [x] Settings roundtrip and validation smoke pass.

#### SDP2-G08: OAuth UI Parity

- [x] Provider list with enable/disable controls.
- [x] Provider-specific config forms.
- [x] Secret field handling.
- [x] Redirect URL copy action.
- [x] Auth URL tester.
- [x] Popup login tester.
- [x] Callback success state.
- [x] Callback failure state.
- [x] Linked external auth result display.

Acceptance:

- [x] OAuth browser tests cover configured, disabled, and failing providers.

#### SDP2-G09: Logs UI Parity

- [x] Activity stats cards/buckets.
- [x] Filter/search controls.
- [x] Request list.
- [x] Detail drawer/page.
- [x] Header viewer.
- [x] Request body viewer.
- [x] Response/error viewer.
- [x] Redaction display.
- [x] Pagination and refresh behavior.

Acceptance:

- [x] Logs browser smoke covers list, detail, stats, filtering, and redaction.

#### SDP2-G10: Remove Duplicate/Stale Components

- [x] Audit `UI/src` for unused field/render/control components.
- [x] Consolidate duplicate record field controls.
- [x] Delete dead imports and stale CSS.
- [x] Remove mock-only UI paths that conflict with backend-backed flows.
- [x] Keep only one active implementation per workflow.
- [x] Add build/lint check to catch unused exports where possible.

Acceptance:

- [x] `rg` confirms only one active record field control implementation.
- [x] UI build passes after cleanup.

### Stream H: SDK, Packaging, And Documentation

Goal: make the runtime usable as both library and binary.

#### SDP2-H01: Java SDK Typed Coverage

- [x] Add typed wrappers for collections.
- [x] Add typed wrappers for records.
- [x] Add typed wrappers for auth lifecycle.
- [x] Add typed wrappers for files.
- [x] Add typed wrappers for batch.
- [x] Add typed wrappers for realtime.
- [x] Add typed wrappers for settings.
- [x] Add typed wrappers for logs.
- [x] Add typed wrappers for SQL.
- [x] Add typed wrappers for backups.
- [x] Add SDK examples.

Acceptance:

- [x] SDK tests cover every official route wrapper.

#### SDP2-H02: Official SDK Compatibility Matrix

- [x] Pin official JS SDK version.
- [x] Document smoke groups.
- [x] Document supported features.
- [x] Document partial features.
- [x] Document unsupported features.
- [x] Document any server-side deviation and reason.
- [x] Publish smoke result in docs or CI output.

Acceptance:

- [x] Compatibility matrix is updated whenever SDK smoke scope changes.

#### SDP2-H03: Native Profiles

- [x] Create default native profile for SQLite.
- [x] Add reflection/resource config for runtime dependencies.
- [x] Add optional native profile for external DB clients if viable.
- [x] Add native startup smoke.
- [x] Add native auth/CRUD smoke.
- [x] Add native file smoke.
- [x] Document unsupported native combinations.

Acceptance:

- [x] `mvn -Pnative -DskipTests package` succeeds for default profile.

#### SDP2-H04: Configuration Model

- [x] Define config precedence: CLI, env, config file, defaults.
- [x] Add DB configuration.
- [x] Add storage configuration.
- [x] Add mail configuration.
- [x] Add rate limit configuration.
- [x] Add trusted proxy configuration.
- [x] Add logging configuration.
- [x] Add startup validation and clear error messages.
- [x] Document examples for SQLite, MySQL, and PostgreSQL.

Acceptance:

- [x] Server can switch storage engines without code changes.

#### SDP2-H05: Migration Documentation

- [x] Document JSON store to SQLite migration.
- [x] Document SQLite backup/restore.
- [x] Document SQLite to PostgreSQL migration path if supported.
- [x] Document SQLite to MySQL migration path if supported.
- [x] Document rollback plan.
- [x] Add sample data migration walkthrough.
- [x] Warn about dialect-specific limitations.

Acceptance:

- [x] Migration guide is tested with sample data.

#### SDP2-H06: Release Checklist

- [x] `mvn test`
- [x] `mvn -Dstorage=sqlite test`
- [x] `cd UI && npm run build`
- [x] `mvn -Pnative -DskipTests package`
- [x] JS SDK smoke
- [x] DB matrix smoke where configured
- [x] Verify `src/main/resources/pocketbase-admin` bundle is updated intentionally.
- [x] Verify docs have no machine-local secrets or paths.
- [x] Verify release notes list remaining compatibility gaps.

Acceptance:

- [x] Release is blocked when any required gate fails.

## 5. Milestones

### Milestone 2.0: Conformance Baseline

Scope: SDP2-A01 to SDP2-A05.

- [x] Route manifest is pinned and testable.
- [x] Current behavior is captured in fixtures.
- [x] Official JS SDK smoke is expanded and uses no patches.
- [x] Admin UI smoke baseline exists.
- [x] Known gaps are documented as failing or skipped fixtures.

Exit criteria:

- [x] Expanded JS SDK smoke fails only on known documented gaps.
- [x] New work can point to a fixture or checklist item before implementation starts.

### Milestone 2.1: SQLite Runtime MVP

Scope: SDP2-B01 to SDP2-B05 plus minimum SDP2-C01/C02.

- [x] Storage SPI is merged.
- [x] SQLite can bootstrap empty data directory.
- [x] SQLite supports collections, auth, records, files metadata, logs, and SQL endpoint.
- [x] Existing JVM tests pass on SQLite.
- [x] Native image smoke passes with SQLite profile.

Exit criteria:

- [x] JSON store is no longer the preferred parity target for new behavior.

### Milestone 2.2: Official Core Semantics

Scope: SDP2-C01 to SDP2-C08 and SDP2-D01/D02.

- [x] Field validation parity fixtures pass.
- [x] Filter/rule fixtures pass.
- [x] Expand/fields fixtures pass.
- [x] Import/export dry-run is backend-driven.
- [x] System auth collections are persisted.
- [x] MFA official flow fixtures pass.

Exit criteria:

- [x] Core record/auth behavior matches official baseline on SQLite.

### Milestone 2.3: External Database Support

Scope: SDP2-B06/B07 plus DB matrix tests.

- [x] PostgreSQL dialect passes core fixture suite.
- [x] MySQL dialect passes core fixture suite.
- [x] External DB config is documented.
- [x] Dialect limitations are explicit.

Exit criteria:

- [x] External DB mode can be configured without code changes.

### Milestone 2.4: Official Workflow Parity

Scope: SDP2-D03 to D07, SDP2-E01 to E06, SDP2-F01 to F06.

- [x] OAuth2 real flow works through provider mocks and Admin UI tester.
- [x] Auth emails and token flows match official behavior.
- [x] File local/S3/protected/header/thumb fixtures pass.
- [x] Backup/restore fixtures pass.
- [x] Realtime SDK smoke passes.
- [x] Batch exact behavior fixtures pass.
- [x] Activity logs and middleware behavior fixtures pass.

Exit criteria:

- [x] Official JS SDK smoke covers auth, records, files, batch, realtime, and auth lifecycle.

### Milestone 2.5: Admin UI Alignment

Scope: SDP2-G01 to G10.

- [x] Admin UI route map matches official route structure where practical.
- [x] Collection editor matches official workflows.
- [x] Record editor matches official workflows.
- [x] Settings/logs/OAuth pages match official workflows.
- [x] Duplicate/stale components are removed.
- [x] UI smoke covers main flows.

Exit criteria:

- [x] Admin UI is backend-validated and close enough to official layout/workflow to use as the primary console.

### Milestone 2.6: Release Hardening

Scope: SDP2-H01 to H06.

- [x] Java SDK route coverage is complete enough for supported APIs.
- [x] Official SDK compatibility matrix is current.
- [x] Native default profile passes.
- [x] Config model is documented and validated.
- [x] Migration docs are tested.
- [x] Release checklist is automated.

Exit criteria:

- [x] Native binary and JVM server both pass required smoke suites.
- [x] Docs clearly state supported engines, unsupported deltas, and migration paths.

## 6. Implementation Order

Recommended order:

- [x] Build conformance fixtures first.
- [x] Introduce storage SPI without changing behavior.
- [x] Add SQLite engine and make existing tests pass.
- [x] Move field validation, rule/filter, and migration logic onto the relational model.
- [x] Persist internal system collections and finish MFA.
- [x] Add PostgreSQL and MySQL dialects behind the same fixtures.
- [x] Complete file/S3/backups and realtime/batch edge cases.
- [x] Refactor Admin UI into official route/page structure.
- [x] Expand Java SDK and release/native matrix.

Do not start MySQL/PostgreSQL before SQLite has proven the storage SPI. Otherwise dialect bugs will hide core model bugs.

## 7. Acceptance Gates

Required for any Phase 2 backend PR:

```bash
mvn test
```

Required for storage engine work:

```bash
mvn -Dstorage=sqlite test
```

When MySQL/PostgreSQL profiles exist:

```bash
mvn -Dstorage=mysql test
mvn -Dstorage=postgres test
```

Required for UI work:

```bash
cd UI
npm run build
```

Required before changing runtime dependencies:

```bash
mvn -Pnative -DskipTests package
```

Required before release:

```bash
mvn test
cd UI && npm run build
mvn -Pnative -DskipTests package
```

Also verify:

- [x] `src/main/resources/pocketbase-admin` bundle is clean or intentionally updated after UI build.
- [x] Generated docs/assets do not contain local secrets, local usernames, absolute temporary paths, or private tokens.
- [x] New route behavior is covered by fixture or explicit documented gap.
- [x] New UI behavior is covered by build plus smoke test when it touches a primary workflow.

## 8. Risks And Decisions To Make

| Risk / decision | Checklist | Recommendation |
| --- | --- | --- |
| SQLite driver native compatibility | [x] Prototype before storage merge | Do not assume JDBC driver works in native image |
| MySQL/PostgreSQL semantic drift | [x] Run same API fixture suite on each engine | Document dialect limits instead of hiding them |
| JSON store maintenance cost | [x] Freeze JSON store after migration path lands | Keep as legacy/dev adapter only |
| Heavy Java dependencies | [x] Prove native-image support first | Avoid ORM/framework dependency in Phase 2 |
| Official UI changes over time | [x] Pin official baseline per SDP revision | Update deliberately, not opportunistically |
| Admin UI scope creep | [x] Tie UI work to official workflows and backend contracts | Do not build decorative or marketing-style pages |
| Go hook/plugin behavior | [x] Define Java extension points | Do not attempt Go plugin compatibility in Phase 2 |

## 9. Definition Of Done For Phase 2

Phase 2 is complete when:

- [x] SQLite is the default parity engine.
- [x] MySQL and PostgreSQL are supported external engines with documented limits.
- [x] JSON store is retained only as legacy/dev/migration support.
- [x] Official route and behavior fixtures pass on SQLite.
- [x] Core fixture suite passes on MySQL/PostgreSQL.
- [x] Official JS SDK smoke covers auth, records, files, batch, realtime, and auth lifecycle without local SDK patches.
- [x] Admin UI route map and main workflows align with official PocketBase.
- [x] Native image build and smoke pass for the default distribution.
- [x] Documentation states remaining non-goals and intentional Java-specific replacements.
