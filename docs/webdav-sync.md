# WebDAV 同步设计规范（MVP）

> 状态：Draft（待评审）
> 分支：`feat/webdav-sync`
> 关联：PR #29（移除官方闭源同步与账号登录）
> 目标版本：Logseq OG 1.1.x（MVP，桌面 + 移动）

本文档是 WebDAV 同步功能的唯一规范来源。实现与文档不一致时，以修订本文档为先。

---

## 1. 背景与决策

- 官方 Logseq Sync 的后端闭源（`rsapi` WASM + 账号体系），对第三方/自托管同步没有复用价值，已在 #29 整体移除。
- WebDAV 是自托管场景覆盖最广的协议：Nextcloud、坚果云、Synology、ownCloud、rclone serve、nginx-dav 等都可用。
- **不采用插件方案**：
  - 插件系统仅桌面可用（`src/main/frontend/config.cljs` 中 `lsp-enabled?` 要求 `util/electron?`），移动端无法使用；
  - 插件 API 没有任意图文件的读写能力（只能写插件存储目录），不能写 assets，也感知不到文件系统事件，无法可靠地做双向文件同步；
  - 唯一能搬运文件的插件 API 是 `logseq.Git.execCommand`，即 git 路线。详见附录 A。
- **结论：WebDAV 同步内置实现**，同步核心写成平台无关的纯逻辑，桌面与移动各做一层适配。

### 1.1 已确认的 MVP 决策

| 决策点 | 结论 |
| --- | --- |
| 平台 | 桌面（Electron）+ 移动（iOS/Android Capacitor） |
| 触发 | 手动 + 打开图 + 定时轮询（保存后触发留到后续） |
| 冲突 | 保留双方副本：远端版本落盘到 `logseq/webdav/conflicts`，本地版本继续作为正文并上传 |
| 删除 | MVP 不传播删除（两侧删除都记入 manifest 墓碑，避免文件复活） |
| 认证 | HTTP Basic over HTTPS |
| 同步粒度 | 文件级（graph 目录 ↔ WebDAV 远端目录），不改 DB 层同步 |
| git 仓库 | 开启同步时仅**提示**用户向 `.gitignore` 添加 `logseq/webdav/`，不自动修改 `.gitignore` |

---

## 2. 范围

### 2.1 MVP 目标

- 单用户、多设备，通过 WebDAV 双向同步 file-based graph（markdown/org + assets）。
- 桌面与移动都能：配置远端、手动同步、打开图自动拉取、按间隔自动同步。
- 外部下载的文件通过现有 file watcher 增量进入 DB，无需同步引擎关心 DataScript。
- 冲突不丢数据：远端版本自动备份，用户可恢复。
- 明确的同步状态与错误提示。

### 2.2 非目标（MVP 之后）

- 删除/重命名传播（tombstone + `DELETE`/`MOVE`）。
- 三方合并（复用 `frontend.fs.diff-merge`）。
- 保存后实时触发、app 关闭时同步、移动端后台任务。
- DB graphs、实时协作、E2E 加密。
- 浏览器（web）版、publish 模式。
- 多远端配置、OAuth/2FA 特殊流程。
- WebDAV 之外的 provider（S3/OneDrive 等）。

---

## 3. 同步语义

### 3.1 数据模型

同步两侧各有一个文件树，引擎维护一份**每设备独立**的 manifest（记录上次成功同步时两侧的元数据）。

manifest v1（EDN）：

```edn
{:version 1
 :remote-root "https://dav.example.com/remote.php/dav/files/user/logseq-og/my-graph"
 :last-sync-at "2026-10-07T10:00:00.000Z"
 :files
 {"journals/2026_10_07.md"
  {:local  {:mtime 1759821600000 :size 1234}
   :remote {:etag "\"abc123\"" :mtime "Wed, 07 Oct 2026 10:00:00 GMT" :size 1234}
   :state  :synced        ; :synced | :local-deleted | :remote-deleted
   :synced-at "2026-10-07T10:00:00.000Z"}}}
```

