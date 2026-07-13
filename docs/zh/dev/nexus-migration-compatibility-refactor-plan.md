# Nexus 兼容迁移重构开发计划

本文细化 kkrepo 面向不同 Nexus Repository 版本的兼容迁移重构方案。这里讨论的不只是 Cargo。Cargo 只是第一个强信号：现有迁移链路主要围绕旧版 OrientDB Nexus 设计，不能直接扩展到当前使用 H2 或 PostgreSQL datastore 的 Nexus 版本。

迁移产品必须把 Nexus 当作兼容性参考，不能只根据版本号推断行为。源端 Nexus 版本可以作为风险提示，但真正选择迁移路径时，应以源端能力探测、schema 形态、repository recipe、API 可用性和真实行为为准。

## 问题定义

kkrepo 需要支持从不同存储时代的 Nexus 实例迁移仓库、blob、用户、用户组、角色、权限、token 和校验状态：

- 旧 Nexus 3，元数据使用 OrientDB。
- 新 Nexus，元数据使用 datastore，常见形态是内嵌 H2 或外置 PostgreSQL。
- 仓库格式可能在 Pro-only、Community、feature flag 或不稳定状态之间变化。
- 源端公开 REST API、Script REST API、内部 Java service 和数据库 schema 暴露的迁移面并不一致。

当前迁移实现可以覆盖历史 OrientDB 路径，但后续不应靠不断追加 `if version >= X` 分支来扩展。新版 Nexus 可能在不改变 `/repository/<repo>/...` URL 形态的情况下改变 schema、版本权益、脚本行为和仓库格式内部模型。

## 当前探测证据

本地 Nexus 3.77.2 参考实例在启用 `nexus.scripts.allowCreation=true` 后，通过 Script REST API 做过真实探测。

已观察到的行为：

- Script `/run` 需要 `Content-Type: text/plain`；空 body 可运行，缺少 body 元信息时可能返回 `415 Unsupported Media Type`。
- Nexus 版本为 `3.77.2-02`。
- 默认 datastore 名称为 `nexus`，JDBC 产品是 `H2 2.3.232`，URL 形态是 `jdbc:h2:file:/nexus-data/db/nexus`。
- `repository` 表包含 `ID`、`NAME`、`RECIPE_NAME`、`ONLINE`、`ROUTING_RULE_ID`、`ATTRIBUTES`。
- Cargo 元数据使用 datastore 表，例如 `cargo_content_repository`、`cargo_component`、`cargo_asset`、`cargo_asset_blob`、`cargo_browse_node`。
- `cargo_asset_blob.checksums` 是 JSON 数据，但 H2 通过 Groovy `getObject()` 可能返回 `byte[]`，探测脚本需要防御性解码。
- 不能假设 `repository` 表存在直接的 `format` 或 `type` SQL 列。应通过 `RECIPE_NAME`、`ATTRIBUTES` 或 Script API 中的 `RepositoryManager` 判断。

迁移含义：datastore 时代的源端需要区别于 OrientDB 的读取策略。正确抽象不是“Cargo 特殊处理”，而是面向所有仓库格式和 Nexus 存储代际的源端画像、能力规则和适配器框架。

## 设计原则

1. 先探测能力，再决定路径。
   迁移必须先识别真实源端能力。版本号只能作为预期和风险提示，不能作为唯一路由条件。

2. 迁移计划显式化。
   Preflight 应产出可复现的 `SourceProfile` 和 `MigrationPlan`，让用户在执行前确认。

3. 未知形态默认阻断。
   如果仓库格式、安全模型、token 存储、blob 引用模型或 checksum 模型无法识别，应标记为不支持或需要人工处理，而不是猜测迁移。

4. 保持现有 OrientDB 行为稳定。
   当前 OrientDB 迁移路径应先收进适配器中，重构不能破坏已支持能力。

5. 优先使用可靠的源端 API。
   Script API 和 Nexus 内部 service 可以屏蔽 schema 差异。只有当 profile 证明 schema 和数据模型可理解时，才允许直接数据库读取。

6. 为 resume 记录证据。
   在 kkrepo MySQL 中保存 profile hash、plan hash、探测时间、源端版本、datastore 类型和适配器选择。恢复执行时如果源端画像变化，应重新 preflight 或要求用户确认。

7. 把迁移当成产品能力。
   每个步骤都要支持 dry-run、resume、checksum 校验、审计和用户可见报告。

## 目标架构

### SourceProfile

`SourceProfile` 是源端探测后的标准化画像，应随每个迁移任务持久化。

建议字段：

