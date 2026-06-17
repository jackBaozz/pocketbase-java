# pocketbase-java API 设计

## 1. 设计目标

`pocketbase-java` 暴露两层能力：

- Java SDK：面向 PocketBase 资源的 Java API，而不是直接暴露裸 HTTP。
- Embedded Server：用 Java 提供 PocketBase 风格 HTTP API 和 Admin UI，便于后续打 GraalVM native 二进制。

调用方只需要关心集合、记录、认证和错误处理。

设计原则：

- 方法名贴近 PocketBase 官方 API 语义。
- 不隐藏 PocketBase 原始 JSON 结构，默认返回 `JsonNode`，避免早期 SDK 过度建模。
- 对高频查询参数提供类型化 Options。
- 认证成功后自动保存 token，后续请求自动附加 Bearer 请求头。

---

## 2. Client 初始化

```java
PocketBaseClient client = PocketBaseClient.builder("http://127.0.0.1:8090").build();
```

可选配置：

```java
PocketBaseClient client = PocketBaseClient.builder("http://127.0.0.1:8090")
        .bearerToken("existing-token")
        .httpClient(customHttpClient)
        .objectMapper(customObjectMapper)
        .build();
```

---

## 3. 记录 API

### 3.1 列表

PocketBase endpoint:

```text
GET /api/collections/{collection}/records
```

Java:

```java
RecordList records = client.collection("posts").list(ListOptions.builder()
        .page(1)
        .perPage(20)
        .sort("-created")
        .filter("published = true")
        .expand("author")
        .fields("id,title,author")
        .build());
```

### 3.2 获取单条

PocketBase endpoint:

```text
GET /api/collections/{collection}/records/{id}
```

Java:

```java
JsonNode record = client.collection("posts").getOne("abc123", RecordQuery.builder()
        .expand("author")
        .build());
```

### 3.3 创建

PocketBase endpoint:

```text
POST /api/collections/{collection}/records
```

Java:

```java
JsonNode created = client.collection("posts").create(Map.of(
        "title", "Hello",
        "published", true
), RecordQuery.builder()
        .expand("author")
        .fields("id,title,expand.author.name")
        .build());
```

### 3.4 更新

PocketBase endpoint:

```text
PATCH /api/collections/{collection}/records/{id}
```

Java:

```java
JsonNode updated = client.collection("posts").update("abc123", Map.of(
        "title", "Updated"
), RecordQuery.builder()
        .fields("id,title")
        .build());
```

### 3.5 删除

PocketBase endpoint:

```text
DELETE /api/collections/{collection}/records/{id}
```

Java:

```java
client.collection("posts").delete("abc123");
```

---

## 4. 认证 API

### 4.1 密码认证

PocketBase endpoint:

```text
POST /api/collections/{collection}/auth-with-password
```

Java:

```java
AuthResponse auth = client.collection("users")
        .authWithPassword("demo@example.com", "password");
```

认证成功后：

- `AuthStore` 保存 token 和 record。
- 后续请求自动带 `Authorization: Bearer <token>`。

### 4.2 刷新认证

PocketBase endpoint:

```text
POST /api/collections/{collection}/auth-refresh
```

Java:

```java
AuthResponse auth = client.collection("users").authRefresh();
```

当前实现要求请求已带同一个 auth collection 签发的 Bearer token。刷新成功后会返回新的 token 和最新公开 record，并更新 `AuthStore`。

### 4.3 查看认证方法

PocketBase endpoint:

```text
GET /api/collections/{collection}/auth-methods
```

Java:

```java
JsonNode methods = client.collection("users").listAuthMethods();
```

---

## 5. 集合管理 API

集合管理通常需要管理员或超级用户权限。

```java
JsonNode collections = client.collections().list();
JsonNode filtered = client.collections().list(ListOptions.builder()
        .filter("type = 'base'")
        .sort("-name")
        .fields("id,name,type")
        .build());
JsonNode collection = client.collections().getOne("posts");
JsonNode slim = client.collections().getOne("posts", ListOptions.builder()
        .fields("id,name")
        .build());
JsonNode scaffolds = client.collections().scaffolds();
JsonNode oauth2Providers = client.collections().oauth2Providers();
JsonNode created = client.collections().create(collectionBody);
JsonNode updated = client.collections().update("posts", patchBody);
client.collections().importCollections(Map.of(
        "deleteMissing", true,
        "collections", List.of(collectionBody)
));
client.collections().truncate("posts");
client.collections().delete("posts");
```

