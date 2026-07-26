# Changelog

## 0.2.0-SNAPSHOT

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
