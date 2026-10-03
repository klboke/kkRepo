# Git Large File Storage (LFS) 仓库开发设计说明

本文设计 kkRepo 的 Nexus-compatible Git LFS hosted 仓库：Git 服务保存提交和 pointer，kkRepo 保存大文件内容，并通过 Git LFS 官方 Batch API 与 basic transfer 协议完成传输。元数据、权限和协同状态持久化到 MySQL/PostgreSQL，文件内容保存在 OSS/S3。

## 当前状态与范围

Git LFS 仍处于 **Planned / 设计阶段**。当前代码没有 `RepositoryFormat.GITLFS`、`gitlfs-hosted` recipe 或面向 Git LFS 客户端的上传入口。本文中的模块、表、路由、配置和测试是实现计划，不代表已发布能力。本次设计未运行 Nexus Git LFS 黑盒或真实 Git LFS 客户端验收。

首期目标：

- 独立 `gitlfs` format、`gitlfs-hosted` recipe 和 `protocol-gitlfs` 模块。
- Nexus 风格的 `/repository/{repo}/info/lfs` 客户端入口、Batch upload/download、basic PUT/GET，以及被声明时可用的 verify action。
- SHA-256/长度校验、不可变对象、重复上传幂等、多副本上传和故障恢复。
- Basic 认证、现有仓库权限/Content Selector/CI token 接入、Admin 创建和 Browse/Search。
- 可审计的人工删除、失败上传回收、可选异步扫描、Nexus hosted 数据迁移。
- Nexus 黑盒对比、真实客户端、双数据库、OSS/S3 和 JVM/Native 验收。

首期不提供 proxy/group recipe、Git smart HTTP/SSH 服务、Git commit/ref 管理、`git-lfs-authenticate`、文件锁、tus/custom transfer、客户端分片续传或 S3 预签名直传。对象存储内部 multipart 是服务端存储手段，不是新增 Git LFS transfer 协议。

现有 Hugging Face proxy 中的 LFS 内容标识和下载桥接只服务于模型代理。Git LFS hosted 的对象命名、鉴权与发布流程独立设计；可复用底层存储和 checksum 工具，不能复用 Hub 路由或把模型 proxy 标记为 Git LFS hosted。

## 调研依据与兼容性基线

