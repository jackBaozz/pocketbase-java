# SDP: PocketBase Java Compatibility Plan

Updated: 2026-06-15

Official baseline: `pocketbase/pocketbase` `master` at `507ecb264b6155ad3677d661115ff277a48a5cdd`.

Local baseline: `main` with the current OAuth2 backend flow, OAuth2 Admin UI tester, OTP/auth settings, settings/logs/crons/sql/backups/files/realtime work included.

## 1. Goal

Build a Java implementation that can run as a GraalVM native binary while staying API-compatible with official PocketBase. The hard rule is that API paths, HTTP methods, request parameters, response shapes, and error envelopes must match official PocketBase closely enough that the official SDKs can be reused without client-side forks.

The Admin UI should track the official UI workflows where it matters for operators: collection/schema management, records CRUD, auth configuration, logs, settings, backups, crons, SQL, and OAuth2/MFA testing.

## 2. Current Snapshot

| Track | Current status | Rough parity | Notes |
| --- | --- | ---: | --- |
| Backend route surface | Most official `/api` routes are present | 95% | Missing known official route: `POST /api/collections/meta/dry-run-view`. Extra compatibility routes exist, such as `/api/admins/auth-with-password` and `/api/bootstrap/superuser`. |
| Backend behavior | Useful local implementation exists, but many semantics are simplified | 50-60% | JSON-file store, simplified query/filter/rule logic, partial auth/MFA semantics, simplified logs/settings/backups. |
| Admin UI surface | Single React app covers login, records, schema, backups, settings JSON, logs, crons, OAuth2 tester | 30-40% | Official UI has separate full pages for collections, fields, records, logs, application/mail/storage/backups/crons/import/export/sql settings. |
| Java SDK facade | Covers core collection/auth/file/settings/logs/crons/sql APIs | 45-55% | Needs full official route coverage, typed auth helpers, realtime client, batch client, backup client, and SDK compatibility smoke tests. |
| Native packaging | GraalVM native image path already works for current feature set | 70% | Reflection config is maintained, but future storage/provider choices must be native-image validated before adoption. |

These percentages are planning estimates, not a formal conformance score. The first SDP milestone below adds contract tests so this becomes measurable.

## 3. Official Surface Used For Comparison

Official API groups from `apis/base.go`:

| Official group | Routes |
| --- | --- |
| Settings | `GET/PATCH /api/settings`, `POST /api/settings/test/s3`, `POST /api/settings/test/email`, `POST /api/settings/apple/generate-client-secret` |
| Collections | `GET/POST /api/collections`, `GET/PATCH/DELETE /api/collections/{collection}`, `DELETE /api/collections/{collection}/truncate`, `PUT /api/collections/import`, `GET /api/collections/meta/scaffolds`, `GET /api/collections/meta/oauth2-providers`, `POST /api/collections/meta/dry-run-view` |
| Records | `GET/POST /api/collections/{collection}/records`, `GET/PATCH/DELETE /api/collections/{collection}/records/{id}` |
| Auth | `GET/POST /api/oauth2-redirect`, `GET /api/collections/{collection}/auth-methods`, `POST /api/collections/{collection}/auth-refresh`, `POST /api/collections/{collection}/auth-with-password`, `POST /api/collections/{collection}/auth-with-oauth2`, `POST /api/collections/{collection}/request-otp`, `POST /api/collections/{collection}/auth-with-otp`, `POST /api/collections/{collection}/request-password-reset`, `POST /api/collections/{collection}/confirm-password-reset`, `POST /api/collections/{collection}/request-verification`, `POST /api/collections/{collection}/confirm-verification`, `POST /api/collections/{collection}/request-email-change`, `POST /api/collections/{collection}/confirm-email-change`, `POST /api/collections/{collection}/impersonate/{id}` |
| Logs | `GET /api/logs`, `GET /api/logs/stats`, `GET /api/logs/{id}` |
| Backups | `GET/POST /api/backups`, `POST /api/backups/upload`, `GET/DELETE /api/backups/{key}`, `POST /api/backups/{key}/restore` |
| Crons | `GET /api/crons`, `POST /api/crons/{id}` |
| Files | `POST /api/files/token`, `GET /api/files/{collection}/{recordId}/{filename}` |
| Batch | `POST /api/batch` |
| Realtime | `GET/POST /api/realtime` |
| Health | `GET /api/health` |
| SQL | `POST /api/sql` |

Official Admin UI routes from `ui/src/router.js`:

| Official UI route | Local status |
| --- | --- |
| `#/login` | Present as local login panel |
| `#/request-password-reset` | Missing |
| `#/auth/confirm-password-reset/{token}` | Missing |
| `#/auth/confirm-verification/{token}` | Missing |
| `#/auth/confirm-email-change/{token}` | Missing |
| `#/auth/oauth2-redirect-success` / `failure` | Implemented differently via `/api/oauth2-redirect` popup postMessage |
| `#/collections` | Present, but simplified |
| `#/logs` | Present, but simplified |
| `#/settings` | Present as JSON editor, not official structured page |
| `#/settings/mail` | Missing structured page |
| `#/settings/storage` | Missing structured page |
| `#/settings/backups` | Backup operations present, settings form incomplete |
| `#/settings/crons` | Cron list/run present, page simplified |
| `#/settings/export-collections` | Missing |
| `#/settings/import-collections` | Backend import exists, UI missing review flow |
| `#/settings/sql` | Backend SQL exists, UI missing SQL console |

## 4. Backend Gap Map

| Area | Done now | Main gaps | Priority |
| --- | --- | --- | --- |
| Route compatibility | Nearly all official `/api` route paths are registered | Add `POST /api/collections/meta/dry-run-view`; remove or quarantine non-official routes from SDK-facing docs; generate route conformance tests | P0 |
| Error envelope | Uses `{code,status,message,data}` | Field-level validation messages and status texts differ from official in many paths | P0 |
| Query/filter/sort/expand | Basic `filter`, `sort`, `fields`, `expand` support | Official filter grammar, relation traversal, auth context variables, date/math functions, query validation, pagination edge cases | P0 |
| Record CRUD | CRUD, multipart files, relation expand, hidden field handling, realtime publish | Full field operations, relation constraints, view collections, protected file edge cases, auth-origin tests, system collection rules | P0 |
| Collection model | Base/auth/view concepts, auth config, scaffolds/import | Official field type validation, indexes, system flags, view query validation, dry-run-view, migration-style schema changes | P0 |
| Auth password/refresh | Password auth and refresh implemented | Constant-time/dummy password checks, exact validation envelopes, token duration config, login alerts | P1 |
| OTP | Request/auth with OTP, expiry/failure tracking, outbox/SMTP | Exact rate-limit text, OTP cleanup semantics, superuser OTP+MFA behavior | P1 |
| OAuth2 | Generic auth URL, PKCE, token exchange, userinfo/id token, external auth link, Admin UI tester | Provider-specific config behavior, deprecated `redirectUrl`, Apple name forwarding, exact meta shape, provider SDK parity tests, OAuth2 failure pages | P1 |
| MFA | Config exists and auth-methods reports it | Real `_mfas` records, `mfaId` response flow, MFA rule evaluation, using OTP/password/oauth2 as second factor, cleanup cron | P0 |
| Auth emails | Request/confirm password reset, verification, email change endpoints exist | Official templates, URL generation, token delivery, typed outbox inspection, exact expiration/duration config | P1 |
| Realtime | SSE connect and subscription POST exist | Official subscription protocol edge cases, auth refresh behavior, access rule re-checks, disconnect cleanup, client SDK compatibility | P1 |
| Batch | Record batch transaction support | Official request/response details, full multipart mapping, max request/body timeout settings, rollback tests across files | P1 |
| Files | Token, download, thumbs, protected file basics | Range/cache headers, exact thumb behavior, S3 storage, download params, filename/content-disposition parity | P1 |
| Settings | GET/PATCH/test S3/test email/Apple secret | Structured validation, redaction parity, mail/storage/backups/application settings defaults, rate-limit settings enforcement | P1 |
| Logs | Activity logging, list/view/stats | Official stats bucket behavior, full filter support, request metadata parity, log retention scheduler | P2 |
| Backups | Local zip create/upload/download/delete/restore | S3 backups, restore safety parity, import/export diff UI support, scheduled backups tested end-to-end | P1 |
| Crons | Built-in IDs listed and manually runnable | Real scheduler loop, exact cron parser behavior, status/last run metadata if needed by UI | P2 |
| SQL | Lightweight SQL console endpoint | Official SQLite semantics, params, explain errors, safe statement handling, view query dry-run reuse | P1 |
| Middleware/security | CORS, OPTIONS, body reads, activity log | Official rate limit, body limit, security headers, trusted proxy, superuser IP whitelist, panic recovery shape | P0 |
| Persistence engine | JSON-file store with backups | Official parity ultimately needs SQLite-style constraints/indexes/query planner; JSON store should become test/dev backend or compatibility layer only | P0 |

## 5. Admin UI Gap Map

