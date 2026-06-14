# pocketbase-java API 设计

## 1. 设计目标

`pocketbase-java` 暴露的是面向 PocketBase 资源的 Java API，而不是直接暴露裸 HTTP。调用方只需要关心集合、记录、认证和错误处理。

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
));
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
));
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

### 4.2 查看认证方法

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
JsonNode collection = client.collections().getOne("posts");
JsonNode created = client.collections().create(collectionBody);
JsonNode updated = client.collections().update("posts", patchBody);
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
| `collection(name).create(body)` | `POST /api/collections/{collection}/records` |
| `collection(name).update(id, body)` | `PATCH /api/collections/{collection}/records/{id}` |
| `collection(name).delete(id)` | `DELETE /api/collections/{collection}/records/{id}` |
| `collection(name).authWithPassword(identity, password)` | `POST /api/collections/{collection}/auth-with-password` |
| `collection(name).listAuthMethods()` | `GET /api/collections/{collection}/auth-methods` |
| `collections().list()` | `GET /api/collections` |

官方文档：

- https://pocketbase.io/docs/
- https://pocketbase.io/docs/api-records/
- https://pocketbase.io/docs/api-collections/
