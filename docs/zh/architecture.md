# 架构说明

kkrepo 是一个兼容 Nexus 的制品仓库，核心设计是 MySQL-first 元数据、OSS/S3-first blob 存储，以及适合多副本部署的运行时行为。

项目目标不是复制 Nexus 内部实现。kkrepo 保持客户端可见协议、权限模型和 `/repository/<repo>/...` URL 兼容，同时避免在生产运行时依赖 OrientDB、内嵌 Elasticsearch、Karaf、OSGi 和只能本地持久化的 blob 状态。

## 整体拓扑

```text
制品客户端
  Maven / npm / PyPI / Go / Helm / Cargo/Rust / Docker/OCI / NuGet / RubyGems / Yum / Raw
        |
        v
负载均衡 / 反向代理
        |
        v
kkrepo 多副本
  server
  protocol modules
  admin-ui / browse-ui 静态资源
        |
        | 元数据、用户、权限、session、锁、迁移状态
        v
      MySQL
        |
        | blob 引用、checksum、object key
        v
  OSS / S3 / File blob storage
```

每个副本都可以服务仓库流量。正确性不能依赖某一个 JVM 独占进程内状态。

## 请求流

仓库客户端请求使用 Nexus 兼容路径：

```text
/repository/<repo>/<artifact-path>
```

Docker / OCI 客户端使用 Registry HTTP API V2 路由：

```text
/v2/<repo>/<image>/...
/v2/<image>/...
```

第二种形态用于仓库级 Docker connector port，本地监听端口会映射到一个 Docker 仓库。

服务端流程：

1. `RepositoryContentController` 从 MySQL 支撑的仓库元数据解析 `<repo>`。
2. 安全过滤器完成 subject 认证并校验仓库权限。
3. 服务端根据仓库 format 和 type 分发到协议实现。
4. hosted 仓库通过 MySQL 事务和 blob storage 读写 asset 与 metadata。
5. proxy 仓库拉取上游内容，持久化可缓存 asset，并在安全条件下使用 negative cache。
6. group 仓库按配置顺序解析 member，并缓存可重建的 member 命中结果。
7. 响应从 blob storage 流式返回，或返回生成的 metadata asset。

Admin UI 和 Browse UI 由 Spring Boot 服务提供静态资源：

- 管理控制台：`/admin/`
- 用户侧浏览器：`/browse/`
- 健康检查和指标：管理端口，通常是 `8081`

## 模块边界

| 模块 | 职责 |
| --- | --- |
| `core` | Repository、component、asset、blob、协议抽象和共享鉴权契约 |
| `storage-s3` | OSS/S3 兼容 blob 存储实现 |
| `storage-file` | 本地/测试 File blob 存储实现 |
| `cache` | 可重建的进程内 TTL cache 抽象 |
| `protocol-maven` | Maven 路径、metadata、layout、policy 辅助逻辑 |
| `protocol-npm` | npm 路径和 metadata 辅助逻辑 |
| `protocol-pypi` | PyPI 协议辅助逻辑 |
| `protocol-go` | Go module proxy 辅助逻辑 |
| `protocol-helm` | Helm chart 和 index 辅助逻辑 |
| `protocol-cargo` | Cargo sparse registry 路径、index、publish body、checksum 和错误模型辅助逻辑 |
| `protocol-docker` | Docker Registry V2 / OCI 路径、digest、manifest、media type 和错误模型辅助逻辑 |
| `protocol-nuget` | NuGet 路径辅助逻辑 |
| `protocol-rubygems` | RubyGems metadata 辅助逻辑 |
| `protocol-yum` | Yum/RPM metadata 辅助逻辑 |
| `persistence-mysql` | MySQL DAO、模型、JSON/枚举/hash 辅助逻辑 |
| `migration-nexus` | Nexus 元数据和安全数据迁移支持 |
| `server` | Spring Boot 运行时、controller、service、filter、worker |
| `admin-ui` | 管理控制台静态资源 |
| `browse-ui` | 用户侧仓库浏览器静态资源 |
| `compat-test` | 黑盒和协议兼容性测试 |