| UI area | Done now | Main gaps | Priority |
| --- | --- | --- | --- |
| Shell/navigation | Sidebar, collection list, records/schema/settings/logs/crons/backups views | Hash routes, official settings sidebar, deep links, record modal URLs, install/reset flows | P1 |
| Login/auth pages | Superuser login panel | Request password reset, confirm reset/verification/email-change pages, installer page | P1 |
| Collections | Create/edit/delete collection modal, schema JSON, auth options, OAuth2 config/tester | Official tabs: Fields, Rules, Auth options, View query; field-specific editors; indexes; change confirmation | P0 |
| Field editors | JSON schema editing and basic record form | Dedicated editors for text/email/url/number/bool/date/autodate/select/json/editor/file/relation/password/geoPoint | P0 |
| Records | List, create/update/delete, simple editor, relation expand basics | Searchbar parity, file picker/thumbs, relation picker, preview/duplicate, impersonate modal, field-specific renderers | P1 |
| OAuth2 tester | Popup/login tester with callback postMessage and result modal | Provider-specific forms matching official `collections/oauth2/*Options.js`, failure/success pages, persisted provider health indicators | P1 |
| Logs | List and details enough for troubleshooting | Chart/stats visualization, filter presets, request data viewer parity | P2 |
| Settings application | Raw JSON editor | Structured app URL/meta/batch/rate-limit/trusted-proxy/superuser sections | P1 |
| Settings mail | Backend test email exists | Structured SMTP/mail settings form and test modal | P1 |
| Settings storage | Backend S3 test exists | Local/S3 file storage form, backups S3 form, validation status | P1 |
| Backups | Create/upload/download/restore/delete | Official backup settings page, restore confirmation details, S3 target, cron keep policy editor | P1 |
| Crons | List/run | Official crons page styling and status details | P2 |
| Import/export | Backend import exists | Export collections page, import diff/review modal, dry-run integration | P0 |
| SQL console | Backend exists | SQL editor, result table, error panel, keyboard submit | P1 |

## 6. Development Plan

### P0: Contract And Core Semantics

1. Build an official-route conformance test suite.
   - Generate a static route manifest from the official route list above.
   - Assert every official route returns non-404 for correct method and correct auth class.
   - Assert unknown methods return official-style 404/405 behavior.
   - Add SDK smoke tests using the official JS SDK against the Java server.

2. Add `POST /api/collections/meta/dry-run-view`.
   - Reuse the SQL/view validation path.
   - Return official-compatible rows/columns or validation errors.
   - Wire it to the future import/view UI.

3. Implement real MFA flow.
   - Add `_mfas` internal collection persistence.
   - Return `mfaId` instead of auth token when collection MFA rules require it.
   - Accept `mfaId` on second-factor auth requests and delete successful/expired MFA sessions.
   - Implement `__pbMFACleanup__` behavior.
   - Cover password, OTP, and OAuth2 auth methods.

4. Harden rules and query evaluation.
   - Replace the current simple rule evaluator with an official-compatible parser/evaluator.
   - Cover `@request.auth`, `@request.method`, relation traversal, null/empty handling, date comparisons, and list operators.
   - Use the same evaluator for collection rules, MFA rules, protected files, and filter queries where applicable.

5. Decide and prototype the storage engine path.
   - Required decision: keep JSON store as compatibility target or introduce SQLite-backed storage for official parity.
   - Acceptance: native-image proof with CRUD, indexes, unique constraints, SQL endpoint, and backups.
   - Do not land a storage dependency before a GraalVM native smoke test passes.

### P1: Official Workflow Parity

1. Finish OAuth2 parity.
   - Add deprecated `redirectUrl` alias.
   - Preserve Apple first-redirect name data.
   - Add provider-specific validation/config for OIDC, self-hosted, Apple, Microsoft, Lark, and common official providers.
   - Add failure tests for invalid provider, bad token response, bad userinfo response, existing external auth, existing email, logged-in link flow.

2. Complete collection/schema management.
   - Implement field-specific validation for all official field types.
   - Add index definitions and validation.
   - Add view collection query editing and dry-run integration.
   - Add collection import/export review semantics.

3. Complete settings backend and pages.
   - Structured application settings: meta, app URL, batch, rate limits, trusted proxy, superuser options.
   - Mail settings with test modal.
   - Storage settings with file S3 and backup S3 tests.
   - Backup settings with cron/max keep.

4. Complete record editor parity.
   - Add field-specific record inputs and table renderers.
   - Add relation picker, file picker, thumb preview, duplicate record, record preview, and auth impersonate modal.
   - Add official list filters/search/sort UX.

5. Complete realtime and batch client compatibility.
   - Add Java SDK realtime client and batch service.
   - Run official JS SDK realtime subscribe/unsubscribe smoke.
   - Add rollback tests for multipart batch requests.

