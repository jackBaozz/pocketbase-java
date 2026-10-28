# RPD：PocketBase v0.41.0 差异分析与 Java 兼容升级

> 核对与验收日期：2026-10-28～29（Asia/Shanghai）\
> 工作分支：`dev`；Java 基线：`1ced41d825c30f1ec8cc1c866f36a5df5aaa24e8` / `v0.4.6`\
> 上游：`v0.40.6` (`9d281ccaba65f2686eb7b0b614ccda72d1116a1b`) → `v0.41.0` (`f6baa0c43f379de21cd806de97ad6a2b8ca97b83`)\
> Java 版本：`v0.5.0`；状态：Java/React 适用增量及本地 JVM、JSONL、UI、Darwin Native 验收已完成，升级代码已提交并推送 `dev`。本轮不合并 `main`。

## 1. 官方版本与比较依据

官方 v0.41.0 于 **2026-10-26 14:23:37 UTC** 发布（北京时间 **2026-10-26 22:23:37**）。官网当前已展示 v0.41.1，本次按用户指定的 v0.41.0 固定最终 tag，不纳入 v0.41.1 变化。两个 tag 之间共有 **11 个提交**，已逐一检查 patch；大量变更仅为 Go import 迁移，未将逐提交文件数相加冒充最终 tag 的净文件数。

官方 GitHub 仓库停留在 v0.40.6 并归档；后续代码与发布迁到 `ganigeorgiev.com`。本次使用新官方主机的 tag、提交 patch 与 Release Atom，不能把旧 GitHub v0.41.0 的 404 当成目标版本不存在。

官方证据：

