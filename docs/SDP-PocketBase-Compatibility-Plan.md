# SDP: PocketBase Java Compatibility Plan

Updated: 2026-06-21

Official baseline: `pocketbase/pocketbase` `master` at `507ecb264b6155ad3677d661115ff277a48a5cdd`.

Local baseline: `main` after the OAuth2 backend flow, OAuth2 Admin UI tester, OTP/auth settings, structured settings/logs/crons/sql/backups/import-export/files/realtime work, auth action pages, native CI gate, and file range handling changes.

## 1. Goal

Build a Java implementation that can run as a GraalVM native binary while staying API-compatible with official PocketBase. The hard rule is that API paths, HTTP methods, request parameters, response shapes, and error envelopes must match official PocketBase closely enough that the official SDKs can be reused without client-side forks.

The Admin UI should track the official UI workflows where it matters for operators: collection/schema management, records CRUD, auth configuration, logs, settings, backups, crons, SQL, OAuth2/MFA testing, installer flows, and auth action pages.

## 2. Audit Summary

This document was re-audited against the current repository on 2026-06-21. The previous version mixed "basic implementation exists" with "official parity complete"; that made the checklist too optimistic. The corrected status model below uses:

- **Verified**: implemented and covered by a direct test or build gate in this repository.
- **Partial**: useful implementation exists, but official parity is incomplete, weakly tested, or only smoke-tested.
- **Missing**: no meaningful local implementation found.

Key corrections:

- `POST /api/collections/meta/dry-run-view` is no longer missing. It is in the route manifest and backend dispatch path.
- Auth action pages are no longer missing. `UI/src/AuthActionPages.tsx` implements installer/reset/verification/email-change forms and `UI/src/App.tsx` routes those hash paths.
- File Range support is no longer absent, but full file/S3 parity is still **Partial** because S3 storage itself is not implemented as a real file backend.
- MFA was previously overclaimed. The current runtime has `mfaId` and in-memory `mfas`, but not a persisted official `_mfas` collection or direct MFA regression tests.
- Field editors, record editors, OAuth2, logs stats, import review, and native CI are useful but not official-complete.

## 3. Current Snapshot

| Track | Current status | Rough parity | Notes |
| --- | --- | ---: | --- |
| Backend route surface | Official route manifest exists and no known official route path hole is listed in this document | 97-98% | `RouteConformanceTest` proves dispatch coverage, not exact behavior. Extra compatibility routes remain, such as `/api/admins/auth-with-password` and `/api/bootstrap/superuser`. |
| Backend behavior | Useful Java runtime exists, but many semantics are simplified | 55-65% | JSON-file store, simplified filter/rule/query engine, in-memory MFA sessions, generic OAuth2, and partial logs/settings/backups/files semantics. |
| Admin UI surface | Single React app covers login, records, schema, settings, mail/storage tests, backups, logs, crons, SQL, import/export, OAuth2 tester, installer, and auth action pages | 65-70% | Main workflows exist, but official details remain simplified: field options, provider-specific forms, hash deep links, record modal parity, dry-run import semantics, and logs details. |
| Java SDK facade | Covers core collection/auth/file/settings/logs/crons/sql plus batch/realtime basics | 60-65% | Needs full typed official helper coverage, backup client, stricter SDK smoke tests, and broader realtime/batch behavior coverage. |
| Native packaging | CI contains JVM tests, UI build, native build, and native smoke job | 70-75% | Gate exists, but native compatibility must be revalidated after storage/provider changes. |

These percentages are planning estimates, not a formal conformance score. Treat direct tests and runtime checks as authoritative.

## 4. Official Surface Used For Comparison

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
| `#/login` | Present as local login panel, not a first-class hash route |
| `#/request-password-reset` | Present via `AuthActionPages` |
| `#/auth/confirm-password-reset/{token}` | Present via `AuthActionPages` |
| `#/auth/confirm-verification/{token}` | Present via `AuthActionPages` |
| `#/auth/confirm-email-change/{token}` | Present via `AuthActionPages` |
| `#/auth/oauth2-redirect-success` / `failure` | Not implemented as official hash routes; local flow uses `/api/oauth2-redirect` popup `postMessage` |
| `#/collections` | Present as stateful collection/records UI, but hash deep links are incomplete |
| `#/logs` | Present, but simplified |
| `#/settings` | Present as structured application settings with advanced JSON fallback |
| `#/settings/mail` | Present with SMTP/auth fields and test email flow |
| `#/settings/storage` | Present with file storage S3 settings and S3 test flow |
| `#/settings/backups` | Present with backup operations, backup settings, S3 target test, and cron keep policy editor |
| `#/settings/crons` | Present with official-style list/run layout, simplified status metadata |
| `#/settings/export-collections` | Present with collection selection, JSON preview, copy, and download |
| `#/settings/import-collections` | Present with JSON/file input, delete-missing option, local diff review modal, and backend import action |
| `#/settings/sql` | Present with SQL editor, result table, and error display |

