# SDP Phase 3: PocketBase Parity And Release Hardening Plan

Updated: 2026-06-23

Primary inputs:

- `docs/SDP-PocketBase-Compatibility-Plan.md`
- `docs/SDP-Phase-2-PocketBase-Parity-Plan.md`
- Current source audit of `src/main/java`, `src/test/java`, `UI/src`, and native-image resources on 2026-06-23

Reference target: official `pocketbase/pocketbase` behavior and Admin UI workflows. Keep Java-native implementation choices where Go runtime behavior cannot or should not be copied directly.

## 1. Phase 3 Goal

Phase 2 introduced the relational storage direction, jOOQ foundation, broad route coverage, Admin UI improvements, and SDK smoke coverage. Phase 3 should move the project from "useful compatibility prototype" to "behaviorally credible PocketBase-compatible runtime".

The target is:

- [ ] Make SQLite relational storage complete enough to become the default parity baseline instead of JSON store.
- [ ] Make MySQL and PostgreSQL real supported engines, not only driver/config entry points.
- [ ] Replace remaining dummy/stub implementations in auth, settings, logs, crons, files, backups, and SQLite storage.
- [ ] Prove behavior with reusable fixture suites across JSON, SQLite, MySQL, and PostgreSQL where enabled.
- [ ] Bring Admin UI workflows closer to official PocketBase in routing, collection editing, record editing, settings, OAuth2, backups, logs, and SQL.
- [ ] Keep GraalVM native packaging green after jOOQ, JDBC drivers, storage providers, and UI bundle changes.

## 2. Status Model

Use this document as a future work board. Do not mark a task complete only because a route exists.

- `Missing`: no meaningful implementation is present.
- `Partial`: implementation exists but has simplified behavior, weak tests, or engine-specific gaps.
- `Done`: implemented, covered by tests, and verified against the relevant storage/UI/native gate.

Checklist convention:

- `[ ]`: Phase 3 work remaining.
- `[x]`: Phase 3 work completed and verified after this document was created.

## 3. Current Carryover Snapshot

These are the high-value carryovers found in the current tree.

### 3.1 Relational Storage

- `JooqDatabase` exists and selects SQLite/MySQL/PostgreSQL dialects.
- `LocalPocketBase` can route `storage=sqlite/mysql/postgres` into the relational storage class.
- `SqliteStorageEngine` now uses jOOQ for important DDL/CRUD/filter paths, but the class name and behavior are still SQLite/MVP oriented.
- MySQL/PostgreSQL are driver/config/dialect entry points only. There is no confirmed MySQL/PostgreSQL fixture pass.
- Several relational paths still use raw SQL or SQLite-specific behavior, especially SQL endpoint, rules cross-collection reads, log list, collection schema loading, record get, view creation, and request JSON helpers.
- SQLite storage still has visible stubs and placeholders:
  - settings return static `pocketbase-java`
  - S3/email test methods are no-op
  - Apple client secret returns `{ }`
  - log stats and log detail are empty/not found
  - cron list/run are empty/no-op
  - file token returns `file-token-dummy`
  - backups return empty/no-op results
  - OTP request/auth return `{ }`
  - impersonation returns `dummy-impersonate`
  - `upsertRecord`, `filePath`, and `backupFile` throw `UnsupportedOperationException`

### 3.2 Auth

- `AuthProcessor` still contains dummy no-op methods for password reset, verification, email change, and simulated OAuth2 failure in the SQLite path.
- MFA/OTP/OAuth2 have useful route and UI work, but official lifecycle persistence and complete SDK fixtures remain incomplete.
- Auth token shape has been improved for SDK compatibility, but token duration, token key rotation, file token, impersonation, and rate-limit behavior still need exact parity work.

### 3.3 Admin UI

- `UI/src` has pages/components for login, collections, records, field editor, settings, logs, crons, backups, import/export, SQL, OAuth tester, and auth action pages.
- Official hash route parity, field option parity, record editor parity, deep links, popup redirects, and visual parity remain incomplete.
- Built resources exist under `src/main/resources/pocketbase-admin`, but Phase 3 should verify generated bundle freshness in CI.

### 3.4 Native And Release

