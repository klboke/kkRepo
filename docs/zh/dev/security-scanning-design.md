# kkRepo 制品安全扫描开发设计说明

本文定义 kkRepo 制品安全扫描能力的开发设计。目标是在现有关系数据库、
OSS/S3-first Blob 存储、Nexus 兼容协议和多副本运行模型上，增加可恢复、
可审计、可替换扫描引擎的 SBOM 与已知漏洞扫描能力。

安全扫描是 kkRepo 的产品增强，不改变各制品协议本身。默认关闭下载阻断时，
Maven、npm、PyPI、Docker/OCI 等客户端可见行为必须保持不变；只有管理员明确为
仓库启用强制策略后，安全判定才参与下载路径。

## 当前支持状态

截至 2026-07-24，kkRepo 尚未实现原生 SBOM、漏洞扫描任务、漏洞结果存储或下载
阻断能力，但已经具备以下落地基础：

- `asset` 与 `asset_blob` 保存仓库资产、Blob 引用、SHA-256、大小和内容类型。
- hosted 上传、proxy 缓存和 Nexus 仓库数据迁移最终都会形成真实 asset/blob。
- `BlobStorage` 支持流式读取，不要求把完整制品加载进 JVM heap。
- MySQL 8 与 PostgreSQL 12+ 已有基于 `FOR UPDATE SKIP LOCKED` 的后台任务领取、
  超时重领和失败重试模式。
- Docker/OCI 已保存 manifest、index、digest、platform 和 layer 引用，可按不可变
  manifest digest 建立扫描主体。
- 管理端已有安全、审计、后台任务和 Prometheus 指标基础。

本文件是后续实现的设计基线，不表示文中功能已经交付。

## 设计决策摘要

- 使用独立 scanner service 执行不可信输入分析，kkRepo JVM 不内嵌扫描器，也不执行
  制品代码。
- 使用 MySQL/PostgreSQL candidate marker、task lease 和 fencing token 保证多副本
  任务不丢失、可接管。
- 以 CycloneDX SBOM 为长期 inventory，Catalog 与漏洞 Match 分离。
- 普通制品按 asset/blob 内容扫描；Docker/OCI 按 manifest digest 和平台扫描；group
  复用实际 member 结果。
- hosted、proxy 和迁移写入通过 `JdbcAssetDao` 的内容变更 marker 进入同一条可靠链路。
- 结果去重同时包含内容、引擎版本、漏洞数据库 revision 和配置 digest，不使用
  “checksum + 时间窗口”作为唯一新鲜度判断。
- 第一阶段只做 vulnerability audit；secret、license、misconfiguration 和强制阻断
  均按独立阶段交付。
- 下载阻断由解析出具体 asset/manifest 后的 `ArtifactDownloadPolicy` 执行，不放进
  只能识别仓库请求的通用鉴权 Filter。

## 设计目标

1. 对 hosted、proxy 和迁移进入 kkRepo 的可扫描制品生成软件包清单，并匹配已知漏洞。
2. 使用数据库持久化候选标记、任务、租约、重试和结果，支持多个 kkRepo 副本共同工作。
3. 把软件清单生成和漏洞匹配分开；漏洞数据库更新时复用已有 SBOM，避免重复下载和解包。
4. 使用内容身份、扫描器版本、漏洞数据库版本和扫描配置共同确定结果是否可复用。
5. 支持普通制品归档与 Docker/OCI 镜像，但不把所有协议强行映射成同一种输入。
6. 保存完整、可追溯的原始扫描文档，同时只在关系数据库中保存有上限的查询投影。
7. 第一阶段使用 audit-only 模式；策略阻断必须在覆盖全部读取入口并完成真实客户端测试后启用。
8. 扫描器必须可替换。kkRepo 业务代码不能依赖某个扫描器的私有 JSON 模型。
9. 扫描故障、结果不完整和不适用必须是显式状态，不能被解释成“未发现漏洞”。
10. 扫描过程不得执行上传制品中的脚本、安装钩子、二进制或构建逻辑。

## 非目标

第一阶段明确不实现：

- 不提供源码仓库 SAST、DAST、IaC 合规或运行时行为分析。
- 不执行 `npm install`、Maven plugin、Python setup hook、RPM scriptlet 等制品代码。
- 不把恶意软件沙箱、病毒特征扫描和已知漏洞扫描混成一个结果模型。
- 不承诺所有 Raw 文件都可以自动识别；无法可靠识别的内容返回 `NOT_APPLICABLE`。
- 不在上传 HTTP 请求内同步等待扫描完成。
- 不在 MySQL/PostgreSQL 中保存无上限的 SBOM、扫描器原始报告或解压文件正文。
- 不把 scanner 本地目录、scanner 本地队列或某个 JVM 内存状态作为正确性真相。
- 不默认把内部生成的 SBOM 发布为用户可见仓库资产或 OCI referrer。
- 不在第一阶段提供第三方漏洞治理产品的 API 兼容层。
- 不把“没有匹配到漏洞”等同于制品绝对安全。

恶意软件扫描、签名验证、VEX、许可证策略和 OCI SBOM referrer 可以复用本文任务与
策略框架，但应分别增加扫描类型和验收标准。

## 核心设计原则

### 内容身份优先

普通制品的扫描主体是不可变 Blob 内容，不是可变化的仓库路径。内容身份至少包含：

```text
blob store + SHA-256 + size + target classification
```

Docker/OCI 的扫描主体是 manifest digest 与平台集合。tag 只是不稳定指针，不能作为
扫描结果去重键。

同一 Blob 被多个仓库路径或 group 成员引用时，可以复用不可变 SBOM 和扫描结果，
但仓库策略判定、豁免和最新状态仍分别绑定到具体 asset 与访问上下文。

### SBOM 与漏洞匹配分层

扫描分为两个可独立重试和复用的阶段：

1. **Catalog**：从制品或镜像生成完整软件包清单和 CycloneDX SBOM。
2. **Match**：使用某一漏洞数据库快照，把 SBOM 中的软件包与已知漏洞匹配。

Catalog fingerprint 由内容身份、catalog engine 版本和 catalog 配置决定。
Match fingerprint 由 SBOM SHA-256、matcher 版本、漏洞数据库 revision 和匹配配置决定。

漏洞数据库更新只会让 Match 结果过期，不会自动让 SBOM 过期。

### 数据库协调、外部执行

kkRepo 负责：

- 发现扫描候选。
- 持久化任务和租约。
- 读取 Blob。
- 调用 scanner。
- 规范化、保存和展示结果。
- 计算策略决定。

scanner 负责：

- 在隔离的临时工作区识别和解包制品。
- 生成 SBOM。
- 匹配漏洞。
- 返回带引擎与漏洞库 provenance 的版本化结果。

scanner 不负责保存 kkRepo 的长期任务状态，也不能要求请求始终回到同一个 scanner
实例。