| 字段 | 说明 |
| --- | --- |
| `nexusVersion` | Nexus 报告版本，例如 `3.77.2-02` |
| `buildRevision` | 可用时记录 build 或 commit 标识 |
| `scriptApi` | 是否禁用、是否可创建、是否可运行、content-type 行为和清理行为 |
| `metadataEngine` | `ORIENTDB`、`DATASTORE_H2`、`DATASTORE_POSTGRESQL` 或 `UNKNOWN` |
| `repositoryModel` | Orient bucket/asset 模型、datastore content 模型或 unknown |
| `securityModel` | Orient security、datastore security、`api_key`、`api_key_v2` 或 unknown |
| `blobStoreTypes` | File、S3、Azure、Google 或 unknown blob store |
| `enabledFormats` | 探测到的 format 和 recipe |
| `repositoryRecipes` | 仓库名、recipe、online 状态、attributes 和 blob store 关联 |
| `restCapabilities` | 可用 REST endpoint 及实际状态码行为 |
| `schemaFingerprints` | 表、列、索引和关键元数据指纹 |
| `formatCapabilities` | 格式专项能力，例如 Maven metadata、npm token、Cargo sparse index、Pub package metadata/upload session、Composer datastore proxy content、Docker connector |
| `warnings` | 展示给用户的风险提示 |
| `unsupportedItems` | 不能自动迁移的项目 |

### 探测流水线

探测应分层运行，每层向 `SourceProfile` 写入事实和 warning。

1. REST 基线探测
   - 读取状态和版本 endpoint。
   - 在权限允许时列出仓库、blob store、realm 和 capability。
   - 记录提供的凭据能访问哪些 REST endpoint。

2. Script 能力探测
   - 判断 script creation 是否启用。
   - 创建、运行并删除一个最小 smoke script。
   - 记录必要请求头，包括实际观察到的 `/run` `Content-Type: text/plain` 要求。
   - 后续探测脚本默认只读。

3. 元数据引擎探测
   - 优先尝试源端暴露的 Nexus datastore service。
   - 探测默认 datastore 名称、JDBC 产品、JDBC URL 形态和数据库主版本。
   - 对 OrientDB-backed 源端，按预期探测 OrientDB service 并选择对应策略。
   - 不假设可以在 Nexus 外部直接拿到 PostgreSQL 凭据。

4. 仓库模型探测
   - 通过 `RepositoryManager` 读取 repository recipe 和 attributes。
   - 指纹化 repository 表和 content 表。
   - 判断 format/type 来自 `RECIPE_NAME`、attributes 还是其它模型。

5. 安全和 token 探测
   - 探测用户、用户组、角色、权限、realm、API key、User Token 和 CI token。
   - 区分可读安全元数据和不可导出的 secret。
   - 对 password hash、加密 secret 和 token material 标记为可迁移、需重建或需人工处理。

6. Blob 和 checksum 探测
   - 探测 blob store 类型、asset 到 blob 的引用、checksum 字段、size 字段、软删除标记和直接 asset 下载兜底能力。
   - 对每种已支持格式尽量抽样验证一个 asset。

7. 格式专项探测
   - Maven：group/artifact/version 布局、`maven-metadata.xml`、checksum、snapshot。
   - npm：package metadata、tarball、dist-tag、scope package、token 行为。
   - PyPI：simple index、metadata、文件 hash。
   - Docker：manifest、blob、tag list、connector 行为。
   - Cargo：sparse `config.json`、crate index 文件、`.crate` blob、yanked 状态。
   - Pub：package metadata、`version.json`、archive blob、`archive_sha256`、upload session 形态、token 行为。
   - 其它格式必须先补齐同类探测，再允许自动迁移。

### 适配器分层

迁移实现应拆成适配器契约，而不是版本条件分支。

建议契约：

- `NexusSourceProbe`：收集事实并构建 `SourceProfile`。
- `CompatibilityRule`：判断某个 profile 是否支持某项迁移能力。
- `MigrationPlanBuilder`：将 profile 和用户选择范围转换成具体计划。
- `NexusMigrationAdapter`：源端家族的顶层适配器。
- `RepositoryConfigExporter`：导出仓库配置和格式 attributes。
- `RepositoryContentExporter`：导出 component、asset、path、blob 引用和 checksum。
- `SecurityExporter`：导出用户、组、角色、权限和 token 元数据。
- `BlobReader`：从直接 blob store 或 Nexus HTTP fallback 读取 blob bytes。
- `FormatMigrationAdapter`：格式专项映射和校验。

初始源端家族适配器：

