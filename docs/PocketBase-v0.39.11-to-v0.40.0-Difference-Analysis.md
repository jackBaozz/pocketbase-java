# PocketBase v0.39.11 → v0.40.0 差异分析报告

> - 生成日期：2026-08-24
> - 当前 pocketbase-java 对标基线：**v0.39.11**（本文完成不代表代码已经升级到 v0.40.0）
> - 上游基线：[`v0.39.11`](https://github.com/pocketbase/pocketbase/tree/v0.39.11) `5d217ddb50cb144d80a5d0b0bdf11b52b2c3e457`
> - 上游目标：[`v0.40.0`](https://github.com/pocketbase/pocketbase/tree/v0.40.0) `50f5f83acecda5afc3245ecc9887cbf53882452b`

## 一、结论摘要

PocketBase v0.40.0 不是单纯的维护版。它同时包含一个 CLI 行为变化、一个新管理 API、一组日志持久化保护、默认安全头增强、在线备份重构、SQLite 安全模式，以及 Go JSON 运行时迁移。官方两个最终 tag 之间共有 **40 个提交、175 个文件变更、7,945 行新增、6,233 行删除**。

对 pocketbase-java 而言，升级工作的核心不是迁移 Go 代码，而是对齐可观察契约，并按 Java 已支持的四种存储模式重新实现：

1. 新增超级管理员专用 `DELETE /api/logs`，并同步 Java SDK、路由清单、README 和 Admin UI。
2. 新增 `settings.logs.maxDataSize`，在日志写入前限制 `data` 和 `message`，且 SQLite、MySQL、PostgreSQL、JSONL 行为一致。
3. 为所有响应增加 `Cross-Origin-Opener-Policy: same-origin`。
4. 保持已对齐的带引号 `Content-Disposition`，补充特殊字符和响应头注入回归测试。
5. 重构备份一致性：避免 ZIP 生成期间长期阻塞写入，同时保证数据库快照与本地文件引用一致。
6. 以 Jackson 兼容矩阵验证 Go `encoding/json/v2` 带来的外部行为，不引入 Go 1.27，也不机械复制 Go 内部 helper。

完整开发拆分见 [`PocketBase-v0.40.0-Upgrade-Development-Plan.md`](PocketBase-v0.40.0-Upgrade-Development-Plan.md)。

---

## 二、核验范围与方法

### 2.1 官方来源

- [PocketBase v0.40.0 Release](https://github.com/pocketbase/pocketbase/releases/tag/v0.40.0)
- [v0.39.11...v0.40.0 比较页](https://github.com/pocketbase/pocketbase/compare/v0.39.11...v0.40.0)
- [v0.40.0 源码树](https://github.com/pocketbase/pocketbase/tree/v0.40.0)
- 关键源码：[`pocketbase.go`](https://github.com/pocketbase/pocketbase/blob/v0.40.0/pocketbase.go)、[`apis/logs.go`](https://github.com/pocketbase/pocketbase/blob/v0.40.0/apis/logs.go)、[`core/log_model.go`](https://github.com/pocketbase/pocketbase/blob/v0.40.0/core/log_model.go)、[`core/settings_model.go`](https://github.com/pocketbase/pocketbase/blob/v0.40.0/core/settings_model.go)、[`core/backup_create.go`](https://github.com/pocketbase/pocketbase/blob/v0.40.0/core/backup_create.go)
- [SQLite defensive mode 官方说明](https://www.sqlite.org/c3ref/c_dbconfig_defensive.html)

### 2.2 核验原则

1. 以 `v0.39.11` 和 `v0.40.0` 两个最终 tag 的净差异为准，不按中间提交标题简单累加。
2. 区分 REST/进程/持久化等用户可观察契约与 Go、Svelte、modernc SQLite 的内部实现。
3. Java 端以行为等价为目标；对 SQLite、MySQL、PostgreSQL 和 JSONL 分别选择适合其事务模型的方案。
4. Release Note 与源码不一致时记录差异，并在开发前通过官方二进制 fixture 固化实际行为。
5. 对对象属性顺序、构建产物 hash 等非契约细节不做无意义的逐字节对齐。

### 2.3 变更规模

| 范围 | 变更文件数 | 说明 |
| --- | ---: | --- |
| `core/` | 42 | 备份、日志模型、设置、JSON v2、数据库连接及模型 helper |
| `apis/` | 13 | 日志清空、安全头及 JSON v2 适配测试 |
| `tools/` | 77 | filesystem、router、store、JSON 类型和依赖适配 |
| `ui/` | 18 | 日志设置/清空、列表摘要、备份提示、select 和性能细节 |
| 其他 | 25 | Go/CI/文档/依赖/构建产物 |

---

## 三、官方 v0.40.0 净差异总览

| 编号 | 官方净变化 | 可观察影响 | pocketbase-java 判定 |
| --- | --- | --- | --- |
| D01 | 命令错误和 recovered panic 返回给 `app.Start()` | 失败命令以非零状态退出，同时仍执行 terminate hook；可能影响 shell 命令链 | **需补充进程级验收，可能需调整生命周期** |
| D02 | 默认 `Content-Disposition` 的 filename 加双引号 | 含空格或特殊字符的下载名解析更稳定 | **主体已对齐，缺边界测试** |
| D03 | 默认增加 COOP 安全头 | 降低自定义 UI 使用不安全 `_blank` 时的 tab-nabbing 风险 | **缺失，需实现** |
| D04 | 新增 `Record.GetInt64(field)` | Go 扩展代码更方便读取 64 位值 | **Java 不适用；`JsonNode.asLong()` 已有等价能力** |
| D05 | 新增 `Store.Keys()` | 支撑内部状态枚举及备份实现 | **内部 helper，不要求公开 Java API** |
| D06 | 新增 `DELETE /api/logs` | 超级管理员可清空日志且不修改保留设置 | **REST、存储、SDK、UI 均缺失** |
| D07 | 新增 `logs.maxDataSize` 和日志截断 | 防止用户输入导致日志数据无限增长；message 固定限长 | **设置与两套写入路径均缺失** |
| D08 | filesystem 增加 `NewWriter`、`OnNewWriter`、`OnDelete` | 允许备份观察并发创建/删除文件 | **不公开照搬；需内部 observer/journal 等价能力** |
| D09 | 备份改为短时数据库快照并并发安全写 ZIP | 不再在整个备份期间持有数据库事务锁，写请求更少失败 | **现有实现未达到跨数据库/文件一致性目标** |
| D10 | SQLite 升级并默认 `_defensive=1` | 限制普通 SQL 对 schema、shadow table 等内部状态的危险修改 | **需研究 Xerial JDBC 等价配置并测试** |
| D11 | 最低 Go 版本升至 1.27，迁移 `encoding/json/v2` | JSON 编解码性能和部分边界行为变化，官方提示可能不兼容 | **不迁移 Go；需用 Jackson 契约矩阵验证** |
| D12 | Admin UI 日志、select、图表、备份提示及 records 请求优化 | 新日志能力可操作；少量语义/性能变化 | **日志功能缺失；其他项需分类审计** |
| D13 | Admin UI 使用的 JS SDK 0.27.3 → 0.28.0 | 新 `logs.truncate()` 可被 UI 调用 | **React UI未嵌入 SDK，但 smoke fixture 应升级验证** |

---

## 四、详细差异

### 4.1 CLI：错误传播、非零退出和优雅终止

官方 v0.40.0 将命令执行放入受保护的执行通道，等待“命令结束”或“OS 信号”二者之一，再触发 `OnTerminate`。命令返回错误或发生可恢复 panic 时，最终错误会返回 `app.Start()`，调用方可以产生非零退出码。

这是轻微破坏性变化。例如：

```bash
./pocketbase invalid && next-command
```

旧行为可能继续执行 `next-command`，v0.40.0 会因前一个命令非零退出而停止 `&&` 命令链。

当前 Java 状态：

- `ServerConfig.fromArgs()` 会拒绝未知参数；异常从 `PocketBaseServer.main()` 传播，JVM 通常以非零状态退出。
- 服务启动后已注册 JVM shutdown hook，SIGTERM/SIGINT 可调用 `LocalPocketBase.close()`。
- 但 shutdown hook 是在 `LocalPocketBase.start()` 成功后才注册；参数错误、启动中途失败、后台线程 panic 和 native binary 的退出行为尚没有统一的真实子进程测试。

因此不能仅凭“Java 异常会退出”判定完全对齐。必须验证：失败退出码、错误只输出一次、已创建资源被关闭，以及 SIGTERM 下 JVM/native 都能在超时内退出。

### 4.2 HTTP：`Content-Disposition` 与 COOP

#### filename 引号

官方从：

```http
Content-Disposition: attachment; filename=my file.txt
```

改为：

```http
Content-Disposition: attachment; filename="my file.txt"
```

pocketbase-java 的 `HttpFileSupport` 已为 inline 和 attachment 两种响应输出带引号 filename；record 文件名还会清理引号、反斜杠和 CR/LF，backup 名则受严格格式校验。主体功能无需重做，但仍需覆盖空格、分号、Unicode、双引号、反斜杠及 CR/LF 注入用例。

#### Cross-Origin-Opener-Policy

官方默认安全响应头新增：

```http
Cross-Origin-Opener-Policy: same-origin
```

它让顶层窗口与跨源 opener 隔离，是对缺少 `rel="noopener"` 的自定义 UI 插件的额外保护。当前 `HttpApi.addCommonHeaders()` 已有 `nosniff`、frame、CSP、referrer 和 permissions policy，但缺少 COOP，需在所有普通、错误、静态资源和文件响应上验证。

### 4.3 日志 API：清空全部日志

v0.40.0 新增：

```http
DELETE /api/logs
```

官方契约为：

| 场景 | 结果 |
| --- | --- |
| 未认证 | `401` |
| 普通认证记录 | `403` |
| 超级管理员 | `204 No Content` |
| 成功后的日志数量 | `0` |
| `settings.logs.maxDays` | 保持原值，不被改成 `0` |
| model hooks | 不触发，直接删除数据库行 |
| auxiliary DB compact/vacuum 失败 | 只记录 warning，不把成功删除改成 API 失败 |

成功的 `DELETE /api/logs` 自身也不应重新写入一条成功 activity log。当前 `shouldLogActivity()` 已跳过成功日志路由，可继续复用。

pocketbase-java 当前只注册并处理三个 GET 路由，`StorageEngine`、`RelationalStorageEngine`、`LogRepository`、`JsonFileStore` 和 Java `LogsService` 均没有 truncate 能力；路由清单和双语 README 也没有该端点。

### 4.4 日志设置与持久化限长

v0.40.0 在 `settings.logs` 中加入：

```json
{
  "logs": {
    "maxDataSize": 0,
    "maxDays": 5,
    "minLevel": 0,
    "logIP": true,
    "logAuthId": false
  }
}
```

关键语义：

- `maxDataSize = 0` 不是禁用限制，而是使用默认 **16 KiB（16,384 bytes）**。
- 显式值的合法范围是 `0..9007199254740991`（JavaScript 最大安全整数）。
- `Log.Data` 先序列化为 JSON；超过阈值后，以 best-effort 方式保留截断位置之前能够完整解码的顶层数据，并增加 `"__pb_truncated__": true`。
- 标记加入后，最终持久化 JSON 可能略大于 `maxDataSize`；该值是截断读取预算，不是最终行大小的绝对硬上限。
- `Log.Message` 固定限制为 8,000。Release Note 写“characters”，但 v0.40.0 源码以 Go 字符串的 UTF-8 **字节长度**判断并切片。多字节字符边界必须用官方二进制 fixture 再固定，不能仅按文案猜测。
- 空 `data` 保持空对象，不应无条件加入截断标记。

设置验证也发生了净变化：

- `maxDays` 的上限改为最大安全整数；最小值仍为 `0`。
- `minLevel` 只有最大安全整数上限，没有非负下限；官方 UI 输入范围为 `-100..100`。

当前 Java 端把 `maxDays` 限制在 `0..3650`，并在两个设置实现中把 `minLevel` 限制为 `0..16`；React UI 则限制为 `-8..16`。这不是 v0.40.0 契约，升级时应一起校正。由于最大安全整数超过 Java `int`，设置模型和清理日期计算必须使用 `long` 并处理 `Instant` 溢出。

映射检查还发现一个早于 v0.40.0 的既有漂移：官方 `logAuthId` 默认是 `false`，当前 Java 两套默认 settings 都是 `true`。目标是完整对标 v0.40.0，因此开发时应让“缺失该字段的新/旧配置”使用官方默认 `false`，但必须保留用户已经显式保存的 `true`。

### 4.5 filesystem 低层能力

官方 filesystem 新增：

- `NewWriter(key, opts)`：直接创建底层 writer，已有上传入口也改为复用它；
- `OnNewWriter()`：在文件即将创建时触发；
- `OnDelete()`：在文件删除时触发。

这些 hook 在 v0.40.0 最终接口中被刻意保持为 app 内部能力，避免公开 API 破坏。其主要消费者是新备份算法。

pocketbase-java 的 `FileStorageProvider` 已有流式 `put/get/delete/list/stat`，没有 writer 或事件 hook。Java 端不需要为了名称一致公开 Go 风格 API；应在内部增加可关闭的 observer/journal，并让 `LocalFileStorageProvider` 的所有写入和删除入口统一经过它。S3 collection storage 继续不进入本地备份，保持官方边界。

### 4.6 在线备份与一致性

官方 v0.40.0 的本地备份流程大致为：

1. 监听 storage 文件删除；若快照引用的文件即将被删，先写入 ZIP 并标记已处理。
2. 用 SQLite `VACUUM INTO` 生成主数据库的短时一致性副本并写入 ZIP。
3. 停止删除监听。
4. 开始监听新文件；快照之后创建的文件标记为排除，避免混入旧数据库状态。
5. 用 `VACUUM INTO` 复制 auxiliary 日志数据库并写入 ZIP。
6. 复制其余 `pb_data` 文件，跳过已处理/应排除项。
7. 停止新文件监听。

后续修正又为 ZIP 写入增加互斥锁、closed guard 和错误处理，避免文件 hook 从不同 goroutine 并发写 ZIP 或在 ZIP 关闭后继续写入。

效果是：数据库写锁主要缩短到快照语句执行期间；生成完整 ZIP 时业务可继续工作。官方仍明确说明，S3 collection 文件不包含在本地 ZIP 中。

当前 Java 实现的风险：

- relational `BackupRepository.createSnapshot()` 使用一个 JDBC connection 逐表读取，但没有显式开启 read-only consistent snapshot；并发写可能造成表与表之间时间点不同。
- 数据库逻辑快照完成后才遍历本地 `storage/`，期间上传或删除可能让数据库引用与 ZIP 文件不一致。
- JSONL `createBackup()` 为 `synchronized`，先 `saveAll()` 再压缩整个数据目录；一致性较强，但 ZIP 生成期间会长期阻塞同一 store 上的操作。
- 当前测试覆盖常规 create/download/restore/delete，却没有“备份期间并发创建记录、更新记录、上传文件、删除文件、恢复后核验引用”的压力验收。

因为 Java 额外支持 MySQL 和 PostgreSQL，不能只实现 SQLite `VACUUM INTO`：

- SQLite：评估 Xerial JDBC backup API、受控只读事务或文件级在线快照。
- MySQL：使用 consistent snapshot transaction，并避免跨连接读取。
- PostgreSQL：使用 `REPEATABLE READ READ ONLY` 快照。
- JSONL：短锁冻结不可变内存/文件快照，锁外完成 ZIP。
- 文件层：使用 generation/journal 处理快照边界前删除和边界后新增。

### 4.7 SQLite defensive mode

官方 modernc SQLite DSN 默认增加 `_defensive=1`。SQLite defensive mode 会阻止普通 SQL 主动破坏数据库文件或内部 schema，例如直接写 `sqlite_schema`、shadow table 或使用部分危险 pragma。

pocketbase-java 使用 Xerial `sqlite-jdbc` 3.46.0.0，当前连接初始化只设置 WAL、busy timeout、synchronous、foreign keys、temp store 和 cache size。modernc 的 DSN 参数不能直接复制到 Xerial URL；开发前必须确认 Xerial 是否暴露 `sqlite3_db_config(SQLITE_DBCONFIG_DEFENSIVE)`，或选择等价的授权器/SQL 防护。若驱动版本不支持，必须把该项标记为有证据的 Java 边界，而不能设置一个无效 URL 参数后宣称完成。

### 4.8 Go 1.27 与 `encoding/json/v2`

官方最低 Go 版本升至 1.27，并把大量 `encoding/json` 调用迁移到 `encoding/json/v2`。部分关键响应继续使用 deterministic 编码，body binding 也显式打开了大小写不敏感字段匹配，以减少升级破坏。

本次没有宣布新的 REST JSON schema，但官方明确警告 JSON 行为并非完全向后兼容。潜在边界包括：

- `null`、nil slice、空数组和空对象的区分；
- 重复 JSON key 和大小写近似字段名；
- 大整数、浮点数、`2^53-1` 附近的精度；
- 非法 UTF-8、转义、尾随内容和嵌套对象；
- map/object key 顺序及依赖精确序列化文本的日志；
- settings、collection import/export、batch、realtime、OAuth2 payload。

pocketbase-java 使用 Jackson 2.17.2。正确的映射方式是以官方 v0.40.0 的请求/响应 fixture 建立兼容矩阵；不应引入 Go 包，也不应把 JSON 对象 key 顺序当成通用 REST 契约。

### 4.9 Go helper：`Record.GetInt64` 与 `Store.Keys`

`Record.GetInt64(field)` 和 `Store.Keys()` 都不改变 HTTP 路由、字段或响应。前者是 Go 扩展开发 API，后者是内存 store 内部 helper。

Java SDK 当前以 Jackson `JsonNode` 返回记录值，调用方已有 `asLong()`；服务端也没有对应 Go Record 扩展模型。因此：

- 不新增仅为同名而存在、没有消费者的 Java API；
- 在大整数 fixture 中验证序列化安全边界即可；
- 若未来引入强类型 Java Record facade，再独立设计 `getLong()`。

### 4.10 Admin UI 净变化

| UI 变化 | 官方目的 | Java/React 映射 |
| --- | --- | --- |
| 日志设置增加 `maxDataSize` | 配置持久化 data 上限 | **必须实现**，含帮助文案、范围与错误反馈 |
| 日志设置增加“删除全部日志”及确认框 | 调用 `pb.logs.truncate()` | **必须实现**，使用项目 `ConfirmDialog`，成功后刷新 stats/list |
| 删除日志成功后刷新列表 | 避免页面残留旧日志 | **必须实现**，清空选择、图表和详情状态 |
| 摘要忽略 `__pb_truncated__` | 系统标记不占普通数据 chip | **必须实现**，详情仍可查看标记 |
| 默认日志级别按 key 排序 | 输出顺序稳定 | **需小幅调整**；React 虽为固定数组，但当前顺序是 `0,4,8,-4`，应改为 `-4,0,4,8` 并测试 |
| select 根节点改为 `output`，name/class 移到根 | 修复表单/错误匹配及自定义样式语义 | **审计后适配**，React 受控组件不应机械替换 DOM |
| 日志图表增加 `translateZ(0)` | 尝试使用 GPU 合成 | **低风险评估项**，以浏览器性能/清晰度验证为准 |
| 备份警告改为“性能可能轻微下降” | 与短时快照实现一致 | **实现在线备份后再改**，不能提前给出错误承诺 |
| records 列表取消 `fields` 投影 | Go JSON v2 全量流式响应在该场景更快 | **当前请求已不带 fields**；只做性能回归，不因 Go 结论改 Java API |
| JS SDK 0.27.3 → 0.28.0 | 提供 logs truncate client | React UI 不依赖 SDK；将 JS smoke fixture 升至 0.28.x |

所有新增文案必须通过 `t("key", "English default")`，并同步 9 个 locale；UI 完成后必须重新构建到 `src/main/resources/pocketbase-admin/`。

---

## 五、不能重复计入的中间提交

以下变化虽然出现在 `v0.39.11..v0.40.0` 的提交历史中，但两个最终 tag 的相应源码内容相同，或已由 v0.39.11 包含，因此不属于本次净增量：

- Realtime API Preview 示例修复；
- archive 文件扩展类型识别；
- 新文件排序到末尾；
- CodeEditor ESC 逃逸；
- sortable、dropdown 的若干中间修复。

升级任务不得再次把这些项目标记成 v0.40.0 新缺口。最终判断应使用：

```bash
git diff v0.39.11..v0.40.0 -- <path>
```

而不是只阅读提交标题。

---

## 六、pocketbase-java 当前差距矩阵

| 能力 | 当前状态 | 主要位置 | 升级动作 |
| --- | --- | --- | --- |
| `DELETE /api/logs` | 缺失 | `HttpApi.java`、`StorageEngine.java`、`LogRepository.java`、`JsonFileStore.java` | 新增路由、鉴权、存储清空和测试 |
| Java logs SDK truncate | 缺失 | `client/LogsService.java` | 新增 `truncate()` 并校验 DELETE/Authorization |
| 路由与文档契约 | 缺失 DELETE | `official-route-manifest.json`、`README.md`、`README_zh.md` | 同步路由表和 API 表 |
| logs 设置 | 缺少 `maxDataSize`，范围及 `logAuthId` 默认值有漂移 | `SettingsRepository.java`、`JsonFileStore.java`、`App.tsx` | 默认值、long 验证、持久化、UI及旧配置兼容 |
| message/data 截断 | 缺失 | relational `LogRepository`、JSONL `JsonFileStore` | 共用截断器，避免两套行为漂移 |
| 截断 marker 摘要 | 缺失 | `App.tsx::logDataChips` | 列表跳过，详情保留 |
| COOP | 缺失 | `HttpApi.addCommonHeaders()` | 增加响应头及全路由测试 |
| filename 引号 | 已实现 | `HttpFileSupport.java` | 仅补特殊字符/注入测试 |
| 在线一致性备份 | 未完成 | `BackupRepository.java`、`JsonFileStore.java` | 分引擎快照 + 文件 generation/journal |
| filesystem 事件能力 | 缺失 | `FileStorageProvider.java` 及 local/S3 实现 | 仅增加内部 observer，不公开照搬 Go API |
| SQLite defensive | 未映射 | `JooqDatabase.java` | 驱动能力研究、实现或记录边界 |
| JSON v2 可观察行为 | 未系统验证 | Jackson、API/SDK smoke tests | 建立官方 fixture 兼容矩阵 |
| CLI 非零退出 + terminate | 部分具备 | `PocketBaseServer.java`、`LocalPocketBase.java` | JVM/native 子进程和信号测试 |
| select / chart 小改 | 部分或不适用 | `DropdownSelect.tsx`、`styles.css` | 语义/性能审计，不机械翻译 |

---

## 七、兼容性与发布风险

### P0：数据一致性风险

- 备份在并发写入期间可能恢复出跨表不一致数据或缺少被记录引用的文件。
- 日志截断若在 relational 与 JSONL 分别实现，容易出现字节/字符、marker 和嵌套 JSON 行为漂移。

### P1：API 与安全风险

- 漏掉 `DELETE /api/logs` 会直接破坏官方 JS SDK 0.28 的管理能力。
- COOP 未设置会缺失 v0.40.0 的默认 tab isolation 防护。
- 错误的 `maxDataSize=0` 解释可能关闭限制，造成日志行无限增长。
- 将官方 safe integer 直接塞入 Java `int` 会溢出或错误钳制。

### P1：升级兼容风险

- Jackson 与 Go JSON v2 对重复 key、数字和截断 UTF-8 的处理可能不同。
- 未在 JVM 和 native binary 上验证退出码，可能出现 CI 认为成功、资源却未清理的情况。

### P2：性能与交互风险

- 在线备份可能增加临时磁盘占用，应至少预留接近数据目录两倍空间。
- records 是否使用 `fields` 是运行时性能选择，不是固定 API 规则，需以 Java benchmark 决定。
- GPU transform 在部分浏览器可能改善动画，也可能影响文字/线条渲染，应通过 Playwright 截图和性能记录验证。

---

## 八、升级范围判定

### 必须实现后才能宣称对标 v0.40.0

- `DELETE /api/logs` 全链路；
- `logs.maxDataSize`、日志 data/message 截断及 marker；
- COOP 默认头；
- Admin UI 日志设置、删除确认、刷新与摘要；
- JS SDK 0.28 smoke 及现有 Dart smoke；
- 四种存储模式的日志一致性测试；
- 在线备份一致性或经批准的明确发布边界；
- JSON、CLI、JVM/native 关键回归通过。

### 已对齐但仍需回归

- 带引号 `Content-Disposition`；
- records 请求当前不使用 fields 投影；
- 成功日志查询不自记录 activity log。

### 不适用或不直接移植

- Go 1.27 版本号；
- `encoding/json/v2` 包本身；
- Go `Record.GetInt64()` 和 `Store.Keys()` 的同名 Java API；
- modernc SQLite DSN 字符串；
- Svelte select 的具体 DOM 实现；
- 上游 `ui/dist` hash 文件名。

---

## 九、复核命令

```bash
# 上游仓库
git rev-parse v0.39.11 v0.40.0
git rev-list --count v0.39.11..v0.40.0
git diff --shortstat v0.39.11..v0.40.0
git diff --name-status v0.39.11..v0.40.0

# 关键净差异
git diff v0.39.11..v0.40.0 -- \
  pocketbase.go apis/logs.go apis/middlewares.go \
  core/log_model.go core/settings_model.go core/backup_create.go \
  tools/filesystem/filesystem.go tools/store/store.go \
  ui/src/logs ui/src/base/select.js ui/src/records/recordsList.js

# pocketbase-java 当前缺口
rg -n "api/logs|maxDataSize|Cross-Origin-Opener-Policy|Content-Disposition" \
  src/main/java src/test/java src/test/resources ui/src README.md README_zh.md
```

> 本报告冻结的是官方 v0.40.0 的升级输入，不是完成声明。只有配套开发计划的所有 release gate 通过后，才能把项目基线从 v0.39.11 更新为 v0.40.0。