- 键是相对 graph 根目录的 POSIX 路径。
- `:local` 是上次同步完成后本地文件的 `mtime` + `size`。
- `:remote` 是远端 `etag`（主判据）+ `getlastmodified`/`getcontentlength`。
- manifest 缺失或损坏时视为空 manifest，此时对两侧同名文件按"双方独立创建"处理为冲突，不丢数据。

### 3.2 状态判定

对每个路径：

| 本地 | 远端 | manifest | 动作 |
| --- | --- | --- | --- |
| 在 | 在 | 无 | **冲突**（双方独立创建同名文件） |
| 在 | 在 | 有 | 本地变了 → 上传；远端变了 → 下载；都变 → 冲突；都没变 → 跳过 |
| 在 | 无 | 无 | 上传（新增） |
| 在 | 无 | 有 | 远端删除不传播：仅标记 `:remote-deleted`；若本地在标记后又修改 → 上传（远端重建） |
| 无 | 在 | 无 | 下载（远端新增） |
| 无 | 在 | 有 | 本地删除不传播：标记 `:local-deleted`；若远端在标记后又修改 → 下载（远端编辑优先，本地文件复活） |
| 无 | 无 | 有 | 删除 manifest 条目 |

变化判定：

- 本地变化 = 当前 `mtime`/`size` 与 manifest `:local` 不同（MVP 不做内容哈希；后续可加）。
- 远端变化 = 当前 `etag` 与 manifest `:remote.etag` 不同；服务器不返回 etag 时退化为 `getlastmodified` + `size`。

### 3.3 冲突处理（MVP）

1. 下载远端版本内容，保存到图的 `logseq/webdav/conflicts/<yyyyMMdd-HHmmss>/<相对路径>`。
   - 该目录接入忽略清单（见 4.6），不会进入 DB，也不会再次同步。
   - **不写入 `logseq/bak`**：与「关闭文件备份」（#11 的 `:feature/enable-backup?`）不冲突。冲突副本只在冲突时产生，语义上是"同步救援"，不是常规变更备份。
   - 冲突副本始终保留，即使关闭了 bak 备份（数据安全优先）；后续如需彻底关闭可加同步级开关。
2. 保持本地文件内容不变，并上传本地版本覆盖远端。
3. 更新 manifest（上传后的远端元数据）。
4. 通知用户冲突路径，提示备份位置。
5. MVP 不做自动合并。

### 3.4 删除与重命名

- 任意一侧的删除都不会同步到另一侧，只更新 manifest 状态（见 3.2）。
- 本地重命名在 MVP 中表现为"新增"：新路径上传，旧路径按"本地删除"标记，远端旧文件保留；同设备不会复活旧文件，其他设备可能同时看到新旧两个页面（已知限制）。
- 后续里程碑考虑按内容哈希识别重命名并映射为远端 `MOVE`。

### 3.5 排除规则

扫描与上传都排除：

- `logseq/bak`、`logseq/version-files`、`.recycle`（含 `logseq/.recycle`）；
- 隐藏文件/目录（`.` 开头，含 `.git`、`.DS_Store`）；
- `logseq/graphs-txid.edn`（历史遗留）；
- `node_modules`；
- 临时/中间文件：`*.tmp`、`*.part`、`*.crdownload`、`Thumbs.db`、`desktop.ini`；
- iCloud 占位文件 `*.icloud`（不上传；扫描到即按 3.6 暂停并告警）；
- 同步冲突副本目录 `logseq/webdav`。

> `logseq/config.edn` 属于图内容，**正常同步**。WebDAV 自身配置与凭据不写入图内（见第 6 节）。

### 3.6 与其他同步方式共存（单一同步源原则）

**原则：同一个图在同一时间只应有 1 个目录级同步源。** WebDAV 与 git、iCloud、Syncthing 等叠加会造成双份冲突处理、文件写入竞争与"幽灵副本"，必须在启用阶段就显式约束。

