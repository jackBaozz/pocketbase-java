# RPD｜PocketBase v0.42.3 差异与 Java 兼容升级

核对与开发日期为 2026-11-20。以 `dev` 工作区已实现的官方 v0.42.0、项目 v0.6.0 为起点，连续核对官方 v0.42.1、v0.42.2 和 v0.42.3。适配阶段版本为 v0.6.1，审查修复后的交付版本为 v0.6.2。v0.42.0 的功能和验证历史见 [v0.42.0 RPD](RPD-PocketBase-v0.42.0-Compatibility-Development-Plan.md)。

## 固定上游版本

官方发布说明与模块源码均已核对。源码比较使用 Go 模块代理下载四个固定版本，逐版解包比对。三个相邻版本分别有 14、9、19 个归档文件变化，v0.42.0 到 v0.42.3 的净差异为 24 个文件，均含官方 UI 构建产物及重命名。发布时间采用北京时间。[官方发布 Feed](https://ganigeorgiev.com/meta/pocketbase/pocketbase/releases.atom) · [v0.42.3 发布页](https://ganigeorgiev.com/pocketbase/pocketbase/releases?filter=tag%3D%27v0.42.3%27)

| 模块版本 | 发布时间 | Go 模块校验和 | ZIP SHA-256 |
|---|---|---|---|
| v0.42.0 | 2026-11-11 13:10:14 | `h1:lDlfF38OcqSNclS918u+X3UWEsIGClqyet/oll7feaQ=` | `48840e4c290fc5230b6d44b0b942b53897cfb2ff4a0468a906ccf1d615e8df50` |
| v0.42.1 | 2026-11-12 00:56:55 | `h1:JQHyDdcgZm9cLTZuLaSB/OHq60d2oLgm2TjUF/om+JI=` | `1d1dff87756aa9013dc600343640bd665d96ac3c1d0f0e81ac6729c95830aeeb` |
| v0.42.2 | 2026-11-12 04:18:54 | `h1:OBrrcVlHD5ZC2ZTFbKr2YkS0mUROGf+TkLbverK7CxQ=` | `0e196e563c7cfeaf4d0b1e16e416aa740f6144a17f31fc66a60da2ed40d4bc2c` |
| v0.42.3 | 2026-11-17 00:19:42 | `h1:4NE29QTH5mugPdgDreJrgJxNpsr+1GVB1wTpmx8lrg4=` | `f010b432aeead21ac86f781a311353242701d6f100644793f88bd95e759d41f9` |

## 差异与任务

上游三次补丁沿用现有 REST 路由和请求字段。可见变化集中在实时订阅是否接受不同 IP、OAuth2 回调结果页及日志级别。

| 任务 | 官方版本与源码 | 本项目落实情况 |
|---|---|---|
| 后台过期会话 | v0.42.1 `ui/src/auth/pageSuperuserLogin.js`、`ui/src/pb.js` | 已有 401 退出并回登录的机制。仅当前会话的非 MFA 401 能清除 token，避免旧请求返回后清除新会话。保留过期 token 后重新登录的浏览器测试。 |
| OAuth2 结果页 | v0.42.2 `ui/src/pb.js` | 将公开认证页和后台会话初始化分开渲染，OAuth2 成功和失败页不会被后台旧 token 的 401 跳转覆盖。回调路径沿用 `/#/auth/oauth2-redirect-success` 和 `/#/auth/oauth2-redirect-failure`，GET 返回 307，POST 返回 303。 |
| 跨 IP 实时订阅 | v0.42.3 `apis/realtime.go`、`apis/record_auth_with_oauth2_redirect.go` | 同一 client 的 SSE 连接与订阅更新可来自不同 IP。保留先匿名后认证的升级，以及认证后身份一致的检查。仅从空订阅首次提交单独的 `@oauth2` 时记录备用 IP。回调匹配连接 IP 或对应订阅 IP，消费后清除该次订阅和备用 IP。 |
| SSE 生命周期 | v0.42.3 发布说明，`apis/realtime.go` 已有 30 分钟上限 | Java 服务端同步将默认最长连接从 2 小时降至 30 分钟。Java SDK 用新 client ID 重订阅已有主题，并在断开期间退避重连。已有全局与单 IP 连接数限制继续生效。 |
| 日志空间回收 | v0.42.3 `core/base.go` | SQLite 的 `__pbLogsCleanup__` 累计删除达到 50,000 条时执行 `VACUUM`，保留 WAL checkpoint。Java 的日志和业务表共用数据库，仍仅在既有维护任务执行时回收。 |
| 日志级别 | v0.42.3 `apis/middlewares.go`、`apis/record_auth_with_oauth2_redirect.go` | 已提交成功响应后的 `write error: client disconnected` 归为正常断连。OAuth2 回调失败在后台 Logs 标为 WARN，保留实际重定向状态和诊断原因，回调参数不进入活动日志。 |
| WebM 预览 | v0.42.3 `ui/src/utils.js` | 本项目的记录文件编辑器已用 `<video>` 预览 `.webm`，服务器已返回 `video/webm`。用浏览器验证现有入口。 |
| JS SDK | v0.42.1 `ui/package.json`、`ui/package-lock.json` | 将本项目官方 SDK 联调固定至 `pocketbase@0.29.0`。React 后台继续使用现有 Fetch 实现。 |
| S3 分片完成响应 | v0.42.3 `tools/filesystem/internal/s3blob/s3/uploader.go` | 官方在 `CompleteMultipartUpload` 的 HTTP 200 中识别 XML `<Error>`。Java 的对象和备份上传均通过 `S3FileStorageProvider.put` 执行单次 PUT，本项目没有该分片完成接口。 |
| Go 依赖与发布产物 | v0.42.3 `go.mod`、`go.sum`，各补丁 `ui/dist` | Go `x/crypto` 更新归上游运行时。本项目沿用 JVM 依赖与 React 构建，并将 v0.6.1 的 UI 产物写入 Java 资源。 |

## 验收

| 项目 | 本地结果 |
|---|---|
| 后端与端到端专项 | 13/13 通过。覆盖不同 IP 的 SSE 订阅、身份一致、OAuth2 回调一次性处理、WARN 日志、Java SDK 重连、JS SDK 0.29.0、已提交响应断连，以及累计 49,999 条与 50,000 条删除的回收边界。 |
| 前端单测与构建 | `npm --prefix ui test` 通过 69 项，`npm --prefix ui run build` 编译成功。九种语言各 921 个一致的 key。新前端与 `target/pocketbase-java-0.6.1-all.jar` 内嵌资源逐字节一致。 |
| SQLite | `mvn -o clean package -Dstorage=sqlite -Dmaven.test.redirectTestOutputToFile=true` 成功。354 项中 353 项通过，Dart SDK 未安装而跳过 1 项。 |
| 管理后台浏览器 | SQLite 全量中的 12 项 Playwright 测试全部通过，包含过期 token 后重新登录、OAuth2 两种结果页、WebM 预览控件和 `video/webm` 响应。WebM 夹具验证预览路径与 MIME，播放仍取决于浏览器解码能力。 |
| JSONL | 同版认证、OAuth2、JS SDK、实时订阅、文件、备份与路由回归中 130 项，128 项通过。2 项读取 SQLite 底层文件的测试按条件跳过。 |
| 原生程序 | `mise exec java@25-graalvm -- mvn -o -gs settings.xml -s settings.xml -Pnative package -DskipTests` 构建成功。macOS arm64 下的 `scripts/native-sqlite-smoke.sh` 验证 SQLite 持久化和 CORS。临时数据目录中的 HTTP 测试验证无效 token、跨 IP SSE、身份一致、OAuth2 回调和 WARN 日志，并实测 49,999 条和 50,000 条日志的 `VACUUM` 边界。 |

测试使用本地 SQLite 和 JSONL 及模拟的外部响应。实际 OAuth2 提供商、外部 MySQL/PostgreSQL、S3、远程 CI 和部署属于本地验收之外的范围。

## 审查修复与复验

- 活动日志使用请求入口已验证的身份，账号修改本人密码后仍保留操作者，后续请求会拒绝失效 token。OAuth2 回调复用路由的路径匹配来移除查询串，覆盖尾斜杠和重复斜杠。
- Java SDK 等待重连后的订阅确认再返回。在 SSE 写入暂停时，服务端仍可更新订阅。OAuth2 订阅的单次消费与 IP 检查在提交事件前完成。
- 后台收到 401 时按当前页面使用的 token 退出，并保留另一标签页已写入的新会话。旧响应若属于当前页面已替换的 token，会被忽略。

修复后 SQLite 全量 356 项中 355 项通过，含 13 项 Playwright 浏览器测试。1 项因本机没有 Dart SDK 跳过。JSONL 专项 20 项和前端单测 69 项全部通过，更新后的资源已重新嵌入 v0.6.1 JAR。GraalVM macOS arm64 构建与运行检查通过，原生程序实测了改密审计、OAuth2 尾斜杠路径脱敏和跨 IP 实时订阅。

## v0.6.2 发布验收

Maven、Admin UI、Java SDK User-Agent 和文档同步为 v0.6.2。前端 69 项测试通过并重新构建。SQLite 全量 356 项中 355 项通过，含 13 项浏览器测试，Dart SDK 用例因本机缺少 Dart 跳过。构建了 `target/pocketbase-java-0.6.2-all.jar`，核对了 JAR 中的前端资源。macOS arm64 的 Native 构建、SQLite 持久化和 CORS 运行检查通过。
