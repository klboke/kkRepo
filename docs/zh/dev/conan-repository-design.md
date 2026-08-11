# Conan 2 仓库开发设计说明

本文记录 kkrepo Conan 2 仓库格式的落地设计。目标不是把 `conan_package.tgz` 当作 Raw 文件保存，而是在 Conan 2 客户端协议、Sonatype Nexus Repository Conan 2 行为和 kkrepo 当前的关系数据库 + OSS/S3 + 多副本架构之间取兼容交集，并复用已经落地的 Cleanup Policy 与制品安全扫描基建。

## 当前支持状态与落地结论

截至 2026-08-11，本文设计已经落地：kkrepo 已注册 `RepositoryFormat.CONAN` 和 hosted/proxy/group recipe，并完成协议、双数据库持久化、真实客户端、Nexus 黑盒、Cleanup、安全扫描、迁移、Browse/UI 和性能验收。路线图因此在实现 PR 中标记为完成。

落地后的关键边界如下：

- `protocol-conan` 固化 Conan 2 route/reference、version、manifest、conaninfo、storage path 与 Nexus Browse path 投影；Conan 1 仍不在支持范围内。
- Hosted 以 manifest-last 作为 RREV/PREV 原子提交点；proxy/group 使用数据库 lease、repository revision 和 source binding 保证多副本与同 revision 文件一致性。
- MySQL/PostgreSQL 使用同号 V45 migration 与同一 DAO contract；exact/prefix/list/claim/expiry/Browse 查询均有显式高效索引和 query-plan 回归。
- Browse 展示路径在 hosted、proxy 和 migration 的最终写入事务中直接持久化；正常读取不从 storage path 反推，也不靠发布后 backfill 补映射。
- 真实 Conan 2.31.2 已完成 login、upload、list、download/install、proxy/group install；双数据库同机 Nexus 3.94 性能基线全部通过发布门禁，结果见 [性能基线](conan-performance-baseline.md)。

落地结论如下：

- 第一阶段实现 **Conan 2** 的 `conan-hosted`、`conan-proxy` 和 `conan-group`。Nexus Repository 从 3.76.0 开始提供这三类 Conan 2 repository，适合作为兼容参考。
- Conan 1.x 不与 Conan 2 共用 recipe 或数据模型。Nexus 也要求把两代 package 分开；首期只保留 Conan 1 revisions-enabled 客户端的探测用例，不承诺 Conan 1 repository。
- 总体改动量为中到大。难点不在大文件下载，而在 RREV/PREV 层级、多文件 manifest-last 原子发布、latest 指针、group source binding、proxy 可变列表缓存，以及清理和扫描对完整逻辑主体的识别。
- Conan 2 很适合复用现有基础设施：Blob 继续进入 OSS/S3，component/asset、通用制品变更 outbox、Cleanup run、扫描 task、权限、审计和迁移框架继续作为共享能力；Conan 模块只补协议投影和格式适配。
- 所有核心查询先定义访问形状再定义复合索引。实现完成前必须在 MySQL 和 PostgreSQL 的大数据集上保存 `EXPLAIN ANALYZE` 证据，并用同机 Nexus 参考实例做可复现的客户端性能对比。

## 调研基线

实现时按以下顺序确定行为：

1. Conan 2 官方文档和 `conan-io/conan` 对应稳定 tag 的客户端路由、上传顺序及响应解析是协议真相。
2. Sonatype Nexus Repository Conan 文档、repository REST schema 和真实 HTTP 行为是兼容性参考。
3. kkrepo 现有 hosted/proxy/group、Cleanup Policy、安全扫描、迁移和多副本设施是落地基础。

协议关键事实：

- 完整 Conan 2 binary reference 为 `name/version@user/channel#rrev:package_id#prev`。`user`、`channel` 可以省略，HTTP route 中由 `_` 占位。
- Recipe revision（RREV）表示 recipe 与 exported sources 的不可变 revision；package revision（PREV）表示某个 RREV + package ID 下的 binary 内容 revision。
- `package_id` 由 settings、options 和依赖关系等 binary model 信息计算，默认是 `conaninfo.txt` 的 SHA-1。服务端不能用文件名、平台猜测或自己的 profile 重新计算 package identity。
- “latest” RREV/PREV 是服务端发布顺序语义，不是 revision 字符串排序。上传旧 revision 是否刷新 latest 必须由 Nexus 黑盒结果固定。
- Conan 版本规则是 SemVer 的扩展而不是标准 SemVer：允许任意数量的段和字母段，数字按数值比较，pre-release 早于 release，build 也参与 Conan 的顺序。Cleanup 的“保留最新版本”必须使用同一规则。
- Conan 2 客户端通过 `/v1/ping` 读取 `X-Conan-Server-Capabilities`，通过 `/v2/users/authenticate` 交换 bearer token，再访问 `/v2/conans/...`。
- Conan 2 把一个 recipe/package revision 拆成多个文件 PUT。官方客户端先上传 `conan_export.t*` / `conan_package.t*`，最后上传 `conanmanifest.txt`；manifest 成功是 revision 进入可见状态和更新 latest 的提交点。
- 每个 PUT 都携带 `X-Checksum-Sha1`。Conan 还可以尝试 `X-Checksum-Deploy` 去重；Nexus 对该请求的状态和 header 必须先实测，不能照搬 Artifactory 行为。
- 当前客户端可上传 gzip、xz、zstd 三类压缩表示，对应 `conan_export.tgz|txz|tzst`、`conan_sources.tgz|txz|tzst` 和 `conan_package.tgz|txz|tzst`。
- Recipe/package metadata 位于 revision 下的 `metadata/...`，可以在主体 revision 已存在后单独变更；metadata 不改变 RREV/PREV，也不能被当作可执行 package payload。

Nexus 兼容结论：

- Conan 2 proxy、hosted、group 从 Nexus Repository 3.76.0 开始提供；group 是 Conan 2 专属能力。
- Nexus 要求启用 Conan Bearer Token Realm。客户端登录、匿名读取、错误 credential 和 repository 权限的 HTTP 细节由 M0 黑盒 fixture 固定。
- Nexus hosted 可以承载 Conan 1 或 Conan 2，而 proxy 创建时显式选择版本且之后不能修改。迁移 hosted 数据时必须通过 source version 和 content shape 判断代际，不能只看 `format=conan`。
- Nexus UI/Search 会从 `conaninfo.txt` 投影 settings 字段。kkrepo 需要保存有界、可检索投影，但不能把任意 `conaninfo.txt` 内容变成无界 JSON 查询。
- Nexus 文档展示了 hosted/proxy/group 的客户端使用方式，但没有固定全部 v2 status、header、错误正文、latest 冲突、group 重复坐标和 checksum deploy 行为；这些都属于 compat-test 的参考实例事实。
- 2026-08-11 使用官方 Nexus 3.94.0 image 与 Conan 2.31.2 客户端实测了 hosted 上传、Search asset path 和 Repository Browse API。Browse 的展示路径与协议/storage path 不同，具体形状在本文 Browse 章节固定；实现前仍需把同一探针纳入 M0，防止参考版本升级后静默漂移。

## 功能范围

### 第一阶段必须实现

