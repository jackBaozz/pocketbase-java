# pocketbase-java

PocketBase Java 实现。项目保留轻量级 PocketBase Java SDK，同时新增一个低依赖的 embedded server：使用 JDK `HttpServer` 提供 PocketBase 风格 API，内置 Admin UI，JSON 文件持久化，面向 GraalVM Native Image 约束设计。

> 当前服务端覆盖集合管理、记录 CRUD、JSON/multipart Batch API、relation `expand`、file 字段 multipart 上传与 `/api/files` 访问、MIME/大小校验、protected file token、基础图片缩略图、备份还原、Realtime SSE 记录订阅与官方 `subscriptions[]`/`options.query` 提交格式、collection access rules 常用子集、`@collection.*` 聚合字段匹配、auth collection 密码登录、superuser bootstrap、Admin UI 和 native-image 元数据。它不是官方 Go PocketBase 的逐行移植；Realtime auth refresh/collection 事件、完整规则函数/复杂跨集合查询、SQLite 查询优化、OAuth2 等高级能力还需要继续补齐。

---

## 核心特性

- **低依赖**: HTTP 使用 `java.net.http.HttpClient`，运行时仅引入 Jackson 处理 JSON。
- **标准 API 映射**: 覆盖 `/api/collections/{collection}/records`、`auth-with-password` 和集合管理接口。
- **Embedded Server**: `io.github.jackbaozz.pocketbase.server.PocketBaseServer` 可直接启动本地 PocketBase 风格服务。
- **内置 Admin UI**: `/_/` 提供 superuser 初始化、登录、集合/记录管理、file 字段上传和备份操作；源码位于 `UI/`，构建产物嵌入 Java resources。
- **本地持久化**: `pb_data/pb_schema.json` 保存集合结构，`pb_data/records/*.json` 保存记录数据。
- **文件字段**: 支持 `multipart/form-data` 上传，文件落到 `pb_data/storage/{collectionId}/{recordId}`，通过 `/api/files/{collection}/{record}/{filename}` 访问，并支持 MIME/大小校验、protected file token 和 `thumb` 图片缩略图。
- **备份还原**: 支持 `pb_data/backups` 下的 zip 备份创建、上传、下载、删除和还原。
- **Batch API**: 支持 `/api/batch` JSON 和 multipart record create/update/upsert/delete，子请求失败时回滚整批记录和 storage 文件变更。
- **Realtime**: 支持 `/api/realtime` SSE 连接、记录级订阅、官方 `subscriptions[]`/`options.query` 参数格式、`filter`/`expand`/`fields` 订阅选项和 create/update/delete 推送，并复用 collection access rules 过滤可见记录。
- **安全基础**: superuser/auth 账号使用 PBKDF2 哈希密码，登录和 auth refresh token 使用 HMAC-SHA256 签名。
- **访问规则**: 支持 `listRule` / `viewRule` / `createRule` / `updateRule` / `deleteRule` 的 `null`、公开规则、常用表达式过滤和 `@collection.*` 聚合字段匹配。
- **Relation 展开**: 支持记录查询里的 `expand`，按 relation 字段解析目标记录并复用目标集合 `viewRule`。
- **认证状态管理**: `AuthStore` 自动保存登录 token，后续请求自动带 `Authorization: Bearer ...`。
- **异常可诊断**: 非 2xx 响应抛出 `PocketBaseException`，保留状态码、URL、原始响应体和 PocketBase 错误结构。
- **GraalVM 友好**: 不使用动态代理，核心模型使用 record/普通类，反射面由 Jackson 限定。

---

## 环境要求

| 类别 | 要求 |
| --- | --- |
| JDK | 17+ |
| Maven | 3.9+ |
| Node.js / npm | 20.19+ / 10+，仅修改或重建 Admin UI 时需要 |
| GraalVM | 构建 native 二进制时需要 GraalVM JDK 17+ / 21+ |
| PocketBase | SDK 可连接官方 PocketBase；embedded server 独立运行 |

如果本机 Maven Central 访问不稳定，可以使用项目内置镜像配置：

```bash
mvn -gs settings.xml -s settings.xml test
```

---

## 快速开始

### 启动 embedded server

```bash
mvn -gs settings.xml -s settings.xml clean package
java -jar target/pocketbase-java-0.1.0-SNAPSHOT-all.jar serve --http 127.0.0.1:8090 --dir pb_data
```

打开：

- Admin UI: http://127.0.0.1:8090/_/
- Health API: http://127.0.0.1:8090/api/health

也可以通过环境变量创建首个 superuser：

```bash
PB_SUPERUSER_EMAIL=root@example.com \
PB_SUPERUSER_PASSWORD=secret123 \
java -jar target/pocketbase-java-0.1.0-SNAPSHOT-all.jar serve
```