## 5. Backend Gap Map

| Area | Done now | Remaining gaps | Priority |
| --- | --- | --- | --- |
| Route compatibility | Static official route manifest and dry-run-view route exist | Replace static list with generated/official manifest sync; validate auth class, request body, response shape, and official 404/405 semantics | P0 |
| Error envelope | Uses `{code,status,message,data}` in many API paths | Field-level validation text, status names, and nested error data differ from official in many routes | P0 |
| Query/filter/sort/expand | Basic `filter`, `sort`, `fields`, `expand`, relation expand, and rule checks exist | Full official filter grammar, relation traversal depth, auth variables, date/math functions, null/empty semantics, and pagination edge cases | P0 |
| Record CRUD | CRUD, multipart files, relation expand, hidden/protected fields basics, realtime publish | Full field operations, relation constraints, view collection behavior, system collection rules, and auth-origin edge cases | P0 |
| Collection model | Base/auth/view concepts, auth config, scaffolds/import, indexes storage, dry-run-view route | Exact field schema model, index validation/enforcement, system flags, migration semantics, and official dry-run/import response shapes | P0 |
| Auth password/refresh | Password auth, auth refresh, query fields/expand, impersonation basics | Constant-time/dummy password checks, exact validation envelopes, duration config, login alerts, and token edge cases | P1 |
| OTP | Request/auth with OTP, expiry/failure tracking, outbox/SMTP send path | Exact rate-limit text, cleanup persistence semantics, superuser OTP+MFA behavior, and direct SDK parity tests | P1 |
| OAuth2 | Generic OAuth2/OIDC code exchange, PKCE metadata, external auth link, Admin UI tester | Full provider-specific behavior, deprecated `redirectUrl`, Apple name forwarding, exact meta shape, avatar download, link-conflict semantics, failure/success hash pages | P1 |
| MFA | `mfaId` auth response path and in-memory MFA session list exist | Persist official `_mfas` internal collection, add direct tests for password/OTP/OAuth2 second factor, cleanup persistence, and exact response shape | P0 |
| Auth emails | Request/confirm password reset, verification, email change endpoints exist | Official templates, URL generation, delivery configuration, typed outbox inspection, exact expiration/duration config | P1 |
| Realtime | SSE connect, POST subscription, Java client, rule checks, query options smoke | Official subscription protocol edge cases, SDK subscribe/unsubscribe parity, auth refresh behavior, disconnect cleanup, reconnect behavior | P1 |
| Batch | Record batch transaction, Java client, multipart file mapping, rollback tests | Exact official request/response details, max request/body timeout settings enforcement, broader method/path coverage | P1 |
| Files | File token, protected file basics, thumbs, range handling, content disposition | Cache header parity, exact thumb failure semantics, real S3 storage backend, signed/protected edge cases | P1 |
| Settings | GET/PATCH/test S3/test email/Apple secret plus structured Admin UI pages | Full validation/default/redaction parity, real rate-limit enforcement, mail template settings, storage provider execution | P1 |
| Logs | Activity logging, list/view/stats, UI chart | Official stats bucket behavior, complete filter support, request metadata parity, retention scheduler tests | P2 |
| Backups | Local zip create/upload/download/delete/restore, manual auto-backup cron | Real S3 backup execution, restore safety parity, scheduled backup loop coverage, native validation | P1 |
| Crons | Built-in IDs listed and manually runnable | Real scheduler loop, exact cron parser behavior, status/last-run metadata | P2 |
| SQL | Native-friendly SQL subset and Admin UI console | Official SQLite semantics, params, full error shape, safe statement handling, view dry-run reuse | P1 |
| Middleware/security | CORS, OPTIONS, body reads, activity log | Official rate limit, body limit, trusted proxy, security headers, superuser IP whitelist, panic recovery shape | P0 |
| Persistence engine | JSON-file store with backups and ADR | Official parity still needs SQLite-style constraints/indexes/query planner; JSON store should remain dev/test or compatibility backend | P0 |

## 6. Admin UI Gap Map

