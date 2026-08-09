# pocketbase-java

PocketBase Java implementation. This project contains a lightweight **PocketBase Java SDK** and a low-dependency **Embedded Server**: using JDK `HttpServer` to serve PocketBase-like APIs, featuring a built-in Admin UI, JSON file persistence, and designed for GraalVM Native Image constraints.

**Official PocketBase Baseline:** v0.39.10

<p align="center">
    <a href="https://github.com/jackBaozz/pocketbase-java/actions/workflows/ci.yml" target="_blank" rel="noopener">
        <img src="https://github.com/jackBaozz/pocketbase-java/actions/workflows/ci.yml/badge.svg" alt="CI Status" />
    </a>
    <a href="https://github.com/jackBaozz/pocketbase-java/releases" target="_blank" rel="noopener">
        <img src="https://img.shields.io/github/v/release/jackBaozz/pocketbase-java?label=release" alt="Latest release" />
    </a>
</p>

---

> [!WARNING]
> Please keep in mind that `pocketbase-java` is still under active development, is not a line-by-line port of the official Golang PocketBase, and full backward compatibility is not guaranteed before reaching v1.0.0.

---

## Key Features

- **Low Dependency**: HTTP services are built using `java.net.http.HttpClient` and JDK built-in `HttpServer`. The core runtime has extremely minimal dependencies, keeping the resource footprint small and native compilation trivial.
- **Standard API Parity**: Strictly aligns with the official PocketBase REST API specifications (up to **v0.39.10**) (e.g. `/api/collections/{collection}/records`, auth with password/OTP/MFA/OAuth2 flows, impersonate, view queries, rate limiting, and client IP rules).
- **Embedded Server**: Provides `io.github.jackbaozz.pocketbase.server.PocketBaseServer` to spin up a local PocketBase-like service directly without relying on heavy frameworks like Spring/Tomcat.
- **Built-in Admin UI**: Access `/_/` for superuser initialization, login, collection/record management, auth collection OTP/MFA/OAuth2 configuration, file uploads, backups, system configuration editing, and activity logs. Features 9-language i18n, an API documentation sidebar, a schema/index editor, a relation picker, a code editor with syntax highlighting and autocompletion, and a `hideControls` safe-lock mode. The frontend source is in `UI/`, and its build outputs are embedded into Java resources.
- **Storage Engine Matrix**: Features a flexible `StorageEngine` SPI. SQLite is the default local engine and stores data in `<server.data-dir>/pocketbase.db`; legacy JSON Lines (`.jsonl` plus `.json` metadata) remains available explicitly with `storage.type=jsonl`. MySQL and PostgreSQL are also supported through `application.properties`, the JVM `-Dstorage` flag, or native-runtime `PB_STORAGE` environment variable, powered by jOOQ and HikariCP.
- **File Management & S3 Support**: Supports local or AWS S3-compatible file storage providers (`FileStorageProvider` SPI). Handles multipart uploads, size/MIME constraints, Protected File Tokens, and automatic image thumbnail generation.
- **Backup & Restore**: Supports creating, uploading, downloading, deleting, and restoring zip backups under local storage or remote S3 backup paths, complete with transaction safety and automatic cleanups.
- **Mail Delivery (SMTP)**: Integrates SMTP client delivery supporting SSL/TLS, custom templates with variable substitutions, and a dry-run/local outbox logger for developer testing.
- **Security Basics**: Superuser and auth record passwords are hashed using PBKDF2. Auth token generation and verification are signed using HMAC-SHA256.
- **Realtime (SSE)**: Supports `/api/realtime` Server-Sent Events (SSE) connections, record-level subscriptions, official `subscriptions[]`/`options.query` format, `filter`/`expand`/`fields` parameters, and enforces collection access rules for visible records.
- **Batch API**: Supports atomic batch record create/update/upsert/delete. Automatically rolls back all database modifications and storage files if any sub-request fails.
- **SQL API**: A superuser-only `POST /api/sql` endpoint. Supports executing queries directly with transaction support and database dialect normalization.
- **GraalVM Native Image Ready**: Designed from the ground up without dynamic proxies or complex reflection. JDBC drivers, jOOQ templates, S3 clients, and Jackson configurations are fully registered and validated for native executable packaging.

