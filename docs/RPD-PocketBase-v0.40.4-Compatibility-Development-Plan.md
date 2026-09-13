# RPD：PocketBase v0.40.4 差异分析与兼容升级开发计划

> - 文档版本：1.0
> - 核对日期：2026-09-13
> - 目标分支：`dev`
> - Java 项目基线：`63e2a05a2f8dcbca1d64f9469a591bbd49798930`，pocketbase-java `v0.4.3`
> - 升级前已完成兼容基线：PocketBase `v0.40.3`
> - 本轮工作区目标版本：pocketbase-java `v0.4.4`（未提交）
> - 上游范围：PocketBase `v0.40.3` (`5684ee24f1e88f71a2ede73d20cf547fbd509e16`) → `v0.40.4` (`5cec579da984436a258602a46a96302fbd31f77c`)
> - 文档状态：**差异分析、开发任务与本地验收已完成；Git 发布动作尚未执行**
> - 发布版本：pocketbase-java `v0.4.4`（工作区未提交，待后续明确授权后提交/推送）

## 1. 结论摘要

PocketBase `v0.40.4` 发布于 2026-09-12，是一次范围很小但涉及运行时可靠性的补丁发布。官方 `v0.40.3...v0.40.4` 共 **4 个提交、22 个文件**，实质变化集中在以下三点：

1. 修复迁移执行期间批量日志写入 AUX 数据库可能造成的死锁；普通批量/定时日志刷新改为非阻塞写入，只有显式携带 `logger.BlockKey=true` 时才等待写入完成。
2. 将 `ResetBootstrapState()` 软废弃为 `ClearBootstrap()`，并增加最终命名为 `OnBootstrapClear()` 的资源清理 Hook；Cron、日志 ticker、剩余日志刷新和数据库关闭被纳入同一 Bootstrap 清理生命周期。
3. 更新 `golang.org/x/*` 依赖、官方 Admin UI 使用的 JS SDK `0.28.0 → 0.28.1`，并刷新版本号和预编译资源。

本次上游没有新增或修改 REST 路由、HTTP 方法、请求参数、响应结构、集合字段或鉴权规则。因此，本系统不需要调整对外 HTTP 契约，也不应复制 Go/JSVM 专用代码或官方 Svelte `ui/dist` 文件。

对本系统的适用性结论如下：

| 上游变化 | 本系统现状 | 应用结论 |
|---|---|---|
| 迁移期间日志写入死锁 | 启动迁移发生在 HTTP 服务和活动日志启用前；请求日志位于 `HttpApi.handle()` 的 `finally`，路由事务返回后才写入，因此**不存在上游完全相同的同线程 AUX 事务死锁路径**。但关系型和 JSONL 请求日志当前都是同步持久化，仍可能让请求线程等待并发 DDL/迁移锁或慢磁盘。 | **适配实现，P0**：用有界、单消费者的非阻塞日志派发器隔离请求线程，并在关闭数据库前排空已接收日志；不照搬 Go `context BlockKey`。 |
| `ClearBootstrap` 与 `OnBootstrapClear` | Java 侧只有一次性 `LocalPocketBase.start()` / `close()`；没有 `ResetBootstrapState`、重新 Bootstrap 或 Hook 体系。升级前 `close()` 不是显式幂等的，启动中途失败也没有统一反向清理。 | **语义对齐，P0**：保留 Java `AutoCloseable` API，不新增名不副实的 Go 同名方法；实现启动失败回滚、幂等关闭和统一资源清理顺序。 |
| 关闭前停止 Cron、刷新日志、关闭数据库 | `RelationalStorageEngine.close()` 已按 Cron → DB 顺序关闭，`AsyncJobRunner.close()` 会等待已接收任务；但尚无日志队列可刷新，`RealtimeHub` 也没有整体关闭入口。 | **补齐生命周期，P0**：顺序调整为停止接流量 → 关闭 SSE 客户端 → 排空 HTTP/日志任务 → 停止 Cron → 关闭存储。 |
| 官方 UI 的 JS SDK `0.28.1` | 本项目 React Admin UI 使用自有 `fetch` 封装，不依赖 `pocketbase` npm 包；但 `src/test/resources/js-sdk-smoke` 仍锁定官方 JS SDK `0.27.0`。 | **升级测试基线，P1**：只将官方 SDK smoke 固定升级到 `0.28.1`，继续覆盖 `pb.filter()` 特殊占位符场景；不向 React UI 引入无消费者依赖。 |
| Go 依赖、JSVM 类型、官方 Svelte 产物 | Java/Maven/React 技术栈没有对应消费者。 | **不移植**：仅记录上游证据，不触发无关依赖升级。 |