### P2: Operational Polish And Compatibility Depth

1. Improve logs and observability.
   - Add official stats bucketing and chart-ready output.
   - Add request metadata redaction parity.
   - Add retention cleanup cron coverage.

2. Improve files and storage behavior.
   - Add cache/range/download header parity.
   - Add S3 storage implementation and backup S3 implementation.
   - Add thumbnail failure parity and protected-file regression tests.

3. Improve SQL console.
   - Add UI SQL editor/result table.
   - Align SQL response columns/rows/errors with official behavior.
   - Reuse SQL validation for view dry-run.

4. Add installer and auth action pages.
   - `#/pbinstall/{token}`
   - `#/request-password-reset`
   - `#/auth/confirm-password-reset/{token}`
   - `#/auth/confirm-verification/{token}`
   - `#/auth/confirm-email-change/{token}`

5. Harden native binary release.
   - Add CI profile for JVM tests, UI build, native build, native smoke.
   - Audit reflection/resources after each new backend feature.
   - Document native runtime limits and supported storage providers.

## 7. Tracking Checklist

Use this checklist as the project board seed.

| ID | Item | Area | Priority | Status |
| --- | --- | --- | --- | --- |
| SDP-001 | Route manifest conformance tests | Backend | P0 | Todo |
| SDP-002 | Official JS SDK smoke suite | Backend/SDK | P0 | Todo |
| SDP-003 | `POST /api/collections/meta/dry-run-view` | Backend | P0 | Todo |
| SDP-004 | `_mfas` internal collection and MFA auth response | Backend/Auth | P0 | Todo |
| SDP-005 | MFA second-factor flow for password/OTP/OAuth2 | Backend/Auth | P0 | Todo |
| SDP-006 | Rule/filter parser parity | Backend/Core | P0 | Todo |
| SDP-007 | Storage engine ADR and native proof | Backend/Core | P0 | Todo |
| SDP-008 | Field type validation matrix | Backend/Collections | P0 | Todo |
| SDP-009 | Collection indexes and schema migration semantics | Backend/Collections | P0 | Todo |
| SDP-010 | Official collection import/export review flow | Backend/UI | P0 | Todo |
| SDP-011 | Field-specific collection editor UI | UI | P0 | Todo |
| SDP-012 | Field-specific record editor UI | UI | P1 | Todo |
| SDP-013 | OAuth2 provider-specific config parity | Backend/UI | P1 | Todo |
| SDP-014 | Structured application settings page | UI/Backend | P1 | Todo |
| SDP-015 | Mail settings page and test modal | UI/Backend | P1 | Todo |
| SDP-016 | Storage settings page and S3 tests | UI/Backend | P1 | Todo |
| SDP-017 | Backup settings page and S3 backups | UI/Backend | P1 | Todo |
| SDP-018 | Realtime SDK compatibility smoke | Backend/SDK | P1 | Todo |
| SDP-019 | Batch service SDK and multipart rollback tests | Backend/SDK | P1 | Todo |
| SDP-020 | SQL console UI | UI | P1 | Todo |
| SDP-021 | Logs chart/stats parity | UI/Backend | P2 | Todo |
| SDP-022 | File range/cache/S3 parity | Backend | P2 | Todo |
| SDP-023 | Installer and auth action pages | UI | P2 | Todo |
| SDP-024 | Native CI release gate | Build | P2 | Todo |

## 8. Acceptance Gates

Every feature should pass the relevant subset of these commands before commit:

```bash
mvn test
```

```bash
mvn package
```

```bash
cd UI
npm run build
```

```bash
JAVA_HOME=<graalvm-home> PATH=<graalvm-home>/bin:$PATH mvn -Pnative -DskipTests package
```

For UI work, also run a browser smoke against the embedded Admin UI and verify the changed page is visible and usable.

## 9. Risks

1. JSON-file storage will keep blocking exact PocketBase behavior for indexes, unique constraints, SQL, view collections, and complex filters.
2. GraalVM native image compatibility can be broken by storage, OAuth2, mail, image, or S3 libraries unless each is tested before adoption.
3. Admin UI can drift from backend behavior if pages are added before backend contracts are locked. Backend contract tests should lead UI expansion.
4. OAuth2 provider behavior is full of provider-specific exceptions. Generic OIDC support is useful, but it is not the same as official provider parity.
5. Official SDK compatibility must be tested directly. A Java client passing its own tests is not enough.

## 10. Next Recommended Work

Start with `SDP-001`, `SDP-003`, and `SDP-004`.

Reason: the route manifest will stop accidental API drift, `dry-run-view` closes the only obvious official route hole, and real MFA is the largest auth gap left after OTP and OAuth2.