1. 协议与 recipe
   - 新增 `RepositoryFormat.CONAN`、`conan-hosted`、`conan-proxy`、`conan-group` 和独立 `protocol-conan` 模块。
   - 实现 Conan 2 ping、认证、search/list、RREV/PREV、file list、file transfer、delete 和 metadata 路由。
   - 路径、JSON shape、content type、capabilities、status、header 和错误语义由官方客户端测试与 Nexus 黑盒 fixture 固定。

2. Hosted
   - 支持真实 `conan upload`、`conan download`、`conan install`、`conan list`、`conan search` 和 `conan remove`。
   - 文件先写入隐藏 staging asset；只有 manifest 校验完成后，才在短数据库事务中原子提交完整 RREV/PREV 并推进 latest。
   - 支持 recipe archive、exported sources、package archive、`conaninfo.txt`、manifest 和 Conan 2 metadata 文件。
   - 支持 write policy、不可变 revision、幂等重传、管理端上传、Components API、Browse/Search、Usage、审计、Cleanup Policy 和安全扫描。

3. Proxy
   - 默认上游为可配置 Conan 2 remote，例如 `https://center2.conan.io`，并支持 remote Basic/bearer credential、TLS、redirect、outbound policy、negative cache、auto-block 和 stale policy。
   - 可变 search/latest/revision/file-list 响应使用共享 validator + TTL cache；不可变 revision file 校验后进入通用 asset/blob cache。
   - 代理认证只使用仓库配置的上游凭据，绝不把客户端 bearer 原样转发给上游。
   - Audit 模式保持边下载边缓存；安全扫描 Enforce 且要求 pending 阻断时，先完整缓存、提交 outbox、异步扫描，再让客户端重试。

4. Group
   - 按 member 顺序聚合 search、RREV/PREV 和 package list，并对完整 revision 建立 source binding。
   - 同一 RREV/PREV 的 file list、manifest 和 payload 必须来自同一 member；禁止从多个成员拼出表面完整、实际 checksum 不一致的 revision。
   - member revision 变化、删除、cleanup 或顺序调整后使 binding 失效。Group 只读，不创建独立扫描结果，也不能作为 Cleanup Policy target。

5. 产品闭环
   - Admin UI 可创建三类 recipe、配置 proxy/group、查看 latest、缓存和 staging 状态。
   - Browse/Search 展示 recipe、version、user/channel、RREV、package ID、settings、PREV、文件、checksum 和实际 source repository。
   - Nexus definition/content migration 支持 dry-run、resume、checksum、幂等重试、shape gate 和逐仓库报告。
   - Client E2E、Nexus compatibility、Migration E2E、双数据库 contract、双副本 takeover、cleanup/scanning 和性能对比全部进入验收。

### 后续扩展

- Conan 1 hosted/proxy；必须使用独立 recipe 和显式迁移方案，不与 Conan 2 混存。
- 备份第三方 source 的 Conan 2 `backup-sources` 扩展。
- package metadata 的批量管理 UI、签名/证明材料与 promotion workflow。
- 面向超大组织的专用 package settings 搜索索引；首期只索引常用固定字段并对自定义字段做有界精确筛选。
- 跨 repository promotion/copy；必须保持 RREV/PREV、manifest 和 Blob 引用，不通过下载后重新打包改变 revision。

### 明确不实现

- 不执行 `conanfile.py`、generator、hook、binary、build script 或 package 中任意程序。
- 不把 recipe/package archive、manifest 原文或大 metadata 存进 MySQL/PostgreSQL。
- 不把一个 revision 的多个文件作为互相独立、可见即成功的 Raw asset。
- 不依赖单 JVM map 维护 latest、upload session、proxy fill、group binding、negative cache、scan task 或 cleanup 真相。
- 不从 RREV/PREV 字符串猜发布时间，也不把字典序当成 latest。
- 不为兼容错误行为关闭 archive 安全限制；Nexus 接受但会造成路径穿越、资源耗尽或跨仓库读取的输入必须明确记录为安全差异。

## 模块与职责

| 模块 | 设计职责 |
| --- | --- |
| `core` | `CONAN` format、三类 recipe、共享权限与上传契约 |
| `protocol-conan` | route、reference/parser、Conan version comparator、manifest/conaninfo codec、media type 与错误模型 |
| `persistence-jdbc` | Conan DAO、revision/file projection、latest、upload session、group binding、lease 和 auth token contract |
| `persistence-mysql` / `persistence-postgresql` | 同号 schema migration、约束、索引和 contract test |
| `server/conan` | hosted publisher、proxy client/cache、group resolver、认证 exchange、删除和迁移 writer |
| server 通用入口 | Controller、安全过滤、Components API、Browse/Search、Cleanup、扫描、metrics 和 repository 生命周期 |
| `security-scan` / `scanner-adapter` | `CONAN_PACKAGE` 主体、复合输入、Syft/Grype、policy decision 和资源限制 |
| `migration-nexus` | Nexus Conan generation/shape 探测、definition/content plan、writer 和校验 |
| `admin-ui` / `browse-ui` | recipe 配置、上传、浏览、Usage、cache/staging/scan/cleanup 状态 |
| `compat-test` | 官方客户端 fixture、可控 upstream、Nexus/kkrepo 黑盒对照和性能正确性探针 |

Controller 只负责 route、认证上下文、流式 body 和响应适配。Reference 解析、manifest 校验、发布状态机、latest、proxy/group 选择、cleanup 与扫描映射必须位于协议 service 或格式 adapter 中。

## URL 与路由设计

客户端配置示例：

```bash
conan remote add kkrepo \
  https://repo.example.com/repository/conan-group
conan remote login kkrepo "$CONAN_LOGIN_USERNAME" -p "$CONAN_PASSWORD"
conan install --requires=zlib/1.3.1 -r=kkrepo
```

首期路由基线如下；表中的返回 shape 和错误细节仍需 M0 与 Nexus 对照：

| 路径 | 方法 | 语义 |
| --- | --- | --- |
| `/repository/{repo}/v1/ping` | GET | 返回 Conan server capabilities |
| `/repository/{repo}/v2/users/authenticate` | GET | Basic credential 换取短期 bearer token |
| `/repository/{repo}/v2/users/check_credentials` | GET | 校验 bearer 并返回当前用户 |
| `/repository/{repo}/v2/conans/search?q=...` | GET | 搜索 recipe reference |
| `/repository/{repo}/v2/conans/{name}/{version}/{user}/{channel}/search` | GET | 列出 package ID/settings；可带 RREV |
| `/repository/{repo}/v2/conans/{name}/{version}/{user}/{channel}/revisions` | GET | 列出 RREV |
| `.../{user}/{channel}/latest` | GET | 返回 latest RREV 与时间 |
| `.../{user}/{channel}/revisions/{rrev}/files` | GET | 返回 recipe revision 文件清单 |
| `.../{user}/{channel}/revisions/{rrev}/files/{path}` | GET/PUT | recipe/metadata 文件下载或 hosted 上传 |
| `.../revisions/{rrev}/packages/{packageId}/revisions` | GET | 列出 PREV |
| `.../packages/{packageId}/latest` | GET | 返回 latest PREV 与时间 |
| `.../packages/{packageId}/revisions/{prev}/files` | GET | 返回 package revision 文件清单 |
| `.../packages/{packageId}/revisions/{prev}/files/{path}` | GET/PUT | package/metadata 文件下载或 hosted 上传 |
| recipe、RREV、package ID、PREV 路径 | DELETE | 按官方粒度删除；group/proxy 写语义由 Nexus fixture 固定 |

