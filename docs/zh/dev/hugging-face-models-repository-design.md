# Hugging Face Models 仓库开发设计说明

本文记录 kkrepo Hugging Face Models 仓库格式的落地设计。目标不是把模型下载 URL 交给 Raw proxy，也不是把 Hugging Face Hub 的临时 CDN / Xet 地址直接转发给客户端，而是在 `huggingface_hub` 当前读协议、Hugging Face Git / Git LFS / Xet 存储语义、Sonatype Nexus Repository Hugging Face proxy 行为和 kkrepo 的关系数据库 + OSS/S3 + 多副本架构之间建立可验证的兼容层。

## 当前状态与落地结论

截至 2026-08-17，kkrepo 已完成首期 Hugging Face Models proxy 落地：`RepositoryFormat.HUGGINGFACE`、`huggingface-proxy`、独立 `protocol-huggingface`、双数据库 V48 schema、服务端协议 adapter、Admin/Browse/Search、Cleanup、安全扫描、Nexus 迁移与兼容/性能 fixture 均已接入。路线图中的完成状态仅指本文明确限定的 **Models proxy**；hosted、group、Datasets、Spaces、推理和写入能力仍属于后续独立范围。

实现证据：

- `huggingface_hub` 0.34.6 与 1.27.0 的 `hf_hub_download`、`snapshot_download`，以及 `hf` CLI 单文件/过滤 snapshot 均通过；kkrepo 补齐了 Nexus 3.94 在当前客户端 tree/paths-info 流程上的 `400` 缺口。
- 公网模型验证覆盖 Transformers `AutoConfig`/`AutoTokenizer`/`AutoModel.from_pretrained` 与 Diffusers `DiffusionPipeline.from_pretrained`；安装 Xet 且未禁用时，客户端仍不请求本地 Xet token/CAS，也不绕过 kkRepo。
- PostgreSQL + MinIO 双副本同时 cold miss 同一模型文件时，两端返回相同完整 SHA-256，fixture 仅收到一次 resolve 与一次完整 body，持久化 `READY` 状态由 fencing token 发布。
- Nexus 3.94 同机 4 MiB 基线中，5 组全新仓库的冷填充中位吞吐比为 `1.009x`；并发 16、每轮 500 请求、5 轮的全部 warm metadata/file 门禁通过。原始结果与复现方法见[性能基线](hugging-face-models-performance-baseline.md)。
- 运维与客户端配置见[Hugging Face Models 仓库使用指南](../repository-guides/hugging-face-models.md)，公开兼容边界见[兼容性矩阵](../compatibility-matrix.md)。

落地结论如下：

- 第一阶段只实现 **Hugging Face Models proxy**，recipe 名称与 Nexus 对齐为 `huggingface-proxy`。不在同一 recipe 中承诺 hosted、group、Datasets、Spaces、Kernels、Buckets、推理 API 或 Git push。
- 客户端通过 `HF_ENDPOINT=https://host/repository/{repo}` 使用 `hf download`、`hf_hub_download`、`snapshot_download`、Transformers、Diffusers 和其它基于 `huggingface_hub` 的模型下载流程；公开路径继续保持 `/repository/{repo}/...`。
- 模型 revision 的正确性真相是远端解析出的完整 Git commit hash。Branch、tag、`refs/pr/N` 等可变 ref 只是短 TTL alias；repo info、tree、文件 metadata、文件 bytes 和 Browse/Search 投影都必须绑定同一个 commit，不能把不同时间取得的 `main` 结果拼成一个 snapshot。
- 第一阶段使用 Hugging Face 提供的 Git LFS-compatible bridge，由 kkrepo 在服务端完整回源、校验并写入 OSS/S3，再从本地 Blob 向客户端提供普通 HTTP 下载。不得把 CDN signed URL、Xet token、CAS URL、`xetHash` 或 `Link: rel=xet-*` 暴露给客户端，否则下载会绕过 kkrepo 的权限、审计、Cleanup、安全扫描和对象存储。
- 当前 `RawProxyService` 可以复用通用 HTTP、Blob、validator、negative cache 和 auto-block 基础，但不能直接承担本格式：Hugging Face 需要 API route 识别、分页 Link 改写、tree / paths-info JSON 投影、Xet hint 去除、revision binding、模型 component 聚合和超大文件跨副本 singleflight。
- 大模型冷下载必须使用数据库 lease + fencing、流式 multipart Blob 写入、硬资源上限和失败接管；任何单 JVM lock、临时目录或内存 future 都不能成为唯一协调真相。
- Sonatype 文档建议 Hugging Face repository 使用文件型 Blob store，并指出首次大模型下载要等 Nexus 完整缓存。kkrepo 的生产目标仍是 OSS/S3，因此实现必须用相同对象存储完成冷填充、热读、Range 和并发性能门禁，不能通过切换为本地持久化文件系统规避问题。

2026-08-17 对 Nexus 3.94.0 和真实客户端的基线探针还固定了以下事实：

- Nexus REST format 为 `huggingface`，只暴露 `/service/rest/v1/repositories/huggingface/proxy`；请求 schema 使用通用 storage、proxy、negative cache 和 HTTP client 配置。
- `huggingface_hub` 0.34.6 的 `snapshot_download` 可通过 Nexus 3.94.0 下载；当前稳定版 1.27.0 的单文件 `hf_hub_download` 可用，但 `snapshot_download` 会请求 `/api/models/{repoId}/tree/{commit}`，Nexus 返回 `400`。kkrepo 必须同时保留 Nexus 已有行为并补齐当前官方客户端路径，不能复制该兼容缺口。
- Xet 文件的远端 resolve response 包含 `X-Xet-Hash`、`Link: rel=xet-auth`、外部 `Location`、`X-Linked-Etag` 和 `X-Linked-Size`。Nexus 缓存后返回本地 `200`，去掉外部 Location、Xet Link 和 `X-Xet-Hash`，同时保留 `X-Repo-Commit`、`X-Linked-Etag`、`X-Linked-Size`，并支持热缓存 `206 Range` 与 `304`。
- Nexus Search 把一次 model revision 表示为 `group=namespace`、`name=repository name`、`version=resolved commit hash` 的 component，同 commit 的配置和 weight 文件作为 assets；repo-info metadata 则可能形成 requested revision（例如 `main`）的独立 metadata component。最终组件、asset 和 Browse 形状必须由更完整的 M0 fixture 固定。

## 调研基线

实现时按以下顺序确定行为：

1. `huggingface_hub` 对应稳定 tag 的客户端源码、Hub OpenAPI、真实 Hub HTTP 响应和 Xet 规范是协议真相。
2. Sonatype Nexus Repository Hugging Face 文档、REST schema 和 Nexus 3.94.x 黑盒行为是兼容性参考。
3. kkrepo 现有 Raw proxy、Conda/Conan proxy、对象存储、通用 component/asset、Cleanup、安全扫描、迁移和多副本设施是落地基础。

协议关键事实：

