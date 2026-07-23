# 兼容性矩阵

本文汇总 kkrepo 当前公开兼容面。这里关注的是用户可见行为：客户端命令、HTTP 路径、仓库 recipe、迁移支持和已知限制。除非 Nexus 内部机制会影响客户端行为，否则不把内部实现细节作为兼容目标。

更详细的验证流程见 [Nexus 兼容性测试说明](nexus-compatibility-testing.md)。

下表中的验证类主要是黑盒协议检查。`client-e2e` suite 会额外覆盖 Maven、npm、PyPI、Go resolve、Helm、Cargo/Rust、Dart/Pub、Composer/PHP、Terraform 0.13/当前稳定版、SwiftPM/Xcode、Ansible Galaxy 2.9/当前版、NuGet、RubyGems、Yum、Docker/OCI 的真实包管理器客户端行为；运行环境要求和 `artifacts/client-e2e/` 诊断信息见 [compat-test README](../../compat-test/README.md)。

## 兼容原则

- 保持 Nexus `/repository/<repo>/...` URL 布局，尽量复用既有客户端配置。
- 先对齐官方协议和 Nexus 用户可见行为，再增加项目自定义行为。
- 对外可见行为优先通过真实 Nexus 参考实例做兼容性测试。
- 有状态逻辑默认按多副本部署设计：所选 MySQL/PostgreSQL 数据库是元数据和协调状态的事实来源；blob 内容放在 OSS/S3/File 存储；进程内缓存必须可重建。

## 数据库后端矩阵

| 后端 | 运行时 | Flyway | 公共持久层契约 | 双实例 server smoke |
| --- | --- | --- | --- | --- |
| MySQL 8 | 支持；默认 | 不可变 V1-V29 历史，从 V30 成对迁移 | 真实 MySQL 容器 | 全新/重复启动与跨节点 session |
| PostgreSQL 12+ | 支持；生产使用仍在维护期的版本 | 等价 V29 baseline，从 V30 成对迁移 | PostgreSQL 12 最低版本 contract，加 PostgreSQL 16 E2E | PostgreSQL 12 全新/重复启动与跨节点 session |

数据库选择不会改变仓库协议行为。CI 会在两种引擎上执行同一套 JDBC API 契约，详见[数据库后端](database-backends.md)。

## 仓库格式矩阵

