# AI Agent Development Standards (AGENTS.md)

本文件定义了 AI 助手（如 Antigravity）在参与本项目开发时必须遵循的环境标准和规范。

## 📌 接口设计规范

### 1. API 路径与规范保持一致
- **强制要求**：本项目作为 PocketBase 的 Java 实现，所有的 API 路由路径、请求参数、返回数据结构以及 HTTP 方法，**必须**与 PocketBase 原版完全保持一致。
- **目的**：确保能够直接兼容并复用现有的 PocketBase 官方 SDK（如 JS/Dart SDK），实现客户端的无缝接入。