- Native image metadata exists under `src/main/resources/META-INF/native-image`.
- jOOQ/JDBC driver usage must be revalidated in native image mode.
- MySQL/PostgreSQL should be optional runtime engines without bloating or breaking the default SQLite native binary.

## 4. Immediate Priority Queue

Work these first before adding new broad features.

- [x] **P3-001 Stabilize and commit current jOOQ work**
  - Scope: current `FilterToSqlCompiler`, `JooqDatabase`, `SqliteStorageEngine`, and `BehaviorFixturesTest` changes.
  - Acceptance: `mvn -gs settings.xml -s settings.xml test`, `mvn -gs settings.xml -s settings.xml -Dstorage=sqlite -Dtest=BehaviorFixturesTest,AdminUiSmokeTest test`, `git diff --check`.

- [x] **P3-002 Rename and split relational storage**
  - Scope: `RelationalStorageEngine`, update initialization.
- [x] **P3-003 Make SQLite storage non-stub for core system endpoints**
  - Scope: settings, logs detail/stats, crons, backups, file token, OTP, impersonation, `upsertRecord`, `filePath`, `backupFile`.
  - Acceptance: no dummy token strings, no empty successful responses for official routes, no `UnsupportedOperationException` for reachable API routes.

- [x] **P3-004 Add real MySQL/PostgreSQL fixture gates**
  - Scope: Testcontainers or external DSN profiles for behavior fixtures.
  - Acceptance: skipped only when DSN/container is unavailable, failed when behavior differs.

- [x] **P3-005 Complete official error envelope and validation text audit**
  - Scope: auth, collection, record, file, settings, SQL, backup, batch, realtime errors.
  - Acceptance: fixtures assert `code`, `status`, `message`, `data`, nested field keys, and route-specific status codes.

- [x] **P3-006 Make Admin UI route and workflow parity measurable**
  - Scope: Playwright smoke for hash routes, login, collection edit, record edit, settings, logs, OAuth popup, backups, import/export, SQL.
  - Acceptance: browser smoke fails when a major official workflow becomes unreachable.

## 5. Workstreams


Acceptance:

- [x] `LocalPocketBase.start()` can select `json`, `sqlite`, `mysql`, and `postgres` without leaking engine-specific implementation names.
- [x] CRUD and batch rollback tests pass on SQLite after the refactor.

#### P3-A02: jOOQ Query Builder Coverage

- [ ] Convert remaining dynamic record reads to jOOQ: `getRecord`, `getCollectionSchema`, rule cross-collection record reads, and log list/detail.
- [ ] Replace raw `SELECT *` helpers with explicit jOOQ field/table helpers where response shape matters.
- [ ] Keep raw SQL only for official SQL endpoint and user-defined view SQL, with explicit validation.
- [ ] Extend `FilterToSqlCompiler` so equality, comparison, contains, logical operators, and `@request.*` values are all bound safely.
- [ ] Replace SQLite-only `json_extract(...)` request context handling with a dialect-aware abstraction or documented fallback.
- [ ] Add tests for string escaping, `%/_` LIKE escaping, null comparisons, booleans, numbers, dates, nested parentheses, and malicious filter input.

Acceptance:

- [ ] Filter tests pass on JSON and SQLite.
- [ ] MySQL/PostgreSQL generated SQL uses dialect-appropriate quoting, concatenation, pagination, and JSON extraction.

#### P3-A03: SQL Type Mapping

- [ ] Define the storage type mapping for every official field type: text, editor, email, url, number, bool, date, autodate, select, json, file, relation, password, geoPoint.
- [ ] Decide what remains text/blob metadata versus typed DB columns.
- [ ] Add migration tests for each field type.
- [ ] Normalize read values back into official record JSON types.
- [ ] Ensure select/json/relation arrays roundtrip consistently across SQLite/MySQL/PostgreSQL.

Acceptance:

- [ ] Field matrix tests pass on SQLite.
- [ ] MySQL/PostgreSQL field matrix tests are either passing or explicitly skipped by unavailable DSN only.

#### P3-A04: Schema Migration Planner

- [ ] Introduce a real migration plan object for collection changes.
- [ ] Plan and execute field add/drop/rename/type-option changes.
- [ ] Plan and execute index create/drop/update.
- [ ] Plan and execute view SQL create/update/drop.
- [ ] Return dry-run changes in the same structure the Admin UI executes.
- [ ] Guarantee rollback on failed migration where the database supports transactional DDL.
- [ ] Document per-dialect DDL limitations.

