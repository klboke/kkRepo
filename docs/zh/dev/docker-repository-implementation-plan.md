# Docker 仓库实现说明

本文记录 kkrepo Docker/OCI 仓库格式的设计和实现说明。目标不是重新发明镜像仓库协议，而是在 Nexus Docker 仓库行为、Docker Registry HTTP API V2 和 OCI Distribution 规范之间取兼容交集，并按 kkrepo 的 MySQL + OSS/S3 + 多副本约束落地。

## 当前支持状态

Docker / OCI 已作为受支持仓库格式实现：

- `docker-hosted`、`docker-proxy`、`docker-group` recipe。
- Registry HTTP API V2 `/v2/...` 路由，支持共享入口 path-based routing 和仓库级 connector port。
- Bearer token login、仓库权限、匿名/读/写/删除授权，以及 Docker challenge/token 行为。
- Hosted push/pull、可恢复 upload session、upload complete 流式提交、blob range 读取、manifest/tag/blob 删除、跨仓库 blob mount 和 cleanup policy worker。
- Proxy pull，包括上游 registry auth、Docker Hub `library` 命名空间补偿、manifest/blob/tag 缓存、negative cache、digest 校验和上游指标。
- Group pull，包括按成员顺序解析、group member cache，以及成员内容变更后的 cache 失效。
- OCI image manifest/index、subject/referrers 索引、`/referrers/<digest>`、`artifactType` 过滤和 `OCI-Subject` 响应。
- Docker browse 详情、Docker 迁移验证脚本、真实客户端兼容脚本，以及手动/按 label 触发的 OCI conformance workflow。
- Docker 专项观测指标，包括 upload、mount、cache、digest verification、cleanup、referrers、active transfer 和 proxy upstream。

Docker Registry V1 API 和 `docker search` 不属于当前支持面。现代 Docker/OCI 工作流使用 Registry V2 和 OCI Distribution；如果后续 Nexus 迁移出现明确需求，可以再评估 search-only 兼容层。

## 调研基线

实现前必须对照以下协议和参考行为：

- Docker Registry HTTP API V2 / CNCF Distribution：`/v2/` 探测、manifest、blob、upload、tag、catalog、错误模型和 `Docker-Content-Digest` 等 header。
- OCI Distribution Specification：OCI pull、push、content discovery、content management、referrers API、OCI conformance 分类。
- OCI Image Spec：image manifest、image index、descriptor、digest、media type、subject/referrers 语义。
- Docker Registry token authentication：`WWW-Authenticate: Bearer ...` challenge、token endpoint、`repository:<name>:pull,push` scope。
- Nexus Repository Docker Registry 文档：Docker hosted/proxy/group、path-based routing、port connectors、Docker Hub `library` 命名空间、OCI 支持和 Nexus UI/客户端访问方式。

关键结论：

- Docker 客户端并不按普通制品仓库的 `/repository/<repo>/...` 形态访问。Nexus 的 path-based routing 使用镜像名第一段作为仓库名，例如 `docker pull nexus.example/docker-group/library/alpine:latest`，对应 registry API 路径形态是 `/v2/docker-group/library/alpine/manifests/latest`。因此 kkrepo 需要新增 Docker 专用 `/v2/...` 路由，并把第一段解析为 kkrepo 仓库名。
- Docker pull 的最小可用面是 `/v2/`、manifest GET/HEAD、blob GET/HEAD、tag list 和认证 challenge。Docker push 必须支持 blob upload session，实际客户端常用 `POST /blobs/uploads/` + `PATCH` + `PUT ?digest=...`，不能只做单次 PUT。
- Docker/OCI 是内容寻址模型。blob、manifest digest 必须由服务端按原始字节计算，不能重排 JSON、改写 timestamp 或规范化 body 后再算 digest。
- OCI referrers 已经成为镜像签名、SBOM、attestation 等场景的关键能力，第一阶段可以不阻塞普通 `docker pull/push`，但数据模型应一次性预留 subject/referrers 索引。
- Nexus Docker 既支持 path-based routing，也支持可选的 HTTP/HTTPS port connectors。kkrepo 第一阶段应支持仓库级 connector 作为可选独立流量入口，避免大镜像层上传/下载挤占主服务端口、管理 UI、REST API 和普通制品协议请求；但 Docker 仓库不应强制配置 connector 端口，未启用 connector 时仍可通过 path-based routing 或反向代理共享入口访问。具体监听端口不应是全局单值，而应作为 Docker 仓库创建/更新时的仓库属性保存。每个启用 connector 的 Docker 仓库可以配置不同 connector 端口，服务端通过本地端口映射到固定 repository id；path-based routing 仍可作为共享入口或反向代理场景的兼容形态。

