# SDP: PocketBase Java Compatibility Plan

Updated: 2026-06-17

Official baseline: `pocketbase/pocketbase` `master` at `507ecb264b6155ad3677d661115ff277a48a5cdd`.

Local baseline: `main` with the current OAuth2 backend flow, OAuth2 Admin UI tester, OTP/auth settings, structured settings/logs/crons/sql/backups/import-export/files/realtime work included.

## 1. Goal

Build a Java implementation that can run as a GraalVM native binary while staying API-compatible with official PocketBase. The hard rule is that API paths, HTTP methods, request parameters, response shapes, and error envelopes must match official PocketBase closely enough that the official SDKs can be reused without client-side forks.

The Admin UI should track the official UI workflows where it matters for operators: collection/schema management, records CRUD, auth configuration, logs, settings, backups, crons, SQL, and OAuth2/MFA testing.

## 2. Current Snapshot

| Track | Current status | Rough parity | Notes |
| --- | --- | ---: | --- |
| Backend route surface | Most official `/api` routes are present | 95% | Missing known official route: `POST /api/collections/meta/dry-run-view`. Extra compatibility routes exist, such as `/api/admins/auth-with-password` and `/api/bootstrap/superuser`. |
| Backend behavior | Useful local implementation exists, but many semantics are simplified | 50-60% | JSON-file store, simplified query/filter/rule logic, partial auth/MFA semantics, simplified logs/settings/backups. |
| Admin UI surface | Single React app covers login, records, schema, structured settings, mail/storage tests, backups, logs, crons, SQL, import/export, and OAuth2 tester | 55-65% | The main pages now exist, but many official details remain simplified: field-specific editors, deep hash routes, auth action pages, provider-specific OAuth2 forms, logs chart details, and exact import dry-run semantics. |
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
| `#/settings` | Present as structured application settings with advanced JSON fallback |
| `#/settings/mail` | Present with SMTP/auth fields and test email flow |
| `#/settings/storage` | Present with file storage S3 settings and S3 test flow |
| `#/settings/backups` | Present with backup operations, backup settings, S3 target test, and cron keep policy editor |
| `#/settings/crons` | Present with official-style list/run layout, simplified status metadata |
| `#/settings/export-collections` | Present with collection selection, JSON preview, copy, and download |
| `#/settings/import-collections` | Present with JSON/file input, delete-missing option, local diff review modal, and backend import action |
| `#/settings/sql` | Present with SQL editor, result table, and error display |

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
| Settings | GET/PATCH/test S3/test email/Apple secret plus structured Admin UI pages | Structured validation, redaction parity, exact mail/storage/backups/application defaults, rate-limit settings enforcement | P1 |
| Logs | Activity logging, list/view/stats | Official stats bucket behavior, full filter support, request metadata parity, log retention scheduler | P2 |
| Backups | Local zip create/upload/download/delete/restore plus Admin UI settings controls | S3 backups, restore safety parity, scheduled backups tested end-to-end | P1 |
| Crons | Built-in IDs listed and manually runnable with Admin UI page | Real scheduler loop, exact cron parser behavior, status/last run metadata if needed by UI | P2 |
| SQL | Lightweight SQL endpoint and Admin UI console | Official SQLite semantics, params, explain errors, safe statement handling, view query dry-run reuse | P1 |
| Middleware/security | CORS, OPTIONS, body reads, activity log | Official rate limit, body limit, security headers, trusted proxy, superuser IP whitelist, panic recovery shape | P0 |
| Persistence engine | JSON-file store with backups | Official parity ultimately needs SQLite-style constraints/indexes/query planner; JSON store should become test/dev backend or compatibility layer only | P0 |

## 5. Admin UI Gap Map