### 构建 native 二进制

```bash
mvn -gs settings.xml -s settings.xml -Pnative -DskipTests package
./target/pocketbase-java serve --http 127.0.0.1:8090 --dir pb_data
```

### 安装到本地 Maven 仓库

```bash
mvn -gs settings.xml -s settings.xml clean install
```

### 创建客户端

```java
import io.github.jackbaozz.pocketbase.PocketBaseClient;
import io.github.jackbaozz.pocketbase.RecordList;

PocketBaseClient client = PocketBaseClient.builder("http://127.0.0.1:8090").build();

RecordList posts = client.collection("posts").list();
posts.items().forEach(item -> System.out.println(item.get("title").asText()));
```

### 账号密码认证

```java
client.collection("users").authWithPassword("demo@example.com", "password");

// authWithPassword 成功后，后续请求会自动带 Bearer token。
client.collection("posts").create(Map.of(
        "title", "Hello PocketBase",
        "published", true
));
```

### 记录查询

```java
RecordList page = client.collection("posts").list(ListOptions.builder()
        .page(1)
        .perPage(20)
        .sort("-created")
        .filter("published = true")
        .expand("author")
        .build());
```

---

## 项目结构

```text
pocketbase-java/
├── docs/
│   ├── API设计.md
│   └── 技术架构与开发规范.md
├── UI/
│   ├── package.json
│   ├── vite.config.ts
│   └── src/
├── src/
│   ├── main/java/io/github/jackbaozz/pocketbase/
│   │   ├── AuthResponse.java
│   │   ├── PocketBaseClient.java
│   │   └── ...
│   ├── main/java/io/github/jackbaozz/pocketbase/server/
│   │   ├── PocketBaseServer.java
│   │   ├── LocalPocketBase.java
│   │   ├── ServerConfig.java
│   │   ├── internal/
│   │   └── model/
│   ├── main/resources/pocketbase-admin/   # UI build output
│   ├── main/resources/META-INF/native-image/
│   └── test/java/io/github/jackbaozz/pocketbase/
│       ├── PocketBaseClientTest.java
│       └── server/LocalPocketBaseServerTest.java
├── .github/workflows/ci.yml
├── pom.xml
├── settings.xml
├── CONTRIBUTING.md
├── CHANGELOG.md
└── LICENSE
```

---

## 常用命令

```bash
# 运行测试
mvn -gs settings.xml -s settings.xml test

# 构建 Admin UI 到 src/main/resources/pocketbase-admin/
(cd UI && npm install && npm run build)

# 打包 jar、all jar、sources jar、javadoc jar
mvn -gs settings.xml -s settings.xml clean package

# 构建 GraalVM native 二进制
mvn -gs settings.xml -s settings.xml -Pnative -DskipTests package

# 安装到本地 Maven 仓库
mvn -gs settings.xml -s settings.xml clean install
```

---

## Embedded Server API 覆盖

| 能力 | 路径 |
| --- | --- |
| 健康检查 | `GET /api/health` |
| 首个 superuser | `POST /api/bootstrap/superuser` |
| superuser 登录 | `POST /api/collections/_superusers/auth-with-password` |
| 集合管理 | `GET/POST /api/collections`, `GET/PATCH/DELETE /api/collections/{idOrName}`，列表/单条支持 `filter`、`sort`、`fields` |
| 记录 CRUD | `GET/POST /api/collections/{collection}/records`, `GET/PATCH/DELETE /api/collections/{collection}/records/{id}` |
| Batch API | `POST /api/batch` |
| 文件访问 | `GET /api/files/{collection}/{recordId}/{filename}` |
| 文件 token | `POST /api/files/token` |
| 备份还原 | `GET/POST /api/backups`, `POST /api/backups/upload`, `GET/DELETE /api/backups/{key}`, `POST /api/backups/{key}/restore` |
| Realtime | `GET /api/realtime`, `POST /api/realtime` |
| auth 方法 | `GET /api/collections/{collection}/auth-methods` |
| auth 登录/刷新 | `POST /api/collections/{collection}/auth-with-password`, `POST /api/collections/{collection}/auth-refresh` |

规则语义：

- `null`: 仅 superuser 可访问。
- `""`: 公开访问。
- 非空表达式：作为记录过滤和访问判断，支持 `&&`、`||`、括号、`=`、`!=`、`>`、`>=`、`<`、`<=`、`~`、`!~`、数组 any 操作符 `?=` 等，以及 `@request.auth.*`、`@request.body.*`、`@request.query.*`、`@request.method`。

---

## 文档

- [技术架构与开发规范](docs/技术架构与开发规范.md)
- [API 设计](docs/API设计.md)
- [PocketBase 官方文档](https://pocketbase.io/docs/)

---

## License

MIT License
