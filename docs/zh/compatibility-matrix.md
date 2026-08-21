# 兼容性矩阵

本文汇总 kkrepo 当前公开兼容面。这里关注的是用户可见行为：客户端命令、HTTP 路径、仓库 recipe、迁移支持和已知限制。除非 Nexus 内部机制会影响客户端行为，否则不把内部实现细节作为兼容目标。

更详细的验证流程见 [Nexus 兼容性测试说明](nexus-compatibility-testing.md)。

下表中的验证类主要是黑盒协议检查。`client-e2e` suite 会额外覆盖 Maven、npm、PyPI、Go resolve、Helm、Cargo/Rust、Dart/Pub、Composer/PHP、Terraform 0.13/当前稳定版、SwiftPM/Xcode、Ansible Galaxy 2.9/当前版、Conda、APT/Debian、Conan 2、Alpine/APK、R 4.5/4.6、NuGet、RubyGems、Yum、Docker/OCI 的真实包管理器客户端行为；Hugging Face 使用独立的 opt-in Nexus 矩阵与已记录的 Hub/Transformers/Diffusers 客户端验证。运行环境要求和 `artifacts/client-e2e/` 诊断信息见 [compat-test README](../../compat-test/README.md)。

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
| Conda | hosted / proxy / group | 根/嵌套 channel、`.tar.bz2`/`.conda` package 读取、Nexus raw PUT 与 UI/API 上传、JSON/BZ2/ZSTD repodata、current repodata、channeldata、条件读取、上游 proxy 和有序 group 解析 | 支持 channel/subdir/name/version/package 层级、package metadata、Browse/Search 和 Usage | Repository definition 与 hosted package 仅对已验证的 Nexus 3.92.x-3.94.x datastore shape 为 `FULL`；生成 metadata 在目标端重建 | `CondaRepositoryBlackBoxCompatibilityTest`、Conda protocol/server/persistence contract、真实 Conda client E2E、双副本检查、cleanup/scanning 测试和 migration E2E |
| APT / Debian | hosted / proxy | 仓库根 POST 与 Components API/UI `.deb` 上传、签名 Packages/Release/InRelease、by-hash、GET/HEAD/Range/validator、passthrough/re-sign proxy，以及 `apt update/download/install/upgrade` | 支持 distribution/component/package/version/architecture 层级、package metadata、Browse/Search 和 Usage | Definition 与 hosted `.deb` 仅对已验证的 Nexus 3.92.x-3.94.x datastore shape 开放；生成 metadata 在目标端重建，私钥需在目标端显式导入 | `AptRepositoryBlackBoxCompatibilityTest`、APT protocol/server/persistence contract、Debian/Ubuntu/当前 APT 真实客户端 E2E、双副本、cleanup/scanning 和 migration E2E |
| Conan 2 | hosted / proxy / group | Bearer 登录、`conan upload/list/download/install/remove`、以 manifest 为提交边界的 RREV/PREV 发布、文件 GET/Range、与 Nexus 一致的 HEAD 404、上游 proxy 和有序 group source binding | 支持 recipe version/RREV/package ID/PREV metadata、与 Nexus 对齐且在写入时固化的 Browse 投影、Search 和 Usage | Nexus 3.94 definition 与 hosted revision 按 shape gate；proxy cache 需显式选择；Conan 1、混合/未知 shape 和不完整 revision 均 fail closed | `ConanRepositoryBlackBoxCompatibilityTest`、Conan protocol/server/双数据库 contract、真实 Conan 2.31.2 E2E、cleanup/scanning 测试、migration contract 和 Nexus 性能门槛 |
| Alpine / APK | hosted / proxy / group | 规范 APK v2 PUT 与 UI/Components API 上传、签名 `APKINDEX.tar.gz`、GET/HEAD/Range/validator、passthrough 或验签后 re-sign proxy、有序 group source binding，以及 `apk update/search/policy/fetch/add/upgrade` | 支持 distribution/channel/repository-architecture/package/version 层级、checksum/signature/source metadata、Browse/Search 和 Usage | 支持 Nexus 3.94 definition 与 shape 可证明的 hosted `.apk`；生成 index 在目标端重建，signing key 或 proxy secret 不可用时 fail closed 并报告 manual action | `AlpineRepositoryBlackBoxCompatibilityTest`、Alpine protocol/server/双数据库 contract、apk-tools 2.14/3.0 真实客户端 E2E 与版本差分、cleanup/scanning 测试、migration contract 和 Nexus 性能门槛 |
| R / CRAN | hosted / proxy / group | 规范 source `.tar.gz` PUT 与 UI/Components API 上传、生成 `PACKAGES.gz`、GET/HEAD/Range/validator、任意路径 proxy cache、snapshot-bound group，以及 `available.packages/install.packages/update.packages` | 支持 package/version/dependency/license/checksum/source-member metadata、Browse/Search、Cleanup、扫描和 Usage | 支持 Nexus 3.94 definition 与 shape 可证明的 hosted source package；生成 index、proxy projection、group binding 和 lease 在目标端重建 | `RRepositoryBlackBoxCompatibilityTest`、R protocol/server/双数据库 contract、R 4.5.3/4.6.1 真实客户端 E2E 与版本差分、cleanup/scanning 测试、migration contract 和 Nexus 性能门槛 |
| Hugging Face Models | proxy | `hf download`、`hf_hub_download`、`snapshot_download`、Transformers/Diffusers 模型加载，model/revision/tree/paths-info/refs API，commit-pinned GET/HEAD/Range/validator 与服务端 Git LFS/Xet bridge | 支持 namespace/model/commit/file 层级、requested ref、Git/LFS/internal checksum、模型 metadata、Browse/Search、Cleanup、扫描和 Usage；内部 API cache/lease asset 不对用户暴露 | 识别 Nexus 3.77+ definition；显式选择的 Nexus 3.94 proxy content 只有在 source shape 能证明 repo/commit/path/checksum 时为 `FULL`，masked secret 与未知 shape 失败关闭 | `HuggingFaceRepositoryBlackBoxCompatibilityTest`、protocol/server/双数据库 contract、`huggingface_hub` 0.34.6/1.27.0、`hf` CLI、Transformers/Diffusers、S3-compatible 双副本、migration contract 与 Nexus 性能门槛 |
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
/repository/conda-group/noarch/repodata.json.zst
/repository/conda-hosted/team/release/linux-64/demo-1.0.0-py_0.conda
/repository/apt-hosted/dists/stable/InRelease
/repository/apt-hosted/pool/d/demo/demo_1.0.0-1_amd64.deb
/repository/conan-group/v2/conans/search?q=demo%2F1.0.0%40team%2Fstable
/repository/conan-hosted/v2/conans/demo/1.0.0/team/stable/revisions/<rrev>/files/conanfile.py
/repository/huggingface-models/api/models/hf-internal-testing/tiny-random-bert/tree/main
/repository/huggingface-models/hf-internal-testing/tiny-random-bert/resolve/main/config.json
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
- Conda repository definition 会保留 hosted/proxy/group 配置、TTL 和有序成员。Hosted `.tar.bz2`/`.conda` package 仅对已验证的 Nexus 3.92.x-3.94.x datastore shape 为 `FULL`。迁移时校验 package identity、size 和 checksum，过滤源端生成的 repodata/channeldata，并从目标关系投影重建。未知 profile、shape 漂移或无法证明 package identity 时 fail closed。Migration E2E 覆盖 Nexus H2/PostgreSQL 源、两种目标数据库、真实 Conda 安装、跨副本读取、checksum、restart/resume 和精确行数幂等。
- APT repository definition 会保留 hosted/proxy 配置、TTL、distribution policy 和 passthrough/re-sign 模式。Hosted `.deb` 仅在 Nexus 3.92.x-3.94.x datastore shape 能证明 canonical package path、APT attributes 和 SHA-256 时恢复；源端生成的 `dists/` metadata 会被过滤并从目标投影重建。私钥绝不静默复制，管理员显式导入前目标仓库保持 offline。Migration E2E 覆盖 H2/PostgreSQL 源、MySQL/PostgreSQL 目标、真实 `apt` 安装、checksum/行数、key fail-closed 与跨副本读取。
- Conan repository definition 会保留 hosted/proxy/group 配置、TTL 和有序成员。Hosted Conan 2 recipe/package revision 仅在 Nexus 3.94 datastore shape 能证明 canonical coordinate、完整 manifest 与 checksum 时恢复；不完整 revision、Conan 1 layout、混合 shape 和未知 profile 均 fail closed。Proxy cache 迁移需显式选择。协议感知的 writer 会重建关系投影，并在提交时持久化 Nexus 对齐的 Browse 展示路径，不需要迁移后补映射。
- Alpine repository definition 会保留 hosted/proxy/group 配置、TTL、namespace allowlist、metadata mode、stale policy 和 member order。Hosted `.apk` 只在精确验证的 Nexus 3.94 datastore shape 能证明规范 path、typed attribute、size、identity 与 SHA-256 时恢复；生成 index 会被过滤并重建。Private signing key 与 masked proxy secret 不会被伪造，相关 target 保持 offline 并报告 `NEEDS_MANUAL_ACTION`，直到管理员显式修复。
- R repository definition 会保留 hosted/proxy/group 配置、TTL、remote 设置和 member order。Hosted source package 只在精确验证的 Nexus 3.94 datastore shape 能证明规范 `src/contrib` path、typed package/version identity、size 与 checksum 时恢复；源端生成的 `PACKAGES.gz`、proxy projection、group binding 和 lease 会被过滤并重建。未知版本、shape 或不可验证内容 fail closed 并报告 `NEEDS_MANUAL_ACTION`。
- Hugging Face repository definition 会保留 proxy remote/TTL/outbound 配置与可恢复 bearer credential。显式选择的 proxy content 只有在精确验证的 Nexus 3.94 datastore shape 能证明 model repo/commit/path/checksum 时恢复；生成 API metadata、route projection 与 lease 在目标端重建。Masked credential、无法绑定 commit 的 mutable alias、损坏 blob 与未知 shape 都失败关闭。
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
- Conda 支持 classic repodata、BZ2/ZSTD 与 current-repodata alias。`current_repodata.json*` 当前返回完整兼容 snapshot，不是 conda-index 裁剪后的子集；暂不暴露 CEP 16 sharded repodata 和 JLAP。Conda 没有原生 publish 命令，hosted 发布使用 Nexus 兼容 PUT 或 UI/API 上传。
- APT 通过 hosted/proxy 支持二进制 `.deb` 仓库，不暴露 Nexus 不支持的 APT group。Hosted source package、flat hosted、PDiff、生成式 Contents/Translation 和 `.udeb` index 不在当前支持边界；proxy passthrough 可原样提供这些上游路径，本地 re-sign metadata 只声明已校验并缓存的二进制 package。配置和运维语义见 [APT / Debian 仓库使用指南](repository-guides/apt-debian.md)。
- Conan 当前支持 Conan 2 hosted/proxy/group 工作流和 Nexus 可见的 v2 API。Conan 1 endpoint/revision、发布 archive 之外的 recipe export source、任意 remote federation 和部分 revision 发布不在支持面。有效 manifest 是提交边界；cleanup 按完整 recipe/package revision 删除，扫描会把 package archive 与对应的 `conaninfo.txt` 一起编目。详见 [Conan 仓库使用指南](repository-guides/conan-2.md)。
- Alpine 当前支持 APK v2 hosted/proxy/group 工作流。`Packages.adb`、APK v3 package container、DSA signing key、unsigned hosted/group index 和 private-key 下载不在支持面。Nexus 3.94 的 unsigned-upload Q1/architecture 投影差异在与 apk-tools 完整性校验冲突时不会复制。详见 [Alpine / APK 仓库使用指南](repository-guides/alpine-apk.md)。
- R 当前支持 source `.tar.gz` hosted/proxy/group 工作流。Hosted/group 的 Windows `.zip`、macOS `.tgz`、`PACKAGES.rds`、自动 Archive alias、package build/check 和 CRAN submission 不在支持面；这些静态路径仍可由 proxy 直连。详见 [R / CRAN 仓库使用指南](repository-guides/r-cran.md)。
- Hugging Face 当前只支持 Models proxy。Hosted/group、Datasets、Spaces、Kernels、Buckets、推理 API、Git push、LFS/Xet upload 与 Hub 社区/Web API 不在当前支持面；客户端不会收到上游 CDN/signed/Xet 路由。详见 [Hugging Face Models 仓库使用指南](repository-guides/hugging-face-models.md)。
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
