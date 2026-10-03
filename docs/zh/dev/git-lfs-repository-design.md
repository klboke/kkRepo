# Git Large File Storage (LFS) 仓库设计与实现

kkRepo 的 `gitlfs-hosted` 保存大文件内容；独立 Git 服务继续保存提交、ref 和 LFS pointer。
元数据和上传协同状态存入 MySQL/PostgreSQL，生产 blob 存入 OSS/S3。

## 实现范围

支持 Batch upload/download、basic PUT/GET、verify、HEAD、单 Range、条件下载，
Basic/GenericToken、逐 OID Content Selector、Admin 创建、Browse/Search 和 Nexus hosted 数据迁移。
原生 Git LFS 3.8.0 是固定的客户端验收版本。实现及验证记录见[验收说明](git-lfs-acceptance.md)，
客户端操作见[使用指南](../repository-guides/git-lfs.md)。

不提供 proxy/group、Git smart HTTP/SSH、commit/ref 管理、文件锁、tus/custom transfer、
客户端分片续传、预签名直传、普通组件上传或自动清理已发布对象。OSS/S3 multipart 仅是服务端存储方式。
Hugging Face proxy 的 LFS/Xet 下载桥接保持独立。

## 协议与 Nexus 基线

2026-10-03 核对 [Git LFS 官方规范][lfs-spec]、[Batch][lfs-batch]、
[basic transfer][lfs-basic]、[认证][lfs-auth]、[服务发现][lfs-discovery]及 [Nexus 文档][nexus-lfs]。
规范快照固定为 `0043a645047926f4bd7f7091299095528253d575`。
参考实例为 `sonatype/nexus3:3.94.0`（3.94.0-12），不由 recipe 名称推断其它版本兼容性。

| 请求 | kkRepo 行为 |
| --- | --- |
| `POST /repository/{repo}/info/lfs/objects/batch` | upload/download；选择 basic；逐对象 action/error |
| `PUT /repository/{repo}/{oid}` | 必须携带 Batch 提供的上传关联 header；校验字节后发布；200 |
| `GET /repository/{repo}/{oid}` | 原字节、octet-stream、SHA-1 ETag、Content-Length；206/416/304 |
| `HEAD /repository/{repo}/{oid}` | 与 GET 相同的鉴权、下载策略和元数据，无 body |
| `POST /repository/{repo}/{oid}/verify` | 校验已发布对象的 OID/size；200；不执行首次发布 |
| `/repository/{repo}/info/lfs/locks...` | 501，官方客户端可关闭锁校验 |

实测差异必须保留，不能在黑盒断言中归一化掉：

| 边界 | Nexus 3.94.0-12 | kkRepo |
| --- | --- | --- |
| 上传 action 凭据 | 回显 Authorization | 不回显；`authenticated:false` 让客户端按目标地址获取凭据 |
| 上传关联 | PUT action 无关联 header | `X-KkRepo-Lfs-Upload` 绑定仓库、OID、长度、主体和有效期；客户端原样发送 action header |
| PUT 内容与 OID 不符 | 实测接受 | 422，不发布；保护不可变对象 |
| 非法 OID/负 size | 实测 Batch 接受 | 无效对象 422；全部无效时请求级 422 |
| verify 长度错误 | 实测 500 | 422 |
| HEAD | 实测 404 | 200，作为下载扩展 |
| 锁验证 | 实测 404 | 501，明确不支持 |
| `hash_algo:sha512` | 实测忽略 | 409，不将其当成 SHA-256 |

已存在对象的 upload Batch 省略 actions；不存在对象的 download Batch 返回对象级 404。
只有 basic 不在显式 transfers 列表时，返回请求级 422。空 objects 数组合法。

## 输入、身份与授权

`protocol-gitlfs` 独立解析 route 和有界 JSON。OID 是 64 位小写 SHA-256；size 是非负精确 long，
拒绝小数/溢出。Batch 最多 1,000 项、1 MiB JSON，深度 16，字符串 4,096 字符。
重复 OID 复用同一响应；冲突 size 为对象级 422。ref 不参与授权或 Git 可达性判断。

内部 asset path 为裸 OID；Content Selector 使用现有标准化路径（例如 `path == "/<oid>"`）。
组件使用空 group/version、name=OID，blob 保存 size/SHA-256/SHA-1/MD5。
任何跨仓库复用判断都必须先检查当前仓库的 asset，不能泄漏另一个仓库的对象存在性。

鉴权 filter 仅对 Batch 读取 JSON 一次，随后把解析结果交给 service；不会读取二进制 PUT body。
Batch 按 operation 判断 READ/ADD，并对每个真实 OID 授权，而不是给 Batch URL 一次权限后放行全批。
每批使用一次数据库权限快照，避免逐对象重新加载角色和 selector。
认证和权限在 LFS 入口绕过节点的凭据/授权缓存；PUT 完成存储后再次加载凭据和权限，
使另一 pod 撤销 token、禁用用户或修改角色后，旧 action 无法继续发布。