协议逻辑应位于 service 和 protocol 模块中，不应堆在 controller 里。

## 数据归属

MySQL 存储：

- 仓库定义和 group member。
- Component、asset、browse node 和 search index。
- Blob 引用、checksum、object key、size 和 content type。
- 用户、角色、权限、realm、API key、session 和审计日志。
- 迁移 job、迁移 asset、cursor、marker 和 checkpoint。
- Cache version 水位和跨副本协调状态。

Blob storage 存储：

- 制品字节内容。
- 缓存的上游制品。
- 生成或迁移的 blob object。

MySQL 只保存 blob 引用，不保存大 blob 内容。

## Blob 存储

生产部署建议使用 OSS/S3 兼容存储。File blob storage 用于本地试用、测试，以及经过谨慎管理的共享文件系统部署。

Blob 写入流程：

1. 将请求体或上游响应流写入临时位置。
2. 计算 checksum 和 size。
3. 在选定 blob store 中持久化或复用 blob object。
4. 在 MySQL 事务中写入 asset/blob metadata。
5. 清理临时文件或废弃 staging object。

只有内容安全写入后，blob metadata 和 object 引用才应事务性可见。

## 多副本模型

kkrepo 默认按多个应用副本设计：

- HTTP session 使用 Spring Session JDBC。
- 认证 ticket 存在 MySQL，生命周期很短。
- 仓库、安全和 blob-store catalog 变更会推进 MySQL 支撑的 cache 水位。
- 节点本地 cache 可重建，并具备 TTL 或显式失效规则。
- 后台 worker 使用 MySQL claim、marker 或 cursor，而不是只存在本地队列。
- 迁移进度和重试状态持久化在 MySQL。

如果某个副本重启或丢失本地 cache，正确性不应受影响。可接受的最坏情况是 cache 回暖期间多读一些数据库或对象存储。

## 缓存层

默认 cache backend 是进程内 memory。缓存只用于性能，不作为正确性来源：

- Asset metadata cache。
- Repository token/version local cache。
- Group member hit cache。
- npm group packument cache。
- PyPI group simple index cache。
- Basic-auth 结果 cache，key 使用密钥派生的 HMAC。
- Security authorization cache。
- Repository/security/blob-store metadata 的 catalog snapshot。

缓存失效依赖 TTL 和 MySQL version 水位。运维人员可以通过 `KKREPO_*` 环境变量调整 cache 大小和 TTL。

## 安全架构

认证来源包括：

- Local 用户。
- LDAP realm。
- OIDC realm。
- API key 和协议 token。
- HTTP session。

授权使用 Nexus 风格 repository privilege，动作包括 browse、read、add、edit、delete。安全敏感配置和用户可见 API-key payload 使用稳定部署密钥做静态加密。

详见 [安全模型](security-model.md)。

## 迁移架构

迁移分为两个主要入口：

- `Nexus Metadata`：源元数据 preflight 和迁移，包括用户、角色、权限、blob store 和 repository。
- `Nexus Repository Data`：仓库 asset 发现和 package/blob 迁移。

当普通 REST API 无法暴露必要数据时，元数据迁移可能使用源 Nexus Script REST API。仓库数据迁移先把源 asset 记录到 MySQL，再由 worker 迁移 blob，并支持重试和 checksum 校验。

详见 [迁移实战手册](migration-playbook.md)。

## 可观测性

管理端口暴露：

- `/actuator/health`
- `/actuator/metrics`
- `/actuator/prometheus`

仓库流量指标会尽量按 repository、format、method、status 和 operation family 打标签。详见 [监控观测指南](monitoring-observability-guide.md)。

## 不复制的 Nexus 内部机制

除非用户可见兼容性需要适配，kkrepo 不主动复制以下 Nexus 内部机制：

- OrientDB。
- 内嵌 Elasticsearch。
- Karaf 和 OSGi runtime model。
- 作为内部实现依赖的 Nexus task 子系统。
- 只能本地持久化的文件 blob 存储作为唯一生产 blob store。

兼容目标是客户端可见行为和迁移可用性，而不是内部实现一致。