---

## 6. 错误处理

非 2xx 响应会抛出 `PocketBaseException`：

```java
try {
    client.collection("posts").create(Map.of());
} catch (PocketBaseException ex) {
    System.err.println(ex.statusCode());
    System.err.println(ex.responseBody());
    if (ex.error() != null) {
        System.err.println(ex.error().message());
        System.err.println(ex.error().data());
    }
}
```

异常保留：

- HTTP status code。
- request method。
- request URI。
- raw response body。
- parsed PocketBase error payload。

---

## 7. 与 PocketBase 官方文档的对应关系

| SDK 方法 | PocketBase API |
| --- | --- |
| `collection(name).list()` | `GET /api/collections/{collection}/records` |
| `collection(name).getOne(id)` | `GET /api/collections/{collection}/records/{id}` |
| `collection(name).create(body[, query])` | `POST /api/collections/{collection}/records` |
| `collection(name).update(id, body[, query])` | `PATCH /api/collections/{collection}/records/{id}` |
| `collection(name).delete(id)` | `DELETE /api/collections/{collection}/records/{id}` |
| `collection(name).authWithPassword(identity, password)` | `POST /api/collections/{collection}/auth-with-password` |
| `collection(name).requestOtp(email)` | `POST /api/collections/{collection}/request-otp` |
| `collection(name).authWithOtp(otpId, password)` | `POST /api/collections/{collection}/auth-with-otp` |
| `collection(name).authWithOAuth2(provider, code, redirectURL, codeVerifier, createData, query)` | `POST /api/collections/{collection}/auth-with-oauth2` |
| `collection(name).authRefresh()` | `POST /api/collections/{collection}/auth-refresh` |
| `collection(name).listAuthMethods()` | `GET /api/collections/{collection}/auth-methods` |
| `files().getToken()` | `POST /api/files/token` |
| `settings().get([fields])` | `GET /api/settings` |
| `settings().update(body)` | `PATCH /api/settings` |
| `settings().testS3(filesystemOrBody)` | `POST /api/settings/test/s3` |
| `settings().testEmail(email, template[, collection])` | `POST /api/settings/test/email` |
| `settings().generateAppleClientSecret(body)` | `POST /api/settings/apple/generate-client-secret` |
| `logs().list(options)` | `GET /api/logs?...` |
| `logs().getOne(id)` | `GET /api/logs/{id}` |
| `logs().stats(query)` | `GET /api/logs/stats?...` |
| `crons().list()` | `GET /api/crons` |
| `crons().run(id)` | `POST /api/crons/{id}` |
| `sql().run(query)` | `POST /api/sql` |
| `collections().list()` | `GET /api/collections` |
| `collections().list(options)` | `GET /api/collections?...` |
| `collections().getOne(id, options)` | `GET /api/collections/{idOrName}?...` |
| `collections().scaffolds()` | `GET /api/collections/meta/scaffolds` |
| `collections().oauth2Providers()` | `GET /api/collections/meta/oauth2-providers` |
| `collections().importCollections(body)` | `PUT /api/collections/import` |
| `collections().truncate(idOrName)` | `DELETE /api/collections/{idOrName}/truncate` |

官方文档：

- <https://pocketbase.io/docs/>
- <https://pocketbase.io/docs/api-records/>
- <https://pocketbase.io/docs/api-collections/>

---

## 8. Embedded Server API

入口类：

```java
io.github.jackbaozz.pocketbase.server.PocketBaseServer
```

命令：

```bash
java -jar target/pocketbase-java-0.1.0-SNAPSHOT-all.jar serve --http 127.0.0.1:8090 --dir pb_data
```

### 8.1 健康检查

```text
GET /api/health
```

响应：

```json
{
  "code": 200,
  "message": "API is healthy.",
  "data": {
    "canBackup": true,
    "dataDir": "pb_data",
    "superuserReady": true
  }
}
```

### 8.2 首个 superuser

```text
POST /api/bootstrap/superuser
```

请求：

```json
{
  "email": "root@example.com",
  "password": "secret123"
}
```

该接口仅在没有任何 superuser 时可用。