### 审计优先、阻断后置

第一阶段只记录结果，不改变下载响应。强制策略上线前必须证明：

- 所有可下载二进制路径都能解析到准确的 asset 或 OCI manifest。
- group 请求使用实际命中成员的扫描结果。
- proxy 首次回源不会在严格模式下先把未扫描字节流给客户端。
- pending、scanner failure 和 partial result 都有管理员明确选择的行为。
- 真实客户端可以正确处理策略返回的状态码和响应体。

## 总体架构

```text
hosted / proxy / migration 写入
             |
             | 同一数据库事务写入或推进 candidate generation
             v
 security_scan_candidate
             |
             | claim + classify + dedupe
             v
     security_scan_task  <---- 手动扫描 / 定时重扫 / 漏洞库更新
             |
             | DB lease + fencing token
             v
     SecurityScanWorker
             |
             +---- BlobStorage.get() 流式输入
             |        或短生命周期只读 URL
             |
             +---- Docker/OCI digest + scoped read token
             v
  scanner adapter / scanner pool
       | Catalog          | Match
       v                  v
 CycloneDX SBOM       vulnerability report
       |                  |
       +--------+---------+
                v
 原始文档进入 Blob storage
 有上限投影和聚合状态进入 MySQL/PostgreSQL
                |
                +---- Admin API / UI / Audit / Metrics
                |
                v
       ArtifactDownloadPolicy
                |
        allow / pending / deny
```

## 模块边界

建议新增 `security-scan` Maven 模块，保持引擎无关的领域模型和 SPI：

| 模块 | 新增职责 |
| --- | --- |
| `security-scan` | 扫描主体、状态机、fingerprint、scanner SPI、规范化结果、策略输入输出 |
| `core` | 仅在确有跨模块需要时增加通用 Blob 只读授权抽象，不放扫描器实现 |
| `persistence-jdbc.api` | Scan DAO、record、分页与统计接口 |
| `persistence-jdbc.internal` | 公共 JDBC 实现、claim、lease、finalize transaction |
| `persistence-mysql` | MySQL migration、dialect 和 contract test |
| `persistence-postgresql` | PostgreSQL migration、dialect 和 contract test |
| `server` | 候选分类、worker、scanner client、管理 API、策略接入和调度 |
| `scanner-adapter` | 独立部署的内部 HTTP adapter、固定版本 Syft/Grype、输入限制和进程隔离 |
| `protocol-*` | 必要时提供格式专用 candidate classifier 或下载主体解析，不放扫描器调用 |
| `admin-ui` | 扫描概览、任务、结果、仓库配置、策略和豁免 |
| `browse-ui` | 后续按权限展示制品安全摘要；第一阶段可以不展示 |
| `compat-test` | audit-only 无回归与 enforce 模式真实客户端测试 |

Controller 只能做鉴权、参数校验和 DTO 转换。任务状态机、结果复用、策略计算和
scanner 调用必须位于 service/domain 层。

`scanner-adapter` 是独立发布镜像，不进入 `server` 运行时 classpath。它可以使用单独
构建目录和语言工具链，但公开契约只能是版本化 HTTP/schema，kkRepo 不能 import
adapter 的进程管理或第三方扫描器类型。

## 扫描主体与候选分类

### 普通 asset

普通制品主体包含：

```text
subjectKind       = ASSET_BLOB
repositoryId
assetId
assetBlobId
sha256
size
format
assetKind
contentType
classification   = ARCHIVE | PACKAGE | MANIFEST | RAW_FILE
```

asset 路径被新 Blob 覆盖时，即使 asset ID 不变，也必须推进 candidate generation。
worker 必须重新读取当前 asset/blob binding；旧 generation 的结果不得覆盖新内容状态。

### Docker/OCI

Docker/OCI 不对每个 layer Blob 单独创建用户可见扫描状态。扫描主体为：

```text
subjectKind       = OCI_MANIFEST
repositoryId
manifestAssetId
manifestDigest
mediaType
platformPolicy
resolvedPlatforms
referencedDigests
```

对于 image index：

- `ALL`：扫描全部可识别平台；超过平台上限时结果为 `PARTIAL`。
- `REQUIRED_SET`：扫描仓库配置指定的平台集合；未覆盖平台在 UI 中明确展示。

第一阶段默认使用 `REQUIRED_SET`，默认平台可配置为 `linux/amd64`。只有
`ALL` 完成，或策略明确声明只要求某个平台集合时，才能把聚合结果标记为对应范围内的
`COMPLETE`。

tag 更新到新 digest 时，tag 本身不复制扫描结果；读取路径解析到新 manifest 后使用
新 digest 对应的状态。

### Group

group 不生成新的扫描任务和 SBOM：

- 先按现有 group 解析规则得到实际 member asset 或 manifest。
- 使用 member 的不可变扫描结果。
- 使用“请求 group 策略 + member 仓库策略”计算最终决定，默认取更严格结果。
- 成员顺序或 source binding 变化后，不复用旧成员的 asset security state。

### 格式适用矩阵

candidate classifier 必须由 format、asset kind、规范路径和 media type 共同判断，
不能只按文件后缀猜测。

| 格式 | 第一阶段扫描主体 | 默认跳过 |
| --- | --- | --- |
| Maven | `.jar`、`.war`、`.ear`、发布归档 | `maven-metadata.xml`、checksum、signature |
| npm | package tarball | packument、dist-tag metadata |
| PyPI | wheel、sdist archive | simple index、项目 metadata 页面 |
| Go | module `.zip`，可选结合 `.mod` 增强清单 | `.info`、版本列表 |
| Helm | chart `.tgz` | `index.yaml`、provenance 文件单独扫描 |
| Cargo/Rust | `.crate` | sparse index、crate metadata API |
| Dart/Pub | package archive | version metadata |
| Composer/PHP | dist archive；无 dist 时可按 profile 扫 source archive | `packages.json` 和 provider metadata |
| Terraform | Module/Provider archive | versions、download metadata、SHA256SUMS、signature |
| Swift | source archive | release metadata、manifest endpoint 响应 |
| Ansible Galaxy | collection tarball | collection/version metadata、import task、signature |
| Docker/OCI | image manifest/index 及所需 layers | 独立 layer asset、普通 tag/index JSON asset |
| NuGet | `.nupkg`，可配置包含 `.snupkg` | service index、registration、search metadata |
| RubyGems | `.gem` | specs/index metadata |
| Yum | `.rpm` | repodata、checksum 和签名文件 |
| Raw | allowlist 命中的 archive/package，或手动指定 | 未识别、超限或明确排除的文件 |

classifier 返回以下之一：

- `SCANNABLE`：给出规范 target classification 和 profile。
- `NOT_APPLICABLE`：协议 metadata、checksum、signature 或不支持的内容。
- `DEFERRED`：还缺少必要关联，例如 OCI index 尚未解析完整。
- `REJECTED_BY_LIMIT`：大小或类型不满足当前 profile，记录为显式 partial/failure。

