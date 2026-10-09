# RPD：PocketBase v0.40.5 差异分析与 Java 兼容升级计划

> 核对日期：2026-10-09（Asia/Shanghai）\
> 工作分支：`dev`；Java 基线：`1a5432ded2310729fb3e80d92276e2517d46c202` / `v0.4.4`\
> 上游：`v0.40.4` (`5cec579da984436a258602a46a96302fbd31f77c`) → `v0.40.5` (`6f370ea741f33adf889eef02e3c1b884a47aa522`)\
> Java 目标版本：`v0.4.5`\
> 状态：Java/React 适用项开发及本地验收已完成；工作区未提交。外部数据库和远程 CI 尚未运行。

## 1. 官方发布与净差异

官方 `v0.40.5` 发布于 **2026-10-08 16:45:10 UTC**，即北京时间 **2026-10-09 00:45:10**。两个最终 tag 之间有 **19 个提交、28 个变更文件**。本轮没有新增 REST 路由、方法、请求字段或响应结构。

官方证据：

- [官网版本](https://pocketbase.io/)
- [v0.40.5 Release](https://github.com/pocketbase/pocketbase/releases/tag/v0.40.5)
- [v0.40.4...v0.40.5 Compare](https://github.com/pocketbase/pocketbase/compare/v0.40.4...v0.40.5)
- [Compare API：完整提交和文件清单](https://api.github.com/repos/pocketbase/pocketbase/compare/v0.40.4...v0.40.5)
- [CORS 实现](https://github.com/pocketbase/pocketbase/blob/v0.40.5/apis/middlewares_cors.go)及[官方测试](https://github.com/pocketbase/pocketbase/blob/v0.40.5/apis/middlewares_cors_test.go)
- [编辑器实现](https://github.com/pocketbase/pocketbase/blob/v0.40.5/ui/src/base/codeEditor.js)

| 官方变更 | 关键提交 | 本系统映射与处理 |
|---|---|---|
| `SafeWrap` 返回最多 2 KB panic 栈；JS 迁移 up/down 单独包装，以保留失败文件名 | `86ce1be8fe35` | Java 没有 Go panic、Goja/JSVM 或 JS 迁移注册器。启动失败已保留原异常、cause、suppressed cleanup 异常，并回收资源；不增加伪 JS 迁移 API，不向 HTTP 响应泄露栈或异常原文。沿用生命周期回归。 |
| JSVM 负数 pool size 归零 | `a12bccaafc97` | 无 JSVM pool。Java HTTP executor 已将配置限制在 8–1024，日志队列有界，不移植 Go 特有对象池。 |
| CORS 快速子域匹配校验所有段，避免 `*` 提前命中而跳过固定前缀；注释澄清通配符 | `211b9645ee80`、`5cdbca2b0478` | 原 Java 固定返回 `*`，不存在相同的不安全 matcher，但缺少可配置白名单。补齐官方 `--origins` 入口及 Java properties/env 映射，实现快速匹配和有长度限制的兼容 glob，验证成功/错误响应和预检。 |
| 编辑器 autocomplete 支持 Promise；候选支持独立 label/value 和自定义 label；内容 CSS 名加前缀 | `35ddf50cbf87`、`d71a58973382` | React CodeEditor 支持数组、同步/异步 provider、独立插入值与 React label；补齐取消/过期结果保护。已有 `pbce-*` CSS 前缀，沿用以避免冲突；不照搬官方 DOM label 回调。 |
| autoexpand textarea 的 overlay/input 预留 scrollbar gutter | `6ffc1b2078f0` | Java UI 的 CodeEditor 已使用单一滚动容器，两个文字层不会独立滚动。在多行公共容器预留稳定 gutter；禁止在透明 textarea 单独预留宽度，否则会破坏高亮对齐。 |
| UI extensions Hook 增加固定 ID，允许重绑定 | `be327b212b3c` | Java 不支持官方 `OnServe`/UI extension Hook；HTTP context 每次启动创建一次，close 不重复。不添加仅名字相同的 Hook。 |
| `strings.ToLower(...) == "null"` 改为 `strings.EqualFold` | `2ae117228e41` | Go 的等价实现优化，无 API 变化；Java 记录排序由 `RecordFieldResolverSupport` 解析，交由 `SearchQuerySupport.sortMaps` 处理，没有对应的 Go SQL identifier 字符串比较路径，不捆绑改变排序语义。 |
| SQLite Go 驱动 `modernc.org/sqlite 1.57.0 → 1.60.1`、libc 和其他 Go 依赖更新 | `a47551804cf2`、`41cac2d0cea9`、`290fa67ec9a0` | Java 使用 Xerial sqlite-jdbc，经 JNI 调用原生 SQLite；不经过 modernc 的 Go 转译实现。没有证据表明升级 Maven 驱动能移植同一优化，本轮保持依赖版本并验证 SQLite 路径。 |
| CI 最低 Go 版本 `1.27.1 → 1.27.2`，包含 `net/http` 安全修复 | `8368a7f4e5c0` | Java 使用 JDK `HttpServer`，没有 Go `net/http` 代码。不能声明修复了 Java 同名漏洞；检查现有请求体限额、有界 executor、预检处理并做回归。Java 运行环境仍需正常跟随 JDK 安全更新。 |
| Hook 名、文档链接/拼写、邮件模板多余分号及版本/产物更新 | `457e094d41e6` 等 | 上轮 RPD 已记录最终名称 `OnBootstrapClear`；Java 邮件模板没有该多余分号。更新当前版本和 docs 索引，由 React 源码自行生成嵌入资源。 |

28 个文件中，8 个是官方 UI 配置或构建产物，另有 3 个 UI 源码文件；按实际路径计，`ui/` 总计 11 个，其余包括 Go 源码、测试、依赖和发布文档。完整清单以 Compare API 为准，不复制官方 `ui/dist` 或生成的 Goja 类型。

## 2. Java CORS 设计及兼容边界

- 配置优先级：`--origins` > `-Dserver.origins` > `PB_ORIGINS` > `server.origins` properties。支持逗号分隔；默认 `*`，保留原有 ServerConfig 构造器。
- 单个明确来源或 `*` 直接匹配；子域快速路径要求协议相同、完整段数相同、每个固定段都匹配，authority 不超过 253 UTF-8 字节。
- **严格快速路径不是最终白名单语义**：官方还保留有长度限制、首尾锚定的 `*`/`?` 正则 fallback。例如 `https://*.example.com` 最终仍能匹配 `https://a.b.example.com`。Java 用非正则 glob 实现等价匹配，避免引入 Java 正则回溯风险。
- `https://trusted.*.example.com` 不得接受 `https://evil.a.example.com`，不得接受恶意追加域名、错误协议或错误端口。
- 没有 Origin 时不输出 Allow-Origin；始终设置 `Vary: Origin`。允许的预检反映客户端请求的 headers，并返回 `204`；不允许的预检也返回 `204`，但不输出允许来源/headers。
- 与官方一致，不允许的普通来源仍执行原 HTTP 路由，只是不输出 CORS 许可；CORS 不替代 API 认证。
- 预检在认证、限流、请求体读取前返回，不产生活动日志。已有 API 权限、`null`/`""` 规则语义保持不变。

代码入口：[`ServerConfig`](../src/main/java/io/github/jackbaozz/pocketbase/server/ServerConfig.java) → [`LocalPocketBase`](../src/main/java/io/github/jackbaozz/pocketbase/server/LocalPocketBase.java) → [`HttpApi`](../src/main/java/io/github/jackbaozz/pocketbase/server/internal/HttpApi.java) → [`HttpCorsPolicy`](../src/main/java/io/github/jackbaozz/pocketbase/server/internal/HttpCorsPolicy.java)。

## 3. React 编辑器设计

[`CodeEditor`](../ui/src/components/CodeEditor.tsx) 的 `completions` 可为候选数组，或 `(prefix) => items | Promise<items>`。候选可为字符串或 `{ value, label }`；`value` 用于插入，数组候选始终按 `value` 筛选，字符串 `label` 可额外匹配但不得屏蔽插入值。`label` 可为 React 节点，不使用 HTML 注入或官方命令式 DOM 回调。

[`CompletionRequests`](../ui/src/components/codeEditorCompletions.ts) 统一控制结果生命周期：新输入、新请求、关闭、失焦、Esc、禁用和卸载会使旧请求失效；Promise 返回后再次核对输入值、焦点、光标及 DOM 是否仍挂载。失败 provider 不得中断编辑或产生 unhandled rejection。保留 50 ms 防抖、已有候选排名和最多 30 项的限制。

布局保留 `pbce-*` 命名和现有主题 CSS 变量；稳定 scrollbar gutter 写入组件 CSS 的公共多行滚动容器。没有新增界面文案，因此不增加九语言翻译 key；版本文案同步更新。

## 4. 任务分解

| ID | 开发任务 | 验收方式 | 状态 |
|---|---|---|---|
| PB405-BASE | 核对官网、Release、最终 tag SHA、19 个提交和 28 个文件，完成 Java/React 适用性分析 | 官方源码和 Compare API | 已完成 |
| PB405-CORS | 配置入口、旧构造器兼容、严格快速匹配、bounded glob、预检及 Vary headers | HttpCorsPolicyTest / CorsCompatibilityTest / ServerConfigTest | 已完成 |
| PB405-EDITOR | 异步补全、label/value、过期结果取消和失败保护 | 既有 CodeEditor 测试及新增 provider 测试，浏览器验收 | 已完成 |
| PB405-LAYOUT | 多行公共 scroller gutter，CSS 名及 overlay 对齐确认 | React build 和浏览器验收 | 已完成 |
| PB405-REGRESSION | SQLite、JSONL、SDK、生命周期、路由及嵌入 Admin UI 回归 | Maven、Vitest、Playwright | 已完成（本地适用矩阵） |
| PB405-NATIVE | Native Image 编译、SQLite 重启持久化和原生 CORS 正/反例 | native profile / native-sqlite-smoke.sh | 已完成 |
| PB405-CI | UI CI 加入 npm test，现有 JVM 多存储与 Native smoke 覆盖本轮新增测试 | workflow 配置及对应命令本地执行 | 已完成（配置）；远程未运行 |
| PB405-VERSION | 项目 `0.4.5`、User-Agent、双语 README、docs、UI 版本和嵌入产物 | 版本检索、构建、diff check | 已完成 |

本轮不执行 Git 提交、推送或合并，发布动作在用户后续安排时单独完成。

## 5. 实际完成记录

以下均为 **2026-10-09 本轮实际运行**，不是上次升级的测试结果。

| 门禁 | 实际命令 / 方式 | 结果 |
|---|---|---|
| CORS 与配置专项 | `mvn -Dstorage=sqlite -Dtest=HttpCorsPolicyTest,CorsCompatibilityTest,ServerConfigTest test` | **20/20 通过**，包含官方 16 个快速匹配场景、固定前缀绕过反例、bounded glob、配置优先级、401 响应及预检/限流验证 |
| SQLite 全量与 JAR | `mvn clean package -Dstorage=sqlite -Dtest='!AdminUiPlaywrightTest'` | **311 项：310 通过，1 跳过，0 失败/错误**；Dart SDK 未安装，Dart smoke 跳过；生成 `target/pocketbase-java-0.4.5-all.jar` |
| JSONL 专项矩阵 | 见下方命令 | **115/115 通过**，覆盖 CORS、配置、84 个服务器回归、SDK、进程、生命周期、文件响应和 SSE |
| UI 单元测试 | `npm --prefix ui test` | **9 个测试文件，61/61 通过**；新增 6 个 provider/竞态/取消/失败/上限用例，审查后另补 4 个 label/value 筛选回归用例 |
| UI 编译与嵌入资源 | `npm --prefix ui run build` | **通过**；审查修复后重建为 `index-C4rtJl0g.js` / `index-CiH-mrh7.css` 等产物，写入 `src/main/resources/pocketbase-admin/`，index 引用的资源全部存在 |
| 编辑器真实浏览器交互 | 隔离 Chrome 页挂载实际 React CodeEditor，调用同步/异步 provider；测试夹具和开发进程已清理 | **13/13 检查通过**：数组排名、插入、Promise、自定义 label/value、旧请求、失焦、Esc、禁用、reject 保护、后续输入恢复、gutter、overlay 宽度对齐、卸载；无 unhandled rejection |
| 嵌入式 Admin UI E2E | `mvn -Dstorage=sqlite -Dtest=AdminUiPlaywrightTest test` | **7/7 通过**，实际服务加载本轮新 hash 的 JS/CSS |
| Native 编译 | `mise exec java@25-graalvm -- mvn -gs settings.xml -s settings.xml -Pnative package -DskipTests` | **BUILD SUCCESS**，GraalVM `25.0.3`，生成 `target/pocketbase-java`；复用本轮已清洁构建的 JAR，以保留测试报告 |
| Native 持久化与 CORS | `bash scripts/native-sqlite-smoke.sh ./target/pocketbase-java` | **通过**：初始化、CRUD、重启后 SQLite 数据存在；重启前后均允许 `trusted.a.example.com`、拒绝 `evil.a.example.com` 的 CORS 许可 |
| 本轮 Java 文件格式 | 限定本轮 8 个 Java 文件执行 Spotless，完整命令见下方 | **通过** |
| 仓库全量格式 | `mvn -o spotless:check` | **基线阻断**：7 个未参与本轮的历史文件格式差异，见下面说明 |
| 文档/语言/空白检查 | 新 RPD 和 docs 索引的相对链接检查、九语言 key 集合比较、`git diff --check`、`bash -n scripts/native-sqlite-smoke.sh` | **通过**；0 断链、9 个语言 key 集合一致、脚本语法有效 |
| MySQL/PostgreSQL、远程 CI | 未执行 | 保留现有四存储 CI matrix，不把配置存在当作已验证结果 |

JSONL 实际命令：

```sh
mvn -Dstorage=json \
  -Dtest=HttpCorsPolicyTest,CorsCompatibilityTest,ServerConfigTest,LocalPocketBaseServerTest,LifecycleCompatibilityTest,RealtimeSmokeTest,JsSdkSmokeTest,HttpFileSupportTest,PocketBaseServerProcessTest \
  -Dmaven.test.redirectTestOutputToFile=true test
```

本轮文件的格式门禁：

```sh
mvn -o spotless:check '-DspotlessFiles=.*(HttpCorsPolicy|HttpCorsPolicyTest|CorsCompatibilityTest|ServerConfig|ServerConfigTest|HttpApi|LocalPocketBase|PocketBaseClient)[.]java'
```

完整格式门禁仍被历史文件阻断：`AuthCollectionConfigMerge.java`、`JsonResponseSanitizer.java`、`LogPersistenceSanitizer.java`、`repository/LogRepository.java`、`PocketBaseServerProcessTest.java`、`HttpFileSupportTest.java`、`SqliteDefensiveModeTest.java`。与上次升级记录一致，本轮只格式化参与改动的文件。

### 代码审查修复（2026-10-09）

- 修复字符串 `label` 替代 `value` 导致数组候选按代码前缀补全失败的问题；保留标签匹配、大小写不敏感、前缀优先、按插入值去重和已完成值排除。
- 新增 4 个回归用例，修复前 4 个全部失败；修复后 UI 全量 **61/61 通过**，TypeScript 与前端生产构建通过，嵌入资源已同步。
- 本次仅修改补全筛选、回归测试、相关开发记录和前端构建产物；未再次执行 Java/Playwright/Native 验证，以上对应结果属于此次审查修复前的升级验收。已有 JAR 和 Native 可执行文件需重新打包才会包含最新前端资源。

### 发布收口与边界

- [x] 官方 tag、提交、文件、发布时间及 API 变化面已核对。
- [x] Java CORS 的实际调用路径、配置与安全反例已验证。
- [x] React 补全与布局修复已实现，生产资源已重新生成。
- [x] SQLite、JSONL、官方 JS SDK `0.28.1` 和现有生命周期/路由回归已通过。
- [x] Native Image 与真实原生 SQLite/CORS smoke 已通过。
- [x] 项目 patch 从 `0.4.4` 升到 `0.4.5`，README、CHANGELOG、User-Agent、UI 与 docs 索引同步。
- [ ] 本地外部数据库或远程 MySQL/PostgreSQL CI 实跑。
- [ ] Git 提交、推送、`main` 同步及远程发布。

`Go net/http` 和 `modernc` 的改动没有被宣称为 Java 已修复的漏洞或等价性能优化。JSVM/up-down migration/extension Hook 没有被假实现。当前完成结论仅指本 RPD 中适用于 Java/React 的开发和已执行的本地验证。