- `hf_hub_download(repo_id, filename, revision)` 使用 `{endpoint}/{repoId}/resolve/{revision}/{filename}`。`revision` 可以是 branch、tag、完整 commit hash 或 `refs/pr/N`；客户端会把 revision 中的 `/` percent-encode。
- `snapshot_download` 先通过 `/api/models/{repoId}` 或 `/revision/{revision}` 解析 commit，再分页读取 `/api/models/{repoId}/tree/{commit}`，最后并发下载同一 commit 下的文件。当前客户端会把 tree 缓存到本地并用于完整性检查。
- resolve 的 `X-Repo-Commit` 给出实际 commit。普通 Git 文件的 ETag / tree OID 是 Git blob identity；LFS 文件的 `X-Linked-Etag` / `lfs.oid` 是内容 SHA-256，`X-Linked-Size` / `lfs.size` 是实际文件大小。
- Xet file hash 是由 chunk identity 派生的 reconstruction identity，不等同于文件内容 SHA-256。`X-Xet-Hash` 和 tree JSON 的 `xetHash` 会让安装了 `hf_xet` 的新客户端向 `{endpoint}/api/models/{repoId}/xet-read-token/{revision}` 取 token，再直接访问 CAS 和 signed xorb URL。
- Xet 官方保留 Git LFS bridge：非 Xet-aware 客户端跟随 resolve 的 302 后可以取得完整重建文件。第一阶段服务端回源正是使用这条兼容路径，而不是实现本地 Xet CAS。
- Hub API 与 resolve 使用不同 rate-limit bucket。`RateLimit`、`RateLimit-Policy` 和 `Retry-After` 必须作为上游节流信号处理；429 不是 not-found，也不能触发无界快速重试。
- Gated model 的访问权授予个人 Hugging Face 账号。Nexus 的兼容模式是在 proxy repository 上配置一个 preemptive bearer token，因此 kkrepo 也以 repository-scoped 上游服务身份访问；本地 repository `READ` 权限决定谁能读取已经代理的内容。

Nexus 兼容结论：

- Nexus 3.77.0 开始提供 Hugging Face proxy，Models 是唯一明确支持的 Hub repo type；Datasets 和 Spaces 不在该能力范围内。
- 官方客户端配置使用 repository root 作为 `HF_ENDPOINT`。Nexus 推荐 `HF_HUB_DOWNLOAD_TIMEOUT=120`、显著增大 `HF_HUB_ETAG_TIMEOUT`，因为冷请求会先完整缓存模型文件。
- Nexus 以 Git commit hash 标识模型版本，并声明为不同版本保存完整文件；实现不能只把 `main` 当版本，也不能按文件名覆盖历史 revision。
- Nexus 文档没有完整规定 tree / paths-info 新 API、Xet hint 处理、分页 Link、PR ref、alias 漂移、同 commit 多 alias、HEAD/Range/conditional response、gated token 撤销、跨副本 miss 合并和失败缓存语义。这些由官方客户端、可控 upstream 和 M0 fixture补齐。
- 当前 compatibility lane 使用 Nexus 3.94.0。自动迁移范围只能在 source datastore shape 和实际 cache path 被验证后扩展，不能因为 repository definition 能识别就宣称 content `FULL`。

## 功能范围

### 第一阶段必须实现

1. Format、recipe 与协议模块
   - 新增 `RepositoryFormat.HUGGINGFACE`、`huggingface-proxy` 和独立 `protocol-huggingface` 模块。
   - 实现 raw-path parser、repo ID / revision / file path value object、Hub response header codec、tree / paths-info transform、model file classifier、error model 和 content identity validator。
   - 在 recipe 可创建前先完成 Nexus 3.94.x M0 和 `huggingface_hub` current-stable fixture。

2. Hub metadata proxy
   - 支持 model repo info、revision info、recursive tree、nested tree、paths-info 和 refs 等下载/浏览必需的只读 API。
   - 对 absolute pagination `Link` 改写为当前 repository base；缓存 JSON 时保留上游原始 Blob和有界规范化投影。
   - tree / paths-info 输出删除 `xetHash` 及其它会触发客户端直连 Xet 的字段，但保留 `lfs.oid`、`lfs.size`、Git OID、size、path 和客户端需要的其它字段。

3. Model file proxy
   - 支持 resolve route 的 GET/HEAD、ETag、`X-Repo-Commit`、`X-Linked-Etag`、`X-Linked-Size`、Content-Disposition、conditional request 和单区间 Range。
   - 冷 HEAD / GET / Range 都先形成一个完整、校验通过、可跨副本读取的本地 Blob；Range 冷填充不能只缓存局部 bytes。
   - 普通 Git、legacy LFS 和 Xet-backed 文件都经同一 canonical `(repository, repoId, commit, path)` identity 发布；不把外部 signed URL 写入公开 metadata。

4. 产品与治理闭环
   - Admin UI 可创建/编辑 proxy，配置 remote、remote bearer token、TTL、negative cache、timeout、redirect/outbound policy、stale/auth-failure policy 和 Cleanup/扫描。
   - Browse/Search 展示 namespace、model repo、commit、requested ref、relative path、file kind/model format、size、Git/LFS/internal checksum、gated/private状态和缓存/扫描状态。
   - Cleanup 以完整 model commit 为主体，安全扫描覆盖可识别的模型文件且绝不执行反序列化或仓库内代码。
   - Nexus definition/content migration、真实客户端 E2E、双数据库 contract、双副本 takeover 和性能对比全部进入完成门禁。

### 后续扩展

- Hugging Face Datasets、Spaces、Kernels 和 Storage Buckets；它们有不同 repo type、路由、文件系统和容量语义，必须以独立 capability/recipe 明确开启。
- Hosted model publication、Git smart HTTP、Git LFS/Xet upload、commit API 和 pull request workflow；不能用 proxy recipe 接受写入。
- 多 proxy 的 Hugging Face group。Group 必须先定义 repo info/tree 合并、revision 冲突和 source binding，不能按文件路径 first-hit 拼装模型。
- 服务端原生 Xet reconstruction，用 chunk/range 并行加速回源后仍组装并校验完整 kkrepo Blob。客户端可见的本地 Xet CAS 是另一项独立设计。
- 模型 promotion、审批、签名/attestation、模型卡 policy 和组织级 allowlist；这些属于模型供应链治理，不阻塞首期 Nexus-compatible proxy。

### 明确不实现

- 不代理 Inference Providers、Inference Endpoints、Jobs、OAuth、讨论、点赞、用户管理或 Hub Web 页面。
- 不代理 `/api/datasets`、`/api/spaces`、`/api/kernels`、`/api/buckets`，也不把它们误标记为 Models。
- 不向客户端转发 Xet read/write token、CAS URL、signed xorb URL、CDN/S3 signed URL 或上游 bearer token。
- 不在服务端执行 `trust_remote_code`、Python、pickle、TorchScript、ONNX、Keras Lambda、自定义 operator、model code 或模型推理。
- 不把模型 Blob、tree page、repo info JSON、模型卡全文或扫描输出存进 MySQL/PostgreSQL；数据库只保存有界投影、状态、索引和 Blob 引用。
- 不把 Xet hash 当内容 SHA-256，不从扩展名单独猜可信 model format，也不把 branch/tag 当 immutable version。
- 不依赖单 JVM map、锁、future、scheduler 或本地临时文件维持 fetch owner、revision binding、negative cache、scan task 或 Cleanup 真相。
- 不为兼容 Hub 的任意 content type 关闭 path、size、redirect、SSRF、checksum 或资源限制；content-type mismatch 只影响分类和告警，不降低内容完整性校验。