## 功能范围

### 第一阶段必须实现

1. Docker hosted 仓库
   - 创建 `docker-hosted` recipe，支持 `ALLOW`、`ALLOW_ONCE`、`DENY` 写策略。
   - `/v2/` 探测，返回 `Docker-Distribution-API-Version: registry/2.0`。
   - Docker Bearer token challenge 和 token endpoint，兼容 `docker login`、`docker pull`、`docker push`。
   - blob HEAD/GET，支持 `Docker-Content-Digest`、`Content-Length`、`Content-Type` 和 Range 下载。
   - blob upload session：start、status、chunk append、complete、cancel。
   - cross-repository blob mount：同 blob store 且权限允许时只登记引用，不重复复制大 blob。
   - manifest PUT/GET/HEAD/DELETE，支持 Docker schema2 manifest、Docker manifest list、OCI image manifest、OCI image index。
   - tag list 和 tag delete，支持 `n`、`last` 分页。
   - manifest digest、tag、blob 的 MySQL 元数据和 OSS/S3 blob 持久化。

2. Docker proxy 仓库
   - 创建 `docker-proxy` recipe，支持远端 URL、远端 Basic/Bearer token 认证、content/max-age 配置。
   - 拉取并缓存远端 manifest、manifest list/index、blob。
   - tag 引用按 TTL revalidate；digest manifest 和 blob 按内容寻址缓存。
   - 支持 Docker Hub 单段镜像名补偿：`alpine` 在代理 Docker Hub 时按 Nexus 参考行为映射到 `library/alpine`。
   - 支持代理另一个 Nexus Docker 仓库时配置完整远端仓库 URL。

3. Docker group 仓库
   - 创建 `docker-group` recipe，按成员顺序解析 manifest、blob、tag。
   - group 只读，push/delete 返回 Nexus 参考行为对应状态。
   - tag 冲突时第一命中成员优先。
   - blob 查找应优先使用 manifest 所属成员，再按成员顺序兜底，避免 group 拉取跨成员镜像时误读。

4. 兼容性测试和真实客户端验证
   - 新增面向 Nexus 参考实例的 Docker black-box 兼容测试。
   - 使用 `docker`、`oras` 或 `skopeo` 验证 hosted push/pull、proxy pull、group pull。
   - 引入 OCI Distribution conformance 作为补充验收，按 Pull、Push、Content Discovery、Content Management 分阶段打开。

### 可选加固和非目标

- 高级 connector TLS/SNI 管理和端口级 access log 集成属于部署侧加固项。
- 跨 blob store 的 server-side copy blob mount 是可选优化；当前实现会在源和目标 blob store 不同时 fallback 到普通 upload。
- Docker Registry V1 API 和 `docker search` 为非目标，除非后续出现真实迁移需求，再评估 search-only 兼容层。

## URL 与路由设计

新增 `DockerRegistryController`，不要挂到现有 `RepositoryContentController` 的 `/repository/{name}` 下。

## Docker 流量端口设计

Docker 镜像层通常远大于 Maven/npm/PyPI 等包，请求连接持续时间也更长。第一阶段应支持把 Docker registry 流量从主应用端口拆到可选的仓库级 connector，并把具体端口绑定到 Docker 仓库配置中，而不是使用一个全局 Docker 端口：

- 主服务端口：`8080`，继续服务 `/repository/...`、`/admin/`、`/browse/` 和管理 REST。
- 管理端口：`8081`，继续服务 actuator。
- Docker 仓库 connector 端口：可选启用。例如 `docker-hosted` 仓库配置 `8082`，`docker-proxy` 仓库配置 `8083`，只服务该仓库的 `/v2/...` 和 Docker token flow；未配置 connector 的仓库仍可通过 path-based routing 或反向代理共享入口访问。

仓库级 connector 端口的目标不是替代 path-based routing，而是提供更清晰的流量隔离面：

- 入口层可以给不同 Docker 仓库端口配置更长的 upload/download timeout、更大的 body size、更宽的连接池和独立限流。
- 主服务端口可以保持较短 timeout，避免镜像层长连接拖慢 UI、权限管理、迁移控制台和普通仓库协议请求。
- Kubernetes Service / Ingress 可以按仓库端口独立暴露 Docker 流量，便于做 HPA、监控、告警和熔断。
- Tomcat 层应尽量给 Docker connector 配置独立 executor、`maxConnections`、`acceptCount`、`connectionTimeout` 和上传并发限制。单独监听端口如果仍共用同一线程池，只能解决入口路由隔离，不能完全解决 JVM 内线程争用。
- OSS/S3 客户端也应为 Docker 大对象读写预留独立 bulkhead 或连接池参数，避免 Docker layer 拉取耗尽普通制品请求的对象存储连接。