| UI area | Done now | Remaining gaps | Priority |
| --- | --- | --- | --- |
| Shell/navigation | Sidebar, collection list, records/schema/settings/logs/crons/backups/import/export/sql views | Official hash routing, deep links, record modal URLs, and browser navigation parity | P1 |
| Login/auth pages | Superuser login panel plus installer/reset/verification/email-change action pages | Exact official installer token semantics, request verification/email change request pages, OAuth success/failure hash pages | P1 |
| Collections | Create/edit/delete modal, Fields/Rules/Auth/OAuth2/View-like sections, local diff review | Official tab parity, complete field option editors, index UI, view dry-run UI, destructive change confirmation | P0 |
| Field editors | Dedicated component for name/type/common flags and minimal file option | Complete official editors for text/email/url/number/bool/date/autodate/select/json/editor/file/relation/password/geoPoint options | P0 |
| Records | List, create/update/delete, field-specific controls, files, relation ID inputs, OAuth links panel | Relation picker, file picker/thumb previews, duplicate/preview, impersonate modal, official search/filter UX, field render parity | P1 |
| OAuth2 tester | Popup login tester with callback `postMessage` and result modal | Provider-specific forms matching official `collections/oauth2/*Options.js`, persisted health indicators, failure/success pages | P1 |
| Logs | List, details, stats-backed chart strip, and search controls | Official stats bucket parity, filter presets, request data viewer parity | P2 |
| Settings application | Structured app URL/meta/batch/rate-limit/trusted-proxy/superuser sections plus advanced JSON fallback | Exact official defaults/validation, complete rate-limit enforcement, better section-level dirty state | P1 |
| Settings mail | Structured SMTP/mail settings form and test email action | Official templates, delivery status details, and validation copy parity | P1 |
| Settings storage | File storage S3 form, backup S3 form, and S3 test action | Real S3 file/backups implementation and exact validation status parity | P1 |
| Backups | Create/upload/download/restore/delete plus settings and cron keep controls | Restore confirmation details, real S3 backup execution, scheduled backup coverage | P1 |
| Crons | Official-style list/run page | Real scheduler loop, exact status/last-run metadata, cron parser parity | P2 |
| Import/export | Export selection/JSON/copy/download and import JSON/file diff review modal | Official dry-run integration, exact schema diff semantics, destructive change warnings | P0 |
| SQL console | SQL editor, result table, and error panel | Official SQLite response/error parity, params, keyboard submit, view dry-run reuse | P1 |

## 7. Audited Task Status

Use this as the project board source. A task marked **Partial** should not be treated as finished parity work.

### P0

| SDP | Status | Evidence | Quality notes / next action |
| --- | --- | --- | --- |
| SDP-001 Route manifest conformance tests | Verified | `RouteConformanceTest` covers official route paths and unknown method behavior | Upgrade from static list to generated official manifest; validate auth class and response envelope, not just non-fallback routing |
| SDP-002 Official JS SDK smoke suite | Verified | `JsSdkSmokeTest` runs official JS SDK auth and CRUD smoke | Broaden to files, realtime, batch, auth action, and OAuth2/MFA flows |
| SDP-003 `POST /api/collections/meta/dry-run-view` | Verified | Route is present in `HttpApi` and manifest | Connect Admin UI view/import flows to backend dry-run results |
| SDP-004 `_mfas` internal collection and MFA auth response | Partial | `JsonFileStore` has `mfas` list and returns `mfaId` | Replace in-memory list with persisted official `_mfas`; add tests |
| SDP-005 MFA second-factor flow for password/OTP/OAuth2 | Partial | `authWithPassword`, `authWithOtp`, and `authWithOAuth2` accept `mfaId` | No direct regression tests found; exact official response/session lifecycle unproven |
| SDP-006 Rule/filter parser parity | Partial | `RuleEvaluator` is reused by rules, filters, protected files, realtime | Current evaluator is still a subset; needs grammar-level compatibility tests |
| SDP-007 Storage engine ADR and native proof | Partial | `ADR-001-Storage-Engine.md`, native-friendly JSON store, native CI job | ADR exists, but no SQLite/native proof branch is integrated; JSON store remains parity blocker |
| SDP-008 Field type validation matrix | Partial | `normalizeFieldValue` covers common field types/options | Relation/file/select/json/geo and official error text are incomplete |
| SDP-009 Collection indexes and schema migration semantics | Partial | Index data is accepted/stored on collection schema | Index validation/enforcement and official migration behavior are incomplete |
| SDP-010 Official collection import/export review flow | Partial | Admin UI local diff review and backend import route exist | Review is local diff, not official dry-run semantics; destructive warnings need parity |
| SDP-011 Field-specific collection editor UI | Partial | `FieldEditor` component edits name/type/common flags and minimal file options | Most official field-specific option panes are still missing |