## 模块与职责

| 模块 | 设计职责 |
| --- | --- |
| `core` | `HUGGINGFACE` format、`huggingface-proxy` recipe、共享权限和 repository capability |
| `protocol-huggingface` | raw route、repo ID/revision/path、Hub JSON/header transform、文件分类、checksum 和错误模型 |
| `persistence-jdbc` | model revision/ref/file/API cache 投影、通用 proxy fetch lease/fencing DAO 契约 |
| `persistence-mysql` / `persistence-postgresql` | 同号 migration、唯一约束、高效索引和 contract test |
| `server/huggingface` | metadata proxy、resolve/fetch、LFS bridge、alias binding、组件投影、Cleanup/扫描适配和 metrics |
| server 通用入口 | Controller、安全过滤、Blob Range/conditional response、proxy credential、outbound policy 和 repository 生命周期 |
| `security-scan` / `scanner-adapter` | Hugging Face model-file candidate、SafeTensors/pickle等静态检查、policy decision；不执行模型 |
| `migration-nexus` | Nexus 3.77+ definition/shape 探测、显式 proxy-cache plan、writer 和校验 |
| `admin-ui` / `browse-ui` | proxy/token 配置、model/revision/file 浏览、cache/scan/cleanup 状态和失败运维 |
| `compat-test` | 可控 Hub/LFS/Xet bridge、Nexus/kkrepo 黑盒、真实客户端矩阵和性能正确性探针 |

Controller 只负责 raw request、认证上下文、body/response 适配。Revision 解析、metadata transform、redirect、checksum、fetch 状态机、component/Browse 投影和安全策略必须位于协议 service 或格式 adapter 中。

## 客户端配置与 URL 路由

推荐客户端配置：

```sh
export HF_ENDPOINT="https://repo.example.com/repository/huggingface-models"
export HF_HUB_DOWNLOAD_TIMEOUT=120
export HF_HUB_ETAG_TIMEOUT=1800

hf download sshleifer/tiny-gpt2 config.json
```

首期 route 如下；精确 status/header/error body 由 M0 和 current-stable client 固定：

| 路径 | 方法 | 语义 |
| --- | --- | --- |
| `/repository/{repo}/api/models/{repoId}` | GET/HEAD | 默认 revision 的 model repo info |
| `/repository/{repo}/api/models/{repoId}/revision/{revision}` | GET/HEAD | 指定 branch/tag/commit/PR ref 的 repo info |
| `/repository/{repo}/api/models/{repoId}/tree/{revision}` | GET/HEAD | tree page；支持 `recursive`、`expand` 和分页 |
| `/repository/{repo}/api/models/{repoId}/tree/{revision}/{path}` | GET/HEAD | nested tree page |
| `/repository/{repo}/api/models/{repoId}/paths-info/{revision}` | POST | 有界路径集合的 metadata 查询；只读但方法为 POST |
| `/repository/{repo}/api/models/{repoId}/refs` | GET/HEAD | branch/tag/ref 列表，供版本浏览与客户端 API 使用 |
| `/repository/{repo}/{repoId}/resolve/{revision}/{path}` | GET/HEAD | 模型文件、配置、tokenizer、model card和其它 repo file 下载 |

下列路径首期明确不代理：

- `/api/models/{repoId}/xet-read-token/{revision}` 与所有 Xet write-token / CAS 路由；
- `/api/models/{repoId}/commit/...`、preupload、LFS batch upload、branch/tag mutation等写 API；
- datasets、spaces、kernels、buckets和 inference 路由。

Path 约束：

- `repoId` 支持标准 `namespace/name`，并保留 Hugging Face 历史单段 model ID 的 fixture；canonical key 大小写语义必须跟随 Hub，不能盲目 lower-case。
- 在 raw URI 上先识别 `api/models`、`resolve`、`revision`、`tree` 等保留边界，再对每个 segment percent-decode 一次。不能让 Servlet 容器提前把 `%2F` 展开成路由分隔符。
- Revision 允许官方客户端生成的 encoded `/`，例如 `refs%2Fpr%2F3`；decode 后只作为 revision value，不重新参与 path routing。
- File path 的 `/` 是层级分隔符。拒绝空段、点段、反斜杠、encoded separator、二次编码、NUL/控制字符、Unicode 歧义、超长 segment 和总路径超限。
- Query 参数只接受对应官方 API 的 allow-list、数量与长度上限；未知但无害参数是否透传由 client fixture 固定，不能形成任意上游 URL 拼接器。
- 公开响应 URL 始终位于当前 `/repository/{repo}` 下。反向代理场景只使用可信 external base URL，不信任任意 Host / Forwarded header。

## Revision、文件与内容身份

Hugging Face 模型必须区分四类身份：

```text
model identity       = repository + canonical repoId
requested revision   = branch / tag / refs/pr/N / commit supplied by client
resolved revision    = full Git commit hash returned by Hub
file identity         = repoId + resolved commit + canonical relative path
content identity      = internal SHA-256 + protocol-provided size/hash evidence
```

Checksum/identity 用途如下：

| 来源 | 语义 | 用途 |
| --- | --- | --- |
| tree `oid` / 普通文件 ETag | Git blob OID，通常为 SHA-1(`blob {size}\0` + bytes) | 普通 Git 文件与 tree 一致性 |
| tree `lfs.oid` / `X-Linked-Etag` | LFS/Xet 完整文件内容 SHA-256 | 大文件强完整性校验与客户端 cache key |
| tree `lfs.size` / `X-Linked-Size` | 重建后完整文件大小 | 截断/膨胀防护与响应 metadata |
| `X-Xet-Hash` / tree `xetHash` | Xet reconstruction identity | 仅保存为上游 provenance，不作为内容 checksum |
| kkrepo Blob SHA-256 | 实际写入 OSS/S3 的完整 bytes | Blob 去重、扫描、迁移、审计和最终校验 |

Ref 解析流程：

1. 对 branch/tag/PR ref 请求 repo info，取得完整 `sha`，写 `huggingface_revision_ref` 的短 TTL binding。
2. 后续 tree 与 file 请求优先使用 commit hash。若客户端仍请求 alias path，服务端每次使用同一 binding generation解析，响应写准确 `X-Repo-Commit`。
3. Immutable commit 的 tree、file identity 和 negative result可以长时间缓存；mutable ref 的 repo info/ref binding只使用 metadata TTL并执行 validator revalidation。
4. Ref 从 commit A 移到 B 时，原子发布新 binding generation。新请求只看 B；已经取得 A commit 的客户端继续通过 commit path读取 A，不受 alias 移动影响。
5. 不允许把 A 的 tree、B 的配置文件和 C 的 weight组合到同一 component或 snapshot。上游返回的 commit/header与请求绑定不一致时失败关闭。

同一文件被 `main`、tag 和 commit path请求时可以有多个 Nexus-visible route projection，但底层只引用一个 canonical file/blob。Alias projection不得重复触发扫描、usage主体或Blob存储。