Acceptance:

- [ ] Dry-run and execute produce the same operation list.
- [ ] Failed migration does not leave mismatched `_collections` metadata and physical tables.

#### P3-A05: MySQL And PostgreSQL Profiles

- [ ] Add Maven profiles or properties for external DSNs.
- [ ] Add Testcontainers path if local Docker is available.
- [ ] Validate DSN, schema/database existence, permissions, timezone, charset, and collation on startup.
- [ ] Normalize duplicate key/unique constraint errors into official validation errors.
- [ ] Add MySQL and PostgreSQL CI jobs as optional first, then required once stable.
- [ ] Document local commands for both engines.

Acceptance:

- [ ] Core behavior fixtures pass on MySQL.
- [ ] Core behavior fixtures pass on PostgreSQL.
- [ ] Failures are behavior failures, not startup/config surprises.

#### P3-A06: SQLite Default Decision

- [ ] Define when SQLite replaces JSON as the default engine.
- [ ] Add migration path from existing JSON data to SQLite.
- [ ] Keep JSON store as dev/compatibility adapter or remove it from primary runtime.
- [ ] Update README, CLI examples, docs, and tests to reflect the default.

Acceptance:

- [ ] A clean server starts with SQLite by default if that is the chosen target.
- [ ] Existing JSON data migration is dry-run tested before destructive changes.

### Stream B: Official Behavior Fixtures

Goal: stop relying on route coverage and smoke tests as proof of parity.

#### P3-B01: Fixture Harness Expansion

- [ ] Group fixtures by official API area: auth, MFA, OAuth2, collections, records, files, settings, logs, backups, crons, SQL, batch, realtime.
- [ ] Assert method, path, query, request body, status, response body, and error envelope.
- [ ] Add negative cases for every route group.
- [ ] Add direct route behavior for unsupported methods, missing auth, forbidden, not found, bad JSON, malformed query, and invalid field values.
- [ ] Reuse the same fixtures across enabled storage engines.

Acceptance:

- [ ] Fixture output includes route, request body, status, and response body diff.
- [ ] Adding a route without official response behavior no longer counts as completion.

#### P3-B02: Official Baseline Refresh

- [ ] Pin an official PocketBase version or commit in every compatibility doc and fixture manifest.
- [ ] Add a script or documented manual process for refreshing the official route manifest.
- [ ] Compare local manifest to official route/method list.
- [ ] Track extra Java compatibility routes separately.
- [ ] Add a changelog section for official baseline changes.

Acceptance:

- [ ] A route change in the official manifest creates a reviewable git diff.

#### P3-B03: SDK Compatibility Matrix

- [ ] Keep JS SDK smoke patch-free.
- [ ] Add JS SDK coverage for auth records, files, batch, realtime unsubscribe, auth refresh, OAuth2 mock flow, MFA second factor, and fields/expand.
- [ ] Add Dart SDK smoke if project scope includes mobile/client parity.
- [ ] Keep Java client tests aligned with official route shapes.
- [ ] Record SDK versions in test output.

Acceptance:

- [ ] SDK tests prove no client-side request monkey-patching is needed.

### Stream C: Auth, MFA, OAuth2, And Mail

Goal: remove dummy auth paths and make official auth workflows durable.

#### P3-C01: Auth Action Persistence

- [ ] Replace dummy password reset, verification, and email change handlers.
- [ ] Persist auth request tokens with expiry and one-time-use semantics.
- [ ] Implement token validation, record lookup, and mutation in one transaction.
- [ ] Render official-compatible response bodies and validation errors.
- [ ] Add fixtures for expired, reused, wrong collection, wrong token, and successful flows.

Acceptance:

- [ ] Auth action pages work against real backend state, not dummy no-op routes.

#### P3-C02: OTP And MFA

- [ ] Persist `_otps` records for SQLite/MySQL/PostgreSQL.
- [ ] Persist `_mfas` records and cleanup expired records.
- [ ] Implement MFA second factor for password, OTP, and OAuth2.
- [ ] Enforce one-time-use, expiry, collection/record match, and method match.
- [ ] Add tests for superuser and auth collection MFA paths.