写入和 verify 要求显式凭据，单独浏览器 cookie 不能授权。显式无效凭据不会回退到 cookie/匿名。
匿名只读遵循现有匿名配置和角色。默认 authenticated role 的额外 READ 授权仍生效，selector 不覆盖其它有效授权。
401 带 LFS JSON、WWW-Authenticate 和 LFS-Authenticate；混合 Batch 的权限失败为对象级 403。
操作容量不足为 429/Retry-After；旧 context/租约冲突为 409，客户端重新 Batch；过期已回收 context 为 410。

绝对 action URL 通过 `ForwardedHeaderPolicy` 构造，路径留在当前仓库公开入口下，
不暴露 bucket/key。上传 ID 只关联持久上下文，不是免认证凭据；所有 action 重新鉴权。
Batch 和错误响应为 no-store，下载为 private/no-cache。GET/HEAD/Range/304 前均执行 `ArtifactDownloadPolicy`。

## 存储与多副本发布

既有 blob key 已包含随机唯一标识；原 `put` 接口的缺口是依赖调用方摘要、可能整文件暂存，
以及缺少可恢复的上传发布生命周期。本实现新增 `prepareVerifiedUpload/uploadVerified/discardVerifiedUpload`，
保留既有上传路径的行为。AWS S3 和 OSS Native 引擎都用 8 MiB 有界 part 顺序上传，
边读边计算三种摘要，并在 CompleteMultipart 前验证 SHA-256 和精确长度。零字节验证后单独 PUT。
峰值 buffer 受 part 大小、SDK 复制和并发数约束，不随整个对象增长，不落整文件临时盘。
File 引擎仅用于开发/测试，使用 CREATE_NEW 防止覆盖已有 attempt。

V59 在两个数据库中新增：

| 表 | 真相和约束 |
| --- | --- |
| `gitlfs_object` | `(repository_id, oid)` 唯一；generation、递增 fence、owner、lease、published、asset_id；asset FK 删除设 NULL |
| `gitlfs_upload` | UUID、仓库/OID/size/subject、blob store、generation/fence、仓库 updated_at、期限、状态、唯一物理 key、multipart handle、cleanup token/保留期 |

流程：

1. Batch 在短事务中锁定 online/可写仓库和对象，建立 READY context（15 分钟）。
2. PUT 校验主体绑定；短事务取得 5 分钟租约、递增 fence，把唯一 key 写入数据库，再执行远程 I/O。
3. 创建 multipart 后先持久化 handle 再发 part；传输期间每 30 秒续租。JVM 的线程和 semaphore 仅是执行资源。
4. 完整性通过后重新鉴权，在短事务内锁定仓库/对象/context，检查 generation、fence、租约、仓库版本/online/write policy。
5. 一次提交 blob、component、asset、Browse 节点、通用 asset-change outbox 和 PUBLISHED 状态。提交前对象不可下载。
6. 若同一 OID 已由其它 context 发布，保留既有 asset，将本 attempt 留给清理；同 OID 不做覆盖更新。

重试 Batch 可见已发布对象；丢失成功响应后的相同 PUT 会重新验证 body，不覆盖内容。
删除 asset 时 FK 原子置空并保留 published tombstone，旧 context 无法复活对象。
只有新 upload Batch 可以推进 generation 重新发布。仓库删除通过 FK 清理对象表，但保留 upload 恢复记录。
仓库 updated_at/online/write policy 参与发布检查。对象存在性直接查数据库，无新增本地 truth/negative cache。

清理 worker 每 60 秒通过 `FOR UPDATE SKIP LOCKED` 领取最多 50 条过期 attempt，将状态置为 REAPING，
阻止并发发布，再 abort multipart/delete 唯一 key。未知 handle 时按精确 attempt key 查找 multipart，
覆盖 initiate 与写入 handle 之间的崩溃窗口。cleanup token 防止旧 worker 回写新 owner 的结果。
物理 key 保留 24 小时、每小时重试删除，覆盖租约过期时已在云端执行的迟到 completion。
PUBLISHED context 24 小时后只删关联记录；已发布 blob 的物理回收由通用引用/GC 负责。
待回收 context 的 blob store FK 禁止提前删掉清理所需配置。

## 配置与产品边界

| 配置 | 默认值 | 含义 |
| --- | --- | --- |
| `kkrepo.gitlfs.max-object-bytes` / `KKREPO_GITLFS_MAX_OBJECT_BYTES` | 34,359,738,368（32 GiB） | Batch/迁移/实际流大小上限；不得高于 8 MiB × 10,000 parts |
| `kkrepo.gitlfs.concurrent-uploads` / `KKREPO_GITLFS_CONCURRENT_UPLOADS` | 4 | 每 pod 的保护性并发上限，不是全局配额 |
| `kkrepo.gitlfs.cleanup-interval-ms` / `KKREPO_GITLFS_CLEANUP_INTERVAL_MS` | 60,000 | 恢复 worker 间隔 |