路径约束：

- 只 percent-decode 一次；拒绝编码分隔符、二次编码、空段、点段、反斜杠、NUL/控制字符和越界字段。
- `_` 只在 user/channel segment 表示缺省值；数据库同时保存 display value 与 canonical key，不能把真实 `_` 和缺省状态混淆。
- name、version、user、channel、RREV、package ID、PREV 和 metadata path 的字符集/长度以 Conan 2 parser 与 Nexus reference 的交集为准。
- 文件路径允许受限的 `metadata/...` 子路径，但必须在规范化后仍位于 revision 根下。任何 absolute path、symlink escape 或 Unicode/大小写碰撞都失败关闭。
- 公开 URL 始终位于 `/repository/{repo}/...`，不生成绕过 repository 权限、扫描下载策略或 group source binding 的对象存储直链。

## Identity、latest 与版本顺序

内部 canonical identity：

```text
Recipe       = repository + name + version + user? + channel?
RREV         = Recipe + recipeRevision
Package      = RREV + packageId
PREV         = Package + packageRevision
RevisionFile = RREV|PREV + normalizedRelativePath
```

设计约束：

- `component` 对应一个完整 recipe version：namespace 为 canonical `user/channel`，name/version 保持 Conan 值；全部 RREV/PREV 文件都关联到该 component。这样当前 Cleanup 基建能把一个 Conan version 作为完整逻辑主体，而不是逐文件删除。
- RREV/PREV 都是不可变 identity。相同 identity + 相同 manifest 重传是幂等成功；相同 identity + 不同内容按 write policy 和 Nexus 对照结果拒绝，不覆盖旧 Blob。
- `conan_recipe.latest_recipe_revision_id` 与 `conan_package.latest_package_revision_id` 是事务内指针。读取 latest 不按时间排序扫 revision 表。
- 每个 revision 仍保存数据库 commit time，既用于 revision list 返回，也用于审计和 latest 重建。DB time 是跨副本统一时间源。
- `ConanVersions` 必须以官方版本 range fixture 做 differential test；Cleanup、Search 的 newest 展示和任何版本排序都复用同一 comparator。

## Hosted 多文件原子发布

Conan upload 没有独立 commit endpoint，因此必须利用官方客户端的 manifest-last 顺序构建服务端提交协议：

1. 校验 hosted recipe、write policy、身份、权限、route、文件名、Content-Length 和 `X-Checksum-Sha1`。
2. 请求流写入受限临时文件，同时计算 SHA-1、SHA-256 和 size；验证 header 后写入 OSS/S3 Blob。
3. 在短事务中把 Blob 绑定到 `.conan/staging/{sessionId}/...` asset，并保存 upload session/file 行。大对象传输不持有数据库事务或行锁。
4. session 由 `repository + RREV/PREV coordinate + actor` 标识；coordinate lease 和 fencing token 防止两个副本把不同文件集合提交到同一 revision。
5. 普通文件 PUT 只更新 staging。`conanmanifest.txt` PUT 到达后，解析有界 manifest，要求其声明的全部文件已经存在，逐项核对 SHA-1、路径、重复项和允许的文件集合。
6. Package commit 额外要求 parent RREV 已提交，并解析 `conaninfo.txt`；验证 package ID、常用 settings 投影、archive 表示和 manifest 的一致性。
7. 在一个短事务内锁定 lease、复查 write policy/目标 revision/Blob binding，创建或复用 component，提交 RREV/PREV/file projection、最终 asset 和 Nexus 对齐的 `browse_node` 路径，更新 latest、repository revision，并由通用 Asset DAO 同事务追加 `artifact_change_event`。任一 Browse path 计算、唯一约束或写入失败都回滚整个发布，revision 不能先可见再等待补映射。
8. 提交后解除 staging Blob 引用。失败、超时或节点退出由所有副本可运行的有界 cleanup 按 `expires_at` + fencing 领取；只有最后一个引用消失后 Blob 才进入全局 GC。

Revision 的 file-list、latest、search、Browse 和下载都只读取 `COMMITTED` 投影；staging 永远不可见。Manifest commit 失败时，客户端可以安全重传缺失文件，不产生“列表可见但 archive 404”的半成品。

Metadata 文件不改变 RREV/PREV。它们在 parent revision 已提交后通过独立短事务更新，同时写入对应 `browse_node` asset leaf、保留审计与 Blob 引用，并推进 repository revision 使 group/cache 失效；metadata 不触发 latest 更新，也不作为 Cleanup 独立 subject。Browse 写入失败时 metadata 事务同样整体回滚。

## Archive 与内容安全

- `conan_export.t*`、`conan_sources.t*`、`conan_package.t*` 必须按 magic 而非扩展名识别 gzip/xz/zstd tar。
- 有界校验覆盖压缩字节、展开字节、entry 数、单文件大小、嵌套深度、压缩比、检查时间和并发数。
- 拒绝 absolute/dot path、重复 entry、special file、越界 symlink/hardlink、稀疏文件放大和尾随垃圾；服务端永不执行 archive 内容。
- `conaninfo.txt` 使用有界 parser，只投影允许的 settings/options/requires 字段。未知字段保留在 Blob 原文中，不自动变成 metric label、SQL column 或日志字段。
- RREV 可能使用 content revision 或 SCM revision，不能一律重算成 archive hash。PREV/RREV 的精确合法性和 manifest hash 算法以对应 Conan 稳定 tag 为准。
- 文件下载继续使用 Blob 的 SHA-256 作为内部内容身份，同时保留客户端需要的 SHA-1；所有 checksum 必须在流式读取时计算，不能把大文件加载进堆。

## Proxy 设计

Proxy 把 endpoint 分为两类：

- **Mutable discovery**：search、latest、RREV/PREV list、package search 和 file list。使用 validator、短 TTL、共享 negative cache 和 stale-if-error policy；cache key 包含 repository、method、规范 path 和影响语义的 query。
- **Immutable revision file**：带完整 RREV/PREV 的文件。首次回源完成 checksum/size/manifest 关联后写入通用 asset/blob，此后直接服务共享 Blob。

流程：

1. 校验 outbound URL、DNS/IP、redirect hop、TLS 和 remote credential scope。
2. 用 repository 配置的上游 credential 完成 Conan ping/auth；短期上游 bearer 只加密保存或保存在可丢失的节点缓存中，失效时重新交换，不能成为正确性真相。
3. 同一 cache key 使用数据库 lease/fencing 合并跨副本冷请求。等待者可以读已提交旧值或按 stale policy 返回，不能并发覆盖较新 validator。
4. Mutable JSON 在大小/深度/条目上限内解析、规范化本地 URL，并把可检索的小投影写数据库；原始大响应进入 Blob。
5. Revision file 流式写 Blob 并校验上游 header、manifest 和已知 file-list；校验失败删除新缓存引用并失败关闭。
6. repository state revision 只在新投影提交后推进。节点本地 TTL cache 以该水位 + TTL 失效，丢失只增加 DB/Blob 读取，不改变结果。

