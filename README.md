# pocketbase-java

轻量级 PocketBase Java SDK。项目基于 JDK 自带 `HttpClient` 封装 PocketBase REST API，运行时只依赖 Jackson，适合在普通 JVM 服务、命令行工具、桌面应用和 GraalVM Native Image 场景中作为基础客户端使用。

> 当前版本是基础骨架，已覆盖认证、记录 CRUD、集合管理、错误模型、测试和文档。后续可继续补齐文件上传、Realtime、批处理、类型化 Record 映射等能力。

---

## 核心特性

- **低依赖**: HTTP 使用 `java.net.http.HttpClient`，运行时仅引入 Jackson 处理 JSON。
- **标准 API 映射**: 覆盖 `/api/collections/{collection}/records`、`auth-with-password` 和集合管理接口。
- **认证状态管理**: `AuthStore` 自动保存登录 token，后续请求自动带 `Authorization: Bearer ...`。
- **异常可诊断**: 非 2xx 响应抛出 `PocketBaseException`，保留状态码、URL、原始响应体和 PocketBase 错误结构。
- **GraalVM 友好**: 不使用动态代理，核心模型使用 record/普通类，反射面由 Jackson 限定。

---

## 环境要求

| 类别 | 要求 |
| --- | --- |
| JDK | 17+ |
| Maven | 3.9+ |
| PocketBase | 建议使用当前官方稳定版本 |

如果本机 Maven Central 访问不稳定，可以使用项目内置镜像配置：

```bash
mvn -gs settings.xml -s settings.xml test
```

---

## 快速开始

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
├── src/
│   ├── main/java/io/github/jackbaozz/pocketbase/
│   │   ├── AuthResponse.java
│   │   ├── AuthStore.java
│   │   ├── CollectionService.java
│   │   ├── CollectionsService.java
│   │   ├── ListOptions.java
│   │   ├── PocketBaseClient.java
│   │   ├── PocketBaseError.java
│   │   ├── PocketBaseException.java
│   │   ├── RecordList.java
│   │   └── RecordQuery.java
│   └── test/java/io/github/jackbaozz/pocketbase/
│       └── PocketBaseClientTest.java
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

# 打包 jar、sources jar、javadoc jar
mvn -gs settings.xml -s settings.xml clean package

# 安装到本地 Maven 仓库
mvn -gs settings.xml -s settings.xml clean install
```

---

## 文档

- [技术架构与开发规范](docs/技术架构与开发规范.md)
- [API 设计](docs/API设计.md)
- [PocketBase 官方文档](https://pocketbase.io/docs/)

---

## License

MIT License
