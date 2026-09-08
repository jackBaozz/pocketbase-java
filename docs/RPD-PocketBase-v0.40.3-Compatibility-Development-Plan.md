# RPD：PocketBase v0.40.3 差异分析与 dev 功能更新计划

> - 文档版本：1.1
> - 核对日期：2026-09-08
> - 目标分支：`dev`
> - 项目基线：`95084aa5d74ca4435a62d98373930a261feda176`，pocketbase-java `v0.4.2`，已对标 PocketBase `v0.40.2`
> - 上游范围：PocketBase `v0.40.2` (`8213ccffb4c80d792181e4a7409dc86e13b83b1c`) → `v0.40.3` (`5684ee24f1e88f71a2ede73d20cf547fbd509e16`)
> - 文档状态：已完成（Completed，尚未提交）
> - 升级目标：完成索引 WHERE 括号非贪婪匹配、geoDistance 浮点截断保护、自引用级联删除列表强制刷新、代理头非断行连字符修复、暗色主题主按钮对比度优化及相关回归验收，产品版本升至 `v0.4.3`。

## 1. 更新结论

PocketBase `v0.40.3` 发布于 2026-09-06，是一个高针对性的补丁与稳健性版本。相较于 `v0.40.2`，官方代码库共包含 **18 个提交、37 个文件**（排除 UI 预编译产物与自动生成的 JSVM 类型定义后，涉及 16 个核心逻辑、测试及前端组件文件）发生净变化。

本次官方变更重点集中在修复边缘场景异常与系统鲁棒性加固，无新增 REST 路由、无新增请求参数、无集合类型或基础字段结构变更。

经过对官方提交记录与本项目 `dev` 分支（当前处于 `v0.4.2`）现状的逐行映射与深入分析，**确认官方本次差异中有多项属于当前系统需要跟进的重要修复与优化**：

1. **集合索引校验器 WHERE 子句括号非贪婪解析（严重缺陷，必修）**：
   - 官方原因：旧版索引正则的列匹配部分使用了贪婪匹配，导致如果 `WHERE` 条件中含有括号（如 `WHERE cast(test1 as int) = 1` 或 `WHERE (status = 'active')`），贪婪捕获会将 WHERE 子句中的右括号误吞入索引列字段，造成列解析损坏并清空 WHERE 条件。同时，官方移除了多行标志 `(?m)`，增加了列解析不一致时的阻断校验。
   - 本系统现状：`CollectionIndexSupport.java` 与前端 `IndexManager.tsx` 均存在完全相同的贪婪匹配 Bug，遇到带括号的复杂 WHERE 表达式会抛出解析校验失败；必须立即对齐为非贪婪匹配，并补充列校验兜底。
2. **geoDistance 距离函数余弦截断保护（精度防崩溃，必修）**：
   - 官方原因：在 Haversine 公式计算中，当计算完全相同或极端近似坐标（如纬度 8° 或 45°）之间的距离时，IEEE 754 浮点数舍入会导致中间余弦值出现 `1.0000000000000002`，在 SQLite 中触发 `acos()` 返回 `NULL` 从而使查询结果异常。官方在 `acos` 内部引入了 `min(1, max(-1, ...))` 截断。
   - 本系统现状：`FilterFunctionSupport.java` 的内存 Haversine 计算中未对中间变量进行 `[0.0, 1.0]` 严格夹取；需对齐浮点截断保护，并预先为未来的 SQL 编译器对齐此规则。