Acceptance:

- [ ] MFA and OTP survive restart where official behavior requires persistence.
- [ ] Fixtures cover success and failure paths.

#### P3-C03: OAuth2 Provider Matrix

- [ ] Replace simulated SQLite OAuth2 failure with a mock-provider-backed flow.
- [ ] Implement provider-specific config validation.
- [ ] Implement auth URL, code exchange, token parsing, id token parsing, userinfo lookup, and external auth linking.
- [ ] Handle email conflicts, verified email semantics, avatar/name forwarding, and unlink behavior.
- [ ] Implement official redirect success/failure hash pages and popup `postMessage` result.
- [ ] Add mocked HTTP fixtures for at least Google/OIDC/GitHub plus one edge provider.

Acceptance:

- [ ] OAuth2 auth fixtures pass without manual token injection.
- [ ] Admin UI tester can complete a mock-provider flow in browser.

#### P3-C04: Token Semantics

- [ ] Use settings-driven durations for auth, file, OTP, reset, verification, email change, and impersonation tokens.
- [ ] Implement token key rotation invalidation.
- [ ] Implement real file token generation and verification.
- [ ] Implement real impersonation token flow.
- [ ] Add constant-time password/auth checks where relevant.
- [ ] Add fixtures for expired tokens, rotated token keys, wrong collection, and auth refresh edge cases.

Acceptance:

- [ ] Official JS SDK auth lifecycle remains patch-free.

#### P3-C05: Mail System

- [ ] Implement SMTP delivery and dry-run/outbox mode.
- [ ] Store and render official mail templates.
- [ ] Generate correct URLs from settings.
- [ ] Add test-email behavior with useful error reporting.
- [ ] Add Admin UI template editing and validation.

Acceptance:

- [ ] Mail-related auth routes mutate and send exactly once per successful request.

### Stream D: Files, Storage Providers, And Backups

Goal: make file and backup behavior real across local and S3-compatible backends.

#### P3-D01: File Storage Provider SPI

- [ ] Define `FileStorageProvider` for put, get, delete, list, stat, signed/proxied read, and temporary staging.
- [ ] Implement local provider.
- [ ] Implement S3-compatible provider.
- [ ] Make file writes participate in record transaction rollback.
- [ ] Implement cleanup for abandoned staged files.
- [ ] Add provider-level tests.

Acceptance:

- [ ] Record create/update/delete rolls back file changes consistently.

#### P3-D02: File API Parity

- [ ] Implement real `filePath` for relational storage.
- [ ] Implement real protected file token flow.
- [ ] Apply `viewRule` to file reads.
- [ ] Complete range/cache/content-disposition/MIME/ETag behavior.
- [ ] Implement thumbnail cache and failure behavior.
- [ ] Add official SDK file helper fixtures.

Acceptance:

- [ ] File fixtures pass on local provider and S3-compatible provider.

#### P3-D03: Backup Provider Parity

- [ ] Implement real `backupFile` for relational storage.
- [ ] Implement backup list/create/upload/download/delete/restore for SQLite.
- [ ] Add MySQL/PostgreSQL backup strategy or document exact limitation.
- [ ] Implement S3-compatible backup storage.
- [ ] Validate backup restore before mutation.
- [ ] Add restore rollback or failure recovery guidance.
- [ ] Add scheduled backup loop tests.

Acceptance:

- [ ] Backup fixtures pass for create, upload, restore, delete, corrupt file, and missing file cases.

### Stream E: Rules, Realtime, Batch, And SQL

Goal: close behavior gaps that official SDK users hit quickly.

#### P3-E01: Rule Engine Parity

- [ ] Audit official filter grammar against `RuleEvaluator` and `FilterToSqlCompiler`.
- [ ] Support `@request.auth`, `@request.body`, `@request.query`, `@request.method`, headers where official exposes them, and `@collection.*`.
- [ ] Ensure rule behavior is consistent for records, files, realtime, auth, and protected endpoints.
- [ ] Add fixtures for null, empty string, arrays, relation fields, date comparisons, and logical precedence.

Acceptance:

- [ ] Rule fixtures pass on JSON and SQLite.
- [ ] Relational storage does not silently allow records that JSON rules reject.

#### P3-E02: Realtime Protocol