| 其他同步方式 | 检测方式 | 策略（建议） |
| --- | --- | --- |
| iCloud 容器（iOS/iPadOS；macOS iCloud Drive 目录） | 移动端：图路径命中 iCloud 容器根（`state/get-icloud-container-root-url`）；桌面：路径位于 `~/Library/Mobile Documents/` 下 | **默认禁止启用 WebDAV**，UI 说明原因并引导二选一（把图移出 iCloud 容器，或继续用 iCloud）。原因：iCloud 会把文件淘汰成 `.icloud` 占位符、生成系统级冲突副本，与 WebDAV 扫描/写入叠加会产生大量假新增/假修改 |
| git 仓库（图根存在 `.git`） | 图根 `.git` 存在性（参考 `src/electron/electron/git.cljs` 的 `git-dir-exists?`）；进一步检查是否配置了 remote | 分两种：**仅本地自动提交**（无 remote）→ 允许，提示向 `.gitignore` 添加 `logseq/webdav/`；**配置了 remote**（真实 git 推送/拉取）→ 强警告 + 显式确认，建议二选一。git 与 WebDAV 并存时，同步下载会被自动提交（历史噪声），冲突也会被两套机制各处理一次 |
| 其他目录同步（Syncthing/Dropbox/OneDrive/Google Drive/rclone 挂载/Android 其他 App） | 无法可靠检测 | 启用时二次确认「我确认此图未使用其他目录同步工具」；运行时启发式：发现 `.icloud` 占位文件、`*.sync-conflict-*`、`* (conflicted copy*)` 等已知冲突产物时，暂停本轮同步并提示 |

实现注意：

- 策略判断做成纯函数（输入：平台、图路径、`.git`/remote 检测结果、iCloud 根），便于单测。
- 检测结果进入同步设置状态（持续展示阻止/警告原因），不能只弹一次。
- **移动端 iCloud 检测存在 fork 遗留问题**：`src/main/frontend/mobile/util.cljs` 的 `in-iCloud-container-path?` 仍在匹配上游容器名 `iCloud~com~logseq~logseq`，而本 fork 的 iCloud 容器是 `iCloud.com.logseq.og`（`ios/App/App/App.entitlements`），按现有代码检测会漏判。M3 需修正（按实际容器路径匹配，例如 `iCloud~com~logseq~og`，以真机路径为准）并补测试。
- 图选择器的「iCloud sync」模式与 WebDAV 的互斥提示在 M3 联动（`src/main/frontend/mobile/graph_picker.cljs`）。

---

## 4. 架构

### 4.1 分层

```
┌─────────────────────────────────────────────────────┐
│ UI / 触发层（renderer，平台无关）                      │
│  frontend.handler.webdav + settings/状态组件          │
├─────────────────────────────────────────────────────┤
│ 同步核心（renderer，平台无关，可单测）                  │
│  webdav/engine  webdav/plan  webdav/manifest         │
│  webdav/client（WebDAV 协议 + XML 解析）              │
├──────────────────────────┬──────────────────────────┤
│ 适配层：桌面              │ 适配层：移动               │
│  HTTP → 主进程 node-fetch │  HTTP → CapacitorHttp    │
│  FS   → frontend.fs(node) │  FS   → frontend.fs(cap) │
│  状态 → ~/.logseq-og      │  状态 → Directory.Data    │
│  凭据 → safeStorage       │  凭据 → Preferences       │
└──────────────────────────┴──────────────────────────┘
```

- 同步核心运行在 renderer，复用 `frontend.fs` 协议（桌面走 IPC，移动走 Capacitor Filesystem），避免维护两套引擎。
- 纯逻辑（plan/XML/manifest 编码）不依赖平台 API，可直接在 `yarn test` 的 Node 环境跑单测。

### 4.2 模块与文件（规划）

```
src/main/frontend/webdav/
  client.cljs          ; PROPFIND/GET/PUT/MKCOL + multistatus 解析
  http.cljs            ; HTTP adapter 协议定义
  http/desktop.cljs    ; ipc :webdavFetch
  http/mobile.cljs     ; CapacitorHttp
  scan.cljs            ; 本地树扫描（frontend.fs）
  plan.cljs            ; 纯函数：两棵树 + manifest → 操作计划
  manifest.cljs        ; 编解码、图 key
  store.cljs           ; 平台状态/配置存储协议
  store/desktop.cljs   ; ~/.logseq-og/webdav/<key>/
  store/mobile.cljs    ; Directory.Data/webdav/<key>/
  engine.cljs          ; 状态机、单飞锁、触发、进度事件
src/main/frontend/handler/webdav.cljs   ; UI 事件与配置处理
src/electron/electron/webdav.cljs       ; IPC：fetch / safeStorage / 多窗口锁
```

