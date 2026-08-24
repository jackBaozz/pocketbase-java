# Admin UI 交互差异分析（对照官方 PocketBase v0.39.11）

> 生成于 2026-08-24。
>
> 对照上游标签：[`v0.39.11`](https://github.com/pocketbase/pocketbase/tree/v0.39.11)（提交
> [`5d217dd`](https://github.com/pocketbase/pocketbase/commit/5d217ddb50cb144d80a5d0b0bdf11b52b2c3e457)），相对
> [`v0.39.10`](https://github.com/pocketbase/pocketbase/compare/v0.39.10...v0.39.11) 共 **10 个提交 / 32 个文件**。
>
> 本地核验基线：`dev` 分支 `1ace1db6d342df4d00252528809dabb20d76eee1`。本文件只分析
> `v0.39.10 → v0.39.11` 的增量；v0.39.9 与 v0.39.10 的历史差异分别保留在对应文档中，不重复计数。
>
> **结论：本版本没有新增 Admin REST 契约变更。** v0.39.11 的可观察 Admin UI 增量主要是 API Preview 示例、代码编辑器键盘逃逸、集合复制时 relation 目标编辑、搜索归一化以及少量拖拽/依赖维护。前四项在 Java Admin UI 已有功能等价实现；搜索转义和 v0.39.10 日志图表占位仍应作为后续回归项，不应在文档中宣称“所有差异已完成”。

---

## 〇、核验范围与方法

### 官方来源

- [v0.39.11 Release](https://github.com/pocketbase/pocketbase/releases/tag/v0.39.11)：官方说明包含 API Preview 示例修复、TAB trap 的 ESC 逃逸、拖拽事件及集合复制等 minor UI 改进，同时包含 JS SDK、Go 依赖和发布工具维护。
- [v0.39.10...v0.39.11 比较页](https://github.com/pocketbase/pocketbase/compare/v0.39.10...v0.39.11)：确认本次增量为 10 commits / 32 files。
- 相关上游提交：[`fixed API preview examples`](https://github.com/pocketbase/pocketbase/commit/4076537a2cdd603447a247ec5133c44f1eefcbb6)、[`minor ui fixes`](https://github.com/pocketbase/pocketbase/commit/6212276c824df8a6835aa2e158493320cc149d1a)、[`duplicated collection relation fields`](https://github.com/pocketbase/pocketbase/commit/3668e1c431d9c58f1d569d0349cb551ef358de18) 和 [`use pb.filter in search normalization`](https://github.com/pocketbase/pocketbase/commit/82368a61520bc8f47acc1941f17134ee9b9db110)。

### 本地核验边界

- Admin UI：`ui/src/App.tsx`、`ui/src/components/ApiPreview.tsx`、`ui/src/components/CodeEditor.tsx`、`ui/src/components/FieldEditor.tsx`、`ui/src/components/IndexManager.tsx`、`ui/src/components/RelationPicker.tsx`。
- HTTP 契约：比较上游变更是否涉及 REST 路由、请求参数、响应结构、认证步骤或文件 URL；本次未发现需要升级 Java API 的变化。
- Go 专属实现：Go 依赖、Cobra/发布动作、上游 JS SDK 和 Svelte 构建产物不直接翻译成 Java 代码；只记录其是否产生 Java 用户可观察行为。

### 判定原则

1. 以用户可观察行为和 PocketBase HTTP 契约为准，不追求 Go/Svelte 源码逐行相同。
2. “已有功能等价”不等于“本次提交逐文件移植”；必须保留本地 React、Java API 和 GraalVM 的实现边界。
3. 任何异步搜索或拖拽状态都必须清理旧请求/旧事件，不能让历史结果覆盖当前筛选条件。

---

## 一、v0.39.10 → v0.39.11 上游增量总览

| 类别 | 上游变更 | 对 Admin UI / API 的影响 | Java 项目判定 |
| --- | --- | --- | --- |
| API Preview | 修正 JS/Dart/curl 示例，并为 auth create 示例使用实际记录邮箱发送验证请求 | 代码示例可复制运行；不改变 REST 契约 | **已对齐**，`ApiPreview.tsx` 使用创建后的 `record.email` / `record.get<String>('email')` |
| 代码编辑器 | 规则字段的 TAB focus trap 可用 ESC 退出 | 键盘用户可以把焦点移出编辑器；不改变规则语义 | **已对齐**，`CodeEditor.tsx` 的 ESC 关闭缩进 trap，下一次 Tab 恢复编辑器缩进 |
| 集合复制 | 新复制的集合允许修改 relation 字段的目标集合 | 只影响新集合编辑，不改变已持久化字段的目标 | **已对齐**，复制 payload 去掉字段 id，`FieldEditor` 仅对已有字段锁定目标 |
| 搜索归一化 | 上游改用 `pb.filter` 构造通配搜索过滤器 | 普通词搜索的引号/特殊字符转义更稳健；显式表达式仍透传 | **功能基本对齐，转义待回归**，Java UI 自有 `normalizeSearchTerm` / `relationSearchFilter`，未引入 JS SDK |
| 拖拽与键盘细节 | sortable `dragend` 清理、dropdown/editor minor fixes | 只影响交互状态清理 | **已具备等价清理**，字段、索引和 relation chip 均在 `onDragEnd` 清理拖拽状态 |
| JS SDK / npm / shablon | 上游 SDK、开发依赖和模板更新 | 不改变 Java Admin API；本项目依赖图独立 | **不直接同步**，按 Java/React 自身依赖生命周期维护 |
| Go 版本与依赖 | Go action、`golang.org/x/*` 和发布回溯记录更新 | 不涉及 Admin UI 或 Java 服务运行时 | **不适用**，不映射为 Java 版本号 |

### 本次未发生的兼容性变更

- 没有新增、删除或重命名 Admin REST 路由。
- 没有修改 `/api/collections/{collection}/records`、`/api/logs`、认证、文件 token 或 SSE 的请求/响应契约。
- 没有新增集合字段类型或改变 `null` / `""` API 规则语义。
- `ui/dist`/嵌入资源的 hash 文件变化只是构建产物，不应被当作新的产品功能。

---

## 二、Java Admin UI 已对齐的增量

### P1-39.11-1：API Preview 示例 — ✅ 已完成

`ui/src/components/ApiPreview.tsx` 的 auth collection create 示例在创建记录后使用返回的记录邮箱：

- JavaScript：`requestVerification(record.email)`；
- Dart：`requestVerification(record.get<String>('email'))`。

这样示例不会再把固定的 `test@example.com` 当成当前创建记录的邮箱；Java REST 端点和 SDK 调用方式保持不变。已在提交 `2d8c4212` 中完成并重新构建嵌入式 Admin UI。

### P1-39.11-2：CodeEditor 的 ESC 逃逸 — ✅ 已完成

`ui/src/components/CodeEditor.tsx` 在多行编辑器中维护 TAB 缩进状态：

1. 默认按 Tab/Shift+Tab 继续缩进；
2. 按 ESC 后释放 focus trap，下一次 Tab 可将焦点交给页面其他控件；
3. 编辑器再次收到普通键盘输入后恢复 TAB 缩进行为；
4. 补全菜单打开时，ESC 仍优先关闭补全，不会误触发外层关闭。

该实现不依赖第三方编辑器，也不改变规则/SQL/JSON 内容。

### P1-39.11-3：复制集合的 relation 目标 — ✅ 已完成

复制集合时，`duplicateCollectionPayload` 会移除源字段的持久化 id；新集合中的 relation 字段因此可在创建前选择目标集合。已存在的字段仍按 `FieldEditor` 规则锁定 relation 目标，避免改变已经存储的数据关系。view 集合继续使用空字段列表并保留 Java 端的 view 约束。

### P2-39.11-4：拖拽结束状态清理 — ✅ 已完成

字段、索引和 relation chip 的拖拽控件都在 `onDragEnd` 中清理 dragging/drop target 状态；放置成功时同时更新顺序并清除落点提示。该实现保留本项目的键盘可达性和自定义拖拽手柄，不引入上游 Svelte sortable 实现。

---

## 三、仍需回归或后续维护的项目

### P2-39.11-1：搜索归一化的特殊字符转义 — 部分对齐

官方 v0.39.11 使用 `pb.filter` 对普通搜索词进行参数化转义。本项目为了保持零 JS SDK 运行时依赖，在 `App.tsx` 与 `RelationPicker.tsx` 中生成等价的 `field~"term"` 表达式。普通词、数字、布尔值、`field:value` 和显式 PocketBase filter 已覆盖，但带内嵌引号、反斜杠或极端长度的输入还应增加针对 Java `FilterToSqlCompiler` 的回归测试。

**验收标准：** 普通搜索不改变现有命中；特殊字符不会改变过滤表达式结构；超长 relation 搜索仍遵守服务端 filter 长度上限；旧搜索请求不能覆盖新筛选结果。

### P2-39.10-1：日志图表首次加载占位 — 延续历史待办

`docs/UI-Parity-Gap-Analysis-v0.39.10.md` 中记录的统计加载占位、空日志列表折叠和 `aria-busy` 交互，未因 v0.39.11 发布而自动完成。它不是 v0.39.11 新增的 API 缺口，但在当前基线中仍应保持为明确待办，完成后再更新本节和 v0.39.10 历史文档的结论链接。

### P3：上游 Go/SDK/依赖维护 — 不纳入 UI 实现

- Go `golang.org/x/*` 和 GitHub Action 版本按 Java 项目的 CI 运行时另行维护；
- 上游 JS SDK 更新不意味着要把 SDK 打包进 Java Admin UI；
- shablon、npm lockfile 和 `ui/dist` 只在本项目自身依赖审查和构建验证通过后更新；
- 动态插件、TinyMCE 生态等 Java 边界仍按 v0.39.9 基线文档的排除项处理。

---

## 四、优先级与完成状态

| 优先级 | 项目 | 状态 | 说明 |
| --- | --- | --- | --- |
| P0 | 新增 Admin API 契约破坏 | **无** | v0.39.11 未修改 REST/认证/文件契约 |
| P1 | API Preview、ESC 逃逸、复制 relation 目标、拖拽清理 | **已完成** | 已在当前 `dev` 代码中核验 |
| P2 | 搜索特殊字符转义 | **部分对齐 / 待回归** | 保持自有 Java 过滤器实现，补充边界测试 |
| P2 | 日志图表 pending/空列表折叠 | **延续待办** | 详见 v0.39.10 增量文档 |
| P3 | Go/JS SDK/构建依赖维护 | **不纳入 UI 移植** | 按各自生命周期单独管理 |

> **当前基线结论**：`v0.39.11` 是本项目唯一当前 PocketBase 对标版本。旧版文档中的 v0.39.9/v0.39.10 仅描述当时的历史比较范围，不代表当前基线。

---

## 五、复核命令

```bash
# 确认官方标签与提交
git ls-remote --tags https://github.com/pocketbase/pocketbase.git 'v0.39.11'

# 在 PocketBase 上游源码仓库中查看 v0.39.10 → v0.39.11 增量
git diff --stat v0.39.10..v0.39.11
git diff v0.39.10..v0.39.11 -- ui/src/apiPreview ui/src/base ui/src/collections ui/src/utils.js

# 本项目 UI 及文档核验
cd ui && npm run build
cd .. && git diff --check
```

> 本文是当前 v0.39.11 增量基线。后续升级时，应从本文件的“部分对齐 / 延续待办”结论继续比较，不要把 v0.39.9/v0.39.10 已完成项重新计入缺口。