## Hub metadata proxy 与 Xet 投影

Repo info、tree、paths-info 和 refs 都是协议 metadata，不是普通 Raw asset：

- Repo info原始 JSON进入对象存储；数据库只投影repoId、resolved commit、author、lastModified、private/gated/disabled、library、pipeline tag、有限tags/license和统计字段。
- Tree每页受上游分页限制，但响应仍视为不可信JSON；使用Jackson streaming parser限制depth、string、entry count和total bytes，并校验每个path、size、OID、LFS/Xet字段。
- 对客户端输出tree/paths-info时删除`xetHash`。`lfs`中的content SHA-256/size必须保留，不能为阻止Xet而损失完整性信息。
- Upstream `Link`若指向下一页，解析后只保存允许的cursor/query，再以当前external repository base重新渲染。不得原样返回`https://huggingface.co/...`。
- Transform后的JSON使用独立schema version和derived ETag；上游validator、raw body SHA-256与transform version共同决定cache identity。升级transform时可重建，不能把旧derived bytes当永久真相。
- `expand=true`可包含last commit和upstream security status。它们是展示/审计信号，不替代kkrepo自己的Blob校验与安全扫描；未知嵌套字段保留在有界raw metadata中，不自动扩列。
- `paths-info`只允许有界path count/bytes，响应与tree执行相同path和Xet字段处理。请求body不进入metric label或错误日志。

Metadata TTL 到期时使用ETag/Last-Modified条件回源。404/410可进入短negative cache；401/403、429、5xx、timeout、malformed JSON、commit mismatch和rate-limit exhaustion不得写not-found。

## Git LFS / Xet 大文件回源

首期采用“服务端使用 LFS bridge、客户端只看本地普通 HTTP”的边界：

1. kkrepo对canonical resolve URL发起HEAD，使用repository remote bearer，而不是客户端Authorization。
2. 校验`X-Repo-Commit`、ETag、`X-Linked-Etag`、`X-Linked-Size`、`X-Xet-Hash`和Location/Link形状；从tree已有记录时交叉验证path、commit、size和hash。
3. 普通Git小文件可能使用same-host相对redirect；LFS/Xet文件通常返回外部CDN/LFS bridge signed URL。每一跳都重新执行scheme、DNS/IP、host、port、redirect count和credential scope检查。
4. 跨host永不转发remote bearer、client Authorization、Cookie或内部header。Signed query只在当前fetch内存活，不写数据库、Blob attributes、日志、审计、metric或错误body。
5. Winner以GET完整回源到受限spool/OSS multipart，边写边计算internal SHA-256、Git blob OID（适用时）并校验linked SHA-256/size。校验完成前状态不是READY。
6. 发布canonical file/blob/component后，从本地Blob响应客户端。响应不包含Location、Xet Link、`X-Xet-Hash`、Xet token或upstream cookie。

即使客户端安装了`hf_xet`，它也必须走本地普通HTTP：

- tree/paths-info不返回`xetHash`；
- resolve不返回`X-Xet-Hash`或`rel=xet-auth`；
- 不提供xet-read-token；
- E2E在客户端容器禁止访问公网，只允许kkrepo地址，仍必须完成snapshot download。

未来可以在服务端使用xet-core按官方reconstruction protocol并行回源，但最终仍要生成一个经SHA-256/size验证的kkrepo Blob。除非另有完整的本地CAS、token、权限、Cleanup和迁移设计，不能向客户端宣布Xet capability。

## 大文件缓存与 HTTP 语义

Hugging Face冷请求和普通Raw文件的主要差异是HEAD本身可能触发数GB/TB级cache fill。为与Nexus客户端行为兼容，首期规则如下：

- 未缓存文件的HEAD使用上游HEAD确定identity后执行完整GET cache population，完成后才返回本地HEAD。不能保存零字节Blob，也不能返回外部redirect让后续GET绕过kkrepo。
- 未缓存Range请求同样完整填充canonical Blob，再从本地Blob返回请求范围。只缓存`bytes=0-...`会破坏后续完整下载和checksum。
- Warm HEAD不读取Blob body；Warm GET/Range使用Blob store原生stream/range，避免先落本地磁盘或读入heap。
- 首期支持单Range。Multi-range、suffix range、invalid/unsatisfiable range的`200/206/416`、Content-Range和body由Nexus/current client fixture固定。
- `If-None-Match`、`If-Modified-Since`和HEAD使用同一published file generation。304不能因省略关键validator而让客户端把另一个commit的cache误认为当前文件。
- Xet/LFS文件对客户端保留`X-Linked-Etag`和`X-Linked-Size`。ETag是否使用Xet hash、content hash或local validator以Nexus fixture为准，但客户端cache identity和内部校验优先linked SHA-256。
- Content-Type只作为展示和浏览hint。Hugging Face repo允许任意模型/config资源，扩展名与media type不一致不能导致关闭完整性校验，也不能因此执行内容。

单文件、单repository、全局并发和临时空间必须有独立限制。默认timeout需要覆盖Nexus建议的120秒activity timeout，并允许管理员为大模型设置更长wall-clock deadline；deadline、max bytes和取消必须同时终止HTTP body、multipart upload和lease heartbeat。

## Proxy 缓存流程与多副本协调

Canonical fetch key 为：

```text
repositoryId + repoIdHash + resolvedCommit + pathHash
```

冷请求流程：

1. 读取canonical `huggingface_file`。READY且policy允许时直接服务；immutable commit内容不按短content TTL覆盖。
2. 不存在时读取/刷新revision与tree metadata，得到expected identity，短事务upsert FETCHING row。
3. 通过通用`proxy_fetch_lease`按fetch key领取renewable lease和单调fencing token。只有winner回源；waiter轮询共享file state并遵守请求deadline。
4. Winner在事务外执行HEAD、redirect、完整body流、multipart upload和checksum。长HTTP期间只续租，不持有数据库行锁。
5. Finalize事务锁定file/lease，验证owner+fencing token，再发布Blob reference、asset/component、Browse投影、通用`artifact_change_event`和READY状态。
6. Winner在Blob完成后、finalize前崩溃：旧owner不能提交；新owner可复用经provisional reference证明完整的Blob或重新回源。孤儿multipart/Blob由fencing-aware worker有界回收。
7. Finalize后、response前崩溃：共享READY状态是唯一真相，重试和其它副本直接服务同一Blob。

失败语义：

- 404/410只对精确repoId + commit/ref generation + path写negative；带`X-Repo-Commit`时绑定该commit。Mutable ref移动会使旧negative失效。
- 401/403更新remote-auth状态并fail closed，不从“最后一次能访问”的gated cache静默继续服务。
- 429持久化reset deadline，返回可重试响应并限制后台/前台回源；不让所有副本同时重试。
- 5xx/timeout可按配置服务已验证的public cached generation；gated/private/auth错误默认禁止stale。Malformedmetadata、hash/size mismatch和unexpected redirect永不替换旧READY generation。
- 节点本地cache只保存短TTL的repo/ref/file lookup和response header；以revision/credential/policy generation失效，丢失只影响性能。

## 数据模型与高效索引