### 8.3 superuser 登录

```text
POST /api/collections/_superusers/auth-with-password
```

请求：

```json
{
  "identity": "root@example.com",
  "password": "secret123"
}
```

响应包含 `token` 和隐藏密码后的 `record`。

#### 8.3.1 Auth 生命周期接口

```text
POST /api/collections/{collection}/request-password-reset
POST /api/collections/{collection}/confirm-password-reset
POST /api/collections/{collection}/request-verification
POST /api/collections/{collection}/confirm-verification
POST /api/collections/{collection}/request-email-change
POST /api/collections/{collection}/confirm-email-change
POST /api/collections/{collection}/impersonate/{id}
```

当前实现覆盖官方 auth collection 的常用 token 流程：

- `request-otp`: 请求体 `{"email":"user@example.com"}`。collection 开启 `otp.enabled=true` 时返回 `{"otpId":"..."}`；未配置 SMTP 时验证码会写入 `pb_data/auth_requests.json` outbox。
- `auth-with-otp`: 请求体 `{"otpId":"...","password":"123456"}`。校验通过后返回标准 auth 响应，并在 `sentTo` 匹配当前 email 时把 record 标记为 `verified=true`；OTP 仅能使用一次。
- `auth-methods`: 现在会反映 auth collection 的 `passwordAuth.identityFields`、`otp.duration`、`mfa.duration` 和已配置的 OAuth2 provider 列表；当 provider 配了 `clientId/authURL` 时，会返回可直接拼接 `redirect_uri` 的 `authURL`、`state`、`codeVerifier`、`codeChallenge`。
- `auth-with-oauth2`: 请求体 `{"provider":"oidc","code":"...","redirectURL":"https://app.example/callback","codeVerifier":"..."}`。当前实现支持 generic OAuth2/OIDC provider：向 provider 的 `tokenURL` 做 code exchange，再从 `userInfoURL` 或 `id_token` 读取用户信息，按 providerId / email 复用或创建 auth record，并返回标准 auth 响应。
- `request-password-reset`: 请求体 `{"email":"user@example.com"}`，存在对应 auth record 时生成 `passwordReset` token，响应 `204`。
- `confirm-password-reset`: 请求体 `{"token":"...","password":"newsecret456","passwordConfirm":"newsecret456"}`，校验 token 后更新密码、标记 `verified=true`，并轮换 record tokenKey，使旧登录 token 失效。
- `request-verification`: 请求体 `{"email":"user@example.com"}`，存在未验证 auth record 时生成 `verification` token，响应 `204`。
- `confirm-verification`: 请求体 `{"token":"..."}`，校验 token 后设置 `verified=true`。
- `request-email-change`: 需要同一 auth collection 的 record Bearer token，请求体 `{"newEmail":"next@example.com"}`，生成 `emailChange` token。
- `confirm-email-change`: 请求体 `{"token":"...","password":"currentPassword"}`，校验当前密码后更新 email、设置 `verified=true`，并轮换 tokenKey。
- `impersonate/{id}`: 需要 superuser token，请求体可选 `{"duration":3600}`，返回目标 auth record 的短期 token；impersonate token 可访问接口，但不能调用 `auth-refresh`。

由于当前 runtime 不引入 Jakarta Mail 等额外反射型邮件依赖，OTP 和 auth request 类接口会把待发送内容写入 `pb_data/auth_requests.json`。`/api/settings/test/email` 已提供纯 JDK SMTP 测试发送路径；当 SMTP 配置启用时，`request-otp` 会直接发送邮件。

`GET/POST /api/oauth2-redirect` 现已提供一个轻量 callback 页面：会把 `{state,code,error}` 通过 `window.opener.postMessage(...)` 和 `sessionStorage` 回传给前端页面，然后自动关闭窗口。它不是官方 Go 版的 realtime subscription relay 实现，但足够支撑浏览器内的 OAuth2 流程。

### 8.4 集合管理

集合管理需要 superuser Bearer token。

```text
GET    /api/collections
POST   /api/collections
GET    /api/collections/{idOrName}
PATCH  /api/collections/{idOrName}
DELETE /api/collections/{idOrName}
PUT    /api/collections/import
DELETE /api/collections/{idOrName}/truncate
GET    /api/collections/meta/scaffolds
GET    /api/collections/meta/oauth2-providers
```