## 候选标记与触发模型

### 持久化 candidate marker

新增 `security_scan_candidate`，以 `asset_id` 为主键，每个 asset 最多保留一行最新
待处理变化：

| 字段 | 语义 |
| --- | --- |
| `asset_id` | 当前 asset |
| `asset_blob_id` | marker 创建时的 Blob；允许为空 |
| `content_generation` | 每次新增或替换内容时单调递增 |
| `enqueued_generation` | 已经转换成 task 的最新 generation |
| `changed_at` | 数据库时间 |
| `updated_at` | marker 更新时间 |

`JdbcAssetDao` 在以下内容绑定操作成功的同一事务内插入或推进 marker：

- 新建带 Blob 的 asset。
- `updateAssetBlobBinding`。
- `updateAssetBlobBindingAndMetadata`。
- Docker manifest/index 写入后完成最终 asset binding。

仅更新下载时间、proxy validator、普通 attributes 或 component 关系不能推进
content generation。

marker 不是 scanner 队列。Candidate worker 领取 marker 后：

1. 重新加载 asset、Blob、repository 和扫描配置。
2. 确认 marker generation 仍对应当前 Blob。
3. 分类为可扫描、不适用或暂缓。
4. 幂等创建 `security_scan_task`。
5. 推进 `enqueued_generation`。

这种设计使 hosted、proxy、迁移写入共享同一可靠入口，不要求在每个协议 writer 中
各自维护易遗漏的异步事件。

### 仓库启用与历史回填

仓库从 disabled/manual-only 切换到自动扫描时，需要持久化 backfill job：

- 按 `(repository_id, asset_id)` 游标分页。
- 对当前有 Blob 的 asset 插入或推进 candidate marker。
- 支持暂停、恢复、进度和失败重试。
- 不一次性把全库 asset 装入内存。
- 多副本通过数据库 claim 分片执行。

删除仓库或 asset 时，candidate 与 asset state 随外键清理；不可变 SBOM/scan run
按保留策略处理，不能破坏仍由其它 asset 复用的结果。

### 触发来源

`security_scan_task.request_reason` 使用有界枚举：

- `CONTENT_CHANGED`
- `REPOSITORY_BACKFILL`
- `MANUAL`
- `PROFILE_CHANGED`
- `SCANNER_CHANGED`
- `VULNERABILITY_DB_CHANGED`
- `MAX_AGE_EXPIRED`
- `RETRY`

手动“重新评估”优先复用 SBOM，只重跑 Match。只有管理员选择“重新生成 SBOM”，
或 catalog fingerprint 已变化时，才重新读取和解包制品。

## 数据模型

下列表名是逻辑设计；MySQL 与 PostgreSQL migration 使用各自类型和索引实现相同语义。

### `security_scan_profile`

定义扫描输入、引擎和资源限制：

- `id`
- `name`
- `enabled`
- `catalog_engine`
- `matcher_engine`
- `scanner_types_json`
- `target_rules_json`
- `max_input_bytes`
- `max_archive_entries`
- `max_uncompressed_bytes`
- `max_single_file_bytes`
- `max_nested_depth`
- `timeout_seconds`
- `oci_platform_policy`
- `required_platforms_json`
- `configuration_digest`
- `revision`
- `created_at`
- `updated_at`

影响扫描语义的配置变化必须生成新的 `configuration_digest` 和 revision。

第一阶段 profile 只启用 `vuln`。Secret、misconfiguration 和 license scanner 必须通过
独立 profile 显式开启，不能继承 scanner CLI 可能变化的默认值。

### `repository_security_scan_config`

每个仓库一行：

- `repository_id`
- `enabled`
- `profile_id`
- `scan_hosted_content`
- `scan_proxy_content`
- `enforcement_mode`
- `pending_action`
- `failure_action`
- `partial_action`
- `max_result_age_seconds`
- `policy_id`
- `config_revision`
- `created_at`
- `updated_at`

`enforcement_mode`：

- `AUDIT`：记录结果但不影响下载。
- `ENFORCE`：使用 materialized policy decision。

`pending_action`、`failure_action`、`partial_action` 分别配置 `ALLOW` 或 `BLOCK`。
默认全部是 `ALLOW`，且 `enforcement_mode=AUDIT`。

### `security_scan_task`

持久化待执行工作：

- `id`
- `repository_id`
- `asset_id`
- `subject_kind`
- `subject_key`
- `subject_key_hash`
- `content_generation`
- `profile_id`
- `profile_revision`
- `stage`
- `request_reason`
- `priority`
- `status`
- `attempts`
- `max_attempts`
- `next_attempt_at`
- `claimed_by`
- `lease_token`
- `lease_until`
- `last_heartbeat_at`
- `last_error_code`
- `last_error_summary`
- `requested_by`
- `requested_at`
- `started_at`
- `finished_at`
- `created_at`
- `updated_at`

`stage` 是 `CATALOG_AND_MATCH` 或 `MATCH_ONLY`。

对 candidate 自动任务建立以下语义的唯一键：

```text
asset + content generation + profile revision + stage + requested scanner snapshot
```

手动强制扫描增加 request UUID，因此可以有新的审计记录；普通重复点击应通过
Idempotency-Key 合并。

### `security_scanner_snapshot`

记录 scanner 可复现输入：

- `id`
- `adapter_name`
- `adapter_api_version`
- `engine_name`
- `engine_version`
- `vulnerability_database_revision`
- `vulnerability_database_updated_at`
- `capability_digest`
- `observed_at`
- `ready`
- `details_json`

`details_json` 必须有大小上限且不能保存 credential。后台 watcher 把 scanner metadata
写入共享数据库，所有副本使用同一观察结果安排重扫。

### `security_sbom`

不可变 SBOM：

- `id`
- `subject_kind`
- `subject_identity`
- `subject_identity_hash`
- `catalog_engine`
- `catalog_engine_version`
- `catalog_configuration_digest`
- `catalog_fingerprint`
- `document_blob_id`
- `document_sha256`
- `spec_name`
- `spec_version`
- `component_count`
- `dependency_count`
- `inventory_complete`
- `created_at`

`document_blob_id` 引用 `asset_blob`，但不创建用户可见 `asset`。Blob GC 的引用计算必须
把 SBOM 和 raw report 引用计入，不能把这些文档误判为孤儿 Blob。

唯一键：

```text
catalog_fingerprint =
  hash(subject identity,
       target classification,
       catalog engine,
       catalog engine version,
       catalog configuration digest)
```

### `security_sbom_component`

SBOM 的有上限查询投影：

- `sbom_id`
- `component_ref`
- `package_url`
- `package_url_hash`
- `type`
- `namespace`
- `name`
- `version`
- `directness`
- `locations_json`
- `licenses_json`
- `properties_json`