协议资料核对日期：2026-10-03。Git LFS 上游文档快照为 [`0043a645047926f4bd7f7091299095528253d575`](https://github.com/git-lfs/git-lfs/tree/0043a645047926f4bd7f7091299095528253d575/docs)。引用的规范约束和下面的 kkRepo 设计决策应分别验证。

| 来源 | 已确认的约束 | 对设计的影响 |
| --- | --- | --- |
| [Git LFS pointer 规范][lfs-spec] | pointer 包含内容 SHA-256 与字节数；空文件通常直接通过 Git 保存 | LFS OID 是内容标识，不是 Git commit hash、文件路径或 S3 ETag；另测零字节对象 API 边界 |
| [Batch API][lfs-batch] | JSON POST、upload/download、transfer 协商、逐对象 action/error | 业务权限必须识别 operation；部分对象失败不能吞掉同批成功对象 |
| [Basic transfer][lfs-basic] | action 指定 GET/PUT 地址；verify 是可选 POST | 客户端跟随 action；PUT 完成时即保证完整性，不能依赖客户端一定调用 verify |
| [认证][lfs-auth]与[服务发现][lfs-discovery] | 支持独立 LFS endpoint、HTTP Basic 和 Git credential helper | Git remote 与 LFS endpoint 可分离，凭据按 endpoint 管理 |
| [Nexus Git LFS 文档][nexus-lfs] | hosted、Batch/basic；配置入口以 `/info/lfs` 结尾；内容按二进制处理 | 首期仅开放 hosted；内容类型不做文件格式白名单，但仍校验大小和摘要 |
| [Git LFS 客户端配置][lfs-config] | 下载支持 Range；锁验证有独立配置和不支持时的处理 | 大文件下载纳入 Range 验收；无文件锁能力时必须明确返回不支持 |

M0 参考实例固定为 `sonatype/nexus3:3.94.0`，与现有主要 Nexus compatibility lane 对齐；实际执行报告须记录 image digest、Git/Git LFS 精确版本、数据库和 blob store。另选一个当前稳定 Git LFS 3.x 客户端固定版本运行；Nexus 文档列出的 1.x/2.x 不自动等于已验证现代客户端，也不构成 kkRepo 对所有历史版本的支持承诺。

以下细节尚无本次实测证据，必须先形成 Nexus fixture：action 路径和认证字段、PUT 成功码、verify 是否出现、重复上传与 write policy、无效 OID/size、认证错误、锁端点、Range/HEAD、Browse/component 投影、管理 UI 对应 REST 调用及迁移数据形态。规范没有固定的细节不凭推测宣称 Nexus 兼容。若 Nexus 行为与官方规范冲突，记录版本限定的差异和真实客户端影响，再决定是否提供兼容处理。

## 客户端配置与路由

以下是功能实现后的目标配置，当前版本不可据此创建 Git LFS 仓库。现有 Git remote 继续指向 Git 服务：

```sh
git lfs install --local
git config -f .lfsconfig lfs.url https://repo.example.com/repository/project-lfs/info/lfs
git config credential.https://repo.example.com.useHttpPath true
git lfs track '*.psd'
git add .lfsconfig .gitattributes
git commit -m "Configure Git LFS storage"
git lfs env
```

凭据通过 credential helper 或 CI 的受控凭据注入提供，不写入 `.lfsconfig` 或 URL。Git 服务使用 SSH 不会自动赋予 kkRepo 访问权。客户端若存在本地 `lfs.url`、`lfs.pushurl` 或 remote-specific override，切换后用 `git lfs env` 核实实际 endpoint。[服务发现][lfs-discovery]定义了独立 LFS 地址的配置方式。

设 `B = /repository/{repo}/info/lfs`：

| 请求 | 目标语义 | 权限入口 |
| --- | --- | --- |
| `POST B/objects/batch`，`operation=download` | 对每个可读对象返回 download action 或对象错误 | 仓库 `READ`，再按对象检查 selector/下载策略 |
| `POST B/objects/batch`，`operation=upload` | 已存在对象省略 actions；新对象返回 upload action | 仓库 `ADD`，再检查对象 selector/write policy |
| `PUT upload.href` | 接收原始字节、校验并发布一个对象 | 重新认证和授权，校验上传上下文 |
| `GET download.href` | 返回完整对象或单区间 Range | `READ` 与下载策略 |
| `HEAD download.href` | 返回与 GET 一致的对象元数据 | `READ` 与下载策略；具体 Nexus 差异由 M0 固化 |
| `POST verify.href`，仅当提供该 action | 校验已发布对象的 OID/size，重复请求无副作用 | 上传权限与对象上下文 |
| `B/locks`、`B/locks/verify` 等锁路由 | 不提供锁服务 | 经过仓库认证/权限检查后返回明确的不支持响应 |

Git LFS 只固定 Batch 相对入口，transfer/verify 路径由服务端在 action 中给出。实现前由 M0 固化 Nexus 的 action 路径，再映射至独立 route parser；本文不假定 `B/objects/{oid}` 就是 Nexus 的传输路径。kkRepo 返回的 action 必须留在本仓库的公开 `/repository/{repo}/...` URL 下，不能暴露 bucket key、内部主机或其它仓库地址。

绝对 URL 使用统一外部地址构造与 `ForwardedHeaderPolicy`，仅接受受信任直接代理的转发头。测试 HTTPS、context path、代理前缀和伪造 Host/Forwarded 输入。Batch 不缓存带用户上下文的响应到公共缓存，也不回显 Authorization。

锁能力采用官方客户端可识别的 `501 Not Implemented` 设计；这是 kkRepo 的能力边界，Nexus 同版本响应仍需单独对比。默认 `locksverify` 客户端应可识别不支持并继续普通 push；显式要求锁验证或 lockable 工作流不属于首期支持范围。不得用空的成功锁列表伪装锁保护。[客户端配置说明][lfs-config]

## Batch、对象标识与错误语义

### 输入与协商

- Batch/verify 接受 `application/vnd.git-lfs+json`，包括 `charset=utf-8` 参数；返回 LFS JSON，不能返回 HTML 登录页。
- OID 按官方 SHA-256 表示校验为 64 位小写十六进制；`size` 是非负整数，使用精确 64 位解析，拒绝小数、溢出和配置限额外输入。UI 不经 JavaScript 浮点数损失字节精度。
- `transfers` 缺省时按 `basic`；显式列表包含 basic 才选择 basic，不能给仅请求其它 adapter 的客户端返回未协商的成功。失败形态由 M0/客户端 fixture 固化。
- `hash_algo` 缺省为 `sha256`；不支持的算法明确报错，不把它当 SHA-256 继续写入。
- `ref` 可缺省或为 null；提供时只作为有界请求上下文。kkRepo 不掌握 Git refs，因此不能据此授予分支权限、证明所有权或计算可清理对象。
- JSON body、数组长度、字符串长度和嵌套深度都设硬上限。对重复 OID 去重查询，但按请求项生成结果；同 OID 不同 size 不采用最后写入获胜。

示例 fixture 内容为六字节 `hello\n`，以下 JSON 是目标协议示例，不是 Nexus 实测响应：

```json
{
  "operation": "upload",
  "transfers": ["basic"],
  "objects": [
    {
      "oid": "5891b5b522d5df086d0ff0b110fbd9d21bb4fc7163af34d08286a2e846f6be03",
      "size": 6
    }
  ]
}
```

该对象已在当前仓库完整发布时，响应省略 `actions`，客户端无需重新上传：

```json
{
  "transfer": "basic",
  "objects": [
    {
      "oid": "5891b5b522d5df086d0ff0b110fbd9d21bb4fc7163af34d08286a2e846f6be03",
      "size": 6
    }
  ]
}
```

对象只有在当前仓库可见且 size 一致时才可复用。另一个仓库恰好存在同一 SHA-256，不能据此跳过上传、返回 action 或泄漏存在性。数据库中的未完成上传也不算已存在对象。[Batch 协议][lfs-batch]

### 错误分层

| 场景 | 目标处理 |
| --- | --- |
| 未认证访问受保护仓库 | HTTP 401，LFS JSON；提供 Basic challenge，核对 `LFS-Authenticate`/`WWW-Authenticate` 的客户端行为 |
| 只读主体发起 upload | HTTP 403；未知/不可见仓库按现有隐藏策略和 Nexus fixture 返回 404 |
| 有效 Batch 中个别对象不存在、被拒绝或校验失败 | HTTP 200，失败项带 `error.code`/`message`；不可同时带可执行 action |
| 非法 JSON/operation、全部对象无效 | 请求级 4xx；全部对象无效遵循规范 422，细分解析错误由 fixture 固化 |
| Batch 数量/body 超上限 | HTTP 413；上传字节限额在 PUT 流上再次执行 |
| 限流或暂时无法取得上传租约 | 429 或可重试 503，按 HTTP 语义提供重试提示，不谎报上传成功 |
| OID/实际摘要/实际长度不一致 | 非成功响应，保持原对象不变，不发布 asset；具体错误码由 M0 固化 |

对象不存在使用对象级 404，算法不匹配和校验失败使用协议规定的错误类别。请求级错误没有 `objects` 字段。错误和审计不包含密码、token、完整凭据头或云存储签名。上述分层依据 [Batch API][lfs-batch]；Nexus 对边界输入的差异作为 fixture 保留。

## 模块集成与当前缺口

| 模块/现有入口 | 计划职责与必须补齐的部分 |
| --- | --- |
| `core` | 添加 `GITLFS`、仅 hosted recipe/capability；定义对象标识、经校验的流式暂存契约 |
| `protocol-gitlfs`（新增） | raw route parser、Batch/verify DTO、OID/size/transfer 校验、action/error codec |
| `server` / `RepositoryProtocolHandler` | controller 委托 Git LFS service；业务 service 完成授权、流式存储、发布、Range、审计 |
| `RepositorySecurityFilter` / `SecurityAuthenticationService` | operation-aware 权限、Basic/CI 凭据、selector、匿名读取和 CSRF 边界 |
| `persistence-jdbc` | 对象和上传 attempt DAO、事务发布、租约/fencing、通用 Blob 引用与制品变更 outbox |
| `persistence-mysql` / `persistence-postgresql` | 同号 migration、相同唯一约束/状态转换/索引和双库 contract test；不预占迁移版本 |
| `storage-s3` / `storage-file` | S3 与 OSS Native 引擎的受限流式暂存/完成/中止；file 引擎仅用于开发和测试 |
| `admin-ui` / `browse-ui` | hosted 配置、LFS endpoint、OID/size/checksum、上传失败运维、搜索和删除入口 |
| `migration-nexus` | definition、权限及 hosted 内容导入，按源 shape 验证支持范围 |
| `compat-test` | Nexus fixture、客户端、多副本故障、性能与安全回归 |

当前 `BlobStorage.put(...)` 接受调用方提供的 SHA-256；`S3BlobStorage` 据此构建最终 key，部分输入流会先落临时文件，multipart 文件上传走 `putFile(...)`。不能把客户端声称的 OID 直接传给该接口，再在写入后才检查摘要，否则坏请求可能覆盖相同 key 的有效内容，也不能声称现有接口已保证大对象的有界流式上传。

当前 `RepositorySecurityFilter.actionsForRepository(...)` 默认把 POST 视为 ADD、PUT 视为 EDIT。新增 format 时必须同时加入 LFS 操作分类，下载 Batch 不得额外要求 ADD；内容不可变，普通首次上传应按 ADD，重复相同内容不应被误判为覆盖编辑。Nexus write policy 差异须先通过 fixture 明确。

当前 `CleanupPolicyCapabilities` 对全部 format 默认支持执行和 last-downloaded。仅添加枚举会错误开放 LFS 自动清理，必须同时调整 capability、策略校验、候选查询和执行防线。

## 存储、发布与多副本一致性

### 对象模型与索引

以下是逻辑模型，最终表名和 asset/component 映射由 M0 与 migration 设计固化：

| 逻辑记录 | 核心字段/约束 | 用途 |
| --- | --- | --- |
| `gitlfs_object` | repository_id、oid、size、asset_id、blob_id、state、generation、时间；`UNIQUE(repository_id, oid)` | 仓库内对象可见性；只有 AVAILABLE 能被下载或被 Batch 判定已存在 |
| `gitlfs_upload` | upload_id、repository_id、oid、expected_size、subject_id、expires_at、state、candidate_key、storage_upload_id、取得的 fence/generation | action 上下文、PUT attempt、恢复及回收；不同请求可以有独立 attempt |
| `gitlfs_upload_lease` | repository_id、oid、owner、fencing_token、expires_at；`UNIQUE(repository_id, oid)` | 同一对象的独占发布租约，续租/抢占用条件更新；不依赖不同数据库的部分唯一索引行为 |
| 通用 asset/blob/component | 格式、逻辑路径、SHA-256、大小、blob store/ref、发布/下载时间 | Browse/Search、资产引用、异步扫描和审计，复用现有通用模型 |

OID 使用可精确比较的固定长度列；大小使用非负 BIGINT。对象行可先以未发布状态预留 identity/generation，不能被 Browse 或下载视为 asset；失败 Batch/PUT 不锁死后续正确的 size。Batch 按 `(repository_id, oid IN ...)` 分块查询，不能每个对象分别扫仓库。回收索引覆盖 `(state, expires_at, upload_id)`，Browse 使用仓库加游标索引；不把任意 ref 或 filename 当关系键。记录权限和 policy 水位时沿用已有机制，不新增每个 JVM 独有的真相。

一个可见 OID 对应一个 asset，component/Browse 是否需要额外层级以 Nexus fixture 为准。Git 原文件名、commit 和项目来源不在上传字节中，UI 展示 OID 和可确认的元数据，不虚构 Maven 风格版本或 Git 路径。内部逻辑对象路径的唯一规范要同时用于 selector、下载、Browse、删除和迁移。

### 上传状态机

```text
Batch upload -> 有期限的上传上下文（不是可见 asset）
PUT -> UPLOADING -> 内容长度和 SHA-256 校验 -> STORED -> AVAILABLE
          |                 |                   |
          +------失败/过期---+------发布失败------+-> 待回收 attempt
AVAILABLE -> 人工删除事务 -> TOMBSTONED -> 通用 Blob GC
```

1. Batch 做身份和对象权限校验，为缺失对象创建有期限、大小有界的上传上下文并返回 action。上下文持久化在数据库，action 可命中任意 pod；opaque upload ID 只是关联标识，不是免认证凭据。若 action 有效期被声明，`expires_in` 必须与持久期限一致。
2. PUT 重新鉴权并验证 repository/OID/size/主体绑定，取得可续租的数据库租约及递增 fencing token。连接期间只持有短事务，不用数据库长事务包住文件传输。重试 PUT 创建新的 candidate，不接续失败请求的局部内容。
3. 服务端一边读取一边计数和计算 SHA-256，通过新增的存储暂存契约写入 **每次 attempt 唯一且不公开的 OSS/S3 key**。流提前结束、超额、断连、摘要不匹配均终止；未验证内容从不写入已有 OID 的最终 key。
4. 暂存契约以 begin/write/finish/abort 语义封装 provider；按有界 part buffer 支持签名、重试和 backpressure，峰值内存随 part 大小和并发数受控，不随整个对象增长。multipart handle、candidate key 与清理责任必须可持久恢复；零字节和小对象使用独立 key 的受限路径。生产正确性不依赖本地持久卷或整文件内存/磁盘暂存。
5. EOF 后核对实际大小、请求 Content-Length（若有）、expected_size 与实际 SHA-256；成功后才完成存储对象并形成服务端确认的 BlobReference。candidate 可直接成为不可变物理 blob，由数据库保存引用，无需按客户端 OID 重命名覆盖共享 key。S3 ETag 不能代替内容 SHA-256。
6. 在短数据库事务中重新核对租约 owner/fence/未过期、对象 generation、仓库仍 online/可写以及当前主体写权限，再提交 AVAILABLE、asset/component、blob 关联及按部署开关启用的通用 outbox；同时转移暂存引用。事务失败时文件仍不可见，由 attempt 回收；事务成功后才向 PUT 返回成功。
7. 如果另一个请求已经发布同仓库同 OID/size，返回幂等结果并回收本 attempt，不能产生重复 asset。无法取得租约则有界等待或可重试失败。若同 OID 的 size 冲突，报告错误，不更新已有记录。
8. 仅在客户端已拿到 verify action 时接受对应 verify；它查询当前已发布对象并比较 size，不执行第一次发布。PUT 已成功但响应丢失后，另一 pod 的重试 Batch/verify 仍可确认成功。

存储 begin 与数据库记录之间不存在跨系统原子事务：先登记服务端生成的 candidate key，再建立 provider 上传，并记录 handle；中途崩溃的窗口由该命名空间下的有界对账和过期 multipart 清理兜底。完成写入到 metadata commit 之间也保留可恢复记录。候选 key 一旦被正式 asset 引用，不再受临时对象生命周期规则删除。

### 故障、删除和缓存

- owner 崩溃或租约失效后，其回调不能提交任何 metadata。续租、完成、中止和回收都检查 owner/fencing token；旧 owner 只可能留下自己唯一 key 的垃圾，不能删除新 owner 或可见对象。
- 回收 worker 用数据库 claim/租约分页处理过期 attempt。先确认没有有效上传 owner、没有 AVAILABLE/asset/通用 Blob 引用，再释放暂存引用和删除；复用 `BlobReferenceDao` 与通用 GC 的删除 fence，避免先检查后误删竞态。
- 人工删除在事务内推进 generation、撤销该对象的旧上传上下文、移除可见 asset 并保留 tombstone。先于删除取得的 PUT 不得使对象复活；新的显式 upload Batch 在权限允许时可重新发布相同内容。Blob 物理回收需等待已有传输/扫描引用结束。
- 仓库删除、离线和 write policy 更新同样参与 publish fence；下载请求不能凭旧 action 绕过当前策略。已开始的长流采用既有在途传输语义，取消和引用释放必须可恢复。
- 首期对象存在性直接读数据库，不引入 negative cache，避免 pod B 看不到 pod A 刚发布的对象。后续若需要热缓存，强类型使用 `LocalCacheFactory`，字符串/JSON TTL 与 per-pod 计数使用 `SharedCache`，提供 TTL/失效和丢失重建语义；上传状态与分布式配额不能放在这些本地缓存中。

## 认证、授权与大文件下载

Basic 使用现有认证服务。CI 复用 GenericToken 的既有 bearer/API-key 入口，通过只作用于该 repository URL 的 Git HTTP header 配置注入并以真实客户端验证；不默认声称把任意 API key 填进 Basic password 就能使用。服务端不新增独立用户或 token 真相表，权限撤销遵循现有共享水位和缓存失效机制。

Batch 只在 Git LFS route 上解析有界 JSON，并把一次解析后的 operation/objects 交给授权和 service，避免两次解析或不同 parser 导致权限分歧。二进制 PUT 不能被鉴权 filter 整体缓存。每个对象的 Content Selector 按统一逻辑对象路径评估；不能只对 `info/lfs/objects/batch` 路径授权后批量放行。

download action 默认不内嵌新凭据，不声明免认证，允许客户端为目标 URL 使用 credential helper。匿名主体只有在仓库开启匿名且有效角色授予 READ 时可执行 download Batch/GET/HEAD；匿名写入关闭。upload/verify 再检查 ADD、write policy 和对象上下文，不能仅凭知道 OID 就绕过授权。

认证 filter、CSRF filter 和代理必须纳入集成测试：协议客户端用显式凭据访问；如沿用 session，需要保留既有 CSRF 保护，不能为 LFS 泛化关闭所有 POST 防护。仓库权限与 Git 服务权限相互独立，推荐按 Git 项目或权限域拆分 LFS 仓库。[Nexus 的项目隔离建议][nexus-lfs]

下载走 kkRepo 鉴权和 `ArtifactDownloadPolicy`，首期不重定向预签名 S3 URL。完整 GET 返回原字节、准确 Content-Length 与 `application/octet-stream`；HEAD 无 body；单 Range 返回 206/Content-Range，越界 416，If-Range/ETag/条件请求复用通用 HTTP 能力并通过 Nexus fixture 确认差异。流式读取用 BlobStorage 范围接口，不能先把全文件读到内存。不得通过透明压缩改变 OID 对应的原始字节。

首期配置需明确 Batch 数量/body 上限、单对象上限、上传并发、part buffer 总预算、读写 idle timeout、租约/上下文期限和回收保留期；名称与默认值在 M0 性能测试后确定。大小限额在 Batch 和实际字节流上都执行。跨 pod 的硬配额/预约量放数据库；每 pod 的保护性限流明确只约束本节点。Nginx/Ingress 的 body 限额、请求缓冲和 idle timeout 与服务端保持一致。

## 管理、清理与安全扫描

Admin 首期只显示 `gitlfs-hosted`，可配置 blob store、online、write policy、访问权限及大文件限制；strict content-type validation 不适用于 LFS。M0 先记录 Nexus 创建/编辑 UI 到 REST 的映射，再实现对应 repository endpoint。Browse/Search 提供 OID、字节数、checksum、创建/下载时间与扫描覆盖状态；对象检索使用精确 OID/受限前缀和游标分页。

上传主入口是原生 Git LFS 客户端。通用 UI/API 上传不能退化成任意路径写入：首期可明确关闭 multipart 组件上传；如提供管理员导入，必须计算 OID/size 并经过同一发布事务。原文件名只能是可选展示属性，不能替代内容 identity。

Git LFS 服务不知道 Git 历史中的可达对象集合。按创建时间、最后下载时间或“保留最近 N 版”删除可见对象，会破坏旧提交 checkout。因此首期不支持对已发布 LFS 内容执行自动 Cleanup；UI、策略保存、通配目标解析和执行器均拒绝该格式，即使对象本身长期未下载。失败 attempt、未完成 multipart 与无任何引用的 blob 可按上述回收契约处理。此限制与 [Nexus 对 LFS 删除风险的说明][nexus-lfs]一致。

人工 asset/component/仓库删除复用既有 DELETE/管理权限、审计与删除流程，并展示对历史 checkout 的影响；服务端不能以 UI 提示代替事务 fence。未来基于 Git reachability 的清理需要外部 Git ref 全量证明、权限域及并发 push 保护，另行设计。

安全扫描沿用[制品扫描设计](security-scanning-design.md)：部署能力关闭时不新增 outbox 工作；开启后，发布事务只写通用变更事件，扫描由持久任务异步执行。格式识别基于内容，不执行二进制或仓库脚本；未知二进制标记未覆盖/不支持，不能把未识别解释成无漏洞。下载阻断、waiver 和 pending/unknown 策略在真实 GET/HEAD/Range 再判定，Batch 通过不能成为绕过窗口。

## Nexus 迁移与切换

迁移范围分为 repository definition、权限映射和 hosted 内容。只有 recipe 被识别，不足以标记内容可迁移；M0 需保存 Nexus datastore/blob 属性、object path、OID/size 和 component/asset 投影 fixture，再为验证过的版本/shape 开放支持。未知 shape 输出不支持原因，禁止猜表名后宣称 FULL。

迁移步骤：

1. dry-run 检查源版本/shape、目标 blob store/权限、容量、OID/长度冲突，生成计划和不能识别的记录清单。
2. 导入任务按源 repository/object identity 建立持久游标和结果记录；对象 bytes 经同一 checksum/发布链路进入 OSS/S3。已经完整导入且校验一致的对象跳过，失败可 resume，不能覆盖不同摘要或长度的内容。
3. 每个对象保留源标识、目标 OID/asset、字节数、SHA-256 与验证状态；报告成功/跳过/冲突/失败、总对象数和逻辑字节数。权限映射失败不降级为公开仓库。
4. 对全部迁移对象核对长度/checksum；从独立克隆执行 `git lfs fetch --all`、`git lfs fsck`，checkout 包含历史大文件的 commit 并比较原始 bytes。只校验当前分支不足以证明历史可用。
5. 初次拷贝可在线进行，最终切换先停止源 LFS 写入并完成增量核对，再修改客户端有效 LFS URL，或在保留 `/repository/{repo}/info/lfs` 布局的入口切换后端。Git remote 与 pointer OID 保持原样。
6. 保留源只读和迁移报告用于回退。如果切换后目标已接收新对象，回退前先补齐这些新对象并重新核对，不能直接切回缺少数据的源。服务端无法保证任意客户端本地 URL override 自动更新。

通用 migration 工具必须支持 dry-run、resume、checksum 和报告；在完成 shape 与真实 Git 历史验证前，路线图不宣称 Nexus 内容迁移已支持。

## 测试、性能与完成门禁

本节是未来实现的测试计划，不是本设计 PR 已执行的结果。先新增 Nexus fixture，再实现最小兼容行为。归一化仅限协议允许的 host、时间和生成标识；不归一化错误类别、缺失 actions、OID/size、认证要求或路径差异来掩盖不兼容。

| 测试层 | 必须覆盖的用例 |
| --- | --- |
| `protocol-gitlfs` 单元测试 | OID/size 溢出、重复项、JSON 限额、charset、缺省/null ref、transfer/hash 协商、action/error 互斥、raw path 歧义 |
| `compat-test` Nexus 黑盒 | hosted 创建/编辑、upload/download Batch、首次 PUT/重复上传、缺失对象、混合成功失败、verify、write policy、鉴权、锁不支持、HEAD/Range/条件请求 |
| server 安全回归 | 只读用户的 download POST、ADD-only 上传、匿名开关、selector 混合批次、token 撤销、action 跨仓库重放、权限变更、伪造代理头、CSRF |
| 双数据库 contract | 唯一约束、lease 续租/抢占/fencing、重复 commit、权限/仓库 generation、delete 与 upload 竞态、GC 引用 fence、大小类型和迁移一致性 |
| 多副本/存储故障 | Batch/PUT/verify/GET 分别命中不同 pod；同 OID 并发；传输中、存储完成后、提交前后分别杀 owner；过期 owner 恢复；完整性失败；GC 与发布竞争 |
| 真实 Git/Git LFS 客户端 | track/add/commit/push、独立 clone/pull、fetch --all、checkout 历史提交、fsck、重复 push、并发上传、失败重试、Basic/CI token、与 SSH Git remote 分离 |
| 管理/迁移/治理 | 无 proxy/group、Browse OID 与 bytes、禁止自动 Cleanup、人工删除审计、扫描未知类型、迁移 dry-run/resume/冲突与切换回退 |
| JVM/Native 和 provider | MySQL/PostgreSQL，S3-compatible 与 OSS Native；大对象、多 part、零字节、资源上限、流中断；DTO/reflection/runtime hints |

计划新增 `GitLfsRepositoryBlackBoxCompatibilityTest`、真实 Git LFS client E2E lane 和存储暂存 contract tests。实现时接入现有 `run-live-compat`、`run-client-e2e`/Native 工作流并固定 Git LFS 安装版本；单有 Maven 单元测试或绿色 workflow wrapper 不算客户端验证。运行 server 测试使用 Java 25、`mvn -pl server -am ...`，指定测试时带 `-Dsurefire.failIfNoSpecifiedTests=false`。

性能基线与 Nexus 使用相同客户端、并发、网络和等价存储条件；记录小对象 Batch、64 MiB、1 GiB 及大于 4 GiB 对象的上传下载、Range、8/32 路并发，分别报告控制面与数据面耗时。后两类可以放入显式资源充足的扩展 lane，不要求每次 PR 都传输全套大数据。

验收指标包含吞吐、p50/p95、错误与重试率、峰值堆内存/临时盘、part/连接数、DB 往返和查询计划、孤儿数量及回收时间；公开原始报告，不预先声称快于 Nexus。10 万/100 万对象下精确 OID 查找必须命中唯一索引，Batch 查询次数按有界分块增长，不能按仓库总量增长。最大支持对象和并发上限要由 OSS/S3 实测确定。

## 实施阶段与待决事项

| 阶段 | 可交付结果 | 退出条件 |
| --- | --- | --- |
| M0：兼容性定标 | 官方协议/Nexus/真实客户端 fixture、UI 映射、迁移 shape 和性能脚本 | action 路径、verify/write policy/错误差异有证据；不提前开放 recipe |
| M1：安全存储基础 | `protocol-gitlfs`、流式暂存契约、双库对象/上传状态机、fencing/GC | 未校验内容不覆盖现有对象；双副本故障与 provider contract 通过 |
| M2：客户端闭环 | hosted routing、operation-aware 鉴权、Batch/basic/verify、Range | 真实 push/clone/历史 checkout、Nexus 黑盒、JVM/Native 通过 |
| M3：产品交付 | Admin/Browse/Search、Cleanup 禁用、扫描接入、shape-gated 迁移、指南和性能报告 | 安全/迁移/治理验收完成后才将路线图改为已实现 |

M0 必须关闭的待决项：

- 固定 Git LFS 客户端支持版本；Nexus 3.94.0 的 action/verify、重复写和 policy 交互；旧客户端需要的差异。
- 固定对象逻辑路径和 Nexus component/Browse/migration 映射，确保各授权入口使用同一 identity。
- 固定 Batch/单对象/并发/part budget 默认值及存储暂存 API；确认 S3-compatible 与 OSS Native 都不依赖完整文件本地暂存。
- 固定无 Basic 环境下 GenericToken 的 Git header 配置和真实客户端凭据作用域；如需 Basic token 新语义，另行明确验证，不能隐式扩大认证范围。
- 记录不支持文件锁、自动可见内容清理、Git 权限继承及直传的产品边界，防止 Admin 宣称超出能力。

## 参考资料

- [Git LFS pointer 规范][lfs-spec]
- [Git LFS Batch API][lfs-batch]
- [Git LFS Basic Transfer API][lfs-basic]
- [Git LFS Authentication][lfs-auth]
- [Git LFS Server Discovery][lfs-discovery]
- [Git LFS 客户端配置][lfs-config]
- [Sonatype Git LFS Repositories][nexus-lfs]
- [kkRepo Hugging Face Models 设计](hugging-face-models-repository-design.md)
- [kkRepo Cleanup Policy 设计](cleanup-policy-design.md)
- [kkRepo 安全扫描设计](security-scanning-design.md)
- [kkRepo Nexus 迁移设计](nexus-migration-compatibility-refactor-plan.md)

[lfs-spec]: https://github.com/git-lfs/git-lfs/blob/0043a645047926f4bd7f7091299095528253d575/docs/spec.md
[lfs-batch]: https://github.com/git-lfs/git-lfs/blob/0043a645047926f4bd7f7091299095528253d575/docs/api/batch.md
[lfs-basic]: https://github.com/git-lfs/git-lfs/blob/0043a645047926f4bd7f7091299095528253d575/docs/api/basic-transfers.md
[lfs-auth]: https://github.com/git-lfs/git-lfs/blob/0043a645047926f4bd7f7091299095528253d575/docs/api/authentication.md
[lfs-discovery]: https://github.com/git-lfs/git-lfs/blob/0043a645047926f4bd7f7091299095528253d575/docs/api/server-discovery.md
[lfs-config]: https://github.com/git-lfs/git-lfs/blob/0043a645047926f4bd7f7091299095528253d575/docs/man/git-lfs-config.adoc
[nexus-lfs]: https://help.sonatype.com/en/git-lfs-repositories.html