| 格式 | 仓库类型 | 主要客户端操作 | 浏览/搜索 | 迁移支持 | 兼容性验证 |
| --- | --- | --- | --- | --- | --- |
| Maven | hosted / proxy / group | Maven deploy、PUT 上传、GET/HEAD/checksum 读取、snapshot/release metadata、管理台组件上传 | 支持 | 默认迁移 hosted；proxy 可选 | `MavenRepositoryBlackBoxCompatibilityTest`、`MavenMetadataMergeCompatibilityTest`、`MavenWritePolicyCompatibilityTest`、`ComponentUploadBlackBoxCompatibilityTest` |
| npm | hosted / proxy / group | `npm publish`、tarball 下载、包 metadata、dist-tags、audit endpoint 兼容、管理台上传 | 支持 | 默认迁移 hosted；proxy 可选 | `NpmProtocolCompatibilityTest`、`NpmRepositoryBlackBoxCompatibilityTest`、`ComponentUploadBlackBoxCompatibilityTest` |
| PyPI | hosted / proxy / group | `twine upload`、包下载、simple index 读取、管理台上传 | 支持 simple index | 默认迁移 hosted；proxy 可选 | `PypiRepositoryBlackBoxCompatibilityTest`、`ComponentUploadBlackBoxCompatibilityTest` |
| Go | proxy / group | Go module proxy 读取：list、info、mod、zip、latest、group fallback | 支持 | proxy 可选 | `GoProxyBlackBoxCompatibilityTest` |
| Helm | hosted / proxy | Chart push、PUT 上传、chart 下载、`index.yaml`、proxy index rewrite、管理台上传 | 支持 `index.yaml` | 默认迁移 hosted；proxy 可选 | `HelmRepositoryBlackBoxCompatibilityTest`、`ComponentUploadBlackBoxCompatibilityTest` |
| Cargo / Rust | hosted / proxy / group | Sparse registry 读取、`cargo publish`、`.crate` 下载、yank/unyank、Cargo search、CargoToken 认证、UI/API `.crate` 上传 | 支持 sparse index 和 Cargo search | source profile 确认 Cargo content 后支持 datastore H2/PostgreSQL hosted；proxy 仅在显式选择且计划为 `FULL` 时迁移 | `CargoRepositoryBlackBoxCompatibilityTest`、`ComponentUploadBlackBoxCompatibilityTest` |
| Dart / Pub | hosted / proxy / group | `dart pub publish`、`dart pub get`、`flutter pub get`、package metadata、archive 下载、Nexus `api/archives` 下载别名、`archive_sha256`、PubToken 认证、UI/API `.tar.gz` 上传 | 支持 package/version metadata 和 archive 属性 | Nexus 3.92.0 datastore source profile 确认 Pub content 后支持 hosted full；proxy cache 仅在显式选择 backup 且计划为 `FULL` 时迁移 | `PubRepositoryBlackBoxCompatibilityTest`、`ComponentUploadBlackBoxCompatibilityTest` |
| Composer / PHP | hosted / proxy / group | Composer 2 `install/show`、`packages.json`、stable/dev p2 metadata、Nexus 风格 dist path、Basic auth、Components API/UI archive 上传、group canonical first-match | 支持 package/version、dist、HTML View、Browse/Search 和 Usage | Nexus 原生 Composer 仅支持 proxy；配置迁移后，cache 只有在管理员通过 `backupProxyRepositories` 显式选择且 source profile 证明 content model 时才迁移 | `ComposerRepositoryBlackBoxCompatibilityTest`、Composer server/protocol tests、真实 Composer client E2E、migration E2E |
| Terraform Provider / Module Registry | hosted / proxy / group | Module/provider version 与下载、Nexus 兼容 PUT/UI/API 上传、Provider platform、SHA256SUMS、detached GPG signature、URL token 认证、registry.terraform.io proxy 和 group source binding | 支持 module/provider coordinate、version、platform、HTML View、Browse/Search 和 Usage；内部 route/cache asset 不对用户暴露 | Nexus Terraform hosted full 迁移、显式选择的 proxy archive cache 迁移及 proxy/group 配置迁移 | `TerraformRepositoryBlackBoxCompatibilityTest`、Terraform server/protocol tests、Terraform 0.13/当前稳定版 client E2E、真实 Nexus proxy 迁移 E2E |
| Swift Package Registry | hosted / proxy / group | Registry v1 release list/metadata/manifest/archive/identifiers、`swift package-registry login/publish`、GitHub-backed proxy、SCM replacement、CMS 签名、不可变发布、Range/cache validator 和 group source binding | 支持 scope/package/version、checksum、签名、tools version、source member、Browse/Search 和 Usage | 仅 Nexus 3.92.x-3.94.x 且 Swift datastore shape 已验证时 hosted 数据可规划为 `FULL`；版本超出范围、shape 漂移或 proxy secret 不可用时需 manual action | `SwiftRepositoryBlackBoxCompatibilityTest`、Swift protocol/server contract、SwiftPM 5.7/5.10/6.x、macOS Xcode、Windows proxy、S3-compatible 双副本 resilience 和 migration E2E |
| Ansible Galaxy | hosted / proxy / group | Galaxy v3 discovery、collection/version metadata、`ansible-galaxy collection publish/install/download`、multipart task 轮询、Nexus raw PUT、依赖解析、artifact checksum 固定、Bearer/Token/Basic 认证和 group source binding | 支持 namespace/name/version、dependency、SHA-256、signature 状态、source member、Browse/Search 和 Usage | Repository definition 与 hosted/proxy collection data 仅对 Nexus 3.93.x-3.94.x 原生 shape 开放；proxy cache 需显式选择且 plan 为 `FULL` | `AnsibleGalaxyRepositoryBlackBoxCompatibilityTest`、Ansible protocol/server contract、Ansible 2.9/当前版 client E2E、双副本生命周期测试和 migration contract |
| NuGet | hosted / proxy / group | package push、包下载、v3 service index、registration、flat container、search/autocomplete、管理台上传 | 支持 v3 service index/search | 默认迁移 hosted；proxy 可选 | `NugetRubygemsYumRepositoryBlackBoxCompatibilityTest` |
| RubyGems | hosted / proxy / group | gem push/yank、gem 下载、compact 和 legacy index assets、管理台上传 | 支持 | 默认迁移 hosted；proxy 可选 | `NugetRubygemsYumRepositoryBlackBoxCompatibilityTest` |
| Yum | hosted / proxy / group | RPM PUT/upload、包下载、`repodata` metadata | 支持 `repodata` | 默认迁移 hosted；proxy 可选 | `NugetRubygemsYumRepositoryBlackBoxCompatibilityTest` |
| Raw | hosted / proxy / group | PUT 上传、GET/HEAD 读取、group/proxy fallback、管理台上传 | 支持 | 默认迁移 hosted；proxy 可选 | `RawRepositoryBlackBoxCompatibilityTest`、`ComponentUploadBlackBoxCompatibilityTest` |
| Docker / OCI | hosted / proxy / group | Registry V2 login、hosted push/pull、proxy pull、group pull、manifest、blob、tag、upload session、cross-repo mount、referrers、content cleanup、Docker Hub `library` namespace 补偿 | 支持 manifest/tag/blob metadata | Docker hosted 仓库数据迁移走 Nexus Repository Data | `DockerRegistryBlackBoxCompatibilityTest`、Docker server/protocol 测试、OCI conformance workflow、[Docker / OCI 实现说明](dev/docker-repository-implementation-plan.md) |

