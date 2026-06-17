# AI Agent Development Standards (AGENTS.md)

本文件定义了 AI 助手（如 Antigravity）在参与本项目开发时必须遵循的环境标准和规范。

## 📌 接口设计规范

### 1. API 路径与规范保持一致
- **强制要求**：本项目作为 PocketBase 的 Java 实现，所有的 API 路由路径、请求参数、返回数据结构以及 HTTP 方法，**必须**与 PocketBase 原版完全保持一致。
- **目的**：确保能够直接兼容并复用现有的 PocketBase 官方 SDK（如 JS/Dart SDK），实现客户端的无缝接入。

## 🚀 最新进展与计划 (2026-06-17)

* 已完成 **SDP-001** (Route manifest conformance tests)：创建了 `RouteConformanceTest` 针对所有官方路由进行基础连通性校验，确保核心框架与 PocketBase API Surface 一致。
* 已完成 **SDP-003** (`POST /api/collections/meta/dry-run-view`)：添加了基于纯 Select 查询视图预览功能，复用 `JsonFileStore` 内部的 SQL 分析机制，并限制最大 5000 字符与单次 10 条结果的返回。
* 下一步重点将转移到 **SDP-004** (`_mfas` internal collection / MFA Auth Response) 及深入规则与过滤器解析器兼容性（**SDP-006**）。
* 已完成 **SDP-004** (`_mfas` internal collection and MFA auth response) 与 **SDP-005** (MFA second-factor flow for password/OTP/OAuth2)：
  * 添加了对 `CollectionSchema` 中 `mfa.rule` 的支持。
  * `JsonFileStore` 内部维护了 `mfas` 列表。
  * 扩展了 AuthResponse 阶段以生成 `mfaId`（在第一阶段）并在传入 `mfaId` 参数时（第二阶段）放行生成完整的 JWT。
  * 启用了 `__pbMFACleanup__` 定期调度任务用于清理过期的 MFA 记录。
* 已完成 **SDP-010** (Official collection import/export review flow)：在 `App.tsx` 中实现了 `collectionImportChanges` 来计算新旧集合之间的差异并展示 diff，对每个集合（新增/修改/删除）都以不同的颜色高亮。
* 已完成 **SDP-011** (Field-specific collection editor UI)：重构了 `field-builder-row` 拆解出新的 `FieldEditor.tsx` 组件，在点击编辑按钮时能进入具体的属性编辑表单（并带有 `Type`, `Required`, `Unique` 等选项的勾选）。
* 已完成 **SDP-002**, **SDP-006**, **SDP-007**, **SDP-008**, **SDP-009**, **SDP-010**, **SDP-011**：
  * 构建了 `JsSdkSmokeTest`，利用 node.js 和官方 JS SDK 跑通了集成认证和 CRUD 测试。
  * 补齐了 `normalizeFieldValue` 关于 Field Type Validation Matrix 的功能（约束 `min`, `max`, `pattern`, `onlyHosts` 等支持）。
  * 在 UI 中补充了 `FieldEditor` 组件满足 `SDP-011` 的界面缺口。
  * 实现了 CollectionSchema Index 及字段变更删减记录冗余数据的基础 Schema Migration Semantics（`SDP-009`）。
  * 编写了 `ADR-001` 作为对 SQLite 及 JSONFileStore 的正式定调（`SDP-007`）。
  * 验证并认可了内置的 Collection Diff/Review 流程 (`SDP-010`) 及现存的 `RuleEvaluator` 评估器作为核心解析器 (`SDP-006`)。