模型和metadata bytes放OSS/S3；数据库保存bounded identity、binding、状态、索引和Blob引用。建议表与关键索引如下；MySQL/PostgreSQL使用同号migration和同一DAO contract：

| 表 | 关键约束与索引 | 热查询 |
| --- | --- | --- |
| `huggingface_model_revision` | `UNIQUE(repository_id,repo_id_hash,commit_hash)`；`UNIQUE(component_id)`；`idx_hf_revision_page(repository_id,repo_id_hash,observed_at,id)` | commit exact、Browse/Search/Cleanup page |
| `huggingface_revision_ref` | `UNIQUE(repository_id,repo_id_hash,ref_hash)`；`idx_hf_ref_expiry(expires_at,id)`；`idx_hf_ref_commit(repository_id,repo_id_hash,commit_hash,id)` | branch/tag/PR alias、TTL revalidate |
| `huggingface_file` | `UNIQUE(repository_id,repo_id_hash,commit_hash,path_hash)`；`UNIQUE(asset_id)`；`idx_hf_file_page(revision_id,path_hash,id)`；`idx_hf_file_state(state,next_attempt_at,id)` | resolve exact、tree binding、fetch/failure worker |
| `huggingface_api_cache` | `UNIQUE(repository_id,route_hash,query_hash,request_hash)`；`idx_hf_api_expiry(expires_at,id)`；`UNIQUE(derived_asset_id)` | repo/tree/paths-info/refs response与validator |
| `huggingface_route_projection` | `UNIQUE(repository_id,route_hash)`；`idx_hf_projection_file(file_id,id)` | requested alias到canonical file、Nexus Browse path |
| `proxy_fetch_lease` | `PRIMARY KEY(repository_id,fetch_key_hash)`；`idx_proxy_fetch_lease_expiry(expires_at)` | 跨副本singleflight、takeover/fencing |

`repo_id_hash`、`ref_hash`、`path_hash`、`route_hash`、`query_hash`、`request_hash`和`fetch_key_hash`只控制索引宽度。Hash命中后必须比较canonical字段；碰撞不能变成identity match。Canonical repoId/ref/path同时保存原始display值与协议比较值。

关键规则：

- Exact resolve从`repository + repoId + commit + path`唯一索引进入，禁止`LIKE '%filename%'`扫描asset。
- Ref lookup、tree/file list、Browse/Search、Cleanup、migration和orphan cleanup使用keyset/forward-only cursor，不使用大OFFSET。
- `huggingface_api_cache`的raw/derived body都只是Blob reference；数据库不保存任意JSON全文。Transform schema升级可重建derived asset。
- File state至少包含expected size、Git OID、LFS SHA-256、Xet hash provenance、internal SHA-256、blob/asset、state、attempt、failure code和credential/policy generation。
- 批量asset/blob/usage/scan查询有默认500和硬上限，避免N+1、超大IN和一次materialize完整model catalog。
- Claim/takeover使用数据库时间、短事务、fencing token和条件更新；旧owner即使HTTP稍后成功也不能覆盖新generation。
- 上线前保存MySQL/PostgreSQL关键SQL的`EXPLAIN ANALYZE`；exact/high-selectivity查询不得全表扫描，keyset examined rows与limit同阶。

## Component、Browse、Search 与管理面

Nexus兼容component首期使用：

```text
format    = huggingface
namespace = Hub namespace（legacy单段repo为空）
name      = model repository name
version   = resolved full commit hash
kind      = model-revision
```

同一commit的config、tokenizer、model card、index和weight files属于同一个model revision component；metadata API cache不是模型文件，不得让Cleanup或扫描把repo-info JSON当weight。

Nexus 3.94实测Browse从`namespace/model/commit`进入，并在component下投影原始resolve path；repo-info metadata另有requested revision节点。kkrepo的`HuggingFaceBrowsePathProjector`必须先用M0固定exact `id/text/leaf/componentId/assetId`，再做写入时投影，不能在Browse请求中临时切URL字符串。Browse UI可以提供更清晰的逻辑视图：

```text
{namespace}/{model}/
  ├── refs/{branch-or-tag} -> {commit}
  └── revisions/{commit}/
      ├── README.md
      ├── config.json
      └── {subdir}/{model-file}
```

协议路径、Nexus Browse API投影和产品逻辑视图可以不同，但都必须指向同一canonical file/blob，不能复制模型bytes。

Model file classifier至少识别SafeTensors、PyTorch/pickle family、TensorFlow/Keras、Flax/MsgPack、ONNX、OpenVINO、GGUF、tokenizer/config/model card和shard index。扩展名歧义时保存`UNKNOWN`，不从`.bin`强行推断PyTorch。`*.safetensors.index.json`等shard manifest使用有界parser，验证weight_map只引用同commit内安全path。

Search至少支持format、namespace、repoId/name、commit、requested ref、path、file kind、model format、extension、pipeline/library、license、gated/private、Git OID、LFS/internal SHA-256和cache/scan状态。任意tags/model-card全文不进入未索引SQL contains；需要全文搜索时使用现有Search能力的显式有界投影。

Admin UI显示remote/auth health、credential generation、API/content/negative TTL、rate-limit reset、active fetch/bytes、failed checksum/redirect、model/revision/file counts、cache bytes、Cleanup/scan状态和最近错误。Cache invalidate只推进generation/删除投影，不直接在请求线程递归删除大Blob。

## 认证、Gated Models 与权限

- 所有model API和file GET/HEAD/POST paths-info走repository `READ`；repository create/update/delete、remote token和cache invalidate走repository admin权限。
- 客户端可以使用anonymous、Basic、GenericToken或现有session访问kkrepo。显式错误credential不能降级为anonymous。
- Client Authorization只用于kkrepo认证，绝不转发上游。Upstream只使用repository配置的Hugging Face fine-grained/read bearer token，并以`SecretCipher`加密落库。
- Remote token、cookie、signed URL、Basic userinfo和query secret不进入cache key、日志、metric、Browse、audit detail或exception message。API只返回masked状态和fingerprint/generation。
- Token轮换先验证新secret，再推进credential generation；旧generation的private/gated ref与auth结果立即失效。已有Blob是否继续可读按gated/private policy决定，默认先重新验证access。
- Repo info标记`gated`或`private`时，缓存只能由本地有`READ`权限的用户访问，并记录上游服务身份。管理员需要明确理解共享proxy token会把该账号获准内容分发给本地repository读者。
- 401/403、access撤销、token缺失或masked migration均fail closed；不得以stale-on-error绕过许可/访问变化。Public repo的5xx stale与gated auth failure是不同策略。
- Hub rate-limit按remote身份/IP计算。转发必要的RateLimit/Retry-After语义并做共享backoff；不接受客户端伪造`X-HF-Bill-To`改变计费身份，除非管理员提供显式受保护配置。

## Cleanup Policy 接入

Hugging Face proxy Cleanup主体是一个完整model commit，而不是单个weight shard：

```text
subject       = repoId + resolved commit component
family        = repoId
version       = commit hash（不可按字典序或SemVer排序）
usage         = 该revision所有canonical files的最大下载水位
publishedAt   = upstream commit time；缺失时使用首次observed time并标注来源
contentToken  = revision/file/blob/usage/policy generation摘要
```