优先使用 Package URL 标识软件包。不能生成可靠 PURL 时保留 engine-native identity，
并在 UI 中标记匹配精度。

完整 dependency graph 保留在原始 CycloneDX 文档中；关系数据库只投影 UI、搜索和策略
真正需要的有界字段。

### `security_scan_run`

不可变漏洞匹配结果：

- `id`
- `task_id`
- `sbom_id`
- `scanner_snapshot_id`
- `match_configuration_digest`
- `match_fingerprint`
- `status`
- `scan_completeness`
- `raw_report_blob_id`
- `raw_report_sha256`
- `finding_count`
- `fixable_finding_count`
- `critical_count`
- `high_count`
- `medium_count`
- `low_count`
- `unknown_count`
- `max_severity`
- `started_at`
- `completed_at`
- `created_at`

唯一键：

```text
match_fingerprint =
  hash(SBOM SHA-256,
       matcher engine,
       matcher engine version,
       vulnerability database revision,
       match configuration digest)
```

相同 fingerprint 直接引用已有 immutable run，不复制 findings 或 component projection。

### `security_scan_finding`

规范化 finding：

- `id`
- `scan_run_id`
- `finding_key`
- `finding_key_hash`
- `advisory_id`
- `aliases_json`
- `data_source`
- `package_url`
- `package_name`
- `installed_version`
- `fixed_versions_json`
- `severity`
- `severity_source`
- `cvss_vector`
- `cvss_score`
- `title`
- `description`
- `primary_url`
- `locations_json`
- `source_status`
- `created_at`

`finding_key` 至少包含 advisory identity、package identity、installed version 和
location/layer identity。不同数据库对同一漏洞的 alias 不在写入时武断合并；保留来源，
展示层可以按 canonical ID/alias 聚合。

title、description 和 URL 都来自外部数据，进入 UI 前必须转义并限制 scheme。

### `asset_security_state`

下载热路径只读取 materialized state，不在请求内聚合 findings：

- `asset_id`
- `profile_id`
- `content_generation`
- `subject_identity_hash`
- `latest_scan_run_id`
- `scan_state`
- `scan_completeness`
- `inventory_complete`
- `max_severity`
- `finding_counts_json`
- `policy_id`
- `policy_revision`
- `policy_decision`
- `policy_reason_code`
- `stale_at`
- `last_evaluated_at`
- `version`

asset 当前 Blob、generation 或 profile 与 state 不一致时，读取逻辑必须返回 `STALE`，
不能继续使用旧的 `ALLOW`。

该表的 `version` 用于节点本地短 TTL cache 失效。cache 丢失只增加数据库读取，不改变
策略正确性。

### `security_scan_policy` 与 `security_scan_waiver`

策略使用版本化、可审计模型。第一阶段规则：

- 阻断的最低 severity。
- 是否只阻断存在 fix 的 finding。
- 允许或阻断 `UNKNOWN` severity。
- 最大结果年龄。
- 是否要求完整 inventory。
- OCI 必须覆盖的平台集合。

waiver 字段至少包含：

- scope：finding、component、asset 或 repository。
- advisory/package/finding selector。
- reason。
- created by / approved by。
- expires at。
- policy revision。
- audit timestamps。

waiver 不能修改原始 finding。策略重新计算时把有效 waiver 作为独立输入，并把命中
waiver 的 finding 数量写入 policy evaluation。

## 状态机

### Task 状态

```text
PENDING
  -> RUNNING
      -> SUCCEEDED
      -> RETRY_WAIT -> RUNNING
      -> FAILED
      -> CANCELLED
```

只有持有当前 `lease_token` 的 worker 可以 heartbeat、完成或重试任务。

### Scan 状态

| 状态 | 语义 |
| --- | --- |
| `PENDING` | 已排队但未完成 |
| `RUNNING` | scanner 正在处理 |
| `COMPLETE` | 当前 profile 范围内完整完成 |
| `PARTIAL` | 有可用结果，但 inventory、平台或 finding 投影不完整 |
| `FAILED` | scanner、输入、存储或持久化失败 |
| `NOT_APPLICABLE` | 该 asset 明确不属于当前扫描范围 |
| `CANCELLED` | 管理员取消或任务被新 generation 取代 |
| `STALE` | 有旧结果，但内容、profile、scanner 或漏洞数据库 freshness 不满足当前要求 |

`COMPLETE` 且 finding 为 0 只表示“在记录的 scanner 和数据库快照下未发现匹配”，UI
使用“未发现已知漏洞”，不使用“安全”或“无风险”。

### Policy 决定

```text
ALLOW
BLOCK_PENDING
BLOCK_SCAN_FAILED
BLOCK_PARTIAL
BLOCK_VULNERABILITY
```

状态与策略决定分离。同一个 scan run 可以在不同仓库策略下得到不同决定。

## 数据库任务领取与多副本语义

worker 使用 MySQL 8/PostgreSQL 共同支持的模式：

```sql
SELECT ...
FROM security_scan_task
WHERE status IN ('PENDING', 'RETRY_WAIT', 'RUNNING')
  AND next_attempt_at <= CURRENT_TIMESTAMP
  AND (
    status <> 'RUNNING'
    OR lease_until < CURRENT_TIMESTAMP
  )
ORDER BY priority DESC, requested_at, id
LIMIT ?
FOR UPDATE SKIP LOCKED
```

领取事务同时：

1. 把任务设置为 `RUNNING`。
2. 增加 attempts。
3. 写入随机 `lease_token`、`claimed_by` 和 `lease_until`。
4. 提交后才开始 Blob I/O 和 scanner 调用。

长任务定期 heartbeat 延长 lease。完成更新必须包含：

```sql
WHERE id = ? AND status = 'RUNNING' AND lease_token = ?
```

旧 worker 在 lease 失效后返回，不得覆盖接管 worker 的结果。

退避使用带 jitter 的指数策略。以下错误默认可重试：

- scanner `429`、`502`、`503`、`504`。
- 网络中断和明确的临时对象存储错误。
- scanner 数据库正在更新。

以下错误默认不可重试或需要管理员修复：

- checksum 不匹配。
- 不支持的 scanner API/schema。
- 归档路径穿越、设备文件或资源上限违规。
- scanner 返回无效 JSON、过大报告或 provenance 缺失。

进程内 executor/semaphore 可以保护单个 JVM，但不能决定集群总任务所有权。scanner
deployment 自身必须暴露容量和 backpressure；需要严格全局并发上限时，增加数据库
`security_scanner_slot` lease，而不是依赖每个副本各自计数。

## Scanner Adapter 契约

kkRepo 定义小型、版本化的内部接口，不直接把第三方 CLI 输出暴露给业务层。

建议端点：

```text
GET  /v1/capabilities
GET  /v1/readiness
POST /v1/catalog
POST /v1/match
POST /v1/oci/scan
```

共同请求信息：