---

## Requirements

| Category | Requirement |
| --- | --- |
| JDK | 17+ |
| Maven | 3.9+ |
| Node.js / npm | 20.19+ / 10+ (only required if modifying/rebuilding the Admin UI) |
| GraalVM | GraalVM JDK 17+ / 21+ (only required if building native binaries) |

If access to the Maven Central repository is unstable in your network, you can use the built-in configuration mirroring file:
```bash
mvn -gs settings.xml -s settings.xml test
```

---

## Quick Start

### 1. Run as Standalone App

Compile the project and start the server:
```bash
mvn -gs settings.xml -s settings.xml clean package
java -jar target/pocketbase-java-0.3.5-all.jar start --http 127.0.0.1:8090 --dir pb_data
```

The `start` command is optional when it is the only operation:

```bash
java -jar target/pocketbase-java-0.3.5-all.jar
```

Once started, open:
- **Admin UI**: http://127.0.0.1:8090/_/
- **Health API**: http://127.0.0.1:8090/api/health

You can also bootstrap the first superuser via environment variables:
```bash
PB_SUPERUSER_EMAIL=root@example.com \
PB_SUPERUSER_PASSWORD=secret123 \
java -jar target/pocketbase-java-0.3.5-all.jar start
```

### Configuration file

The server supports an optional UTF-8 `application.properties` file. Copy
`config/application.properties.example` to `config/application.properties`; the file is
loaded automatically from the `config/` directory (with a root-level file kept as a
convenience fallback). Use `--config <path>` or `PB_CONFIG_FILE` to select another file.

```properties
app.name=My PocketBase App
server.host=127.0.0.1
server.port=8090
server.data-dir=pb_data
storage.type=sqlite
```

Then start the server normally:

```bash
java -jar target/pocketbase-java-0.3.5-all.jar start
```

For SQLite, the database file is created automatically at
`<server.data-dir>/pocketbase.db`; no separate SQLite service is required.
For MySQL or PostgreSQL, additionally set `database.url`, `database.user`, and
`database.password`. Command-line options take precedence over JVM properties,
which take precedence over environment variables and the properties file.
Changing the storage engine does not automatically migrate existing JSONL data. Back up the
JSONL directory and complete an explicit export/import before switching an existing deployment;
the checked-out local `pb_data` directory has already been migrated to SQLite and retains its
original JSONL files for rollback.

### Runtime profiles

Without adding Spring Boot, the server supports Spring Boot-style profile file names. The base
file is loaded first and `application-<profile>.properties` then overrides it:

```text
config/application.properties
config/application-dev.properties
config/application-test.properties
config/application-production.properties
```

Copy the tracked templates before editing an environment-specific file:

```bash
cp config/application-dev.properties.example config/application-dev.properties
cp config/application-test.properties.example config/application-test.properties
cp config/application-production.properties.example config/application-production.properties
```

Select a profile at runtime with one of the following (highest priority first):

```bash
java -jar target/pocketbase-java-0.3.5-all.jar start --profile dev
java -Dapp.profile=dev -jar target/pocketbase-java-0.3.5-all.jar start
PB_PROFILE=dev java -jar target/pocketbase-java-0.3.5-all.jar start
```

You may also set `app.profile=dev` in the base properties file. Maven profiles are build-time
only; `mvn -Pdev package` does not select a profile when the resulting JAR or native binary runs.
Use the runtime options above for both packaging modes. Profile names are limited to letters,
numbers, `_`, and `-` so they cannot escape the configuration directory.

### 2. Embed Programmatically in Java

You can add `pocketbase-java` as a jar dependency to your Java application and start it programmatically:

```java
import io.github.jackbaozz.pocketbase.server.LocalPocketBase;
import io.github.jackbaozz.pocketbase.server.ServerConfig;
import java.nio.file.Path;

public class App {
    public static void main(String[] args) throws Exception {
        // Use default configuration (127.0.0.1:8090, data dir pb_data)
        ServerConfig config = ServerConfig.defaults();
        
        // Or customize the options
        // ServerConfig config = new ServerConfig("127.0.0.1", 8090, Path.of("my_pb_data"), "admin@example.com", "password123");

        try (LocalPocketBase server = LocalPocketBase.start(config)) {
            System.out.println("pocketbase-java started on: " + server.baseUrl());
            System.out.println("Admin Dashboard: " + server.baseUrl() + "/_/");
            
            // Block the current thread to keep the server running
            Thread.currentThread().join();
        }
    }
}
```

### 3. Use Java SDK Client

`pocketbase-java` includes a built-in Java SDK client to interact with either this Java server or the official Go PocketBase server:

```java
import io.github.jackbaozz.pocketbase.client.PocketBaseClient;
import io.github.jackbaozz.pocketbase.client.RecordList;
import io.github.jackbaozz.pocketbase.client.ListOptions;
import java.util.Map;

// 1. Initialize the client
PocketBaseClient client = PocketBaseClient.builder("http://127.0.0.1:8090").build();

// 2. Authenticate as record/user (bearer token auto-managed afterwards)
client.collection("users").authWithPassword("demo@example.com", "password123");

// 3. Query records with options (supports filter, sort, expand, etc.)
RecordList posts = client.collection("posts").list(ListOptions.builder()
        .page(1)
        .perPage(20)
        .sort("-created")
        .filter("published = true")
        .expand("author")
        .build());

posts.items().forEach(item -> System.out.println(item.get("title").asText()));

// 4. Create a record
client.collection("posts").create(Map.of(
        "title", "Hello PocketBase from Java!",
        "published", true
));
```

### 4. Build Native Binary (GraalVM)

You can compile the project to a single VM-free native executable using GraalVM:

```bash
mvn -gs settings.xml -s settings.xml -Pnative -DskipTests package
./target/pocketbase-java start --http 127.0.0.1:8090 --dir pb_data
```

---

## Development Commands

```bash
# Run unit tests
mvn -gs settings.xml -s settings.xml test

# Build Admin UI and copy outputs to src/main/resources/pocketbase-admin/
(cd UI && npm install && npm run build)

# Install to the local Maven repository
mvn -gs settings.xml -s settings.xml clean install
```

---

## Project Structure

```text
pocketbase-java/
├── docs/                               # Documentation
├── UI/                                 # Admin UI React + Vite codebase
│   ├── package.json
│   ├── vite.config.ts
│   └── src/
├── src/
│   ├── main/java/io/github/jackbaozz/pocketbase/           # Java SDK sources
│   │   ├── AuthResponse.java
│   │   ├── PocketBaseClient.java
│   │   └── ...
│   ├── main/java/io/github/jackbaozz/pocketbase/server/    # Embedded Server sources
│   │   ├── PocketBaseServer.java
│   │   ├── LocalPocketBase.java
│   │   ├── ServerConfig.java
│   │   ├── internal/
│   │   └── model/
│   ├── main/resources/pocketbase-admin/                    # Frontend UI build outputs
│   └── test/java/io/github/jackbaozz/pocketbase/
│       ├── PocketBaseClientTest.java
│       └── server/LocalPocketBaseServerTest.java
├── pom.xml
└── settings.xml
```

---

## Embedded Server API Support