Ingress/Nginx 应允许所需 body 大小，关闭上传请求磁盘缓冲，并设置适合大文件的 read/send timeout。
S3 凭据需允许 multipart create/upload/complete/list/abort 和对象读写删除；不对已发布 key 配置临时对象过期规则。
32 GiB 是配置默认上限，不是本次已完成的极限吞吐或最大对象压测结论。

Admin 只提供 hosted，关闭 strict content-type 选项；Browse 展示 OID 和客户端配置。
普通 UI/Components API 上传关闭。所有自动 Cleanup 能力为 false，策略保存、try-run 和执行路径均拒绝 LFS。
Git ref 不在服务器中，因此不能根据年龄或下载时间证明对象可删。人工管理删除仍需 DELETE 权限和审计，可能破坏历史 checkout。
扫描将不支持的 Git LFS 二进制标记 NOT_APPLICABLE，不把未知格式视为 CLEAN；下载阻断遵循现有策略。

## Nexus 迁移

repository definition、权限和 hosted 内容沿用持久 migration job、dry-run、metadata cursor、checksum、resume 和报告。
自动内容迁移限定 Nexus 3.94.x datastore：预检确认 GITLFS asset/blob/component 表和列，并抽样最多 2,048 条
验证 `/OID` 路径、非负 size、SHA-256=OID。未知版本/shape、OrientDB 或非 hosted 拒绝自动内容迁移。
抽样只判断源 schema；每一个实际导入对象仍通过同一完整性 verifier、租约和发布事务验证，不能因为抽样通过跳过字节检查。

源 path 去掉一个前导斜线后必须是合法 OID。已导入对象仅在目标 SHA-256/size 与源 identity 相同时跳过，禁止覆盖冲突内容。
最终切换先停止源 LFS 写入、完成增量校验，再更新有效 lfs.url 或同布局入口。保持 Git remote/pointer 不变。
切换后从独立 clone 执行 fetch --all、历史 checkout、fsck；保留只读源和迁移报告用于回退。

## 模块与验证入口

- `protocol-gitlfs`：解析、输入限制与协议错误。
- `core`、`storage-s3`、`storage-file`：有界完整性 verifier 和 provider 生命周期。
- `persistence-jdbc` 及双库 V59：租约、fence、删除 tombstone、恢复状态。
- `server/gitlfs`：授权、协议 handler、发布、迁移 writer、回收 worker；controller 仍只分发。
- `GitLfsRepositoryBlackBoxCompatibilityTest`：Nexus/candidate Batch、PUT/GET/verify、重复上传和错误 fixture。
- `scripts/ci/git-lfs-client-e2e.sh`：原生 Git LFS 历史版本 push/clone/pull/fetch/fsck 与 Cleanup 拒绝。
- `scripts/ci/git-lfs-resilience-e2e.py`：副本交接、撤销、selector、错误 bytes、Range、删除 fence、并发。
- 真实客户端接入 system shard，覆盖 JVM/Native 和 MySQL/PostgreSQL；OSS Native 适配器用 contract/mock 测试，不能等同于真实阿里云环境验收。

扩展验收仍需按部署资源运行 1 GiB/4 GiB+、8/32 并发、百万 OID、真实 OSS 端点及更广历史客户端矩阵。
这些项目不作为已有性能结论或旧客户端兼容性承诺。

## 参考资料

- [Git LFS pointer][lfs-spec]、[Batch][lfs-batch]、[basic transfer][lfs-basic]
- [Authentication][lfs-auth]、[Server Discovery][lfs-discovery]、[客户端配置][lfs-config]
- [Sonatype Git LFS Repositories][nexus-lfs]

[lfs-spec]: https://github.com/git-lfs/git-lfs/blob/0043a645047926f4bd7f7091299095528253d575/docs/spec.md
[lfs-batch]: https://github.com/git-lfs/git-lfs/blob/0043a645047926f4bd7f7091299095528253d575/docs/api/batch.md
[lfs-basic]: https://github.com/git-lfs/git-lfs/blob/0043a645047926f4bd7f7091299095528253d575/docs/api/basic-transfers.md
[lfs-auth]: https://github.com/git-lfs/git-lfs/blob/0043a645047926f4bd7f7091299095528253d575/docs/api/authentication.md
[lfs-discovery]: https://github.com/git-lfs/git-lfs/blob/0043a645047926f4bd7f7091299095528253d575/docs/api/server-discovery.md
[lfs-config]: https://github.com/git-lfs/git-lfs/blob/0043a645047926f4bd7f7091299095528253d575/docs/man/git-lfs-config.adoc
[nexus-lfs]: https://help.sonatype.com/en/git-lfs-repositories.html
