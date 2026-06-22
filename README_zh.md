# pocketbase-java

PocketBase 的 Java 实现。本项目包含一个轻量级的 **PocketBase Java SDK**，以及一个低依赖的 **嵌入式服务器 (Embedded Server)**：使用 JDK `HttpServer` 提供 PocketBase 风格的 API，内置 Admin UI，采用 JSON 文件持久化，特别面向 GraalVM Native Image 约束而设计。

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
> 请注意，`pocketbase-java` 目前仍处于活跃开发阶段，并非官方 Golang PocketBase 的逐行移植，在达到 1.0.0 版本之前，无法完全保证向后兼容性。

---

## 核心特性

- **低依赖**: HTTP 服务基于 `java.net.http.HttpClient` 与 JDK 内置 `HttpServer`，运行时仅引入 Jackson 用于 JSON 处理，保持极小的体积与侵入性。
- **标准 API 映射**: 完美对齐 `/api/collections/{collection}/records`、`auth-with-password` 等官方 PocketBase REST API 规范。
- **嵌入式服务器 (Embedded Server)**: 提供 `io.github.jackbaozz.pocketbase.server.PocketBaseServer`，可直接启动本地 PocketBase 风格服务，无需依赖 Spring/Tomcat。
- **内置 Admin UI**: 访问 `/_/` 即可使用超级管理员初始化、登录、集合/记录管理、文件上传、备份、配置编辑以及日志查看等功能；前端源码位于 `UI/`，构建产物内嵌至 Java resources。
- **本地持久化**: 以结构化的 JSON 文件存储。`pb_data/pb_schema.json` 存储集合结构，`pb_data/records/*.json` 存储记录，`pb_data/pb_settings.json` 和 `pb_data/logs.json` 存储配置与日志。
- **文件管理与缩略图**: 支持 `multipart/form-data` 上传，文件存储在 `pb_data/storage/{collectionId}/{recordId}` 下，通过 `/api/files/{collection}/{record}/{filename}` 进行访问；支持 MIME 类型与大小校验、Protected File Token 以及图片缩略图自动生成。
- **备份与还原**: 支持在 `pb_data/backups` 下创建、上传、下载、删除及还原 Zip 格式备份。
- **安全基础**: 超级管理员与 Auth 记录密码采用 PBKDF2 哈希，登录与 auth 刷新 Token 均基于 HMAC-SHA256 签名。
- **Realtime (SSE)**: 支持 `/api/realtime` Server-Sent Events 连接，支持记录级订阅、官方 `subscriptions[]`/`options.query` 格式、`filter`/`expand`/`fields` 选项，并复用 collection access rules 过滤可见记录。
- **Batch API**: 支持批量对记录进行 create/update/upsert/delete 操作，任何子请求失败时自动回滚整批记录和 storage 文件。
- **SQL API**: 超级管理员专用的 `POST /api/sql` 接口，内置轻量级 SQL 解析子集，支持标准的事务回滚，不引入 JDBC/ORM 等 native 负担。
- **GraalVM 友好**: 不使用动态代理，核心模型采用 Java Record/普通类，反射配置集中由 Jackson 限定，便于构建 GraalVM Native Image 二进制。

---

## 环境要求

| 类别 | 要求 |
| --- | --- |
| JDK | 17+ |
| Maven | 3.9+ |
| Node.js / npm | 20.19+ / 10+ （仅在需要修改或重新构建 Admin UI 时需要） |
| GraalVM | 构建 native 二进制时需要 GraalVM JDK 17+ / 21+ |

若本机直接访问 Maven 中央仓库不稳定，可使用项目内置镜像配置：
```bash
mvn -gs settings.xml -s settings.xml test
```

---

## 快速开始

### 1. 运行独立服务器 (Standalone App)

编译打包项目并启动服务：
```bash
mvn -gs settings.xml -s settings.xml clean package
java -jar target/pocketbase-java-0.1.0-SNAPSHOT-all.jar serve --http 127.0.0.1:8090 --dir pb_data
```

启动后可打开：
- **Admin UI 管理后台**: http://127.0.0.1:8090/_/
- **Health 检查 API**: http://127.0.0.1:8090/api/health

你也可以通过环境变量在启动时直接初始化超级管理员（superuser）：
```bash
PB_SUPERUSER_EMAIL=root@example.com \
PB_SUPERUSER_PASSWORD=secret123 \
java -jar target/pocketbase-java-0.1.0-SNAPSHOT-all.jar serve
```

### 2. 作为 Java 库嵌入使用 (Embedded Server inside Java)

你也可以将 `pocketbase-java` 作为普通 jar 包引入你的 Java 项目，并在代码中编程式启动：

```java
import io.github.jackbaozz.pocketbase.server.LocalPocketBase;
import io.github.jackbaozz.pocketbase.server.ServerConfig;
import java.nio.file.Path;

public class App {
    public static void main(String[] args) throws Exception {
        // 使用默认配置 (127.0.0.1:8090, 数据目录 pb_data)
        ServerConfig config = ServerConfig.defaults();
        
        // 或者自定义配置
        // ServerConfig config = new ServerConfig("127.0.0.1", 8090, Path.of("my_pb_data"), "admin@example.com", "password123");

        try (LocalPocketBase server = LocalPocketBase.start(config)) {
            System.out.println("pocketbase-java 已启动，监听地址: " + server.baseUrl());
            System.out.println("后台管理地址: " + server.baseUrl() + "/_/");
            
            // 阻塞当前线程以保持服务器运行
            Thread.currentThread().join();
        }
    }
}
```

### 3. 使用 Java SDK 客户端

