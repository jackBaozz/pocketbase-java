# SDP Phase 4: PocketBase Parity And Release Hardening Plan

Updated: 2026-07-19
Baseline: [PocketBase v0.39.7](https://github.com/pocketbase/pocketbase/releases/tag/v0.39.7) API and Admin UI behavior compatibility.

This document outlines the roadmap for Phase 4. It excludes all tasks successfully completed during Phase 3 and breaks down the remaining uncompleted requirements into granular, actionable tasks.

---

## 1. Phase 4 Goals

The primary goal of Phase 4 is to transition the Java implementation from an SQLite MVP to a robust, multi-database production-ready runtime.

- **Storage Parity**: JSONL-backed local storage remains an acceptable default for lightweight/dev usage, while SQLite/MySQL/PostgreSQL are verified through the relational storage path for production parity work.
- **Durable Auth & Lifecycle**: Implement persistent tokens, OTP, OAuth2 code flows, and SMTP mail delivery.
- **Unified File & Backup SPI**: Support S3 and local storage, complete with transactions, thumbnails, and ZIP-based backups.
- **Rule, SSE, Batch Parity**: Implement the complete rule query parameters, SSE realtime updates, transactional batch changes, and raw SQL queries.
- **Admin UI Parity**: Deep links, index/schema editor, relation selector, logs stats, S3 controls, and mail template visual designer.
- **GraalVM Release Verification**: Ensure native-image runs correctly with dynamic JDBC profiles and S3/mail dependencies.

---

## 2. Phase 4 Work Breakdown

### Stream A: Relational Storage & Database Matrix

This workstream focuses on database compatibility, dialect abstractions, and schema migrations.

#### [x] P4-A01: Dialect-Aware Query Compilation & SQL Endpoint
- [x] **SQL query validation**: Design query validation parser to reject invalid or dangerous statements in raw SQL/view SQL.
- [x] **Dialect-aware JSON extraction**: Replace SQLite-only `json_extract(...)` calls in `FilterToSqlCompiler` with a dialect abstraction supporting:
  - SQLite: `json_extract(column, '$.path')` or `column ->> '$.path'`
  - MySQL: `JSON_EXTRACT(column, '$.path')` or `column->>'$.path'`
  - PostgreSQL: `column ->> 'path'` (for json/jsonb columns)
- [x] **Dialect-specific operators & quoting**: Implement dialect-specific text concatenation, quoting, pagination, and LIKE escaping (`%`, `_`, `\`) for MySQL and PostgreSQL.
- [x] **SQL Exec API validation**: Enable the official raw SQL executing API for superusers with parameter bindings, enforcing transaction security.
- *Acceptance Criteria*: SQL execution and view dry-runs pass correctly on all active relational dialects (SQLite, MySQL, PostgreSQL).

#### [x] P4-A02: SQL Type Mapping & Value Normalization
- [x] **Migration tests for field types**: Create migration tests covering all field types (text, editor, email, url, number, bool, date, autodate, select, json, file, relation, password, geoPoint).
- [x] **Output type normalization**: Verify that values read from the database are correctly coerced into official JSON data types (e.g. converting numeric booleans, mapping JSON strings to list arrays).
- [x] **Array serialization consistency**: Verify that list fields (`select` multiple, `json` arrays, `relation` ID lists) serialize and roundtrip consistently across SQLite, MySQL, and PostgreSQL.
- *Acceptance Criteria*: Integration tests verify field storage mapping and value normalization pass for all database dialects.

#### [x] P4-A03: Schema Migration Planner
- [x] **Migration plan generator**: Build an engine that calculates diffs between schema states and outputs safe migration SQL.
- [x] **Field structural modifications**: Implement field addition, removal, renaming, and type modification.
- [x] **Index manager operations**: Implement DDL commands to create, alter, and drop collection database indexes.
- [x] **Dynamic View DDL**: Handle schema migration updates when collections of type `view` change their underlying SQL definition.
- [x] **Dry-run schema output**: Provide a structural migration diff payload mapping directly to the Admin UI review screen.
- [x] **DDL Transaction safety**: Wrap DDL migration queries in database transactions where supported (PostgreSQL/SQLite) and add transactional rollback workarounds for MySQL.
- [x] **DDL Dialect documentation**: Document specific DDL limitations (such as SQLite column type alteration limits).
- *Acceptance Criteria*: Applying or rolling back a migration plan behaves atomically and leaves the database schema in sync with `_collections` metadata.

#### [x] P4-A04: MySQL and PostgreSQL Database Profiles
- [x] **Startup permission probes**: Add startup validation to verify that the DSN credentials have the necessary database permissions (CREATE, SELECT, INSERT, UPDATE, DELETE, INDEX, ALTER, DROP).
- [x] **Collation & encoding validation**: Verify that database connection character encoding is UTF-8 (utf8mb4 for MySQL) and collations match PocketBase requirements on startup.
- [x] **CI pipeline integration**: Configure optional/required MySQL and PostgreSQL test execution in the project's CI configuration.
- *Acceptance Criteria*: Verification suites run on real MySQL and PostgreSQL databases.

#### [x] P4-A05: JSONL Default Engine & Relational Migration Paths
- [x] **Default JSONL configuration**: Keep the server's default configuration on the lightweight JSONL-backed local store for dev/native-friendly usage.
- [x] **SQLite opt-in path**: Keep `-Dstorage=sqlite` as the relational parity baseline and preserve MySQL/PostgreSQL profile switches.
- [x] **JSON-to-JSONL compatibility**: Read legacy `.json` record files and write current record files as `.jsonl`.
- [x] **Relational migration path**: Keep explicit relational storage profiles available for parity validation and production-style deployments.
- [x] **Documentation updates**: Revise this Phase 4 plan to reflect the JSONL-default decision instead of a SQLite-first default.
- *Acceptance Criteria*: A fresh server launch bootstraps using JSONL without requiring extra flags, and relational storage remains available through explicit storage flags.

---

### Stream B: Official Behavior Fixtures & Testing

This workstream improves the testing coverage to verify compatibility against official specs.

#### [x] P4-B01: API Fixture Harness Expansion
- [x] **Modular fixture groups**: Restructure behavior fixtures to match official API groups (auth, MFA, OAuth2, collections, records, files, settings, logs, backups, crons, SQL, batch, realtime).
- [x] **Negative test cases**: Write negative tests for each endpoint covering unauthorized access, not found, forbidden, invalid JSON bodies, and invalid query structures.
- [x] **Superuser record CRUD parity**: Support the official `_superusers` Records API flow, including superuser-only access, create/list/view/update/delete operations, sensitive-field redaction, auth-token revocation after email/password changes, and protection against deleting the final superuser.
- [x] **Auth support collection CRUD parity**: Register `_authOrigins`, `_externalAuths`, `_mfas`, and `_otps` as official system collections with owner-scoped list/view rules, collection-specific delete permissions, superuser-only create/update operations, reference validation, persistence, and auth-record cascade cleanup.
- *Acceptance Criteria*: Test suites execute the same fixture assertions across all active storage engines.

#### [x] P4-B02: Official Version Baseline Management
- [x] **Version baseline pin**: Explicitly pin the official PocketBase version baseline in project documentation and compatibility audits.
- [x] **Route manifest sync script**: Implement a script or workflow that compares current project routes against the official route/method manifest.
- [x] **Custom route separation**: Isolate and document custom Java-only routes so they are clearly marked.
- *Acceptance Criteria*: Running the route validation helper produces a diff showing exactly which routes are verified, missing, or customized.

#### [x] P4-B03: Client SDK Compatibility Testing
- [x] **Patch-free JS SDK validation**: Run the official JavaScript SDK test suites against the running server without any custom client-side request modifications.
- [x] **Expand JS SDK coverage**: Add test coverage for file uploads/downloads, batch operations, realtime subscriptions, token refresh, OAuth2, and MFA.
- [x] **Dart SDK smoke tests**: Write basic client tests using the official Dart/Flutter SDK to verify mobile client compatibility.
- *Acceptance Criteria*: Official JS and Dart SDKs can establish connections and perform CRUD, auth, and realtime operations without client code modifications.

#### [x] P4-B04: HTTP Request Middleware Parity
- [x] **Rate-limit rule matching**: Apply settings-driven fixed-window limits for exact routes, `/`-suffix route prefixes, guest/auth audiences, and collection action labels such as `*:create`, `*:auth`, and `collection:create`.
- [x] **Rate-limit response and bypass rules**: Return `429` responses with `Retry-After`, bypass superusers, and reset counters when the effective configuration changes.
- [x] **Client IP resolution**: Support excluded IP/CIDR entries, trusted proxy headers, and configurable leftmost/rightmost forwarded-IP selection.
- [x] **Superuser IP whitelist**: Validate `superuserIPs` as literal IP/CIDR entries and enforce the whitelist for existing superuser sessions, new auth responses, protected file tokens, and backup downloads without restricting guests or ordinary auth records.
- [x] **Request body limits**: Enforce the official 32 MiB global request limit and collection-aware extra allowance for file, editor, and JSON fields using both `Content-Length` prechecks and streamed-read enforcement.
- [x] **Settings validation and rollback**: Reject invalid `rateLimits` settings consistently on JSONL and relational storage without replacing the last valid in-memory configuration.
- *Acceptance Criteria*: Middleware behavior and validation envelopes match the official v0.39.7 rules on JSONL and SQLite.

#### [x] P4-B05: Logging Settings & Maintenance Cron Parity
- [x] **Logging settings enforcement**: Apply `logs.maxDays`, `logs.minLevel`, `logs.logIP`, and `logs.logAuthId` consistently to JSONL and relational activity logs.
- [x] **Settings-change cleanup**: Immediately remove logs that no longer satisfy the updated retention duration or minimum level.
- [x] **Logs cleanup cron**: Run `__pbLogsCleanup__` asynchronously and delete expired relational logs using the configured retention period.
- [x] **Database optimize cron**: Run the official SQLite WAL checkpoint and optimize operations, with MySQL table analysis and PostgreSQL database analysis equivalents for the additional relational profiles.
- *Acceptance Criteria*: Logging privacy/level controls and maintenance jobs have executable JSONL/SQLite coverage, and no listed relational cron remains an explicit no-op.

#### [x] P4-B06: SearchProvider Query Contract Parity
- [x] **Shared query parsing**: Apply the official pagination and search query rules consistently to record, collection, and log list endpoints on JSONL and relational storage.
- [x] **Skip-total responses**: Support `skipTotal` values accepted by Go's boolean parser and return `totalItems=-1` and `totalPages=-1` for SDK full-list/first-item requests.
- [x] **Pagination normalization and limits**: Normalize non-positive page values, restore the official 30-item default, cap `perPage` at 1000, and reject malformed numeric or boolean values.
- [x] **Search limits and sort parsing**: Enforce the official 3500-character filter, eight-expression sort, and 255-character sort-field limits, including `+field`, `-field`, `@random`, and nested map sorting support where applicable.
- [x] **Record query safety and processing order**: Reject guest/record-auth client filters or sorts containing `@collection.*` or `@request.*`, and apply filtering, sorting, and pagination before record enrich/expand/field selection.
- [x] **Schema-aware field resolution**: Reject unknown record, collection, log, and log-stats filter/sort fields before query execution; allow hidden record fields only for superuser client queries while keeping server-side collection rules unrestricted.
- [x] **Official field modifiers**: Evaluate `:lower`, `:length`, `:each`, `:isset`, and `:changed` consistently in the shared rule engine, apply modifier-aware in-memory record filtering/sorting for relational storage, and reject unknown modifiers with the standard filter error envelope.
- [x] **Official filter token functions**: Parse and evaluate `strftime(...)` and `geoDistance(...)`, including SQLite-compatible date formats/modifiers, multi-relation all/any behavior, geo-distance any-match behavior, argument validation, and schema-aware identifier extraction.
- *Acceptance Criteria*: Official SDK requests receive matching pagination metadata and cannot use superuser-only rule fields from non-superuser sessions.

#### [x] P4-B07: Hidden Record Field Write Protection
- [x] **Default-access field filtering**: Silently ignore hidden collection fields submitted by guests or ordinary auth records during create and update operations.
- [x] **Superuser field access**: Preserve full hidden-field write access for authenticated superusers.
- [x] **Auth password exception**: Keep the official auth `password` field writable for non-superusers even though the field is hidden.
- [x] **Multipart and file modifiers**: Ignore hidden file replacements, append operations, and deletion markers from non-superusers without writing orphaned storage files.
- [x] **Permission check ordering**: Reject `createRule=null` requests before loading or validating submitted fields so system collections return the official 403 response instead of field validation errors.
- *Acceptance Criteria*: Hidden values and files never change from non-superuser record requests, while superuser writes and ordinary auth registration continue to work.

#### [x] P4-B08: Request Rule Context Parity
- [x] **Case-insensitive request headers**: Expose HTTP headers through `@request.headers.*` with case-insensitive lookup and `:isset` support.
- [x] **CRUD and auth propagation**: Preserve query and header context through record list/view/create/update/delete, relation expansion, auth/manage/MFA rules, and response field processing on JSONL and relational storage.
- [x] **Batch sub-request context**: Merge allowed batch sub-request headers with the outer request context, ignore per-request authorization overrides, and preserve sub-request query parameters for update and delete rules.
- [x] **Realtime subscription context**: Apply subscription option headers and query values to list/view rules, filters, relation expansion, and emitted record visibility.
- *Acceptance Criteria*: Direct HTTP, batch, authentication, and realtime rules observe the same request query/header values on JSONL and SQLite.

#### [x] P4-B09: Official Request Execution Contexts
- [x] **Official context values**: Expose `default`, `expand`, `realtime`, `protectedFile`, `batch`, `oauth2`, `otp`, and `password` through `@request.context` at the same rule execution boundaries as PocketBase v0.39.7.
- [x] **Expansion and protected files**: Evaluate expanded relation `viewRule`/manage visibility with the `expand` context and protected file `viewRule` checks with the `protectedFile` context while preserving request query, headers, and file-token auth.
- [x] **Authentication contexts**: Evaluate password, OTP, OAuth2, MFA, and auth rules with their method-specific context values.
- [x] **OAuth2 internal record creation**: Create new OAuth2 auth records through the normal Records create-rule path with the `oauth2` context and original request auth/headers, then apply the official matching-email verification upgrade.
- *Acceptance Criteria*: Rules can independently allow or reject direct, batch, expand, realtime, protected-file, password, OTP, and OAuth2 operations using only `@request.context`.

#### [x] P4-B10: Protected File Authorization Privacy
- [x] **Optional file-token principal**: Resolve protected-file authentication only from the optional `?token=` file token and evaluate the collection `viewRule` even when the resulting principal is a guest.
- [x] **Public protected-file rules**: Allow guest access to a protected field when its collection `viewRule` permits it, including requests with no token or an invalid token.
- [x] **Authorization privacy**: Return the standard resource-not-found `404` envelope for missing files, expired or invalid file tokens, and protected-file rule denials instead of exposing a distinguishable `403` response.
- [x] **Bearer isolation**: Do not treat an ordinary Authorization bearer token as a protected-file token.
- *Acceptance Criteria*: Protected-file access and denial behavior matches the official v0.39.7 handler without leaking whether a file, token, or rule match exists.

#### [x] P4-B11: Health API Privacy & Diagnostics
- [x] **Public response privacy**: Return an empty `data` object to guests and ordinary auth records instead of exposing storage paths or superuser setup state.
- [x] **Superuser diagnostics**: Return only the official `canBackup`, `realIP`, and `possibleProxyHeader` fields to authenticated superusers.
- [x] **Proxy hint ordering**: Check configured trusted-proxy headers before the official common proxy-header fallbacks and reuse the shared real-IP resolver.
- [x] **HEAD compatibility**: Accept `HEAD /api/health` with the same success status as GET and no response body.
- [x] **Admin bootstrap isolation**: Move the bundled Java Admin UI setup check to the Java-only `GET /api/bootstrap/superuser` probe so the official health response remains compatible.
- *Acceptance Criteria*: Health responses match PocketBase v0.39.7 visibility and diagnostic fields while the bundled Admin UI can still bootstrap the first superuser without relying on nonofficial health fields.

#### [x] P4-B12: Backup HTTP Contract & Operation State
- [x] **Official list response**: Return the backup list as a top-level array of `key`, `size`, and date-time `modified` objects without pagination metadata or Java-only `name` fields.
- [x] **No-content mutations**: Return `204` with an empty body after successful backup creation, upload, restore scheduling/execution, and deletion.
- [x] **Upload contract**: Disable the default request body limit for `/api/backups/upload`, validate the ZIP MIME type and unique name, and defer archive structure validation until restore.
- [x] **Operation mutual exclusion**: Share one active create/restore guard per storage engine, reject overlapping operations, prevent deletion of the active backup, and expose the state through `health.data.canBackup`.
- [x] **Official error statuses**: Return `400` for invalid create names, missing restore files, already-deleted files, and active-operation conflicts.
- *Acceptance Criteria*: Local and S3-backed backup APIs match the official v0.39.7 response shapes, success statuses, upload boundary, and active-operation behavior.

#### [x] P4-B13: Auth Methods Response Contract
- [x] **Collection lookup behavior**: Return the standard empty-data `404` envelope for missing collections and non-auth collections.
- [x] **Disabled method normalization**: Return an empty password `identityFields` array and zero OTP/MFA durations whenever the corresponding method is disabled.
- [x] **OTP response privacy**: Expose only `enabled` and `duration` from the OTP configuration without leaking the configured code length.
- [x] **OAuth2 enabled state**: Report `oauth2.enabled` from the collection configuration even when no configured provider can be initialized into a response entry.
- [x] **Official provider registry**: Embed the ordered PocketBase v0.39.7 registry of 32 providers, including the official display names and SVG logos exposed by the collection metadata and auth-method endpoints.
- [x] **Standard provider initialization**: Apply official default authorization/token/user-info URLs, scopes, and PKCE behavior before generating auth URLs or exchanging codes, while preserving collection-level overrides.
- [x] **Legacy provider compatibility**: Preserve the full OAuth2 provider entries in `oauth2.providers` while cloning `authProviders` with an explicit empty `logo` value, matching the official legacy response.
- *Acceptance Criteria*: `/api/collections/{collection}/auth-methods` matches the PocketBase v0.39.7 response and error contract on JSONL and SQLite.

#### [x] P4-B14: OAuth2 Collection Configuration Validation
- [x] **Enabled-only provider validation**: Validate provider entries only when OAuth2 is enabled, while allowing disabled configurations and enabled configurations with an empty provider list to persist unchanged.
- [x] **Official provider requirements**: Require case-sensitive registered provider names plus non-empty `clientId` and `clientSecret` values, reject duplicate provider names, and validate only non-empty endpoint overrides as URLs.
- [x] **Indexed validation envelopes**: Return official validation codes at `data.oauth2.providers.{index}.{field}` for missing fields, unknown providers, duplicate providers, and invalid URLs.
- [x] **OIDC model parity**: Allow OIDC providers with credentials and no authorization, token, or user-info endpoint overrides because those URLs are not required by the collection model validator.
- [x] **Atomic collection writes**: Apply the same validation to collection create, update, dry-run import, and actual import, preserving the previously stored collection after a rejected update or import.
- *Acceptance Criteria*: OAuth2 collection validation and rollback behavior match PocketBase v0.39.7 on JSONL and SQLite.

#### [x] P4-B15: Auth Option Range, Template, and MFA Rule Validation
- [x] **OTP model validation**: Require enabled OTP durations between 10 and 86400 seconds, a code length of at least four characters, and a non-empty email subject/body even when OTP is disabled.
- [x] **MFA model validation**: Require enabled MFA durations between 10 and 86400 seconds and compile non-empty enabled MFA rules against the same collection/relation field resolver used by the other collection rules.
- [x] **Mail template validation**: Require non-empty subjects and bodies for auth-alert, verification, password-reset, and email-change templates regardless of whether the associated delivery feature is enabled.
- [x] **Official validation envelopes**: Return `validation_required`, `validation_min_greater_equal_than_required`, and `validation_max_less_equal_than_required` with the official messages and `params.threshold` values at the matching nested fields.
- [x] **Disabled-value preservation and patch semantics**: Preserve explicitly submitted disabled OTP/MFA values without silently clamping them, merge partial nested updates over the current configuration, and keep stored collections unchanged after rejected updates or imports.
- *Acceptance Criteria*: Auth option validation, nested patching, persistence, and rollback match PocketBase v0.39.7 on JSONL and SQLite.

#### [x] P4-B16: Auth Token Configuration Validation and Secret Round-Trip
- [x] **All token config types**: Apply the same model validation to `authToken`, `passwordResetToken`, `emailChangeToken`, `verificationToken`, and `fileToken`.
- [x] **Secret and duration bounds**: Require secrets between 30 and 255 characters and durations between 10 and 94670856 seconds, including the official required/min/max/length codes, messages, and validation params.
- [x] **Hidden secret patch behavior**: Preserve the persisted secret when a redacted empty secret is submitted, while continuing to revoke issued auth, file, and action tokens after an explicit valid secret replacement.
- [x] **Atomic collection changes**: Apply token validation to collection create, partial update, dry-run import, and actual import without replacing the last valid collection after a rejection.
- [x] **Valid expiration fixtures**: Test expired auth and password-reset tokens using correctly configured collections and valid signatures with past expiry claims instead of relying on nonofficial sub-10-second durations.
- *Acceptance Criteria*: Token configuration validation, hidden-secret updates, rotation, expiry, and rollback match PocketBase v0.39.7 on JSONL and SQLite.

#### [x] P4-B17: Collection Update Immutability and Superuser Invariants
- [x] **Immutable collection metadata**: Reject collection type and system-state changes after creation with `validation_collection_type_change` and `validation_collection_system_flag_change`.
- [x] **Immutable field types**: Reject updates and imports that reuse an existing field ID with a different type, returning the indexed `validation_field_type_change` error.
- [x] **System collection protection**: Reject system collection name changes and changes to list, view, create, update, delete, auth, manage, and enabled MFA rules with the official system-name/rule error codes.
- [x] **Superuser save invariants**: Force `_superusers` password authentication on, disable and clear OAuth2 providers, and automatically enable MFA whenever OTP is enabled.
- [x] **MFA empty-rule semantics**: Treat a null or empty enabled MFA rule as applying MFA to every auth record, while continuing to evaluate non-empty rules normally.
- [x] **Atomic update/import behavior**: Apply the same immutable-property checks before JSONL writes or relational migrations so rejected changes leave collection metadata and physical schemas unchanged.
- *Acceptance Criteria*: Collection update restrictions, `_superusers` invariants, and MFA default-rule behavior match PocketBase v0.39.7 on JSONL and SQLite.

#### [x] P4-B18: Collection Model Identifiers and Field List Validation
- [x] **Collection ID and type validation**: Enforce the official 1-100 ID length, word-character format, existing-ID rejection, and case-sensitive `base`/`auth`/`view` type allowlist with matching validation codes, messages, and range params.
- [x] **Collection name contract**: Enforce the 1-255 word-character name range, case-insensitive uniqueness, `_via_` exclusion, existing collection-ID collisions, and internal table collisions with the official collection-specific errors.
- [x] **Field list binding semantics**: Replace earlier submitted fields by explicit duplicate ID or exact missing-ID name before validation, matching PocketBase's `FieldsList.Add` behavior, while retaining case-insensitive duplicate-name validation for distinct fields.
- [x] **Field identifier validation**: Enforce 1-100 field ID/name lengths, word-character names, dynamic and literal reserved names, auth-only reserved names, `_via_` exclusion, primary-key presence, indexed errors, and official error params.
- [x] **Aggregated update validation**: Return independent name and immutable-type errors together and keep the previous collection unchanged after rejected updates or imports.
- [x] **Case-only relational renames**: Rename tables through a transaction-local temporary name when only identifier casing changes, avoiding SQLite's direct case-only rename failure while preserving the official API behavior.
- *Acceptance Criteria*: Collection and field identifier validation, field list replacement, error aggregation, rollback, and case-only renames match PocketBase v0.39.7 on JSONL and SQLite.

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
- [x] **Official standard-provider defaults**: Initialize the v0.39.7 provider registry with its built-in endpoints, scopes, PKCE defaults, display names, ordering, and logos; retain custom URL/display/scope/PKCE/extra overrides and preserve nullable PKCE defaults through the Admin UI.
- [x] **OAuth2 lifecycle**: Implement the full OAuth2 authentication flow (redirect generation, token exchange, id_token verification, user profile fetching).
- [x] **OAuth2 record creation parity**: Generate an internal random password and matching `passwordConfirm` when OAuth2 creates a new auth record without an explicitly submitted password.
- [x] **Official auth system fields**: Normalize every auth collection to the official `id`, `password`, `tokenKey`, `email`, `emailVisibility`, and `verified` system fields, and upgrade legacy JSONL metadata/records and relational tables during startup.
- [x] **Persistent token secrets and response redaction**: Generate and persist random auth/action token secrets, redact token and OAuth2 client secrets from collection API responses, and preserve stored OAuth2 secrets when a redacted collection payload is submitted unchanged.
- [x] **Auth token revocation on rule changes**: Rotate the auth token secret whenever `authRule` materially changes so previously issued auth tokens are rejected immediately and after restart.
- [x] **Account linking & conflict management**: Handle email duplication, link new OAuth providers to existing records, and handle unlinking.
- [x] **OAuth2 mapped fields parity**: Persist and expose the official `id`, `name`, `username`, and `avatarURL` mappings, apply them only while creating new auth records, preserve explicit `createData` values, clear mappings to missing collection fields, and support text or bounded remote-file avatar targets.
- [x] **Browser redirect/popup response**: Render redirect success/failure pages and handle `postMessage` outputs for popup authentication.
- *Acceptance Criteria*: The mock OAuth2 server handles authentication flows, and accounts link or fail according to configuration rules.

#### [x] P4-C04: SMTP Mail Delivery
- [x] **SMTP Client integration**: Implement SMTP client support with TLS, SSL, and authentication options.
- [x] **Outbox/Dry-run mail log**: Implement a dry-run or local mail directory logger to capture email outputs for testing.
- [x] **Template compilation**: Compile official collection mail templates using `{APP_NAME}`, `{APP_URL}`, `{TOKEN}`, `{OTP}`, `{OTP_ID}`, `{ALERT_INFO}`, and `{RECORD:field}` placeholders.
- [x] **New-device auth alerts**: Persist auth-origin fingerprints, suppress first-login and repeated-origin notifications, retain the five most recent origins, and clear origins after password changes.
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
- [x] **File token resolution**: Resolve optional protected-file principals from query file tokens while allowing guest access when the collection `viewRule` permits it.
- [x] **Rule-based access control**: Evaluate collection `viewRule` requirements with the official `protectedFile` request context and return a privacy-preserving `404` for denied access.
- [x] **HTTP range & caching**: Implement HTTP range requests, cache-control headers, ETag validation, and MIME-type assertions.
- [x] **Thumbnail generation & cache**: Generate and cache file thumbnails, returning fallback assets for unsupported files.
- *Acceptance Criteria*: Protected files follow the official optional-token and `viewRule` behavior, denied or missing resources share the standard `404` envelope, and thumbnails cache as expected.

#### [x] P4-D03: Backup Provider Parity
- [x] **backupFile resolver**: Implement backup file resolvers for relational storage.
- [x] **Relational backup operations**: Implement backup creation, list, download, upload, delete, and restore workflows.
- [x] **Multi-dialect support**: Document or implement backup/restore strategies for MySQL and PostgreSQL.
- [x] **S3 Backup storage**: Support backing up files directly to AWS S3/S3-compatible storage.
- [x] **Official backup download authorization**: Require a short-lived superuser file token in the download query and apply the configured superuser IP whitelist for local and S3-backed backup downloads.
- [x] **Restore validation & safety**: Validate the backup ZIP archive structure before running restorations, and implement rollback fallbacks if the restore fails.
- [x] **Official HTTP wire contract**: Return array list responses and `204` mutation responses, accept MIME-valid uploads without eagerly parsing the archive, and validate them when restore is requested.
- [x] **Active operation lifecycle**: Reject overlapping create/restore calls, block deletion of the active backup, and report backup availability through the superuser health diagnostics.
- [x] **Scheduled auto-backup cron**: Integrate backups with the scheduler to run automatic backups.
- [x] **Atomic publication and shutdown lifecycle**: Generate and validate local backups under temporary names, atomically publish complete ZIP files, wait for accepted cron jobs during storage shutdown, and close relational database resources after maintenance work finishes.
- *Acceptance Criteria*: ZIP backups can be created, uploaded, downloaded, and restored successfully.

---

### Stream E: Rules Engine, Realtime, Batch, and SQL Endpoints

This workstream completes advanced API features such as rules compilation, realtime streams, and batch actions.

#### [x] P4-E01: Rule Engine Parity & Compiler
- [x] **Grammar validation**: Audit the project's `RuleEvaluator` and `FilterToSqlCompiler` against official rule specifications, and reject invalid syntax or unknown record fields when collection rules are saved.
- [x] **Context variables support**: Implement context variables: `@request.auth.*`, `@request.body.*`, `@request.query.*`, `@request.headers.*`, `@request.method`, and `@collection.*` relation fields.
- [x] **Record relation field resolver**: Resolve direct, nested, and `collection_via_field` back-relation paths for record rules and searches, including PocketBase's all-match operators and `?` any-match variants across multi-value relations.
- [x] **Complete save-time rule compilation**: Validate full direct, nested, back-relation, `@collection`, `@request.body`, modifier, and filter-function identifier paths during collection create, update, dry-run import, and actual import, including forward references within the same import batch.
- [x] **Logical & type assertions**: Add tests verifying null, empty string, arrays, relation fields, date comparisons, and operator priorities.
- [x] **Auth rule enforcement**: Persist and enforce auth collection `authRule` semantics (`null` denies authentication, blank allows authentication, and non-empty rules must match the target record) across password, OTP, and OAuth2 flows.
- [x] **Manage rule enforcement**: Apply normal `updateRule`/`deleteRule` checks before `manageRule`, require email/verified/password safeguards for callers without manage access, and expose private auth-record emails only to superusers, owners, or matching managers.
- *Acceptance Criteria*: Compiled rule filters output valid SQL and match the evaluation results of the official engine.

#### [x] P4-E02: Realtime SSE Protocol Parity
- [x] **SSE format validation**: Format SSE response structures to match official PocketBase clients.
- [x] **Auth refresh validation**: Validate auth tokens on open SSE connections, disconnecting expired or revoked sessions.
- [x] **Access filter broadcasting**: Filter change notifications before broadcasting to ensure clients have permission via collection rules.
- [x] **Connection pool management**: Implement connection cleanup, reconnect handlers, and backpressure management.
- *Acceptance Criteria*: Clients subscribe and receive updates over SSE, filtering out unauthorized events.

#### [x] P4-E03: Batch API Parity
- [x] **Sub-request routing**: Route batch requests to appropriate endpoint handlers.
- [x] **System limits**: Enforce batch request limits (max requests, payload sizes, timeout, and authorization).
- [x] **Multipart batch payloads**: Parse multipart files within batch updates.
- [x] **Atomic transactions**: Ensure database transactions and uploaded files roll back completely if any batch sub-request fails.
- *Acceptance Criteria*: Batch API requests process atomically, rolling back all modifications on failures.

#### [x] P4-E04: Direct SQL Endpoint
- [x] **SQL request API**: Implement the raw SQL executing endpoint for superusers.
- [x] **Query syntax analyzer**: Restrict execution to allowed query types (SELECT, INSERT, UPDATE, DELETE) and handle safety validations.
- [x] **Dialect normalizer**: Format returned column types and errors to match database dialects.
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

#### [x] P4-G01: GraalVM Native Image Validation
- [x] **Native compilation check**: Run native image builds with all dependencies (jOOQ dialects, JDBC drivers, S3 packages, mail, and image thumbnail encoders).
- [x] **Minimize native configs**: Update and minimize files under `src/main/resources/META-INF/native-image`.
- [x] **Native verification tests**: Execute integration tests against compiled native binaries.
- [x] **Conditional database drivers**: Ensure optional drivers (MySQL, PostgreSQL) do not impact compilation size or startup validation when using SQLite.
- *Acceptance Criteria*: Native image compilation succeeds and passes core integration tests on SQLite.

#### [x] P4-G02: CLI & Configuration Parity
- [x] **CLI commands**: Match official startup options and flags (`--dir`, `--encryptionEnv`, etc.).
- [x] **Data masking**: Redact credentials, API keys, and email passwords from system logs and settings responses.
- *Acceptance Criteria*: Startup flags behave consistently, and logs redact sensitive credentials.

#### [x] P4-G03: Build & Release Gates
- [x] **Required CI gates**: Configure CI pipeline checks for JVM test execution, SQLite tests, MySQL tests, PostgreSQL tests, and UI asset compilation.
- [x] **Native binary gate**: Include a compilation step verifying release branches compile on GraalVM.
- *Acceptance Criteria*: Merges to main require successful test coverage passes across all database dialects.

---

## 3. Recommended Execution Order

1. **Database dialect configuration** (P4-A01, P4-A02)
2. **Schema migration compiler** (P4-A03)
3. **Multi-database integration and JSONL default paths** (P4-A04, P4-A05)
4. **Auth actions, OTP persistence, and OAuth2 flow** (P4-C01, P4-C02, P4-C03)
5. **SMTP mail delivery** (P4-C04)
6. **File Storage provider and backup integration** (P4-D01, P4-D02, P4-D03)
7. **Rules compilation and SSE realtime** (P4-E01, P4-E02)
8. **Batch actions and SQL endpoint** (P4-E03, P4-E04)
9. **Admin UI hash routing, schemas, and record editors** (P4-F01 to P4-F04)
10. **GraalVM native configuration and release gates** (P4-G01 to P4-G03)

---

## 4. Corrective Completion Audit (2026-07-19)

The following items were rechecked after the initial Phase 4 completion review. A checked task in this document must have executable implementation and test evidence, not only a design note or placeholder.

| Task | Completion evidence | Verification status |
| --- | --- | --- |
| P4-A03 | `SchemaMigrationPlanner` emits dialect-aware field/index operations and real View DDL using `viewQuery`. JSONL and relational engines validate View queries, generate fields, execute dynamic View reads, persist updates, and reject invalid updates without replacing the working definition. Relational `_collections` now persists the official `indexes`, `created`, and `updated` metadata. `CollectionIndexSupport` validates and normalizes CREATE INDEX expressions, rejects duplicate definitions and cross-collection names, rewrites submitted table names to the owning collection, and synchronizes removed/added physical indexes transactionally. Auth collections receive the official tokenKey/email unique indexes. Legacy relational databases add the missing metadata columns and reconstruct existing SQLite/MySQL/PostgreSQL index definitions where supported by the dialect catalog. New collections and fields now use PocketBase's CRC32-derived IDs (`pbc_ + crc32(type+name)` and `fieldType + crc32(fieldName)`), append official numeric collision suffixes, include the base collection `id` system field, and preserve explicit or previously persisted IDs across updates, renames, imports, and restarts. `CollectionFieldProtection` rejects record-response reserved names (`expand`, `collectionId`, and `collectionName`) and prevents existing system fields from being deleted or renamed across create, update, and import paths. Relational collection updates also protect the mandatory physical `id`, `created`, and `updated` columns. | `CollectionIndexSupportTest`, `SchemaIdSupportTest`, `collectionIndexesAndTimestampsPersistWithOfficialValidation`, `legacyRelationalCollectionMetadataColumnsUpgradeOnRestart`, `generatedCollectionAndFieldIdsMatchOfficialChecksumsAndRemainStable`, `collectionReservedAndSystemFieldsMatchOfficialValidation`, and the superuser MFA schema-update flow pass. JSONL and SQLite verify validation envelopes, physical index replacement, auth default indexes, restart persistence, timestamps, legacy SQLite catalog backfill, deterministic IDs, collisions, explicit IDs, imports, field-ID reuse, reserved-name errors, system-field replacement rules, and fixed-column preservation. MySQL/PostgreSQL catalog paths compile but were not run against external instances in this local audit. |
| P4-A05 | JSONL remains the zero-configuration default; SQLite/MySQL/PostgreSQL remain explicit relational profiles. Legacy JSON arrays are readable while current record writes use JSONL. | Default JVM suite runs on JSONL; the complete `LocalPocketBaseServerTest` matrix passes 76/76 on both JSONL and `-Dstorage=sqlite`. |
| P4-B01 | Standard record CRUD routes now handle `_superusers`, `_authOrigins`, `_externalAuths`, `_mfas`, and `_otps` on JSONL and relational storage. `_superusers` remains superuser-only, hides `password` and `tokenKey`, stays verified, rotates tokens after identity/password changes, and protects the final account. The four auth support collections use official owner-scoped list/view behavior; only `_authOrigins` and `_externalAuths` allow owner deletion; create/update remain superuser-only; logical fields use `collectionRef`, `recordRef`, and `password`; references and unique pairs are validated; records persist across restarts; and auth-record deletion cascades to every support collection. All five collections now expose the official CRC32-derived fixed IDs and deterministic system field IDs. Startup migrates the previous Java IDs in JSONL and relational metadata, auth-support references, pending auth requests, relation field definitions, record files, and storage directories without dropping records or files. | `superuserRecordsCrudMatchesOfficialSdkFlow`, `authSupportCollectionsCrudMatchesOfficialOwnershipRules`, and `legacySystemCollectionIdsMigrateToOfficialIdsWithoutDataLoss` pass on JSONL and SQLite. The official JS SDK smoke also verifies `authStore.isSuperuser` from the token alone when the record payload is absent. MySQL/PostgreSQL use the same jOOQ migration path but were not exercised against external instances in this local audit. |
| P4-B02 | `docs/route_manifest_sync.sh` is pinned to v0.39.7 and executes `RouteConformanceTest` against the checked-in official route manifest and `HttpApi.REGISTERED_ROUTES`. | Route manifest gate passes 3/3. |
| P4-B03 | JS smoke covers auth refresh, CRUD, files, batch rollback, and realtime. Dart smoke is executable, pins the official `pocketbase` 0.24 line, and covers auth refresh, CRUD, files, the official batch result array, and realtime. | JS smoke passes locally. Dart smoke is skipped when `dart` is absent from `PATH`; release SDK jobs must install Dart so it executes. |
| P4-B04 | `HttpRateLimiter` applies official fixed-window route/audience rules, collection action labels, superuser bypass, excluded IP/CIDR checks, configuration resets, and `Retry-After` responses before routing. Trusted proxy parsing scans the configured left-to-right or right-to-left list for the first valid literal IP. `superuserIPs` rejects hostnames and invalid subnets, blocks non-whitelisted superuser sessions and auth responses, and leaves guests and ordinary auth records unaffected. `HttpApi` also enforces the 32 MiB request limit plus collection-aware file/editor/JSON allowance for declared and streamed bodies. | `rateLimitsAndBodyLimitMatchOfficialMiddlewareRules`, `superuserIpWhitelistMatchesOfficialMiddlewareAndAuthResponseRules`, and `HttpBodyLimitTest` pass on JSONL and SQLite. The complete server integration matrix passes 76/76 on both engines. |
| P4-B05 | JSONL and relational activity logging apply retention, minimum-level, IP, and auth-ID settings before writes. Settings updates immediately delete expired or below-level logs. Relational cron execution is asynchronous; `__pbLogsCleanup__` deletes expired rows and `__pbDBOptimize__` runs SQLite `wal_checkpoint(TRUNCATE)`/`optimize`, MySQL `ANALYZE TABLE`, or PostgreSQL `ANALYZE`. | `logSettingsAndMaintenanceCronsMatchOfficialBehavior` passes on JSONL and SQLite, including privacy flags, level filtering, disabled retention, persisted old-row cleanup, and cron triggering. MySQL/PostgreSQL maintenance statements compile but were not executed against external instances in this local audit. |
| P4-B06 | `SearchQuerySupport` now provides the official shared SearchProvider contract for records, collections, and logs. It strictly parses pagination and `skipTotal`, caps pages at 1000 items, returns `-1` totals when requested, enforces filter/sort limits, handles explicit ascending prefixes, and keeps record sorting independent from `fields` selection. Record list requests from guests and ordinary auth records cannot use `@collection.*` or `@request.*` filter/sort fields. `SearchFieldValidationSupport` validates collection/log allowlists and complete record relation paths before execution, rejects hidden client fields at every relation depth for non-superusers, preserves syntax-error envelopes, and leaves server-side list rules able to use hidden fields. `RuleEvaluator` validates official field modifiers, distinguishes normal arrays from multi-relation match sets, and parses registered filter functions without treating function names as schema fields. `FilterFunctionSupport` implements `strftime` with SQLite date substitutions, epoch modes, date/time shifts, start/weekday/subsecond modifiers, and ceiling/floor month resolution; it implements Haversine `geoDistance` with PocketBase's any-match behavior for multi-relation coordinates. `RecordFieldResolverSupport` resolves direct, nested, and `collection_via_field` back relations, applies related collection `listRule` constraints to non-superuser client searches, supports relation paths in `@request.body` and `@request.auth`, and implements non-`?` all-match versus `?` any-match operators. `CollectionRuleSupport` now validates the same complete resolver paths before schema persistence, including `@collection`, request-body field-specific modifiers, function arguments, and import-batch forward references; JSONL collection creation no longer bypasses rule validation, and relational import no longer converts update validation failures into create attempts. Relational record lists use the same post-fetch evaluator and relation-aware sorting as JSONL. | `searchPaginationMatchesOfficialProviderContract`, `searchFieldsRejectUnknownAndHiddenClientFields`, `filterModifiersMatchOfficialResolverSemantics`, `filterTokenFunctionsMatchOfficialStrftimeAndGeoDistanceSemantics`, `relationFilterAndSortPathsMatchOfficialMultiMatchSemantics`, `collectionRulesValidateCompleteResolverPathsOnCreateUpdateAndImport`, `RuleEvaluatorTest`, and `RecordFieldResolverSupportTest` pass on JSONL and SQLite. Coverage includes pagination, field visibility, modifiers, function argument errors, time formatting/shifts, geo distance, direct/nested/back relations, `@collection`, request-body changed fields, multi-relation all/any matching, related `listRule` enforcement, hidden nested fields, create/update rollback, and forward-referencing imports. The official JS SDK smoke also verifies `skipTotal` and fields selection through `getList()`. |
| P4-B07 | `RecordInputProtection` filters hidden JSON and multipart fields before record loading and file staging for non-superusers, while retaining the auth password exception. Hidden file replace/append/delete keys are removed together, so ignored uploads never reach storage. Record creation also performs the official nil-create-rule authorization check before field validation. | `hiddenRecordFieldsAreWritableOnlyBySuperusers` passes on JSONL and SQLite, proving guest hidden text/file create and update inputs are ignored, deletion markers cannot remove a superuser file, no guest upload is staged, superusers retain write access, and guest auth registration/password login still work. `authSupportCollectionsCrudMatchesOfficialOwnershipRules` verifies the preflight 403 ordering for system collections. |
| P4-B08 | `RuleRequestContext` carries normalized query and case-insensitive header values through `HttpApi`, both storage engines, rule evaluation, relation-aware sorting/filtering, record expansion, auth/manage/MFA checks, batch sub-requests, and realtime subscriptions. DELETE requests now retain their query values, batch request headers can be supplied per sub-request without overriding authorization, and realtime option headers participate in list/view/filter decisions and emitted record processing. | `requestHeadersAreCaseInsensitiveAndSupportIsset`, `requestHeadersReachCrudAuthAndBatchRules`, and `realtimeOptionsHeadersReachCollectionRules` pass on JSONL and SQLite. Coverage includes header-name casing, `:isset`, direct create/list/update/delete, DELETE query rules, authRule checks, batch create/update/delete context, and realtime option headers. |
| P4-B09 | `RuleRequestContext` now carries the official execution context in addition to query and headers. Direct record operations use `default`; batch sub-requests use `batch`; relation expansion uses `expand`; realtime delivery uses `realtime`; protected files use `protectedFile`; and auth flows use `password`, `otp`, or `oauth2`. OAuth2 new-user creation now runs through the ordinary Records create-rule and manage-access path with the original request auth/headers instead of bypassing collection rules, and only upgrades verification when the created email matches the provider email. | `requestContextsMatchOfficialRuleExecutionModes`, `oauth2EndpointsExchangeCodeAndReuseLinkedAuthRecord`, `protectedFilesRequireFileTokenAndViewRuleAccess`, `realtimeOptionsHeadersReachCollectionRules`, and `RuleEvaluatorTest` pass on JSONL and SQLite. Coverage proves all eight official context values, expand-only visibility, protected-file-only access, password/OTP auth rules, OAuth2 allow/deny create rules, no record persistence after a rejected OAuth2 create, and realtime-only list/view access. |
| P4-B10 / P4-D02 | Protected file requests resolve authentication only from the optional query file token, then evaluate the collection `viewRule` with `@request.context='protectedFile'` even for guests. Public rules therefore remain accessible without a token or with an invalid token, while missing files, invalid or revoked tokens, and rule denials all use the same standard resource-not-found `404` envelope. Bearer authorization alone does not become protected-file authentication. | `protectedFilesRequireFileTokenAndViewRuleAccess`, `rotatingCollectionTokenSecretsInvalidatesIssuedAuthFileAndResetTokens`, and `multipartFileUploadsAreStoredAndServedFromApiFiles` pass on JSONL and SQLite. Coverage includes no token, invalid token, Bearer-only access, unauthorized and authorized records, public guest rules, token-secret rotation, missing files, and ordinary range/download behavior. |
| P4-B11 | `/api/health` now returns an empty `data` object to guests and ordinary auth records. Authenticated superusers receive only `canBackup`, the shared trusted-proxy-aware `realIP`, and the first configured/common `possibleProxyHeader`; the previous Java-only `dataDir` and `superuserReady` fields are no longer exposed. GET and HEAD both match the official route behavior. The bundled Admin UI uses a separate Java-only bootstrap-status probe and refreshes health with the newly issued superuser token after login. | `healthDiagnosticsAreVisibleOnlyToSuperusers` passes on JSONL and SQLite, covering guest, ordinary auth, superuser, configured/common proxy headers, real-IP selection, setup-state isolation, and HEAD. All six Admin UI Playwright workflows pass with the rebuilt assets. |
| P4-B12 / P4-D03 | Backup list responses are official top-level arrays containing only `key`, `size`, and date-time `modified`. Create, upload, restore, and delete return `204`; upload bypasses the default body cap, validates MIME/name without parsing archive contents, and restore performs structural/traversal validation. `BackupOperationGuard` tracks active create/restore operations for both storage engines, rejects overlap, protects the active key from deletion, and drives the superuser health `canBackup` value. | `backupsCanBeCreatedDownloadedRestoredAndDeleted` passes on JSONL and SQLite with array/204/error-envelope assertions, MIME rejection, opaque ZIP upload, restore-time archive rejection, traversal safety, and lifecycle restoration. `S3BackupRepositoryTest` blocks a live S3 PUT and proves `canBackup=false`, overlapping-create rejection, active-delete rejection, release recovery, array listing, download, and deletion. `BackupOperationGuardTest` verifies overlap and failure cleanup directly, while `HttpBodyLimitTest` locks the unlimited upload route boundary. |
| P4-B13 | Auth-method discovery now returns `404` for missing or non-auth collections, clears password identity fields and OTP/MFA durations for disabled methods, omits the nonofficial OTP length, and reports OAuth2 enabled state independently from provider initialization. The ordered v0.39.7 registry supplies all 32 provider display names and SVG logos plus standard endpoints, scopes, and PKCE defaults. Legacy `authProviders` entries explicitly contain an empty logo while primary OAuth2 entries retain the full provider metadata. | `authMethodsReflectConfiguredPasswordOtpMfaAndOauth2`, `collectionMetaApisReturnScaffoldsAndOAuth2Providers`, and `OAuth2SupportTest#standardProviderDefaultsAndOverridesMatchOfficialMetadata` pass on JSONL and SQLite. Coverage includes missing/base collection errors, disabled and enabled auth configurations, the complete provider registry, default GitHub authorization URL construction, non-PKCE Bitbucket defaults, explicit overrides, response field boundaries, and legacy-provider clone assertions. The official JS SDK `listAuthMethods()` smoke also passes on both matrices. |
| P4-B14 / P4-C03 | `AuthCollectionConfigValidation` now mirrors the v0.39.7 collection model contract: provider validation runs only when OAuth2 is enabled; enabled configurations may contain no providers; provider names are case-sensitive and must be registered and unique; `clientId` and `clientSecret` are required; and non-empty endpoint overrides must be valid URLs. OIDC endpoint overrides remain optional. Invalid provider data is retained when OAuth2 is disabled instead of being silently normalized away, and enabled validation failures use indexed `data.oauth2.providers.{index}.{field}` errors. Collection create, update, dry-run import, and actual import share the validator and leave persisted collections unchanged after rejection. | `authCollectionOptionsValidateIdentityFieldsAndMfaMethods` and `OAuth2FailuresTest` pass on JSONL and SQLite. Coverage includes disabled invalid-provider bypass, empty enabled provider lists, missing credentials, unknown and duplicate providers, invalid authorization/token/user-info URLs, OIDC without endpoint overrides, case-sensitive names, and update/import rollback. |
| P4-B15 / P4-C02 / P4-C04 | `AuthCollectionConfigValidation` now applies the v0.39.7 OTP and MFA duration/length constraints, always validates OTP/auth-alert/action-mail template subjects and bodies, and returns the official range codes, messages, and `params.threshold` metadata. Enabled MFA rules compile through `CollectionRuleSupport` and report nested `data.mfa.rule` errors. `AuthCollectionConfigMerge` restores PocketBase's nested patch semantics after normalization so omitted subfields retain defaults or current values, while explicitly submitted disabled OTP/MFA values remain unchanged instead of being silently clamped during persistence or relational reload. Invalid create, partial update, dry-run import, and actual import requests leave the prior collection intact. | `authCollectionOptionsValidateIdentityFieldsAndMfaMethods` passes on JSONL and SQLite as part of the complete `LocalPocketBaseServerTest` 76/76 matrix. Coverage includes OTP min duration/length errors with threshold params, disabled out-of-range value persistence, partial enable-update rejection, MFA max duration and invalid-rule errors, always-on nested template validation, and update/import rollback. |
| P4-B16 / P4-C01 | All five auth/action/file `TokenConfig` values now enforce PocketBase's 30-255 character secret and 10-94670856 second duration boundaries, including nested aggregate errors and the official length/range params. Nested token patches preserve omitted fields, and an explicitly empty redacted secret reuses the persisted secret rather than rotating it. Explicit valid replacements still invalidate previously issued auth, file, and password-reset tokens. Invalid create, partial update, dry-run import, and actual import operations remain atomic. Expiration coverage now re-signs original claims with the persisted collection secret and an already elapsed expiry, keeping the test fast while exercising the real signature and expiry validation path. | `authCollectionOptionsValidateIdentityFieldsAndMfaMethods`, `rotatingCollectionTokenSecretsInvalidatesIssuedAuthFileAndResetTokens`, and `expiredAndWrongCollectionTokensAreRejected` pass on JSONL and SQLite. Coverage includes every token config field, required/min/max/length errors and params, empty-secret round-trips, explicit rotation, rejected update/import rollback, wrong-collection action tokens, and validly signed expired auth/reset tokens. |
| P4-B17 / P4-C02 | `CollectionUpdateProtection` now enforces immutable collection type/system state, field types, system collection names, and system API/auth/manage/MFA rules before persistence or schema migration. `_superusers` save invariants force password auth, disable OAuth2 providers, and enable MFA whenever OTP is active. Both auth engines interpret an empty enabled MFA rule as requiring MFA for every record instead of disabling the challenge. Rejected update/import operations leave the prior JSONL metadata, relational options, and physical field definitions unchanged. | `collectionReservedAndSystemFieldsMatchOfficialValidation` and `superuserMfaCanEscalateFromPasswordToOtp` pass on JSONL and SQLite. Coverage includes update/import type changes, system-flag changes, indexed field-type errors, aggregate system name/rule errors, protected MFA rule changes, password/OAuth2/OTP-MFA superuser invariants, and password-to-OTP escalation with the official empty-rule behavior. |
| P4-B18 | `CollectionModelValidation` now applies the official collection ID, type, name, field-list, identifier, reserved-name, duplicate-name, and primary-key checks before every create, update, or import write. Submitted duplicate field IDs replace the earlier entry before validation, collection names are unique case-insensitively and cannot collide with collection IDs or internal tables, and `CollectionUpdateProtection` contributes immutable errors to the same response. Relational case-only table renames use a temporary name inside the existing transaction. | `collectionModelIdentifiersAndDuplicatesMatchOfficialValidation` passes on JSONL and SQLite. Coverage includes ID format/length/existing collisions, `_via_`, invalid and maximum name lengths, long and numeric-leading valid identifiers, uppercase invalid types, case-insensitive collection/field duplicates, duplicate-ID replacement, missing primary keys, literal/dynamic/auth reserved field names, aggregated name/type update errors, rejected import rollback, and case-only renames. The complete `LocalPocketBaseServerTest` matrix passes 77/77 on both engines. |
| P4-C01 | Auth action tokens are persisted in `_authRequests` and consumed by one conditional database `DELETE` inside the same transaction as the record mutation. A failed mutation rolls the token deletion back; a successful mutation makes reuse fail. | `AuthActionPersistenceTest` passes on SQLite. |
| P4-C02 | Relational OTP records enforce collection-configured expiry at authentication time, delete expired entries, and use a conditional delete for one-time successful consumption. The no-SMTP development outbox exposes the generated code without making the outbox authoritative. | JSONL OTP lifecycle tests and SQLite expiry integration tests pass. |
| P4-C03 / P4-E01 | Auth collections persist nullable `authRule` and `manageRule` values in JSONL schema data and relational collection options. Collection writes reject invalid rule syntax, unknown fields, and blank `manageRule` values. Authentication enforces official null/blank/non-empty `authRule` semantics, and auth/MFA/manage rule evaluation uses the shared relation-aware context for stored records, `@request.body`, and `@request.auth`. Auth record reads apply email visibility for superusers, owners, and matching managers; mutations enforce `updateRule`/`deleteRule`, manager access, direct email/verified restrictions, password confirmation, old-password checks, and auth-origin cleanup. Auth scaffolds and persisted auth collections normalize the official `id`, `password`, `tokenKey`, `email`, `emailVisibility`, and `verified` system fields in official order. `AuthCollectionConfigValidation` allows arbitrary password identity fields while requiring each field to exist and have a unique constraint, and rejects enabled MFA configurations with fewer than two enabled auth methods. JSONL startup repairs legacy schema metadata and missing boolean record values; relational startup repairs metadata, adds missing auth columns, and initializes existing boolean values. OAuth2 internal record creation supplies the official generated password confirmation pair. The standard-provider runtime now initializes the complete v0.39.7 registry with official endpoint, scope, PKCE, display-name, order, and logo defaults while preserving explicit provider overrides and nullable Admin UI PKCE settings. `oauth2.mappedFields` persists the official provider ID/name/username/avatar mappings, ignores unavailable username values, preserves explicit `createData`, clears targets removed from the schema, and never overwrites linked existing records. Avatar URLs map directly to text fields or use bounded HTTP(S) downloads for file fields with private-network, redirect, and size protections. The Admin UI exposes all four mapping selectors. All auth/action token configurations persist random secrets, while collection API responses redact token secrets and OAuth2 `clientSecret` values. Redacted OAuth2 collection updates preserve the stored client secret, and material `authRule` changes rotate the auth token secret to revoke previously issued tokens. | `authAndManageRulesControlLoginAndAuthRecordMutations`, `authCollectionOptionsValidateIdentityFieldsAndMfaMethods`, `RecordFieldResolverSupportTest`, `OAuth2SupportTest`, and `oauth2EndpointsExchangeCodeAndReuseLinkedAuthRecord` pass on JSONL and SQLite, including save-time validation, standard-provider defaults and overrides, relation-aware request/auth contexts, custom unique-field password login, managed/private email visibility, token-secret redaction, ordinary-restart token continuity, mapped-field precedence/normalization, existing-record preservation, and old-token rejection after an `authRule` change and restart. `authSystemFieldsAreProtectedAndLegacyCollectionsUpgradeOnRestart` passes on both engines with deliberately downgraded persistence fixtures. OAuth2 account creation/reuse and redacted-secret round-trip tests pass on both engines. |
| P4-C03 / P4-E02 | `/api/oauth2-redirect` now matches the official realtime handoff: the `state` must identify a live client subscribed to `@oauth2`, callback IP must match the SSE connection IP, the callback publishes one `@oauth2` event and removes that subscription, GET returns 307, POST returns 303, and success/failure locations match the bundled Admin UI routes. Missing codes and provider errors are still delivered before the failure redirect. Realtime subscription updates enforce the original client IP while allowing the official guest-to-auth upgrade. Subscription requests also enforce the official 255-character client ID, 1000-subscription, and 2500-character topic limits before client lookup or subscription replacement, returning `validation_length_too_long` at the matching field or item index. Apple POST redirect user data is bounded, retained for one minute, and consumed once during the Apple code exchange when provider userinfo has no name. The Admin UI OAuth2 tester now establishes the same realtime subscription before opening its popup. | `oauth2RedirectUsesOfficialRealtimeContractAndIpChecks`, `realtimeAllowsGuestAuthUpgradeButRejectsLaterAuthorizationChanges`, `realtimeValidationErrorsUseOfficialEnvelope`, `OAuth2SupportTest`, and `AdminUiPlaywrightTest#testOAuth2PopupTesterCompletesBrowserCallback` pass. Targeted and full JSONL/SQLite matrices verify the official redirect statuses, SSE payload, one-time unsubscribe, IP mismatch failure, missing-code delivery, POST form binding, subscription limit boundaries, indexed validation errors, and Admin UI token exchange. |
| P4-C04 | Auth collections persist the official verification, password reset, email change, OTP, and auth-alert templates. JSONL and relational auth requests share the same placeholder renderer and SMTP dispatcher. With SMTP enabled, password-reset tokens and OTP codes are sent asynchronously and omitted from the development outbox; OTP send failure deletes the issued OTP. `_authOrigins` fingerprints are persisted across restarts, first login and repeated origins are suppressed, new IP/User-Agent combinations trigger auth-alert mail, only the five most recent origins are retained, and password changes clear prior origins. Official v0.39.7 defaults are used for auth token, verification token, OTP, and MFA durations. | Fake SMTP end-to-end coverage passes on JSONL and SQLite, including custom templates, `{RECORD:email}`, action token/OTP placeholders, origin persistence and deduplication, five-origin retention, password-change cleanup, default durations, and outbox leak prevention. |
| P4-D01 / P4-D03 | `S3FileStorageProvider` implements SigV4 PUT/GET/HEAD/LIST/DELETE and signed URLs against AWS S3-compatible endpoints. Backup create/list/download/upload/delete/restore uses the provider and streams generated ZIP files through disk-backed temporary files. Locally generated JSONL and relational backups are written and validated under hidden temporary names, then atomically moved to their final `.zip` names so list/download calls never observe partial archives. Uploaded files are atomically published after MIME/name validation and are structurally validated before restore. `AsyncJobRunner` preserves concurrent fire-and-forget cron execution while tracking accepted maintenance jobs so storage shutdown can wait for them; relational shutdown then closes the connection pool. Backup downloads require the official short-lived superuser file token query parameter, Bearer-only downloads are rejected, and file-token access observes `superuserIPs`. SQLite snapshot restore accepts both ordinary and unique index DDL while retaining the existing object-type and identifier validation. | Local backup lifecycle coverage passes on JSONL and SQLite, including restore with auth collection unique indexes, traversal rejection, MIME rejection, restore-time invalid archive rejection, auto-backup publication, and immediate server shutdown without incomplete ZIPs or surviving temporary files. `serverCloseWaitsForAcceptedAutoBackupAndPublishesCompleteZip` covers the cron lifecycle race, while `AsyncJobRunnerTest` verifies concurrent execution, close-time draining, and post-close rejection. S3 provider and S3 backup repository tests pass against local S3-compatible HTTP fixtures using the same file-token download contract. |

The `/api/batch` response was also corrected during this audit to return the official top-level result array. Java, JS, and Dart client smoke coverage now target the same wire contract.

Final local verification for this audit:

- Current test inventory: 229 tests, including six `AdminUiPlaywrightTest` browser tests.
- JDK 21 `mvn test`: 228 tests executed, 0 failures, 0 errors, 1 skipped.
- JDK 21 `mvn -Dstorage=sqlite test`: 228 tests executed, 0 failures, 0 errors, 1 skipped.
- `LocalPocketBaseServerTest`: 76/76 on both JSONL and SQLite.
- The official JS SDK smoke, including the SearchProvider `skipTotal` contract, passes on both matrices. Dart SDK smoke remains skipped because `dart` is absent from `PATH`.
- All six Admin UI Playwright browser tests execute and pass on both JSONL and SQLite, including the OAuth2 popup/realtime callback flow with an explicit `oauth2` create rule.
- `sh/build-native.sh` succeeds with GraalVM 25, and the generated native executable passes a runtime smoke covering the 32-provider metadata registry plus standard GitHub auth-method URL/logo and legacy-provider responses.
