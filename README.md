# pocketbase-java

PocketBase Java implementation. This project contains a lightweight **PocketBase Java SDK** and a low-dependency **Embedded Server**: using JDK `HttpServer` to serve PocketBase-like APIs, featuring a built-in Admin UI, JSON file persistence, and designed for GraalVM Native Image constraints.

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

- **Low Dependency**: HTTP services are built using `java.net.http.HttpClient` and JDK built-in `HttpServer`. The runtime only imports Jackson for JSON handling, keeping dependencies and footprints extremely small.
- **Standard API Mapping**: Strictly aligns with `/api/collections/{collection}/records`, `auth-with-password`, and other official PocketBase REST API specifications.
- **Embedded Server**: Provides `io.github.jackbaozz.pocketbase.server.PocketBaseServer` to spin up a local PocketBase-like service directly without relying on Spring/Tomcat.
- **Built-in Admin UI**: Access `/_/` for superuser initialization, login, collection/record management, auth collection OTP/MFA/OAuth2 configuration, file uploads, backups, configuration editing, and activity logs. The frontend source is in `UI/`, and its build outputs are embedded into Java resources.
- **Local Persistence**: Stores everything in structured JSON files: `pb_data/pb_schema.json` for collection schemas, `pb_data/records/*.json` for records, and `pb_data/pb_settings.json` and `pb_data/logs.json` for settings and activity logs.
- **File Management & Thumbnails**: Supports `multipart/form-data` uploads. Files are stored under `pb_data/storage/{collectionId}/{recordId}` and are accessible via `/api/files/{collection}/{record}/{filename}`. Supports MIME/size checks, Protected File Token, and automatic image thumbnail generation.
- **Backup & Restore**: Supports creating, uploading, downloading, deleting, and restoring zip backups under `pb_data/backups`.
- **Security Basics**: Superuser and auth record passwords are hashed using PBKDF2. Auth token generation and verification are signed using HMAC-SHA256.
- **Realtime (SSE)**: Supports `/api/realtime` Server-Sent Events (SSE) connections, record-level subscriptions, official `subscriptions[]`/`options.query` format, `filter`/`expand`/`fields` parameters, and enforces collection access rules for visible records.
- **Batch API**: Supports batch record create/update/upsert/delete. Automatically rolls back all records and storage files if any sub-request fails.
- **SQL API**: A superuser-only `POST /api/sql` endpoint. Contains a lightweight SQL parser subset and supports transactions, avoiding heavy dependencies like JDBC/ORM.
- **GraalVM Friendly**: Avoids dynamic proxies. Core models use Java Record or plain classes. Reflection configuration is tightly limited by Jackson, facilitating GraalVM Native Image builds.

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
java -jar target/pocketbase-java-0.1.0-SNAPSHOT-all.jar serve --http 127.0.0.1:8090 --dir pb_data
```

Once started, open:
- **Admin UI**: http://127.0.0.1:8090/_/
- **Health API**: http://127.0.0.1:8090/api/health

You can also bootstrap the first superuser via environment variables:
```bash
PB_SUPERUSER_EMAIL=root@example.com \
PB_SUPERUSER_PASSWORD=secret123 \
java -jar target/pocketbase-java-0.1.0-SNAPSHOT-all.jar serve
```

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
import io.github.jackbaozz.pocketbase.PocketBaseClient;
import io.github.jackbaozz.pocketbase.RecordList;
import io.github.jackbaozz.pocketbase.ListOptions;
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
./target/pocketbase-java serve --http 127.0.0.1:8090 --dir pb_data
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
│   ├── API设计.md
│   └── 技术架构与开发规范.md
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
| **Superusers** | `POST /api/bootstrap/superuser`<br>`POST /api/collections/_superusers/auth-with-password` |
| **Collections** | `GET/POST /api/collections`<br>`GET/PATCH/DELETE /api/collections/{idOrName}`<br>`PUT /api/collections/import`<br>`DELETE /api/collections/{idOrName}/truncate`<br>`GET /api/collections/meta/scaffolds`<br>`GET /api/collections/meta/oauth2-providers` |
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

## License

This project is licensed under the [MIT](LICENSE) License.