全局运行时配置建议预留，用于启用 connector 能力和控制共享资源池，不包含具体仓库端口：

- `kkrepo.docker.connector.enabled=true`
- `kkrepo.docker.connector.threads.max`
- `kkrepo.docker.connector.max-connections`
- `kkrepo.docker.connector.accept-count`
- `kkrepo.docker.connector.connection-timeout`
- `kkrepo.docker.transfer.max-concurrent-uploads`
- `kkrepo.docker.transfer.max-concurrent-downloads`
- `kkrepo.docker.transfer.response-buffer-size`

Docker 仓库创建/更新属性建议预留：

- `docker.connector.enabled=false`，默认关闭；打开后才需要配置端口。
- `docker.connector.port=8082`，仅当 `docker.connector.enabled=true` 时必填。
- `docker.connector.public-url=https://registry.example:8082`，可选，用于 UI 示例、token `service`/realm 展示和反向代理场景。

端口约束：

- `docker.connector.port` 只对启用 connector 的 Docker format 仓库有效，并应在同一 kkrepo 部署内唯一。
- 未启用 connector 时允许端口为空，不能因为端口为空阻断仓库创建或迁移。
- 已启用 connector 的端口不能与主服务端口、管理端口或其它 Docker 仓库 connector 端口冲突。
- 仓库创建/更新时需要在 MySQL 事务中校验已启用 connector 的端口唯一性；多副本启动时根据 MySQL 中的 Docker 仓库属性构建本地 `port -> repository_id` 映射。
- 如果运行时暂不支持无重启新增监听端口，第一阶段可以要求端口变更后滚动重启实例，但文档和 UI 必须明确这一运维语义。

路由策略：

- 仓库级 connector 主入口：`http(s)://<host>:<repo-port>/v2/<image...>/...`。
- Docker 客户端镜像引用：`<host>:<repo-port>/<image>:<tag>`。
- 可选共享入口或反向代理 path-based 形态：`http(s)://<host>:<shared-port>/v2/<repo>/<image...>/...`，客户端引用为 `<host>:<shared-port>/<repo>/<image>:<tag>`。

路径解析规则：

| 请求 | 解析方式 |
| --- | --- |
| `GET /v2/` | registry 探测，不绑定具体仓库 |
| 仓库级 connector 上的 `/v2/<image...>/manifests/<reference>` | 仓库由本地监听端口映射得到，`<image...>` 为 Docker repository name |
| path-based 共享入口上的 `/v2/<repo>/<image...>/manifests/<reference>` | `<repo>` 为 kkrepo 仓库名，`<image...>` 为 Docker repository name |
| `/v2/<image...>/blobs/<digest>` 或 `/v2/<repo>/<image...>/blobs/<digest>` | digest 为 `sha256:<hex>` 等 OCI digest |
| `/v2/<image...>/blobs/uploads/` 或 `/v2/<repo>/<image...>/blobs/uploads/` | 创建 upload session |
| `/v2/<image...>/blobs/uploads/<uuid>` 或 `/v2/<repo>/<image...>/blobs/uploads/<uuid>` | 读取、追加、完成或取消 upload session |
| `/v2/<image...>/tags/list` 或 `/v2/<repo>/<image...>/tags/list` | tag list |
| `/v2/<image...>/referrers/<digest>` 或 `/v2/<repo>/<image...>/referrers/<digest>` | OCI referrers |

实现时不能按固定段数切分 Docker image name。`<image...>` 允许多级路径，应通过右侧哨兵段解析：`/manifests/`、`/blobs/`、`/blobs/uploads/`、`/tags/list`、`/referrers/`。

为了兼容迁移和代理场景，可以额外预留内部路由 `/repository/<repo>/v2/<image...>/...`，但不作为 Docker 客户端主入口。公开客户端文档应优先使用仓库级 connector port 的 port-based 形态；如部署了共享入口或反向代理，再补充 path-based routing 示例。

## 数据模型规划

现有 `component`、`asset`、`asset_blob` 可以承载通用内容和 browse/search，但 Docker 需要额外关系表表达 tag、manifest digest、manifest 引用和 upload session。

建议新增 Flyway 迁移：

### `docker_manifest`

保存 manifest 原始 JSON blob 及其 digest 元数据。

核心字段：