3. **自引用级联删除列表刷新与级联机制对齐（高优先级 UI 修复，规划后端架构）**：
   - 官方原因：如果集合包含自引用且开启 `cascadeDelete` 的 relation 字段，服务端删除父记录会级联删除关联的子记录；官方 Admin UI 在删除记录后若检测到该集合包含此类字段，会强制触发 `loadRecords(true)` 全量刷新，避免界面残留已在服务端被级联删除的子记录（幽灵记录）。服务端方面，官方改为仅分批抓取 `id`，在循环删除时重新查询记录，以妥善应对深层级联中记录已被前序步骤删除的场景。
   - 本系统现状：前端 `ui/src/App.tsx` 在删除单条或多条记录时，仅从客户端缓存和列表中过滤被选中的 ID，未检查自引用级联字段，存在数据不一致问题；前端需立即对齐强制刷新逻辑。后端目前尚未实现关系字段的通用级联删除（仅有超管和 Auth 附属表清理），需作为后续数据完整性架构路线进行设计与落地。
4. **健康检查代理头非断行连字符 Bug 修复（直接代码缺陷，必修）**：
   - 官方原因：官方在 `health.go` 中不慎将 `"X-Forwarded-For"` 写成了带有 Unicode 非断行连字符的 `"X‑Forwarded-For"`（U+2011），导致标准代理透传头无法命中。官方在此版本修正为 ASCII 减号（U+002D）。
   - 本系统现状：本系统在历史对标时不慎同步引入了该符号（位于 `HttpApi.java:1164`：`headers.add("X\u2011Forwarded-For");`），导致 `possibleProxyHeader` 在接收标准 `X-Forwarded-For` 时无法正确识别；属于低风险、高收益的直接修复项。
5. **暗色模式主按钮对比度优化（UI 体验对齐，必修）**：
   - 官方原因：在暗色模式下，官方原 `--primaryColor: #121212` 与深色背景近乎融为一体，可读性较差，故调整为 `var(--surfaceAlt4Color)`。
   - 本系统现状：`ui/src/styles.css` 中的暗色模式 `--primaryColor` 为 `#171a20`，与页面背景色 `#15181d` 对比度严重不足；需同步升级以提升暗色主题下主操作按钮的可识别度。
6. **JSON 字段校验语义与重复键兼容处理（设计对齐）**：
   - 官方原因：上游切至 `encoding/json/v2`，对新提交数据严格校验不允许重复键，同时在旧记录导出序列化时通过 `AllowDuplicateNames(true)` 保持对历史旧数据的容错。
   - 本系统现状：Java 采用 Jackson 处理，默认对重复键具有宽容覆盖语义，不存在因反序列化严格性导致的旧数据不可读问题。在数据写入时可根据需要补充严格重复键校验。
7. **架构已有或无需移植项（安全与技术差异）**：
   - JSON 响应状态码写入时机：官方修复了在字段挑选（picker）出错前先发送了 200 头的时序 Bug。Java 端 `HttpApi` 采用先将响应对象序列化为 byte 数组再调用 `exchange.sendResponseHeaders` 的机制，若处理异常则在写头前抛出并进入错误处理器，天然不受此时序问题影响。
   - 请求体限流中间件（`maxBytesReader`）：Java 端在流读取中发现超出限制立即抛出 413，行为符合预期。
   - OIDC id_token 类型断言防 Panic：Java 端使用安全类型转换工具，天然免疫 ClassCastException。
   - JSVM TypeScript 定义由 type 改为 interface、Go 依赖安全扫描版本升级：仅适用于 Go/JSVM 生态，不影响 Java 运行时。

---

## 2. 基线与官方证据

| 对象 | 版本 / Commit SHA | 说明 |
|---|---|---|
| 上游对比起始点 | `v0.40.2` / `8213ccffb4c80d792181e4a7409dc86e13b83b1c` | 官方发布时间：2026-09-02 13:18:16 UTC |
| 上游对比目标点 | `v0.40.3` / `5684ee24f1e88f71a2ede73d20cf547fbd509e16` | 官方发布时间：2026-09-06 17:33:10 UTC |
| Java 本地 dev 基线 | `95084aa5d74ca4435a62d98373930a261feda176` | 当前 `dev` 分支最新提交，产品版本 `v0.4.2` |
| 当前已达标基线 | PocketBase `v0.40.3` | 本 RPD 的实现、双存储引擎回归、Admin UI E2E 与打包验收均已完成；项目版本已升至 `v0.4.3`。 |

