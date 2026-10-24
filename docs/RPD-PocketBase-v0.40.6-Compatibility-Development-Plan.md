# RPD：PocketBase v0.40.6 差异分析与 Java 兼容升级

> 核对日期：2026-10-24（Asia/Shanghai）\
> 工作分支：`codex/pocketbase-v0.40.6`；Java 基线：`6294f515810806af70c7208a23571dacb24be6a6` / `v0.4.5`\
> 上游：`v0.40.5` (`6f370ea741f33adf889eef02e3c1b884a47aa522`) → `v0.40.6` (`9d281ccaba65f2686eb7b0b614ccda72d1116a1b`)\
> Java 目标版本：`v0.4.6`；状态：Java/React 适用增量已实现，本地 JVM、JSONL、UI 和 Darwin Native 验收完成。

## 1. 官方净差异与适用性

官方 v0.40.6 发布于 **2026-10-23 11:21:45 UTC**，即北京时间 **2026-10-23 19:21:45**。两个最终 tag 之间共有 **12 个提交、29 个文件变更**：UI 13 个、plugins 6 个、tools 4 个，其余为 CI、示例、依赖及发布文档。本轮没有新增 REST 路由、HTTP 方法、请求字段或响应结构。

官方证据：

- [v0.40.6 Release](https://github.com/pocketbase/pocketbase/releases/tag/v0.40.6)
- [v0.40.5...v0.40.6 Compare](https://github.com/pocketbase/pocketbase/compare/v0.40.5...v0.40.6)
- [Compare API：完整提交、文件及 patch](https://api.github.com/repos/pocketbase/pocketbase/compare/v0.40.5...v0.40.6)
- [filesystem 源图像素限制](https://github.com/pocketbase/pocketbase/blob/v0.40.6/tools/filesystem/filesystem.go)及[官方测试](https://github.com/pocketbase/pocketbase/blob/v0.40.6/tools/filesystem/filesystem_test.go)
- [fileblob bucket 路径加固](https://github.com/pocketbase/pocketbase/blob/v0.40.6/tools/filesystem/internal/fileblob/fileblob.go)
- [新更新插件](https://github.com/pocketbase/pocketbase/blob/v0.40.6/plugins/update/update.go)与[最终依赖版本](https://github.com/pocketbase/pocketbase/blob/v0.40.6/go.mod)

| 官方变化 | 关键提交 | Java/React 处理 |
|---|---|---|
| 缩略图解码前检查源图 `W × H`；环境变量 `PB_THUMBS_MAX_SOURCE_PIXELS`，默认 `250 << 20`（262144000） | `2c961da38236` | **实现**：读取固定大小 PNG 头后先检查，再读取完整内容/解压；同名环境变量允许进一步收紧，保留 Java 原有更严格的内存硬上限。 |
| fileblob 对象 key 不得指向 bucket 本身；key/list prefix 均检查 bucket 越界；列表读取 attrs 使用绝对路径 | `af5000c21510` | **实现**：本地 SPI 统一校验，阻止 `.` / `./` 对象操作访问或删除 bucket；list 仍允许列根目录。保留并验证路径与符号链接防护。Java 没有 `.attrs` 元数据文件，stat 已通过绝对 Path 查询，不复制该 helper。 |
| 上传/缩略图解码后的 `Seek` 错误不能忽略 | `2c961da38236` | Java 上传使用 InputStream/Files.copy，不走 Go 的 Seek；PNG 预检和读取的 IOException 沿原调用链传播，不引入虚假的 rewind 操作。 |
| 自更新从 GitHub ghupdate 切换到 BugStage update，示例配置 `https://ganigeorgiev.com` | `b5eb25663210` | **不移植**：Java CLI 目前没有自更新命令，且分发的是独立 Java/JAR/native 产物。不能指向官方 Go 可执行文件覆盖 Java 程序。记录上游后续版本发现方式的变化。 |
| 最低 Go CI 版本 1.27.3、`golang.org/x/*` 安全/维护更新 | `bd1f2259367a` | **Go 专属**：Java 使用 JDK HttpServer/HttpClient、JVM truststore 和 Java 依赖，没有这些 Go 模块。不能据此声明 Java CA/truststore 已修复或批量升级无关 Maven 依赖。 |
| goja/regexp2/pprof 更新；`nullObject` 改为空 map 避免新的 nil map 行为 | `8ad3728b8707`、`abd1254d4d00` | **不适用**：项目没有 Goja/JSVM；Java/Jackson 的 null/对象语义沿用现有兼容测试。 |
| modernc SQLite 曾升到 1.61.0，随后因 bug 回退 | `8ad3728b8707`、`65a66899c38c` | **最终净差异为零**：`go.mod` 最终仍是 1.60.1；Java 使用 Xerial sqlite-jdbc，本轮不升级驱动。 |
| 暗色 surface `#1c1c1c → #1b1b1b`；超级管理员 IP 安全说明语法修正 | `0b1fa3b6f2c8`、`240a056144a9` | **实现**：React 暗色主 surface 对齐 `#1b1b1b`；补齐同等安全说明，9 种语言使用同一 key。使用项目现有 CSS 变量与组件结构。 |
| loader 更换 Remix 字体 glyph、伪元素尺寸/refresh 图标基线微调；halfRotate 不再禁用 pointer events | `240a056144a9`、`5f49104aa433` | Java 使用 Lucide SVG 和 flex，没有这些字体伪元素和 halfRotate 路径。沿用现有实现，不复制不适用的字体补丁。 |
| 官方测试图片由 1×1 改为 5×5，字节长度/Range 期望变化；发布 UI 构建产物 | `3d8f401a0d7a`、`9d281ccaba65` | 测试 fixture 字节数不是 HTTP 契约；Java 使用自己的 PNG fixture 和文件响应回归。嵌入 UI 从本地 React 源码重建，不复制官方 dist。 |

## 2. 缩略图设计与现有边界

实现入口为 [`ThumbnailGenerator`](../src/main/java/io/github/jackbaozz/pocketbase/server/internal/ThumbnailGenerator.java)。

- 支持官方同名环境变量 `PB_THUMBS_MAX_SOURCE_PIXELS`，匹配上游 [cast v1.10.0 的字符串解析](https://github.com/spf13/cast/blob/v1.10.0/number.go)：小数截断、Go base-0 前缀和合法下划线均兼容。例如 `25.9` / `0x19` 为 25，`025` 为八进制 21；不会自动去除空白。缺失、格式无效、非正数或 64 位越界值回退官方默认值 **262144000**。源图像素数等于上限时允许，超过上限时拒绝生成。
- **保留 Java 原有约束**：PNG-only、8-bit RGB/RGBA、非 interlaced、单边最多 8192、源图最多 16777216 像素、解压后扫描行最多 64 MiB、输出最多 16777216 像素。环境变量是额外上限，不能放宽这些约束；实际允许范围由所有限制共同决定。
- 在完整读取源文件及解压前读取固定大小的 PNG IHDR，使用 long 计算 `W × H`，阻止压缩文件或伪造头声明超大位图。解码阶段继续复核尺寸，不依赖一次预检。
- 沿用已有缩略图缓存与 HTTP 回退：有效缓存直接复用；不支持或超限图像不生成缓存，文件 API 返回原文件。拒绝生成不代表拒绝原文件下载。没有新增 REST 参数、设置字段或错误响应结构。
- 不引入 AWT、ImageIO、Go imaging 或 libvips，维持 GraalVM 原生编译路径。

## 3. 本地存储设计

实现入口为 [`LocalFileStorageProvider`](../src/main/java/io/github/jackbaozz/pocketbase/server/internal/storage/LocalFileStorageProvider.java)，直接使用 SPI 时也必须执行校验。

- put/get/stat/delete/getLocalPath 的 key 不可归一化到 bucket 根目录；list 的空前缀、`.`、`./` 仍允许列出 bucket 内容。
- 拒绝绝对路径、Windows drive/反斜杠路径和 `..` 路径段；普通文件名如 `report..v2.txt` 可正常使用。标准化之后再进行 Path 边界比较，避免字符串前缀误判。
- 拒绝 bucket 自身或路径内已有符号链接；递归列举不跟随符号链接。
- 不增加 S3 对象 key 的本地路径限制，不改变备份、文件鉴权或存储格式。已有逐层检查不等价于目录句柄隔离，不宣称防住拥有本地目录写权限者的所有并发路径替换。

## 4. 任务与验收

| ID | 工作 | 状态 |
|---|---|---|
| PB406-BASE | 官方 Release、最终 tag SHA、12 提交/29 文件逐项分析 | 已完成 |
| PB406-THUMBS | 环境变量、PNG 头预检、既有硬上限及边界/真实 env 回归 | 已完成 |
| PB406-STORAGE | 对象根目录保护、路径边界、list prefix、符号链接及正常 CRUD 回归 | 已完成 |
| PB406-UI | 暗色 surface、IP 提示、9 语言 key 一致性、构建与浏览器检查 | 已完成 |
| PB406-REGRESSION | SQLite 全量打包、JSONL 关键链路、官方 SDK、路由和 Admin UI E2E | 已完成（本地适用矩阵） |
| PB406-NATIVE | Native Image 编译及原生 smoke | 已完成（Darwin arm64） |
| PB406-VERSION | Java v0.4.6、SDK UA、UI、嵌入资源、README 与 docs 索引 | 已完成 |

## 5. 本轮实际验证

以下均为 **2026-10-24 本轮实际运行结果**，不沿用 v0.40.5 的历史通过记录。

| 验证项 | 实际执行方式 | 结果 |
|---|---|---|
| SQLite 全量与 JAR | `mvn clean package -Dstorage=sqlite -Dmaven.test.redirectTestOutputToFile=true`；配置格式修复后重跑 `mvn package -Dstorage=sqlite -Dmaven.test.redirectTestOutputToFile=true` | 最终 **331 项：330 通过、1 跳过、0 失败/错误，BUILD SUCCESS**；Dart SDK 未安装，Dart smoke 跳过。生成 `target/pocketbase-java-0.4.6-all.jar`。 |
| 缩略图专项 | 全量内 `ThumbnailGeneratorTest` | **10/10 通过**；包含真实独立 JVM 的 env 边界/进制/小数、无效值回退、不放宽旧上限、超过 2 GiB 的稀疏文件头提前拒绝、重复 IHDR 防绕过和缓存权限。 |
| 本地文件存储专项 | 全量内 `LocalFileStorageProviderTest` | **6/6 通过**；覆盖正常 CRUD、多点文件名、根目录 key、list 根前缀、Unix/Windows 路径和符号链接，包括 bucket 运行期间被替换。 |
| JSONL 关键链路 | 下方专项命令 | **121/121 通过**；覆盖文件/缩略图、服务器 API、官方行为 fixture、备份一致性、SDK、Batch/SSE 和路由。 |
| UI 单元测试 | `npm --prefix ui test` | **9 文件、61/61 通过**。 |
| UI 构建与嵌入资源 | `npm --prefix ui run build`，并检查 JAR 内资源 | **通过**；嵌入入口引用 `index-3-RVQkjC.js` / `index-DGIqKQnU.css`，当前 JAR 含新资源。已有大 chunk 的体积提示保留。 |
| Admin UI E2E | 全量内 `AdminUiPlaywrightTest`，真实 Chrome | **7/7 通过**。 |
| 本轮 UI 实际浏览器检查 | 启动隔离 SQLite 临时服务，创建测试超管，打开暗色 Settings → Superusers | 主面板背景 **rgb(27, 27, 27)**，IP 帮助文案可见，页面实际加载新 hash JS；已检查截图并关闭测试页、停止临时服务。 |
| JVM 真实文件 API | 隔离 JAR 进程，上传 5×5 PNG，设置 `thumbs=["2x2"]`，逐次使用 env `24` / `25` / `0x18` / `25.9` | **4/4 通过**；24/0x18 返回原图且无缓存，25/25.9 生成 2×2 缩略图；原文件请求始终返回 5×5。 |
| Native Image | `mise exec java@25-graalvm -- mvn -gs settings.xml -s settings.xml -Pnative package -DskipTests` | **BUILD SUCCESS**；Oracle GraalVM 25.0.3，Darwin arm64，生成 `target/pocketbase-java`。 |
| 原生 SQLite 与 CORS | `PB_NATIVE_SMOKE_PORT=18907 bash scripts/native-sqlite-smoke.sh ./target/pocketbase-java` | **通过**；CRUD、重启持久化、允许/拒绝来源均验证。 |
| 原生真实文件 API | 与 JVM 相同的隔离 HTTP 测试，改用本轮 native 可执行文件 | **4/4 通过**；env `24` / `25` / `0x18` / `25.9` 的原图回退、2×2 缩略图及缓存行为与 JVM 一致。 |
| 本轮 Java 格式 | 下方 Spotless 命令 | **通过**；只检查本轮 5 个 Java 文件，不改写历史格式问题。 |
| 文档、语言与空白 | 新 RPD/docs 相对链接、嵌入资源引用、9 语言 key、`git diff --check` | **通过**；9 文件均为 **919 个叶子 key**，新文档无断链，资源引用有效。 |
| 外部 DB/远程 CI/Windows | 未执行 | MySQL/PostgreSQL 真实部署、远程 CI、Windows 文件系统和 Linux native 未在本轮运行。S3 回归使用既有测试实现，不能视作真实 AWS 验证。 |

最终代码的首次全量重跑曾在既有认证用例 `authSupportCollectionsCrudMatchesOfficialOwnershipRules` 出现一次 `HTTP/1.1 header parser received no bytes`。该用例单独复查通过，随后完整 331 项重新执行通过，未修改认证或连接代码来绕过问题。保留此瞬态记录，不把失败的一轮算作通过。

JSONL 命令：

```sh
mvn -Dstorage=json \
  -Dtest=ThumbnailGeneratorTest,LocalFileStorageProviderTest,LocalPocketBaseServerTest,BehaviorFixturesTest,BackupConsistencyTest,MultipartRollbackTest,BatchSmokeTest,RealtimeSmokeTest,JsSdkSmokeTest,HttpFileSupportTest,RouteConformanceTest \
  -Dmaven.test.redirectTestOutputToFile=true test
```

本轮 Java 格式检查：

```sh
mvn -o spotless:check \
  '-DspotlessFiles=.*(ThumbnailGenerator|ThumbnailGeneratorTest|LocalFileStorageProvider|LocalFileStorageProviderTest|PocketBaseClient)[.]java'
```

提交与推送按用户后续明确授权执行，交付状态以 Git 提交和远端分支为准；上述本地验收不代表远程 CI 或正式发布。
