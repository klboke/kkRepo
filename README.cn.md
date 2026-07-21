# kkRepo

[![CI](https://github.com/klboke/kkrepo/actions/workflows/ci.yml/badge.svg)](https://github.com/klboke/kkrepo/actions/workflows/ci.yml)
[![codecov](https://codecov.io/gh/klboke/kkRepo/branch/main/graph/badge.svg)](https://codecov.io/gh/klboke/kkRepo)
[![Release](https://img.shields.io/github/v/release/klboke/kkrepo)](https://github.com/klboke/kkrepo/releases)
[![License](https://img.shields.io/github/license/klboke/kkrepo)](LICENSE)
[![Container](https://img.shields.io/badge/ghcr.io-kkrepo-blue)](https://github.com/klboke/kkrepo/pkgs/container/kkrepo)
[![Security Policy](https://img.shields.io/badge/security-policy-green)](SECURITY.md)

[English](README.md) | **中文**

kkRepo 是一款社区驱动、完全开源的自托管制品仓库，旨在解决 Sonatype Nexus 社区版的各类限制与痛点，为社区提供开放、可靠且可持续演进的制品管理方案。目前已支持 Maven、npm、PyPI、Go、Helm、Cargo/Rust、Dart/Pub、Composer/PHP、Terraform、Swift Package Registry、Docker/OCI、NuGet、RubyGems、Yum 和 Raw 等制品格式。

## 功能特性

- 支持 15+ 种主流仓库格式，覆盖 hosted、proxy 和 group 仓库管理。
- 支持显式开启的 [Spring AOT / GraalVM Native Image 发行包](docs/zh/native-vs-jvm-guide.md)，容器约 1 秒即可就绪，并显著降低内存占用；默认仍使用 JVM 发行包。
- Proxy 仓库支持按仓库配置出站 HTTP 或 SOCKS5 网络代理，并支持可选的代理认证和 HTTPS 上游隧道。
- 兼容 Sonatype Nexus API 协议、用户权限模型、和 `/repository/<repo>/...` URL 布局。
- 可使用 kkRepo 平替 Sonatype Nexus，支持存量数据一键迁移并沿用原仓库域名和 URL，原有客户端配置与 CI 工作流无需改动。
- 支持完整的身份与访问控制，覆盖 Local、LDAP、OIDC 认证、匿名访问策略和细粒度权限管理。
- 支持完整的可观测性，包括 Prometheus 指标导出、提供 Grafana 面板。
- 使用 MySQL 或 PostgreSQL 存储元数据和共享运行状态；默认仍为 MySQL。
- 支持 OSS/S3/File 存储制品 blob。
- 支持多副本高可用部署。

<p align="center">
  <img src="docs/assets/kkrepo-project-map.svg" alt="kkRepo 架构图：支持的仓库格式、协议入口、服务副本、存储、UI，以及 Nexus 迁移" width="100%">
</p>

## 商标声明

Sonatype、Nexus 和 Nexus Repository 是 Sonatype, Inc. 的商标。kkRepo 是独立开源项目，不隶属于 Sonatype, Inc.，也未获得其认可、赞助或背书。文档中对 Sonatype Nexus Repository 的引用仅用于准确说明兼容性、迁移或互操作性。

## 快速开始

使用公开发行镜像和 MySQL 在本地启动一个试用环境：

```bash
curl -fsSL https://raw.githubusercontent.com/klboke/kkrepo/main/scripts/quickstart.sh | bash
```

使用同一镜像和默认 PostgreSQL 16 quickstart 启动（运行时兼容 PostgreSQL 12+）：

```bash
curl -fsSL https://raw.githubusercontent.com/klboke/kkrepo/main/scripts/quickstart.sh | KKREPO_DATABASE_TYPE=postgresql bash
```

启动后访问：

- 管理控制台：`http://127.0.0.1:19090/admin/`
- 用户侧浏览器：`http://127.0.0.1:19090/browse/`
- 健康检查：`http://127.0.0.1:19091/actuator/health`

首次进入页面时，在 UI 中创建初始 `Local/admin` 管理员密码。quickstart 使用 File blob storage 作为本地试用存储；生产环境请改用 OSS/S3，并替换为自己的加密密钥。

如果希望先检查脚本内容，可以先下载 `scripts/quickstart.sh`，确认后再用 `bash` 执行。

## 构建和部署

本地快速启动、Spring Boot 可执行 jar、Docker 镜像、压缩包、生产部署架构、资源规格和升级流程见 [构建部署指南](docs/zh/build-deployment-guide.md)。

默认 JVM 与显式开启的 Native Image 在启动、内存、镜像体积和预热后吞吐方面的实测差异见 [Native Image 与 JVM 选型指南](docs/zh/native-vs-jvm-guide.md)。

如果 kkRepo 部署在 Nginx 或其他 HTTPS 反向代理后面，请参考 [Nginx 反向代理配置注意事项](docs/zh/nginx-reverse-proxy.md)，确保 npm `dist.tarball` 等生成的仓库 URL 保持公网 `https://` scheme 和 host。

本地开发热重载和测试说明见 [中文开发指南](docs/zh/development-guide.md)。

## 支持能力

| 格式 | 仓库类型 | 客户端发布/上传 | 浏览和搜索 | Nexus 迁移 |
| --- | --- | --- | --- | --- |
| Maven | hosted / proxy / group | 支持 Maven deploy、PUT 上传和管理台上传 | 支持 | 默认迁移 hosted；proxy 可作为可选仓库迁移 |
| npm | hosted / proxy / group | 支持 `npm publish`、dist-tag 和管理台上传 | 支持 | 默认迁移 hosted；proxy 可作为可选仓库迁移 |
| PyPI | hosted / proxy / group | 支持 twine 上传和管理台上传 | 支持 simple index | 默认迁移 hosted；proxy 可作为可选仓库迁移 |
| Go | proxy / group | Go module proxy 以只读代理为主，不支持 hosted 上传 | 支持 | proxy 可作为可选仓库迁移 |
| Helm | hosted / proxy | 支持 chart push、PUT 上传和管理台上传 | 支持 index.yaml | 默认迁移 hosted；proxy 可作为可选仓库迁移 |
| Cargo / Rust | hosted / proxy / group | 支持 `cargo publish`、yank/unyank、`CargoToken` 认证和 UI/API `.crate` 上传 | 支持 sparse index 和 `cargo search` | 支持 Cargo 仓库迁移 |
| Dart / Pub | hosted / proxy / group | 支持 `dart pub publish`、`dart pub get`、`flutter pub get`、`PubToken` 认证和 UI/API `.tar.gz` 上传 | 支持 package/version metadata、archive 属性和 Pub 搜索 | 支持 Nexus 3.92.0 Pub hosted 迁移，以及显式选择的 proxy cache 迁移 |
| Composer / PHP | hosted / proxy / group | Composer 没有标准 publish 命令；支持 Components API 和 UI 上传 zip/tar archive，并通过 Composer 2 安装 | 支持 package/version、dist、HTML View、Browse/Search 和 Usage | Nexus 原生 Composer proxy 配置可迁移；cache 仅在管理员显式选择且 source profile 证明后迁移 |
| Terraform Provider / Module Registry | hosted / proxy / group | 支持 Nexus 兼容 PUT 和 UI/API archive 上传；`terraform init` 可通过 group 解析 hosted 与 proxy module/provider | 支持 module/provider coordinate、version、platform、Browse/Search 和 Usage | 支持 Nexus Terraform hosted 数据和显式选择的 proxy archive cache 迁移；同时迁移 proxy/group 配置 |
| Swift Package Registry | hosted / proxy / group | 支持 `swift package-registry publish`、Basic/Bearer 登录和 UI/API source archive 上传 | 支持 Registry v1 release/manifest/archive 元数据、Browse/Search 和 Usage | 仅已验证 Nexus 3.92.x-3.94.x datastore shape 可规划为 `FULL`；shape 漂移或 proxy secret 不可用时需人工处理 |
| Docker / OCI | hosted / proxy / group | 支持 Registry V2 login、hosted push/pull、proxy pull、group pull、OCI referrers、cleanup 和 connector port 访问 | 支持 manifest/tag/blob metadata | Docker hosted 仓库数据迁移走 Nexus Repository Data 流程 |
| NuGet | hosted / proxy / group | 支持 package push 和管理台上传 | 支持 v3 service index / search | 默认迁移 hosted；proxy 可作为可选仓库迁移 |
| RubyGems | hosted / proxy / group | 支持 gem push/yank 和管理台上传 | 支持 | 默认迁移 hosted；proxy 可作为可选仓库迁移 |
| Yum | hosted / proxy / group | 支持 RPM 上传和管理台上传 | 支持 repodata | 默认迁移 hosted；proxy 可作为可选仓库迁移 |
| Raw | hosted / proxy / group | 支持 PUT 上传和管理台上传 | 支持 | 默认迁移 hosted；proxy 可作为可选仓库迁移 |

仓库数据迁移默认扫描 hosted 仓库；如需迁移源 Sonatype Nexus Repository 部署中作为历史备份或回源缓存使用的 proxy 仓库，可以在迁移页面的 `Optional proxy repositories` 中显式指定仓库名。Cargo / Rust、Dart / Pub、Nexus 原生 Composer 和 Terraform proxy cache 在显式选择且 source profile 接受时可迁移。Swift hosted archive、manifest、签名、metadata 和 repository URL mapping 仅对 Nexus 3.92.x-3.94.x 且 datastore fingerprint 能证明预期 Swift content model 的源恢复；版本超出范围、shape 漂移或未知 profile 均 fail closed 并生成 manual action。如果 Swift proxy 的源端 secret 被遮蔽或缺失，目标 proxy 会以 offline 状态创建且不写入占位 credential，需管理员显式补齐。Terraform 使用独立 proxy-cache writer 恢复 module/provider archive、保留 Nexus 公开 asset path，且不会把 cache asset 当作 hosted publication 重放。已恢复的 module archive 可不回源直接完成 module download discovery；Provider route 从已配置上游重建并校验 checksum/signature snapshot，其缓存 blob 在 metadata 有效期内保持固定。

## 从 Sonatype Nexus Repository 迁移

迁移入口在管理控制台 `/admin/`：

1. 在源 Sonatype Nexus Repository 部署中开启 Script REST API 脚本创建能力。
2. 在 `Nexus Metadata` 页面先执行 `Run preflight`，确认无阻塞问题后执行 `Run migration`。
3. 在 `Nexus Repository Data` 页面先执行 `Sync metadata` 迁移仓库元数据，再执行 `Sync packages` 迁移 blob 真实数据。
4. 首次仓库数据迁移时 `Metadata since` 保持为空，扫描全量数据；后续迁移可以指定 `Metadata since` 做增量。
5. 迁移完成后，把原制品仓库域名指向 kkRepo。非 Docker 客户端可以继续使用相同 `/repository/<repo>/...` URL；Docker 客户端应保持相同 `/v2/...` registry 入口、仓库名和 connector/path-based routing 形态。

迁移支持中断后继续，已迁移完成的数据会跳过。完整流程见 [Nexus 迁移说明](docs/zh/nexus-migration-guide.md)。

## 兼容与迁移背景

| 维度 | Sonatype Nexus Repository OSS / Community Edition | kkRepo                                                                                                        |
| --- | --- |-------------------------------------------------------------------------------------------------------------------|
| 产品定位 | 通用制品仓库管理平台，功能完整，覆盖大量官方格式和管理能力 | 提供面向迁移的客户端行为、权限模型和 `/repository/<repo>/...` URL 布局兼容，同时采用共享关系数据库、OSS/S3-first、适合多副本部署的架构 |
| 支持格式 | 官方支持格式更多，具体能力随版本和发行形态变化 | 聚焦常用制品格式，当前支持 Maven、npm、PyPI、Go、Helm、Cargo/Rust、Dart/Pub、Composer/PHP、Terraform、Swift Package Registry、Docker/OCI、NuGet、RubyGems、Yum 和 Raw；每个格式以独立 protocol 模块实现，便于按优先级扩展和验证                       |
| 使用限制 | Community Edition 面向个人和小团队，官方限制为最多 40,000 components、100,000 requests/day；超过阈值后会暂停新增 component，直到用量回到限制以下 | 不内置 Community Edition 这类版本授权用量限制；容量边界由所选关系数据库、OSS/S3、运行副本数和部署规格决定，适合按实际业务规模扩容 |
| 高可用部署 | 开源版适合单实例或基础 Kubernetes 部署；官方 HA deployment 属于 Pro 能力 | 从设计上默认支持多副本：session、认证 ticket、catalog 水位、锁、迁移进度和短生命周期协同状态都落 MySQL 或 PostgreSQL，进程内缓存只作为可重建热缓存 |
| 稳定性和升级 | 版本边界复杂：3.70.x 是最后支持 OrientDB 的版本；3.71.0 起新安装默认 H2，但 H2 仍是内嵌数据库；Community Edition 到 3.77.0+ 才支持免费使用外部 PostgreSQL；3.88.0 起搜索才完全改为 SQL、替代 Elasticsearch。旧版 OrientDB/Elasticsearch/本地数据目录组合升级窗口重，文件损坏后恢复高度依赖备份、修复任务和人工介入 | 运行时支持 MySQL/PostgreSQL，不依赖 OrientDB 和内嵌 Elasticsearch；核心状态在共享关系数据库，blob 在 OSS/S3/File blob store，缓存和索引均可重建，更适合滚动升级、故障切换和数据恢复 |
| 元数据存储 | 版本演进中经历 OrientDB、H2、PostgreSQL 等存储迁移路径，历史实例升级需要处理数据库迁移约束 | repository、component、asset、权限、token、审计、迁移状态和可重建索引在 MySQL 或 PostgreSQL 中使用显式表结构，便于排查、治理和横向扩展 |
| blob 存储 | 常见部署使用本地文件 blob store，也可按版本和配置使用对象存储能力 | OSS/S3-first，同时保留 File blob store 用于开发测试；关系数据库只保存元数据、状态、索引和引用，不把大 blob 放进数据库 |
| 搜索和索引 | 3.88.0 之前的 Nexus 搜索和索引基于内嵌 Elasticsearch，索引文件和数据库状态分离；索引损坏或不一致时需要依赖 Nexus 修复/重建任务处理 | 使用关系数据库反范式索引和协议派生元数据，browse/search/index 都按可重建数据设计，节点丢缓存不影响正确性                                              |
| 架构复杂度 | Nexus Repository 功能复杂，覆盖大量通用管理能力和历史架构机制 | kkRepo 架构简单，只聚焦仓库管理和客户端协议实现                                                                                   |

## 选型建议

- 如果公司业务量非常小，仓库包数量和访问量都在 Community Edition 限制内，并且可以接受偶尔停机维护，Sonatype Nexus Repository 开源版本可能已经足够。
- 如果对稳定性、扩展性和多副本部署要求较高，或者需要管理的包数量非常多，kkRepo 面向这类部署形态设计。
- 如果存量 Sonatype Nexus Repository 实例在升级到新的 Community Edition 版本时遇到组件数量或日请求量限制问题，kkRepo 提供面向 0 停机迁移的一键迁移流程。

## UI 展示

### 前台

前台面向制品使用者，提供仓库列表、包搜索、目录浏览、制品详情和上传入口。

仓库列表展示 hosted、proxy、group 仓库的格式、状态和访问 URL，便于用户直接复制客户端配置地址。

![前台仓库列表](docs/img/img_7.png)

按格式搜索组件，支持 Maven、npm、PyPI、Go、Helm、Cargo/Rust、Dart/Pub、Composer/PHP、Terraform、Swift Package Registry、Docker/OCI、NuGet、RubyGems、Yum 和 Raw 等仓库类型的制品检索。

![前台制品搜索](docs/img/img.png)

目录浏览展示仓库路径树、制品摘要、checksum、content-type、更新时间和客户端使用片段。

![前台目录浏览和制品详情](docs/img/img_1.png)

上传页面提供按仓库选择文件和 asset path 的入口，用于 hosted 仓库的手工制品发布。

![前台制品上传](docs/img/img_2.png)

### 后台

后台面向仓库管理员，聚焦仓库配置、存储健康、安全配置、审计和迁移。

Blob Store 页面支持 OSS Native SDK、AWS S3 SDK 和 File 引擎配置，并展示读写探测健康状态。

![后台 Blob Store 管理](docs/img/img_4.png)

OIDC 页面管理 issuer、JWKS、client、scope、claim 映射和 token 校验参数，便于接入统一身份系统。

![后台 OIDC 配置](docs/img/img_3.png)

Nexus Metadata 迁移入口用于迁移用户、角色、权限、blob store 和 repository 定义，并支持 preflight。

![后台 Nexus 元数据迁移](docs/img/img_5.png)

Nexus Repository Data 迁移页面展示 hosted 仓库数据迁移任务、并发参数、进度统计、失败数量和仓库级明细。

![后台 Nexus 仓库数据迁移](docs/img/img_6.png)

AI agent 和贡献者的开发说明见 [AGENTS.md](AGENTS.md)。

## 路线图

基础设施路线图：

1. ✅ PostgreSQL 数据库后端支持 - 已实现。通过 `persistence-jdbc` 公共契约、语义化 dialect SPI、backend 自有 Flyway migration、双库 contract test 和多副本 server smoke test 隔离并验证差异；MySQL 继续作为默认后端（[数据库后端指南](docs/zh/database-backends.md)、[设计方案](docs/zh/dev/pluggable-database-access-layer-design.md)）。

仓库格式路线图：

1. ✅ Docker / OCI Registry - 已完成（[实现说明](docs/zh/dev/docker-repository-implementation-plan.md)）
2. ✅ Cargo / Rust - 仓库能力已完成，包含搜索、UI/API 上传和迁移能力（[设计说明](docs/zh/dev/cargo-rust-repository-design.md)）
3. ✅ Dart / Pub - 仓库能力已完成，包含 hosted/proxy/group、真实客户端 E2E、UI/API 上传、搜索和 Nexus 迁移能力（[设计说明](docs/zh/dev/dart-pub-repository-design.md)）
4. ✅ Composer / PHP - hosted、proxy、group、UI/API 上传、搜索、真实客户端 E2E、强制 Nexus live 对比和显式选择的 Nexus proxy cache 迁移 E2E 已实现（[设计说明](docs/zh/dev/composer-php-repository-design.md)）
5. ✅ Terraform Provider / Module Registry - hosted、proxy、group、Provider GPG 签名、Nexus 路径兼容、UI/API 上传、搜索、真实 Terraform CLI E2E、Nexus hosted 数据迁移和显式选择的 proxy cache 迁移已实现（[设计说明](docs/zh/dev/terraform-repository-design.md)）
6. ✅ Swift Package Registry - hosted、GitHub-backed proxy、group、Registry v1、不可变签名发布、UI/API 上传、Browse/Search、多副本协同、真实 SwiftPM/Xcode E2E 和 shape-gated Nexus 3.92.x-3.94.x 迁移已实现（[设计说明](docs/zh/dev/swift-package-registry-design.md)）
7. ohpm / HarmonyOS - 规划中，覆盖 hosted、proxy、group、导入和管理端能力（[设计说明](docs/zh/dev/ohpm-repository-design.md)）
8. Ansible Galaxy
9. APT / Debian
10. Conan
11. Conda

用户和管理端 UI 已暴露的 token 类型包括协议专用 token（`NpmToken`、`CargoToken`、`PubToken`、`NuGetApiKey`、`RubyGemsApiKey`），以及面向 Terraform 服务 URL、CI、脚本和自定义 HTTP 客户端的 `GenericToken`；`GenericToken` 适用于能够发送已配置 API-key header 或 bearer token 的调用方。

## 参与贡献

欢迎提交 issue 和 pull request。贡献流程、PR 要求、兼容性测试要求和多副本设计约束见 [CONTRIBUTING.md](CONTRIBUTING.md)。社区行为准则见 [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md)。

本地开发和测试说明见 [中文开发指南](docs/zh/development-guide.md)；构建部署说明见 [构建部署指南](docs/zh/build-deployment-guide.md)；AI agent 和贡献者约束见 [AGENTS.md](AGENTS.md)。

## 支持

加入 [kkRepo Telegram 群](https://t.me/+UbIsTKXTzxBhYjFl) 获取社区支持和使用交流。Issue 分类、支持范围和安全问题报告边界见 [SUPPORT.md](SUPPORT.md)。

## 安全

如果发现安全问题，请按 [SECURITY.md](SECURITY.md) 说明优先通过 GitHub Security Advisory 报告，避免在公开 issue 中直接披露可利用细节。普通 bug、兼容性问题和功能建议可以直接提交 issue。

## 许可证

kkRepo 使用 [Apache License 2.0](LICENSE) 开源。

## 文档

- [中文开发指南](docs/zh/development-guide.md)
- [构建部署指南](docs/zh/build-deployment-guide.md)
- [Native Image 与 JVM 选型指南](docs/zh/native-vs-jvm-guide.md)
- [Nginx 反向代理配置注意事项](docs/zh/nginx-reverse-proxy.md)
- [客户端配置示例](docs/zh/client-recipes.md)
- [架构说明](docs/zh/architecture.md)
- [兼容性矩阵](docs/zh/compatibility-matrix.md)
- [排障指南](docs/zh/troubleshooting.md)
- [生产加固指南](docs/zh/production-hardening.md)
- [备份恢复指南](docs/zh/backup-restore.md)
- [安全模型](docs/zh/security-model.md)
- [MySQL ER 设计](docs/zh/mysql-er.md)
- [数据库后端指南](docs/zh/database-backends.md)
- [数据库 Schema](docs/zh/database-schema.md)
- [Nexus 迁移说明](docs/zh/nexus-migration-guide.md)
- [Nexus 迁移实战手册](docs/zh/migration-playbook.md)
- [监控观测指南](docs/zh/monitoring-observability-guide.md)
- [Nexus 兼容性测试说明](docs/zh/nexus-compatibility-testing.md)
- [FAQ](docs/zh/faq.md)
- [为什么开发 kkRepo](docs/zh/why-kkrepo.md)
- [Changelog](CHANGELOG.md)
