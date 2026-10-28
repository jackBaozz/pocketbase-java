# Changelog

## 0.5.0

- 对齐 PocketBase v0.41.0 的 Java/React 适用增量，逐项差异、排除项和验收见 `docs/RPD-PocketBase-v0.41.0-Compatibility-Development-Plan.md`。
- `PB_THUMBS_MAX_SOURCE_PIXELS` 的缺省及无效值回退改为 `100 << 20`（104857600）；保留 Java 更严格的 PNG 解码上限及原文件回退。
- SMTP 空 HTML 邮件使用 `text/plain`，不再生成空 HTML 的 multipart；非空 HTML 保持原有文本/HTML 两部分。
- 修复小图片预览布局，更新官方项目链接至新主机；本 Java 仓库地址保持不变，管理界面资源由 React 重新构建。
- 同步 Java、前端和 SDK User-Agent 为 v0.5.0。Go module、Hook Extra、ServeEvent 及 sendmail 专用接口不移植为无对应消费者的 Java API。

## 0.4.6

- 对齐 PocketBase v0.40.6 的 Java/React 适用增量；12 个提交、29 个变更文件的分类和验收见 `docs/RPD-PocketBase-v0.40.6-Compatibility-Development-Plan.md`。
- 缩略图生成前读取 PNG 头并验证源图像素数，支持 `PB_THUMBS_MAX_SOURCE_PIXELS`；保留 Java 现有更严格的源图/解压内存限制，超限时沿用原文件响应。
- 加固本地文件存储 SPI：对象 key 不可指向 bucket 根目录，统一验证对象和列表前缀，阻止路径越界与符号链接；正常列表根前缀和合法多点文件名保持可用。
- 暗色主背景对齐 `#1b1b1b`，补齐超级管理员 IP 限制提示的九语言翻译，并重新生成嵌入 Admin UI。
- 同步项目 v0.4.6、SDK User-Agent 和文档。官方 Go 自更新源、Goja/Go 依赖不移植；modernc SQLite 的中间升级已被官方撤回，最终版本未变。

## 0.4.5

- 对齐 PocketBase v0.40.5 的适用增量，官方为 19 个提交、28 个变更文件；Java/React 映射与任务验收见 `docs/RPD-PocketBase-v0.40.5-Compatibility-Development-Plan.md`。
- 新增可配置 CORS 来源（`--origins` / `server.origins` / `PB_ORIGINS`），严格校验子域快速匹配的完整前缀与后缀，保留官方 bounded wildcard fallback；预检支持自定义请求 headers，并在鉴权与限流之前返回。
- React CodeEditor 支持同步/异步补全 provider 和独立 label/value；新输入、关闭及失焦等操作使旧异步结果失效，provider 异常不影响编辑。
- 多行编辑器在公共滚动容器预留稳定 scrollbar gutter，保留高亮层与输入层的共享尺寸。
- 同步 Java、SDK User-Agent、管理前端和嵌入产物版本。Go/JSVM/modernc 驱动专属变化不复制到 Java。

## 0.3.1

**Admin UI 对齐官方 v0.39.9（交互差异修复）:**
- **修复 API 规则空值语义反转（数据破坏性）**：此前 UI 会把空规则统一提交为 `null`，导致任何"所有人可访问"（`""`）的集合被打开并保存一次后，权限被静默改为"仅超管"，且 UI 无法配置公开规则。现已区分 `""` 与 `null` 两态，并实现官方的规则锁定/解锁交互。
- 新增 MFA/OTP 超级用户登录流程（此前 `_superusers` 一旦启用 MFA 将完全无法登录后台）。
- 记录列表与日志列表新增追加式分页（此前仅能看到第一页数据）。
- 搜索框支持普通词自动归一化为过滤表达式（此前输入自然词会直接被后端拒绝），记录页按可见字段、日志页按 level/message/data。
- 新增 relation 字段记录选择器（弹窗搜索、分页、多选约束），列表单元格改为展示关联记录摘要而非裸 ID。
- 字段编辑器补齐 14 种字段类型的专属 Schema 选项（select 选项值、relation 目标集合、file MIME 预设与缩略图尺寸、text 正则、number 整数约束、日期范围、域名白/黑名单互斥等），移除已废弃的字段级 unique 属性，字段类型新增 geoPoint。
- 日志页表格重构为官方结构（Level 标签 / Message / data 摘要标签 / 时间），非 HTTP 日志此前几乎无法辨识；图表统计随过滤条件联动。
- 以自绘确认对话框替换全部原生 `window.confirm`；删除集合与恢复备份改为需手动输入名称确认；SQL 控制台对写入类语句增加执行前确认。
- 集合与记录编辑器增加未保存变更保护，放弃编辑时清理本地草稿。
- 修复记录表格列重复导致的表头与数据错位；统一 `RecordFieldControl` 的重复实现；修复多处失效的 CSS 变量。
- i18n 补齐全部新增文案的九语言翻译，并修复存量未翻译条目与繁体中文中混入的简体字。