- `id`
- `repository_id`
- `image_name`
- `image_name_hash`
- `digest_algorithm`
- `digest`
- `digest_hash`
- `media_type`
- `artifact_type`
- `subject_digest`
- `subject_digest_hash`
- `asset_id`
- `size`
- `pushed_by`
- `pushed_by_ip`
- `deleted_at`
- `attributes_json`
- `created_at`
- `updated_at`

约束：

- `UNIQUE(repository_id, image_name_hash, digest_hash)`
- `KEY(repository_id, subject_digest_hash)`
- `KEY(repository_id, image_name_hash, updated_at)`

manifest body 以 `asset_blob` 保存原始字节。`docker_manifest.asset_id` 指向对应 asset，asset path 可以采用 `docker/manifests/<image-name>/sha256/<hex>` 这类稳定内部路径。

### `docker_tag`

保存 tag 到 manifest digest 的当前指针。

核心字段：

- `repository_id`
- `image_name`
- `image_name_hash`
- `tag`
- `tag_hash`
- `manifest_id`
- `manifest_digest`
- `pushed_by`
- `pushed_by_ip`
- `created_at`
- `updated_at`

约束：

- `UNIQUE(repository_id, image_name_hash, tag_hash)`
- `KEY(repository_id, image_name_hash, tag)`

tag 不是 blob，不应作为大对象存储；它是 MySQL 中的可事务更新指针。

### `docker_manifest_reference`

保存 manifest 内引用的 config、layer、child manifest，用于 push 校验、GC、referrers 和 UI 展示。

核心字段：

- `manifest_id`
- `repository_id`
- `image_name`
- `reference_kind`：`CONFIG`、`LAYER`、`MANIFEST`
- `digest`
- `digest_hash`
- `media_type`
- `size`
- `platform_json`
- `annotations_json`

约束：

- `KEY(repository_id, digest_hash)`
- `KEY(manifest_id, reference_kind)`

### `docker_upload_session`

保存 blob upload 会话，必须是 MySQL 真相，不能只放 JVM 内存。

核心字段：

- `uuid`
- `repository_id`
- `image_name`
- `status`：`STARTED`、`COMPLETING`、`COMPLETED`、`CANCELLED`、`EXPIRED`
- `next_offset`
- `digest_algorithm`
- `expected_digest`
- `created_by`
- `created_by_ip`
- `expires_at`
- `locked_by`
- `locked_until`
- `attributes_json`
- `created_at`
- `updated_at`

### `docker_upload_chunk`

如果底层 OSS/S3 不提供可跨副本 append 的 staging 能力，每个 PATCH chunk 先写入临时对象并登记。

核心字段：

- `session_uuid`
- `chunk_index`
- `start_offset`
- `end_offset`
- `object_key`
- `sha256`
- `size`
- `created_at`

完成 upload 时按 chunk 顺序流式合并到最终 digest object，再写入 `asset_blob`。清理任务按 `expires_at` 删除未完成 session 和临时 chunk object。

## Blob 与对象存储策略

Docker blob 是内容寻址，最终存储应按 digest 稳定命名，例如：

- blob：`docker/blobs/sha256/<first-two>/<hex>`
- manifest：`docker/manifests/<image-name>/sha256/<hex>`
- upload chunk：`docker/uploads/<uuid>/<chunk-index>`

写入流程：

1. 上传 body 进入跨副本可恢复 staging，不落单机临时目录作为唯一真相。
2. 完成时计算 digest 和 size，校验客户端 `digest` 参数。
3. 按 digest object key 写入 OSS/S3。
4. 在 MySQL 事务中写入 `asset_blob`、`asset`、`docker_manifest` 或 blob asset 引用。
5. 事务失败时删除刚写入且未被引用的 object，或写入待清理 marker。

去重策略：

- 同 blob store 下相同 `sha256 + size` 的 blob 可复用。
- cross-repository mount 要同时检查源仓库 pull 权限和目标仓库 push 权限。
- 如果源 blob 和目标仓库使用不同 blob store，第一阶段返回 `202` 开始普通 upload；后续可以做跨 store copy 优化。

## 权限与认证设计

Docker 客户端优先使用 Bearer token。现有 `RepositorySecurityFilter` 对 `/repository/...` 的 Basic challenge 不足以兼容 Docker，需要新增 Docker 专用认证链。

### Challenge

未认证请求返回：

```text
401 Unauthorized
WWW-Authenticate: Bearer realm="<base-url>/service/rest/v1/docker/token",service="<registry-service>",scope="repository:<repo>/<image>:pull"
Docker-Distribution-API-Version: registry/2.0
```