创建集合：

```json
{
  "name": "posts",
  "type": "base",
  "fields": [
    {"name": "title", "type": "text", "required": true},
    {"name": "published", "type": "bool"}
  ]
}
```

支持字段类型：`text`、`email`、`password`、`bool`、`number`、`select`、`json`、`relation`、`file`。当前 `file` 支持 multipart 上传和 `/api/files` 访问；`relation` 支持通过 `collectionId`、`collectionIds` 或 `options.collectionId` 指向目标集合，并可在记录查询中使用 `expand` 展开。

`PUT /api/collections/import` 当前实现：

- 请求体 `{"collections":[...],"deleteMissing":true}`，`collections` 必须是非空数组。
- 每个集合配置支持官方 `fields` 和旧别名 `schema`。
- 同 id 集合会更新并保留记录数据；同 id 改名后旧名称会失效。
- `deleteMissing=true` 会删除导入列表外的非系统集合，同时清理对应 records JSON 和 storage 目录。
- 系统集合不会被 `deleteMissing` 删除，auth 集合会自动补齐 `email`、`password`、`verified` 字段。

`DELETE /api/collections/{idOrName}/truncate` 会清空该集合的 records JSON 并删除对应 storage 目录；系统集合当前不允许 truncate。

`GET /api/collections/meta/scaffolds` 返回 `base`、`auth`、`view` 三类集合模板，供 Admin UI 创建集合前填充默认结构。

`GET /api/collections/meta/oauth2-providers` 返回官方常见 OAuth2 provider metadata（`name`、`displayName`、`logo`）。该 metadata 会被 Admin UI 的 auth collection OAuth2 配置和登录测试器使用；当前 generic OAuth2/OIDC code exchange 与 popup callback 已实现，但 provider-specific 细节仍需继续补齐。

集合列表和单条查询支持常用查询参数：

- `page`
- `perPage`
- `filter`
- `sort`
- `fields`

### 8.5 记录 CRUD

```text
GET    /api/collections/{collection}/records
POST   /api/collections/{collection}/records
GET    /api/collections/{collection}/records/{id}
PATCH  /api/collections/{collection}/records/{id}
DELETE /api/collections/{collection}/records/{id}
```

列表/单条参数：

- `page`
- `perPage`
- `sort`
- `filter`
- `expand`
- `fields`

当前 `filter` 和 collection access rules 共用同一个轻量表达式求值器，支持常用比较、包含、数组 any 和布尔组合。

创建、更新、upsert 和 auth 响应也支持响应查询参数：

- record create/update/upsert: `expand`、`fields` 会应用到返回的 record。
- auth-with-password/auth-refresh: `expand` 会应用到返回的 `record`，`fields` 支持 `token`、`meta` 和 `record.*` 路径裁剪，例如 `fields=token,record.id,record.expand.team.name`。

`expand` 支持逗号分隔的 relation 路径，例如：

```text
GET /api/collections/posts/records?expand=author,comments.author
GET /api/collections/posts/records/{id}?expand=author
```

展开结果写入记录的 `expand` 字段。目标记录会继续执行目标集合的 `viewRule`，非 superuser 不会看到 hidden/password 字段。当前展开深度限制为 6 层，暂不支持官方所有 modifier 和复杂深层规则组合。

`fields` 支持根字段和展开字段裁剪，例如：

```text
GET /api/collections/posts/records?fields=id,title
GET /api/collections/posts/records/{id}?expand=author&fields=id,expand.author.name
```

### 8.6 Batch API

```text
POST /api/batch
```

当前实现支持 JSON 和 multipart batch 的 record create/update/upsert/delete。所有子请求共用外层请求的 Bearer token，并复用对应集合的 access rules。

请求示例：

```json
{
  "requests": [
    {
      "method": "POST",
      "url": "/api/collections/posts/records",
      "body": {"id": "post_1", "title": "Created"}
    },
    {
      "method": "PUT",
      "url": "/api/collections/posts/records/post_2",
      "body": {"title": "Upserted"}
    },
    {
      "method": "PATCH",
      "url": "/api/collections/posts/records/post_1",
      "body": {"title": "Updated"}
    },
    {
      "method": "DELETE",
      "url": "/api/collections/posts/records/post_2"
    }
  ]
}
```

响应示例：