- API version。
- kkRepo run ID 和 Idempotency-Key。
- target classification。
- 期望 SHA-256、size 和 media type。
- profile configuration digest。
- timeout 与资源上限。

共同响应信息：

- adapter/engine 名称和版本。
- 漏洞数据库 revision 与更新时间。
- capability digest。
- 输入实际 SHA-256。
- completeness。
- 有界 summary。
- CycloneDX 或版本化 vulnerability report。

adapter API 使用同步、可安全重试的调用。scanner 可以在请求期间使用本地临时目录，
但请求结束后不需要保留任务状态。kkRepo 超时后用相同 Idempotency-Key 重试时，结果
必须保持内容幂等。

第一版只交付一个 `syft-grype-v1` 参考 profile：

- Syft 负责 Catalog，并输出包含 package identity、location 和 dependency 的
  CycloneDX JSON。
- Grype 读取已保存的 CycloneDX 完成 Match，adapter 记录实际 Grype 版本与
  vulnerability database revision。
- CycloneDX JSON 是 kkRepo 长期保存、可导出的引擎中性 SBOM；adapter 内部格式不能
  成为数据库 API。
- scanner container 通过 image digest 固定工具版本，升级工具或 catalog 配置会生成
  新 fingerprint。

后续可以增加 `trivy-vuln` 等 adapter/profile。不同引擎的 finding 默认分别形成 scan
run，不在第一阶段自动合并、投票或覆盖。

禁止在 kkRepo 内重新实现 Maven/npm/PyPI 等生态的漏洞匹配器。协议模块负责识别
哪个 asset 是发布制品，包目录识别和漏洞匹配由 scanner 工具链完成。

## Blob 与输入传输

### 普通制品

`ScanInputBroker` 支持两种模式：

1. `STREAM`：kkRepo 通过 `BlobStorage.get()` 打开 InputStream，边读边把 multipart
   body 发送给 scanner。所有 Blob backend 都必须支持，是第一阶段基线。
2. `SIGNED_URL`：Blob backend 支持时生成短生命周期、只读、绑定对象的 URL，让
   scanner 直接读取。它是性能优化，不能改变正确性。

无论哪种模式：

- scanner 必须重新计算 SHA-256 与 size。
- 不把 OSS/S3 access key 交给 scanner。
- signed URL 不写日志、finding、metric label 或审计详情。
- scanner 只能读取该次任务目标对象，不能列出 bucket。
- 传输失败不能创建 COMPLETE 结果。

### Docker/OCI

OCI scanner 按 digest 从 kkRepo 内部 registry 地址拉取：

- kkRepo 签发只读、仓库范围、digest/manifest 范围、短 TTL token。
- token 不继承触发扫描用户的长期 credential。
- scanner 必须按 digest 而不是可变化 tag 拉取。
- index 根据 platform policy 解析并记录实际扫描的平台。
- scanner 不挂载宿主 Docker socket。

后续也可以把 OCI layout 通过对象存储提供给 scanner，但不能要求 kkRepo 主进程在本地
拼装完整镜像。

### 原始结果

SBOM 和 raw vulnerability report 使用专用对象前缀，例如：

```text
security-scan/sbom/<sha256>.cdx.json
security-scan/report/<sha256>.json
```

先写 staging object，再在一个数据库事务中：

1. 插入或复用 immutable SBOM。
2. 插入或复用 scan run。
3. 写入完整、有上限的 component/finding 投影。
4. 计算并更新 asset security state。
5. 使用 lease token 完成 task。
6. 写入安全审计事件。

事务失败后 staging object 由持久 cleanup marker 清理。不能出现 finding 已写入一半，
task 却被标记 COMPLETE 的状态。

## 安全解包与执行隔离

所有输入均视为不可信。scanner 必须：

- 使用非 root 用户和只读 root filesystem。
- 每个请求使用独立临时目录，结束后清理。
- 禁止执行归档中的二进制、脚本和 package lifecycle hook。
- 禁止设备文件、FIFO、socket、hard link 和逃逸工作区的 symlink。
- 规范化每个归档路径并拒绝绝对路径、`..` 和 Unicode/分隔符绕过。
- 限制压缩层级、归档条目数、单文件大小、解压总量和压缩膨胀率。
- 限制 CPU、内存、临时磁盘、进程数和 wall-clock timeout。
- 不挂载宿主源码、Docker socket、kkRepo 配置目录或云凭据目录。
- 默认禁止任意出站网络；漏洞数据库更新使用独立、允许列表控制的流程。
- 对 scanner response 设置 body 大小、JSON nesting 和字段长度上限。

scanner adapter 与 kkRepo 之间使用受保护的内部网络，并支持 mTLS 或轮换的 service
credential。readiness 必须同时验证扫描器可执行、漏洞数据库可读且未超过允许运维
年龄；不能只判断 HTTP 端口可连接。

## 漏洞数据库与结果新鲜度

scanner 定期报告：

- engine version。
- vulnerability database revision。
- database updated at。
- supported target/capability digest。

`ScannerSnapshotWatcher` 把变化写入共享数据库。新 revision 出现后：

1. 找出仍有有效 SBOM、但 match fingerprint 使用旧 revision 的 asset。
2. 按优先级创建 `MATCH_ONLY` task。
3. 复用 SBOM，不重新读取原始制品。
4. 在新结果完成前把旧结果显示为 `STALE`，是否阻断由仓库 freshness 策略决定。

多个 scanner 实例短时间处于不同数据库 revision 时，每个 run 记录真实 revision。
不能把“本周扫描过”作为唯一复用条件。

数据库 revision 获取失败时 scanner readiness 为 degraded。Audit 仓库继续正常提供
制品并暴露告警；Enforce 仓库根据 `failure_action` 和 `max_result_age_seconds` 决定。

## 策略与下载路径

### 接入位置

`RepositorySecurityFilter` 继续只负责身份与仓库权限，不能在这里推断最终 asset。

新增 `ArtifactDownloadPolicy`，在协议 service 已经解析实际 asset/member/manifest，
但尚未调用 `BlobStorage.get()` 前执行：

```text
authorization
  -> repository/group resolution
  -> concrete asset or OCI manifest resolution
  -> ArtifactDownloadPolicy
  -> BlobStorage.get()
```

普通协议 reader、Components/Browse download 和 Docker manifest 路由都必须接入。
不能只覆盖 `/repository/{repo}` 而遗漏 Docker `/v2`、仓库 connector port 或内部
redirect/download endpoint。

### Audit 模式

- 总是允许原协议请求继续。
- 记录本次访问关联的 scan state 与 policy shadow decision。
- 不改变 status、header 或 body。
- 指标展示“如果开启 enforce 将阻断多少次”，用于评估误报和覆盖率。

### Enforce 模式

普通制品：