push 场景 scope 为 `pull,push`，delete 场景可映射到 `delete`。

### Token endpoint

新增：

- `GET /service/rest/v1/docker/token`
- 可兼容 `service`、`scope`、`client_id`、`offline_token`
- 支持 Basic auth、已有 session、API key 或匿名主体
- 返回 `token` 和 `access_token`，`expires_in` 不低于 Docker 客户端兼容要求

token 实现建议：

- 第一阶段使用短生命周期、MySQL 可校验的 opaque token，避免多副本签名 key 分发问题。
- 如果后续改成 JWT，签名密钥必须来自共享配置，并支持 key rotation。

scope 到 kkrepo 权限映射：

| Docker action | kkrepo permission |
| --- | --- |
| `pull` | `READ`，必要时也检查 `BROWSE` |
| `push` | blob upload 为 `ADD`，manifest/tag 覆盖为 `EDIT` |
| `delete` | `DELETE` |
| catalog | `ADMIN` 或专用 registry browse 权限 |

匿名 pull 必须遵循现有 anonymous-read 配置和仓库权限，不能绕过 `SecurityAuthorizationCache`。

## Hosted 服务设计

建议拆分：

- `protocol-docker`：路径解析、digest/media type 校验、manifest descriptor 解析、错误模型。
- `server/docker/DockerRegistryController`：HTTP 路由和响应装配。
- `DockerHostedService`：hosted pull/push/delete 行为。
- `DockerUploadService`：upload session、chunk、commit、cancel。
- `DockerManifestStore`：manifest 原始字节、tag、reference、referrers。
- `DockerBlobStore`：blob asset 和 asset_blob 读写、去重、mount。
- `DockerErrorAdvice`：registry error JSON。
- `DockerResponseSupport`：`Docker-Content-Digest`、`Location`、`Range`、`Link`、`OCI-Subject` 等 header。

Hosted 行为清单：

| 能力 | 行为 |
| --- | --- |
| `/v2/` | 200 或 401，始终带 registry API header |
| manifest GET/HEAD | tag 或 digest 命中返回 200；digest header 为 manifest digest；按 Accept 做基本 media type 选择 |
| manifest PUT | 保存原始 body，计算 digest，解析 descriptor，校验 referenced blob/manifest 是否存在 |
| tag PUT | `PUT /manifests/<tag>` 更新 `docker_tag` 指针 |
| digest PUT + tag 参数 | 支持 OCI `?tag=...` 批量 tag |
| blob HEAD/GET | 按 digest 查找，支持 Range；未命中 404 `BLOB_UNKNOWN` |
| upload POST | 返回 202、`Location`、`Range` |
| upload PATCH | 追加 chunk，校验 offset，返回 202、`Range` |
| upload PUT | 校验 digest，完成写入，返回 201、blob location、digest header |
| upload DELETE | 取消 session，返回 204 |
| tags/list | 字典序分页，返回 `{"name": "...", "tags": [...]}` |
| manifest DELETE tag | 删除 tag 指针 |
| manifest DELETE digest | 删除 manifest 和相关 tag，物理 blob 交给 GC |
| referrers | 返回 OCI image index，空结果也返回 200 和空 `manifests` |

## Proxy 服务设计

Proxy 服务需要兼容上游 registry 的 Bearer challenge，不能把上游 401 直接透传给客户端。

拉取 manifest：

1. 本地 tag 缓存未过期时直接返回。
2. tag 缓存过期时向上游 HEAD/GET revalidate。
3. digest manifest 命中后可视为不可变，除非清理或手动 invalidate。
4. 保存 manifest 原始 body、media type、digest、reference 关系。

拉取 blob：

1. 本地 digest blob 命中直接返回。
2. 未命中时向上游 GET，边下载边计算 digest，校验成功后入库。
3. 大 blob 失败要清理 staging object，不能留下可见 asset。

上游认证：

- 解析上游 `WWW-Authenticate` 的 `realm`、`service`、`scope`。
- 仓库配置支持远端 Basic credential 或匿名。
- token 缓存在共享 TTL cache 或 MySQL 中，key 包含 remote URL、service、scope、username hash；丢失可重取。

Docker Hub 特例：

- 对单段 image name，按 Nexus 参考行为补 `library/`。
- 注意 Docker Hub rate limit header 可透传到日志/指标，但不应污染协议响应。

## Group 服务设计

Group 不存储新 blob，只负责成员选择、缓存和合并。

manifest 查找：