```json
{
  "responses": [
    {"status": 200, "body": {"id": "post_1", "title": "Created"}},
    {"status": 200, "body": {"id": "post_2", "title": "Upserted"}},
    {"status": 200, "body": {"id": "post_1", "title": "Updated"}},
    {"status": 204, "body": null}
  ]
}
```

当前行为：

- 支持 `POST /api/collections/{collection}/records`。
- 支持 `PATCH /api/collections/{collection}/records/{id}`。
- 支持 `PUT /api/collections/{collection}/records` 和 `PUT /api/collections/{collection}/records/{id}` upsert。
- 支持 `DELETE /api/collections/{collection}/records/{id}`。
- multipart batch 使用 `@jsonPayload` 字段传 JSON payload，文件字段名支持 `requests.N.fileField` 和 `requests[N].fileField` 两种格式。
- 任一子请求失败时，已经执行的 record 变更和 storage 文件写入会回滚，响应为 `400`，`data.index` 指向失败子请求。

multipart 示例：

```bash
curl -X POST http://127.0.0.1:8090/api/batch \
  -H "Authorization: Bearer <token>" \
  -F '@jsonPayload={"requests":[{"method":"POST","url":"/api/collections/assets/records","body":{"id":"asset_1","title":"Batch file"}}]}' \
  -F 'requests.0.attachment=@./hello.txt;type=text/plain'
```

当前限制：

- 暂不支持集合管理、备份等非 record batch 子请求。

### 8.7 文件上传和访问

创建或更新带 `file` 字段的记录时，可以使用 `multipart/form-data`：

```bash
curl -X POST http://127.0.0.1:8090/api/collections/assets/records \
  -H "Authorization: Bearer <token>" \
  -F 'title=Demo' \
  -F 'attachment=@./demo.txt'
```

响应记录中的 file 字段保存服务端文件名，例如：

```json
{
  "id": "abc123",
  "title": "Demo",
  "attachment": "demo_a1b2c3d4e5.txt"
}
```

访问文件：

```text
GET /api/files/{collection}/{recordId}/{filename}
POST /api/files/token
```

当前实现：

- 文件保存到 `pb_data/storage/{collectionId}/{recordId}/{filename}`。
- 文件名会做基本 sanitize，并追加随机后缀。
- `maxSelect` 或 `maxFiles` 大于 1 时，file 字段保存字符串数组；否则保存单个字符串。
- `PATCH` 支持用 `field+` 追加上传，用 `field-` 删除指定文件名。
- file 字段支持 `maxSize` 和 `mimeTypes`，同时兼容 `options.maxSize` 和 `options.mimeTypes`。`maxSize` 按字节校验，`mimeTypes` 支持精确 MIME 和 `image/*` 这类通配。
- file 字段设置 `"protected": true` 或 `options.protected=true` 后，文件访问需要 Bearer auth 或 `?token=<fileToken>` 满足记录 `viewRule`。
- file 字段配置 `thumbs` 或 `options.thumbs` 后，`GET /api/files/...?...thumb=<size>` 会按配置生成并缓存 PNG 缩略图；未配置的 size 或非当前支持格式会返回原文件。
- 支持 `download=1` 返回 `Content-Disposition: attachment`。
- `POST /api/files/token` 要求已有 auth token，返回 2 分钟有效的短期 file token。file token 只用于 `/api/files/...?...token=`，不会被普通 API 当作 Bearer token 接受。

Java:

```java
String token = client.files().getToken();
```

当前限制：

- 当前缩略图生成只支持 8-bit truecolor PNG；JPEG/GIF/WebP 等格式会回退原文件。

### 8.8 Backups

```text
GET    /api/backups
POST   /api/backups
POST   /api/backups/upload
GET    /api/backups/{key}
DELETE /api/backups/{key}
POST   /api/backups/{key}/restore
```

所有 backup API 都要求 superuser token。

创建备份：

```bash
curl -X POST http://127.0.0.1:8090/api/backups \
  -H "Authorization: Bearer <superuser-token>" \
  -H "Content-Type: application/json" \
  -d '{"name":"manual.zip"}'
```

响应示例：

```json
{
  "key": "manual.zip",
  "name": "manual.zip",
  "size": 1234,
  "modified": "2026-06-14T03:30:00Z"
}
```

当前实现：