- [ ] Verify official subscribe/unsubscribe payloads and response shapes.
- [ ] Enforce auth refresh behavior on realtime connections.
- [ ] Apply collection rules consistently on event dispatch.
- [ ] Add reconnect, disconnect cleanup, and backpressure handling tests.
- [ ] Add JS SDK realtime smoke for subscribe, receive, unsubscribe, and reconnect.

Acceptance:

- [ ] Realtime tests assert actual event payloads, not only connection success.

#### P3-E03: Batch Semantics

- [ ] Verify official batch request and response shapes for every supported method.
- [ ] Enforce max requests, body size, timeout, and auth behavior.
- [ ] Support multipart/file paths where official behavior allows it.
- [ ] Keep rollback guarantees for records and files.
- [ ] Add negative fixtures for partial failure, auth failure, and validation failure.

Acceptance:

- [ ] A failed batch leaves no partial DB or file mutations.

#### P3-E04: SQL Endpoint

- [ ] Decide official-compatible SQL request shape and parameter behavior.
- [ ] Execute SQL through the active relational engine.
- [ ] Add allowlist/guardrails for unsafe multi-statements if official behavior differs by role.
- [ ] Normalize error envelopes and column/value types.
- [ ] Reuse SQL validation for view dry-run where possible.
- [ ] Add fixtures for SELECT, DDL, DML, multiple statements, invalid SQL, and auth errors.

Acceptance:

- [ ] SQL fixtures pass on SQLite.
- [ ] MySQL/PostgreSQL SQL behavior is either supported or explicitly disabled with official-shaped errors.

### Stream F: Admin UI Parity

Goal: make the UI feel operationally close to official PocketBase instead of only covering route demos.

#### P3-F01: Routing And Shell

- [ ] Implement official hash routes for login, collections, logs, settings sections, auth actions, and OAuth redirect success/failure.
- [ ] Support browser back/forward for major workflows.
- [ ] Add deep links for collection, record, settings section, and log detail.
- [ ] Keep layout dense and operational, matching official Admin UI patterns.
- [ ] Add Playwright smoke for major hash routes.

Acceptance:

- [ ] Reloading a deep link lands on the expected screen with correct selection.

#### P3-F02: Collection Editor

- [ ] Complete official field option editors for all field types.
- [ ] Add index editor UI.
- [ ] Add auth options editor parity.
- [ ] Add view SQL dry-run UI backed by backend dry-run.
- [ ] Add destructive schema change warnings.
- [ ] Add import dry-run review using backend migration plan.

Acceptance:

- [ ] Collection editor smoke covers add/edit/delete field, index, view dry-run, import dry-run, and save error display.

#### P3-F03: Record Editor

- [ ] Add relation picker instead of raw ID-only input.
- [ ] Add file picker, preview, replace, clear, and thumbnail preview.
- [ ] Add JSON/editor validation display.
- [ ] Add duplicate, preview, and impersonate workflows where official UI has them.
- [ ] Add official-style search/filter/sort controls.
- [ ] Add field projection and expand inspection where useful.

Acceptance:

- [ ] Record editor smoke covers text/number/bool/select/json/file/relation fields and validation errors.

#### P3-F04: Settings, Logs, Backups, OAuth UI

- [ ] Complete official settings sections and defaults.
- [ ] Add mail template editor.
- [ ] Add storage provider status and validation details.
- [ ] Add backup restore confirmation and S3 backup state.
- [ ] Add logs detail viewer and stats bucket parity.
- [ ] Complete OAuth provider-specific forms and tester states.

Acceptance:

- [ ] UI smoke covers every settings section without relying on raw JSON fallback.

#### P3-F05: Visual QA

- [ ] Add Playwright screenshots for desktop and mobile breakpoints.
- [ ] Verify no overlapping text/buttons in collection editor, record editor, settings, logs, and auth pages.
- [ ] Verify Admin UI bundle generated under `src/main/resources/pocketbase-admin` is current after UI changes.
- [ ] Add a CI check that fails on stale UI build artifacts.

Acceptance:

- [ ] UI build and smoke can run from a clean checkout.

### Stream G: Native Image And Release Readiness

Goal: keep the project useful as a GraalVM native binary while adding real storage/providers.

#### P3-G01: Native Runtime Validation