`pocketbase-java` 内置了对接服务端（无论是 Java 版还是 Go 原版 PocketBase）的 Java SDK：

```java
import io.github.jackbaozz.pocketbase.client.PocketBaseClient;
import io.github.jackbaozz.pocketbase.client.RecordList;
import io.github.jackbaozz.pocketbase.client.ListOptions;
import java.util.Map;

// 1. 初始化客户端
PocketBaseClient client = PocketBaseClient.builder("http://127.0.0.1:8090").build();

// 2. 账号密码认证 (认证成功后，后续请求自动带上 Bearer Token)
client.collection("users").authWithPassword("demo@example.com", "password123");

// 3. 记录查询 (支持 filter, sort, expand 等参数)
RecordList posts = client.collection("posts").list(ListOptions.builder()
        .page(1)
        .perPage(20)
        .sort("-created")
        .filter("published = true")
        .expand("author")
        .build());

posts.items().forEach(item -> System.out.println(item.get("title").asText()));

// 4. 创建记录
client.collection("posts").create(Map.of(
        "title", "Hello PocketBase from Java!",
        "published", true
));
```

### 4. 构建 Native 二进制 (GraalVM)

你可以使用 GraalVM 将本项目编译成无 Java VM 依赖的单文件原生二进制：

```bash
mvn -gs settings.xml -s settings.xml -Pnative -DskipTests package
./target/pocketbase-java serve --http 127.0.0.1:8090 --dir pb_data
```

---

## 常用开发命令

```bash
# 运行单元测试
mvn -gs settings.xml -s settings.xml test

# 构建 Admin UI 并输出到 src/main/resources/pocketbase-admin/ 
(cd UI && npm install && npm run build)

# 安装到本地 Maven 仓库
mvn -gs settings.xml -s settings.xml clean install
```

---

## 项目结构

```text
pocketbase-java/
├── docs/                               # 技术文档
│   ├── API-Design.md
│   └── Technical-Architecture-and-Development-Standards.md
├── UI/                                 # Admin UI 前端工程 (React + Vite)
│   ├── package.json
│   ├── vite.config.ts
│   └── src/
├── src/
│   ├── main/java/io/github/jackbaozz/pocketbase/           # Java SDK 源码
│   │   ├── AuthResponse.java
│   │   ├── PocketBaseClient.java
│   │   └── ...
│   ├── main/java/io/github/jackbaozz/pocketbase/server/    # 嵌入式服务器源码
│   │   ├── PocketBaseServer.java
│   │   ├── LocalPocketBase.java
│   │   ├── ServerConfig.java
│   │   ├── internal/
│   │   └── model/
│   ├── main/resources/pocketbase-admin/                    # 前端 UI 构建产物目录
│   └── test/java/io/github/jackbaozz/pocketbase/
│       ├── PocketBaseClientTest.java
│       └── server/LocalPocketBaseServerTest.java
├── pom.xml
└── settings.xml
```

---

## 嵌入式服务器 API 支持列表

| 分类 | 支持的 API 路径与 HTTP 方法 |
| --- | --- |
| **系统** | `GET /api/health` |
| **超级管理员** | `POST /api/bootstrap/superuser`<br>`POST /api/admins/auth-with-password`<br>`POST /api/collections/_superusers/auth-with-password` |
| **集合管理** | `GET/POST /api/collections`<br>`GET/PATCH/DELETE /api/collections/{idOrName}`<br>`PUT /api/collections/import`<br>`DELETE /api/collections/{idOrName}/truncate`<br>`GET /api/collections/meta/scaffolds`<br>`GET /api/collections/meta/oauth2-providers`<br>`POST /api/collections/meta/dry-run-view` |
| **记录 CRUD** | `GET/POST /api/collections/{collection}/records`<br>`GET/PATCH/DELETE /api/collections/{collection}/records/{id}` |
| **文件接口** | `GET /api/files/{collection}/{recordId}/{filename}`<br>`POST /api/files/token` |
| **批处理** | `POST /api/batch` |
| **实时推送** | `GET/POST /api/realtime` |
| **备份还原** | `GET/POST /api/backups`<br>`POST /api/backups/upload`<br>`GET/DELETE /api/backups/{key}`<br>`POST /api/backups/{key}/restore` |
| **系统设置** | `GET/PATCH /api/settings`<br>`POST /api/settings/test/s3`<br>`POST /api/settings/test/email`<br>`POST /api/settings/apple/generate-client-secret` |
| **系统日志** | `GET /api/logs`<br>`GET /api/logs/{id}`<br>`GET /api/logs/stats` |
| **定时任务** | `GET /api/crons`<br>`POST /api/crons/{id}` |
| **SQL API** | `POST /api/sql` *(仅 Superuser 权限)* |
| **Auth 详情** | `GET /api/collections/{collection}/auth-methods`<br>`POST /api/collections/{collection}/auth-with-password`<br>`POST /api/collections/{collection}/auth-with-otp`<br>`POST /api/collections/{collection}/auth-with-oauth2`<br>`POST /api/collections/{collection}/auth-refresh`<br>`POST /api/collections/{collection}/request-otp`<br>`POST /api/collections/{collection}/request-password-reset`<br>`POST /api/collections/{collection}/confirm-password-reset`<br>`POST /api/collections/{collection}/request-verification`<br>`POST /api/collections/{collection}/confirm-verification`<br>`POST /api/collections/{collection}/request-email-change`<br>`POST /api/collections/{collection}/confirm-email-change`<br>`POST /api/collections/{collection}/impersonate/{id}`<br>`GET/POST /api/oauth2-redirect` |

---

## 授权协议

本项目采用 [MIT](LICENSE) 开源协议。
