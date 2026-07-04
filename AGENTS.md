# AI Agent Development Standards (AGENTS.md)

本文件定义了 AI 助手（如 Antigravity）在参与本项目开发时必须遵循的环境标准和规范。

## 📌 接口设计规范

### 1. API 路径与规范保持一致
- **强制要求**：本项目作为 PocketBase 的 Java 实现，所有的 API 路由路径、请求参数、返回数据结构以及 HTTP 方法，**必须**与 PocketBase 原版完全保持一致。
- **目的**：确保能够直接兼容并复用现有的 PocketBase 官方 SDK（如 JS/Dart SDK），实现客户端的无缝接入。

## 🚀 最新进展与计划 (2026-07-04)

- **Phase 2, Phase 3 & Phase 4 兼容性里程碑全部顺利完成**：
  - **存储层重构 & 关系数据库支持**：引入了统一的 `StorageEngine` SPI，在默认支持零依赖本地 JSONL 文件存储（通过 `JsonFileStore` 兼容旧 `.json` 数组）的基础上，成功实现了 SQLite (`SqliteStorageEngine`)、MySQL (`MysqlStorageEngine`) 和 PostgreSQL (`PostgresStorageEngine`) 关系型数据库底层，并使用 jOOQ 实现方言适配与 Schema 自动迁移。
  - **对象存储与备份 S3 支持**：实现统一的 `FileStorageProvider` SPI，全面集成 AWS S3 及兼容的 S3 备份存储，并在备份 ZIP 生成阶段采用了磁盘临时流式写入以规避 OutOfMemory 错误。
  - **邮件分发 (SMTP)**：完成了基于 SSL/TLS 加密及模板替换的 SMTP 邮件递送引擎，并提供开发测试下的 outbox 日志预览。
  - **认证流程闭环**：支持 OTP (`_otps` 表持久化)、MFA 二次验证以及完全的 OAuth2 Provider 授权交换流。
  - **SSE 实时流与 Batch 事务**：完全对齐 PocketBase 官方 SSE 订阅协议，并在 `/api/batch` 接口中实现了严格的数据库与文件回滚机制。
  - **超级管理员 SQL API**：实现 `/api/sql` 专用执行端点，支持方言统一映射、语法过滤与事务边界。
  - **Admin UI 深度对齐**：实现了 Hash 路由、集合 Schema 编辑器、关系属性选择器、高级记录检索与 System Settings 设置面板。
  - **GraalVM 原生编译验证**：完成了 `sh/build-native.sh` 脚本在 Darwin 与 Linux 环境下的 Native Image 编译验证，所有 JDBC、jOOQ 以及 Jackson 反射配置均已注册完成。

## 🛠️ 构建与编译 (Build Commands)

- **普通打包**：`mvn clean package`
- **Native 编译 (GraalVM)**：运行 `sh/build-native.sh`。该脚本会自动寻找 `mise` 中的 `25-graalvm`，并使用 GraalVM Native Image 进行编译，跳过单元测试（可在命令行传入其他参数覆盖）。

