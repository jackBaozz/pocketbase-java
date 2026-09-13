# PocketBase Java Docs

Baseline tracked: PocketBase v0.40.4

The current completed parity baseline is PocketBase v0.40.4.
The v0.40.4 RPD records the completed Java-adapted log isolation, bootstrap cleanup, lifecycle, JS SDK 0.28.1, regression matrix, and v0.4.4 release validation.
The v0.40.3 RPD records the completed index WHERE parenthesis parsing, geoDistance clamping, proxy header ASCII fix, self-referenced cascade delete reload, dark-mode primary-button contrast, and v0.4.3 release validation.
The v0.40.2 RPD tracks index validation, editor autocomplete, and filter compatibility alignment.
The v0.40.0 upgrade documents cover the major feature release; the v0.40.1 RPD tracks its two JSON/OAuth2 regression fixes.
The v0.39.9, v0.39.10, and v0.39.11 documents remain available as historical incremental analyses;
their version references describe the upstream release that was compared at that time.

## 文档索引

| 文档 | 说明 |
|---|---|
| [RPD-PocketBase-v0.40.4-Compatibility-Development-Plan.md](RPD-PocketBase-v0.40.4-Compatibility-Development-Plan.md) | **当前已完成 RPD**：官方 v0.40.3 → v0.40.4 的迁移日志死锁、Bootstrap 清理生命周期和 JS SDK 0.28.1 差异分析，以及 Java 日志隔离、资源清理、测试矩阵与 v0.4.4 发布收口。 |
| [RPD-PocketBase-v0.40.3-Compatibility-Development-Plan.md](RPD-PocketBase-v0.40.3-Compatibility-Development-Plan.md) | **当前已完成 RPD**：官方 v0.40.2 → v0.40.3 差异分析、索引 WHERE 括号非贪婪匹配、geoDistance 浮点截断保护、自引用级联删除 UI 强制刷新、代理头修复、完整验收与 v0.4.3 版本升级。 |
| [RPD-PocketBase-v0.40.2-Compatibility-Development-Plan.md](RPD-PocketBase-v0.40.2-Compatibility-Development-Plan.md) | **已完成基线 RPD**：官方 v0.40.1 → v0.40.2 的索引名称校验、编辑器前缀补全与防抖、任务分解、验收门禁与执行追踪。 |
| [RPD-PocketBase-v0.40.1-Compatibility-Development-Plan.md](RPD-PocketBase-v0.40.1-Compatibility-Development-Plan.md) | **历史基线 RPD**：官方 v0.40.0 → v0.40.1 的两项 JSON/OAuth2 回归修复、任务分解、验收门禁和执行追踪。 |
| `PocketBase-v0.39.11-to-v0.40.0-Difference-Analysis.md` | **差异分析**：官方 v0.39.11 → v0.40.0 最终 tag 净差异，以及对应的 Java/React/多存储映射结论。 |
| `PocketBase-v0.40.0-Upgrade-Development-Plan.md` | **升级开发计划**：已完成的 v0.40.0 API、日志截断、DELETE logs、安全头、在线备份一致性及 Admin UI 开发任务。 |
| `UI-Parity-Gap-Analysis-v0.39.11.md` | 历史基线：Admin UI 与官方 v0.39.11 的增量差异、Java 映射结论和验收记录。 |
| `UI-Parity-Gap-Analysis-v0.39.10.md` | 历史增量：v0.39.9 → v0.39.10 的日志页加载差异及 Java 映射结论。 |
| `UI-Parity-Gap-Analysis-v0.39.9.md` | 历史基线：v0.39.9 的逐模块交互差异分析与修复记录。 |