| UI area | Done now | Main gaps | Priority |
| --- | --- | --- | --- |
| Shell/navigation | Sidebar, collection list, records/schema/settings/logs/crons/backups/import/export/sql views | Hash routes, deep links, record modal URLs, install/reset flows | P1 |
| Login/auth pages | Superuser login panel | Request password reset, confirm reset/verification/email-change pages, installer page | P1 |
| Collections | Create/edit/delete collection modal, schema JSON, auth options, OAuth2 config/tester | Official tabs: Fields, Rules, Auth options, View query; field-specific editors; indexes; change confirmation | P0 |
| Field editors | JSON schema editing and basic record form | Dedicated editors for text/email/url/number/bool/date/autodate/select/json/editor/file/relation/password/geoPoint | P0 |
| Records | List, create/update/delete, simple editor, relation expand basics | Searchbar parity, file picker/thumbs, relation picker, preview/duplicate, impersonate modal, field-specific renderers | P1 |
| OAuth2 tester | Popup/login tester with callback postMessage and result modal | Provider-specific forms matching official `collections/oauth2/*Options.js`, failure/success pages, persisted provider health indicators | P1 |
| Logs | List, details, stats-backed chart strip, and search controls | Official stats bucket parity, filter presets, request data viewer parity | P2 |
| Settings application | Structured app URL/meta/batch/rate-limit/trusted-proxy/superuser sections plus advanced JSON fallback | Exact official validation/defaults and complete rate-limit enforcement | P1 |
| Settings mail | Structured SMTP/mail settings form and test email action | Official templates, delivery status details, and validation copy parity | P1 |
| Settings storage | File storage S3 form, backup S3 form, and S3 test action | Real S3 file/backups implementation and exact validation status parity | P1 |
| Backups | Create/upload/download/restore/delete plus settings and cron keep controls | Restore confirmation details, real S3 backup execution, scheduled backup coverage | P1 |
| Crons | Official-style list/run page | Real scheduler loop, exact status/last-run metadata, cron parser parity | P2 |
| Import/export | Export selection/JSON/copy/download and import JSON/file diff review modal | Official dry-run integration, exact schema diff semantics, destructive change warnings | P0 |
| SQL console | SQL editor, result table, and error panel | Official SQLite response/error parity, params, keyboard submit, view dry-run reuse | P1 |

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
   - Replace local collection import/export diffing with official-compatible dry-run/review semantics.

3. Harden settings backend and pages.
   - Align structured application settings validation and default values with official behavior.
   - Align mail test, storage S3 test, and backup S3 test response shapes.
   - Exercise backup cron/max keep settings end-to-end.

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

### P0
- [x] **SDP-001**: Route manifest conformance tests (Backend)
- [ ] **SDP-002**: Official JS SDK smoke suite (Backend/SDK)
- [x] **SDP-003**: `POST /api/collections/meta/dry-run-view` (Backend)
- [x] **SDP-004**: `_mfas` internal collection and MFA auth response (Backend/Auth)
- [x] **SDP-005**: MFA second-factor flow for password/OTP/OAuth2 (Backend/Auth)
- [ ] **SDP-006**: Rule/filter parser parity (Backend/Core)
- [ ] **SDP-007**: Storage engine ADR and native proof (Backend/Core)
- [ ] **SDP-008**: Field type validation matrix (Backend/Collections)
- [ ] **SDP-009**: Collection indexes and schema migration semantics (Backend/Collections)
- [/] **SDP-010**: Official collection import/export review flow (Backend/UI)
- [ ] **SDP-011**: Field-specific collection editor UI (UI)

### P1
- [ ] **SDP-012**: Field-specific record editor UI (UI)
- [ ] **SDP-013**: OAuth2 provider-specific config parity (Backend/UI)
- [/] **SDP-014**: Structured application settings page (UI/Backend)
- [/] **SDP-015**: Mail settings page and test modal (UI/Backend)
- [/] **SDP-016**: Storage settings page and S3 tests (UI/Backend)
- [/] **SDP-017**: Backup settings page and S3 backups (UI/Backend)
- [ ] **SDP-018**: Realtime SDK compatibility smoke (Backend/SDK)
- [ ] **SDP-019**: Batch service SDK and multipart rollback tests (Backend/SDK)
- [x] **SDP-020**: SQL console UI (UI)

### P2
- [/] **SDP-021**: Logs chart/stats parity (UI/Backend)
- [ ] **SDP-022**: File range/cache/S3 parity (Backend)
- [ ] **SDP-023**: Installer and auth action pages (UI)
- [ ] **SDP-024**: Native CI release gate (Build)

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