官方依据链接：
- [PocketBase 官网](https://pocketbase.io/)
- [v0.40.3 Release Notes](https://github.com/pocketbase/pocketbase/releases/tag/v0.40.3)
- [v0.40.2...v0.40.3 Compare 视图](https://github.com/pocketbase/pocketbase/compare/v0.40.2...v0.40.3)
- [v0.40.3 CHANGELOG.md](https://github.com/pocketbase/pocketbase/blob/v0.40.3/CHANGELOG.md)

### 2.1 37 个变更文件的详细分类

| 模块类别 | 涉及文件 | 文件数 | 修改性质与上游意图 |
|---|---|---:|---|
| 索引解析与校验 | `tools/dbutils/index.go`、`index_test.go`、`ui/src/utils.js` | 3 | WHERE 子句支持括号表达式、非贪婪匹配、多行解析及列解析失败清空兜底 |
| 过滤器与地理计算 | `tools/search/token_functions.go`、`token_functions_test.go`、`filter_test.go` | 3 | `geoDistance` 函数内 arccosine 边界截断至 `[-1, 1]`，防止相同坐标计算得 `NULL` |
| 记录与自引用级联删除 | `core/record_model.go`、`record_model_test.go`、`ui/src/records/recordsList.js` | 3 | 级联删除优化为抓取 ID 动态重查，防重复删除异常；UI 针对自引用级联字段在删除后强制重载 |
| JSON 与字段处理 | `core/field_json.go`、`field_json_test.go`、`tools/types/json_raw.go`、`plugins/migratecmd/templates.go` | 4 | 校验阶段拦截重复键（jsonv2 语义），序列化阶段放宽允许重复键保持向后兼容 |
| HTTP 路由与中间件 | `apis/health.go`、`apis/middlewares_body_limit.go`、`middlewares_body_limit_test.go`、`tools/router/event.go`、`event_test.go`、`tools/picker/pick.go`、`pick_test.go` | 7 | 修正代理头连字符、体限制提前截断、JSON 响应状态码写头时机调整、字段挑选器错误分级 |
| 认证与 OAuth2 | `tools/auth/oidc.go` | 1 | OIDC 解析 `id_token` 安全类型断言，防止非 string 类型触发 Panic |
| JSVM 扩展与类型 | `plugins/jsvm/internal/types/types.go`、`generated/types.d.ts` | 2 | `$app` 实例由 type 更改为 interface 声明（便于外部合并扩展 #7834） |
| UI 主题与配置 | `ui/src/css/vars.css`、`ui/.env` | 2 | 暗色模式主按钮使用更高对比度的变量色，UI 版本号升级至 `v0.40.3` |
| 发行与变更日志 | `CHANGELOG.md`、`CHANGELOG_16_22.md`、`go.mod`、`go.sum` | 4 | 版本发布日志说明，更新 `golang.org/x/*` 依赖以消除安全扫描告警 |
| UI 预编译资源 | `ui/dist/index.html` 及各 CSS/JS chunk | 8 | 官方内嵌 Svelte Admin UI 打包产物更新 |

---

## 3. 官方行为差异与在本系统的适用性分析

### 3.1 [PB403-INDEX] 集合索引校验器 WHERE 子句括号与非贪婪匹配

- **上游提交**：`dbea21f1`、`1984952e`
- **上游代码变更**：
  - 正则表达式优化：
    ```go
    // 旧版（贪婪匹配，且带多行模式 m）
    indexRegex = regexp.MustCompile(`(?im)create\s+(unique\s+)?\s*index\s*(if\s+not\s+exists\s+)?(\S*)\s+on\s+(\S*)\s*\(([\s\S]*)\)(?:\s*where\s+([\s\S]*))?`)

    // 新版（非贪婪匹配，首尾锚定，要求 where 前至少一个空格，移除 m）
    indexRegex = regexp.MustCompile(`(?i)\s*create\s+(unique\s+)?\s*index\s*(if\s+not\s+exists\s+)?(\S*)\s+on\s+(\S*)\s*\(([\s\S]*?)\)(?:\s+where\s+([\s\S]*?))?\s*$`)
    ```
  - 列解析异常兜底：
    ```go
    if len(rawColumns) != len(result.Columns) {
        // unset to trigger validation error
        result.Columns = []IndexColumn{}
    }
    ```
- **根因分析**：
  旧版正则中括号捕获为 `\(([\s\S]*)\)`。如果用户创建部分索引：
  `CREATE INDEX idx ON tablename (col1) WHERE cast(test1 as int) = 1` 或 `WHERE (status = active)`
  因为贪婪匹配的存在，`\(([\s\S]*)\)` 会一直向后匹配到 WHERE 子句最末尾的 `)`，将整个 `col1) WHERE cast(test1 as int` 误当成索引列，导致列解析失败，且 WHERE 子句被置空！
- **Java / React 现状**：
  - 后端：`CollectionIndexSupport.java` 中 `INDEX_PATTERN` 当前定义为：
    ```java
    Pattern.compile("(?is)^\s*create\s+(unique\s+)?index\s+(if\s+not\s+exists\s+)?(\S*)\s+on\s+(\S*)\s*\((.*)\)\s*(?:where\s+(.+))?\s*$");
    ```
    在带 `DOTALL` 标志下使用贪婪 `.*`，**完全存在上述被 WHERE 内部括号截断破坏的致命缺陷**！
  - 前端：`ui/src/components/IndexManager.tsx` 中 `INDEX_REGEX` 当前为：
    ```ts
    /create\s+(unique\s+)?\s*index\s*(if\s+not\s+exists\s+)?(\S*)\s+on\s+(\S*)\s*\(([\s\S]*)\)(?:\s*where\s+([\s\S]*))?/i;
    ```
    同样是贪婪匹配，会导致前端索引管理器解析带括号 WHERE 的索引时彻底混乱。
- **升级决策**：**必须升级（最高优先级）**。后端改为非贪婪匹配并补全列校验失败兜底；前端同步更新非贪婪正则和双向解析逻辑。

---

### 3.2 [PB403-GEO] geoDistance 函数 Arccosine 浮点截断保护

- **上游提交**：`df4e6eeb`
- **上游代码变更**：
  ```go
  // tools/search/token_functions.go
  Identifier: `(6371 * acos(min(1, max(-1, ` +
      `cos(radians(` + latA + `)) * cos(radians(` + latB + `)) * ` +
      `cos(radians(` + lonB + `)) - radians(` + lonA + `)) + ` +
      `sin(radians(` + latA + `)) * sin(radians(` + latB + `))` +
      `))))`,
  ```
- **根因分析**：
  在计算地球球面大圆距离时，当比对点相同时（例如两点坐标均为 `lon=0, lat=8` 或 `lon=0, lat=45`），理论上应满足三角恒等式为 1.0。但在 IEEE 754 双精度浮点运算下，可能计算出 `1.0000000000000002`。
  由于反余弦函数 `acos(x)` 仅在定义域 `[-1, 1]` 内有效，超出该范围会导致 SQLite 的 `acos` 函数直接返回 `NULL`，使过滤表达式计算结果变成非预期值甚至报错。
- **Java 现状**：
  当前 `pocketbase-java` 在 `FilterFunctionSupport.java` 中采用内存 Java 方法计算 `haversine`：
  ```java
  double a = sinLat * sinLat + Math.cos(latARadians) * Math.cos(latBRadians) * sinLon * sinLon;
  return EARTH_RADIUS_KM * 2D * Math.atan2(Math.sqrt(a), Math.sqrt(Math.max(0D, 1D - a)));
  ```
  虽然使用了 `Math.max(0D, 1D - a)`，但在极少数极端坐标下，浮点舍入可能导致 `a > 1.0`，造成 `1D - a` 发生溢出偏差。
- **升级决策**：**必须升级**。在 `FilterFunctionSupport.java` 中对中间变量进行 `Math.min(1.0, Math.max(0.0, a))` 钳位保护；同时在未来若实现 SQL 端 `geoDistance` 翻译时，生成带 `min(1, max(-1, ...))` 的 SQL。

---

### 3.3 [PB403-CASCADE] 自引用级联删除安全保护与 UI 强制刷新

- **上游提交**：`44f817e7`、`10f4f283`、UI 变更
- **上游变更**：
  1. 服务端：处理级联删除时，不预加载全部关联记录，而是仅分页查询其 `id`（`Select(refTableName + ".id")`，批大小 8000）。在遍历删除每一项时，重新调用 `FindRecordById`。如果记录已被同一次事务中的嵌套自引用级联删除了（返回 `sql.ErrNoRows`），则平滑跳过（`continue`），防止报记录不存在错误。
  2. 管理后台 UI：在 `recordsList.js` 中，删除记录后防抖检测：若该集合中存在指向当前集合且配置了 `cascadeDelete: true` 的 relation 字段，则不单单从本地数组剔除被删记录，而是调用 `loadRecords(true)` 强制向后端拉取最新列表。
- **Java / React 现状**：
  - 前端 UI（`ui/src/App.tsx`）：当前 `deleteRecord` 与 `deleteSelectedRecords` 仅调用 `removeRecordsFromLoadedState([record.id])` 从本地页面缓存中扣除记录。若集合存在树形自引用级联删除，父节点被删除后，子记录已被服务端清理，但前端界面依然保留子记录视图，导致用户看到“幽灵记录”，点击编辑时报 404。
  - 后端存储：`RecordRepository.java` 目前主要实现了 Auth 附属表（token/otp/externalAuth）的级联清理，尚未建立针对关系集合通用的关系图级联分析与删除体系。
- **升级决策**：
  - 前端：**立即对齐**。在 `App.tsx` 的记录删除处理中加入集合字段探测，凡命中本集合自引用级联删除字段的，自动触发当前页全量刷新与分页重算。
  - 后端：在 Phase 4/5 架构演进中正式实现 Relation 级联删除机制时，严格遵循本次官方提供的“基于 ID 分批 + 单条实时校验 + 容忍已删除”的安全删除模式。

---

### 3.4 [PB403-HEADER] 健康检查代理透传头 ASCII 规范化修复

- **上游提交**：`57c0b034`
- **上游变更**：
  `apis/health.go` 中将 `"X‑Forwarded-For"` 改为标准 ASCII 连字符 `"X-Forwarded-For"`。
- **Java 现状**：
  `src/main/java/io/github/jackbaozz/pocketbase/server/internal/HttpApi.java:1164`：
  ```java
  headers.add("CF-Connecting-IP");
  headers.add("Fly-Client-IP");
  headers.add("X‑Forwarded-For"); // 注意：此处是 ‑ 非断行连字符！
  ```
  由于此前对照上游 Go 代码编写，不慎原样保留了该 Unicode 字符，导致客户端发来的标准 `X-Forwarded-For` 头部无法被可能代理头检测器命中。
- **升级决策**：**必须修复（零风险、高收益）**。直接替换为 `headers.add("X-Forwarded-For");`。

---

### 3.5 [PB403-UI-THEME] 暗色主题主操作按钮对比度加固

- **上游提交**：`7ac159b6`、`bf12ae02`
- **上游变更**：
  `ui/src/css/vars.css` 中，暗色模式下的 `--primaryColor` 由偏黑的暗灰值 `#121212` 改为 `var(--surfaceAlt4Color)`。
- **Java 现状**：
  `ui/src/styles.css` 中，`:root[data-theme="dark"]` 定义为：
  ```css
  :root[data-theme="dark"] {
    --primaryColor: #171a20;
    ...
    --surfaceColor: #15181d;
    --surfaceAlt1Color: #1b1f25;
    ...
    --surfaceAlt4Color: #3c4652;
  ```
  在暗色主题下，主操作按钮（如“Save changes”、“Create collection”等）背景为 `#171a20`，而面板和卡片背景为 `#15181d` / `#1b1f25`，按钮边框和背景与环境反差微弱，极难聚焦。
- **升级决策**：**必须优化**。将暗色主题下的 `--primaryColor` 调整为 `var(--surfaceAlt4Color)`（或与之搭配的高对比度色），并保持对应文字与悬停态（`--primaryAlt1Color`, `--primaryAlt2Color`）清晰和谐。

---

### 3.6 [PB403-OTHER] 其他评估与无需移植项

1. **响应状态码与 Field Picker 执行时机**（`97f9d63a`）：
   - 上游修复了 Go 运行时在 `picker.Pick` 之前先调用了 `WriteHeader(status)` 导致出错时客户端依然收到 200 的问题。
   - 本项目 `HttpApi.sendJson` 会在写入 HTTP 响应头前完成整个对象树的过滤与序列化（`store.mapper().writeValueAsBytes`），若出错直接抛出 `ApiException`，响应头由最外层捕获器正确写入 400/500。结论：**当前 Java 架构天然免疫该问题，无需改造**。
2. **请求体限流中间件提前截断**（`53a6cd04`、`7ff7e0f0`）：
   - 上游 Go 引入 `maxBytesReader` 避免在超限时仍将整块网络数据读入内存缓冲区。
   - 本项目 `HttpApi.readRequestBytes` 以 8192 字节分段流式读取，一旦累加字节 `total > limit` 立即抛出 413 终止读取并释放流。结论：**行为合理，性能可控，无需重构**。
3. **JSON 重复键校验与模型导出兼容**（`75c6a4fd`）：
   - 上游因升级 Go 1.24 `encoding/json/v2`，其新包默认拒绝 JSON 重复键，官方因此在字段校验增加检查，在记录导出放宽参数。
   - Java 采用成熟的 Jackson 处理。记录导出和读写对重复键天然容错（覆盖后值），不会造成旧数据库因重复键导致服务挂起。结论：**记录行为差异，暂不强制开启 Jackson 的重复键报错特性**。
4. **OIDC Provider `id_token` 安全转换**（`4709f631`）：
   - 上游 Go 处理 OIDC 响应时直接使用了 Go 类型断言 `.(string)` 导致非 string 类型会 Panic。
   - 本项目 `OAuth2Support.java` 使用 `text(token.get("id_token"))` 工具方法，传入 null 或非 String 时会安全转为空串，并抛出带有详细错误信息的 `ApiException(400, ...)`。结论：**Java 实现已完全类型安全**。
5. **JSVM 接口定义与 Go 依赖更新**：
   - 官方 JSVM `$app` 改为 `interface` 及 `golang.org/x/*` 升级，属于 Go 专用运行时范畴，与本系统无关。

---

## 4. 开发任务分解与实施路线

| 任务编号 | 任务类别 | 任务目标与改动文件 | 依赖项 | 优先级 | 执行状态 |
|---|---|---|---|---|---|
| **PB403-TASK-1** | 核心后端 | `CollectionIndexSupport.java` 改为非贪婪匹配，列解析数量不一致时拒绝索引；覆盖 WHERE 括号、嵌套列函数与畸形表达式。 | 无 | P0 | 已完成 |
| **PB403-TASK-2** | 核心前端 | `IndexManager.tsx` 改为非贪婪匹配，新增平衡括号/引号感知的列分割；同步更新 `IndexManager.test.ts`。 | 无 | P0 | 已完成 |
| **PB403-TASK-3** | 核心后端 | `HttpApi.java` 使用标准 ASCII `X-Forwarded-For`；增加健康检查代理头断言。 | 无 | P0 | 已完成 |
| **PB403-TASK-4** | 核心后端 | `FilterFunctionSupport.java` 的 Haversine 中间值钳位至 `[0.0, 1.0]`；覆盖纬度 8°、45° 的相同坐标。 | 无 | P1 | 已完成 |
| **PB403-TASK-5** | 核心前端 | `ui/src/styles.css` 暗色模式主色改为 `var(--surfaceAlt4Color)`。 | 无 | P1 | 已完成 |
| **PB403-TASK-6** | 核心前端 | `App.tsx` 在单条/批量删除后探测自引用 `cascadeDelete` 字段；命中时清空选择与页缓存、回到第一页并强制从服务端刷新。补齐后端 `FieldSchema.cascadeDelete` 的 API/持久化透传。 | 无 | P1 | 已完成 |
| **PB403-TASK-7** | 测试与构建 | 添加索引、geoDistance、代理头、级联字段配置与 UI 刷新测试；重新生成 `src/main/resources/pocketbase-admin/`。 | 1~6 | P0 | 已完成 |
| **PB403-TASK-8** | 基线与发布 | SQLite/JSONL、JS SDK smoke、Playwright 和 Maven package 验收；版本元数据、文档索引更新为 `v0.4.3`。本项目没有 `ui/.env`，因此仅更新实际存在的版本元数据。 | 7 | P1 | 已完成 |

---

## 5. 验收门禁与测试矩阵

### 5.1 功能验收矩阵

| 验收维度 | 核心用例与测试输入 | 预期表现 | 验证依据与覆盖文件 |
|---|---|---|---|
| **索引 WHERE 括号支持** | `CREATE INDEX idx ON tbl (col1) WHERE (status = active)`<br>`CREATE UNIQUE INDEX idx ON tbl (col1) WHERE cast(col2 as int) > 10` | 正确解析列名与完整 WHERE 子句，不截断、不错位，创建集合成功。 | `CollectionIndexSupportTest.java`、`IndexManager.test.ts` |
| **索引列异常阻断** | 构造无法完全匹配列正则的多列索引字符串 | 解析结果清空列集合，触发 `Invalid CREATE INDEX expression` 400 校验错误。 | `CollectionIndexSupportTest.java` |
| **geoDistance 浮点夹取** | `geoDistance(0, 8, 0, 8)`<br>`geoDistance(0, 45, 0, 45)`<br>经纬度相同坐标比较 | 稳定返回 `0.0`，不产生 NaN 或异常，不因浮点舍入出现精度倒挂。 | `RuleEvaluatorTest.java`、`LocalPocketBaseServerTest.java` |
| **代理头识别** | 请求携带标头 `X-Forwarded-For: 203.0.113.195` 请求 `GET /api/health` | 返回 `possibleProxyHeader: "X-Forwarded-For"`，正确识别标准 ASCII 标头。 | `LocalPocketBaseServerTest.java` |
| **暗色模式主按钮** | 在暗色主题下渲染 Primary Button（如保存设置、新建集合） | 按钮背景呈现 `var(--surfaceAlt4Color)`，与背景 `#15181d` 形成鲜明反差，文字清晰。 | UI 视觉与样式检查 |
| **自引用级联删除刷新** | 对包含自引用级联字段的集合删除父节点记录 | 前端触发 `loadRecords(true)`，子节点记录随同被刷新移除，无幽灵残留。 | `AdminUiPlaywrightTest.java` |

### 5.2 实施验证命令清单

已执行以下全套验证流程；命令及结果以本次工作区实际输出为准：

```sh
# 1. 单元测试回归（SQLite 引擎）
mvn -Dstorage=sqlite -Dtest=CollectionIndexSupportTest,RuleEvaluatorTest,FilterFunctionSupportTest,LocalPocketBaseServerTest,JsSdkSmokeTest test

# 2. 单元测试回归（JSONL 引擎）
mvn -Dstorage=json -Dtest=CollectionIndexSupportTest,RuleEvaluatorTest,FilterFunctionSupportTest,LocalPocketBaseServerTest,JsSdkSmokeTest test

# 3. 前端测试与构建（51 项通过；随后以 v0.4.3 重新构建）
npm --prefix ui test
npm --prefix ui run build

# 4. 基于全新 Admin UI 构建产物的 Playwright E2E 验证
mvn -Dstorage=sqlite -Dtest=AdminUiPlaywrightTest test

# 5. 最终打包、代码格式与 Git 变更审查
mvn clean package
git diff --check
```

### 5.3 执行结果

| 门禁 | 结果 |
|---|---|
| SQLite 定向回归 | `111` 项通过：`LocalPocketBaseServerTest` 84、`RuleEvaluatorTest` 17、`CollectionIndexSupportTest` 9、`JsSdkSmokeTest` 1。 |
| JSONL 定向回归 | `111` 项通过，覆盖与 SQLite 相同的测试集。 |
| 前端单元测试 | `npm --prefix ui test`：8 个文件、51 项通过。 |
| Admin UI 构建 | `npm --prefix ui run build`：通过，产物写入 `src/main/resources/pocketbase-admin/`。 |
| Admin UI E2E | `mvn -Dstorage=sqlite -Dtest=AdminUiPlaywrightTest test`：7 项通过；包含自引用级联配置删除后的服务器列表重载。 |
| 完整打包与静态检查 | `mvn clean package`：302 项通过、0 失败/错误、1 项 Dart smoke 因未配置 Dart SDK 跳过；已生成 `target/pocketbase-java-0.4.3-all.jar`。`git diff --check` 通过。 |

---

## 6. 完成条件与风险控制

- [x] 官方 `v0.40.3` 全部 18 个提交、37 个文件差异与基线比对完成并归档（本文件）。
- [x] 后端 `CollectionIndexSupport.java` 与前端 `IndexManager.tsx` 索引非贪婪解析完成，带括号 WHERE 场景测试通过。
- [x] `FilterFunctionSupport.java` 中 `haversine` 完成浮点截断保护，相同坐标回归通过。
- [x] `HttpApi.java` 代理头标准 ASCII 连字符修复完成，健康检查测试通过。
- [x] 前端暗色模式主按钮对比度提升，样式检查通过。
- [x] 前端记录删除在自引用级联集合下支持全量刷新。
- [x] Admin UI 重新编译，打包文件写入 `src/main/resources/pocketbase-admin/`。
- [x] SQLite / JSONL 双存储模式测试及 Playwright E2E 全部通过。
- [x] `docs/README.md` 与系统版本信息平稳更新。

**范围说明**：本轮已对齐 Admin UI 在自引用 `cascadeDelete` 配置下的强制重载行为，并确保该配置在 Java API 与两种存储模式中可见、可持久化。关系字段的通用后端级联删除仍是独立的后续数据完整性架构任务；本 RPD 不将其误报为已实现。

**关键风险与规避策略**：
1. **非贪婪正则边界风险**：在将 `\((.*)\)` 修改为 `\((.*?)\)` 时，必须确保对于列本身包含括号的表达式（如 `json_extract(data, $.id)`）依然能够正确匹配到最外层闭合括号。需在 `CollectionIndexSupportTest` 中重点覆盖包含表达式索引与包含 WHERE 括号的复合用例。
2. **UI 预编译产物同步风险**：React 源码改动后，若未运行 `npm run build`，服务端内嵌的 Web 资源仍为旧版本。必须严格执行构建命令并以 Playwright E2E 验证真实打包产物。