Proxy cleanup 只移除本地 revision file cache 与对应 component/asset 投影，不修改上游。Discovery snapshot 可以保留到 TTL；下一次文件请求按已知 coordinate 回源。管理员显式执行 invalidate 时再删除 validator/negative cache。

## Group 设计

Group 的 search/list 聚合与文件读取必须共享 source decision：

- recipe search 去重 canonical reference，member 优先级由 Nexus 黑盒固定；结果分页和上限在合并后执行。
- latest、RREV/PREV list 对每个候选保存 member 与 member repository revision。相同 revision 冲突时默认选前置 member，但实际策略必须由 Nexus fixture 固定。
- 第一次命中完整 RREV/PREV 时写 `conan_group_binding`。后续 file list 和所有 file GET 校验 member revision 水位后走同一 member。
- 若绑定 member 删除、离线、cleanup、revision 改变或不再返回完整 manifest，绑定失效并从头解析；不能静默从后置 member 补单个缺失文件。
- nested group 做循环检测和有界深度。Group 不复制 member asset、SBOM、Cleanup usage 或安全状态；下载计入实际 member，同时叠加入口 group policy。

## 数据模型与索引

大 Blob、manifest 原文、archive、原始 discovery JSON 和扫描结果文档继续放 OSS/S3。数据库只保存 identity、状态、checksum、有限搜索投影、lease 和 Blob/asset 引用。

建议的 Conan 专用表和关键索引如下；MySQL/PostgreSQL migration 使用同号版本并通过同一 DAO contract：

| 表 | 关键约束与索引 | 热查询 |
| --- | --- | --- |
| `conan_recipe` | `UNIQUE(repository_id, coordinate_hash)`；`idx_conan_recipe_search(repository_id,name_key,user_key,channel_key,version_key,id)` | exact recipe、search keyset、component 映射 |
| `conan_recipe_revision` | `UNIQUE(recipe_id,rrev_hash)`；`idx_conan_rrev_list(recipe_id,published_at,id)` | RREV list、latest 重建 |
| `conan_package` | `UNIQUE(recipe_revision_id,package_id)`；`idx_conan_package_list(recipe_revision_id,id)`；平台/编译器索引都以 `recipe_revision_id` 开头 | package list、常用 settings 过滤 |
| `conan_package_revision` | `UNIQUE(conan_package_id,prev_hash)`；`idx_conan_prev_list(conan_package_id,published_at,id)` | PREV list、latest 重建 |
| `conan_revision_file` | `UNIQUE(owner_kind,owner_id,path_hash)`；`idx_conan_file_list(owner_kind,owner_id,id)`；`UNIQUE(asset_id)` | file list、exact file、反查扫描主体 |
| `conan_repository_state` | `PRIMARY KEY(repository_id)` | repository revision/CAS 水位 |
| `conan_upload_session` | coordinate/actor active unique；`idx_conan_upload_claim(status,lease_until,id)`；`idx_conan_upload_expiry(expires_at,id)` | resume、takeover、staging cleanup |
| `conan_upload_file` | `UNIQUE(session_id,path_hash)`；`UNIQUE(asset_id)` | manifest commit 批量校验 |
| `conan_coordinate_lease` | `PRIMARY KEY(repository_id,coordinate_hash)`；`idx_conan_lease_expiry(expires_at)` | publish/proxy fill fencing |
| `conan_group_binding` | `UNIQUE(group_repository_id,binding_kind,coordinate_hash)`；`idx_conan_group_member(member_repository_id,member_revision,id)`；`idx_conan_group_expiry(expires_at,id)` | group resolve、member 失效 |
| `conan_auth_token` | `PRIMARY KEY(token_hash)`；`idx_conan_token_expiry(expires_at,id)`；`idx_conan_token_subject(subject_source,subject_user_id,id)` | bearer auth、bounded cleanup/revoke |
| 共享 `browse_node` | `UNIQUE(repository_id,path_hash)`；`UNIQUE(asset_id)`；`idx_browse_node_parent(parent_id)`；`idx_browse_node_root(repository_id,depth,path_hash)` | root/child Browse、asset/component 定位 |

`coordinate_hash`、`rrev_hash`、`prev_hash` 和 `path_hash` 只用于控制索引宽度。发生 hash 命中后必须比较原始 canonical 字段，不能把理论碰撞当作同一对象。

数据库访问规则：

- 所有 list/search/cleanup/backfill 都使用稳定 keyset，不使用随页数增长的 `OFFSET`。
- exact coordinate 必须从 repository + hash/normalized key 进入唯一索引；禁止从 asset path 做 `%...%` 扫描恢复 identity。
- package settings 查询先锁定 exact RREV，再使用以 `recipe_revision_id` 为首列的索引；自定义 settings 只能在有界候选页内解析，不能对全表 JSON 做无索引 predicate。
- 批量 file、asset、usage 和 scan state 查询按 ID 分批，避免 N+1 和超大 `IN`；默认 batch 上限 500，可配置但有硬上限。
- Browse 必须从 `repository_id + depth` 或 `parent_id` 索引进入，exact path 先以 `repository_id + path_hash` 定位并复核原始 path；禁止按 path 前缀扫描 `asset`/Conan 表，也禁止在请求线程临时遍历 RREV/PREV 拼树。
- claim/takeover 使用数据库时间、短事务、`FOR UPDATE SKIP LOCKED` 与 fencing token。旧 owner 的 commit/heartbeat 必须因 token 不匹配失败。
- migration 上线前对 MySQL/PostgreSQL 保存关键 SQL 的 `EXPLAIN ANALYZE`；选择性查询不得出现全表扫描，扫描行数必须与 page/batch/result 上限同阶。

## Cleanup Policy 接入

Conan 直接接入当前 `CleanupSubjectScanner`、`CleanupPolicyCapabilities`、`CleanupUsageTrackingService`、`CleanupExecutionService` 和 `RepositoryContentDeletionService`，不另建 Conan 定时删除系统。

主体语义：

```text
subject       = 完整 recipe version（全部 RREV、package ID、PREV 和 revision files）
family        = canonical user/channel + recipe name
version       = Conan version
usage         = subject 内任一已解析 payload 的最后下载水位最大值
publishedAt   = subject 内最新一次 committed revision/file 变更时间
contentToken  = recipe state revision + component/asset/blob/usage revision 摘要
```

选择完整 recipe version 是为了保持当前 Cleanup 的“完整逻辑制品版本”契约。单独删除某个 PREV 仍由 `conan remove` / Browse 管理删除提供，但 Cleanup 的 retain N、age、usage 和 pattern 不把同一个 version 的不同 binary configuration 分开排名。

接入要求：