- 备份文件保存在 `pb_data/backups/{key}`。
- zip 内容包含 schema、records 和 storage，不包含 `backups/` 目录和当前进程的 `pb_secret`。
- 还原会清空当前数据目录中除 `backups/` 外的内容，解压指定备份，然后重新加载 schema/records。
- 上传备份使用 `multipart/form-data`，任意文件字段名均可，服务端取第一个文件。
- 解压时会校验 zip entry，拒绝绝对路径、反斜杠路径和目录穿越。

当前限制：

- 暂不支持 S3 等远端备份存储。
- 暂不支持备份加密。
- 当前进程会保留已有 `pb_secret`，避免还原后 HMAC token secret 与内存状态不一致。

### 8.9 Settings 和 Logs

```text
GET   /api/settings
PATCH /api/settings
POST  /api/settings/test/s3
POST  /api/settings/test/email
POST  /api/settings/apple/generate-client-secret
GET   /api/logs
GET   /api/logs/{id}
GET   /api/logs/stats
```

所有 settings/logs API 都要求 superuser token。

Settings 当前实现：

- 设置保存到 `pb_data/pb_settings.json`。
- `PATCH /api/settings` 对传入 JSON 做深合并；`meta.appUrl` 和 `logs.logIp` 会兼容写入为官方字段 `meta.appURL`、`logs.logIP`。
- 默认 settings 结构包含官方常用段：`superuserIPs`、`meta.accentColor`、`logs.logIP`、`trustedProxy.useLeftmostIP`、`rateLimits.excludedIPs` 和默认 rate limit rules。
- 响应会按官方模型省略 `password`、`secret`、`privateKey` 等敏感字段；`accessKey` 会正常返回。
- PATCH 传入旧版 UI 使用的 `******` 会保留已有真实值，避免保存旧脱敏 JSON 时覆盖 secret。
- `POST /api/settings/test/s3` 请求体 `{"filesystem":"storage"}` 或 `{"filesystem":"backups"}`，按保存的 S3 配置使用 JDK `HttpClient` 和 AWS Signature V4 执行测试上传、列表探测和删除；请求体可带 `s3` 覆盖用于未保存前测试。
- `POST /api/settings/test/email` 请求体 `{"email":"dev@example.com","template":"verification","collection":"users"}`，template 支持 `verification`、`password-reset`、`email-change`、`otp`、`login-alert`。启用 SMTP 时使用内置纯 JDK SMTP 客户端发送；未启用时写入 `pb_data/auth_requests.json` 作为本地 outbox。
- `POST /api/settings/apple/generate-client-secret` 按 Apple Sign in 要求生成 ES256 JWT，字段为 `clientId`、`teamId`、`keyId`、`privateKey`、`duration`，duration 最大 `15777000` 秒。
- S3 远端文件系统和远端备份的实际读写集成仍未替换本地 storage/backups；当前 S3 API 只覆盖官方 settings 测试动作。

示例：

```bash
curl -X PATCH http://127.0.0.1:8090/api/settings \
  -H "Authorization: Bearer <superuser-token>" \
  -H "Content-Type: application/json" \
  -d '{"meta":{"appName":"Demo","appUrl":"https://example.test"},"logs":{"maxDays":14}}'
```

Logs 当前实现：

- 请求活动日志保存到 `pb_data/logs.json`。
- 记录 `/api/*` 请求的 method、url、status、execTime、userAgent、remoteIP、auth/authId 等字段；`execTime` 与官方一致使用毫秒。
- `/api/health`、成功的 `/api/logs*` 和 `GET /api/realtime` 长连接不会写活动日志；`/api/logs*` 的错误响应仍会记录。
- `GET /api/logs` 支持 `page`、`perPage`、`filter`、`sort`、`fields`。
- `GET /api/logs` 兼容官方 Admin UI 使用的 `sort=-@rowid`。
- `GET /api/logs/stats` 按小时返回 `{date,total}` 统计，`date` 格式为 `yyyy-MM-dd HH:00:00.000Z`。
- `logs.maxDays <= 0` 时不会保留活动日志，`logs.minLevel` 会过滤低于配置级别的日志。

示例：

```text
GET /api/logs?filter=data.status%20%3E%3D%20400&sort=-created
GET /api/logs/stats
```

### 8.10 Crons