### P1

| SDP | Status | Evidence | Quality notes / next action |
| --- | --- | --- | --- |
| SDP-012 Field-specific record editor UI | Partial | `RecordFieldControl` handles bool/number/json/editor/date/select/basic text/relation IDs | It is useful but not official-grade; needs relation picker, file preview/picker, duplicate/preview, impersonation |
| SDP-013 OAuth2 provider-specific config parity | Partial | Generic OAuth2 backend, provider metadata, Apple form post mode test, Admin UI tester | Full provider-specific behavior, `redirectUrl` alias, avatar/name forwarding, and conflict semantics remain |
| SDP-014 Structured application settings page | Partial | Structured settings UI and backend settings persistence exist | Defaults/validation/rate-limit enforcement are not official-complete |
| SDP-015 Mail settings page and test modal | Partial | Mail settings UI and `POST /api/settings/test/email` exist | Official templates and delivery status/details remain |
| SDP-016 Storage settings page and S3 tests | Partial | S3 settings UI and `S3Probe` test endpoint exist | S3 is only probed; it is not a real file storage backend |
| SDP-017 Backup settings page and S3 backups | Partial | Local backups and backup S3 settings/test exist | Real S3 backup execution and scheduled loop coverage are missing |
| SDP-018 Realtime SDK compatibility smoke | Partial | Java realtime client and server smoke tests exist | Official JS SDK realtime subscribe/unsubscribe smoke is still needed |
| SDP-019 Batch service SDK and multipart rollback tests | Verified | `BatchService`, `BatchSmokeTest`, and multipart rollback tests exist | Continue broadening official response shape and limit enforcement |
| SDP-020 SQL console UI | Partial | Admin SQL console and `/api/sql` subset exist | Official SQLite semantics and param/error parity remain incomplete |

### P2

| SDP | Status | Evidence | Quality notes / next action |
| --- | --- | --- | --- |
| SDP-021 Logs chart/stats parity | Partial | `GET /api/logs/stats` and Admin chart exist | Official bucket/filter/request-data parity is incomplete |
| SDP-022 File range/cache/S3 parity | Partial | `HttpFileSupport` handles range requests; tests cover clamp/suffix/416 | Cache header parity and real S3 storage remain incomplete |
| SDP-023 Installer and auth action pages | Verified | `AuthActionPages` implements `#/pbinstall`, reset, verification, email-change flows | Add request verification/email change request pages if matching official UI scope |
| SDP-024 Native CI release gate | Verified | `.github/workflows/ci.yml` has JVM tests, UI build with resource diff check, native build, native smoke | Monitor CI results; rerun native build after every dependency/storage/provider change |

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

For UI work, also run a browser smoke against the embedded Admin UI and verify the changed page is visible and usable. For generated Admin UI assets, CI now requires `npm run build` to leave `src/main/resources/pocketbase-admin` clean.

## 9. Risks

1. JSON-file storage will keep blocking exact PocketBase behavior for indexes, unique constraints, SQL, view collections, and complex filters.
2. GraalVM native image compatibility can be broken by storage, OAuth2, mail, image, or S3 libraries unless each is tested before adoption.
3. Admin UI can drift from backend behavior if pages are added before backend contracts are locked. Backend contract tests should lead UI expansion.
4. OAuth2 provider behavior is full of provider-specific exceptions. Generic OIDC support is useful, but it is not the same as official provider parity.
5. Official SDK compatibility must be tested directly. A Java client passing its own tests is not enough.

## 10. Next Recommended Work

Start with these quality gaps, in order:

1. **Persist and test MFA officially**: replace in-memory `mfas` with an internal `_mfas` collection model, then add password/OTP/OAuth2 second-factor tests and cleanup tests.
2. **Upgrade route conformance into behavior conformance**: keep the manifest test, but add canonical request/response fixtures for auth, records, files, realtime, batch, and settings.
3. **Connect import/view UI to `dry-run-view`**: local diff review is useful, but official-compatible review should use backend dry-run semantics.
4. **Finish file/storage parity**: keep the new Range support, then add cache header checks and real S3 storage/backups.
5. **Make UI field editors official-grade**: complete field option panes before claiming collection/record editor parity.