- `CleanupPolicyCapabilities` 注册 `ConanVersions.COMPARATOR`；不能复用 Maven/SemVer comparator。
- Conan component 关联一个 version 的全部 committed asset；generated file-list、latest、group binding、auth token 和 staging 不成为独立 subject。
- Try Run 的扫描页从 `conan_recipe`/component keyset 索引进入，并用聚合 SQL 取得 asset count、bytes、published/usage 水位；禁止为每个 component 把全部 file 行加载进堆。
- Execute 先取得 repository cleanup lease，再锁定 recipe state 和相关 asset/usage，复查 content token/protection/fencing；按稳定 ID 分批解除引用，但对外可见删除、latest 重算、repository revision 与 tombstone 在一个事务边界内完成。
- Hosted 删除完整 version 后，search/latest/RREV/PREV/file list、Browse/Search 和 group binding 同步失效；Blob 仍由通用引用与 GC 异步回收。
- Proxy 删除只清本地缓存；Group 不能绑定 Cleanup Policy。Group 下载写入实际 member usage，并由入口 group + member 的安全策略共同判定。
- RUNNING/PENDING 安全扫描通过带 freshness deadline 的 `CleanupProtectionProvider` 或 scanner Blob reference 保护 subject；过期 protection 不永久阻止清理。一个 package 正在扫描时，保守保护整个 recipe version，保证 Cleanup 原子性。
- Cleanup 与 upload/delete/scan race 在双数据库 contract 中验证：新下载推进 usage 后旧 Try Run 必须 stale，manifest commit 后旧 content token 必须 skip，旧 fencing owner 不能提交删除。

## 制品安全扫描接入

Conan 复用现有部署 capability gate、通用 `artifact_change_event`、candidate/task lease、Syft/Grype adapter、SBOM 复用、policy/waiver、Audit/Enforce 和下载策略，不在上传请求中同步调用 scanner。

扫描主体定义为一个完整 package revision：

```text
subjectKind       = CONAN_PACKAGE
repositoryId
componentId
recipeRevisionId
packageRevisionId
archiveAssetId / archiveBlobId / archiveSha256
conanInfoAssetId / conanInfoSha256
name / version / user? / channel? / rrev / packageId / prev
classification    = PACKAGE
inputSchema        = conan-package-v1
```

需要独立 `CONAN_PACKAGE`，不能直接复用 `ASSET_BLOB`：`conan_package.t*` 是实际 binary archive，而 Syft 的 `conan-info-cataloger` 还需要同一 PREV 的 `conaninfo.txt`。SBOM content fingerprint 必须包含 archive SHA-256、conaninfo SHA-256 和 input schema；同一 archive 在不同 package identity 下不能错误复用声明型结果。

具体流程：

1. Manifest commit 使用公共 Asset DAO 写入通用 outbox。Conan candidate projector 将 package archive 或同一 PREV 的 `conaninfo.txt` 变化都归并到 archive asset 上的一个 generation。
2. Classifier 只接受 committed PREV 的唯一 `conan_package.tgz|txz|tzst`；recipe archive、sources、manifest、file-list、metadata、staging 和 checksum 信息均为 `NOT_APPLICABLE`。
3. Scanner contract 增加受限的 Conan 复合输入：multipart 的主 part 传 package archive，第二个小 part 传已校验 `conaninfo.txt` 及其 checksum/size；不把任意数据库 JSON 放进 header。
4. Adapter 在现有 archive guard 下展开 package，把 canonical `conaninfo.txt` 放入扫描根，运行 `conan-info-cataloger` 与 binary/language cataloger，并验证结果至少包含预期 name/version/package ID 对应的 Conan component。
5. 扫描器不执行 package binary、recipe 或 build script；archive、sidecar、展开目录、进程树、输出和超时继续受现有 resource budget/cancellation 控制。
6. 下载任一 PREV payload 时，协议 service 先解析真实 package revision/member，再调用 `ArtifactDownloadPolicy`；Group 复用 member 的扫描状态，不复制 SBOM。
7. Proxy Audit 保持首次响应；Enforce 且 pending 必须阻断时先缓存完整 PREV、提交 outbox 后返回经真实 Conan 客户端验证的可重试错误，扫描通过后重试命中 Blob。
8. 删除 package/version 时 candidate 与 asset state 随引用清理；不可变 SBOM/scan run/finding 按现有 retention 保留，不能删除仍由其它相同 fingerprint 主体复用的结果。

安全扫描兼容矩阵与设计文档同时补一行 Conan：扫描 `conan_package.t* + conaninfo.txt`，默认跳过 recipe/source/manifest/metadata。Scanner capability digest 必须包含 Conan input schema 和所用 cataloger，滚动升级时旧 adapter 不支持 Conan 不能领取该 task。

## 认证、权限与 secret

- `/v2/users/authenticate` 接受 HTTP Basic，通过现有 realm 验证后返回短期 opaque bearer。明文 token 只返回一次，数据库只保存 SHA-256 hash、主体引用、repository scope 和过期时间。
- `ConanBearerTokenService` 复用 `DockerAuthService` 的 hash-only、DB truth、有界过期清理和 stored-subject 恢复模式，但使用独立 `conan_auth_token`，不把 Conan scope 塞进 Docker 表。
- `GenericToken`/CI token 可以作为 Basic password 或由客户端支持的 credential 使用，交换出的 bearer 仍受原 token scope、expiry、disabled 与当前权限约束。
- 匿名仓库可以在未登录时读取，但显式错误 credential 不能降级为 anonymous。写入、删除和 metadata 修改始终要求对应 repository permission。
- Content selector 同时匹配 protocol route 和 canonical Conan coordinate；不能只检查原始 URL 后再允许双重编码绕过。
- 上游 username/password/bearer 使用现有 SecretCipher 加密；客户端 bearer、Basic header、上游 token、manifest 内容和带凭据 URL 不进入日志、metric label、trace 或 CI artifact。
- token/staging/lease cleanup 在每个副本运行小批次，数据库 claim 保证幂等；进程内缓存只允许保存已验证 token 的短 TTL 热结果，权限/状态 revision 变化后失效。

## Browse、Search、Components API 与运维

### Nexus 对齐的 Browse 展示路径

Nexus 3.94.0 在真实 Conan 2.31.2 hosted 上传后的 Repository Browse API 中使用以下展示层级：

```text
{user 或 _}/{name}/{version}/{channel 或 _}#{rrev}
  ├── {recipeFile}
  └── packages/{packageId}/revisions/{prev}/files/{packageFile}
```

例如 `browseprobe/1.2.3@acme/stable` 的 recipe 文件展示为：

```text
acme/browseprobe/1.2.3/stable#{rrev}/conanfile.py
acme/browseprobe/1.2.3/stable#{rrev}/packages/{packageId}/revisions/{prev}/files/conan_package.tgz
```

缺省 user/channel 按 Nexus 保留 `_` 占位，因此 `defaultprobe/0.4` 展示为 `_/defaultprobe/0.4/_#{rrev}/...`。UI 可以在详情中解释 `_` 表示缺省值，但 path segment、面包屑、复制路径和 Browse API `id` 不替换成自创的 `default`。`#` 在数据库 canonical path 中保留原字符，在 URL/query 中正常 percent-encode。

这与 Nexus Search Assets 返回的 storage path 是两个明确投影；后者保持协议形状：

```text
/conans/{name}/{version}/{user 或 _}/{channel 或 _}/revisions/{rrev}/files/{recipeFile}
/conans/{name}/{version}/{user 或 _}/{channel 或 _}/revisions/{rrev}/packages/{packageId}/revisions/{prev}/files/{packageFile}
```