```text
GET  /api/crons
POST /api/crons/{id}
```

所有 crons API 都要求 superuser token。

当前实现：

- `GET /api/crons` 返回官方 cron job JSON 形状：`{"id":"...","expression":"..."}`。
- 内置暴露 `__pbLogsCleanup__`、`__pbDBOptimize__`、`__pbMFACleanup__`、`__pbOTPCleanup__`，表达式与官方默认值保持一致。
- `__pbLogsCleanup__` 会执行当前 JSON store 的日志保留清理；`__pbDBOptimize__`、`__pbMFACleanup__`、`__pbOTPCleanup__` 在当前 JSON runtime 中是兼容占位 job。
- 当 `settings.backups.cron` 非空时，会额外注册 `__pbAutoBackup__`，表达式等于配置值；手动运行会生成 `@auto_pb_backup_yyyyMMddHHmmss.zip` 并按 `settings.backups.cronMaxKeep` 清理旧自动备份。
- `POST /api/crons/{id}` 与官方一样返回 `204`，job 在后台线程运行。

Java:

```java
JsonNode jobs = client.crons().list();
client.crons().run("__pbLogsCleanup__");
```

### 8.11 SQL

```text
POST /api/sql
```

`POST /api/sql` 要求 superuser token。请求体为：

```json
{"query":"select 1"}
```

响应字段与官方 SQL API 对齐：

```json
{
  "execTime": 0,
  "affectedRows": 0,
  "columns": [{"name":"1","type":"","nullable":true}],
  "rows": [["1"]]
}
```

当前实现：

- `query` 不能为空，最大长度 `5000`；校验失败返回 `400` 和 `data.query`。
- `SELECT` 最多返回 `1000` 行；支持常量选择、`*`、字段列表、`count(*) as alias`、`WHERE`、`ORDER BY`、`LIMIT`、`OFFSET` 和多条只读语句返回最后一条结果。
- 写语句支持 `CREATE TABLE`、`DROP TABLE`、`INSERT INTO ... VALUES`、`UPDATE ... SET ... WHERE`、`DELETE FROM ... WHERE`，映射到当前 JSON collection/records 持久化。
- 写模式会按官方语义包在一个事务快照里；多语句中任一条失败，会回滚 schema、records 和 storage 状态。
- 由于本项目当前不是 SQLite runtime，SQL 是 native-friendly 轻量子集，不支持 arbitrary SQLite 函数、JOIN、子查询、ALTER、REPLACE、DETACH 等完整数据库能力；这些语句会返回 `400 Failed to execute query. Raw error`。
- 实现不引入 JDBC、ORM、ANTLR 等反射/资源配置重的依赖，保持 GraalVM native 构建面可控。

Java:

```java
JsonNode result = client.sql().run("select count(*) as total from posts");
```

### 8.12 Realtime SSE

```text
GET  /api/realtime
POST /api/realtime
```

`GET /api/realtime` 打开 SSE 长连接。服务端建立连接后会先发送 `PB_CONNECT` 事件：

```text
event: PB_CONNECT
data: {"clientId":"<clientId>"}
```

客户端拿到 `clientId` 后，用 `POST /api/realtime` 设置订阅：

```json
{
  "clientId": "<clientId>",
  "subscriptions": [
    "posts/*",
    "posts/abc123",
    "posts/*?filter=published%20%3D%20true"
  ]
}
```

也支持官方 query/form 风格提交，`subscriptions[]`、`subscriptions[0]` 和 multipart/form-data 字段都会被识别：

```text
POST /api/realtime?clientId=<clientId>&subscriptions[0]=posts/*&options={"query":{"filter":"published = true","expand":"author","fields":"id,title,expand.author.name"}}
```

订阅 topic 支持：

- `{collection}/*`: 订阅集合下所有可见记录。
- `{collection}/{recordId}`: 订阅单条记录。
- `?filter=...`: 对本次订阅额外应用轻量表达式过滤。
- `options.query.filter`: 官方格式的订阅过滤，会进入 `@request.query.*` 上下文并参与 access rule / filter 判断。
- `options.query.expand`: 复用记录查询的 relation `expand` 逻辑，事件 payload 的 `record.expand` 会包含可见的关联记录。
- `options.query.fields`: 复用记录查询的字段裁剪逻辑，事件 payload 的 `record` 只返回指定字段。