| Domain | Supported API Endpoint & HTTP Methods |
| --- | --- |
| **System** | `GET /api/health` |
| **Superusers** | `GET/POST /api/bootstrap/superuser`<br>`POST /api/admins/auth-with-password` *(Legacy Compatibility)*<br>`POST /api/collections/_superusers/auth-with-password` |
| **Collections** | `GET/POST /api/collections`<br>`GET/PATCH/DELETE /api/collections/{idOrName}`<br>`PUT /api/collections/import`<br>`DELETE /api/collections/{idOrName}/truncate`<br>`GET /api/collections/meta/scaffolds`<br>`GET /api/collections/meta/oauth2-providers`<br>`POST /api/collections/meta/dry-run-view` |
| **Records CRUD** | `GET/POST /api/collections/{collection}/records`<br>`GET/PATCH/DELETE /api/collections/{collection}/records/{id}` |
| **Files** | `GET /api/files/{collection}/{recordId}/{filename}`<br>`POST /api/files/token` |
| **Batch** | `POST /api/batch` |
| **Realtime SSE** | `GET/POST /api/realtime` |
| **Backups** | `GET/POST /api/backups`<br>`POST /api/backups/upload`<br>`GET/DELETE /api/backups/{key}`<br>`POST /api/backups/{key}/restore` |
| **Settings** | `GET/PATCH /api/settings`<br>`POST /api/settings/test/s3`<br>`POST /api/settings/test/email`<br>`POST /api/settings/apple/generate-client-secret` |
| **Logs** | `GET /api/logs`<br>`GET /api/logs/{id}`<br>`GET /api/logs/stats` |
| **Crons** | `GET /api/crons`<br>`POST /api/crons/{id}` |
| **SQL API** | `POST /api/sql` *(Superuser Only)* |
| **Auth APIs** | `GET /api/collections/{collection}/auth-methods`<br>`POST /api/collections/{collection}/auth-with-password`<br>`POST /api/collections/{collection}/auth-with-otp`<br>`POST /api/collections/{collection}/auth-with-oauth2`<br>`POST /api/collections/{collection}/auth-refresh`<br>`POST /api/collections/{collection}/request-otp`<br>`POST /api/collections/{collection}/confirm-password-reset`<br>`POST /api/collections/{collection}/request-password-reset`<br>`POST /api/collections/{collection}/request-verification`<br>`POST /api/collections/{collection}/confirm-verification`<br>`POST /api/collections/{collection}/request-email-change`<br>`POST /api/collections/{collection}/confirm-email-change`<br>`POST /api/collections/{collection}/impersonate/{id}`<br>`GET/POST /api/oauth2-redirect` |

---

## System Collections

On first startup, the server provisions the five internal collections below. They are part of the authentication subsystem and are maintained by the server; do not delete or change their collection definitions manually.

| Collection | Role | Related operations |
| --- | --- | --- |
| **`_superusers`** | Stores the accounts used to sign in to the Admin UI and call administrative APIs. | Admin UI login (`/_/`), superuser bootstrap, and `/api/collections/_superusers/auth-with-password`. |
| **`_authOrigins`** | Keeps recent login origins (IP address and device fingerprint) for auth records. When `authAlert` is enabled, it helps detect a new location and send an alert email. | Written after successful authentication and read by the authentication-alert flow. |
| **`_externalAuths`** | Maps an auth record to an external OAuth2 identity identified by its `provider` and `providerId`. | OAuth2 sign-in, account linking, and external-auth unlinking. |
| **`_mfas`** | Stores the short-lived MFA challenge created after password authentication when MFA is enabled. | Password authentication returns `mfaId`, followed by `request-otp` and `auth-with-otp`. |
| **`_otps`** | Stores short-lived one-time-password records. Entries are consumed during verification or removed after they expire. | `request-otp` issues a record; `auth-with-otp` verifies and consumes it. |

> Each collection has a stable built-in ID (for example, `_superusers` uses `pbc_3142635823`). Legacy identifiers such as `pbc_superusers` remain recognized for migration compatibility. The server creates and maintains these collections automatically; deleting an auth record also removes its related `_authOrigins`, `_externalAuths`, `_mfas`, and `_otps` records.

---

## License

This project is licensed under the [MIT](LICENSE) License.