metadata 的子路径、排序、component/asset node type 与 group 重复坐标继续由 M0 fixture 固定；除已记录的产品或安全差异外，kkrepo 的展示 segment 和层级以 Nexus 当前结果为准。

### 写入时投影，不做事后猜测

- `ConanBrowsePathProjector` 是 `protocol-conan` 内的纯函数，只接受已经通过 parser 校验的 canonical reference、RREV/PREV、owner kind 和 relative file path；不能从原始 request path、asset path 字符串切割或 Search JSON 反推坐标。
- Hosted 的 manifest commit 先为 `{user}/{name}/{version}/{channel}#{rrev}` 写 component node，再为 recipe/package 文件写 asset leaf。component、asset、Conan file row、`browse_node`、latest 和 outbox 必须处于同一个数据库事务；Browse 写入失败时保持 staging，不能发布半成品。
- Proxy 只在 immutable revision file 校验并提交本地 asset 时同步写同一投影；Nexus migration 也必须通过同一个 typed importer/projector 写入。不得先导入 asset，再依赖定时任务、启动 backfill 或首次打开 Browse 时补路径。
- Group 不复制 member 的 `browse_node`；Browse 合并 member 已提交节点并携带实际 source repository，仍受 source binding 约束。
- `browse_node` 可以按 typed Conan rows 做灾难恢复重建，但这只是显式 repair 能力，不是正常发布的完成步骤；repair 禁止通过 `%...%` asset path 扫描或启发式映射弥补写入遗漏。
- contract test 必须注入 component node/asset leaf 写入失败，证明事务回滚后 RREV/PREV、latest、Search 和 Browse 都不可见；重试走同一幂等写入路径。

Search 至少支持 format、name、version、user、channel、RREV、package ID、PREV、os、arch、compiler、compiler version、build type、checksum 和 source repository。

管理上传与 Components API 不接受一个无法证明 identity 的任意 tarball。推荐两种入口：

- 上传由 `conan cache save`/等价导出生成的完整 bundle，服务端拆分后仍走同一 manifest commit service。
- multipart 显式提供 reference、RREV/PREV、archive、manifest 与 `conaninfo.txt`；缺少必需文件时返回可操作错误。

UI/API 删除调用 Conan mutation service，不能直接删 asset 行。Usage 页面给出 remote add/login/install 示例，不回显真实 password/token。

低基数指标建议：

- `kkrepo_conan_requests_total{repository,type,operation,status}`
- `kkrepo_conan_publish_total{repository,subject,result}`
- `kkrepo_conan_proxy_requests_total{repository,kind,outcome}`
- `kkrepo_conan_group_resolve_total{repository,kind,outcome}`
- `kkrepo_conan_staging_items{repository}`
- `kkrepo_conan_cleanup_items_total{repository,subject,outcome}`
- 通用 scan/task/policy metric 继续用 `format=conan`，不新增坐标级 label。

name、version、user/channel、RREV、package ID、PREV、path、token 和 remote URL 不进入 metric label；坐标级诊断留在权限受控的 Browse、task 和 audit detail。

## 多副本、一致性与故障语义

- latest、committed revision、upload session、proxy validator、group binding、token、cleanup/scan task 和 repository revision 全部以数据库为真相；Blob 以 OSS/S3 为真相。
- 节点本地缓存只缓存 immutable file descriptor、repository runtime、权限结果和短 TTL discovery 响应，key 中包含 DB revision/watermark；丢失或驱逐不能改变可见结果。
- upload/proxy fill/group refresh 使用 coordinate lease + fencing。长时间网络/对象存储 I/O 在事务外，最终提交事务重新验证 lease、write policy、repository online 和 parent revision。
- 一个副本在 archive 上传后、manifest 前崩溃，只留下不可见 staging；另一个副本可以在 lease 过期后接管或 cleanup。
- 一个副本在事务提交后、staging unlink 前崩溃，最终 asset 已经可见且同时保留 Blob 引用；cleanup 只解除多余 staging 引用。
- Proxy 上游失败时按 stale policy 返回最后一个已验证 snapshot；从未成功的 coordinate 不伪造空列表。认证失败、checksum drift 和协议损坏不进入 negative cache。
- DB 不可用时不发布、不更新 latest、不执行 cleanup/scan claim。已有 immutable Blob 是否允许读取沿用全局数据库故障策略，不能由 Conan 单独 fail open。
- 所有 background cleanup/reconcile 都有 batch、max batches、grace、lease、metric 和 kill switch；定时器只是唤醒器，不能把节点本地 schedule 当作任务真相。

## Nexus 迁移设计

Definition migration：

- 识别 Nexus `conan-hosted`、`conan-proxy`、`conan-group`、Conan version、write policy、remote、blob store、成员和顺序。
- Proxy/Group 只有 source 明确为 Conan 2 时自动映射。Conan 1 definition 报告 `MANUAL`，不能静默创建 Conan 2 repository。
- Hosted 没有显式 version selector时，通过 Nexus version、datastore/content model、asset route 和 revisions shape 判定；混合或未知 shape 失败关闭。
- 加密或 masked remote secret 不能恢复时要求管理员重新输入，不生成占位 credential，也不把计划标成 `FULL`。

Content migration：

- 只迁移已经 committed、manifest 完整且 checksum 可验证的 RREV/PREV；Nexus browse/search 派生数据和 auth bearer 不迁移。
- 每个 revision 先在事务外读取/校验 Blob 和 manifest，再通过同一 hosted importer、`ConanBrowsePathProjector` 和原子发布事务幂等提交；checkpoint 使用 source repository + stable source identity。迁移不能把 source asset path 直接当作 Browse path，也不能把 Browse 投影留给导入后的 backfill。
- latest 指针由 source 可证明的 published order 重建；缺少顺序证据时报告差异并要求显式策略，不能按 RREV/PREV 字符串猜。
- Hosted content 在 source version/shape gate 通过后支持 full migration。Proxy cache 只有管理员显式选择且 shape 可证明时迁移；group binding、negative cache、token、lease 和本地热缓存全部重建。
- Dry-run 报告 recipe、RREV、package ID、PREV、file、bytes、invalid/incomplete/conflict 计数和预估 Blob 复用；resume、重跑与跨副本 worker 接管保持幂等。
- Migration E2E 从 Nexus PostgreSQL reference 发布可安装 dependency graph，迁移到 MySQL/PostgreSQL target，再以新 Conan 2 客户端 install/list/download 校验 exact revision、checksum 和 lockfile 重放。

## 性能与 Nexus 对比验收

Conan 实现不以“功能正确”替代性能验收。实现 PR 必须新增 `scripts/perf/compare-conan-nexus.py` 和结果文档 `docs/zh/dev/conan-performance-baseline.md`，方法参考现有 APT/Nexus 基线但使用真实 Conan 2 client 与 v2 HTTP 热路径。

### 对比环境与方法