本轮已按第 5 节任务完成 Java 侧适配、测试与版本收口，并将 `docs/README.md` 的完成基线更新为 `v0.40.4`。本文件的完成结论不包含 Git 提交、推送或 `main` 合并；这些动作仍需单独授权。

---

## 2. 官方基线与证据

### 2.1 版本证据

| 对象 | Tag / Commit | 发布时间（UTC） | 说明 |
|---|---|---|---|
| 对比起点 | `v0.40.3` / `5684ee24f1e88f71a2ede73d20cf547fbd509e16` | 2026-09-06 17:33:10 | 本项目升级前已完成兼容基线 |
| 对比终点 | `v0.40.4` / `5cec579da984436a258602a46a96302fbd31f77c` | 2026-09-12 14:35:16 | 本轮目标上游版本 |
| Java 基线 | `dev` / `63e2a05a2f8dcbca1d64f9469a591bbd49798930` | — | 项目版本 `v0.4.3`，工作区分析起点 |

官方依据：

- [PocketBase 官网](https://pocketbase.io/)
- [PocketBase v0.40.4 Release Notes](https://github.com/pocketbase/pocketbase/releases/tag/v0.40.4)
- [PocketBase v0.40.3...v0.40.4 Compare](https://github.com/pocketbase/pocketbase/compare/v0.40.3...v0.40.4)
- [迁移日志死锁 Issue #7836](https://github.com/pocketbase/pocketbase/issues/7836)
- [PocketBase v0.40.4 CHANGELOG.md](https://github.com/pocketbase/pocketbase/blob/v0.40.4/CHANGELOG.md)
- [JS SDK v0.28.1 Release Notes](https://github.com/pocketbase/js-sdk/releases/tag/v0.28.1)

### 2.2 上游提交

| Commit | 上游说明 | 影响 |
|---|---|---|
| `114c01ac1212` | `[#7836] fixed migration logs write deadlock and added app.ClearBootstrap/OnClearBootstrap helpers` | 日志非阻塞写入、关闭前日志刷新、Bootstrap 清理入口与 Hook、Cron/DB/ticker 清理顺序及测试 |
| `adf4eb822fbd` | `renamed OnClearBootstrap to OnBootstrapClear for consistency with the other hooks` | Hook 最终源码名称改为 `OnBootstrapClear()` |
| `5f496665c729` | `bumped golang.org/x/* deps` | Go 依赖更新，与 Java 运行时无直接映射 |
| `5cec579da984` | `bumped app version` | `v0.40.4` 版本元数据与官方 UI 构建产物 |

> **命名勘误**：`v0.40.4` 的最终 Release Notes 和源码使用 `OnBootstrapClear()`；tag 中 `CHANGELOG.md` 仍写成 `OnClearBootstrap()`。本报告以最终源码及重命名提交 `adf4eb822fbd` 为准。

### 2.3 22 个变更文件分类

| 类别 | 文件 | 数量 | Java 映射 |
|---|---|---:|---|
| 核心运行时与扩展 API | `core/app.go`、`core/base.go`、`pocketbase.go`、`tools/logger/batch_handler.go` | 4 | 需要分析并以 Java 生命周期/并发模型适配 |
| 核心测试与测试基座 | `core/base_test.go`、`core/log_printer_test.go`、`core/system_alert_test.go`、`tests/app.go`、`plugins/jsvm/binds_test.go` | 5 | 转化为日志并发、关闭排空、重复关闭与启动失败回归 |
| JSVM 生成类型 | `plugins/jsvm/internal/types/generated/types.d.ts` | 1 | 本项目无 JSVM，不移植 |
| 发布日志与 Go 依赖 | `CHANGELOG.md`、`go.mod`、`go.sum` | 3 | 仅记录；不据此升级 Maven 依赖 |
| 官方 UI 配置/依赖 | `ui/.env`、`ui/package.json`、`ui/package-lock.json` | 3 | 版本元数据需映射；JS SDK 只进入本项目 smoke fixture |
| 官方 UI 预编译产物 | `ui/dist/index.html` 及 5 个 JS asset | 6 | 本项目为 React UI，不复制 hash 文件；如更新版本文案则按本项目流程重新构建 |

---

## 3. 本系统现状代码证据

> 本节记录的是实施前基线，用于说明为何采用 Java 等价适配；当前实现与验收结果以第 5、6 节为准。

### 3.1 启动迁移、事务与活动日志时序

当前关系型启动路径为：

1. [`LocalPocketBase.start()`](../src/main/java/io/github/jackbaozz/pocketbase/server/LocalPocketBase.java) 先调用 `RelationalStorageEngine.open()`。
2. [`RelationalStorageEngine`](../src/main/java/io/github/jackbaozz/pocketbase/server/internal/RelationalStorageEngine.java) 构造器创建 `JooqDatabase` 和各 Repository，随后立即执行 `bootstrapSystemTables()`；其中包含系统表创建、字段升级、系统集合 ID 迁移和索引回填。
3. 上述迁移完成后才创建 `RealtimeHub`、`HttpServer`、HTTP executor 和 `HttpApi`，最后调用 `server.start()`。
4. [`HttpApi.handle()`](../src/main/java/io/github/jackbaozz/pocketbase/server/internal/HttpApi.java) 在请求处理完成后的 `finally` 中调用 `store.recordActivityLog()`；`/api/batch` 等事务已经从 `store.transactional()` 返回。
5. [`JooqDatabase.transactional()`](../src/main/java/io/github/jackbaozz/pocketbase/server/internal/JooqDatabase.java) 使用 `ThreadLocal<Connection>` 复用同线程事务连接，并在返回前提交/回滚和清理 ThreadLocal。
6. 迁移/Repository 内部错误通过 [`SecuritySupport.logInternalFailure()`](../src/main/java/io/github/jackbaozz/pocketbase/server/internal/SecuritySupport.java) 安全输出到 `System.err`，不会进入 `_logs` 持久化路径。

因此，上游“迁移内部触发批量日志阈值 → 同步等待另一个 AUX 写事务”的闭环在当前 Java 代码中不存在，不能把 Issue #7836 直接描述为本项目的已复现缺陷。

不过，当前日志路径仍有适合本系统的改进空间：

- [`LogRepository.recordActivityLog()`](../src/main/java/io/github/jackbaozz/pocketbase/server/internal/repository/LogRepository.java) 直接在请求线程执行 `_logs` INSERT；SQLite 并发 DDL/写事务可使它等待 `busy_timeout`，外部数据库也可能等待连接池或行/表锁。
- [`JsonFileStore.recordActivityLog()`](../src/main/java/io/github/jackbaozz/pocketbase/server/internal/JsonFileStore.java) 是 `synchronized` 方法，并且每条请求日志都会重写 `logs.json`；慢磁盘会直接占用 HTTP worker。
- 日志写入位于 `exchange.close()` 之前。虽然响应头和响应体已经发送，处理线程和 exchange 仍可能被日志 I/O 延迟占用。
- 当前没有统一的“停止接收日志 → 排空 → 关闭存储”协议；如果后续采用异步日志，必须先补齐该协议，不能通过 daemon 线程静默丢失尾部日志。

### 3.2 Bootstrap 清理与资源所有权

当前资源关系如下：

- [`LocalPocketBase.close()`](../src/main/java/io/github/jackbaozz/pocketbase/server/LocalPocketBase.java) 依次停止 `HttpServer`、等待/中断 HTTP executor，再调用 `store.close()`。
- [`RelationalStorageEngine.close()`](../src/main/java/io/github/jackbaozz/pocketbase/server/internal/RelationalStorageEngine.java) 已先等待 `cronRunner`，再关闭 Hikari 数据源；[`JsonFileStore.close()`](../src/main/java/io/github/jackbaozz/pocketbase/server/internal/JsonFileStore.java) 只关闭 `cronRunner`。
- [`AsyncJobRunner.close()`](../src/main/java/io/github/jackbaozz/pocketbase/server/internal/AsyncJobRunner.java) 会拒绝新任务并等待已接收任务，具备可复用的 drain 语义。
- [`RealtimeHub`](../src/main/java/io/github/jackbaozz/pocketbase/server/internal/RealtimeHub.java) 只在单个 SSE 客户端退出或发送失败时关闭连接，没有服务器级 `close()` 来主动唤醒全部 heartbeat 等待者。
- `StorageEngine.close()` 是默认空实现，`LocalPocketBase.close()` 没有原子状态保护；当前也没有专门测试重复 `close()`。
- 如果 `RelationalStorageEngine.open()` 已创建 Hikari pool、但 `bootstrapSystemTables()` 抛出异常，或者 `LocalPocketBase.start()` 在 store 创建后因应用名更新、端口绑定、executor/HTTP 启动失败而退出，现有代码没有统一的逆序释放栈。

Java 当前不支持“同一个 App 对象清空后再次 Bootstrap”的运行模型，也没有对应的 `OnBootstrap` 扩展 Hook。故本轮应对齐**资源清理语义**，而不是添加一个只有名字相同、却无法重新初始化的 `ClearBootstrap()` 公共方法。对嵌入式调用方，`AutoCloseable`、`try-with-resources` 和幂等 `close()` 仍是正确的 Java API。

### 3.3 Admin UI 与官方 JS SDK

- [`ui/package.json`](../ui/package.json) 不包含 `pocketbase` npm 依赖；React Admin UI 使用 `App.tsx` 中的本地请求封装。官方 Svelte UI 的 dependency bump 不应直接复制到这里。
- [`src/test/resources/js-sdk-smoke/package.json`](../src/test/resources/js-sdk-smoke/package.json) 在本轮前声明 `pocketbase: ^0.27.0`，lock 文件实际锁定 `0.27.0`；当前 package 与 lock 已固定为官方 v0.40.4 UI 使用的 `0.28.1`，测试启动时用 `npm ci` 校验并安装该精确版本。
- [`smoke.js`](../src/test/resources/js-sdk-smoke/smoke.js) 已覆盖认证、CRUD、文件、Batch、Realtime，并包含 `pb.filter()` 空字符串和占位符字面量用例；升级依赖后可以直接验证 JS SDK `0.28.1` 的单次占位符替换修复。

---

## 4. 目标设计

### 4.1 非阻塞活动日志与关闭前排空

已由 `LocalPocketBase` 持有内部 `ActivityLogDispatcher`，统一包装现有 `StorageEngine.recordActivityLog()`：

1. HTTP 请求只提交不可变日志事件，不等待数据库/文件写入；队列必须有界，禁止用无限队列把锁争用转化为 OOM。
2. 使用单消费者保持日志接收顺序，并复用现有 `LogPersistenceSanitizer`、日志设置判断及 relational/JSONL 持久化逻辑，不复制两套契约。
3. 队列满时不得阻塞或改变 API 响应；记录无敏感数据的丢弃计数/限频告警，并由测试验证背压策略。
4. `close()` 首先停止接收新 HTTP 请求，等待已经进入的 handler 提交日志，再调用派发器 `closeAndFlush(timeout)`，最后关闭 Cron 与数据库。
5. 排空必须有明确超时；超时后报告丢弃数量并继续释放资源，不能无限等待。日志 I/O 失败仍不能改变已完成的 API 响应。
6. 不移植 Go 的 `context.Value(logger.BlockKey)`。Java 当前只有请求活动日志消费者，显式的 `submit()`、`flush()`、`closeAndFlush()` 比弱类型 context key 更清晰。

为避免异步化造成日志查询测试偶发失败，测试应通过可等待的 flush/fence 或最终一致性轮询取证，不使用固定长时间 `sleep`。对外 `/api/logs` 响应结构保持不变。

### 4.2 统一 Bootstrap 资源清理语义

在不增加虚假重启 API 的前提下，已补齐官方 `ClearBootstrap` 的核心语义：

1. 为 `LocalPocketBase` 增加原子生命周期状态，使重复/并发 `close()` 只执行一次核心清理，后续调用为 no-op。
2. 在 `LocalPocketBase.start()` 和 `RelationalStorageEngine.open()` 使用显式资源栈或 `try/finally`；任一步骤失败都按创建顺序的逆序释放已完成资源，并把清理异常作为 suppressed exception 保留。
3. 为 `RealtimeHub` 增加服务器级 `close()`，主动关闭全部 SSE output 并唤醒 heartbeat latch，避免主要依赖 HTTP executor 的 5 秒超时中断。
4. 固化关闭顺序：停止 HTTP 接流量 → 关闭 SSE → drain HTTP executor → flush 活动日志 → drain Cron → 关闭数据库/文件存储。
5. 核心资源由 `LocalPocketBase` 明确持有并按固定顺序清理；本轮不公开清理注册表或 `OnBootstrapClear` Hook。将来只有在同时存在可扩展 `OnBootstrap` 和真正的 re-bootstrap 模型时，才设计成对外扩展 API。

### 4.3 JS SDK 与 UI 映射

1. 已将 JS SDK smoke 的 `pocketbase` 版本固定为 `0.28.1`（package 与 lock 同步），避免 `^` 在不同机器解析到不同版本；运行时由 `npm ci` 校验 fixture。
2. 保留并强化 `pb.filter()` 用例：重复占位符、空字符串、值本身包含 `{:name}`、引号/反斜线；这些是 JS SDK `0.28.1` 的实际变更面。
3. React Admin UI 不新增 `pocketbase` 依赖，也不复制官方 `ui/dist`。
4. 发布阶段将本项目版本更新为 `v0.4.4`；`App.tsx` 版本文案变化后，必须运行 `npm --prefix ui run build` 并提交 `src/main/resources/pocketbase-admin/` 的真实生成结果。

---

## 5. 开发任务分解与跟踪

### 5.1 任务总表

| ID | 任务 | 优先级 | 状态 | 依赖 | 估算 |
|---|---|---|---|---|---:|
| **PB404-BASE** | 冻结官方差异、Java 映射和非目标 | P0 | **已完成** | 无 | 0.5 人日 |
| **PB404-LOG** | 实现有界非阻塞活动日志派发与关闭排空 | P0 | **已完成** | PB404-BASE | 1.0–1.5 人日 |
| **PB404-CLEAR** | 启动失败回滚、SSE 主动清理与幂等关闭 | P0 | **已完成** | PB404-BASE、PB404-LOG | 1.0–1.5 人日 |
| **PB404-SDK** | 官方 JS SDK smoke 固定升级到 `0.28.1` | P1 | **已完成** | PB404-BASE | 0.5 人日 |
| **PB404-REGRESSION** | 日志锁争用、排空、生命周期和多存储回归 | P0 | **已完成** | PB404-LOG、PB404-CLEAR、PB404-SDK | 1.0–1.5 人日 |
| **PB404-CI** | 将 v0.40.4 专项门禁接入 CI，补齐 JSONL 变更路径验证 | P1 | **已完成（本地配置）** | PB404-REGRESSION | 0.5–1.0 人日 |
| **PB404-RELEASE** | 版本 `v0.4.4`、UI 产物、README/RPD 状态及发布收口 | P1 | **已完成（本地收口）** | PB404-CI | 0.5 人日 |

预计总量：**5–7 人日**。`PB404-SDK` 可与 `PB404-LOG` 并行；生命周期实现依赖日志 drain 接口，最终发布必须串行等待全部门禁。

### 5.2 PB404-BASE：官方差异冻结

**状态：已完成。**

- 已核验两个 tag SHA、发布日期、4 个提交和 22 个文件。
- 已确认 REST/API 契约无变化。
- 已区分“相同缺陷”“Java 等价风险”“无需移植项”。
- 已记录 `OnBootstrapClear()` 与 changelog 旧名称的差异。

**完成记录（2026-09-13）：**

- Commit: `未提交（本轮未执行提交）`
- Tests: `上游 tag/compare 证据核验` — 4 个提交、22 个文件及发布说明已记录。
- CI: `不适用（分析任务）`
- Deviation: `无。`

### 5.3 PB404-LOG：日志隔离与排空

**涉及位置：**

- `src/main/java/io/github/jackbaozz/pocketbase/server/LocalPocketBase.java`
- `src/main/java/io/github/jackbaozz/pocketbase/server/internal/HttpApi.java`
- 已新增 `src/main/java/io/github/jackbaozz/pocketbase/server/internal/ActivityLogDispatcher.java`
- `src/main/java/io/github/jackbaozz/pocketbase/server/internal/repository/LogRepository.java`
- `src/main/java/io/github/jackbaozz/pocketbase/server/internal/JsonFileStore.java`

**验收标准：**

- 持有 SQLite 写锁/执行 schema DDL 时，另一个请求完成后不会因日志 INSERT 阻塞 HTTP worker。
- 解除锁后，已接收日志最终持久化；关闭前队列中的日志被排空。
- 队列饱和、存储异常和关闭竞态都不改变业务 API 状态码/响应体。
- 日志脱敏、`maxDays`、`minLevel`、`maxDataSize`、`logIP`、`logAuthId` 行为保持不变。

**完成记录（2026-09-13）：**

- Commit: `未提交（本轮未执行提交）`
- Tests: `mvn -Dstorage=json -Dtest=ActivityLogDispatcherTest,LocalPocketBaseServerTest,RealtimeSmokeTest,JsSdkSmokeTest test` — 94/94 通过；`ActivityLogDispatcherTest` 4/4 通过。
- CI: `尚未执行（本轮未推送）`
- Deviation: `无；Java 侧采用有界单消费者队列，不移植 Go context BlockKey。`

### 5.4 PB404-CLEAR：生命周期清理

**涉及位置：**

- `src/main/java/io/github/jackbaozz/pocketbase/server/LocalPocketBase.java`
- `src/main/java/io/github/jackbaozz/pocketbase/server/internal/RelationalStorageEngine.java`
- `src/main/java/io/github/jackbaozz/pocketbase/server/internal/JsonFileStore.java`
- `src/main/java/io/github/jackbaozz/pocketbase/server/internal/RealtimeHub.java`
- `src/main/java/io/github/jackbaozz/pocketbase/server/internal/AsyncJobRunner.java`

**验收标准：**

- 连续或并发调用 `close()` 不抛异常、不重复执行清理回调。
- 端口占用、迁移失败、HTTP server 创建/启动失败时，已创建 executor、Cron runner、SSE、Hikari pool 和文件句柄全部释放。
- 活跃 SSE 连接在关闭时被主动唤醒，服务在规定超时内退出。
- 清理失败不会跳过后续资源；主异常保留，其他清理异常以 suppressed 形式可诊断。

**完成记录（2026-09-13）：**

- Commit: `未提交（本轮未执行提交）`
- Tests: `LifecycleCompatibilityTest` 2/2、`RealtimeSmokeTest` 2/2 通过；`mvn clean package -Dtest='!AdminUiPlaywrightTest'` 全量 302 个测试通过、0 失败、1 个 Dart SDK 测试因本机无 Dart 跳过。
- CI: `尚未执行（本轮未推送）`
- Deviation: `无；Java 侧使用 AutoCloseable.close() 与幂等生命周期，不新增虚假的 ClearBootstrap/OnBootstrapClear 公共 API。`

### 5.5 PB404-SDK：JS SDK 0.28.1

**涉及位置：**

- `src/test/resources/js-sdk-smoke/package.json`
- `src/test/resources/js-sdk-smoke/package-lock.json`
- `src/test/resources/js-sdk-smoke/smoke.js`
- `src/test/java/io/github/jackbaozz/pocketbase/server/JsSdkSmokeTest.java`

**验收标准：**

- `npm ci` 安装出的 `pocketbase` 精确版本为 `0.28.1`。
- Auth、CRUD、Batch、文件、Realtime 原 smoke 全部通过。
- `pb.filter()` 对重复占位符、空值、占位符字面量及转义字符生成正确过滤表达式，并能被 Java 服务执行。

**完成记录（2026-09-13）：**

- Commit: `未提交（本轮未执行提交）`
- Tests: `JsSdkSmokeTest` — 官方 JS SDK `0.28.1` smoke 通过，认证、CRUD、Batch、文件、Realtime、重复占位符和引号/反斜线转义均通过。
- CI: `尚未执行（本轮未推送）`
- Deviation: `无；React Admin UI 不引入 pocketbase npm 依赖。`

### 5.6 PB404-REGRESSION / CI：专项矩阵

已新增或扩展：

- `ActivityLogDispatcherTest`：已覆盖队列顺序、饱和、flush、持久化失败和关闭。
- `LifecycleCompatibilityTest`：已覆盖重复关闭、重启和端口占用后的存储释放。
- `LocalPocketBaseServerTest`：现有 84 个服务器回归覆盖日志设置、请求日志、JSONL/SQLite 路径和 REST 行为。
- `RealtimeSmokeTest`：已覆盖活跃 SSE 下关闭服务。
- `JsSdkSmokeTest`：已升级并验证官方 JS SDK `0.28.1`。
- `PocketBaseServerProcessTest`、`RouteConformanceTest`：本轮未新增专门类，分别由现有进程/路由回归与完整 Maven 套件覆盖；后续可按 CI 诊断需要拆分。

CI 中当前 JVM matrix 已覆盖 SQLite、JSONL、MySQL、PostgreSQL；本轮补充了 JSONL lane，避免只在关系型实现上通过后误报完成。

**完成记录（2026-09-13）：**

- Commit: `未提交（本轮未执行提交）`
- Tests: SQLite 与 JSONL 专项回归均通过；本地完整 Maven 302/302 通过。MySQL/PostgreSQL 的远程 CI lane 尚未因未推送而执行。
- CI: `尚未执行（本轮未推送）`
- Deviation: `本地未启动外部数据库容器；CI workflow 已加入 JSONL matrix，外部数据库由后续 CI 运行验证。`

### 5.7 PB404-RELEASE：版本与文档收口

前述任务完成后已执行本地发布收口：

1. `pom.xml` 的 `revision` 更新为 `0.4.4`。
2. `ui/package.json`、`ui/package-lock.json`、`ui/src/App.tsx` 更新为 `v0.4.4`。
3. `PocketBaseClient` User-Agent、`README.md`、`README_zh.md` 的 artifact/版本示例更新为 `0.4.4`。
4. 重新构建 Admin UI 到 `src/main/resources/pocketbase-admin/`。
5. 本 RPD 已改为“已完成”，逐项记录测试、未提交状态和 CI 尚未执行；`docs/README.md` 的完成基线已更新为 PocketBase `v0.40.4`。

**完成记录（2026-09-13）：**

- Commit: `未提交（本轮未执行提交）`
- Tests: `npm --prefix ui test` — 8 个文件、51/51 通过；`npm --prefix ui run build` — 产物成功写入 `src/main/resources/pocketbase-admin/`；`mvn -Dstorage=sqlite -Dtest=AdminUiPlaywrightTest test` — 7/7 通过；`sh/build-native.sh` — GraalVM Native Image BUILD SUCCESS。
- CI: `尚未执行（本轮未推送）`
- Deviation: `Spotless 仍被 7 个未参与本轮的历史文件阻断，详见第 6.3 节；未为此扩大格式化改动。`

---

## 6. 验收门禁

### 6.1 功能矩阵

| 场景 | 预期结果 | 主要证据 |
|---|---|---|
| 启动迁移产生异常 | 启动有限时间内失败，Hikari/文件/线程资源全部释放 | 启动失败注入测试、线程/端口/文件锁断言 |
| 并发 DDL 与请求日志 | 业务请求线程不等待日志数据库锁；无永久死锁 | latch 驱动的确定性 SQLite/MySQL/PostgreSQL 测试 |
| JSONL 慢写与并发请求 | 日志文件 I/O 不阻塞请求 worker；最终日志合法且不丢已接受项 | JSONL dispatcher 与重启读取测试 |
| 有待写日志时关闭 | 停止新提交，已接受项在超时内排空后再关闭存储 | dispatcher fence、重启后日志数量 |
| 重复/并发关闭 | 核心清理只执行一次，所有调用安全返回 | 并发 close 单元测试 |
| 活跃 SSE 时关闭 | 客户端连接主动结束，HTTP executor 不等待完整 heartbeat 周期 | `RealtimeSmokeTest` |
| JS SDK `0.28.1` | 完整 smoke 与 `pb.filter()` 边界用例通过 | `JsSdkSmokeTest`、lock 版本断言 |
| REST 契约 | 路由、方法、鉴权和响应结构与 v0.40.3 基线一致 | `RouteConformanceTest`、现有 SDK smoke |

### 6.2 建议执行命令

```sh
# Java 专项：SQLite 与 JSONL
mvn -Dstorage=sqlite -Dtest=ActivityLogDispatcherTest,LogApiAndSettingsTest,LocalPocketBaseServerTest,RealtimeSmokeTest,PocketBaseServerProcessTest,JsSdkSmokeTest,RouteConformanceTest test
mvn -Dstorage=json -Dtest=ActivityLogDispatcherTest,LogApiAndSettingsTest,LocalPocketBaseServerTest,RealtimeSmokeTest,JsSdkSmokeTest,RouteConformanceTest test

# 外部关系数据库（由 Testcontainers 提供）
mvn -Pexternal-db-drivers -Dstorage=mysql -Dtest=LogApiAndSettingsTest,LocalPocketBaseServerTest,JsSdkSmokeTest test
mvn -Pexternal-db-drivers -Dstorage=postgresql -Dtest=LogApiAndSettingsTest,LocalPocketBaseServerTest,JsSdkSmokeTest test

# Admin UI 源码与嵌入产物
npm --prefix ui test
npm --prefix ui run build
git diff --exit-code -- src/main/resources/pocketbase-admin

# 全量 JVM、格式与 Native Image
mvn clean package -Dtest='!AdminUiPlaywrightTest'
mvn spotless:check
sh/build-native.sh
git diff --check
```

> `git diff --exit-code -- src/main/resources/pocketbase-admin` 应在已提交或已暂存预期生成产物的基线下执行；其目的不是禁止版本更新，而是确认重新构建后没有遗漏的脏生成差异。

### 6.3 本轮实际门禁结果

| 门禁 | 结果 | 说明 |
|---|---|---|
| SQLite/默认存储回归 | 通过 | `mvn clean package -Dtest='!AdminUiPlaywrightTest'`：302 个测试通过，0 失败，1 个 Dart 测试因本机无 Dart 跳过 |
| JSONL 专项回归 | 通过 | `mvn -Dstorage=json -Dtest=LocalPocketBaseServerTest,ActivityLogDispatcherTest,LifecycleCompatibilityTest,RealtimeSmokeTest,JsSdkSmokeTest test`：94 个测试通过 |
| JS SDK | 通过 | fixture 精确为 `pocketbase@0.28.1`，smoke 通过 |
| Admin UI | 通过 | `npm --prefix ui test`：8 个文件、51/51；`npm --prefix ui run build`：嵌入资源成功生成 |
| Admin UI 浏览器验收 | 通过 | `mvn -Dstorage=sqlite -Dtest=AdminUiPlaywrightTest test`：7/7 |
| Native Image | 通过 | `sh/build-native.sh`：GraalVM Native Image BUILD SUCCESS，生成 `target/pocketbase-java` |
| 变更空白检查 | 通过 | `git diff --check` |
| Spotless | 基线阻断 | 7 个既有文件仍有格式差异：`AuthCollectionConfigMerge.java`、`JsonResponseSanitizer.java`、`LogPersistenceSanitizer.java`、`repository/LogRepository.java`、`PocketBaseServerProcessTest.java`、`HttpFileSupportTest.java`、`SqliteDefensiveModeTest.java`；本轮未扩大范围修复 |
| MySQL/PostgreSQL | 待 CI | 本机未启动 Docker/Testcontainers；CI workflow 已保留并扩展矩阵，需推送后验证 |

---

## 7. 风险、边界与回滚

1. **异步日志的可见性变化**：日志由立即可见变为最终一致。测试和 UI 刷新不得依赖同一毫秒内出现，但关闭前必须排空。若该行为不可接受，应保留同步模式开关，而不是退回无界线程。
2. **队列背压与数据丢失**：有界队列满时需要明确计数和告警。不能阻塞业务请求，也不能记录包含 token/header 的原始事件。
3. **关闭顺序竞态**：若先关数据库再 drain 日志会稳定丢日志；若先等日志但 HTTP 仍可提交会永远无法清空。顺序必须由 latch/fence 测试固定。
4. **启动失败资源泄漏**：构造器抛出前对象可能尚未赋给局部变量；资源栈必须在资源创建点立即注册，不能只在最外层对返回对象调用 `close()`。
5. **不要伪造 Go API 对齐**：本项目没有 re-bootstrap 和 Hook 生态，新增 `ClearBootstrap()`/`OnBootstrapClear()` 同名公共 API 会制造错误承诺。本轮以 `AutoCloseable` 语义对齐。
6. **不要复制官方 UI dist**：官方 asset hash 来自 Svelte 工程；本项目只提交 React `ui` 自己构建出的嵌入资源。
7. **不要捆绑无关依赖升级**：`golang.org/x/*` 与 Maven 依赖不存在一一映射。Java 依赖升级需独立的漏洞或兼容证据。

回滚时，日志派发器、生命周期清理和 SDK fixture 应保持可独立提交；若异步日志出现兼容问题，可单独回滚 dispatcher，同时保留启动失败清理、幂等关闭与 SDK `0.28.1` 验证。

---

## 8. 完成条件与进度更新规则

- [x] 官方 `v0.40.3 → v0.40.4` tag、提交、文件与发布说明完成核验。
- [x] Java/React/SDK 现状映射、适用项和不适用项已记录。
- [x] 非阻塞日志、背压和关闭排空实现完成。
- [x] 启动失败回滚、SSE 清理和幂等关闭实现完成。
- [x] JS SDK smoke 固定升级到 `0.28.1` 并通过。
- [x] SQLite、JSONL 专项矩阵通过；MySQL/PostgreSQL 已接入 CI matrix，待推送后运行。
- [x] UI test/build、全量 Maven 和 Native Image 门禁通过；Spotless 仅受 7 个既有文件格式差异阻断。
- [x] 项目版本升级到 `v0.4.4`，README、内嵌 UI 和文档基线同步。
- [ ] 提交、推送及 `main` 合并状态按用户后续授权执行并记录。

每完成一个任务，必须同步更新任务表状态，并在对应任务下追加：

```markdown
**完成记录（YYYY-MM-DD）：**

- Commit: `<sha>`
- Tests: `<实际命令>` — `<结果>`
- CI: `<run URL / 尚未执行>`
- Deviation: `<无 / 与原计划的差异及原因>`
```

当前结论：**PB404 开发任务已完成，项目代码与本地验收已对齐 PocketBase v0.40.4 / pocketbase-java v0.4.4；工作区仍未提交，MySQL/PostgreSQL 需在推送后的 CI 中运行，Spotless 仍有 7 个历史格式差异。**
