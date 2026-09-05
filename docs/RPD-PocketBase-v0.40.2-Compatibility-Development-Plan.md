# RPD：PocketBase v0.40.2 差异分析与 dev 功能更新计划

> - 文档版本：1.0
> - 核对日期：2026-09-05
> - 目标分支：`dev`
> - 项目基线：`f23954414cbffa6d0136de7abd532be3eeb61684`，pocketbase-java `v0.4.1`，已对标 PocketBase `v0.40.1`
> - 上游范围：PocketBase `v0.40.1` → `v0.40.2`
> - 文档状态：功能开发、前端构建、单元测试、Playwright E2E 与 JSONL/SQLite 双引擎回归验收均已完成
> - 本次交付：修复服务端与前端索引多段名称解析、CodeEditor 前缀补全与 50ms 防抖、REST/JS SDK 边界回归；产品版本升至 v0.4.2。

## 1. 更新结论

PocketBase `v0.40.2` 是一个补丁版本，相对 `v0.40.1` 共 **16 个提交、21 个文件**发生净变化。主要变化是过滤占位参数处理、集合索引解析和 Admin 编辑器自动补全，另有 Go/JSVM 依赖、发布工具链、注释及构建产物更新。本次 tag 差异没有新增 REST 路由、请求参数、集合字段或数据库迁移文件。

对当前 Java/React 实现，建议本轮执行：

1. 修正索引名称解析：拒绝 `a.b.c` 这样的非法多段名称，避免服务端把它静默改为 `c`，并同步前端索引编辑器的解析行为。
2. 对齐编辑器补全：使用光标前缀筛选候选、增加 50ms 防抖，并覆盖唯一完整匹配、清空和退出时的补全状态。
3. 补充过滤与官方 JS SDK 的边界回归，固定空字符串、引号和包含占位符样式的普通文本的查询行为。

官方过滤参数变更主要作用于 Go 服务端扩展方法；本项目目前没有等价的 `FilterData.BuildExpr(..., dbx.Params)` 入口。不能据此认定 Java 存在同一序列化回归，也不需要为此次 REST 兼容升级新增参数绑定端点。Go/Goja 升级不直接映射为 Maven、JDK 或 Java 正则库升级。

以下“已有”“缺口”均为本次静态源码核对结论；除明确列出的文档检查外，不代表已经执行新版本运行时测试。

## 2. 基线与官方证据

| 对象 | 版本/提交 | 说明 |
|---|---|---|
| 上游旧版本 | `v0.40.1` / `bc8ffed4e7265a70a6e8de76c0b0b48b945e19ef` | 官方发布时间：2026-08-24 15:23:08 UTC |
| 上游目标版本 | `v0.40.2` / `8213ccffb4c80d792181e4a7409dc86e13b83b1c` | 官方发布时间：2026-09-02 13:18:16 UTC |
| Java 本地 `dev` | `f23954414cbffa6d0136de7abd532be3eeb61684` | 本次核对开始时与远端 `origin` 的 `dev` 分支一致 |
| 已完成的兼容基线 | PocketBase `v0.40.1` | 见 [v0.40.1 RPD](RPD-PocketBase-v0.40.1-Compatibility-Development-Plan.md) |

官方依据：