| 适配器 | SourceProfile 匹配条件 | 职责 |
| --- | --- | --- |
| `OrientDbNexusAdapter` | `metadataEngine=ORIENTDB` | 保持现有 OrientDB 迁移行为 |
| `DatastoreH2NexusAdapter` | `metadataEngine=DATASTORE_H2` | 使用 Script/Internal service 和 H2 schema 指纹 |
| `DatastorePostgresqlNexusAdapter` | `metadataEngine=DATASTORE_POSTGRESQL` | 使用 Script/Internal service 和 PostgreSQL schema 指纹 |
| `RestOnlyNexusAdapter` | Script 禁用且无 DB 访问 | 仅通过公开 REST 或 repository HTTP 做配置和内容迁移 |

格式适配器应与源端家族适配器组合使用。例如，H2 上的 Cargo 是 `DatastoreH2NexusAdapter + CargoMigrationAdapter`，OrientDB 上的 Maven 是 `OrientDbNexusAdapter + MavenMigrationAdapter`。

## MigrationPlan 模型

Preflight 应为每个迁移区域产出明确状态。

建议状态：

| 状态 | 含义 |
| --- | --- |
| `FULL` | 配置、元数据、blob、权限、校验和切换检查都支持 |
| `CONFIG_ONLY` | 可迁移仓库配置，但无法安全导出内容 |
| `DATA_ONLY` | 可导入内容，但部分权限或 token 需要人工重建 |
| `UNSUPPORTED` | 源端行为未知或不支持，自动迁移阻断 |
| `NEEDS_MANUAL_ACTION` | 需要用户完成显式动作后才能继续 |

每个 plan item 应包含：

- 源仓库或安全区域。
- 选中的源端适配器和格式适配器。
- 读取方式：Script service、direct DB、public REST、repository HTTP 或 blob store。
- 写入 kkrepo 的方式。
- Checksum 和校验方式。
- Resume key。
- 已知不支持字段。
- 操作风险和用户可见 warning。

## 用户交互模型

正常迁移流程不应让用户选择源端是否大于某个版本号，而应让系统自动探测后展示事实：

1. 用户填写源端 URL、凭据和可选 blob-store 访问配置。
2. kkrepo 执行 preflight 探测。
3. UI 展示探测事实：版本、metadata engine、datastore 细节、script 状态、repository recipe、安全/token 支持、blob store 支持和 warning。
4. UI 展示拟定迁移计划，以及每个仓库的支持状态。
5. 用户确认范围、dry-run、跳过策略和人工动作。
6. 执行过程将进度和校验结果记录到 MySQL。

可以保留高级 override，用于支持和应急场景，但必须显式且可审计：

- 强制指定源端家族适配器。
- 禁用某个仓库或 token 迁移区域。
- 对不支持内容的仓库只迁移配置。
- 使用 repository HTTP 下载兜底，而不是直接读 blob。

版本预设可以辅助形成初始预期，但只作为提示：

- `<= 3.70.x`：预期 OrientDB，仍需探测确认。
- `>= 3.71.x`：预期 datastore，仍需探测确认 H2 或 PostgreSQL。
- `>= 3.77.x`：预期 Community 可用 previously Pro-only formats，仍需确认实际 recipe 和 UI/API 暴露状态。
- `>= 3.92.x`：预期 Pub 仓库可用，仍需通过探测确认实际 recipe、content model 和客户端可见行为。

## 执行语义

迁移执行必须保持幂等，并适配 kkrepo 多副本部署。

- 任务、profile、plan、阶段、checkpoint 和校验状态存储在选定的共享关系数据库中。
- 对仓库、component、asset 和权限使用稳定源端 identity key。
- 导入对象使用显式唯一约束和 upsert。
- Blob bytes 只存 OSS/S3 或 File blob store，不存 MySQL。
- 进程内 cache 只作为可重建加速层。
- 每个阶段支持 dry-run、retry、resume 和审计报告。
- Resume 前校验 profile hash 和 plan hash。
- 切换校验对比源端和目标端数量、关键元数据、blob size、checksum 和客户端可见 URL。

## 开发阶段

### 阶段 1：盘点和边界固定

- 盘点当前迁移代码路径、脚本模板、OrientDB 假设、直接 SQL 假设、token 假设和 checksum 校验。
- 将可复用脚本拆成命名 probe/exporter template。
- 重构前记录当前 OrientDB 支持行为。
- 为现有 OrientDB 迁移路径增加测试夹具，避免重构回归。

### 阶段 2：SourceProfile 和探测框架