**Admin UI 第二轮补齐:**
- 新增**限流规则编辑器**：规则表格（标签自动补全、最大请求数、时间窗口、适用对象）、增删行与自动启停、excludedIPs 编辑，保存前按官方优先级重排规则；此前限流只能通过 Advanced JSON 手改。
- 新增 **API Preview 文档侧栏**：按集合类型动态生成 20 个端点的说明、JS/Dart SDK 代码示例、参数表与响应示例，未启用的端点置灰提示。
- 新增**索引管理**：索引列表与编辑弹窗（列多选、唯一开关、WHERE 表达式、直接编辑 SQL 并实时预览）；此前 UI 完全不处理 `indexes`，无法创建唯一约束。
- 新增**保存前变更确认**：集合保存前逐条列出重命名、字段增删、类型变更、多值转单值与规则变更，破坏性变更以危险样式呈现。
- 新增**代码编辑器**（零新增依赖）：API 规则、SQL 控制台与 view 查询支持语法高亮与光标处自动补全（集合字段、`@request.*`、`@collection.*`）。
- 记录编辑器支持**字段级错误定位**：校验失败时错误显示在对应字段下方并高亮，此前只有一行笼统提示。
- 字段列表支持**拖拽排序**；记录列表支持 **Shift + 点击范围批量选择**（官方 v0.39.8 特性）。
- 备份页在操作进行中轮询状态并禁用控件，操作结束后自动刷新列表。
- 修复设置保存后界面与服务端不同步：后端 PATCH 响应回显提交值，而规则去重/排序与密钥脱敏发生在存储阶段，改为保存后重新拉取。

**Features:**
- 完成了对 PocketBase 官方 API 路由结构的一致性测试套件 (Route manifest conformance tests)。
- 实现了 `POST /api/collections/meta/dry-run-view` 视图预览端点接口。
- 增加了完整的 MFA (Multi-Factor Authentication) 多因子认证流程支持，包含初次验证签发 `mfaId`，与二次凭证处理。
- 对内置基于 JSON 存储（`JsonFileStore`）引擎补充并完善了对 `url`, `date`, `text`, `number`, `bool` 等核心数据字段类型的插入约束和格式验证矩阵。
- 在 `CollectionSchema` 中加入了索引配置，并在修改集合 Schema 后自动裁剪过期数据字段完成模拟 Migration。
- 在本地集成了与官方 JS SDK 的 E2E 烟雾测试，确保了记录 CRUD 与 Auth 行为对接顺畅。
- 加入了 `/ping` 健康检查端点以及服务端启动的控制台横幅。

**Admin UI:**
- 完成集合数据导入、导出与本地差异比对与审查流程。
- 实现了集合 Schema 建立与更新中，面向单个字段展开编辑的细化 UI 表单界面（Field-specific collection editor UI）。
- 将内置的系统参数表单（包含应用配置、邮件发信测试、存储 S3 配置、备份操作管理以及定时 Cron 管理等界面），全方位适配并对齐到官方 PocketBase 相应的用户交互逻辑和外观。
- 将客户端 SDK 类统一迁移到了 `client` 包下。
- 将开发文档均重命名为英文命名风格。

## 0.1.0-SNAPSHOT

**Initial Implementation:**
- 初始化 Maven 项目骨架。
- 添加 PocketBase HTTP Client。
- 添加记录 CRUD、密码认证、集合管理基础 API。
- 添加统一异常模型和认证状态管理。
- 添加 JUnit 测试、GitHub Actions、README 和技术规范文档。