- [ ] Run native build after jOOQ, SQLite, MySQL/PostgreSQL driver, S3, mail, and image thumbnail changes.
- [ ] Update reflect/resource/native-image configs only with necessary entries.
- [ ] Add native smoke for health, bootstrap, auth, collection CRUD, file URL, and Admin UI static assets.
- [ ] Keep MySQL/PostgreSQL optional in native mode unless explicitly selected.

Acceptance:

- [ ] Native smoke passes on SQLite.
- [ ] Optional drivers do not break default native binary startup.

#### P3-G02: Configuration And CLI

- [ ] Document storage selection: `json`, `sqlite`, `mysql`, `postgres`.
- [ ] Document DSN/user/password environment variables.
- [ ] Add startup validation errors that are useful and non-secret.
- [ ] Add config redaction for logs and settings responses.
- [ ] Document migration and backup behavior.

Acceptance:

- [ ] README and docs show one working command for each supported runtime mode.

#### P3-G03: Release Gates

- [ ] Add required gate: JVM default tests.
- [ ] Add required gate: SQLite tests.
- [ ] Add optional-to-required gate: MySQL tests.
- [ ] Add optional-to-required gate: PostgreSQL tests.
- [ ] Add required gate: UI build and static resource freshness.
- [ ] Add required gate: native build and smoke for release branches.

Acceptance:

- [ ] Release branch cannot be cut when default JVM, SQLite, UI, or native gates fail.

## 6. Definition Of Done

A Phase 3 task is done only when:

- [ ] The implementation matches official route/method/request/response/error shape or documents a deliberate limitation.
- [ ] The behavior is covered by a fixture or smoke test.
- [ ] The feature works on SQLite if it is part of the default runtime.
- [ ] MySQL/PostgreSQL behavior is tested, skipped only for unavailable infrastructure, or explicitly marked unsupported.
- [ ] Admin UI uses the real backend contract when the feature has a UI surface.
- [ ] No dummy token, no-op success response, or `UnsupportedOperationException` remains on reachable official API paths.
- [ ] Native image implications are checked for new dependencies or reflection/resource needs.

## 7. Verification Commands

Default JVM gate:

```bash
mvn -gs settings.xml -s settings.xml test
```

SQLite gate:

```bash
mvn -gs settings.xml -s settings.xml -Dstorage=sqlite test
```

Targeted SQLite smoke while the full suite is still being stabilized:

```bash
mvn -gs settings.xml -s settings.xml -Dstorage=sqlite -Dtest=BehaviorFixturesTest,AdminUiSmokeTest test
```

UI build gate:

```bash
cd UI && npm ci && npm run build
```

MySQL external DSN gate:

```bash
mvn -gs settings.xml -s settings.xml -Dstorage=mysql -Ddb.url="$PB_MYSQL_TEST_URL" -Ddb.user="$PB_MYSQL_TEST_USER" -Ddb.password="$PB_MYSQL_TEST_PASSWORD" test
```

PostgreSQL external DSN gate:

```bash
mvn -gs settings.xml -s settings.xml -Dstorage=postgres -Ddb.url="$PB_POSTGRES_TEST_URL" -Ddb.user="$PB_POSTGRES_TEST_USER" -Ddb.password="$PB_POSTGRES_TEST_PASSWORD" test
```

Native gate:

```bash
mvn -gs settings.xml -s settings.xml -Pnative -DskipTests package
```

Diff hygiene:

```bash
git diff --check
```

## 8. Suggested Execution Order

1. [ ] Commit current jOOQ/filter hardening once reviewed.
2. [ ] Make SQLite relational storage non-stub for settings/logs/crons/backups/file token/OTP/upsert.
3. [ ] Split relational engine naming and repositories.
4. [ ] Add MySQL/PostgreSQL fixture gates with external DSN or Testcontainers.
5. [ ] Complete field type storage mapping and constraint error normalization.
6. [ ] Complete auth action persistence, OTP, MFA, and mocked OAuth2 provider flow.
7. [ ] Complete local/S3 file provider and backup provider.
8. [ ] Upgrade Admin UI hash routing, collection editor, record editor, settings, and OAuth tester.
9. [ ] Add native image validation after each new dependency/provider.
10. [ ] Refresh official PocketBase baseline and regenerate route/behavior manifests.