- 新增 `NexusSourceProfile` 模型、持久化和 JSON 报告输出。
- 增加 REST 基线探测和 Script API smoke 探测。
- 增加 OrientDB、H2、PostgreSQL 的 metadata engine 探测。
- 增加 repository model、blob model 和 security model 指纹探测。
- 在任何 import 开始前，通过迁移 CLI/API/UI 暴露 preflight 结果。

### 阶段 3：CompatibilityRule 和 PlanBuilder

- 增加 compatibility rules，将 `SourceProfile` 映射到受支持迁移能力。
- 生成可复现的 `MigrationPlan`。
- 按仓库和安全区域展示 `FULL`、`CONFIG_ONLY`、`DATA_ONLY`、`UNSUPPORTED`、`NEEDS_MANUAL_ACTION`。
- 持久化 profile hash 和 plan hash。

### 阶段 4：现有迁移适配器化

- 将当前 OrientDB 逻辑收敛到 `OrientDbNexusAdapter`。
- 保持已有成功迁移路径行为不变。
- 所有迁移执行统一通过新的 plan model。
- 围绕 OrientDB 仓库、asset、权限和 checksum 迁移补回归测试。

### 阶段 5：Datastore 源端适配器

- 实现 `DatastoreH2NexusAdapter`，优先使用 Script/Internal service，其次使用 schema 指纹。
- 实现 `DatastorePostgresqlNexusAdapter`，保持相同契约。
- 对 checksum metadata 等 datastore 列增加 JSON/byte 防御性解码。
- 不依赖未经 profile 指纹证明的表列。

### 阶段 6：格式专项扩展

- Maven、npm、PyPI、Go、Helm、NuGet、RubyGems、Yum、Docker、Cargo、Pub 和 Composer 都必须有探测证据和校验逻辑后再启用；Composer 仅接受管理员显式选择的 Nexus 原生 proxy。
- 对尚不能安全导出内容的格式，先允许配置迁移。
- Asset/blob/checksum 映射证明后，再启用内容迁移。
- Cargo 迁移只在 Nexus 3.77.x+ H2/PostgreSQL source profile 证明 sparse index、crate blob、token、权限、checksum 和切换验证方案后启用。
- Pub 迁移只在 Nexus 3.92.0+ datastore source profile 证明 package metadata、`version.json`、archive blob、`archive_sha256`、token、权限、checksum 和切换验证方案后启用。

### 阶段 7：参考实例矩阵和自动化

维护可丢弃的 Nexus 参考实例矩阵：

| 参考实例 | 目的 |
| --- | --- |
| Nexus 3.29.2 OrientDB | 现有历史迁移基线 |
| Nexus 3.73.0 datastore/H2 | datastore 时代参考实例，Community 下 Cargo 可能未暴露 |
| Nexus 3.77.x Community/H2 | 当前 Community 下 previously Pro-only formats 参考实例，例如 Cargo |
| Nexus 3.77.x 或更新 PostgreSQL | 外置 datastore 行为 |
| Nexus 3.92.0+ datastore | Pub hosted/proxy/group 参考行为、Pub 迁移和 Dart/Flutter 客户端行为 |
| 可选 Pro 或 feature-flagged 实例 | 功能开关行为 |

自动化检查应覆盖：

- Probe 输出 snapshot 测试。
- Migration plan snapshot 测试。
- Dry-run 测试。
- 各格式小规模真实 import 测试。
- Checksum 和客户端可见 URL 校验。
- Resume 和 profile drift 测试。

## 验收标准

- Preflight 在不导入数据的情况下产出可复现 source profile。
- 同一 profile 和用户范围总是产出相同 migration plan。
- 现有 OrientDB 支持的迁移可以通过新适配器路径继续工作。
- H2 和 PostgreSQL 源端探测可通过 Nexus Script API 完成，不要求用户额外提供外部数据库凭据。
- 每个仓库和安全区域都有明确支持状态和原因。
- 未知 schema、未知 token store 和不支持格式默认阻断。
- 源端 profile 变化时，resume 不会静默继续。
- 迁移报告包含数量、跳过项、warning、checksum 结果和人工动作。
- 文档提示 Nexus Script API 只应在迁移窗口内启用，探测/导出完成后应关闭。

## 待确认问题

- 哪些 Nexus 版本需要进入每次发布的最小自动化参考矩阵？
- 哪些 token 类型可以作为可复用 secret material 迁移，哪些必须在 kkrepo 中重建？
- 大规模迁移是否要求直接 blob-store 读取，还是默认使用 repository HTTP fallback 更安全？
- Datastore schema 指纹允许多大漂移，超过后必须阻断迁移？
- 哪些迁移区域需要先做产品 UI，哪些可以先走 CLI/admin API？
