# FAQ

## kkrepo 是什么？

kkrepo 是一个兼容 Nexus 的自托管制品仓库，面向 Maven、npm、PyPI、Go、Helm、Cargo/Rust、Dart/Pub、Composer/PHP、Terraform、Docker/OCI、NuGet、RubyGems、Yum 和 Raw 等常见包格式。

它保持 Nexus 风格客户端 URL、协议行为、权限和迁移目标，同时使用 MySQL 存储元数据，用 OSS/S3 兼容存储保存 blob。

## kkrepo 是 Sonatype Nexus 的 fork 吗？

不是。kkrepo 是独立实现。Nexus 是客户端可见行为的兼容性参考，但 kkrepo 不复制 Nexus 内部机制，例如 OrientDB、内嵌 Elasticsearch、Karaf、OSGi 或 Nexus task 子系统。

## 它能完整替代 Nexus 吗？

取决于你的使用场景。

kkrepo 面向需要 Nexus 兼容客户端路径、常见仓库格式、MySQL 元数据、对象存储、多副本友好行为，以及从现有 Nexus 迁移的团队。

它不是每一个 Nexus 功能或每一个 Nexus UI/API endpoint 的完整克隆。生产迁移前请先查看 [兼容性矩阵](compatibility-matrix.md)。

## 支持哪些仓库格式？

当前支持：

- Maven
- npm
- PyPI
- Go
- Helm
- Cargo / Rust
- Dart / Pub
- Composer / PHP
- Terraform Provider / Module Registry
- Docker / OCI
- NuGet
- RubyGems
- Yum
- Raw

## 客户端 URL 是否保持一致？

对已支持的非 Docker 格式，主要客户端 URL 形态与 Nexus 兼容：

```text
/repository/<repo>/<artifact-path>
```

这有助于在对应格式已被迁移流程覆盖时保留 Maven、npm、pip、Helm、Cargo、Dart/Flutter Pub、Composer、Terraform、NuGet、RubyGems、Yum、Raw 和 CI 客户端配置。

Docker / OCI 使用 Registry HTTP API V2 的 `/v2/...` 路由，不走 `/repository/<repo>/...`：共享入口部署使用 `<host>/<repo>/<image>:<tag>`，配置仓库级 connector port 后可使用 `<host>:<repo-port>/<image>:<tag>`。

## kkrepo 可以使用哪些数据库？

kkrepo 支持 MySQL 8 和 PostgreSQL 12+；已有安装默认仍为 MySQL。PostgreSQL 12 是兼容性下限，生产应使用仍在维护期的 PostgreSQL 版本。所选数据库是以下数据的事实来源：

- 仓库元数据。
- Component 和 asset。
- 用户、角色、权限、session、API key 和审计日志。
- 迁移状态。
- 跨副本协调状态。

这样可以避免生产正确性依赖内嵌数据库或只存在本地的状态。

两种后端使用同一个可执行文件/容器镜像，通过 `KKREPO_DATABASE_TYPE` 选择。不要只改 JDBC URL 就切换已有安装的数据库引擎，详见[数据库后端](database-backends.md)。

## kkrepo 必须依赖 Redis 吗？

不需要。默认 cache backend 是进程本地内存，正确性由共享数据库支撑。进程内 cache 是可重建热缓存，具备 TTL 或数据库支撑的失效水位。

## 制品文件存在哪里？

制品字节存储在 blob storage：

- 生产环境建议使用 OSS/S3 兼容存储。
- File storage 用于本地试用、测试和经过谨慎管理的共享文件系统部署。

MySQL 存储元数据和引用，不存储大制品字节。

## 生产环境可以使用 File blob storage 吗？

生产环境推荐使用 OSS/S3 兼容存储。

只有当所有副本挂载同一个强一致共享文件系统，并且显式启用生产 File 存储时，File blob storage 才适合生产。普通生产部署请使用 OSS/S3。

## kkrepo 支持高可用吗？

kkrepo 按多副本部署设计：

- Session 使用 Spring Session JDBC。
- 短生命周期认证 ticket 存在 MySQL。
- 迁移状态和后台 worker 协调状态存在 MySQL。
- 节点本地 cache 可重建。
- Blob 内容存在共享对象存储。

你仍然需要可靠 MySQL、共享 blob storage、负载均衡、监控和备份。

## 如何从 Nexus 迁移？

使用 `/admin/` 控制台：

1. 执行 `Nexus Metadata` preflight。
2. 执行元数据迁移。
3. 执行 `Nexus Repository Data` metadata sync。
4. 执行 package sync。
5. 切换前重复增量同步。
6. 将流量切到 kkrepo。

详见 [Nexus 迁移说明](nexus-migration-guide.md) 和 [迁移实战手册](migration-playbook.md)。

## 迁移后用户需要修改客户端配置吗？

如果把原 Nexus 域名切到 kkrepo，并保持 repository name 不变，大多数已支持的非 Docker 客户端可以继续使用相同 `/repository/<repo>/...` URL。

如果域名或 repository name 改变，则客户端需要更新配置。

## 迁移会复制 proxy 仓库吗？

Hosted 仓库默认扫描。Proxy 仓库可以显式指定迁移，用于保留历史缓存或上游备份数据。否则，proxy 仓库可以在切换后重新从上游回源。

## 用户密码和 API key 迁移会怎样？

