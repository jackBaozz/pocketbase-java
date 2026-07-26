# Admin UI 交互差异分析（对照官方 PocketBase v0.39.9）

> 生成于 2026-07-26。方法：将官方 v0.39.9 `ui/src`（无框架原生 JS + 自定义元素，约 34,700 行 / 185 文件）与本项目 `UI/src`（React，约 6,700 行 / 9 文件，主体为 App.tsx 单体 5,552 行）逐文件对照，只关注交互行为与功能逻辑，忽略纯视觉样式。
>
> 总量：**约 102 项交互完全缺失、53 项行为不一致**。其中 4 项属于"数据破坏 / 功能断裂"级，应最优先处理。

---

> **修复进度（2026-07-26 当日）**：P0 全部修复并端到端验证，P1 主干与 P2 核心已落地，详见文末[修复记录](#八修复记录2026-07-26)。P0-2 经核查为误报（后端已有防护）。

## 〇、危险级问题（P0）

### P0-1 空规则语义反转（数据破坏性，两组独立核对确认）— ✅ 已修复
- 官方语义：规则为空串 `""` = **所有人可访问**；`null` = **仅超管**。UI 用"锁定/解锁"交互显式区分（`base/ruleField.js`：null 时显示锁定遮罩 "Unlock and set custom rule"，"Set superusers only" 按钮加锁）。
- 本项目：加载时 `collection?.listRule ?? ""` 把 null 显示为空；提交时 `nullableRule` 把空串一律转成 `null`（App.tsx:3917-3921、4940）。
- **后果**：UI 永远无法配置"公开"规则；且任何规则原本为 `""` 的公开集合，在本项目 UI 打开并保存一次，就被**静默改成仅超管**，前台应用直接 403。
- 修复方向：区分 `""`/`null` 双态 + 实现锁定/解锁交互。

### P0-2 Settings 整包 PATCH 回写掩码 secret — ❌ 误报（后端已有防护）
- 分析时只看了前端，未核查后端。实际上 `SettingsRepository.deepMerge`（第 84-86 行）与 `JsonFileStore.deepMerge`（第 4694 行）都会跳过值为 `******` 的字段，掩码值不会覆盖真实凭据。
- 前端整包 PATCH 仍不够干净（提交了大量未修改字段），但**不存在凭据损坏风险**，降级为低优先级优化项。

### P0-3 MFA/OTP 登录流程缺失（功能断裂）— ✅ 已修复
- 官方：密码登录返回 401+mfaId 后自动进入 OTP 请求/验证分步表单（步骤计数、自动预填邮箱发送 OTP、429 保留 lastOTPId、Request another OTP）（`auth/pageSuperuserLogin.js`）。
- 本项目：无此流程。一旦 `_superusers` 集合开启 MFA/OTP，**管理后台完全无法登录**。

### P0-4 记录列表无分页（数据不可见）— ✅ 已修复
- 官方：每页 40 条追加式 Load more（skipTotal）。
- 本项目：`refreshRecords` 永远只请求第 1 页，无任何翻页控件。**数据超过一页即不可见也不可操作**（per-page 选择器 25/50/100/200 只是缓解）。

---

## 一、记录模块（缺失 24 项 / 不一致 17 项）

### 缺失（按重要程度排序）
1. **分页加载（Load more）** — 见 P0-4（recordsList.js）
2. **Relation 记录选择器** — 弹窗选择器：滚动加载（50/页）、搜索、预选分批回填保序、maxSelect 超限挤出旧项、已选拖拽排序、行内编辑/新建关联记录；本项目 relation 字段是手输 id 的文本框（recordsPickerModal.js、relation/input.js）
3. **搜索栏智能化** — 普通词自动归一化为 `字段~"词"||…` 跨字段过滤、字段名自动补全、按集合持久化搜索历史；本项目裸 filter 输入，普通词直接被后端拒绝（recordsSearchbar.js、utils.normalizeSearchFilter）
4. **Shift+Click 范围批量选择**（v0.39.8 新增，含 label 透传 shiftKey workaround）
5. **文件字段完整编辑** — 拖拽上传、已有文件"标记删除+恢复"、新旧混排拖拽排序、缩略图；本项目原生 file input 且再次选择整体覆盖、已有文件无法单独删除（file/input.js）
6. **富文本 editor（TinyMCE）+ 文件插入选择器** — recordFilePickerModal：集合下拉记忆、缩略图网格、图片 thumb 尺寸选择；本项目纯 textarea
7. **auth 记录操作集** — Impersonate（时长、token 复制、SDK 示例）、发送验证邮件、发送密码重置邮件、重置已签发 token（recordImpersonateModal.js）
8. **auth 专用字段控件** — email 的 Public 切换（emailVisibility）、change password 开关+随机密码生成、verified 二次确认；本项目过滤 system 字段导致 email/password/verified 无表单入口
9. **关闭前未保存变更确认**（含清草稿）
10. **记录 Duplicate** — 清 id/file/autodate 后以新记录打开
11. **URL 状态同步与深链** — filter/sort/record id 入 hash query，`?record=id` 直开编辑弹窗，记忆最后活跃集合
12. **relation 单元格摘要与懒加载 expand** — eager expand + presentable 摘要 + 批量懒加载补全 + 悬浮 JSON 预览 + 深链编辑；本项目显示裸 id
13. **view 集合记录预览弹窗** — 只读 preview（Copy/Download JSON）；本项目仍开编辑弹窗且 Delete 按钮照常显示（必失败）
14. **记录 JSON 导出/复制** — 批量下载 JSON、编辑弹窗 Copy JSON（脱敏 password/tokenKey/expand）
15. **Ctrl+S 快速保存 + 编辑态 Save and continue / Reset form 下拉**
16. **geoPoint 字段控件** — 经纬度输入 + Leaflet 地图选点；本项目显示 "[object Object]" 基本不可用
17. **文件预览弹窗** — 图片/音频/视频/PDF 预览、其它下载、缩略图并发限流（10）
18. **列表变化提示（suggestReset）** — 保存改动影响当前 filter/排序时 Refresh 按钮变警示
19. **API preview 按钮**（记录页头部入口）
20. **新建时自定义主键 id 与 autogenerate 占位**
21. **隐藏字段模糊显示**（hidden-field-blur，列偏好打开则解除）
22. **Auth providers 真实外链管理** — 拉 `_externalAuths` 列绑定并可 Unlink；本项目引用不存在的字段、无解绑
23. **hideControls 表单锁定**（Unlock to save）
24. **record:save/record:delete 跨组件事件同步**（列表原地合并/移除）

### 行为不一致（摘要）
- **排序**：官方点列头切换（relation 列自动映射 presentable 子字段，默认 `-@rowid`）；本项目双下拉 + Apply，默认 `-created`
- **筛选提交**：官方输入变化才浮现 Search + 常驻 Clear；本项目常驻 Apply、无 Clear
- **批量删除**：官方分批 100 并发、view 集合隐藏入口；本项目全量 Promise.all、view 集合仍显示
- **行打开**：官方整行可点（键盘可达）；本项目行尾 Edit 图标
- **编辑数据新鲜度**：官方打开弹窗先 getOne 拉最新；本项目直接用列表旧对象
- **保存后列表**：官方原地合并保持滚动位置；本项目整表重拉回第 1 页
- **字段级错误**：官方 400 映射到具体字段内联展示；本项目一条整体错误
- **number 前导零**（v0.39.8）：官方 oninput 跳过 "0X" 中间态 + onchange 归一化；本项目 `Number()` 直转无保护
- **json 字段**：官方 CodeMirror + 有效性角标 + 无效拦截提交；本项目每击键 parse、失败把原始字符串提交（可能存成字符串）
- **date**：官方带秒 + 时区名提示；本项目截断到分钟；autodate 官方不进表单，本项目渲染禁用输入框
- **多选 select**：官方下拉多选强制 maxSelect；本项目复选框矩阵不限制
- **草稿细节**：官方仅 hasChanges 写草稿、回退即删、放弃时清；本项目变更即写、回退/关闭不清
- **列显隐**：官方按字段 id 存、遵循 field.hidden、排除主键；本项目按名存、hidden 字段完全不可选
- **文件单元格**：官方 100x100 缩略图（限流）；本项目文件名 pill
- **_superusers**：官方隐藏 verified 列；本项目未排除
- **总数统计**：官方独立轻量 count 查询并随事件增减；本项目读 totalItems

---

## 二、集合与 Schema 模块（缺失 25 项 / 不一致 11 项）

### 缺失（按重要程度排序）
1. **字段类型专属 Schema 选项编辑器（几乎全部）** — text 的 min/max/pattern/autogeneratePattern、number 的 min/max/onlyInt、select 的 choices 弹层（换行分隔+去重）与 Single/Multiple、relation 的目标集合下拉（含内联新建集合、已有字段禁改目标）/cascadeDelete/min/maxSelect、file 的 mimeTypes 预设/thumbs/protected、date 的 min/max、autodate 三选、email/url 的 onlyDomains/exceptDomains 互斥、editor 的 convertURLs、json/editor 的 maxSize、所有类型 Help text（fields/*/settings.js）。本项目 FieldEditor 仅 name/type/required/unique/hidden/presentable + file maxSize；**select 配不了选项、relation 选不了目标集合**，只能手写 Fields JSON
2. **索引与唯一约束管理** — 索引列表（Unique 标识、拖拽）+ 编辑弹窗（Unique 开关、SQL 编辑、字段 preset）+ 重命名自动同步索引表名/列名；本项目对 `indexes` 零处理
3. **保存前变更确认对话框（diff）** — 集合/字段重命名 old→new、删除字段"数据将永久删除"警告、多转单"仅保留最后一项"、规则 diff、OIDC host 冲突检测
4. **API 规则锁定/解锁交互** — 见 P0-1
5. **API 规则自动补全** — 递归集合字段（relation 4 层）、back-relation、@request.*（:isset/:changed）、@collection.* 补全
6. **字段删除标记/恢复机制** — 已有字段删除仅标记 @toDelete（灰显+恢复），保存时才真删；本项目立即移除且无确认
7. **auth 集合 authRule / manageRule** 折叠区
8. **View 集合 SQL 试运行与预览** — 停顿 200ms 调 dryRunViewQuery、实时有效性、Sample output、SQL 关键字补全
9. **集合 Truncate**（输入集合名确认）
10. **集合 Duplicate**（name_duplicate、清 id、索引重命名）
11. **未保存关闭保护 / Ctrl+S / Save and continue / Reset form / 无变更禁用保存**
12. **Mail templates 编辑** — 5 个模板 accordion（Verification/Password reset/Confirm email change/OTP/Login alert）+ placeholder 补全 + 测试发信入口
13. **Token options** — 各类 token 时长 + "Invalidate all previously issued tokens"（secret 轮换）
14. **authAlert（新登录邮件提醒）开关**
15. **字段拖拽排序**（字段列表与索引列表）
16. **字段 Duplicate**（xxx_copy）
17. **系统字段保护联动** — id/password/tokenKey 等被改自动还原 + toast；hidden 与 presentable 互斥
18. **OAuth2 provider 专属配置** — Apple（Team/Key ID/Private key/一键生成 secret）、Microsoft（Azure 端点）、OIDC（User info URL/ID Token 来源/PKCE）、Lark 等；本项目只有一套通用字段
19. **Collections overview 总览弹窗**（ERD 关系图 + Rules 总览表）
20. **新建集合 scaffold 与类型切换合并**（服务端 scaffold 初始化系统字段）；本项目硬编码默认字段
21. **集合/字段名 slugify 自动规范化与重命名联动**（同步索引、identityFields）
22. **删除/清空需输入集合名确认**；本项目 window.confirm
23. **标签页错误徽标与字段级错误定位**
24. **侧栏细节** — OAuth2 无 provider 警告图标、中键新标签打开、System 分组默认折叠
25. **geoPoint 字段类型**（本项目类型列表无 geoPoint，反而暴露了官方刻意排除的 password 类型）

### 行为不一致（摘要）
- **空规则语义**：见 P0-1
- **字段级 unique 复选框**：官方 v0.23+ 已移除（唯一性走索引）；本项目仍提供并提交（服务端不识别，误导）
- **identityFields**：官方动态生成（email + 有单列唯一索引的字段）且至少选一；本项目硬编码 email/username、可全不选
- **OAuth2 增删**：官方 picker→配置→提交才加入、首个自动启用、清空自动禁用；本项目 checkbox 即时增删、无联动；mappedFields 候选不做类型过滤
- **新字段插入**：官方插在最后一个非 autodate 字段前、重名自动 +2 后缀、自动聚焦；本项目一律 append
- **字段编辑模型**：官方 accordion 展开即改；本项目两段式 Save/Cancel + 底部 Fields JSON 双向同步（JSON 改坏即整列表不可用）
- **auth 数值默认**：OTP length 官方 8 / 本项目 6；MFA duration 官方 600 / 本项目 1800；OTP duration min 官方 1 / 本项目 60
- **保存定位**：官方 update 用 collection.id（重命名安全）；本项目 PATCH 用旧 name
- **规则帮助**：官方动态生成完整 identifiers + 示例；本项目静态 chips
- **view 保存 fields**：官方保留推导字段；本项目强制 `fields: []`

---

## 三、Settings 与 Logs 模块（缺失 26 项 / 不一致 12 项）

### 缺失（按重要程度排序）
1. **限流规则编辑器** — 规则表格：label 按集合动态补全、Max requests/Interval/受众、增删行自动启停限流、保存前按优先级排序、格式说明弹窗、excludedIPs；本项目只显示条数无编辑
2. **日志 Message/Level 列与 data 摘要** — 官方主列 level+message+data 键值标签；本项目表头是 Method/Status/URL 请求专用列，**非 HTTP 日志几乎空行**
3. **日志图表缩放/平移/悬浮**（uPlot 框选缩放联动列表、双击重置、间隙补零）；本项目纯静态柱条
4. **备份恢复安全确认** — 手输备份文件名 + 危险警示 + 提交中禁关 + 成功后自动整页重载；本项目一个 window.confirm
5. **canBackup 轮询** — 每 3.5s 轮询 health，备份/恢复期间按钮显示"操作进行中"
6. **表单脏检查 + Cancel 还原**（四个设置页 hasChanges，无改动禁用 Save）
7. **Superuser IPs 防锁死** — 保存前弹窗警告 + 保存后 authRefresh 自验 + "你的 IP (you)" 一键填入
8. **Trusted proxy 实时诊断** — Resolved IP / Detected header、不匹配警告、建议 chip 一键填入
9. **SQL 危险语句确认**（ALTER/INSERT/CREATE/UPDATE/DELETE/DROP/DETACH/PRAGMA）
10. **SQL 查询历史**（localStorage 10 条、去重、回填、单删）
11. **SQL 结果表格能力** — 列头客户端排序、250 行 + Load remaining、NULL 标记、Export CSV、耗时
12. **导入 ID 替换建议**（同名不同 ID 时一键替换，连带 relation collectionId、索引名）
13. **导入 review side-by-side diff**（Old/New 双列、字段级 Added/Deleted/Changed、删字段二次确认）；本项目仅计数汇总
14. **导入 merge 模式**开关
15. **日志批量选择与导出**（全选/shift 范围/下载 JSON）
16. **日志 Load older 分页**（50/页、loadStartDate 防推挤）；本项目固定 100 条
17. **"Include requests by superusers" 开关**（默认过滤超管请求，持久化+入 URL）
18. **日志 URL 状态同步与深链**（filter/logId/superuserRequests）
19. **日志搜索归一化 + 历史 + 补全**（普通词转 level/message/data 过滤；stats 随 filter 变化）
20. **日志详情结构化视图**（逐行+复制按钮、着色标签、Copy/Download JSON）；本项目原始 JSON 文本
21. **测试邮件 Auth collection 选择 + 收件人记忆 + 15s 超时**
22. **S3 自动连通性测试标签**（防抖自动测试、内嵌状态、错误 tooltip）；本项目手动按钮+toast
23. **Accent color 校验与实时预览**（太浅拒绝、全局实时生效、离开还原）；本项目存库后从不应用
24. **空结果快捷操作**（Reset zoom / Clear search）
25. **创建备份后台继续提示**（1.5s 自动关窗 + toast）
26. **导出 shift 范围选择**

### 行为不一致（摘要）
- **保存提交内容**：见 P0-2
- **Logs 设置位置**：官方日志页齿轮弹窗（存后自动刷新）；本项目在 Application 设置页、无联动
- **测试邮件时机**：官方仅无未保存改动时可测（确保测已保存配置）；本项目始终可点
- **恢复完成**：官方整页 reload；本项目仅刷新部分状态（settings 陈旧）
- **日志行打开**：官方整行可点（键盘可达）；本项目行尾小箭头
- **Cron 触发**：官方仅禁用运行中那一个、保持 API 顺序；本项目全部禁用、按 id 重排
- **Batch 联动**：官方未启用时禁用数字输入；本项目始终可编辑
- **日志 Total 口径**：官方 stats 汇总随 filter；本项目 totalItems
- **导入变更判定**：官方感知 deleteMissing；本项目整对象全等（会误报 Changed）

---

## 四、全局基础交互 / 认证 / API Preview（缺失 27 项 / 不一致 13 项）

### 缺失（按重要程度排序）
1. **API Preview 文档侧栏（整个 apiPreview/ 目录 19 文件）** — 每集合的端点导航（List/View/Create/Update/Delete/Realtime/Batch + auth 各流程）、未启用端点置灰、JS/Dart SDK 示例（记忆上次 SDK）、参数表、200/400/403 响应示例
2. **MFA/OTP 登录流程** — 见 P0-3
3. **表单错误全局管线 + accordion 自动展开** — response.data 按 name 映射到具体输入框、再输入自动清除、invalid 事件自动展开所在 details
4. **模态未保存更改确认**（onbeforeclose + 保存中禁关）
5. **代码编辑器** — Prism 高亮、光标处补全下拉（↑↓/Enter/Tab/ESC）、Ctrl+L 选行/Ctrl+D 选词、Tab 缩进；本项目 SQL/filter/规则/JSON 全是普通 textarea
6. **规则字段锁定/解锁**（见 P0-1 的交互半边）
7. **搜索历史**（15 条、下拉回选、中键新标签打开）
8. **拖拽排序通用件**（sortable.js）
9. **自定义 tooltip 体系**（8 方位、视口修正）；本项目仅原生 title
10. **模态层叠与键盘/遮罩关闭** — ESC 关最顶层、遮罩 mousedown+mouseup 双判防拖选误关、popstate 强制关闭、焦点恢复；本项目 Modal 只有右上角 X
11. **密码显示/隐藏切换**（所有密码框眼睛按钮）
12. **登录页忘记密码入口**（本项目页面存在但登录面板无链接，需手输 URL）
13. **Installer 从备份初始化**（上传 zip + 风险确认 + 自动 restore）
14. **confirm-verification 自动确认与重发**（本项目需手动点、无重发）
15. **OAuth2 重定向落地页**（自动 window.close）
16. **页面标题管理**（document.title 随路由/AppName）
17. **后台 token 刷新**（启动 authRefresh，仅 401/403 清登录态）
18. **多标签页同步**（BroadcastChannel 同步 collections/settings/配色）
19. **侧栏宽度拖拽**（dragline + 持久化）
20. **colorPicker**（色板 + hex + 对比字色）
21. **codeBlock 局部全选**（块内 Ctrl+A）
22. **顶栏用户菜单**（显示当前超管邮箱 + Manage superusers 跳转）；本项目不显示登录身份
23. **导航项自动滚动定位**
24. **credits 页脚**（Docs + 版本号）
25. **插件扩展机制**（/_/extensions.js + app.routes 注册 API）
26. **demo 凭据预填**（?demoEmail/demoPassword）
27. **favicon/accent color/theme-color 动态生效 + hideControls 模式**

### 行为不一致（摘要）
- **确认对话框**：官方自绘 confirm（文案定制、Promise 回调、按钮 loading）；本项目 6 处 window.confirm（可被浏览器拦截）
- **Toast**：官方三类、悬停暂停、堆叠、按 key 去重、可关闭；本项目单条顶替、固定 3200ms、无关闭
- **规则空值**：见 P0-1
- **日期展示**：官方 formattedDate 悬浮本地时间+时区（full 模式双显 UTC）；本项目记录表格 UTC 直出
- **复制反馈**：官方原位图标变对勾 500ms、遍布 id/token；本项目仅个别处、弹全局 toast
- **Installer token**：官方解析校验类型/过期并携带授权；本项目忽略 token 匿名调 bootstrap
- **confirm-password-reset/email-change**：官方从 token 解析 collectionId 与邮箱、独立 client 不影响登录态；本项目要求手填 collection 名（默认 users）
- **401 登出**：官方错误 toast→取消在途请求→清 store→关模态→跳 #/login；本项目清空状态+清 hash（丢路由位置）、无提示
- **路由 URL 形态**：官方 `#/collections?collection=xxx`（filter/sort 入 URL）；本项目路径式 `#/collections/{name}/records`（互不兼容，刷新丢状态）；官方 guestOnly 已登录自动跳回
- **下拉选择**：官方 select 支持多选/必选保护/搜索阈值 6；本项目仅单选/阈值 7（键盘导航对齐；Theme/Language 两个自定菜单无键盘导航）
- **提交防重入**：官方全部异步按钮 loading+disabled；本项目 CollectionModal Save 无防重入（快速双击发两次）
- **搜索栏**：官方变更才浮现 Search + 常驻 Clear + 高亮补全；本项目常驻 Apply
- **后退与模态**：官方 popstate 强制关模态；本项目模态状态残留

---

## 五、本项目多出的能力（官方没有，建议保留）

- **i18n 多语言**：9 种语言 + 顶栏切换（官方完全无 i18n）
- **健康状态指示**：顶栏 StatusPill（online/offline/syncing）
- **顶栏一键全局刷新 / 一键登出**
- **记录 JSON 双向编辑视图**（表单控件与 JSON 文本互同步）
- **登录/初始化一体面板**（setupRequired 同表单建首个超管+自动登录）
- **集合置顶分组、列显隐持久化**（与官方实现路线不同但能力在）
- **per-page 选择器**（25/50/100/200）

## 六、基本对齐的部分（摘要）

三类集合与标签页组合、system 集合保护、侧栏搜索/Pinned 分组/compact 模式、集合导入导出快照流程、OTP/MFA/OAuth2 基础开关、备份列表与创建/自动备份 cron 表单、Crons 列表与手动触发、SMTP/S3 字段集与掩码占位、主题三档、记录草稿机制（细节有差）、空列表状态、批量选择 bulkbar、删除确认、hash 路由 settings 子路径同名、auth action 路由路径、DropdownSelect 单选键盘导航、登录防重入、OAuth2 测试闭环。

---

## 七、修复优先级建议

| 波次 | 内容 | 性质 |
| --- | --- | --- |
| **P0 立即** | 空规则 ""/null 语义 + 规则锁定 UI；settings 按页提交并剔除掩码 secret；MFA/OTP 登录流程；记录列表分页 | 数据破坏 / 功能断裂 |
| **P1 核心体验** | relation 选择器 + 单元格 expand 摘要；字段专属 Schema 选项编辑器（先 select choices / relation 目标集合）；索引管理；搜索词归一化（记录页+日志页）+ 搜索历史；auth 记录操作集（email/password/verified 表单、impersonate）；文件字段编辑（删除/恢复/排序）；限流规则编辑器；日志 Message/Level 列 | 日常主链路 |
| **P2 安全网** | 未保存关闭确认（记录/集合/设置脏检查）；保存前 diff 确认 + 字段删除标记恢复；备份恢复强确认 + canBackup 轮询；SQL 危险语句确认 + 结果分页；Superuser IPs 防锁死 | 防误操作 |
| **P3 质感** | 字段级错误定位 + accordion 自动展开；自绘 confirm / toast 升级 / tooltip；模态 ESC/遮罩/popstate；代码编辑器（规则/SQL/JSON 高亮补全）；拖拽排序；Shift+Click 范围选择与 number 前导零（v0.39.8 两项）；API Preview 文档侧栏；日期本地化 tooltip；URL 状态同步 | 手感与开发者体验 |

> API Preview 虽归入 P3 实现，但对开发者价值高（官方影响评估中列第 2），若目标用户以开发者为主可提前至 P1。

---

## 八、修复记录（2026-07-26）

### 已修复并验证

| # | 项目 | 实现要点 | 验证方式 |
| --- | --- | --- | --- |
| P0-1 | **空规则语义** | 规则 state 改为 `string \| null`，`nullableRule` 换成保语义的 `normalizeRule`；新增官方式锁定/解锁交互（锁定态显示"(Superusers only)"与解锁按钮，解锁记忆原值） | 浏览器实测：`""` 显示可编辑框、`null` 显示锁定态；保存往返后经 API 核验两者各自保持不变 |
| P0-3 | **MFA/OTP 登录** | 新增 `ApiRequestError`（携带 status/data/mfaId），401+mfaId 时自动请求 OTP 并进入验证分步表单，支持重发与取消；修正 401 在未登录态误触发全局登出 | 类型检查 + 后端契约核对（`request-otp` → `{otpId}`，`auth-with-otp`） |
| P0-4 | **记录分页** | `refreshRecords` 支持 page 参数与追加加载，底部"加载更多（剩余 N 条）"，footer 改为"已显示 X / Y" | 浏览器实测 60 条数据：50 → 60 行，按钮消失 |
| P1 | **搜索词归一化** | 移植官方 `normalizeSearchFilter`：普通词自动转跨字段 `~` 表达式，已含操作符则透传；记录页按可见字段、日志页按 level/message/data | 实测输入"number 55"命中 1 条、日志输入"oauth2"命中 6 条，均无报错 |
| P1 | **relation 选择器** | 新增 `RelationPicker` 组件：弹窗选择、搜索、分页加载、maxSelect 超限挤出、已选 chip 显示摘要可移除；列表单元格改用 eager expand + `recordSummary` 显示摘要 | 实测搜索→选中→chip 显示"Post number 42"→JSON 写入 id→列表显示摘要徽标 |
| P1 | **字段专属选项编辑器** | 14 种字段类型的完整 Schema 选项（select choices、relation 目标集合、file mimeTypes 预设与 thumbs、text pattern、number onlyInt、date min/max、autodate 三选、email/url 域名互斥等）；移除废弃的字段级 unique；hidden ⟂ presentable 互斥；类型列表加 geoPoint、去 password | 类型检查 + 构建 |
| P1 | **日志页信息结构** | 表格改为官方的 Level / Message / data 摘要 chips / Created；非请求日志回退展示前若干 data 键；error/details 着色置尾；新增"Load older"分页（50/页），图表统计随 filter 联动 | 浏览器实测：INFO 标签 + method/status/execTime/auth/userIP chips；分页 50 → 100 |
| P2 | **自绘确认对话框** | 新增 `ConfirmDialog`（Promise 式 API），替换全部 6 处 `window.confirm`；危险操作红色样式、ESC/遮罩关闭；**删除集合与恢复备份需手输名称才解锁确认按钮** | 实测：错误文本按钮禁用、正确文本启用；删除记录确认框正常 |
| P2 | **未保存变更保护** | 集合编辑器按全量状态快照判断 `hasChanges`，记录编辑器复用既有 `changed`；关闭前弹确认，放弃时清理草稿（修复官方有而本项目缺的清理步骤） | Playwright 测试已更新覆盖此路径 |
| P2 | **SQL 危险语句拦截** | ALTER/INSERT/CREATE/UPDATE/DELETE/DROP/DETACH/PRAGMA/REPLACE 开头的查询执行前二次确认 | 类型检查 + 构建 |

### 顺带修复的既有缺陷（非本轮分析项）

- **记录表格列重复错位**：`recordColumns` 无条件补 `id`/`created`/`updated`，但它们在 PB v0.23+ 本就是 schema 字段，导致表头比数据行多出 2-3 列且整体错位。已改为去重。
- **`RecordFieldControl` 死代码**：`components/RecordFieldControl.tsx` 从未被 import，实际生效的是 App.tsx 内的同名副本。已合并两者改进（更完善的日期/JSON/select 处理）并统一为组件文件，删除 App.tsx 内重复定义（约 190 行）。
- **CSS 变量失效**：9 处引用了本项目不存在的 `--baseColor`/`--txtHintColor`（照搬官方命名），另有既有的 `--bg-body`/`--border-color`/`--lgFontSize`/`--lgLineHeight` 未定义（导致字段编辑器编辑态背景透明）。已全部替换为项目实际变量。
- **i18n**：新增约 97 个 key × 9 种语言；并修复存量未翻译条目（`confirm.*` 等在 ja/es/pt/fr/ru 曾是英文原文，会造成中英混排）与 zh_TW 混入的简体字。

### 第二轮修复（同日，承接上表）

| 项目 | 实现要点 | 验证方式 |
| --- | --- | --- |
| **限流规则编辑器** | 规则表格（label 带按集合动态生成的 datalist 补全、maxRequests/interval/audience、增删行）、首条规则自动启用与删空自动停用、excludedIPs 编辑；保存前按官方 `sortRules` 优先级重排（tag > 精确路径 > 长前缀 > 短前缀） | 实测新增规则后保存，UI 自动同步为 `*:auth, *:create, /api/batch, /api/collections/, /api/` 的正确优先级顺序 |
| **API Preview 文档侧栏** | 新增 `ApiPreview` 组件：20 个端点（按集合类型动态生成，未启用的置灰并提示）、JS/Dart SDK 示例（选择记忆 localStorage）、参数表、200/4xx 响应示例、代码块复制 | 记录页头部入口打开，实测导航与 SDK 切换、路径使用实际集合名与 baseUrl |
| **索引管理** | 新增 `IndexManager` 组件：索引列表（名称/列/UNIQUE 标记）、编辑弹窗（表单模式的列 chip 多选 + Unique + WHERE，或直接编辑 SQL，带实时预览）、移植官方 `parseIndex`/`buildIndex` | 实测读取既有索引、且后端确认唯一索引真实生效（重复值返回 `validation_not_unique`） |
| **保存前变更 diff 确认** | 集合保存前比对原始 schema，逐条列出集合/字段重命名、字段增删、类型变更、多值转单值、规则变更（含 `null`/`""`/表达式三态描述）；含破坏性变更时对话框转为危险样式 | 实测删字段 + 重命名后弹出红色确认框，逐条列明"数据将被永久删除" |
| **代码编辑器** | 新增 `CodeEditor` 组件（零新增依赖，textarea + 高亮层方案）：pbrule/sql/json 三种高亮、光标处补全下拉（↑↓/Enter/Tab/ESC）、Tab 缩进、Ctrl+D 选词；已接入 API 规则、SQL 控制台、view 查询 | 实测 SQL 关键字高亮且两层像素级对齐（left/top 偏差 0）；规则输入 `@request.au` 弹出 `@request.auth.id` 等候选 |
| **字段级错误定位** | 解析 PocketBase 的 `data.{field}.message` 结构，错误显示在对应字段下方并高亮边框，再次编辑该字段即清除 | 实测唯一约束冲突时 email 字段红框 + "Value must be unique." |
| **字段拖拽排序** | 字段列表支持拖拽重排（独立手柄，避免干扰编辑态文本选择），拖拽中与落点有视觉反馈 | 类型检查 + 构建 |
| **Shift+Click 范围选择** | 记录列表按官方 v0.39.8 的 `bulkSelectRange` 语义：从上次点击行到当前行批量应用选中/取消 | 实测普通点击选 1 行 → Shift 点击第 5 行变 4 行 → 反向 Shift 点击回到 1 行 |
| **备份 canBackup 轮询** | 备份页打开时每 3.5s 轮询 health，操作进行中禁用控件，操作结束后自动刷新列表 | 类型检查 + 构建 |

顺带修复：**设置保存后 UI 与服务端不同步**——后端 PATCH 响应回显的是提交值而非规范化后的存储值（规则去重/排序、secret 脱敏均在存储阶段发生），导致保存后界面显示陈旧。改为保存后重新拉取。

### 仍未完成（按剩余价值排序）

1. **auth 记录运营能力** — impersonate 模拟登录、发送验证/密码重置邮件、邮件模板编辑、token 时长与失效控制、OAuth2 外链解绑。
2. **文件字段完整编辑** — 拖拽上传、已有文件的删除/恢复标记、新旧混排排序、缩略图与预览弹窗。
3. **URL 状态同步** — filter/sort/分页/记录 id 写入 hash query，支持刷新恢复与深链分享。
4. **集合层其它** — Truncate、Duplicate、Collections overview（ERD）、view 集合 SQL 试运行预览、auth 集合的 authRule/manageRule。
5. **日志页余项** — 图表框选缩放与联动、批量选择导出、"包含超管请求"开关、日志设置迁移到日志页齿轮。
6. **其它体验项** — 自定义 tooltip 体系、模态 ESC/遮罩关闭与层叠管理、搜索历史、S3 自动连通性测试、Superuser IPs 防锁死、Trusted proxy 实时诊断、SQL 结果分页与导出 CSV。