接入要求：

- 首期支持age、last-download、include/exclude和Try Run/Execute；在增加基于commit time的格式专用ranking前，`retain N`保持不可用，不能给commit hash注册伪version comparator。
- 删除锁定model revision和相关file rows，复查content token、ref protection、scan protection与fencing；同一revision的所有canonical file引用作为一个batch解绑，失败可重试。
- 当前branch/tag binding、RUNNING/PENDING scan、active fetch和迁移中的Blob受保护。Ref移动后旧commit只有在grace和policy同时满足时可删除。
- Alias/Browse projection随canonical file删除；多个alias引用同Blob时不能提前回收。Blob由通用reference计数和GC决定最终删除。
- Proxy Cleanup只清本地cache，不删除上游model。下次请求可以重新回源，但gated/private内容必须重新验证auth。
- Repo info/tree/refs derived metadata按TTL/容量策略清理，不作为独立model release进入Cleanup结果。

## 制品安全扫描接入

Hugging Face模型不能直接套用“归档包 -> Syft -> Grype”并宣称安全。首期在现有capability gate、通用`artifact_change_event`、数据库task lease、policy/waiver和下载阻断链路上增加模型专用静态输入：

```text
subjectKind       = HF_MODEL_FILE
classification    = MODEL
repositoryId / componentId / assetId / blobId / blobSha256
repoId / resolvedCommit / path / fileKind / modelFormat
expected LFS SHA-256 / size / Git OID
inputSchema        = huggingface-model-file-v1
```

规则：

1. Repo info、tree、refs、model card、tokenizer text、shard index和hidden transform asset默认是metadata，不创建重量级model scanner task；可以进入独立的metadata/license检查。
2. `.safetensors`使用官方格式约束做静态header/offset/shape/duplicate-key/完整buffer校验，限制header大小、tensor count、dimension和整数溢出，不materialize tensor。
3. `.bin/.pt/.pth/.pkl/.pickle`等pickle family只做opcode/import/global/reduce静态分析；绝不调用`pickle.load`、`torch.load`或导入模型模块。结果必须明确“静态启发式，不是可安全执行证明”。
4. ONNX、Keras/H5、GGUF、MsgPack等只有在存在有界、无执行的parser时才注册candidate；未知或超限格式标记`NOT_APPLICABLE`/`REJECTED_BY_LIMIT`，不能送给通用解包器猜测。
5. Sharded model每个Blob独立扫描，revision聚合状态取最差结果；index声明的任一required shard blocked/missing时，模型revision不能显示为clean。
6. Upstream tree的security字段只作为advisory evidence保存，不替代本地任务，也不能自动把kkrepo状态标为PASSED。
7. Proxy Audit可完整缓存后立即服务并异步扫描；Enforce且pending阻断时，先完成cache/checksum和outbox，再返回真实客户端可重试的错误，不能把未完成Blob流给scanner或client。
8. Scanner capability digest包含input schema/parser版本。滚动升级中不支持模型输入的旧adapter不能领取task。

模型仓库中的Python、自定义operator和`trust_remote_code`文件可以下载和审计，但scanner不运行它们。kkrepo必须在UI和文档中区分“文件完整性通过”“静态模型检查通过”和“代码可安全执行”，不能合并成一个误导性绿色状态。

## 多副本、一致性与故障语义

- Ref revalidate、API transform、file cold fill、cache invalidate、Cleanup和scan都使用数据库真相、lease和fencing；本地scheduler只唤醒due work。
- 大body上传OSS/S3与checksum在事务外完成，finalize事务只做有界lock/CAS。数据库锁期间不等待公网或读写大Blob。
- 同一file的16/32并发冷请求跨两个副本只允许一个有效upstream body transfer；其它请求等待共享state或收到有Retry-After的明确可重试响应。
- Pod在upstream HEAD后退出：未发布任何file；lease过期后其它Pod重试。
- Pod在multipart中退出：不完整object不可读；abort/orphan worker按upload id和fencing回收。
- Pod完成Blob但finalize前退出：新owner验证provisional Blob后可复用；旧owner不能凭过期token提交。
- Ref从A移动到B时，客户端只会看到完整A或完整B generation；已经解析A的下载不被B覆盖。
- DB不可用时不创建新binding、不回源发布、不执行Cleanup/scan claim。已有immutable Blob是否可读沿用全局DB故障策略，格式模块不单独fail open。
- 所有worker有batch、max batches、lease、deadline、kill switch、metric和bounded cleanup；节点本地cache有TTL/水位失效且可重建。

## Nexus 迁移设计

Definition migration：

- 识别Nexus 3.77+ `huggingface-proxy`，迁移name、online、blob store映射、strict-content-type意图、remote、metadata/content/negative TTL、auto-block、timeout、routing rule和可证明的HTTP配置。
- Preemptive bearer token只有source export提供明文且管理员授权时才导入。Masked/unavailable secret不生成placeholder；target repository保持offline并报告manual action。
- Nexus文件Blob store建议不能改变kkrepo生产Blob策略；target继续使用用户选择的OSS/S3，并在dry-run报告预计bytes/objects。

Content migration：

- Proxy cache只在管理员显式选择且source profile通过shape gate时迁移。初始自动范围限定Nexus 3.94.0 exact datastore/content shape；其它版本需fixture后扩展。
- 迁移器从component `group/name/version`、asset resolve path、checksum/size和Blob恢复`repoId + commit + path`。Version不是完整commit、path无法解析、Blob缺失或SHA-256不符时该项不能标为FULL。
- Nexus缓存的Xet-backed文件已经是完整重建Blob；直接校验并迁移完整bytes，不迁移Xet token、signed URL、CAS metadata或chunk cache。
- Mutable alias asset（如`resolve/main`）必须绑定component的resolved commit。无法证明commit的alias只进入metadata rebuild/remote revalidate，不能猜测。
- Repo-info/tree/refs、negative cache、validator、Browse/Search和fetch lease默认在target重建；只有raw metadata具备明确commit/validator且transform schema兼容时才复用Blob。
- Checkpoint使用source repository + stable source component/asset identity；重复运行复用已校验Blob，writer/takeover保持幂等。

Dry-run报告repository、model revisions、files、bytes、public/gated/private、valid/invalid identity、missing secret、generated metadata filtered、expected Blob reuse和manual actions。Migration E2E在Nexus reference缓存public与测试gated model，迁移到MySQL/PostgreSQL target后运行current/legacy client、校验commit/files/checksum/Browse/Search和双副本读取；unknown shape和missing token必须fail closed。

## 性能与 Nexus 对比验收

实现PR必须新增`compare-huggingface-nexus`性能脚本和可复现基线文档。公网Hugging Face只用于smoke；门禁使用可控Hub API + LFS/Xet bridge模拟器和相同文件bytes，消除rate-limit/CDN抖动。

### 对比环境与方法

