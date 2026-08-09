# pocketbase-java

PocketBase 的 Java 实现。本项目包含一个轻量级的 **PocketBase Java SDK**，以及一个低依赖的 **嵌入式服务器 (Embedded Server)**：使用 JDK `HttpServer` 提供 PocketBase 风格的 API，内置 Admin UI，采用 JSON 文件持久化，特别面向 GraalVM Native Image 约束而设计。

**官方 PocketBase 基准版本:** v0.39.10

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

- **低依赖**: HTTP 服务基于 `java.net.http.HttpClient` 与 JDK 内置 `HttpServer`，核心运行时依赖极少，保持极小的体积与资源占用，且便于 Native Image 编译。
- **标准 API 映射**: 完美对齐官方 PocketBase REST API 规范 (截至 **v0.39.10**)，包括 `/api/collections/{collection}/records`、密码/OTP/MFA/OAuth2 认证流程、账号模拟 (impersonate)、视图查询、速率限制与客户端 IP 策略规则。
- **嵌入式服务器 (Embedded Server)**: 提供 `io.github.jackbaozz.pocketbase.server.PocketBaseServer`，可直接在 Java 应用中编程式启动本地 PocketBase 风格服务，无需依赖 Spring/Tomcat。
- **内置 Admin UI**: 访问 `/_/` 即可使用超级管理员初始化、登录、集合/记录管理、文件上传、备份、配置编辑以及详细日志查看等功能。支持 9 种语言国际化、API 文档侧栏、Schema/索引编辑器、关联记录选择器、带语法高亮和自动补全的代码编辑器，以及 `hideControls` 安全锁定模式。前端源码位于 `UI/`，构建产物内嵌至 Java 资源文件。
- **多存储引擎矩阵**: 引入了灵活的 `StorageEngine` SPI。默认使用 SQLite，将数据保存到 `<server.data-dir>/pocketbase.db`；旧版 JSONL（`.jsonl` 记录文件和 `.json` 元数据）仍可通过 `storage.type=jsonl` 显式启用。MySQL 与 PostgreSQL 也可通过 `application.properties`、`-Dstorage` 或 native 运行时的 `PB_STORAGE` 配置，底层使用 jOOQ 与 HikariCP。
- **文件管理与 S3 支持**: 提供 `FileStorageProvider` SPI，支持本地文件系统及 AWS S3 或兼容的对象存储服务，支持多媒体缩略图自动生成、MIME 类型/大小校验和 Protected File Token 安全控制。
- **备份与还原**: 支持在本地或 S3 远端创建、上传、下载、删除和恢复 Zip 格式的数据备份，具备事务级安全性与自动过期清理。
- **邮件服务 (SMTP)**: 整合了支持 SSL/TLS 安全通道的 SMTP 客户端发送，支持模板渲染与变量替换，并提供开发测试用的本地 outbox 邮件输出日志。
- **安全基础**: 超级管理员与 Auth 记录密码采用 PBKDF2 哈希，登录与 auth 刷新 Token 均基于 HMAC-SHA256 签名。
- **Realtime (SSE)**: 支持 `/api/realtime` Server-Sent Events 连接，支持记录级订阅、官方 `subscriptions[]`/`options.query` 格式、`filter`/`expand`/`fields` 选项，并复用 collection access rules 过滤可见记录。
- **Batch API**: 支持批量对记录进行 create/update/upsert/delete 操作，任何子请求失败时自动回滚整批记录和 storage 文件。
- **SQL API**: 超级管理员专用的 `POST /api/sql` 接口，支持原生 SQL 执行、事务回滚以及各数据库方言自动适配。
- **GraalVM 原生镜像友好**: 整个框架经过精简和优化，消除了动态代理，所有 JDBC 驱动、jOOQ 方言、S3 客户端和 Jackson 反射映射均显式注册并验证，支持一键编译为无 VM 依赖的单文件原生二进制。

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
java -jar target/pocketbase-java-0.3.4-all.jar start --http 127.0.0.1:8090 --dir pb_data
```

当前只有启动这一种命令，因此 `start` 也可以省略：

```bash
java -jar target/pocketbase-java-0.3.4-all.jar
```

启动后可打开：
- **Admin UI 管理后台**: http://127.0.0.1:8090/_/
- **Health 检查 API**: http://127.0.0.1:8090/api/health

你也可以通过环境变量在启动时直接初始化超级管理员（superuser）：
```bash
PB_SUPERUSER_EMAIL=root@example.com \
PB_SUPERUSER_PASSWORD=secret123 \
java -jar target/pocketbase-java-0.3.4-all.jar start
```

### 配置文件

服务支持可选的 UTF-8 `application.properties` 配置文件。可以将
`config/application.properties.example` 复制为 `config/application.properties` 后按需修改；
服务会优先读取 `config/` 目录下的文件，根目录文件作为兼容性备用方式。
还可以通过 `--config <路径>` 或 `PB_CONFIG_FILE` 指定其他配置文件。

```properties
app.name=我的 PocketBase 应用
server.host=127.0.0.1
server.port=8090
server.data-dir=pb_data
storage.type=sqlite
```

然后正常启动即可：

```bash
java -jar target/pocketbase-java-0.3.4-all.jar start
```

SQLite 数据库会自动创建在 `<server.data-dir>/pocketbase.db`，不需要单独启动
SQLite 服务。使用 MySQL 或 PostgreSQL 时，还需要配置 `database.url`、
`database.user` 和 `database.password`。命令行参数优先级最高，其次是 JVM
系统属性，再其次是环境变量和 properties 文件。

切换存储引擎不会自动把已有 JSONL 数据迁移到 SQLite。已有部署应先备份 JSONL 目录，
再通过明确的导出/导入流程完成迁移；当前检出的本地 `pb_data` 已完成 SQLite 迁移，
原始 JSONL 文件仍保留，可用于回滚。

### 运行环境 Profile

不引入 Spring Boot 的前提下，服务支持与 Spring Boot 相同的 profile 文件命名方式。
先加载基础文件，再由 `application-<profile>.properties` 覆盖：

```text
config/application.properties
config/application-dev.properties
config/application-test.properties
config/application-production.properties
```

可先复制项目内的模板，再编辑环境配置文件：

```bash
cp config/application-dev.properties.example config/application-dev.properties
cp config/application-test.properties.example config/application-test.properties
cp config/application-production.properties.example config/application-production.properties
```

可以在运行时选择 profile，优先级从高到低如下：

```bash
java -jar target/pocketbase-java-0.3.4-all.jar start --profile dev
java -Dapp.profile=dev -jar target/pocketbase-java-0.3.4-all.jar start
PB_PROFILE=dev java -jar target/pocketbase-java-0.3.4-all.jar start
```

也可以在基础配置中设置 `app.profile=dev`。Maven Profile 仅在构建期生效，
`mvn -Pdev package` 不会让打出的 JAR 或 native 二进制在运行时自动选择 dev；
两种发布方式都应使用上面的运行时参数。profile 名称仅允许字母、数字、`_` 和 `-`，
避免路径越界。

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
./target/pocketbase-java start --http 127.0.0.1:8090 --dir pb_data
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
| **超级管理员** | `GET/POST /api/bootstrap/superuser`<br>`POST /api/admins/auth-with-password` *(历史兼容)*<br>`POST /api/collections/_superusers/auth-with-password` |
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

## 系统集合

服务首次启动时会创建下面 5 个内部集合。它们属于认证子系统，由服务端统一维护，不应手动删除或修改集合定义。

| 集合 | 用途 | 相关流程 |
| --- | --- | --- |
| **`_superusers`** | 保存用于登录 Admin UI 和调用管理 API 的超级管理员账户。 | Admin UI 登录 (`/_/`)、超级管理员初始化，以及 `/api/collections/_superusers/auth-with-password`。 |
| **`_authOrigins`** | 保存认证记录最近的登录来源（IP 地址和设备指纹）。开启 `authAlert` 后，用于识别新地点登录并发送提醒邮件。 | 成功认证后写入，由认证告警流程读取。 |
| **`_externalAuths`** | 保存认证记录与 OAuth2 第三方身份的映射，身份由 `provider` 和 `providerId` 唯一标识。 | OAuth2 登录、账号关联和第三方身份解绑。 |
| **`_mfas`** | 保存启用 MFA 时在密码校验通过后生成的短期 MFA 挑战记录。 | 密码登录返回 `mfaId`，随后执行 `request-otp` 和 `auth-with-otp`。 |
| **`_otps`** | 保存短期的一次性密码（OTP）记录。记录会在验证消费或过期后清理。 | `request-otp` 创建记录；`auth-with-otp` 验证并消费记录。 |

> 每个集合都有稳定的内置 ID，例如 `_superusers` 使用 `pbc_3142635823`；`pbc_superusers` 等旧标识仍作为迁移兼容别名保留。集合由服务端自动创建和维护；删除认证记录时，也会清理其关联的 `_authOrigins`、`_externalAuths`、`_mfas` 和 `_otps` 记录。

---

## 授权协议

本项目采用 [MIT](LICENSE) 开源协议。