- 按成员顺序查找 tag/digest。
- tag 冲突时第一命中成员获胜。
- 记录 `group-member-asset` 或新 Docker group manifest cache，key 为 `groupId:image:reference`，value 为 member repository id、manifest digest、TTL。

blob 查找：

- 如果请求来自刚返回的 manifest，可优先使用 manifest 所属 member。
- 否则按成员顺序查找 digest blob。
- 命中 member 后写入可重建 TTL cache；成员更新或删除时通过 repository cache token 失效。

tag list：

- 合并成员 tag，去重后按字典序分页。
- 大仓库 tag list 需要避免一次性加载全部 tag，可先第一阶段实现基本分页，第二阶段优化游标查询。

## 管理端和浏览端

Admin UI：

- 仓库 recipe 下拉新增 `docker-hosted`、`docker-proxy`、`docker-group`。
- Docker 仓库配置展示仓库级 connector port、public URL，以及 port-based 和可选 path-based pull/push 示例。
- 增加 Docker connector 配置视图，展示每个 Docker 仓库的 connector 端口、入口 URL、连接数、上传/下载并发和限流状态。
- Proxy 配置新增远端认证、Docker Hub library namespace 提示、remote URL path 校验。
- Group 配置复用成员顺序管理。
- Repository cache 页面支持清理 Docker manifest/tag/blob cache。

Browse UI：

- 以 image name 为一级对象展示。
- image detail 展示 tags、manifest digest、media type、size、platform、updated at。
- manifest detail 展示 config、layers、child manifests、subject、referrers。
- blob detail 展示 digest、size、content type、last downloaded。

## 迁移工具

`migration-nexus` 需要支持源 Nexus Docker hosted 仓库。

迁移顺序：

1. metadata migration 创建 Docker 仓库、权限、blob store 映射。
2. repository data migration 扫描源 Nexus Docker asset/component。
3. 先迁移 blob，再迁移 manifest/tag 指针。
4. 对每个 manifest 重新计算 digest 并和源 metadata 对比。
5. 支持增量：按源 asset `blob_updated`、`last_updated` 或 Docker tag/manifest 更新时间游标扫描。

迁移校验：

- 抽样 `docker pull`。
- 对比 tag list。
- 对比 manifest digest、blob digest、manifest media type。
- 对比常用镜像的 multi-arch manifest list。

## 兼容测试计划

新增 `compat-test` Docker 测试，和现有 Maven/npm/PyPI 等黑盒测试保持同一风格。

### Nexus 参考测试

覆盖：

- hosted 创建、login、push、pull、delete。
- proxy 到 Docker Hub 或测试 registry。
- proxy 到另一个 Nexus Docker 仓库。
- group 成员顺序、tag 冲突、blob 查找。
- anonymous pull 和 private repo challenge。
- write policy：`ALLOW`、`ALLOW_ONCE`、`DENY`。
- 仓库级 connector port routing：`<host>:<repo-port>/<image>:<tag>`。
- 可选共享入口 path-based routing：`<host>:<shared-port>/<repo>/<image>:<tag>`。

对比项：

- HTTP status。
- `WWW-Authenticate` challenge。
- `Docker-Distribution-API-Version`。
- `Docker-Content-Digest`。
- `Location`。
- upload `Range`。
- manifest `Content-Type`。
- registry error JSON code/message。

### 真实客户端测试

全仓库真实客户端矩阵也会覆盖 Docker image push/pull，以及可选的 ORAS artifact push/pull：

```bash
scripts/ci/run-live-compat.sh client-e2e
```

建议脚本：

```bash
docker login localhost:18090
docker pull localhost:18090/docker-proxy/library/alpine:latest
docker tag alpine:latest localhost:18090/docker-hosted/team/alpine:test
docker push localhost:18090/docker-hosted/team/alpine:test
docker pull localhost:18090/docker-group/team/alpine:test
oras push localhost:18090/docker-hosted/team/artifact:sbom ./sbom.spdx.json:application/spdx+json
```

### OCI conformance

分阶段打开：

1. `OCI_TEST_PULL=1`
2. `OCI_TEST_PUSH=1`
3. `OCI_TEST_CONTENT_DISCOVERY=1`
4. `OCI_TEST_CONTENT_MANAGEMENT=1`

Content Management 可最后通过，因为 DELETE blob/manifest 与 GC、cleanup policy、引用计数关系更复杂。

## 开发阶段拆解

### M0：协议与参考行为基线

- 在 Nexus 参考实例创建 docker hosted/proxy/group。
- 抓取 `docker login/pull/push/delete` 请求日志。
- 在 `compat-test` 中先写失败用例，锁定仓库级 connector port routing、可选共享入口 path-based routing、401 challenge、upload response header。
- 输出 Nexus 行为矩阵，作为后续实现验收标准。