当源 Nexus 暴露足够信息时，迁移会尽力保留兼容安全数据。部分 local 用户如果无法补偿密码 hash，可能需要重置密码。API key 或协议 token 可能因源数据可用性和安全策略需要重新签发。

切换前务必执行 preflight，并审查迁移报告。

## Docker / OCI 支持了吗？

Docker / OCI Registry 的 hosted、proxy、group 仓库已实现 Registry HTTP API V2 客户端工作流。请使用 Docker 的 `/v2/...` 路由：共享入口部署使用 `<host>/<repo>/<image>:<tag>`，配置仓库级 connector port 后也可以使用 `<host>:<repo-port>/<image>:<tag>`。

Docker hosted 仓库迁移走 Nexus Repository Data 流程。Docker Registry V1 API 和 `docker search` 不属于当前支持面；现代 Docker/OCI 工作流使用 Registry V2 和 OCI Distribution。

不要假设 Docker pull/push 可以通过 `/repository/<repo>/...` 工作。

## Cargo / Rust 支持了吗？

Cargo hosted、proxy、group 仓库已支持 Cargo sparse registry 协议。kkrepo 支持 `cargo publish`、fetch/download、yank/unyank、`cargo search`、`CargoToken` 认证，以及 hosted `.crate` 的 UI/API 上传。

对于 preflight 已确认 Cargo content model 的 datastore 时代 H2/PostgreSQL 源端，Nexus Cargo hosted 仓库迁移已支持。未知 schema 指纹会在迁移计划中 fail closed，OrientDB 源端不会启用 Cargo 内容导出。

## Dart / Pub 支持了吗？

Dart / Pub hosted、proxy、group 仓库已支持 Hosted Pub Repository V2 协议。kkrepo 支持 `dart pub publish`、`dart pub get`、`flutter pub get`、`PubToken` bearer 认证、package/version metadata、archive 下载、Pub 搜索，以及 hosted `.tar.gz` 的 UI/API 上传。

对于 preflight 已确认 Pub content model 的 Nexus 3.92.0 源端，Nexus Pub hosted 仓库迁移已支持。显式选择 Pub proxy cache 且 migration plan 为 `FULL` 时也支持 proxy 缓存迁移；否则 proxy 仓库在切换后从上游自然回填。

## Composer / PHP 支持了吗？

Composer hosted、proxy、group 仓库已支持 Composer 2 repository metadata。kkrepo 支持 `packages.json`、stable/dev p2 metadata、Nexus 风格 `vendor/package/version/*.zip` dist path、HTTP Basic、group canonical first-match、Components API/UI archive 上传、Browse/Search、HTML View 和 Usage。

Composer 本身没有标准 publish 命令，因此 hosted 包通过 Components API 或 UI 上传包含 `composer.json` 的 zip/tar archive。Nexus 原生 Composer 只有 proxy；迁移配置时保持 proxy 语义，cache 只有在管理员显式选择源仓库且 preflight 证明 Composer content model 后才迁移。

## Terraform Provider / Module Registry 支持了吗？

已支持。Terraform hosted、proxy、group 仓库实现 Module Registry 与 Provider Registry 协议，使用 Nexus 兼容的 `/repository/<repo>/v1/modules/...` 和 `/v1/providers/...` 路径。kkRepo 支持 module/provider 上传、version/platform、registry.terraform.io proxy、group 解析、URL token 认证、Provider SHA256SUMS、hosted detached GPG 签名、Browse/Search/Usage，并使用 Terraform 0.13 与当前稳定版执行真实 `terraform init` 验证。

Terraform CLI 需要通过 `host.services` 把 `modules.v1`、`providers.v1` 指向目标 group。根域 discovery 与 Provider Network Mirror Protocol 暂不在公开支持面。Nexus Terraform hosted 数据及 proxy/group 配置可迁移。Terraform proxy cache 数据当前会在创建 job 前被拒绝；目标 proxy 从已配置上游重建经过校验的 cache。

## kkrepo 可以用于生产吗？

kkrepo 目前是早期开源软件，并已有首个公开版本。它已经包含重要的生产架构选择，但每个部署都应该自行验证：

- 所需仓库格式。
- 客户端兼容性。
- 迁移行为。
- 备份恢复。
- 监控。
- 安全模型。
- 负载和对象存储吞吐。

对外承载生产流量前，请先阅读 [生产加固指南](production-hardening.md)。

## 如何报告 bug？

使用 GitHub issues，并选择最接近的 issue template。请提供：

- kkrepo 版本或 commit。
- 部署模式。
- 仓库格式和类型。
- 客户端命令或 HTTP 请求。
- 预期行为和实际行为。
- 脱敏日志。

如果是 Nexus 行为差异，请使用 compatibility issue template，并提供 Nexus 和 kkrepo 对同一请求的响应。

## 如何报告安全问题？

不要为可利用漏洞创建公开 issue。

请按 [SECURITY.md](../../SECURITY.md) 通过 GitHub Security Advisories 私下报告。

## kkrepo 使用什么许可证？

kkrepo 使用 [Apache License 2.0](../../LICENSE)。

## 去哪里提问？

可使用：

- GitHub issues：报告 bug、兼容差异、功能建议和文档问题。
- [kkrepo Telegram 群](https://t.me/+UbIsTKXTzxBhYjFl)：社区讨论。
- GitHub Security Advisories：报告可利用安全问题。

详见 [SUPPORT.md](../../SUPPORT.md)。