- [官网及当前仓库入口](https://pocketbase.io/)
- [v0.41.0 发布页](https://ganigeorgiev.com/pocketbase/pocketbase/releases?filter=tag%3D%27v0.41.0%27)
- [官方 Release Atom](https://ganigeorgiev.com/meta/pocketbase/pocketbase/releases.atom)
- [最终 tag 列表](https://ganigeorgiev.com/api/git/pocketbase/pocketbase/refs?type=tag)
- [v0.41.0 提交列表](https://ganigeorgiev.com/api/git/pocketbase/pocketbase/commits?rev=refs%2Ftags%2Fv0.41.0&perPage=50)
- [v0.41.0 CHANGELOG](https://ganigeorgiev.com/api/git/pocketbase/pocketbase/content/CHANGELOG.md?rev=refs%2Ftags%2Fv0.41.0)

## 2. 差异与适用性

本轮未发现新增 REST 路由、HTTP 方法、请求字段或响应结构。Go 扩展 API 的变动与 SDK 使用的 HTTP 契约分别处理。

| 官方变化 | 官方依据 | Java/React 映射 |
|---|---|---|
| Go module/import、示例、发布下载和仓库链接迁至新官方主机 | `6171155`，最终 `go.mod` | 更新当前官方项目链接和报告来源；Java Maven 坐标、包名及本仓库 GitHub origin 保持不变，不下载官方 Go 二进制覆盖 Java。Release 迁移说明有机械替换笔误，以最终源码为准。 |
| 所有 Hook Event 增加 `Extra map[string]any`，用于处理器之间共享数据 | `65356a4`，`tools/hook/event.go` | 本系统没有通用 Go Hook/JSVM 事件总线；不把内部 `Extra` 伪装成 REST 字段，也不凭名称增加无人调用的 Java 事件类型。 |
| `ServeEvent.StartBannerFunc` 可替换/禁用启动输出；默认字符树改为 `>` | `98a173c`，`apis/serve.go`、`core/events.go` | Java 启动由 `PocketBaseServer`/`LocalPocketBase` 管理，没有 `ServeEvent` 或 Unicode 字符树。保留现有启动入口与可读输出，不构造伪 Go callback。生命周期/进程回归验证服务启动和资源回收。 |
| `PB_THUMBS_MAX_SOURCE_PIXELS` 默认 `250 << 20` → `100 << 20` | [默认值 patch](https://ganigeorgiev.com/api/git/pocketbase/pocketbase/commits/71da7ef/diff) | 实现缺省/无效值回退 **104857600**，继续保留 Java 单边 8192、源图 16 Mi 像素及解压扫描行 64 MiB 上限；环境变量只能进一步收紧。 |
| SMTP 在 HTML 为空时不创建 HTML multipart | [邮件 patch](https://ganigeorgiev.com/api/git/pocketbase/pocketbase/commits/1cf5d6b/diff) | Java SMTP 空/null HTML 改用 `text/plain`；非空 HTML 保持 `multipart/alternative`，不把仅含空白的 HTML 误当成空字符串。验证实际 SMTP DATA 内容。 |
| sendmail 空 HTML 用 `text/plain`、随机 `Message-ID`、应用自定义 headers | 同上，`tools/mailer/sendmail.go` | Java 没有外部 sendmail 程序调用路径；不为此新增进程执行器或 REST 邮件 header 参数。Java SMTP 当前也未暴露上游通用 mailer 的自定义 header 模型，该既有扩展能力差异单独记录，不宣称已实现。 |
| 小图片预览横向滚动、文件名辅助点击、loader 与仓库图标/链接调整 | `f1feb0d`，官方 UI 源码 | 修复 React 预览 grid 被长文件名撑宽：列可收缩、标题可换行、关闭按钮不压缩。Java 无对应的标题打开按钮和官方 GitHub 运行链接；不新增另一套点击行为。Lucide SVG 不照搬字体 glyph 数值，自己的仓库链接保留。 |
| Goja 更新、移除 `nullObject` workaround；删除官方 dprint WASM/config/npm 依赖；安全说明移位 | `76060cf`、`567a27a`、`cc8999c` | Java 无 Goja/JSVM/dprint，沿用 TypeScript+Vite 和本项目安全渠道。React 源码独立构建，不复制官方 dist/生成类型，不混入 v0.41.1 依赖更新。 |

最终 Go 依赖包括 fexpr `v0.6.0 → v0.7.0`、dbx `v1.12.0 → v1.13.0`、ozzo-validation `v4.3.0 → v4.4.1`、tygoja `v0.1.0 → v0.2.0`，同时迁移 module host；SQLite 最终仍为 `modernc.org/sqlite v1.60.1`。Java 使用自己的过滤/校验实现与 Xerial JDBC，本轮没有证据要求升级无关 Maven 依赖，也不宣称所有迁移依赖内部语义均已逐行证明等价。

## 3. 兼容边界与实施原则

- API 路径、方法、鉴权和已有返回结构保持现有官方兼容行为；`null` 与空字符串规则不可合并。
- 缩略图新默认值高于 Java 现有 16 Mi 源像素硬上限，因此有效解码范围仍由更严格的 Java 限制决定，不宣称获得同等 Go 图像格式支持或性能提升。
- SMTP 只调整 MIME 内容，不改变邮件模板保存、收件人、鉴权、TLS 或 outbox 行为。测试使用本地 SMTP 替身，不代表真实邮件供应商投递成功。
- 官方新主机仅影响官方项目的跳转；本项目 `github.com/jackBaozz/pocketbase-java` 仍是自己的仓库与发布渠道。历史差异报告保留当时来源。
- UI 使用现有主题变量、i18n 和组件；新增文案才新增九语言 key；最终统一构建 Java 内嵌资源。
- 项目版本 `0.4.6 → 0.5.0`，与官方兼容版本 `0.41.0` 分开标识。

## 4. 任务跟踪

| ID | 工作 | 验收 | 状态 |
|---|---|---|---|
| PB410-BASE | 核对新官方主机、Release、最终 tag、11 个提交逐项分析 | 官方 API/patch | 已完成 |
| PB410-THUMBS | 源像素默认值及缺省/无效值回退 | ThumbnailGeneratorTest、既有 PNG/env 边界 | 已完成（10/10） |
| PB410-MAIL | 空 HTML 的 SMTP MIME 修复，保留 HTML 邮件行为 | MIME + 本地真实 SMTP DATA 回归 | 已完成（6/6） |
| PB410-UI | 图片预览与适用链接/交互修复 | UI 测试、构建、浏览器 | 已完成（UI 61/61，布局 8/8，E2E 7/7） |
| PB410-REGRESSION | SQLite 全量、JSONL 关键链路、SDK、生命周期及路由 | Maven/Playwright | 已完成（SQLite 336 通过/1 skip，JSONL 127/127） |
| PB410-NATIVE | GraalVM Native Image 及 SQLite/CORS smoke | Darwin native | 已完成 |
| PB410-VERSION | Java/UI/UA v0.5.0、双语 README、docs 与当前基线 | 版本/语言/资源核对 | 已完成 |
| PB410-DELIVERY | `dev` 提交、推送并核对远端 SHA | staged check、git push、refs | 已完成（代码提交 `3bd2dc8`，远端已核对；本记录另作 docs 提交） |

## 5. 本轮实际验证

以下均为本轮实际运行，不沿用 v0.40.6 的历史通过记录。

| 验证 | 实际命令/方式 | 结果 |
|---|---|---|
| 缩略图与邮件专项 | `mvn -o -Dstorage=sqlite -Dtest=SmtpMailerTest,ThumbnailGeneratorTest -Dmaven.test.redirectTestOutputToFile=true test` | **16/16 通过**；SMTP 包含本地 socket 实际 DATA 传输。初次沙箱运行的 SMTP bind 被拒后，以正常本机权限复跑通过，不将该拒绝视为通过。 |
| SQLite 全量与 JAR | `mvn -o clean package -Dstorage=sqlite -Dmaven.test.redirectTestOutputToFile=true` | **337 项：336 通过、1 Dart skip、0 失败/错误；BUILD SUCCESS**。本机无 Dart SDK，跳过 Dart smoke。生成 `target/pocketbase-java-0.5.0-all.jar`。 |
| JSONL 关键链路 | 下方完整命令 | **127/127 通过**，覆盖邮件/缩略图、文件存储、服务器 API、行为 fixture、备份、SDK、Batch/SSE 和路由。 |
| Admin UI E2E | 上述全量中的 `AdminUiPlaywrightTest` | **7/7 通过**；真实 Chrome 加载本轮嵌入资源。 |
| UI 单元测试 | `npm --prefix ui test` | **9 文件，61/61 通过**。 |
| UI 构建/嵌入资源 | `npm --prefix ui run build`，`jar tf` 核对资源 | **通过**；`index-Df7bZRgm.js` / `index-Dbme0cWB.css` 已生成并包含在 v0.5.0 JAR。保留已有大 chunk 提示，不把构建警告当失败或静默改阈值。 |
| 图片预览布局 | 真实 Chrome，项目实际 CSS + 等价 DOM 夹具 | **8/8 通过**：短/长文件名 × 320/375/768/1280 容器宽度；修复前长文件名在前三档出现 `scrollWidth=813`，修复后无横向溢出且关闭按钮可见。这是布局夹具验证，不是 React 文件上传全链 E2E。 |
| Java 文件格式 | 限定本轮 5 个 Java 文件的 Spotless，完整命令见下方 | **通过**；不格式化其他历史文件。 |
| 语言/文档/资源 | 九语言叶子 key 比较、本地 Markdown 链接、入口资源、`git diff --check` | **通过**；9 语言各 919 个 key 一致，11 个本地文档链接和所有入口资源存在。 |
| 独立代码审查 | 指定基点 diff、staged/unstaged/untracked 全覆盖，仅只读检查 | **未发现确认的可行动问题**；生成资源与源码一致。审查本身未运行测试或修改文件。 |
| Native Image | `mise exec java@25-graalvm -- mvn -o -gs settings.xml -s settings.xml -Pnative package -DskipTests` | **BUILD SUCCESS**；Oracle GraalVM 25.0.3、Darwin arm64，生成 `target/pocketbase-java`，包含本轮新前端资源。此命令跳过测试，测试证据来自上面的单独验收。 |
| Native SQLite/CORS | `PB_NATIVE_SMOKE_PORT=18907 bash scripts/native-sqlite-smoke.sh ./target/pocketbase-java` | **通过**；隔离临时实例的 CRUD、重启后 SQLite 持久化和允许/拒绝来源均验证。测试进程与临时目录由脚本清理。 |

JSONL 完整命令：

```sh
mvn -o -Dstorage=json \
  -Dtest=ThumbnailGeneratorTest,SmtpMailerTest,LocalFileStorageProviderTest,LocalPocketBaseServerTest,BehaviorFixturesTest,BackupConsistencyTest,MultipartRollbackTest,BatchSmokeTest,RealtimeSmokeTest,JsSdkSmokeTest,HttpFileSupportTest,RouteConformanceTest \
  -Dmaven.test.redirectTestOutputToFile=true test
```

本轮 Java 格式门禁：

```sh
mvn -o spotless:check \
  '-DspotlessFiles=.*(SmtpMailer|SmtpMailerTest|ThumbnailGenerator|ThumbnailGeneratorTest|PocketBaseClient)[.]java'
```

## 6. 交付记录

2026-10-29 已将升级代码提交并推送至 `origin/dev`：

- 代码提交：`3bd2dc8d12ff61047657dcda632544391c60f3d8` — `feat(release): align PocketBase v0.41.0 and bump v0.5.0`。
- `git push origin dev` 成功，随后 `git ls-remote origin refs/heads/dev refs/heads/main` 核实远端 dev 为该提交；本地 dev 与 origin/dev 一致，代码交付时工作区干净。
- `main` 与 `origin/main` 仍为 `1ced41d825c30f1ec8cc1c866f36a5df5aaa24e8`；本轮没有合并或推送 main，没有创建 GitHub Release。
- 本交付记录作为独立 docs 提交同步到 dev，避免在代码实际推送前宣称交付已完成；最终分支以 Git 引用为准。

外部 MySQL/PostgreSQL、真实 SMTP/S3、Linux/Windows Native 和远程 CI 未执行；不能把本地替身或配置存在当作外部部署已验证。