- Reference固定为implementation compatibility lane的Nexus版本，初始3.94.0；记录edition、JVM、数据库、Blob store、repository配置和image digest。
- Candidate使用同机同资源kkrepo PostgreSQL主对比，MySQL跑相同正确性、客户端和query-plan矩阵。生产门禁另以S3-compatible Blob执行，不能只给file storage结果。
- HTTP热路径预热至少32次，并发16下至少250次、3轮交替顺序；大文件场景同时报告MiB/s、time-to-first-byte、p50/p95、错误率和对象存储request数。
- 每次计时前校验repo commit、tree file set、linked SHA-256/size、完整GET SHA-256、Range bytes和真实client结果。错误页、external redirect、空文件或不同模型不能计入性能。

### 必测场景

1. Repo info、revision info、refs、1/10/100页tree、paths-info 1/100/1000 paths的warm/validator/304。
2. 4 MiB、256 MiB、5 GiB文件的warm HEAD/GET/64 KiB Range；nightly覆盖至少20 GiB和multipart边界。
3. Xet-backed文件的cold HEAD/full fill、warm GET、linked SHA-256/size和确认客户端无外网访问。
4. `hf_hub_download`、`snapshot_download`和`hf download`的cold/warm client cache与server cache组合。
5. 16/32并发同文件、不同文件和两个副本同时cold miss；记录upstream bytes放大、lease wait、takeover和失败重试。
6. Branch移动A->B、tag/PR ref、tree分页、absolute Link rewrite和同commit多alias。
7. 404 negative、401/403 gated、429 reset、5xx stale、redirect loop/host变化、checksum/size mismatch和truncated body。
8. Browse root/model/revision/file、Search exact/prefix和Components page，在100万revision/file数据集记录SQL数与rows examined。
9. Cleanup Try Run/Execute、scan Audit/Enforce与前台大文件下载并发，报告foreground p95、object requests、lock wait和stale/skip。
10. Pod在HEAD、multipart、Blob complete、finalize四个故障点退出后的接管和orphan cleanup。

### 发布门禁

- 所有场景成功率100%，commit/file set/checksum/size/header和真实客户端结果先于速度通过。
- Warm metadata吞吐不低于同机Nexus`0.80x`，p95不高于`1.25x`；kkrepo额外支持的current-client tree路径需保存独立绝对基线。
- Warm文件GET/Range吞吐不低于Nexus`0.90x`，p95不高于`1.15x`；S3 range不得退化为下载完整对象。
- Cold full fill在相同upstream/Blob条件下MiB/s不低于Nexus`0.90x`，同文件并发upstream bytes不超过单文件大小的`1.10x`加协议metadata开销。
- 5 GiB/20 GiB下载heap保持有界，不按文件大小增长；数据库事务、临时磁盘和multipart part数落在配置上限内。
- 100万file rows下exact resolve、ref lookup、fetch claim、Browse、Search、Cleanup和scan candidate命中声明索引，无不必要full scan、external sort或unbounded temp table。
- Background Cleanup/scan/orphan worker期间foreground p95相对无后台基线增长不超过20%，且无长事务、未处理deadlock或旧fencing owner提交。

未达门禁时必须附profile、statement digest、object-store request trace和`EXPLAIN ANALYZE`复测。阈值调整需要在实现PR中提供Nexus/kkrepo原始结果、正确性证据、风险和明确批准记录。

## 测试与兼容性矩阵

### M0：Nexus 3.94 reference 基线

在注册可创建recipe前固定：

- `huggingface-proxy` REST schema、默认remote/TTL/HTTP字段、format/type和不支持hosted/group的响应。
- Repo info/revision、tree、paths-info、refs和resolve route的支持/拒绝矩阵；记录Nexus对current client tree返回400的已知差异。
- Public、private、gated、missing、disabled model以及remote bearer缺失/错误/撤销时的status、body和cache行为。
- Branch/tag/full commit/`refs/pr/N`、单段repo ID、nested file、percent-encoding和invalid path。
- 普通Git、legacy LFS、Xet-backed文件的HEAD/GET/Range/conditional、ETag、linked headers、Content-Type/Disposition、redirect和cache timing。
- Xet hash/Link/Location的剥离、tree `xetHash`处理，以及客户端是否尝试xet-read-token。
- Component/Search identity、model revision assets、repo-info metadata component和exact Browse tree。
- Strict content type开/关、mismatch、large model timeout、429/5xx/negative/auto-block/stale。

只有Date、request ID、signed URL、rate-limit remaining、timestamp等已证明非确定字段可规范化；path、commit、file set、hash、size、status、关键header和客户端结果不得无依据放宽。官方current client正确而Nexus失败的路径要记录为明确的兼容修复，不把Nexus bug当目标行为。

### 协议、数据库与安全测试

- Path/parser fixture覆盖namespace/name、legacy ID、encoded PR ref、Unicode、nested path、点段、double decode、encoded separator、超长输入和保留route冲突。
- Metadata fixture覆盖分页、absolute/relative Link、unknown fields、deep/large JSON、duplicate key、invalid UTF-8、xetHash、LFS metadata和commit drift。
- File fixture覆盖Git blob、LFS、Xet bridge、wrong Git OID/SHA-256/size、truncated/oversized body、range、redirect loop、DNS rebinding和signed-query redaction。
- MySQL/PostgreSQL contract覆盖unique/FK、ref generation、immutable commit、file state、lease/fencing、winner/waiter、takeover、route projection、repository delete和keyset page。
- 双副本覆盖same/different file cold fill、credential rotation、ref move、429 shared backoff、worker crash和rolling restart。
- SafeTensors恶意header/offset/shape、pickle dangerous opcodes/imports、shard path traversal、scanner timeout和Cleanup/scan/fetch race进入CI。

### 真实客户端 E2E

至少覆盖实现时current stable、Nexus已验证的legacy line和Transformers声明的最低/常用`huggingface_hub`版本；初始固定0.34.6与1.27.0：

1. `hf_hub_download`下载普通config和Xet-backed weight，校验cache目录中的commit、etag和bytes。
2. `snapshot_download`下载完整tiny model和allow/ignore pattern子集，验证tree分页/cache completeness。
3. `hf download`分别按main、tag、commit、`refs/pr/N`执行单文件与整仓下载。
4. Transformers `AutoConfig`、`AutoTokenizer`、tiny `AutoModel.from_pretrained`和Diffusers tiny pipeline从`HF_ENDPOINT`加载。
5. 安装`hf_xet`且不设置`HF_HUB_DISABLE_XET`；客户端网络只允许kkrepo，仍成功并证明未请求本地xet token/CAS。
6. Basic/GenericToken/anonymous访问；显式错误credential不降级，client token不转发上游。
7. 可控gated/private upstream验证正确remote token、缺失/撤销/轮换、local权限和fail-closed stale。
8. Branch在下载中从A移到B，只得到完整A或完整B；commit-pinned下载继续可复现。
9. 429/5xx/timeout/redirect/checksum drift后重试，negative和auto-block不污染正常文件。
10. 两副本轮流服务metadata、HEAD和GET，cache/blob/component/scan状态一致。

### 迁移 E2E

- 在Nexus 3.94缓存普通Git、LFS和Xet-backed model files，保存component/asset/Browse/Search/header/client基线。
- 显式迁移definition与selected proxy cache到MySQL/PostgreSQL target，验证dry-run、resume、重复运行、worker takeover、checksum和Blob reuse。
- 使用legacy/current client再次执行单文件与snapshot下载，验证commit和bytes不变、Xet不绕过target。
- Unknown source shape、invalid component version、alias无commit、corrupt/missing Blob和masked remote token全部fail closed。