记录 create/update/delete 后，服务端向匹配的订阅发送事件，事件名为订阅 topic，payload 结构如下：

```json
{
  "action": "create",
  "record": {
    "id": "abc123",
    "title": "Hello"
  }
}
```

权限行为：

- superuser 订阅绕过 collection access rules。
- 普通 auth/public 订阅会复用 access rules 过滤记录：`{collection}/*` 使用 `listRule`，`{collection}/{recordId}` 使用 `viewRule`。
- 同一个 realtime `clientId` 的后续订阅请求必须使用和首次订阅一致的授权身份，否则返回 `403`。
- `options.query` 会作为 `@request.query.*` 参与规则判断；`options.headers` 会解析并保留在订阅对象中，但当前规则引擎还不提供 `@request.headers.*`。
- 非 superuser 收到的记录会隐藏 `hidden` 字段。

当前限制：

- 暂不支持官方 Realtime 的 auth refresh 事件。
- 暂不支持 collection schema 事件。
- 暂不支持 SDK 侧自动重连封装。

### 8.11 Collection Access Rules

集合字段：

- `listRule`
- `viewRule`
- `createRule`
- `updateRule`
- `deleteRule`

语义：

- `null`: 仅 superuser 可访问。
- `""`: 公开访问。
- 非空表达式：作为记录过滤和访问判断。

响应行为：

- `listRule` 为 `null`: 非 superuser 返回 `403`。
- `listRule` 表达式不匹配：列表中不返回该记录。
- `createRule` 表达式不匹配：返回 `400`。
- `viewRule` / `updateRule` / `deleteRule` 表达式不匹配：返回 `404`，避免暴露记录存在性。
- superuser token 绕过 collection access rules。

支持表达式：

```text
public = true || owner = @request.auth.id
status != "archived" && score >= 10
tags ?= "java"
title ~ "pocket"
@collection.news.categoryId ?= id
```

支持上下文：

- 记录字段名，例如 `owner`、`public`、`status`。
- `@request.auth.id`
- `@request.auth.collectionId`
- `@request.auth.collectionName`
- `@request.auth.email`
- `@request.body.<field>`
- `@request.query.<param>`
- `@request.method`
- `@collection.<collection>.<field>` 聚合字段匹配，例如 `@collection.news.categoryId ?= id`

当前限制：

- `@collection.*` 当前按目标集合字段值聚合匹配，暂不支持官方 alias 的同一关联记录相关性约束。
- 暂不支持官方完整 modifier/function 集合。
- 暂不支持官方完整关系展开深层规则和 modifier 组合。
- OAuth2 当前覆盖 collection meta provider metadata、auth collection provider 配置持久化、`auth-methods` authURL/codeVerifier 生成、generic `auth-with-oauth2` code exchange，以及浏览器 callback 页。官方完整 provider 特化逻辑、ExternalAuth 复杂语义、头像文件下载和 MFA 叠加流程仍未实现。

### 8.12 Admin UI

```text
GET /_/
```

当前 Admin UI 已支持在 auth collection 编辑弹窗中配置：

- password auth 开关和 `email` / `username` identity 字段
- OTP 开关、有效期、验证码长度
- MFA 开关和有效期
- OAuth2 provider 多选

Schema 页会读取 `GET /api/collections/{collection}/auth-methods` 并展示当前 auth 方法预览。

UI 源码位于 `UI/`，使用 React + Vite + TypeScript；执行 `cd UI && npm run build` 后，产物会写入 `src/main/resources/pocketbase-admin/`。构建 native image 时，`resource-config.json` 会把该目录资源打进二进制。

当前 UI 覆盖：

- superuser 初始化和登录。
- collection 创建、schema/rules/auth options 编辑、OAuth2 provider 配置与登录测试、删除。
- record 列表、filter/sort/perPage 查询、JSON 编辑、file 字段上传、删除。
- file 字段上传和带 file token 的文件打开。
- backup 创建、上传、下载、恢复和删除。
- structured application/mail/storage/backups settings 页面，包含 SMTP/S3 测试动作和 advanced JSON fallback。
- crons 列表/手动执行、SQL console、logs 列表/详情/stats chart。
- collections export 选择/JSON 预览/复制/下载，以及 import JSON/file 载入、本地 diff review 和导入动作。