- confirmed policy violation：默认 `403`。
- pending/stale 且配置阻断：默认 `503`，带有界 `Retry-After`。
- 不在错误正文暴露调用方无权限查看的 CVE、内部路径或 scanner 信息。

不同协议需要专用错误适配。Docker 必须返回 Registry V2 JSON 错误；其它包客户端要
用真实客户端验证重试和错误展示。最终状态码、header 和 body 以兼容测试固定，不能由
一个通用 controller 猜测。

### Hosted 上传

上传校验和仓库 write policy 仍在同步请求内完成。扫描异步进行：

1. 上传成功并持久化 asset。
2. 同事务推进 candidate marker。
3. 严格仓库中的新 asset 处于 pending/quarantined-for-download。
4. scan 与 policy 通过后允许下载。

扫描失败不回滚已经成功提交的 Blob；管理员可以修复 scanner 后重试、添加有期限
waiver 或删除制品。

### Proxy 首次回源

Audit 模式保持当前边下载边缓存/响应语义。

Enforce 且 pending 必须阻断的仓库不能把首次回源字节先流给客户端：

1. 把完整上游响应写入 staging/Blob 并校验 checksum。
2. 提交 proxy asset 和 candidate。
3. 返回可重试的 pending 响应。
4. 后台扫描通过后，客户端重试命中本地 Blob。

这会增加严格 proxy 仓库第一次请求的延迟和一次失败重试，必须在管理端明确提示，
并用 Maven/npm/PyPI/Docker 等真实客户端验证。

### Group 策略

group 不复制扫描结果。最终决定使用：

- 实际命中 member asset 的 scan state。
- member 仓库 policy。
- 请求入口 group 的 policy。

默认取更严格决定。Group metadata 可以继续展示版本，但实际二进制下载仍需在解析
member 后执行策略。若未来支持“隐藏被阻断版本”，必须作为独立行为并补客户端
dependency resolution 兼容测试。

### Docker 限制

第一阶段在 manifest/index 读取处阻断正常 `docker pull`。单个 layer 可能被多个
manifest 共享，直接按 layer finding 阻断会误伤其它镜像。

因此第一阶段保证的是“正常 manifest 驱动的 pull 被策略阻断”，不是把 layer Blob
变成绝对不可读取的机密对象。如果产品要求禁止具备仓库 read 权限的用户直接请求共享
layer digest，需要增加绑定 manifest 的短期下载授权上下文，另行设计。

## 管理 API 与权限

第一阶段使用 kkRepo internal API，不声明 Nexus REST API 兼容：

```text
GET    /internal/security/scanning/summary
GET    /internal/security/scanning/tasks
GET    /internal/security/scanning/runs
GET    /internal/security/scanning/findings
GET    /internal/security/scanning/assets/{assetId}
POST   /internal/security/scanning/assets/{assetId}/rescan
POST   /internal/security/scanning/tasks/{taskId}/retry
POST   /internal/security/scanning/tasks/{taskId}/cancel
GET    /internal/security/scanning/repositories/{repositoryId}/config
PUT    /internal/security/scanning/repositories/{repositoryId}/config
GET    /internal/security/scanning/policies
POST   /internal/security/scanning/policies
POST   /internal/security/scanning/waivers
DELETE /internal/security/scanning/waivers/{id}
GET    /internal/security/scanning/sboms/{sbomId}
```

权限要求：

- 所有查询必须按仓库权限过滤，不能通过 finding、SBOM 或 task ID 枚举其它仓库。
- 查看完整 SBOM/finding 需要仓库 `browse/read` 和安全扫描查看权限。
- 修改仓库扫描配置、重试和手动扫描需要 repository administration 权限。
- 修改全局 profile、policy 和 scanner 配置需要 application/security administration 权限。
- 创建 waiver 需要单独的高权限动作，并写入审计日志。
- SBOM/raw report 下载使用受鉴权 controller 或短期 URL，不直接暴露 object key。

API 列表使用稳定分页和有界 filter。description、raw report 和 SBOM 不进入列表响应。

## Admin UI

新增 **Security > Artifact Scanning**：

1. **Overview**
   - scanner readiness 和数据库更新时间。
   - 扫描覆盖率。
   - pending/failed/partial/stale 数量。
   - severity 分布和将被策略阻断的 asset 数。
2. **Tasks**
   - 状态、repository、format、reason、attempt、lease age、error。
   - retry/cancel，但不显示 credential 或 signed URL。
3. **Findings**
   - repository、component、asset、package/PURL、advisory、severity、fixed version。
   - 原始来源和 scan snapshot。
4. **Repositories**
   - profile、hosted/proxy trigger、audit/enforce、pending/failure/partial action。
5. **Policies and Waivers**
   - 版本、阈值、范围、到期时间、审批人和审计历史。

Browse UI 第一阶段只显示有权限用户可见的状态徽标和最后扫描时间，不直接展示完整
漏洞描述。finding 详情统一进入受权限控制的管理页面。

## 可观测性

新增有界指标：

| 指标 | 类型 | 说明 |
| --- | --- | --- |
| `kkrepo_security_scan_tasks_total` | counter | task outcome，按 stage/reason 分类 |
| `kkrepo_security_scan_task_duration_seconds` | timer | task 总耗时 |
| `kkrepo_security_scan_catalog_duration_seconds` | timer | Catalog 耗时 |
| `kkrepo_security_scan_match_duration_seconds` | timer | Match 耗时 |
| `kkrepo_security_scan_input_bytes_total` | counter | 发送给 scanner 的字节数 |
| `kkrepo_security_scan_backlog` | gauge | 可领取任务数 |
| `kkrepo_security_scan_oldest_age_seconds` | gauge | 最老待处理任务年龄 |
| `kkrepo_security_scan_running` | gauge | 当前有效 lease 数 |
| `kkrepo_security_scan_failures` | gauge | 终态失败任务数 |
| `kkrepo_security_scan_findings` | gauge | 当前 asset state 的 finding 聚合 |
| `kkrepo_security_scan_scanner_ready` | gauge | scanner readiness |
| `kkrepo_security_scan_database_age_seconds` | gauge | 漏洞数据库年龄 |
| `kkrepo_security_policy_decisions_total` | counter | allow/block/shadow decision |

允许的低基数标签：

- `format`
- `repository_type`
- `stage`
- `reason`
- `outcome`
- `severity`
- `scanner`
- `decision`

禁止把 repository 名、asset path、component coordinate、PURL、CVE、task UUID、
object key 和 token 放入 metric label。按具体对象排查使用受权限控制的 API、审计和
结构化日志。

建议告警：

- scanner 连续不可用。
- 漏洞数据库超过允许运维年龄。
- oldest backlog age 持续增长。
- terminal failure 增长。
- lease takeover 频率异常。
- partial/inventory incomplete 比例异常。
- enforce 仓库存在大量 pending block。

## 审计

以下动作写入 `security_audit_log`：