## 可观测性与审计

建议指标：

- `kkrepo_huggingface_requests_total{repository,route,result,cache}`
- `kkrepo_huggingface_upstream_requests_total{repository,kind,result}`
- `kkrepo_huggingface_upstream_bytes_total{repository,kind}`
- `kkrepo_huggingface_cache_fill_duration_seconds{repository,result}`
- `kkrepo_huggingface_cache_fill_bytes{repository}`
- `kkrepo_huggingface_active_fetches{repository}`
- `kkrepo_huggingface_fetch_waiters{repository}`
- `kkrepo_huggingface_revision_rebind_total{repository}`
- `kkrepo_huggingface_xet_hints_stripped_total{repository,source}`
- `kkrepo_huggingface_rate_limit_seconds{repository,bucket}`
- `kkrepo_huggingface_checksum_failures_total{repository,kind}`

`repoId`、commit、path、token fingerprint和signed host不作为metric label，避免高基数或secret泄漏；它们只进入受权限控制、长度受限且redacted的审计detail。

审计至少记录repository、route kind、repoId hash/display、requested ref、resolved commit、path hash/display、operation、local actor、remote credential generation、cache result、bytes、checksum result、source host class和结果。禁止记录remote/client token、signed query、cookie、Xet access token和完整model metadata。

## 实施顺序

1. M0 与 protocol foundation
   - 增加Nexus 3.94 Hugging Face probe、可控Hub/LFS/Xet bridge和legacy/current client fixture。
   - 新增`protocol-huggingface`的raw path、revision/identity、header、tree transform和文件分类测试。
   - 此阶段不注册可创建recipe。

2. Metadata 与单文件proxy
   - 增加format/recipe、repo info/revision/tree/paths-info/refs、Link rewrite、Xet hint去除和remote bearer。
   - 增加双数据库revision/ref/file/API cache schema、通用fetch lease/fence和S3 multipart finalization。
   - 完成GET/HEAD/Range/conditional、current/legacy单文件与snapshot E2E和双副本接管。

3. 产品、Cleanup 与安全扫描
   - 完成component/Browse/Search、Admin/Browse UI、cache运维、commit级Cleanup和模型静态scanner input。
   - 覆盖gated/private、credential rotation、Audit/Enforce、sharded model和所有secret redaction。

4. 迁移与性能门禁
   - 增加Nexus 3.77+ definition、3.94 exact content shape gate、dry-run/resume/checksum报告和Migration E2E。
   - 增加Nexus/kkrepo性能脚本、S3大文件/百万row query-plan门禁和基线文档；达到本文标准后才标记路线图完成。

## 验收标准

- `huggingface-proxy`可创建、编辑、停用和删除；首期明确只支持Models proxy。
- `HF_ENDPOINT`指向kkrepo后，legacy/current `huggingface_hub`的单文件和snapshot下载、`hf download`、Transformers/Diffusers tiny model均通过。
- Branch/tag/PR ref精确解析为commit；metadata、tree、files和component始终绑定同一commit，commit-pinned下载可复现。
- 普通Git、LFS和Xet-backed文件都完整写入OSS/S3并校验；客户端不收到external redirect、Xet token/CAS URL，也不能绕过kkrepo出网。
- 冷HEAD/GET/Range跨副本只产生一个有效回源，节点退出可接管，不发布partial/truncated/wrong-checksum Blob。
- Gated/private token加密、隔离、轮换和fail-closed stale符合设计；local权限与remote服务身份边界清晰可审计。
- Browse/Search/component与Nexus fixture对齐，当前client tree兼容缺口被补齐；alias投影不复制Blob或扫描任务。
- Cleanup按完整commit安全删除；SafeTensors/pickle等扫描不执行模型或代码，upstream security字段不冒充本地PASSED。
- Nexus migration对unknown shape、invalid commit、corrupt Blob和missing secret失败关闭，dry-run/resume/checksum/报告完整。
- MySQL/PostgreSQL关键身份、ref、file、lease/fencing 与 keyset 查询由双库 contract 和声明索引约束；S3 双副本并发 cold miss 与同机 Nexus 4 MiB 协议性能门禁已通过。256 MiB/5 GiB、百万 row、跨可用区对象存储和后台混合负载属于部署容量验收，必须在目标生产规格上运行，不能把本机结果当作 SLA。

## 参考资料

- [Hugging Face Hub：下载文件](https://huggingface.co/docs/huggingface_hub/en/guides/download)
- [Hugging Face Hub API 与 OpenAPI](https://huggingface.co/docs/hub/en/api)
- [Hugging Face Hub OpenAPI JSON](https://huggingface.co/.well-known/openapi.json)
- [`huggingface_hub` v1.27.0：file download / resolve metadata](https://github.com/huggingface/huggingface_hub/blob/v1.27.0/src/huggingface_hub/file_download.py)
- [`huggingface_hub` v1.27.0：snapshot download / tree cache](https://github.com/huggingface/huggingface_hub/blob/v1.27.0/src/huggingface_hub/_snapshot_download.py)
- [`huggingface_hub` v1.27.0：Hub API client](https://github.com/huggingface/huggingface_hub/blob/v1.27.0/src/huggingface_hub/hf_api.py)
- [Hugging Face Xet storage](https://huggingface.co/docs/hub/en/xet/index)
- [Xet：从 Hub resolve 获取 file ID](https://huggingface.co/docs/xet/en/file-id)
- [Xet：LFS backward compatibility](https://huggingface.co/docs/hub/en/xet/legacy-git-lfs)
- [Xet download protocol](https://huggingface.co/docs/xet/download-protocol)
- [Xet authentication and authorization](https://huggingface.co/docs/xet/auth)
- [Hugging Face user access tokens](https://huggingface.co/docs/hub/en/security-tokens)
- [Hugging Face gated models](https://huggingface.co/docs/hub/en/models-gated)
- [Hugging Face Hub rate limits](https://huggingface.co/docs/hub/main/rate-limits)
- [Hugging Face model cards](https://huggingface.co/docs/hub/en/model-cards)
- [Hugging Face pickle scanning](https://huggingface.co/docs/hub/en/security-pickle)
- [SafeTensors format](https://github.com/safetensors/safetensors#format)
- [Sonatype Nexus Repository：Hugging Face Repositories](https://help.sonatype.com/en/hugging-face-repositories.html)
- [Sonatype Nexus Repository 3.77 release notes](https://help.sonatype.com/en/sonatype-nexus-repository-3-77-0-release-notes.html)
- [Sonatype：Nexus component definitions](https://help.sonatype.com/en/nexus-repository-component-definitions.html)
- [kkRepo Cleanup Policy 开发设计说明](cleanup-policy-design.md)
- [kkRepo 制品安全扫描开发设计说明](security-scanning-design.md)
- [kkRepo Conan 2 仓库开发设计说明](conan-repository-design.md)
- [kkRepo Alpine / APK 仓库开发设计说明](alpine-apk-repository-design.md)