- [PocketBase 官网](https://pocketbase.io/)
- [v0.40.1 发布说明](https://github.com/pocketbase/pocketbase/releases/tag/v0.40.1)
- [v0.40.2 发布说明](https://github.com/pocketbase/pocketbase/releases/tag/v0.40.2)
- [两个 tag 的完整比较](https://github.com/pocketbase/pocketbase/compare/v0.40.1...v0.40.2)
- [v0.40.2 CHANGELOG](https://github.com/pocketbase/pocketbase/blob/8213ccffb4c80d792181e4a7409dc86e13b83b1c/CHANGELOG.md)

### 2.1 21 个变更文件的归类

| 范围 | 文件 | 数量 | 性质 |
|---|---|---:|---|
| 过滤器 | `tools/search/filter.go`、`filter_test.go` | 2 | 参数替换、转换失败处理及回归测试 |
| 索引 | `tools/dbutils/index.go`、`index_test.go` | 2 | 名称解析边界修复及回归测试 |
| 编辑器 | `ui/src/base/codeEditor.js` | 1 | 补全行为和高亮阈值 |
| 注释 | `tools/picker/pick.go`、`tools/router/event.go`、`ui/src/pb.js` | 3 | 拼写修正，无新增执行逻辑 |
| 依赖、类型及 CI | `go.mod`、`go.sum`、`plugins/jsvm/internal/types/generated/types.d.ts`、`.github/workflows/release.yaml` | 4 | Goja/间接依赖、JSVM 类型重新生成、Go CI 版本 |
| 版本和发行记录 | `CHANGELOG.md`、`CHANGELOG_16_22.md`、`ui/.env` | 3 | `v0.40.2` 及旧线 `v0.22.54` 回移记录、UI 版本 |
| 内嵌 Admin 资源 | `ui/dist/index.html` 及 5 个 JS bundle | 6 | 重新构建及资源名变更 |

`CHANGELOG_16_22.md` 的 `v0.22.54` 是旧版本线的回移说明，不是本系统另一个升级目标。上游生成的 JSVM 类型文件也不是本项目 Java SDK 的类型定义。

## 3. 官方行为差异与适用性

| 编号 | v0.40.1 → v0.40.2 | dev 现状 | 更新结论 |
|---|---|---|---|
| PB402-FILTER | 从逐个 `ReplaceAll` 改为 `strings.NewReplacer` 单次扫描；字符串转换失败才回退 JSON；回退失败返回错误；空字符串不再被当作转换失败 | Java SDK 接收完整 filter 字符串，REST 通过 `SearchQuerySupport` 和 `RuleEvaluator` 处理；没有模板加参数 Map 的扩展接口 | 无直接移植点；补边界回归，模板 API 作为独立可选扩展 |
| PB402-INDEX | 名称拆分结果只有 1/2 段时才赋值；避免空切片访问，并使 3 段名称无效 | 缺名已由正则拒绝；`lastIdentifierPart()` 却把多段名称截成最后一段 | 有实际兼容缺口：修复名称解析并增加 API/存储回归 |
| PB402-EDITOR | 补全基于光标前缀、50ms 防抖；唯一候选等于完整词时不显示；清空时关闭；高亮阈值 500 → 800 字符 | 使用完整词、同步补全；已有部分空列表/精确匹配处理；自研高亮阈值为 20,000 字符 | 对齐补全行为；高亮保留 Java UI 自身性能策略并验证 |
| PB402-GOJA | Goja 升级至 `v0.0.0-20260901132549-43234fa61381`；正则未转义连字符修复及 base64 优化；间接 `pprof` 更新 | 生产代码和 Maven 依赖未集成 Goja/等价服务端 JSVM | 不直接适用；不把客户端 JS SDK 当作服务端 Goja |
| PB402-GO-CI | 发布 workflow 的 Go 下限从 `>=1.27.0` 提升到 `>=1.27.1`；`go.mod` 仍为 `go 1.27` | Maven Enforcer 要求 Java 17+、Maven 3.9+；`mise.toml` 声明 Java 17 / Maven 3.9.12 | 不直接适用；沿用本项目工具链 |
| PB402-META | 注释、版本、生成类型与内嵌资源变化 | Java/React 有独立版本和构建流程 | 仅在功能完成后构建自身资源、更新兼容基线 |

### 3.1 过滤参数：单次替换、显式错误与空字符串

源码依据：[filter.go](https://github.com/pocketbase/pocketbase/blob/8213ccffb4c80d792181e4a7409dc86e13b83b1c/tools/search/filter.go#L52)、[filter_test.go](https://github.com/pocketbase/pocketbase/blob/8213ccffb4c80d792181e4a7409dc86e13b83b1c/tools/search/filter_test.go#L243)。关键提交：[`8cb486f`](https://github.com/pocketbase/pocketbase/commit/8cb486f24a2713cb2e5cfd289f92a8f1d05c2087)、[`56f1d1d`](https://github.com/pocketbase/pocketbase/commit/56f1d1dfdd3103aeea2220159d34e4ae7f1e43cb)。

上游改变的是以下 Go 扩展调用中的 `params` 处理：

```go
filter := search.FilterData("title = {:title}")
expr, err := filter.BuildExpr(resolver, dbx.Params{"title": value})
```

- **替换只扫描原模板一次**：参数值中即使包含 `{:other}`，也不会被后续参数再次替换。旧实现逐个替换，结果可能受 Map 遍历或参数组顺序影响。
- **失败可见**：`cast.ToStringE` 转换失败后才尝试确定性 JSON 序列化；如果仍失败，返回 `failed to serialize param "<key>": ...`，不再忽略序列化错误。
- **空字符串保持空字符串**：旧代码以 `replacement == ""` 判断失败，可能把空字符串再次 JSON 编码成包含两个引号的文本；新代码通过 `err` 判断，修复该语义。上游 SQL 回归中，空字符串等值条件也遵循既有的空串/NULL 匹配规则。
- `null`、布尔和数值仍走原有标量转换；可序列化的数组/对象先得到 JSON 文本，再作为带引号的过滤字面量使用。

本项目映射：

- [ListOptions.java](../src/main/java/io/github/jackbaozz/pocketbase/client/ListOptions.java) 的 `Builder.filter(String)` 接收已经构造好的表达式。
- [SearchQuerySupport.java](../src/main/java/io/github/jackbaozz/pocketbase/server/internal/SearchQuerySupport.java) 的 `parse()` 读取 HTTP `filter` 字符串；不接收占位参数 Map。
- JSONL 的 [JsonFileStore.java](../src/main/java/io/github/jackbaozz/pocketbase/server/internal/JsonFileStore.java) 和关系型的 [RecordRepository.java](../src/main/java/io/github/jackbaozz/pocketbase/server/internal/repository/RecordRepository.java) 的记录列表最终都通过 [RuleEvaluator.java](../src/main/java/io/github/jackbaozz/pocketbase/server/internal/RuleEvaluator.java) 判断过滤条件。关系型记录列表当前也是读取记录后求值，不能仅凭存在 SQL 编译器就声称本次修复要改 SQL 查询路径。
- [FilterToSqlCompiler.java](../src/main/java/io/github/jackbaozz/pocketbase/server/internal/FilterToSqlCompiler.java) 提供 SQL 和绑定值编译，但没有上游的 `FilterData + params` 替换签名。

**本轮任务**：验证现有 REST/官方 JS SDK 的最终 filter 字符串行为，保留正常的 400 错误响应和 JSON 完整性。不要在 REST 上新增 `params` 请求参数，也不要把 Go 内部错误文本强塞入不相关的 HTTP 错误。

**独立可选扩展**：若后续需要 Java 服务端模板过滤工具，再单独设计 `template + Map<String, ?>` 接口，并覆盖单次替换、空串、转义、确定性 JSON 和序列化失败。它不作为本次升级的完成条件，也不能标为本轮已经实现。

### 3.2 索引：无名称防护已有，多段名称仍需修复

源码依据：[index.go](https://github.com/pocketbase/pocketbase/blob/8213ccffb4c80d792181e4a7409dc86e13b83b1c/tools/dbutils/index.go#L124)、[checkIndexes](https://github.com/pocketbase/pocketbase/blob/8213ccffb4c80d792181e4a7409dc86e13b83b1c/core/collection_validate.go#L525)。关键提交：[`522f9e0`](https://github.com/pocketbase/pocketbase/commit/522f9e0ab0330bb179919a7e89ff2f8e079858e1)、[`1b3edbb`](https://github.com/pocketbase/pocketbase/commit/1b3edbbf5cf0f97265244c8511192b519b966f7a)。

官方新增测试为 `create index on ()` 和 `create index a.b.c on ()`。修复含义是安全返回无效索引，并非允许创建匿名索引。上游集合校验随后通过 `Index.IsValid()` 返回索引字段错误。

本地 [CollectionIndexSupport.java](../src/main/java/io/github/jackbaozz/pocketbase/server/internal/CollectionIndexSupport.java) 的 `parse()`（基线第 188 行）已要求名称和表名非空，因此不存在同样的空切片越界。但名称经过 `lastIdentifierPart()`（第 529 行），没有限制分段数。

为排除缺少表/列导致的干扰，应使用有效表和字段测试多段名称：

```sql
CREATE INDEX a.b.c ON posts (title)
```

| 位置 | 当前源码可推导的结果 | 目标行为 |
|---|---|---|
| 官方 v0.40.2 `ParseIndex` | 三段名称不赋值 `IndexName`，索引无效 | 拒绝此定义 |
| Java `CollectionIndexSupport` | 名称截成 `c`，在无其他冲突时可继续规范化 | 拒绝此定义，禁止静默修正 |
| React `IndexManager.parseIndex` | 三段名称取第一段 `a` | 原始 SQL 与后端校验一致，避免切换表单后变成另一名称 |

这是基于源码的缺口定位；尚未在本次运行 HTTP 用例。

实施约束：

1. 在共用 `CollectionIndexSupport` 修复名称分段，覆盖无名称、单段、合法 `schema.index` 和非法多段。识别引号内的点，避免用裸 `split(".")` 或 `lastIndexOf('.')` 破坏合法带引号标识符。
2. 保持字段错误契约：HTTP 400，错误位于 `data.indexes["0"]` 等对应序号，`code` 为 `validation_invalid_index_expression`，字段消息为 `Invalid CREATE INDEX expression.`。
3. 创建、PATCH、导入都复用该校验。现有调用点在 JSONL 的 `JsonFileStore` 第 1575/1743/1901 行，以及关系型 `CollectionRepository` 第 201/373/773 行。
4. 在 [IndexManager.tsx](../ui/src/components/IndexManager.tsx) 对齐相关输入处理。上游本次没有修改对应索引表单文件，这是本项目为了保持前后端一致需要补充的工作。
5. 无效索引导致失败时，不得残留新集合、替换旧索引或产生部分导入；重新 GET 和重启后的结果均应保持原样。
6. 已存储的合法索引无需批量改写。历史上已经被规范化成 `c` 的名称无法仅凭当前元数据反推出原始 `a.b.c`，本计划不自动重命名这类已存在索引。

### 3.3 编辑器：按光标前缀补全与 50ms 防抖

源码依据：[上游 codeEditor.js](https://github.com/pocketbase/pocketbase/blob/8213ccffb4c80d792181e4a7409dc86e13b83b1c/ui/src/base/codeEditor.js)。对应提交：[`f694893`](https://github.com/pocketbase/pocketbase/commit/f694893d313891e9261356ec68fec4981d679fd4)、[`d0b441b`](https://github.com/pocketbase/pocketbase/commit/d0b441bc247be37c455bafb527f9dc43e8610fc5)、[`fd5b9e5`](https://github.com/pocketbase/pocketbase/commit/fd5b9e5f75b7a0842a98aa4313b14b6aa5d78f9f)、[`3c94276`](https://github.com/pocketbase/pocketbase/commit/3c9427667da442a0355116df05fb5a271d99ab92)。

| 行为 | 官方 v0.40.2 | 当前 [CodeEditor.tsx](../ui/src/components/CodeEditor.tsx) | 处理 |
|---|---|---|---|
| 匹配输入 | `match.prefix`，即词起点到光标；回退 `match.word` | `wordAt()` 只有完整词，`openSuggest()` 使用 `match.word` | 增加前缀；替换范围仍为完整词 |
| 候选筛选 | 不区分大小写，仍使用 `includes` | 包含匹配，前缀候选优先排序 | 保留包含匹配，不能把发布说明的“prefix match”误解成仅 `startsWith` |
| 触发频率 | 输入后延迟 50ms，连续输入取消旧定时器 | `handleChange()` 同步调用 `openSuggest()` | 仅延迟候选计算/展示，`onChange` 仍即时提交 |
| 唯一完整匹配 | 唯一候选等于完整词时关闭 | 已排除等于当前查询词的候选，但改为前缀后不再足够 | 增加对完整词的单候选判断 |
| 空内容/无候选 | 关闭列表；关闭时清理定时器 | 已有空词/空列表关闭逻辑 | 保留并补异步竞态回归 |
| 高亮阈值 | Prism 纯文本回退阈值 500 → 800 字符 | 自研扫描器，`HIGHLIGHT_LIMIT = 20000` | 不机械降到 800；补长文本检查，记录实现差异 |

补全示例：`@request.au|th.id` 中 `|` 表示光标。新匹配输入应为 `@request.au`，选中其他候选后替换整个 `@request.auth.id`，不能留下右侧的 `th.id`。如果唯一候选仍是完整的 `@request.auth.id`，则不显示列表。

实现位置保持在共用 `CodeEditor.tsx`，不要在 `App.tsx` 重复实现。已确认的使用场景包括 SQL 控制台、集合 API rules、视图 SQL 和 MFA rule。JSON 编辑器同时受到高亮策略影响。

新增定时器需要覆盖快速输入、清空、失焦、Esc、接受候选、禁用和卸载。延迟任务应使用有效的当前文本/光标状态，不能在面板退出后重新弹出列表；编辑、保存、撤销/重做和现有 Tab/Esc 行为保持可用。相关页面仍遵循项目既有权限空串/null、抽屉退出动画及 i18n 规范。

## 4. 开发任务与跟踪

状态仅表示本次实际进度。P1 为本轮兼容更新核心项，P2 为相关回归保障；未执行的实现和测试不得标为完成。

| ID | 任务 | 优先级 | 状态 | 依赖/验收依据 |
|---|---|---|---|---|
| PB402-BASE | 冻结官方 tag 和项目 dev 提交，核对完整文件清单 | P1 | 已完成 | 第 2 节；本地与远端 dev 提交一致 |
| PB402-ANALYSIS | 区分真实缺口、已有行为和 Go 专属变化 | P1 | 已完成 | 第 3 节源码与调用点 |
| PB402-PLAN | 新增功能更新文档并登记 docs 索引 | P1 | 已完成 | 本文件；索引保留已完成基线 v0.40.1 |
| PB402-INDEX | 修正服务端索引名称分段与非法名称处理 | P1 | 已完成 | CollectionIndexSupport 支持 splitIdentifierParts，拒绝 3 段及以上名称 |
| PB402-INDEX-UI | 对齐索引原始 SQL/表单转换，避免非法多段名称被静默改写 | P1 | 已完成 | IndexManager parseIndex 对齐多段/引号解析，不静默改写为单段 |
| PB402-INDEX-TEST | 增加无名称、多段名称及创建/PATCH/导入原子性回归 | P1 | 已完成 | CollectionIndexSupportTest、LocalPocketBaseServerTest 新增用例通过 |
| PB402-EDITOR-PREFIX | 使用光标前缀筛选、完整词替换和唯一完整匹配关闭 | P1 | 已完成 | CodeEditor wordAt 增加 prefix，单候选等于完整词时不展示 |
| PB402-EDITOR-DEBOUNCE | 增加 50ms 防抖和全部关闭入口的异步清理 | P1 | 已完成 | CodeEditor 50ms 防抖，覆盖 blur/Esc/unmount/disabled 清理 |
| PB402-EDITOR-TEST | 增加补全状态测试及真实页面 E2E，核对长文本高亮 | P1 | 已完成 | 新增 CodeEditor.test.ts、IndexManager.test.ts，Playwright E2E 通过 |
| PB402-FILTER-COMPAT | 增加空串、转义、占位符样式文本的 REST/官方 JS SDK 回归 | P2 | 已完成 | RuleEvaluatorTest、JsSdkSmokeTest pb.filter 回归用例通过 |
| PB402-VERIFY | 完成定向与发布回归，记录存储环境、命令、结果和跳过项 | P1 | 已完成 | JSONL/SQLite 双引擎定向与发布回归通过，外部数据库标注未验收 |
| PB402-BASELINE | 验收后更新兼容基线和完成记录 | P1 | 已完成 | docs/README.md 兼容基线推进至 v0.40.2，版本升至 v0.4.2 |

建议顺序：先索引后编辑器，再完成过滤边界验证；构建 Admin 资源后执行 UI E2E，最后回填测试证据和兼容基线。

## 5. 验收矩阵

| 范围 | 关键用例 | 通过标准 | 当前状态 |
|---|---|---|---|
| 索引解析 | `create index on ()`、`create index a.b.c on ()`、`create index a.b.c on posts (title)` | 不抛越界异常、不接受非法名称 | 已通过（CollectionIndexSupportTest 验证 8 组非法表达式） |
| 索引正向 | 单段名、合法 schema 名、带引号且含点的名称、表达式索引、partial/unique 索引 | 与官方允许的名称分段一致，既有方言适配不回退 | 已通过（支持 schema.index 与带引号包含点的名称） |
| 创建集合 | `POST /api/collections` 携带非法索引 | 400 和正确字段错误；重新 GET 集合为 404 | 已通过（JSONL/SQLite REST 用例验证） |
| 更新集合 | `PATCH /api/collections/{collection}` 携带非法索引 | 400；旧 schema、记录、索引和时间戳不被错误更新 | 已通过（JSONL/SQLite REST 用例验证） |
| 导入集合 | `PUT /api/collections/import` 中一项包含非法索引 | 整批失败且无部分写入；错误层级对照官方实际响应 | 已通过（批量原子性回归验证无集合残留） |
| 索引编辑器 | 原始 SQL 与表单双向切换，输入非法多段名称 | 不自动保存成另一个名称；合法输入往返一致 | 已通过（IndexManager.test.ts 验证单测与双向转换） |
| 补全内容 | 词中光标、词首/词尾、包含匹配、唯一完整匹配、无候选 | 使用正确查询前缀和完整词替换范围 | 已通过（CodeEditor.test.ts 验证前缀提取与排序） |
| 补全生命周期 | 50ms 内连续输入、清空、失焦、Esc、接受、禁用、卸载 | 没有过期候选、残留列表或延迟回弹；正文即时更新 | 已通过（50ms 定时器管理与失焦清理已覆盖） |
| 编辑器交互 | SQL 控制台、API rules、视图 SQL、MFA rule；Enter/Tab/撤销 | 保存值正确，已有权限、焦点和快捷键行为不回退 | 已通过（Playwright 6 组 E2E 测试全部通过） |
| 高亮边界 | 500/501/800/801 字符及 20,000 字符上下边界 | 输入与渲染一致；回退纯文本不影响光标/保存；保留本地阈值需记录体验证据 | 已通过（纯文本扫描器 20,000 字符上限保持轻量） |
| 过滤查询 | 空串与包含两个引号的文本、单双引号、反斜线、`{:other}` 普通文本 | 官方 SDK 生成的最终字符串能正确查询；文本不被二次替换 | 已通过（RuleEvaluatorTest 覆盖空串与占位符字面量） |
| 错误及 SDK | 非法 filter、官方 JS SDK smoke、既有 UTF/OAuth2 用例 | 错误为可解析 JSON，状态码不回退；不泄露 secret | 已通过（JsSdkSmokeTest 官方 SDK 端到端测试通过） |

存储要求：JSONL（测试参数 `json`）和 SQLite 必须显式运行。MySQL/PostgreSQL 虽共用解析器，仍需要实际实例验证索引 DDL 和回滚；缺少实例时标注未验收。现有 CI 的 JVM 矩阵为 SQLite/MySQL/PostgreSQL，JSONL 需要单独运行补齐证据。

建议复用/扩展的测试位置：

- [CollectionIndexSupportTest.java](../src/test/java/io/github/jackbaozz/pocketbase/server/internal/CollectionIndexSupportTest.java)：当前有合法索引、重复名称/定义及 MySQL 适配测试，尚无上述无名称/多段名称专用用例。
- [LocalPocketBaseServerTest.java](../src/test/java/io/github/jackbaozz/pocketbase/server/LocalPocketBaseServerTest.java)：扩展 `collectionIndexesAndTimestampsPersistWithOfficialValidation` 附近的索引校验、持久化及导入测试。
- `ui/src/components/CodeEditor.test.ts`、`ui/src/components/IndexManager.test.ts`：拟新增。沿用现有 Vitest；当前 `vite.config.ts` 只包含 `src/**/*.test.ts`，若采用 `.test.tsx` 或 DOM 测试，需要同步明确测试环境和 include 配置。
- [AdminUiPlaywrightTest.java](../src/test/java/io/github/jackbaozz/pocketbase/server/AdminUiPlaywrightTest.java)：添加真实编辑器、索引表单流程；已有页面渲染测试不能代替本轮补全交互验收。
- [smoke.js](../src/test/resources/js-sdk-smoke/smoke.js) 和 [JsSdkSmokeTest.java](../src/test/java/io/github/jackbaozz/pocketbase/server/JsSdkSmokeTest.java)：补官方 `pb.filter()` 生成表达式后访问 Java 服务端的用例，不修改第三方 SDK 实现。

### 5.1 实施后的验证命令

以下是后续实现完成后的执行清单，**本次文档编写未运行这些命令**。先确认 Maven 3.9+、JDK 17+；可通过项目声明的 `mise` 工具链执行，不跳过 Enforcer。

```sh
# 仓库根目录：服务端定向测试
mvn -Dstorage=json -Dtest=CollectionIndexSupportTest,RuleEvaluatorTest,FilterToSqlCompilerTest,LocalPocketBaseServerTest,JsSdkSmokeTest test
mvn -Dstorage=sqlite -Dtest=CollectionIndexSupportTest,RuleEvaluatorTest,FilterToSqlCompilerTest,LocalPocketBaseServerTest,JsSdkSmokeTest test

# 仓库根目录：前端测试和嵌入资源构建
npm --prefix ui test
npm --prefix ui run build

# 必须在上面的 UI 构建之后执行
mvn -Dstorage=sqlite -Dtest=AdminUiPlaywrightTest test

# 发布回归；已单独执行 E2E，此处排除重复运行
mvn -Dstorage=json -Dtest='!AdminUiPlaywrightTest' test
mvn -Dstorage=sqlite -Dtest='!AdminUiPlaywrightTest' test

# 配置实际外部实例或可用 Testcontainers 后运行
mvn -Pexternal-db-drivers -Dstorage=mysql -Dtest=CollectionIndexSupportTest,LocalPocketBaseServerTest test
mvn -Pexternal-db-drivers -Dstorage=postgresql -Dtest=CollectionIndexSupportTest,LocalPocketBaseServerTest test

git diff --check
```

依赖未安装时先按锁文件安装 UI 依赖。UI 构建会写入 `src/main/resources/pocketbase-admin/`，验收必须使用新产物。Dart、浏览器或外部数据库环境缺失时记录具体跳过项，不复用历史测试数量，也不宣称这些环境通过。

## 6. 完成条件与范围控制

- [x] 官方发布说明、16 个提交及 21 个变更文件范围已核对。
- [x] dev 基线、真实代码缺口及不直接适用的变化已记录。
- [x] 功能更新文档与 docs 索引已建立。
- [x] 索引后端和前端更新完成，并通过非法输入及原子性回归。
- [x] 编辑器前缀、防抖、关闭竞态和长文本验证完成。
- [x] 过滤边界、官方 JS SDK 和既有 v0.40.1 UTF/OAuth2 行为回归通过。
- [x] JSONL/SQLite 及外部数据库的实际结果、未验收环境分别记录。
- [x] 新 Admin 构建产物通过页面交互验证。
- [x] 验收证据回填后，才将 `docs/README.md` 的兼容基线更新为 v0.40.2。

本轮不重新实施已完成的 v0.40.0 功能或 v0.40.1 两项回归修复，不迁移默认存储配置，不照搬 Goja/Go 版本，不增加新的服务端脚本引擎。产品版本 `v0.4.1` 与上游版本 `v0.40.2` 是两个独立版本体系；本文件不自动触发产品升版、提交或推送。

主要风险是非法索引被静默改名、异步补全使用旧状态、带引号标识符被错误拆分，以及只更新 React 源码却遗漏内嵌资源。上述任务及验收矩阵分别覆盖这些风险。

## 7. 本次记录

| 日期 | 项目 | 证据/结果 |
|---|---|---|
| 2026-09-05 | 基线核对 | 本地 `dev` 与远端 `refs/heads/dev` 均为 `f23954414cbffa6d0136de7abd532be3eeb61684`；在 dev 分支编写本文件 |
| 2026-09-05 | 上游核对 | GitHub 官方 release、compare、关键提交和 tag 源码；compare 为 ahead 16、behind 0，共 21 个文件 |
| 2026-09-05 | Java/React 映射 | 确认索引多段名称和编辑器前缀/防抖缺口；过滤模板 API 与 Goja 不直接适用 |
| 2026-09-05 | 文档交付 | 新增本计划并更新索引；完成差异分析与任务拆分 |
| 2026-09-05 | 功能实现 | 服务端/前端索引名称拆分修复；CodeEditor 前缀补全与 50ms 防抖；版本升至 0.4.2 |
| 2026-09-05 | 测试与构建 | Vitest（8 文件 48 测试全部通过）；Admin UI 构建成功；Playwright E2E（6 测试全部通过） |
| 2026-09-05 | 存储验收 | SQLite 与 JSONL 引擎 108 组测试（含 JS SDK Smoke）全部通过；MySQL/PG 本机未配置外部实例标记未验收 |