- Electron 新增 IPC（`src/electron/electron/handler.cljs` 注册）：
  - `:webdavFetch`：包装现有 `utils/fetch`（node-fetch，支持任意 method，复用代理配置），返回 `{status headers body(base64/text)}`；
  - `:webdavSafeStorage`：`safeStorage.encryptString/decryptString`；
  - `:webdavSyncLock`：每图同步租约（多窗口协调）。
- 移动端在 `capacitor.config.ts` 启用 `plugins.CapacitorHttp.enabled = true`（Capacitor 5 内置，无需新依赖）。

### 4.3 触发与调度

| 触发 | 行为 |
| --- | --- |
| 打开图 | graph 加载完成后延迟 ~3s 执行一次（拉取远端变更）；仅 file-based local graph |
| 定时 | 默认 300s，可配 0/60/300/900/1800；app 在前台时生效，移动端随 app 生命周期暂停/恢复 |
| 手动 | 设置页与图下拉菜单的「立即同步」 |
| 保存后 | MVP 不接入（留待后续，避免回环复杂度） |

- 单飞：同一时刻每个图只允许一个同步任务；进行中忽略新触发。
- 离线（`navigator.onLine === false`）直接跳过，不报错刷屏。
- 共存策略（3.6）判定为禁止时，自动触发全部跳过；手动触发只展示原因与引导。

### 4.4 防回环

- 下载写盘走 `frontend.fs`（不经 `alter-file`），由 chokidar / 移动原生 watcher 触发 `handle-changed!` 增量入库；watcher 已有"内容与 DB 相同则跳过"比较，重复写入是 no-op。
- 同步引擎只由 4.3 的触发驱动，不监听文件变更事件，从根上避免"同步→写入→再同步"的回环。
- 同步过程中对同一路径加互斥，watcher 与同步同时写同一文件时以同步任务完成为准，随后比较内容兜底。
- 冲突副本写入 `logseq/webdav`（已接入忽略清单，见 4.6）。

### 4.5 多窗口与多设备

- 桌面多窗口：Electron 主进程按 graph 目录维护同步租约，只有一个窗口执行同步；租约在窗口关闭/切换图时释放，其他窗口看到"另一窗口正在同步"。
- 多设备：manifest 每设备独立（存放于本机），不做跨设备共享；冲突策略保证不丢数据。

### 4.6 忽略规则接入点（logseq/webdav）

图内新增 `logseq/webdav` 目录后，必须确保它不被当作页面解析、不触发 watcher、不进入任何扫描/同步：

| 位置 | 用途 |
| --- | --- |
| `deps/common/src/logseq/common/graph.cljs` 的 `ignored-path?` | 桌面 watcher（chokidar）与图文件枚举；同步更新 docstring |
| `deps/common/test/logseq/common/graph_test.cljs` | 补充 `logseq/webdav` 忽略用例 |
| `src/main/frontend/util/fs.cljs` 的 `ignored-path?` | renderer 侧全量加载 / 手动刷新 / 初始 watcher |
| `src/main/frontend/fs/capacitor_fs.cljs` 的目录过滤（`get-file-paths` / `get-files`） | 移动端递归枚举 |
| `android/app/src/main/java/com/logseq/og/FsWatcher.java` 的目录名过滤 | Android 原生 watcher |
| `ios/App/App/FsWatcher.swift` 的 `/logseq/bak/`、`/logseq/version-files/` 同处过滤 | iOS 原生 watcher |
| 同步引擎本地扫描排除（`webdav/scan.cljs`） | 本地扫描与远端上传 |
| 发布/导出流程 | 回归确认 `logseq/webdav` 不进入 publish 产物 |

> manifest、配置与凭据仍存放在图外（见第 6 节）：它们是每设备状态，随图/ git 传播会导致跨设备语义错乱。冲突副本放图内，是因为它是用户数据，需要可发现、可随图快照保留。