- Reference 固定为实现时 compatibility lane 使用的 Nexus 版本，最低 3.76.0；报告必须记录精确版本、license/edition、JVM、PostgreSQL、Blob store 和 repository 配置。
- Candidate 使用同机同资源的 kkrepo PostgreSQL 部署做主对比，因为 Nexus Conan 2 revisions 对 PostgreSQL 有明确要求；kkrepo MySQL 另跑相同正确性与查询计划，不把不同数据库结果混成一个比例。
- 两端使用相同 recipe/package bytes、相同权限、相同 Blob store 类型、相同 TLS/反向代理层。Proxy 使用本地可控 upstream，排除公网抖动。
- 每个 HTTP 场景先预热至少 32 次，再在并发 16 下请求至少 250 次，执行 3 轮并交替 Nexus/kkrepo 顺序；报告三轮中位数的 req/s、p50、p95、错误率和传输 MiB/s。
- 每次测量前验证 status、JSON 语义、file-list、manifest、archive 逻辑 tar tree/member checksum 和实际 `conan install` 结果，禁止把错误页、empty result 或不同 payload 计入性能。独立上传导致的 gzip/tar container metadata 差异不应误判为内容差异。

### 必测场景

1. `v1/ping`、latest、RREV/PREV list 和小 file-list 热读。
2. recipe search：精确命中、前缀/通配命中和达到服务端上限的大结果。
3. 一个 hot RREV 下 10,000 个 package ID 的 package search/list。
4. Browse root、深层 RREV、10,000-package 热点目录和 package `files` 目录；逐层校验 Nexus 对齐的 path/node type，并报告每次展开的 SQL 数、rows examined、req/s 与 p95。
5. 4 MiB 与 256 MiB `conan_package.t*` 的 GET、64 KiB Range，以及已由 Nexus 3.94 fixture 固定的 HEAD 404 行为。
6. Hosted 上传一组 recipe + 8 个 binary package，包含 manifest commit、Browse 同事务投影、幂等重传和 16 并发不同坐标。
7. Warm group install；成员含重复 reference 时验证 source binding 不以错误缓存换吞吐。
8. Controlled upstream 的 proxy cold fill、warm hit、revalidate、404 negative cache 和 upstream 5xx stale。
9. 真实 `conan install` dependency graph：25 个 direct/transitive package，冷客户端 cache 与热服务端 cache 各一轮。
10. 安全扫描 capability disabled 与 enabled/Audit 两组 hosted upload；同步 outbox 开销单独报告，scanner 异步耗时不混入上传延迟。
11. Cleanup Try Run/Execute 与 16 并发 install 同时运行，报告 foreground p95、cleanup subjects/s、锁等待和 stale/skip。

### 发布门禁

- 所有场景成功率为 100%，响应语义、checksum 和客户端结果先于速度通过。
- 热 metadata/search/list 与真实 install 的 kkRepo 吞吐不得低于同机 Nexus 的 `0.80x`，p95 不得高于 Nexus 的 `1.25x`。
- 大 package GET/Range 吞吐不得低于 Nexus 的 `0.90x`，p95 不得高于 `1.15x`；若 Blob store 本身成为瓶颈，仍需给出两端同存储的证据。
- Hosted manifest commit 的 p95 不得高于 Nexus 的 `1.25x`；开启通用扫描 outbox 后、scanner 异步且无 backpressure 时，上传 p95 相对 kkRepo disabled 基线增长不得超过 10%。
- Browse root/child 展开使用普通热 metadata 门禁；每次请求必须命中 `idx_browse_node_root`、`uk_browse_node_path` 或 `idx_browse_node_parent`，SQL 数保持常数级，不能随祖先深度、repository 总 asset 数或同级未返回节点数增长。
- Proxy cold fill 总 p95 不得高于 Nexus 的 `1.25x`；warm hit 使用普通热读门禁。Enforce 首次阻断是产品语义，单独测量，不与 Nexus Audit 路径直接比较。
- 10,000 package ID 和大 search 结果必须保持有界堆；压测期间不得出现 OOM、unbounded queue、一次性全表 materialization 或请求结束后的后台全仓库投影。
- 大仓库数据集至少包含 10,000 recipe version、每个 2 个 RREV、8 个 package ID、2 个 PREV 和 4 个 file，以及一个 10,000 package ID 的热点 recipe。关键 SQL 在 MySQL/PostgreSQL 都命中本文声明的索引。
- Exact coordinate/claim/file-list/cleanup/scan candidate 查询不得出现不必要的全表/顺序扫描；keyset 页的 examined rows 与 `limit + 1` 同阶。高选择性计划退化、临时表/外部排序或 rows estimate 严重漂移都阻止发布。
- Cleanup 每批事务和 scan candidate/backfill 页必须有硬上限；foreground p95 相对无后台任务基线增长不得超过 20%，且不能出现死锁未重试、长事务或旧 fencing owner 成功提交。

如果某项未达门禁，先用 profile、statement digest 和 `EXPLAIN ANALYZE` 找到瓶颈并复测；不能只在文档写“网络抖动”后豁免。确需变更阈值时必须在实现 PR 中列出 Nexus/kkrepo 原始结果、正确性证据、风险和明确批准记录。

## 测试与兼容性矩阵

### M0：Nexus reference 基线

在注册可创建 recipe 前，先对当前 Nexus PostgreSQL reference 固定：

- Conan 2 hosted/proxy/group repository REST schema、默认值、版本不可变字段和 group member 规则。
- ping capabilities、auth/check credential challenge、anonymous、错误 Basic/Bearer、permission denied 与 content selector。
- search、package search、RREV/PREV list/latest、file list 的 JSON、排序、大小写、limit 和空/缺失语义。
- 使用真实 Conan 2 客户端上传带 user/channel 与缺省 user/channel 的 recipe/package，再逐层记录 Repository Browse API 的 `id`、`text`、node type、排序、component/asset linkage，以及 Search Assets 的 `/conans/...` storage path；fixture 至少覆盖 recipe file、`packages/{packageId}/revisions/{prev}/files`、metadata 和 `_` 占位。
- gzip/xz/zstd recipe/package 上传、`X-Checksum-Sha1`、`X-Checksum-Deploy`、manifest-last、重传、force、write policy 和 incomplete upload。
- GET/Range、HEAD 404、ETag、Last-Modified、Content-Length、Content-Type、conditional request、404/409/5xx body。
- exact recipe/RREV/package/PREV delete 后 latest 重算、file 可见性、group/cache 失效。
- proxy auth、redirect、mutable TTL/validator、negative cache、stale、checksum drift 和上游代际不匹配。
- group duplicate reference/revision、成员顺序、成员故障、nested group 与 source consistency。

只有 Date、request ID、token、timestamp 等已证明非确定的字段可以规范化；path、status、capabilities、checksum、revision identity、file list 和真实客户端结果不得无依据放宽。

### 协议、数据库与安全测试

- Reference/parser/version/manifest/conaninfo 的 golden、round-trip、property-based 和官方 Python implementation differential test。
- 恶意 path、duplicate file、manifest mismatch、错误 SHA-1、truncated/伪装 archive、tar bomb、link escape、超限 metadata 和 slow input。
- MySQL/PostgreSQL contract 覆盖 unique/FK、latest CAS、manifest commit、Browse 同事务投影及失败回滚、idempotent retry、package parent、keyset、claim/skip locked、lease/fence、token expiry 和 group invalidation。
- 双副本上传同一/不同 revision、proxy cold fill、group refresh、staging cleanup、worker crash/takeover 和 repository delete protection。
- Cleanup comparator/family/subject、Try Run、usage、protection、content token stale、完整 version 删除和 proxy refill。
- 扫描 classifier、复合 archive + conaninfo fingerprint、adapter contract、catalog result、Audit/Enforce、group member policy、cleanup race 和 scanner crash/retry。
- 大数据集 query plan、batch 上限、heap/temporary disk、SQL count、statement digest 和索引回归测试。

