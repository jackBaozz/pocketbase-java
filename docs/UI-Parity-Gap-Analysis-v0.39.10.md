# Admin UI 交互差异分析（对照官方 PocketBase v0.39.10）

> 生成于 2026-08-08。
>
> 对照上游标签：[`v0.39.10`](https://github.com/pocketbase/pocketbase/tree/v0.39.10)（`0a74d2f25d6decfc9bd0fc64656ec431f23bf610`），相对 [`v0.39.9`](https://github.com/pocketbase/pocketbase/compare/v0.39.9...v0.39.10) 共 **7 个提交 / 23 个文件**。
>
> 本地核验基线：`dev` 分支 `80d9b6d74b6a8b008cf9b00a71b9f6ccbc3ef7ba`。本轮只分析 `v0.39.9 → v0.39.10` 的**增量**；`UI-Parity-Gap-Analysis-v0.39.9.md` 中已记录的历史差异不重复计数。
>
> **结论：未发现新的 P0/P1 Admin API 契约或主流程缺口；新增 1 项 P2 日志页加载体验差异。** 其余上游变化属于 Go 运行时/依赖维护，不能也不应按文件逐行移植到 Java。

---

## 〇、核验范围与方法

### 官方来源

- [v0.39.10 Release](https://github.com/pocketbase/pocketbase/releases/tag/v0.39.10)：官方发布说明仅列出 CLI panic-recover 回退、日志图表加载占位等 minor UI 改进，以及 `modernc.org/sqlite` 升级。
- [v0.39.9...v0.39.10 比较页](https://github.com/pocketbase/pocketbase/compare/v0.39.9...v0.39.10)：确认变更量为 7 commits / 23 files。
- 上游源码逐文件比较：[`ui/src/logs/logsChart.js`](https://github.com/pocketbase/pocketbase/blob/v0.39.10/ui/src/logs/logsChart.js)、[`logsList.js`](https://github.com/pocketbase/pocketbase/blob/v0.39.10/ui/src/logs/logsList.js)、[`pageLogs.js`](https://github.com/pocketbase/pocketbase/blob/v0.39.10/ui/src/logs/pageLogs.js) 与 [`logs.css`](https://github.com/pocketbase/pocketbase/blob/v0.39.10/ui/src/css/logs.css)。

### 本地核验边界

- Admin UI：`ui/src/App.tsx`、`ui/src/styles.css`、当前日志 API 调用和状态管理。
- HTTP 契约：上游本次 diff 未变更 REST 路由、请求参数、响应结构或官方 JS/Dart SDK；本项目既有 `/api/logs`、`/api/logs/stats` 契约不需要升级。
- Go 专属实现：只判断其是否映射到本项目的公开行为；不把 `*filesystem.File`、Cobra 或 `modernc.org/sqlite` 的内部实现直接翻译成 Java。

### 判定原则

1. **兼容性优先于源码同形**：只有用户可观察行为或 PocketBase HTTP 契约发生变化时，才作为 Java 的必做对齐项。
2. **区分“已具备主能力”和“加载细节缺失”**：本项目日志页已有统计、连续阶梯图、范围筛选、平移、悬浮提示和键盘交互；本次不重复标记这些已覆盖能力。
3. **所有异步状态须按请求代际隔离**：本项目已有 `AbortController` 与 generation guard；后续补 UI 状态时必须沿用，不能让旧请求的计时器或结果覆盖新筛选条件。

---

## 一、v0.39.9 → v0.39.10 上游增量总览

| 类别 | 上游变更 | 对 Admin UI / API 的影响 | Java 项目判定 |
| --- | --- | --- | --- |
| 日志图表 | 首次加载时延迟 250ms 创建空图表占位；列表与统计加载状态分离；空日志列表时折叠图表 | 可见加载状态、避免布局跳动和“空图”误导 | **P2，待补齐** |
| 日志页 CSS | `pending`、`empty-list`、`nonempty-list` 状态控制图表高度，默认最大高度由 200 调整为 180 | 仅服务于上述加载流程 | 随 P2 一并实现 |
| 文件字段核心 | Go `FileField` 忽略未初始化的 `*filesystem.File` 指针 | 不改变 REST/multipart 契约 | **不适用**，见第三节 |
| CLI 执行 | 移除 CLI 命令的 auto panic recover，以保留非零退出码 | 不涉及 Admin UI 或 HTTP API | **不适用**，见第三节 |
| SQLite 依赖 | `modernc.org/sqlite` `v1.54.0 → v1.55.0` | Go SQLite 驱动维护，不是新 UI 行为 | 后端依赖维护项，不纳入 UI 缺口 |
| npm 开发依赖 / `ui/dist` | 锁文件更新并重新构建上游 Svelte UI | 不新增 UI 产品能力 | 仅按本项目自己的 Vite/React 依赖计划维护 |
| 版本常量 | 上游 `PB_VERSION` 更新为 `v0.39.10` | 用于官方 UI 显示与构建标识 | 本项目无对应官方常量，不产生 API 差异 |

### 本次未发生的兼容性变更

- 没有新增、删除或改名的 Admin REST 路由。
- 没有修改 `/api/logs`、`/api/logs/stats` 的参数或响应结构。
- 没有新增集合字段类型、规则语义、认证步骤、文件 URL/token 规则或 SDK 调用方式。
- `ui/dist` 的 hash 文件替换是构建产物，不能被误判为新的页面功能。

---

## 二、可落地差异

### P2-39.10-1：日志图表的首次加载占位与空列表折叠 — 待实现

#### 官方 v0.39.10 行为

上游将日志列表和日志统计的准备状态拆开处理：

1. 进入日志页时，图表先处于 `pending`，避免在列表首屏尚未确定前占用图表高度。
2. 如果统计请求超过 **250ms** 且尚没有统计点，先初始化一个空图表作为稳定的 layout placeholder，并显示 loader。
3. 列表首次加载完成后，延迟一个主线程 tick 再标记 `isFirstLoadReady`，避免图表与列表同时进入造成的布局抖动。
4. 列表为空且没有时间范围缩放时，图表收起；列表非空时，空统计数据仍可显示受控的占位图。
5. 统计加载期间，图表容器使用 `inert` 禁止交互；卸载、失败或后续加载时都会清理占位计时器。

这是一项 UI 稳定性改进，不改变日志查询结果、筛选语义或任何 HTTP 契约，因此定为 **P2** 而非功能/数据类 P0/P1。

#### 当前 Java Admin UI 状态

| 已有能力 | 本地证据 | 判定 |
| --- | --- | --- |
| 同一筛选条件下并行读取列表和统计 | `ui/src/App.tsx:1728-1755` 通过 `Promise.all` 请求 `/api/logs` 和 `/api/logs/stats` | 主数据链路已对齐 |
| 旧请求不会覆盖新路由 | `ui/src/App.tsx:1701-1767` 使用 `AbortController`、load generation 与 cache scope | 优于单纯的无取消加载 |
| 图表能力 | `ui/src/App.tsx:7155-7390` 已有阶梯面积图、窗口平移、范围筛选、双击重置、悬浮提示和键盘选择 | 历史 v0.39.9 能力已覆盖 |
| 空状态 | `ui/src/App.tsx:7307-7309` 在 `stats` 尚未回来时立即渲染 “No log activity” | **与 v0.39.10 加载语义不一致** |
| 容器布局 | `ui/src/styles.css:1755-1771` 固定保留 180px 的蓝色图表条 | **缺少 `pending`/空列表折叠状态** |

当前 `props.loading` 是应用级 loading 信号，并不区分“日志列表正在加载”“统计正在加载”和“首屏是否已准备”。因此慢网络、切换筛选条件或进入没有日志的环境时，管理员会先看到空图提示/固定蓝色区，随后再发生内容替换；这正是官方本次要规避的视觉抖动。

#### 实现方案（功能等价，不照搬 Svelte/uPlot）

1. 在日志加载范围内增加与请求 generation 绑定的状态：
   - `isLogListLoading`
   - `isLogStatsLoading`
   - `isLogFirstLoadReady`
   - `hasLogItems`（必须是当前 route/generation 的结果，不能直接复用上一筛选条件的 `logs.length`）
2. 在 `LogsView` 中增加 250ms 的占位计时器：仅当当前代际仍在等待统计、尚无可展示统计、且日志列表有内容时显示 spinner/空壳；请求结束、abort、筛选切换、组件卸载时清理计时器。
3. 首次日志列表完成后用 `requestAnimationFrame` 或一个 macrotask 再置 `isLogFirstLoadReady`，让列表先稳定落位；不在有缓存的翻页加载中重置首屏状态。
4. 无日志且未缩放时收起图表区；已有统计或当前有时间范围时保留图表。加载中图表应标记 `aria-busy`，并禁用点击范围、平移按钮和键盘命中区，等价于官方 `inert`。
5. 继续复用现有 React/SVG 图表，不引入上游的 uPlot，也不修改 `/api/logs*` 请求格式。新增可见文案必须走 `t("key", "English default")` 并补全全部 9 个 locale 文件。

#### 验收标准

| 场景 | 预期结果 |
| --- | --- |
| 首次进入、`/api/logs/stats` 在 250ms 内完成 | 不闪现 “No log activity”，图表与日志列表稳定显示 |
| 首次进入、统计请求超过 250ms、列表有数据 | 显示不可交互的图表占位和 loader；统计返回后无高度跳变地替换为真实图表 |
| 当前筛选无日志 | 不保留无意义的固定高度蓝色图表条；列表空状态仍能 Clear search / Reset zoom |
| 快速切换筛选或离开页面 | 旧请求、旧 timer、旧统计不得改变新页面的 loader、图表或空状态 |
| 已缓存的“加载更多” | 不重复请求统计，不把已稳定的图表退回 pending |
| 可访问性 | 加载期间图表不可操作且可被辅助技术识别为 busy；加载结束后范围筛选与键盘操作恢复 |

---

## 三、上游非 UI 变更的 Java 映射结论

### 1. Go `*filesystem.File` 空指针防御 — 不适用（无 HTTP 差异）

官方修复的是 Go SDK/内部代码把未初始化的 `*filesystem.File` 放进 `FileField` 值时可能留下 nil 元素的问题。它没有改 multipart 字段名、文件名、响应 JSON 或 `/api/files` 行为。

Java 端没有等价的可空指针对象图：`UploadedFile` 是值 record，构造时已规范化文件名、content type 与字节数组（`src/main/java/io/github/jackbaozz/pocketbase/server/internal/UploadedFile.java:4-15`）；multipart 解析器仅在检测到真实 `filename` 后创建并追加 `UploadedFile`（`MultipartFormData.java:79-84`）。因此不应为了“同步版本”虚构一个 nullable-file 分支。

后续若引入可由插件或 Java SDK 直接组装的可空上传列表，再单独增加“忽略空元素、保留真实文件顺序”的单测即可；当前无需修改公开 API。

### 2. CLI panic recover 回退 — 不适用（不属于 Admin UI）

官方恢复了 Go/Cobra 命令 panic 时以非零退出码退出的旧行为。该变更不触及后台页面、服务端 HTTP 处理器或 JSON 契约。Java 启动与命令行的异常退出策略应在运行时/CLI 专题中独立评估，不能作为 UI parity 缺口计入。

### 3. `modernc.org/sqlite` 升级 — 单独纳入存储兼容性维护

`modernc.org/sqlite v1.55.0` 是 Go 驱动升级；本项目使用 JDBC/jOOQ 与自己的 SQLite、MySQL、PostgreSQL 存储实现，依赖坐标和故障模型均不同。不能把 Go 版本号映射成 Java 依赖版本号。

建议在数据库维护任务中按本项目依赖生命周期处理，并以 SQLite 建库、迁移、文件字段、事务回滚和现有跨数据库测试矩阵为验收依据；它不阻塞本次 v0.39.10 Admin UI 对齐。

### 4. npm 开发依赖及上游 `ui/dist` 重建 — 不直接同步

官方只更新锁文件中的开发期依赖并重新打包其 Svelte UI。本项目的 React/Vite 依赖图独立（见 `UI/package.json`），应基于自身锁文件、Node 版本与构建回归安排维护，不能复制 PocketBase Go 仓库的 `package-lock.json`。

---

## 四、优先级与完成状态

| 优先级 | 项目 | 状态 | 说明 |
| --- | --- | --- | --- |
| P0 | 新增 API 契约破坏 | **无** | 上游 v0.39.10 未修改 Admin REST 契约 |
| P1 | 新增核心交互/数据流程缺口 | **无** | 日志主流程与历史图表能力已在 v0.39.9 基线覆盖 |
| P2 | P2-39.10-1 日志图表首次加载占位与空列表折叠 | **待实现** | 唯一应当落地的 v0.39.10 UI 增量 |
| P3 | CLI、Go file pointer、Go SQLite、上游 dev deps | **不纳入 UI 实现** | 需各自的 Java 运行时/依赖维护专题，而非机械移植 |

> **下一步**：实现 P2-39.10-1 后，执行 `cd UI && npm run build`，并以慢速统计、空日志、快速切换筛选、缓存翻页四类场景完成浏览器回归；构建产物会写入 `src/main/resources/pocketbase-admin/`。

---

## 五、复核命令

```bash
# 确认官方标签与提交
git ls-remote --tags https://github.com/pocketbase/pocketbase.git 'v0.39.10'

# 在 PocketBase 上游源码仓库中查看精确增量
git diff --stat v0.39.9..v0.39.10
git diff v0.39.9..v0.39.10 -- ui/src/logs/logsChart.js ui/src/logs/logsList.js ui/src/logs/pageLogs.js ui/src/css/logs.css

# 本项目实现 P2 后的 UI 构建
cd UI && npm run build
```

> 本文档是 **v0.39.10 增量基线**。它不改写 v0.39.9 历史分析；将来继续升级时，应从本文件的“待实现 / 不适用”结论继续比较，而不是重新把已修复项计入缺口。