---

## 5. WebDAV 协议子集

### 5.1 请求

| 操作 | 请求 | 说明 |
| --- | --- | --- |
| 列目录 | `PROPFIND` Depth: 1，请求 `D:prop`（getetag/getlastmodified/getcontentlength/resourcetype） | 递归逐目录扫描 |
| 下载 | `GET` | 文本按 UTF-8，二进制按 base64 落盘 |
| 上传 | `PUT` | 先确保父目录 `MKCOL`；成功后从响应 ETag 或补一次 PROPFIND 获取新 etag |
| 建目录 | `MKCOL` | 已存在（405）视为成功 |
| 删除/移动 | MVP 不使用 | 后续里程碑 |

- URL 拼接：远端根 + 相对路径，逐段 percent-encode（非 ASCII 文件名必须编码）。
- XML 解析用 `DOMParser`，按 `localName` 匹配，忽略命名空间前缀差异。
- `href` 需 URL-decode 后去掉远端根前缀，得到相对路径。

### 5.2 服务器兼容

首批必须验证：**Nextcloud、坚果云、Synology WebDAV**（rclone serve 用于 CI）。

已知差异点：

- 坚果云有请求频率限制，需串行 + 节流（默认请求间隔 ≥100ms）并对 429 退避；需使用应用密码。
- Nextcloud 大文件 PUT 可能要求分块（MVP 限制单文件 <100MB，超限跳过并提示，后续做分块）。
- 部分服务器 etag 带引号/弱 etag；统一按原样字符串比较。
- `getlastmodified` 为 RFC1123，解析失败时仅依赖 etag/size。

### 5.3 错误与重试

- 超时：连接/读取各 30s。
- 重试：网络错误、429、5xx 退避重试 3 次（指数退避，尊重 `Retry-After`）。
- 401/403：暂停自动同步，提示检查账号/密码/权限，保留上次 manifest。
- 单个文件失败不中断整轮：记入本轮错误列表，其余继续，最后汇总通知。
- 错误状态进入 `[:webdav/status repo]`，UI 可见。

---

## 6. 配置与状态存储

### 6.1 每图配置（非机密）

```edn
{:enabled false
 :url "https://dav.example.com/remote.php/dav/files/user"
 :remote-root "/logseq-og/my-graph"     ; 相对 url 的远端目录
 :username "user"
 :interval 300
 :verify-tls true                        ; MVP 固定 true，不提供关闭
 :request-gap-ms 100
 :max-file-mb 100}
```

- 桌面：`~/.logseq-og/webdav/<graph-key>/config.edn`
- 移动：`Preferences`（key: `webdav/config/<graph-key>`）
- `graph-key`：桌面沿用 git 的做法（图目录路径替换 `/`、`:`）；移动用图目录 URI 的哈希。

### 6.2 凭据

- 桌面：Electron `safeStorage` 加密后存 `~/.logseq-og/webdav/<graph-key>/credentials.bin`。
- 移动：MVP 存 `@capacitor/preferences`（app 私有沙箱），在文档中标注为已知限制；后续换安全存储插件。
- **绝不写入 `logseq/config.edn` 或任何会同步/发布的图内文件。**

### 6.3 manifest

- 桌面：`~/.logseq-og/webdav/<graph-key>/manifest.edn`
- 移动：`Directory.Data/webdav/<graph-key>/manifest.edn`
- 每次同步轮次结束后原子写入（temp + rename）。
- manifest 不进图：它是每设备状态，随图或 git 传播会导致跨设备 mtime/etag 语义错乱。冲突救援副本则放在图内 `logseq/webdav/conflicts`（见 3.3、4.6）。

---

## 7. UI

- 设置页新增 **Sync / 同步** 区块（桌面与移动同一个逻辑组件）：
  - 启用开关、服务器 URL、用户名、密码（掩码、只写）、远端目录、同步间隔；
  - 「测试连接」（PROPFIND 远端根）；
  - 「立即同步」；
  - 状态行：上次同步时间、结果（成功/失败原因）、进行中进度。