### M1：格式骨架和路由

- `RepositoryFormat` 增加 `DOCKER`。
- `RepositoryRecipes` 增加 `docker-hosted`、`docker-proxy`、`docker-group`。
- 根 `pom.xml` 增加 `protocol-docker`。
- 新增 Docker path parser、digest parser、media type 常量、error payload。
- 新增 `/v2/` 和 `/v2/<repo>/...` controller skeleton。
- 新增 Docker connector 管理器或等效独立入口配置，按 Docker 仓库属性建立 `port -> repository_id` 映射，默认只挂载 Docker registry 路由。
- `RepositorySecurityFilter` 或新 filter 识别 `/v2/...`，但 challenge 交给 Docker auth 支持。
- Admin/Browse 仓库列表能显示 Docker format。

验收：

- 仓库级 Docker connector 端口 `GET /v2/` 行为与 Nexus 对齐。
- 不配置仓库级 Docker connector 端口时，Docker 仓库仍可创建，且不启动额外监听端口。
- Docker 大对象请求不经过主服务端口，主端口仍能正常访问 `/admin/`、`/browse/`、`/repository/...`。
- 创建多个 Docker 仓库时可以配置不同 `docker.connector.port`，重复端口会被拒绝。
- 创建 Docker 三类仓库成功。
- 不支持的 Docker endpoint 返回 registry error JSON，而不是 Maven/npm 错误。

### M2：Hosted pull/push 核心闭环

- 新增 Docker Flyway 表和 DAO。
- 实现 `DockerUploadService`，所有 session/chunk 状态入 MySQL/OSS staging。
- 实现 blob HEAD/GET/POST/PATCH/PUT/DELETE。
- 实现 manifest PUT/GET/HEAD。
- 实现 tag list、tag delete。
- 实现 digest、size、media type、descriptor 校验。
- 写入 asset/blob/browse 元数据，接入 `AssetMetadataCache` 失效。

验收：

- `docker push` 一个普通 amd64 镜像成功。
- `docker pull` 同一 tag 成功。
- manifest digest 与客户端本地 digest 一致。
- 重启服务或切换副本后，未完成 upload 能查询、取消或过期清理；已完成内容可拉取。

### M3：Docker Bearer token 与权限

- 新增 token endpoint。
- 实现 challenge scope 生成。
- 实现 token grant 与 repository 权限交集。
- Docker Bearer token 校验接入请求主体。
- 匿名 pull、登录 pull、push、delete 权限分别测试。
- API key/basic/session 与 Docker login 的交互测试。

验收：

- `docker login` 成功。
- 无权限 push 返回 401/403 与 Nexus 参考行为一致。
- 只有 pull 权限的用户不能 push。
- 多副本下 token 校验不依赖单 JVM 内存。

### M4：Proxy 仓库

- 实现上游 request builder 和 remote token auth。
- 实现 manifest/tag TTL 缓存和 revalidation。
- 实现 blob 按 digest 缓存。
- 实现 Docker Hub `library` 补偿。
- 实现 proxy negative cache，TTL 短且可通过 cache token 失效。

验收：

- 通过 proxy 拉取 Docker Hub 或测试 registry 镜像。
- 首次拉取走上游，第二次拉取命中本地 blob/manifest。
- 上游 tag 更新后，TTL 到期能刷新。
- 上游 401 token flow 不泄漏给本地客户端。

### M5：Group 仓库

- 实现 group manifest/blob/tag list。
- 实现 group member 命中缓存。
- 仓库成员变更后失效 Docker group cache。
- 覆盖 tag 冲突、成员离线、proxy+hosted 混合场景。

验收：

- group pull hosted 镜像成功。
- group pull proxy 镜像成功。
- 成员顺序调整后 tag 冲突结果变化符合 Nexus 参考行为。

### M6：OCI referrers 和高级兼容

- manifest PUT 处理 `subject` 字段并写入索引。
- 实现 `/referrers/<digest>` 和 `artifactType` 过滤。
- PUT manifest response 带 `OCI-Subject`。
- 支持 cosign/oras artifact 基本场景。
- 评估是否支持 referrers tag schema fallback。

验收：

- `oras attach` 或 cosign 类 artifact 可 push/pull。
- referrers 空结果返回 200 + 空 image index。
- 删除 subject 或 referrer 后索引保持一致。

### M7：Content management、GC 和 cleanup