- 启用/关闭仓库扫描。
- profile、policy、enforcement mode 变化。
- 手动 scan/rescan/retry/cancel。
- waiver 创建、更新、过期和删除。
- asset policy state 从 allow 变为 block，或从 block 变为 allow。
- scanner snapshot 变化导致批量 stale/rescan。

批量重扫不为每个 worker heartbeat 写审计，避免日志膨胀。每个 run 通过 task/run ID
关联触发人、触发原因、scanner snapshot、policy revision 和最终决定。

## 配置与部署

全局配置建议：

```properties
kkrepo.security-scanning.enabled=false
kkrepo.security-scanning.adapter.base-url=http://scanner:8080
kkrepo.security-scanning.worker.batch-size=4
kkrepo.security-scanning.worker.lease-seconds=300
kkrepo.security-scanning.worker.heartbeat-seconds=60
kkrepo.security-scanning.worker.max-attempts=5
kkrepo.security-scanning.worker.max-backoff-seconds=1800
kkrepo.security-scanning.input-mode=stream
kkrepo.security-scanning.scanner-database-max-age=48h
```

数值只是配置形态示例，最终默认值由基准测试确定。

Docker Compose/quickstart：

- scanner 是可选 profile，不默认增加基础 quickstart 资源占用。
- scanner 使用独立 volume/cache 保存可重建漏洞数据库。
- 不挂载 Docker socket。
- readiness 未通过时 kkRepo audit 模式仍可启动，但 health details 显示 degraded。

Helm/Kubernetes：

- `securityScanning.enabled` 控制 adapter Deployment/Service 和 kkRepo 配置。
- scanner pod 有 CPU、memory、ephemeral-storage request/limit。
- 支持 NetworkPolicy、Pod Security Context、read-only root filesystem。
- 多个 kkRepo pod 访问同一 scanner service；任务所有权仍在共享数据库。
- adapter rolling update 期间 run 记录真实 engine/database snapshot。

## 保留、删除与 GC

建议分别配置：

- terminal task 保留期。
- immutable scan run/finding 保留期。
- 被当前 asset state 引用的最新 SBOM/run 最低保留期。
- 已删除 asset 的审计结果保留期。
- raw report 和 SBOM Blob 保留期。

删除顺序：

1. 清理不再被 asset state、waiver、审计保留规则引用的 finding/run。
2. 清理不再被 run 引用的 SBOM projection。
3. 对 SBOM/raw report Blob 调用现有软删除与 GC 流程。
4. Blob GC 在删除前再次检查 scan document 引用。

不能因为原始制品 asset 被删除，就立即删除仍被相同内容的其它 asset 复用的 SBOM。

## 双数据库实现约束

MySQL 与 PostgreSQL 必须保持：

- 相同状态枚举和时间精度。
- 相同唯一键、fingerprint 和幂等语义。
- 相同 `SKIP LOCKED` claim、lease takeover 和 fencing 行为。
- 相同 JSON DTO 语义，但核心 filter/order 字段使用普通列和索引。
- 相同分页稳定顺序。
- 相同删除/外键和 scan document Blob 引用语义。

不使用 PostgreSQL advisory lock 作为公共正确性机制，也不依赖 MySQL 专属
`GET_LOCK`。跨后端协调统一使用行锁、唯一约束、lease token 和 dialect 中性事务。

MySQL/PostgreSQL 公共 contract 至少覆盖：

1. 并发推进同一 asset candidate generation 不丢更新。
2. 并发创建同一 fingerprint 只产生一个 immutable SBOM/run。
3. 两个 worker 不领取同一 task。
4. lease 过期后可接管，旧 fencing token 无法完成任务。
5. finalize transaction 要么完整写入 finding/state/task，要么全部回滚。
6. asset Blob 替换后旧 run 不能更新最新 state。
7. Blob GC 不删除仍被 SBOM/raw report 引用的 object。
8. backfill 可暂停、恢复并在重复执行时幂等。

## 测试设计

### Domain 与单元测试

- candidate classifier 对全部 format 的正例、metadata 跳过和边界路径。
- catalog/match fingerprint 稳定性。
- scan/task/policy 状态机。
- severity 规范化、alias 和 finding key。
- waiver scope、到期和 policy revision。
- OCI platform aggregation。
- group 使用实际 member state 并选择更严格策略。

### Scanner contract

使用固定 scanner 版本与离线测试数据库，覆盖：

- capability/readiness/provenance。
- 普通 archive、单文件、CycloneDX 和 OCI digest。
- 0 finding、多个 severity、fixed/unfixed、withdrawn advisory。
- unsupported target。
- malformed/oversized report。
- timeout、429、临时错误和不可重试错误。
- scanner 版本与数据库 revision 变化。

CI 不能依赖实时漏洞源返回固定 finding；fixture 必须可复现。

### 恶意输入

- `../`、绝对路径、混合分隔符和 Unicode 路径逃逸。
- symlink/hard link/device/FIFO/socket。
- zip/tar bomb、超多条目、深层嵌套、超大单文件。
- checksum/size 不匹配。
- 恶意 package metadata、HTML/Markdown 和 URL。
- scanner 报告的 JSON nesting/field/body 上限。
- scoped token 越权、过期和日志脱敏。
- signed URL 重放与越权 object。

### Persistence 与多副本

MySQL 和 PostgreSQL 分别运行真实集成测试：

- candidate/task/SBOM/run/finding/state 全链路。
- 并发 claim 和 fingerprint insert。
- worker 中途退出后接管。
- scanner 调用完成但 DB finalize 前退出。
- finalize 完成但 HTTP 响应丢失后的幂等重试。
- backfill 与在线写入并发。
- profile/policy 更新与在途任务并发。

### 协议与真实客户端

Audit 模式：

- 全部现有真实客户端 E2E 的 status/header/body 不变。
- hosted、proxy、group 下载后产生正确候选和结果。
- metadata、checksum、signature 不产生无意义任务。

Enforce 模式：

- Maven、npm、PyPI、Go、Helm、Cargo、Pub、Composer、Terraform、Swift、
  Ansible、Docker、NuGet、RubyGems、Yum 和 Raw 分别覆盖 pending、block、allow。
- proxy 第一次回源严格模式不会泄漏未扫描响应体。
- group 命中不同 member 时使用正确 state。
- Docker tag、digest、multi-arch index 和 connector port 路由一致。
- HEAD、Range、conditional GET 和 redirect 不绕过策略。
- Components API、Browse download 和协议专用 artifact endpoint 不绕过策略。

安全扫描默认 disabled/audit，因此 Nexus 兼容黑盒不应产生差异。Enforce 是管理员显式
产品策略，其响应行为由 kkRepo 专项测试固定。

### 性能与容量

至少基准：

