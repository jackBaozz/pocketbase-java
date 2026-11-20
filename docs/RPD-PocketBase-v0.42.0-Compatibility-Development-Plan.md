# RPD：PocketBase v0.42.0 差异分析与 Java 兼容升级

> 核对日期：2026-11-11（Asia/Shanghai）\
> 开发分支：`dev`；本地基点：`7ac7762da1ee8fdb72a6dbcb00c51a61ff8b6b0f` / Java `v0.5.1`\
> 官方范围：`v0.41.1 → v0.42.0`；Java 目标：`v0.6.0`\
> 状态：Java/React 适用项开发与本地验收已完成；不执行提交、推送或 main 合并。

## 1. 来源与证据边界

官方 v0.42.0 于 **2026-11-11 05:10:14 UTC / 北京时间 13:10:14** 发布。发布说明随后增加认证初始化回归警告，预告 v0.42.1；本轮不把移动的 master 或未发布补丁冒充 v0.42.0。

- [官方 Release Atom](https://ganigeorgiev.com/meta/pocketbase/pocketbase/releases.atom)
- [v0.42.0 发布页](https://ganigeorgiev.com/pocketbase/pocketbase/releases?filter=tag%3D%27v0.42.0%27)
- [官方源码仓库](https://ganigeorgiev.com/pocketbase/pocketbase)
- [官方认证回归讨论 #8368](https://ganigeorgiev.com/pocketbase/discussions/8368)

新主机的源码网页 API 禁止自动抓取，因此没有绕过该限制；另通过标准 `go mod download -json` 获取官方发布的两个固定版本 Go 模块归档并逐文件比较。Go 工具链为 1.22，而模块要求 1.27，命令在归档下载后报告工具链版本不足；只解压和比较归档，没有编译或运行官方 Go 服务。归档中按重命名合并统计为 **36 个文件变化**，这是模块发行内容的比较，不宣称拥有完整 Git 提交数或所有被 Go 模块打包规则排除的仓库文件。

| 官方模块版本 | 模块校验和（go mod download） | 发行 ZIP SHA-256 |
|---|---|---|
| v0.41.1 | `h1:qIDj5ZE8ysev7LJNcLR4eeYICme5hDF/Ry3bQ9nOcTA=` | `2345888f4b30629389d21f1a0b9819be30eaa062ff8a3664c46eb801c05ed999` |
| v0.42.0 | `h1:lDlfF38OcqSNclS918u+X3UWEsIGClqyet/oll7feaQ=` | `48840e4c290fc5230b6d44b0b942b53897cfb2ff4a0468a906ccf1d615e8df50` |

Context7 的官方文档索引用于核查 token 类型与文件 token 边界，但其版本列表尚无 v0.42.0；具体差异以固定模块源码和官方发布说明为准。

## 2. 差异与适用性

本轮没有新增 REST 路由、方法或返回字段，但 **Authorization 的错误处理属于可观察的兼容性变化**。

| 官方变化 | 官方源码 | Java/React 处理 |
|---|---|---|
| 未知、过期或已撤销 Authorization token 统一拒绝为 401 | `apis/base.go`、`apis/middlewares.go`、对应测试 | 实现全局 fallback；未携带 token 仍可匿名访问。CORS OPTIONS 在认证前返回；已有 IP/限流/body limit 优先级不变。不把文件 query token 当作普通 Authorization。 |
| 上述变化暴露官方后台读取旧 token 时无法返回登录页的回归 | Release 更新、讨论 #8368 | 沿用 React 已有的 401 清理/跳转机制，新增存储旧 token 的真实浏览器回归，确保可重新登录；不移植官方 UI 初始化缺陷。 |
| 允许 JPEG XL（`.jxl`）预览，仍无缩略图支持 | `ui/src/utils.js` | 统一列表/文件编辑器的图片识别，原图预览不发送 `thumb`。Java 补充 `image/jxl`、裸码流和容器两种文件签名验证；伪扩展名仍强制下载。浏览器是否可解码取决于其 JPEG XL 支持，不新增 Java 解码器。 |
| OAuth2 提供商可拖动排序，顺序用于 `listAuthMethods()` | `ui/src/collections/oauth2Accordion.js`、`ui/src/base/sortable.js` | 增加独立 React 排序组件，支持拖动及键盘可操作的上/下移按钮；只重排 provider name，原配置按 name 保留。验证 PATCH 脱敏合并、服务重启及 auth-methods 顺序。 |
| 新增 `app.CountLogs()` helper | `core/log_query.go`、`core/app.go` | 统一 `StorageEngine.countLogs()` 与 filter 查询重载，关系库无筛选用 SQL COUNT，JSONL 同步计数；计数忽略分页与字段投影，不新增 HTTP count 路由。 |
| 日志定期清理累计删除 >= 20,000 后 VACUUM，并做 WAL checkpoint | `core/base.go` | 仅关系型 SQLite 的 `__pbLogsCleanup__` 执行；JDBC 返回精确受影响行数，避免并发插入导致前后计数误差。累计阈值、成功后归零、事务内延迟及重试分别保护。MySQL/PostgreSQL 不执行 SQLite 命令；JSONL 保留文件重写。 |
| 日志图表 >= 100,000 时加宽 y 轴 | `ui/src/logs/logsChart.js` | 根据实际格式化标签长度计算列宽，x 轴同步缩进，不依赖固定五位数宽度，兼容窄屏和更大数量级。 |
| 成功响应之后的无害断连错误记为 INFO | `apis/middlewares.go` | 只对已经提交 2xx/3xx 响应后的已知断连分类，保留成功状态和 INFO 日志；不再发送第二份错误响应。未提交响应、磁盘读取错误、其他 IOException 不降级。 |
| cron.Stop 在 ticker 初始化前的竞态防护 | `tools/cron/cron.go` | Java 使用已有 AsyncJobRunner/executor，没有该 timer/channel 路径；复用关闭等待与生命周期测试，不伪造 Go ticker。现有 cron 仍由接口手动触发，不宣称新增自动定时调度器。 |
| hex.EncodeToString 等价优化 | `core/record_query.go`、`tools/filesystem/filesystem.go`、`tools/security/crypto.go` | Go 专属等价实现，无 HTTP 变化；Java 不机械重写已有哈希/编码或改变签名。 |
| update channel 可省略 | `plugins/update/update.go` | Java 不支持官方 Go 自更新器，不引入下载并覆盖 Java 程序的路径。 |
| modernc SQLite 1.62.0 / SQLite 3.54.1，Go1.27.4 与 x/* 更新 | `go.mod`、`.goreleaser.yaml` 等 | Java 使用 Xerial JDBC 和 JDK，不经过 Go modernc/x模块；没有证据将上游安全修复等同于本项目修复，不捆绑升级无关 Maven 依赖。 |
| 版本、官方 UI 构建产物、生成 JSVM 类型 | `ui/.env`、`ui/dist` 等 | 项目版本独立升为 0.6.0，React 源码重新构建嵌入资源，不复制官方产物。 |
| 官方示例、构建脚本、说明/历史变更记录及 vendored shablon | `examples/base/main.go`、`Makefile`、`README.md`、`CHANGELOG*`、`.bugstage`、`ui/public/libs/shablon` 等 | Go 示例/分发与官方 Svelte 模板库没有本项目消费者；保留本系统 Java/React 构建和组件，同步本项目自己的版本说明与 RPD。 |

## 3. 本系统特别适配

- Java 的日志与业务数据共用 `pocketbase.db`，不是官方 `auxiliary.db`。VACUUM 因而会处理整个 SQLite 文件，仅放在维护任务且累计达到阈值时运行，不能在每次活动日志写入时触发；可能短暂争用写锁。其他表必须完整保留。
- 关系库存储此前只验证 token 的签名/类型，未在普通接口核对当前记录 `tokenKey`。本轮为了支持“改密码后旧 token 在任何端点失效”，补齐当前记录存在性和 tokenKey 校验，与 JSONL 现有语义一致。
- `null` 与空字符串规则保持区别；鉴权 fallback 不放宽集合权限。客户端若仍携带过期 token 访问公开接口，将收到 401，应清除 token 后按匿名请求重试，这是官方的预期行为，不是取消匿名访问。
- React 修改使用现有组件和主题变量；上/下移文案同步全部九种语言。JPEG XL 原图支持不等于新增 JPEG XL 缩略图或通用服务器解码能力。

## 4. 任务与状态

| ID | 任务 | 状态 |
|---|---|---|
| PB420-BASE | 固定 Release/模块版本，逐项差异及已知回归分析 | 已完成 |
| PB420-AUTH | 全局 401、撤销状态、匿名/CORS/后台重新登录回归 | 已完成：JVM、JSONL、浏览器通过 |
| PB420-JXL | Java MIME/签名及 React 原图预览，无缩略图 | 已完成：JVM、JSONL、UI 单测/构建通过 |
| PB420-OAUTH | 排序控件、密钥保留、auth-methods/restart/UI 验证 | 已完成：JVM、JSONL、浏览器通过 |
| PB420-LOGS | CountLogs、20k 累计清理、空间回收及断连分类 | 已完成：计数、阈值/事务、断连分类回归通过 |
| PB420-CHART | 大数量级日志轴布局 | 已完成：UI 单测、真实浏览器布局断言通过 |
| PB420-RELEASE | 0.6.0、文档、九语言、资源、JVM/JSONL/E2E/Native | 已完成；待后续发布指令决定提交/推送 |

## 5. 本轮验收

以下为本轮实际执行结果，不沿用历史通过记录。

| 验证项 | 本轮命令/方式 | 结果 |
|---|---|---|
| 前端单测 | `npm --prefix ui test` | **通过**：12 个文件，69/69 用例。覆盖 JXL 原图地址、排序不丢配置标识、大数值轴宽度及既有 UI 逻辑。 |
| 前端构建 | `npm --prefix ui run build` | **通过**：TypeScript/Vite 构建成功，嵌入资源重新生成；主 JS 为 `index-8jST6vU-.js`，CSS 为 `index-sneWoAj-.css`。大于 500 kB 的分包提示属于既有警告。 |
| 本轮后端专项 | `mvn -o -Dstorage=sqlite -Dtest=V042CompatibilityTest,LogMaintenanceTest,HttpResponseFailureSupportTest -Dmaven.test.redirectTestOutputToFile=true test` | **通过**：11/11；新 token、JXL、OAuth 与 CountLogs 为 7 项，日志维护/断连分类各 2 项。专项最后添加的账号/集合删除和密钥断言也在后续全量中通过。 |
| SQLite 全量打包 | `mvn -o clean package -Dstorage=sqlite -Dmaven.test.redirectTestOutputToFile=true` | **通过**：351 项，350 通过、0 失败、1 跳过（本机无 Dart SDK）；包含官方 JS SDK、认证、受保护文件、备份、Batch、SSE 以及 Playwright 10 项。 |
| JSONL 回归 | `mvn -o -Dstorage=jsonl -Dtest=LocalPocketBaseServerTest,BehaviorFixturesTest,RouteConformanceTest,CorsCompatibilityTest,BackupConsistencyTest,AuthActionPersistenceTest,JsonV040CompatibilityTest,LifecycleCompatibilityTest,JsSdkSmokeTest,LogApiAndSettingsTest,RealtimeSmokeTest,OAuth2SuccessTest,OAuth2FailuresTest,V042CompatibilityTest,BatchSmokeTest,AdminUiSmokeTest -Dmaven.test.redirectTestOutputToFile=true test` | **通过**：129 项，127 通过、0 失败、2 跳过（`AuthActionPersistenceTest` 直接检查 SQLite 文件，明确限制于 SQLite）。 |
| 真实浏览器 | `AdminUiPlaywrightTest`，本机无头 Chrome | **通过**：10/10。新增存储失效 token 后重新登录、OAuth 排序保存/重载、1,000,000 轴标签边界断言，既有 7 项仍通过。 |
| 格式与静态核验 | 对本轮 13 个 Java 文件限定绝对路径运行 Spotless apply/check；九语言 key、版本、文档本地链接、`git diff --check` 与新文件空白校验 | **通过**：9 种语言各 921 个相同 key；Maven/npm/lock/User-Agent/UI 页脚均为 0.6.0。未对无关 Java 文件批量格式化。 |
| JAR 内嵌资源 | 比较 shaded JAR 的 `pocketbase-admin/index.html`、引用资源与工作区文件字节 | **通过**：`target/pocketbase-java-0.6.0-all.jar` 包含本轮 UI 与 0.6.0 页脚。 |
| GraalVM Native 构建 | `mise exec java@25-graalvm -- mvn -o -gs settings.xml -s settings.xml -Pnative package -DskipTests` | **通过**：GraalVM 25.0.3，Darwin arm64，生成 `target/pocketbase-java`；`-DskipTests` 仅用于 native 构建，不能代替前述 JVM 回归。 |
| Native SQLite/CORS | `PB_NATIVE_SMOKE_PORT=18907 bash scripts/native-sqlite-smoke.sh ./target/pocketbase-java` | **通过**：独立临时数据目录，数据库写入/重启持久化与受限 CORS。 |
| Native 本轮功能 | 针对新二进制运行临时 HTTP/SQLite 验收脚本，不使用业务数据 | **通过**：无效/已撤销 token 401、匿名/OPTIONS、OAuth 排序及重启、受保护文件 query token、两种 JXL 签名/伪装文件/无 thumb 缓存；插入 20,000 条旧日志后从 cron API 触发清理，确认旧日志归零和 SQLite 空闲页归零，其他业务数据保留。 |

### 验收中发现并处理的问题

- 关系库普通接口未核对当前账号的 tokenKey，专项改密码测试先失败；补齐后，密码变更/删除账号/删除集合均使旧 token 返回 401，重新登录正常。
- 原有测试把邮箱验证 token、文件 token 误放在 Authorization 时期待资源 404；官方新增全局 fallback 明确先返回 401。仅更新这两个断言及错误结构，文件 query token 的有效/无效访问断言继续保留并通过。
- 新 OAuth 浏览器用例先通过 API 创建集合，页面持有旧目录；重新载入目录后操作。保存后还必须等 PATCH 成功及抽屉退出完成再重载，避免 DDL 重试期间读到旧配置。最终全量浏览器验收通过。
- 日志计数测试在重启前等待活动日志分发关闭，避免初始化请求的异步日志与断言竞争；VACUUM 用实际空闲页验证阈值/成功归零，并核对非日志表保留。

### 验证边界

没有编译或运行官方 Go 服务；没有执行真实外部 MySQL/PostgreSQL、SMTP/S3 服务验证、远程 CI、正式部署或 Linux/Windows native 验证。JXL HTTP 测试使用两类签名与伪装文件核对响应策略，不把签名夹具视为完整可解码图片，也不宣称本机 Chrome 成功解码 JXL 像素。

## 6. 交付状态

当前修改保留在 `dev` 工作区，未暂存、提交或推送；`main` 不移动。提交、推送和合并由后续发布指令单独安排。
