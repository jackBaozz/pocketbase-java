# RPD：PocketBase v0.41.1 差异分析与 Java 兼容升级

> 核对与验收日期：2026-11-05～06（Asia/Shanghai）\
> 工作分支：`dev`；Java 基线：`696759d1ad8781329937303722573fade40b078a` / `v0.5.0`\
> 上游：`v0.41.0` (`f6baa0c43f379de21cd806de97ad6a2b8ca97b83`) → `v0.41.1` (`f70b24e186df98df2e7ebc703f103cb94dc3ca63`)\
> Java 版本：`v0.5.1`；状态：Java/React 适用增量、Admin UI 页脚 `Docs` 链接缺陷修复及本地 JVM、UI、Playwright、独立 JAR 启动验收已完成。

## 1. 官方版本与比较依据

官方 v0.41.1 于 **2026-10-28 06:27:43 UTC** 发布（北京时间 **2026-10-28 14:27:43**）。`v0.41.0` 与 `v0.41.1` 之间共有 **6 个提交**，已逐一检查官方提交 diff 与 Release 说明。

官方证据：

- [官网及当前仓库入口](https://pocketbase.io/)
- [v0.41.1 发布页](https://ganigeorgiev.com/pocketbase/pocketbase/releases?filter=tag%3D%27v0.41.1%27)
- [官方 Release Atom](https://ganigeorgiev.com/meta/pocketbase/pocketbase/releases.atom)
- [最终 tag 列表](https://ganigeorgiev.com/api/git/pocketbase/pocketbase/refs?type=tag)
- [v0.41.1 提交列表](https://ganigeorgiev.com/api/git/pocketbase/pocketbase/commits?rev=refs%2Ftags%2Fv0.41.1&perPage=15)
- [v0.41.1 CHANGELOG](https://ganigeorgiev.com/api/git/pocketbase/pocketbase/content/CHANGELOG.md?rev=refs%2Ftags%2Fv0.41.1)

## 2. 逐提交差异与 Java/React 适用性映射

本轮上游未新增或修改任何 REST API 路由、HTTP 方法、请求参数或响应字段。6 个提交的逐项分析如下：

| 提交 SHA | 官方提交说明与变更内容 | Java/React 适用性与映射结论 |
|---|---|---|
| `8d0e9c64867dab728b2bc1e9e62051fef6b33476` | `added fonts readme and license information`：为官方 Svelte UI 内置字体目录（`ibm-plex-mono`、`ibm-plex-sans`、`remixicon`）补充 `README.md`、`OFL.txt` 与 `LICENSE`（[#8352](https://ganigeorgiev.com/pocketbase/discussions/8352)），并修正官方 `README.md` 中 `examples/base/main.go` 链接路径。 | **不移植字体许可证文件**：本项目 React Admin UI（`ui/`）使用 `lucide-react` SVG 图标与系统字体栈，未内置上述三套字体文件，无需凭空创建空字体目录或许可证。核对本项目文档，未引用旧 `blob/master/examples/base/main.go` 链接。 |
| `b98e5615353c25143061ee5b8c6271c03748ab5e` | `updated npm deps`：清理官方 `ui/package-lock.json` 中残留的 `dprint` 锁条目并更新 Vite dev 依赖锁，写入 `v0.41.1 (WIP)` 变更日志草稿。 | **保持本项目 React 独立依赖树**：本项目 `ui/package.json` 已使用 `vite@8.0.16` 且从未引入 `dprint`，由 `./sh/bump-version.sh 0.5.1` 同步本项目前端包版本。 |
| `bec171fd2de6a2644b235e5936880aa1e47fd2b2` | `updated ui/dist`：将官方 `ui/.env` 中 `PB_VERSION` 设为 `v0.41.1-dev` 并重新打包 `ui/dist`。 | **对应执行**：在完成本轮前端修复与版本同步后，通过 `npm --prefix ui run build` 重新生成 `src/main/resources/pocketbase-admin/` 内嵌前端产物。 |
| `eec11eee9d94cc41fc9965e49940ccf2ff55d7f9` | `workaround readme url linkify error and bumped golang.org/x/net`：将 `go.mod`/`go.sum` 中 `golang.org/x/net` 从 `v0.61.0` 升至 `v0.62.0`，定稿 `CHANGELOG.md` 的 `v0.41.1` 章节，并微调官方 `README.md` 末尾句号空格。 | **Go 专属依赖不适用**：Java 实现基于 JDK 内置 `HttpServer` / `HttpClient`，不依赖 `golang.org/x/net`；同步更新本项目 `CHANGELOG.md` 与双语 `README` 的 `v0.41.1` 基线说明。 |
| `13d5508f4657ab0a2801a6b27900f4316fff6e3d` | `bumped app version`：将官方 `pocketbase.go` 与 `ui/.env` 的版本定稿为 `v0.41.1`，并重新生成 `ui/dist`。 | **对应执行**：将本项目版本从 `0.5.0` 升级至 `0.5.1`（`pom.xml`、`PocketBaseClient` `User-Agent`、`ui/package.json`、`ui/package-lock.json`、Admin UI 页脚 `PocketBase v0.5.1` 及双语 `README` 示例），并重新构建内嵌前端资源。 |
| `f70b24e186df98df2e7ebc703f103cb94dc3ca63` | `updated backport changelog`：在 `CHANGELOG_16_22.md` 记录 `v0.22.58`（回移 `golang.org/x/net` 升级）。 | **不适用**：旧版 Go `v0.22.x` 维护分支文档，Java 项目不维护该历史分支。 |

## 3. 本项目 Admin UI 巡检与缺陷修复

在对当前 React 19 Admin UI（`ui/src/`）进行代码与浏览器巡检时，发现并修复了以下前端缺陷：

- **Records 与 Logs 页脚 `Docs` 链接被 React 19 拦截报错**：
  - **问题根因**：`ui/src/App.tsx` 中记录列表页脚与日志页脚的 `Docs` 链接此前写为 `<a href="javascript:void(0)" onClick={(e) => e.preventDefault()}>`。在 React 19（`react@19.2.7`）下，渲染 `javascript:` 协议的 `href` 会触发 React 安全拦截并在控制台输出错误，同时将 DOM 属性改写为 `href="javascript:throw new Error('React has blocked a javascript: URL as a security precaution.')"`，且点击无法跳转官方文档。
  - **修复方式**：对齐官方 `PB_DOCS_URL`（`https://pocketbase.io/docs`），在 `ui/src/App.tsx` 提取共享组件 `PageFooterRight` 供 Records 与 Logs 页脚复用，将 `Docs` 链接统一设为 `href="https://pocketbase.io/docs" target="_blank" rel="noopener noreferrer"`，移除无效的 `javascript:void(0)`、`e.preventDefault()` 以及未定义的 `footer.docs` 翻译 key。
  - **验证结果**：源码与构建产物不再包含任何 `javascript:void(0)` 链接；Playwright 浏览器实测确认 Records 与 Logs 页脚 `Docs` 链接 `href` 均为 `https://pocketbase.io/docs`，页面加载与路由切换期间零 React `javascript:` 拦截错误。

## 4. 任务跟踪

| ID | 工作 | 验收 | 状态 |
|---|---|---|---|
| PB411-BASE | 核对官方 `v0.41.0 → v0.41.1` 全部 6 个提交与 Release 说明 | 官方 API/diff | 已完成 |
| PB411-UI | 修复 Admin UI 页脚 `Docs` 的 `javascript:void(0)` 缺陷并重新构建内嵌资源 | UI 构建、Playwright E2E、浏览器巡检 | 已完成 |
| PB411-VERSION | 同步项目版本 `0.5.0 → 0.5.1`（Maven、npm、UI 页脚、SDK `User-Agent`、README） | `./sh/bump-version.sh 0.5.1` 及版本一致性核对 | 已完成 |
| PB411-DOCS | 新增 `v0.41.1` RPD，更新 `CHANGELOG.md`、`AGENTS.md`、`docs/README.md`、`README.md`、`README_zh.md` | 文档基线与链接核对 | 已完成 |
| PB411-VERIFY | 运行前端单测、Maven + Playwright 回归、独立 JAR 多次启动与浏览器验证 | 测试与启动日志全部通过 | 已完成 |

## 5. 本轮实际验证

| 验证项 | 实际命令/方式 | 结果 |
|---|---|---|
| 版本与文档一致性 | 检查 `pom.xml`、`ui/package.json`、`ui/package-lock.json`、`ui/src/App.tsx`、`PocketBaseClient.java`、`README.md`、`README_zh.md`、`CHANGELOG.md`、`AGENTS.md`、`docs/README.md` 及静态资源 | **通过**：全部统一为项目版本 `0.5.1` 与官方基线 `v0.41.1`，`javascript:void(0)` 为 0 处。 |
| UI 单元测试与构建 | `npm --prefix ui run build && npm --prefix ui test` | **通过**：9 个测试文件、61/61 用例通过，生成并内嵌 `src/main/resources/pocketbase-admin/` 新产物。 |
| Java 与 Playwright E2E | `mvn -o test -Dstorage=sqlite -Dtest=AdminUiPlaywrightTest,SmtpMailerTest,ThumbnailGeneratorTest` | **通过**：Playwright 浏览器覆盖页脚 `PocketBase v0.5.1`、Records/Logs 页脚 `Docs` 链接及零 `javascript:` 拦截错误，连同邮件与缩略图回归全部通过。 |
| 可执行 JAR 多次启动 | `mvn -o package -DskipTests` 后连续多次以独立临时目录启动 `target/pocketbase-java-0.5.1-all.jar`，请求 `/api/health` 与 `/_/` | **通过**：每次运行 `/api/health` 与 `/_/` 均返回 HTTP 200 并指向最新内嵌前端资源。 |