Swift 验证证据按层级区分。Nexus 3.94.x 对比覆盖 canonical JSON/`Link`、`v`/`V` tag、renamed GitHub repository、不可变发布、group 重排/nested 与跨副本并发读；candidate black-box check 覆盖 active/revoked/expired `GenericToken` 和真实 5 MiB 限制拒绝。Server 和 persistence contract 覆盖 moving tag 不可变性、1,200 tag 分页上界、cleanup 和失败传播。真实客户端/存储 lane 覆盖 SwiftPM 5.7/5.10/6.x、macOS Xcode、Windows proxy resolve、多 MiB package、共享 429/5xx 水位与 stale fallback、通过 AWS S3-compatible adapter 访问 MinIO，以及双副本下破坏式数据库/object 备份恢复。阿里云 OSS Native 引擎当前由 adapter contract 验证，本矩阵不声称已运行真实 OSS Native endpoint E2E。

## 管理和安全兼容

| 领域 | 当前兼容目标 | 验证方式 |
| --- | --- | --- |
| 安全管理 API | Nexus 风格的用户、角色、权限、仓库引用、realm 类型名，以及部分 ExtDirect/UI contract | `SecurityAdminBlackBoxCompatibilityTest` |
| 仓库权限模型 | Nexus 风格的 repository view、browse、read、edit、add、delete、component-create 语义 | server 安全测试和 live compatibility 测试 |
| 组件上传 API | Nexus 风格 `/service/rest/v1/components` 上传规格和部分格式上传 | `ComponentUploadBlackBoxCompatibilityTest` |
| Browse API | 仓库 browse 返回形态和权限过滤 | `SecurityAdminBlackBoxCompatibilityTest` 和 server browse 测试 |
| 认证 realm | Local 用户、LDAP、OIDC bearer/auth-code、API key、session subject | server 安全测试 |

## URL 兼容

主要客户端入口是：

```text
/repository/<repo>/<artifact-path>
```

示例：