- 10 MB、100 MB、1 GB archive 的流式传输和临时磁盘峰值。
- 大量小文件归档。
- 大型 SBOM 与 finding 投影。
- 100 万 asset backfill 对数据库和线上请求的影响。
- 多副本 claim 吞吐与 scanner backpressure。
- 下载热路径 policy cache 命中/未命中延迟。
- OCI 多平台镜像扫描和 layer 复用。

## 实施顺序

### PR 1：领域模型与双数据库骨架

- 新增 `security-scan` 模块。
- 定义 subject、fingerprint、状态机、scanner SPI 和 policy decision。
- 增加双数据库 migration、DAO API 与 contract test。
- 实现 candidate/task/SBOM/run/finding/state 的基本 CRUD。
- 不接真实 scanner，不改变下载行为。

验收：

- MySQL/PostgreSQL fresh migration 和重复启动通过。
- 并发 claim、fencing、immutable fingerprint contract 通过。
- 模块依赖没有把 JDBC/internal 类型暴露给 domain/server 业务代码。

### PR 2：可靠候选与任务编排

- `JdbcAssetDao` 内容变更时事务性推进 candidate。
- Candidate worker、backfill job、task worker 和 retry/lease。
- Fake scanner adapter contract。
- task/queue 指标和审计。

验收：

- hosted、proxy、迁移写入都能产生候选。
- asset 覆盖、并发写和 worker crash 不会丢失扫描需求。
- 多副本不会重复拥有同一 task。

### PR 3：普通制品 Audit 扫描

- 交付参考 scanner adapter。
- STREAM Blob 输入、安全解包、Catalog、Match。
- CycloneDX/raw report Blob 与有界数据库投影。
- 格式 candidate matrix。
- Admin overview、task、run 和 finding 页面。

验收：

- 第一阶段支持矩阵中的普通制品产生可追溯 SBOM 与 finding。
- 漏洞数据库更新只创建 MATCH_ONLY task。
- 所有现有客户端 E2E 在 audit 模式无响应回归。

### PR 4：Docker/OCI

- digest-scoped 短期 scanner token。
- manifest/index/platform 解析和聚合状态。
- OCI scanner contract 与多平台测试。
- tag 移动、proxy/group 和 connector port 覆盖。

验收：

- 不按 tag 复用旧结果。
- 平台覆盖范围和 partial 状态准确。
- scanner 无需 Docker socket 或长期 registry credential。

### PR 5：策略、Waiver 与 Shadow Decision

- policy/waiver 版本化模型。
- materialized asset security state。
- `ArtifactDownloadPolicy` 接入全部读取入口，但只运行 shadow/audit。
- 统计潜在阻断与误报。

验收：

- 普通 reader、Components/Browse、group 和 Docker 路径没有漏点。
- shadow decision 不改变任何协议响应。
- waiver 权限、到期和审计通过。

### PR 6：可选 Enforce

- 仓库级 enforce feature flag。
- pending/failure/partial/finding 响应适配。
- proxy buffer-before-release。
- 真实客户端 enforce E2E。
- 运维告警、回滚开关和生产文档。

验收：

- 关闭 enforce 可立即恢复原协议行为，不需要清理扫描数据。
- 严格 proxy 首次回源不返回未扫描字节。
- 全协议真实客户端矩阵和多副本故障注入通过。
- scanner 不可用、数据库过期和 partial result 行为与配置一致。

### PR 7：生产加固与后续扩展

- signed URL 输入优化。
- 更完整的 retention/GC 和大规模 backfill。
- VEX、许可证、secret/misconfiguration 独立 profile。
- 可选 OCI SBOM referrer。
- 外部漏洞治理系统导出。

每项扩展都必须保留扫描类型、权限、结果状态和策略边界，不能把不同风险类别压缩成
一个 severity 字段。

## 发布与回滚

发布顺序：

1. 数据库 migration 和 domain/DAO，功能保持 disabled。
2. 部署 scanner，验证 readiness、数据库更新和资源上限。
3. 对测试仓库启用 audit。
4. 执行小范围 backfill，观察 backlog、partial、失败率和 finding 质量。
5. 扩大 audit 覆盖。
6. 只对经过验证的仓库启用 shadow policy。
7. 最后按仓库显式启用 enforce。

回滚：

- 全局关闭 worker 停止新任务领取，但保留数据库状态。
- 仓库从 enforce 切回 audit 立即停止下载阻断。
- scanner adapter 回滚不修改已有 immutable run；新任务记录回滚后的真实版本。
- migration 回滚遵循 kkRepo 现有数据库策略，不通过手工删表恢复旧应用。

## 完成标准

安全扫描能力只有在以下条件全部满足后才可声明生产可用：

1. hosted、proxy、迁移资产不会丢失扫描候选。
2. MySQL/PostgreSQL 使用相同 claim、lease、fingerprint 和 finalize 语义。
3. worker/scanner 任一副本退出后任务可恢复，旧 worker 不能覆盖新结果。
4. SBOM、finding、scanner/version/database provenance 可追溯。
5. 漏洞库更新可以复用 SBOM 重新匹配。
6. 普通制品与 OCI 平台覆盖范围明确，unsupported/partial 不会显示为 clean。
7. 扫描器不执行制品代码，并通过恶意归档与资源耗尽测试。
8. Audit 模式不改变现有协议和真实客户端行为。
9. Enforce 覆盖所有实际下载入口，proxy 严格模式不会先返回未扫描字节。
10. policy、waiver、重扫和阻断都有权限校验与审计。
11. backlog、scanner readiness、数据库年龄、failure、partial 和 policy block 可监控告警。
12. Blob GC、retention 和仓库删除不会破坏仍被引用的扫描文档或泄漏越权数据。

## 参考资料

### kkRepo 内部设计

- [架构说明](../architecture.md)
- [安全模型](../security-model.md)
- [监控观测指南](../monitoring-observability-guide.md)
- [MySQL / PostgreSQL 可插拔数据库访问层设计](pluggable-database-access-layer-design.md)
- [Docker 仓库实现说明](docker-repository-implementation-plan.md)

### 外部规范与扫描器能力

- [Trivy Filesystem scanning](https://trivy.dev/docs/latest/target/filesystem/)
- [Trivy filesystem CLI options](https://trivy.dev/docs/latest/references/configuration/cli/trivy_filesystem/)
- [Syft supported scan targets](https://oss.anchore.com/docs/guides/sbom/scan-targets/)
- [Grype supported scan targets](https://oss.anchore.com/docs/guides/vulnerability/scan-targets/)
- [CycloneDX specification overview](https://cyclonedx.org/specification/overview/)
- [Package URL specification](https://github.com/package-url/purl-spec/blob/main/PURL-SPECIFICATION.rst)
- [Open Source Vulnerability schema](https://ossf.github.io/osv-schema/)
- [OCI Distribution Specification](https://github.com/opencontainers/distribution-spec/blob/main/spec.md)
- [OCI Image Format Specification](https://github.com/opencontainers/image-spec/blob/main/spec.md)
