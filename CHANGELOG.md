# Changelog

## 0.2.0-SNAPSHOT

**Features:**
- 完成了对 PocketBase 官方 API 路由结构的一致性测试套件 (Route manifest conformance tests)。
- 实现了 `POST /api/collections/meta/dry-run-view` 视图预览端点接口。
- 增加了完整的 MFA (Multi-Factor Authentication) 多因子认证流程支持，包含初次验证签发 `mfaId`，与二次凭证处理。
- 对内置基于 JSON 存储（`JsonFileStore`）引擎补充并完善了对 `url`, `date`, `text`, `number`, `bool` 等核心数据字段类型的插入约束和格式验证矩阵。
- 在 `CollectionSchema` 中加入了索引配置，并在修改集合 Schema 后自动裁剪过期数据字段完成模拟 Migration。
- 在本地集成了与官方 JS SDK 的 E2E 烟雾测试，确保了记录 CRUD 与 Auth 行为对接顺畅。
- 加入了 `/ping` 健康检查端点以及服务端启动的控制台横幅。

**Admin UI:**
- 完成集合数据导入、导出与本地差异比对与审查流程。
- 实现了集合 Schema 建立与更新中，面向单个字段展开编辑的细化 UI 表单界面（Field-specific collection editor UI）。
- 将内置的系统参数表单（包含应用配置、邮件发信测试、存储 S3 配置、备份操作管理以及定时 Cron 管理等界面），全方位适配并对齐到官方 PocketBase 相应的用户交互逻辑和外观。
- 将客户端 SDK 类统一迁移到了 `client` 包下。
- 将开发文档均重命名为英文命名风格。

## 0.1.0-SNAPSHOT

**Initial Implementation:**
- 初始化 Maven 项目骨架。
- 添加 PocketBase HTTP Client。
- 添加记录 CRUD、密码认证、集合管理基础 API。
- 添加统一异常模型和认证状态管理。
- 添加 JUnit 测试、GitHub Actions、README 和技术规范文档。