```text
/repository/maven-public/org/example/app/1.0.0/app-1.0.0.pom
/repository/npm-hosted/@scope/package
/repository/pypi-proxy/simple/demo/
/repository/helm-hosted/index.yaml
/repository/cargo-group/config.json
/repository/cargo-hosted/crates/demo/1.0.0/download
/repository/pub-group/api/packages/path
/repository/pub-hosted/api/archives/demo_package-1.0.0.tar.gz
/repository/composer-group/packages.json
/repository/composer-group/p2/vendor/package.json
/repository/terraform-group/v1/modules/acme/network/aws/versions
/repository/terraform-group/v1/providers/hashicorp/null/versions
/repository/ansible-group/api/v3/collections/acme/tools/versions/1.0.0/
/repository/ansible-hosted/api/v3/plugin/ansible/content/published/collections/artifacts/acme-tools-1.0.0.tar.gz
/repository/nuget-group/v3/index.json
```

Docker / OCI 比较特殊，因为 Docker 客户端使用 registry `/v2/...` 路由。共享入口部署会把 image path 第一段作为 kkrepo 仓库名：

```text
<host>:<shared-port>/<repo>/<image>:<tag>
```

配置仓库级 Docker connector port 后，也可以暴露标准 image 形态：

```text
<host>:<repo-port>/<image>:<tag>
```

## 迁移兼容

kkrepo 把迁移作为产品能力，而不是一次性脚本：

- 元数据迁移覆盖用户、角色、权限、blob store、repository 定义和相关兼容数据。
- 仓库数据迁移默认扫描 hosted 仓库。
- proxy 仓库可显式指定，用于迁移历史备份数据或回源缓存数据。
- Cargo / Rust hosted 仓库数据迁移已支持 datastore H2/PostgreSQL 源端，但必须由 preflight 证明 Cargo content model；未知 schema 默认 fail closed。
- Dart / Pub hosted 仓库数据迁移已支持 Nexus 3.92.0+ datastore 源端，但必须由 preflight 证明 Pub content model；Pub proxy cache 迁移要求显式选择且 plan 为 `FULL`。
- Composer 只迁移 Nexus 原生 proxy repository；未显式选择时只迁移配置，不迁移 cache。选择 cache 迁移时必须由 source profile 证明 Composer datastore content model，未知或非原生 Composer source fail closed。
- Terraform hosted module/provider 数据通过协议感知的 writer 重建，包括 Provider platform、checksum 和签名 metadata。显式选择的 Nexus 原生 Terraform proxy 使用独立 cache restore 路径，module/provider archive 保留 Nexus 公开 path。Module download discovery 可直接选择已恢复的本地 archive；Provider remote route、validator、checksum manifest 和 signature snapshot 从已配置上游重建，并在 metadata 有效期内固定对应缓存 blob。
- Swift repository definition 会保留 hosted/proxy/group 配置、TTL 和有序成员。可恢复的 proxy credential 以密文保存；源 secret 被遮蔽或缺失时生成 `NEEDS_MANUAL_ACTION`，目标 proxy 保持 offline，且不写入占位 credential。Hosted archive、checksum 和 manifest 仅对已验证 Nexus 3.92.x-3.94.x datastore shape 规划为 `FULL`；签名、原始 metadata 和 repository URL mapping 仅在源导出实际包含对应字段时保留，绝不伪造。原生 Nexus 3.94 接受这些可选字段后并不会持久化。版本超出范围、未知 profile 和 shape 漂移均 fail closed。Migration E2E 覆盖 Nexus 3.94 H2 源到 MySQL，以及 PostgreSQL 源到 MySQL/PostgreSQL 目标，并验证 restart/resume 和精确行数幂等。
- Ansible Galaxy repository definition 会保留 hosted/proxy/group 配置、TTL 和有序成员。Hosted collection 与显式选择的 proxy cache 仅在 Nexus 3.93.x-3.94.x 原生 datastore shape 已验证时规划为 `FULL`。完整性字段缺失、未知 profile、shape 漂移以及 proxy credential 被遮蔽/缺失都 fail closed 并生成 manual action。Collection tarball 与完整 `MANIFEST.json`/`FILES.json` 放在 blob storage；关系表只保存有上限的元数据投影、hash、引用、task、lease 和 source binding。
- 迁移步骤按 preflight/dry-run、resume、checksum 校验和报告能力设计。
- 不支持或被阻塞的条目应进入报告，而不是静默跳过。