- 图下拉菜单增加「立即同步」入口（`src/main/frontend/components/repo.cljs`）。
- git 仓库提示：若当前图存在 `.gitignore`（或 `.git` 目录），在同步设置中展示一条可复制的建议——向 `.gitignore` 添加 `logseq/webdav/`；**不自动修改用户文件**。
- 通知：
  - 冲突：提示备份路径；
  - 错误：认证失败、超限、单文件失败汇总。
- i18n：新增 `:webdav/*` 键，至少 en + zh-cn，通过 `bb lang:validate-translations`。

---

## 8. 测试计划

- 单测（`src/test/frontend/webdav/`，`yarn test` 可跑）：
  - `plan_test`：3.2 状态表的全部分支（表驱动）；
  - `client_test`：PROPFIND multistatus 解析（Nextcloud / 坚果云 / 无命名空间三类 fixture）、href 解码、URL 编码；
  - `manifest_test`：编解码、损坏恢复、墓碑转换；
  - 忽略规则：`deps/common` 的 `ignored-path?` 补充 `logseq/webdav` 用例。
- 集成（Node 环境，stub HTTP adapter + `frontend.fs.memory-fs` 或临时目录）：
  - 首次同步（双向新增）、双方修改冲突、单侧删除墓碑、排除规则。
- 移动端回归：确认 `logseq/webdav` 下文件不触发原生 watcher、不进入 DB。
- 共存策略：3.6 判定纯函数单测（iCloud 路径 / `.git` / remote / 平台组合）；移动端修正后的 iCloud 容器检测用例。
- 真实服务器手工矩阵（发版前）：Nextcloud、坚果云、Synology、rclone serve。
- CI：后续加 `rclone serve webdav` 的集成任务；E2E 更晚。

---

## 9. 里程碑

- **M0 规范**：本文档评审通过（本 PR）。
- **M1 纯核心**：`plan` / `client` / `manifest` + 单测；不接真实 IO。
- **M2 桌面打通**：HTTP/FS/store 适配、engine、手动 + 打开图 + 定时、设置 UI、共存检测（git / iCloud Drive 路径）与提示。
- **M3 移动打通**：CapacitorHttp、移动 store、移动 UI、修正 iCloud 容器检测并接入互斥提示；验证写入经原生 watcher 入库。
- **M4 稳定化**：错误/限流/退避、服务器兼容矩阵、文档与 i18n、性能（大图 >2000 文件）。
- **后续**：删除/重命名传播、保存触发、三方合并、后台同步、E2E 加密、浏览器版。

## 10. 开放问题（待定项详解）

> 下列问题**不阻塞 M1**（纯核心）。每条包含：背景、可选方案、我的建议。定下来后写回 1.1 决策表。

### Q1 首次同步时远端已有内容，怎么处理？

- **背景**：manifest 为空时无法判断两侧同名文件谁更新（可能是两台设备各自创建的同名页面，也可能是同一文件的旧副本）。3.2 会把它们全部判为"冲突"。
- **方案**：
  - A. 统一走 3.2：同名 → 冲突副本 + 本地上传；异名 → 各自同步。无新增 UI。
  - B. 首次同步弹窗让用户选「以本地为准 / 以远端为准 / 合并」。
  - C. 首次只下载不上传，让用户看过远端后再正常同步。
- **建议**：A。首次同步冲突本就少，B/C 留作后续增强。
- **影响**：B/C 会增加 M2 的 UI 与状态机复杂度。

### Q2 重命名要不要在 MVP 就处理？

- **背景**：MVP 不传播删除，重命名页面会导致远端旧文件保留，其他设备可能同时看到新旧两个页面（3.4 已知限制）。
- **方案**：
  - A. 维持现状，后续里程碑再做。
  - B. MVP 就做「本地消失 + 新出现且内容哈希一致 → 远端 MOVE」的识别。
- **建议**：A。重命名识别与「删除传播 / tombstone」强耦合，分开做容易漏判误判；作为后续里程碑第一个补上的能力。
- **影响**：影响 M2/M4 排期与 3.4 的用户体验说明。

### Q3 移动端密码存在哪里？

