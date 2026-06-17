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