详见 [Nexus 迁移说明](nexus-migration-guide.md)。

## 已知限制

- kkrepo 不是 Nexus 内部机制的完整复刻。Karaf、OSGi、OrientDB、内嵌 Elasticsearch 和 Nexus task 子系统不是兼容目标。
- Docker / OCI 使用 Registry HTTP API V2 和 OCI Distribution；Docker Registry V1 API 与 `docker search` 不属于当前支持面，除非后续出现明确兼容需求再评估 search-only shim。
- Docker connector listener 变更可通过 Docker operations endpoint 刷新；高级 connector TLS/SNI 管理属于部署侧能力。
- Cargo / Rust 支持 Cargo sparse registry。Cargo git index 协议、crates.io 风格 GitHub owner 邀请、删除已发布 crate version 当前不支持。Cargo 迁移需要 datastore H2/PostgreSQL schema 指纹；OrientDB Cargo 内容导出不会启用。
- Dart / Pub 支持 Hosted Pub Repository V2 hosted/proxy/group 工作流。pub.dev social、publisher、score、download-count 和 advisory API 不作为协议正确性依赖。
- Composer 仅承诺 Composer 2 metadata；Composer 1 `provider-includes` 主线、Packagist security-advisories/metadata-changes、VCS source checkout 和标准 publish 命令不在当前支持面。Hosted 发布使用 Components API 或 UI archive 上传。
- Terraform 当前支持通过 CLI `host.services` 显式配置的 Module Registry Protocol 与 Provider Registry Protocol；根域 discovery/virtual-host binding 和 Provider Network Mirror Protocol 暂未暴露。Proxy 保留并校验上游 signing key，不会用 kkrepo 签名冒充上游。
- Terraform proxy 迁移只恢复协议可识别的 module/provider archive cache，不把它们当作 hosted publication。Module download metadata 可在不访问上游时解析已恢复的本地 path；Provider metadata 会重建并校验上游 route/checksum/signature snapshot。未知 source schema、community plugin 和低于 `FULL` 的计划仍会 fail closed。
- Swift proxy 有意限定为与 Nexus 3.94.x 兼容的 GitHub source-to-registry 模式，不暴露 generic registry chaining 或 `/availability` endpoint。Swift 规范中 `POST /login` 是可选能力（未实现的服务端可返回 `501`），但 kkRepo 已实现 `200`/`401`，`501` 不是 kkRepo 的预期响应。Windows E2E 只覆盖 proxy resolve/build，不把 hosted publish 列为验收项。
- Ansible 当前支持 Galaxy v3 collection，不支持 Galaxy v1 role、GitHub role import、notification secret 和 `ansible-galaxy role install`。Collection version 不可变；大体积上游 JSON 和完整 manifest/files 文档作为 blob 内容处理，不存成无上限数据库 JSON。
- Go 不支持 hosted 上传；Go module proxy 行为以读取代理为主。
- 不承诺覆盖每一个 Nexus UI endpoint。只有在支持用户工作流或迁移兼容需要时，才补对应 endpoint。
- 当协议允许非确定性时，测试中可能规范化排序、时间戳、生成 ID 和 hostname。
- File blob storage 可用于本地试用和开发；生产部署建议使用 OSS/S3 兼容存储。

## 如何反馈兼容差异

提交 Nexus compatibility issue，并包含：

- Nexus 版本和 kkrepo 版本或 commit。
- 仓库格式和 recipe。
- 精确客户端命令或 HTTP 请求。
- Nexus 的状态码、header 和响应体语义。
- kkrepo 的状态码、header 和响应体语义。
- 对真实客户端的影响。

普通兼容差异可以用公开 issue。可利用的安全问题请按 [SECURITY.md](../../SECURITY.md) 私下报告。