- **背景**：桌面有 Electron `safeStorage`；移动端没有可用的内置加密存储。
- **方案**：
  - A. `@capacitor/preferences`（app 私有沙箱、明文，其他 App 读不到）。
  - B. 引入安全存储插件（iOS Keychain / Android Keystore）。
  - C. 不保存密码，每次同步时输入（无法自动同步）。
- **建议**：A，并在文档标注为已知限制；如果对安全要求高，B 作为 M3 的独立任务（新增一个原生依赖）。
- **影响**：B 增加移动端依赖与发版验证成本。

### Q4 大文件与大量 assets 怎么限制？

- **背景**：Nextcloud 大文件需要分块上传（MVP 不做）；assets 里可能有几百 MB 的视频。
- **方案**：
  - A. 固定上限（如单文件 100MB，超限跳过并提示），不提供开关。
  - B. 设置项「跳过大于 N MB 的文件」。
  - C. 提供「不同步 assets」总开关。
- **建议**：A 起步，后续按需加 B。
- **影响**：影响 5.2 与设置页字段数量。

### Q5 坚果云等服务器的兼容参数需要实测

- **背景**：坚果云 PROPFIND 频率限制严格，etag 行为可能与 Nextcloud 不同；这直接影响 5.3 的退避参数与 5.1 的扫描策略。
- **待办**：M4 用真实账号实测，确定请求间隔、重试次数、是否引入服务器预设（Nextcloud/坚果云/Synology）。不需要现在决策。
- **影响**：无，不阻塞开发。

### Q6 冲突副本要不要独立开关？

- **背景**：3.3 目前是强制保留。用户若关闭了 bak 备份，可能也不想再产生冲突文件。
- **方案**：
  - A. 固定保留（数据安全优先）。
  - B. 设置项「冲突时保留远端副本」默认开，关闭需二次确认（有丢数据风险）。
- **建议**：A。冲突是低频事件，副本是唯一的远端版本兜底。
- **影响**：B 增加设置项与风险提示文案。

### Q7 3.6 的共存策略强度是否需要调整？

- **背景**：3.6 建议 iCloud 硬禁止、git remote 强警告、其他目录同步靠确认 + 启发式。
- **需要确认**：
  - iCloud：硬禁止（不给覆盖入口）还是允许强警告后覆盖？
  - git remote：强警告后允许共存，还是同样硬禁止？
- **建议**：iCloud 硬禁止（Apple 的占位文件与系统冲突副本时序不可控，是唯一会主动破坏图数据的情况）；git remote 警告 + 允许，给高级用户留口子。

---

## 附录 A：为什么不用插件

- 插件仅桌面：`src/main/frontend/config.cljs` 的 `lsp-enabled?` 包含 `(util/electron?)`，移动端不加载插件系统。
- 插件文件 API 只管插件自己的存储目录（`src/main/logseq/api.cljs` 的 `write_plugin_storage_file` / `write_user_tmp_file`），不能写 pages/journals/assets。
- `Assets.listFilesOfCurrentGraph` 只列 `assets/`（`src/electron/electron/handler.cljs` `:getAssetsFiles`），不提供内容读取。
- 插件没有文件系统事件，只有 `DB.onChanged`，无法可靠感知外部写入。
- 唯一可搬运图文件的插件 API 是 `logseq.Git.execCommand`（`src/main/logseq/api.cljs` `exec_git_command`），即 git 方案。

## 附录 B：相关代码索引

| 用途 | 位置 |
| --- | --- |
| 外部文件变化 → DB | `src/main/frontend/fs/watcher_handler.cljs` |
| 全量图加载 / 手动刷新 | `frontend.handler.repo/load-repo-to-db!`、`load-graph-files!` |
| 三方合并（后续） | `src/main/frontend/fs/diff_merge.cljs` |
| 主进程 git（状态目录/调度先例） | `src/electron/electron/git.cljs` |
| FS 协议与平台实现 | `src/main/frontend/fs/protocol.cljs`、`node.cljs`、`capacitor_fs.cljs` |
| 主进程 fetch / IPC 注册 | `src/electron/electron/utils.cljs`、`handler.cljs` |
| 插件系统仅桌面 | `src/main/frontend/config.cljs` |
| 官方同步移除 | PR #29 |