- DELETE manifest/tag/blob 细化为 Nexus 参考行为。
- blob 删除不立即破坏仍被 manifest 引用的内容。
- GC worker 根据 `docker_manifest_reference`、`asset`、`asset_blob` 判断可删除对象。
- cleanup policy 支持按 tag、last downloaded、last updated、untagged manifest 清理。
- upload session cleanup worker 删除过期 session 和 staging object。

验收：

- OCI conformance Content Management 通过或有明确兼容差异说明。
- 大量 upload 中断后不会积累不可清理临时对象。

### M8：迁移、UI 和运维观测

- `migration-nexus` 支持 Docker 仓库 metadata 和 data migration。
- Admin UI/Browse UI 支持 Docker 详情页。
- 指标新增 Docker 维度：upload session、proxy upstream latency、manifest cache hit、blob cache hit、digest verification failure、referrer count。
- 文档更新：README 支持格式、中文开发指南、迁移指南、兼容测试指南。

验收：

- 从 Nexus Docker hosted 仓库迁移一批真实镜像后，客户端无需改 image reference 即可 pull。
- `/actuator/prometheus` 能观察 Docker 请求、上传、proxy、缓存指标。

## 多副本一致性要求

Docker 实现必须满足：

- upload session、chunk offset、最终 manifest/tag 指针都以 MySQL 为真相。
- 未完成 upload 不依赖本地临时文件；副本重启后可恢复、取消或过期清理。
- tag 更新、manifest 删除、group 成员变更后，通过 MySQL 事务和 cache token 让其他副本失效。
- manifest/tag 写入必须有唯一约束，处理并发 push 同一 tag 时只能出现一个最终指针。
- blob dedupe 通过唯一约束和 `FOR UPDATE` 协调，不能靠 JVM lock。
- proxy token、negative cache、group member cache 都是可重建 TTL cache，丢失不影响正确性。

## 风险点

| 风险 | 影响 | 缓解 |
| --- | --- | --- |
| Docker 大对象流量和主服务共用端口、线程池、对象存储连接 | 大镜像 push/pull 可能拖慢 UI、管理 REST 和普通制品协议 | 第一阶段提供可选的仓库级 Docker connector 端口，并配置独立 connector/executor、连接数、timeout、上传下载并发和 OSS/S3 bulkhead |
| Docker connector port 使用全局单值 | 多个 Docker 仓库无法使用不同端口，port-based image reference 兼容性不足 | 把可选的 `docker.connector.port` 放入 Docker 仓库创建/更新属性，并用 MySQL 事务校验已启用 connector 的端口唯一性 |
| Docker path-based routing 与 `/repository/<repo>` 目标不一致 | 客户端无法直接使用 Docker CLI | 仓库级 connector port 提供 `/v2/<image>/...` 主入口；共享入口或反向代理场景再提供 `/v2/<repo>/...` path-based 兼容入口 |
| chunked upload 依赖本地临时文件 | 多副本切换后 push 失败或数据丢失 | upload session 和 chunk staging 持久化到 MySQL/OSS |
| manifest JSON 被重写 | digest 不匹配，客户端拒绝 | 保存原始 bytes，解析只用于索引 |
| proxy 上游 Bearer token 处理不完整 | Docker Hub/远端 registry 拉取失败 | 单独实现 upstream auth flow 和 token cache |
| group blob 命中错误成员 | manifest 返回后 layer 404 | manifest 命中 member 与 blob 查找联动缓存 |
| tag 并发覆盖 | tag 指针错乱 | `docker_tag` 唯一约束 + 事务更新 |
| DELETE 与 GC 过早删除 blob | 已有镜像拉取失败 | 引用表驱动 GC，DELETE 先逻辑删除 |

## 已交付范围

当前支持的 Docker / OCI 范围包括：

- 支持 `docker-hosted`。
- 支持可选的仓库级 Docker connector 端口和 port-based `/v2/<image>`；不配置 connector 时仍能创建仓库，并通过可选共享入口支持 path-based `/v2/<repo>/<image>`。
- 支持 Bearer token login。
- 支持 Docker schema2 和 OCI image manifest/index。
- 支持普通 `docker push`、`docker pull`、`docker tag`、`docker manifest inspect`。
- 支持 tag list 和 manifest/blob HEAD。
- 支持 Docker proxy/group 读取路径、cross-repository blob mount、referrers、content management cleanup、迁移验证、OCI conformance workflow 和 Docker 专项指标。

剩余非核心增强主要是 connector TLS/SNI/access-log 加固、可选跨 blob store copy 优化，以及真实 Nexus 兼容需求出现时再评估的 search-only shim。