### 真实 Conan 客户端 E2E

至少覆盖 Nexus 声明的 Conan 2 最低兼容线、当前稳定版和实现时最新稳定版：

1. 生成带 transitive dependency 的 C/C++ fixture，并创建 Linux/macOS/Windows 风格 settings 的多个 package ID。
2. 登录 hosted，上传 recipe 与 binaries，清空客户端 cache 后通过 group install/build；lockfile 固定 RREV 后可重复安装。
3. `conan list/search/download` 覆盖 all/latest/explicit RREV/PREV、package query 和 metadata。
4. 幂等上传、force、断线后续传、manifest 缺失、checksum 错误和 write policy。
5. Hosted 删除 PREV/RREV/version 后 latest、list 和 install 行为正确；Cleanup 删除旧 version 后新 version 仍可安装。
6. Proxy ConanCenter 的可控 mirror，验证 cold/warm/offline/stale/404/auth/checksum drift；不得依赖公网作为 CI 正确性真相。
7. Group hosted + proxy、duplicate coordinate、成员顺序变化和另一个副本读取。
8. Basic、GenericToken/CI token、Conan bearer、anonymous、过期/撤销 token 和错误 credential 不降级。
9. Audit 下载不改变客户端；Enforce pending/blocked 的错误与重试由真实客户端固定。
10. 两个 kkrepo 副本间上传、latest、读取、cleanup/scan takeover 与 rolling restart 保持一致。

### 迁移 E2E

- 在 Nexus Conan 2 hosted/proxy/group 创建 dependency graph，保存 list/lockfile/install 与 checksum 基线。
- 迁移 definition 与 hosted content 到 MySQL/PostgreSQL target，再次执行 exact revision download 与 lockfile install。
- 验证 dry-run、resume、重复运行、worker takeover、checksum、计数、latest、权限和跨副本读取。
- 验证 Conan 1、混合/未知 hosted shape、缺失 manifest、损坏 Blob、masked remote secret 和未选择 proxy cache 都 fail closed。

## 实施顺序

1. M0 与 protocol foundation
   - 增加 Nexus Conan 2 black-box probe、官方客户端 fixture、可控 upstream。
   - 新增 `protocol-conan` 的 route/reference/version/manifest/conaninfo 契约与测试。
   - 此阶段不注册可创建 recipe。

2. Hosted persistence 与原子发布
   - 增加双数据库 schema/DAO/索引、format/recipe、upload session、lease/fence 和 manifest commit。
   - 完成 auth exchange、file list/transfer、latest、search/list/delete 和真实 hosted E2E。

3. Cleanup、安全扫描与产品面
   - 接入 component/asset、Cleanup comparator/subject/deletion、usage/protection。
   - 增加 `CONAN_PACKAGE`、复合 scanner 输入、Audit/Enforce、Admin/Browse/Search、Components API 和 metrics。

4. Proxy 与 group
   - 接入共享 cache、validator/negative/stale、outbound security、group aggregate/source binding 和多副本冷请求。
   - 完成可控 upstream、offline、duplicate coordinate 和真实 client E2E。

5. 迁移与性能门禁
   - 增加 Nexus shape gate、definition/content writer、dry-run/resume/checksum 报告和 Migration E2E。
   - 增加 Conan/Nexus 性能脚本、双数据库大仓库 query-plan gate 和基线文档；达到本文阈值后才能把路线图标记为完成。

## 验收标准

- `conan-hosted`、`conan-proxy`、`conan-group` 可创建、编辑、停用和删除；不把 Conan 1 内容导入 Conan 2。
- 真实 Conan 2 客户端可以 login、upload、list/search、download、install 和 remove；lockfile 固定的 RREV 可重放。
- Manifest 提交前 revision 完全不可见；提交后 file list、archive checksum、latest、Browse/Search 和 component/asset 一致。Browse 路径符合 Nexus fixture，并在 hosted/proxy/migration 写入事务中完成，不依赖发布后的 backfill、首次读取或 asset path 猜测。
- 同 identity 重传幂等，冲突内容、损坏 manifest、错误 checksum、恶意 archive 和资源超限输入失败关闭。
- Proxy 的 mutable/immutable cache、validator、negative/stale、auth、redirect 和 auto-block 工作；Group 的全部文件来自同一 source binding。
- Cleanup 以完整 recipe version 为 subject，使用 Conan version comparator；删除后不残留 list 指向 404，proxy 可安全回源，group binding 正确失效。
- 安全扫描以完整 PREV 为 `CONAN_PACKAGE`，archive + conaninfo fingerprint、Syft/Grype、Audit/Enforce、waiver 和 group member 策略可验证；上传线程不调用 scanner。
- 所有持久状态支持多副本 claim/takeover/fencing；节点本地 cache 丢失不影响正确性。
- MySQL/PostgreSQL contract、真实客户端、Nexus 黑盒、Migration E2E、cleanup/scanning race 和恶意输入测试全部通过。
- 大数据集关键查询命中高效索引，无 unbounded scan/materialization；同机 Nexus 性能对比达到本文发布门禁并提交可复现原始结果。
- Nexus migration 对 Conan 1、未知 shape、不完整 revision、损坏 Blob 和不可恢复 secret 失败关闭，不生成虚假 `FULL` 结果。
- 上述实现与验收闭环已经完成，路线图在实现 PR 中给 Conan 加 `✅`；后续新增客户端版本、Nexus 版本和更大生产数据集继续作为回归/容量验证扩展，不再阻塞格式可用性。

## 参考资料

- [Conan 2 Revisions](https://docs.conan.io/2/tutorial/versioning/revisions.html)
- [Conan 2 Version Ranges and Ordering](https://docs.conan.io/2/tutorial/versioning/version_ranges.html)
- [How Conan `package_id` Is Computed](https://docs.conan.io/2/reference/binary_model/package_id.html)
- [Conan 2 Upload Command](https://docs.conan.io/2/reference/commands/upload.html)
- [Conan 2 List Command](https://docs.conan.io/2/reference/commands/list.html)
- [Conan 2 Remote Configuration](https://docs.conan.io/2/reference/config_files/remotes.html)
- [Conan 2 Client REST Routes, tag 2.31.2](https://github.com/conan-io/conan/blob/2.31.2/conan/internal/rest/rest_routes.py)
- [Conan 2 REST Client Upload/Download Behavior, tag 2.31.2](https://github.com/conan-io/conan/blob/2.31.2/conan/internal/rest/rest_client_v2.py)
- [Sonatype Nexus Repository: Conan Repositories](https://help.sonatype.com/en/conan-repositories.html)
- [Sonatype Nexus Repository: Browsing Repositories](https://help.sonatype.com/en/browsing-repositories.html)
- [Syft Package Catalogers](https://oss.anchore.com/docs/guides/sbom/catalogers/)
- [kkRepo Cleanup Policy 开发设计说明](cleanup-policy-design.md)
- [kkRepo 制品安全扫描开发设计说明](security-scanning-design.md)
