# kkrepo Agent 指南

本项目由 Infra 在 AI 辅助下实现。必须把 Nexus 当作兼容性参考，而不是凭记忆重新发明行为。

## 产品目标

构建一个兼容 Nexus 的自托管制品仓库，包含：

- Maven、npm、PyPI、Go、Helm、Cargo/Rust、Dart/Pub、Composer/PHP、Docker/OCI、NuGet、RubyGems、Yum 和 Raw 制品支持；具体 hosted/proxy/group 边界以兼容性矩阵为准。
- 面向用户、用户组、角色、仓库权限和 CI token 的完整权限管理。
- 兼容 Nexus 的客户端协议行为和 `/repository/<repo>/...` URL 布局。
- 使用 MySQL 存储元数据、用户数据、权限数据、token 数据、审计数据和迁移状态。
- 使用 MySQL 承载跨副本 session、短生命周期协同状态和版本水位；使用进程内 TTL cache 作为可重建的节点本地热缓存。
- 使用 OSS/S3 存储所有制品 blob。
- 运行时不依赖 OrientDB、内嵌 Elasticsearch，也不要求本地持久化 blob 文件系统。
- 用于迁移现有 Nexus 仓库、元数据、权限和 blob 的迁移工具。
- 用户侧迁移尽可能接近无感。

## 强制开发规则

任何功能在设计和实现时都必须考虑分布式多副本部署。涉及状态、缓存、锁、后台任务、session、上传/删除、索引重建、metadata、negative cache 或权限判定的逻辑，默认不能依赖单个 JVM 进程内状态作为唯一真相；应使用 MySQL、OSS/S3、共享 TTL cache、分布式锁、marker 队列或其它可协调机制。允许使用进程内缓存作为节点本地热缓存，但必须满足：丢失后可自动恢复、不影响正确性、有明确 TTL 或失效条件，并在代码或文档中说明多副本语义。

实现任何类型仓库功能前，必须先检查该仓库的官方协议，保持和官方仓库协议对齐 ，不要自行发明协议行为。

## 兼容性工作流

对每个协议功能：

1. 新增或更新面向 Nexus 参考实例运行的兼容性测试。
2. 在 `kkrepo` 中实现最小兼容行为。
3. 对比 HTTP 状态、相关 header、响应体语义、生成的元数据、checksum 和真实客户端行为。
4. 只有在协议允许时，才规范化 host、timestamp、排序或生成 ID 等非确定性值。

## 构建与测试注意事项

- 跑 `server` 模块的编译或测试时，默认带上 `-am`，例如 `mvn -pl server -am test` 或 `mvn -pl server -am -Dtest=RepositorySecurityFilterTest -Dsurefire.failIfNoSpecifiedTests=false test`。不要单独跑 `mvn -pl server ...`，否则 Maven 可能命中本地仓库里过期或缺失的 `0.1.0` 模块缓存，误报类似 `kkrepo-storage-file` 缺失的依赖解析错误。
- 指定单个 server 测试并使用 `-am` 时，加上 `-Dsurefire.failIfNoSpecifiedTests=false`，避免上游依赖模块因为没有匹配测试类而让 reactor 提前失败。
- 覆盖本地 38090 运行容器的 `/app/kkrepo.jar` 前，必须先生成 Spring Boot 可执行 jar，例如 `mvn -pl server -am -DskipTests package spring-boot:repackage`。普通 `server` 模块 jar 没有可执行入口，复制进容器后会报 `no main manifest attribute, in /app/kkrepo.jar` 并导致服务启动失败。

## 模块职责

- `core`：repository、component、asset、blob、metadata、协议抽象和共享鉴权契约。
- `storage-s3`：OSS/S3 blob 存储适配器。默认引擎是兼容 AWS S3 SDK 的访问方式；阿里云 OSS 部署使用 OSS Native SDK 引擎。
- `storage-file`：本地/测试用 blob 存储适配器，只用于开发和测试替换，不作为必须的生产运行时依赖。
- `cache`：进程内 TTL 缓存抽象和实现，用于可重建的节点本地热缓存、negative cache 和 per-pod 限流计数。
- `protocol-maven`：Maven 仓库行为。
- `protocol-npm`：npm 仓库行为。
- `protocol-pypi`：PyPI 仓库行为。
- `protocol-go`：兼容 Go module proxy 的行为。
- `protocol-helm`：Helm chart 仓库行为。
- `protocol-cargo`：Cargo sparse registry、publish、index 和 crate 行为。
- `protocol-pub`：Dart / Flutter Pub hosted repository 行为。
- `protocol-composer`：Composer 2 repository metadata、dist、hosted、proxy 和 group 行为。
- `protocol-docker`：Docker Registry HTTP API V2 和 OCI 行为。
- `protocol-nuget`：NuGet 仓库行为。
- `protocol-rubygems`：RubyGems 仓库行为。
- `protocol-yum`：Yum 仓库行为。
- `persistence-mysql`：MySQL DAO、模型、JSON/枚举/哈希列辅助逻辑，以及迁移和运行时共享的数据访问层。
- `migration-nexus`：Nexus 迁移和校验工具。
- `admin-ui`：由 Spring Boot 服务提供的静态运维管理控制台。
- `browse-ui`：由 Spring Boot 服务提供的用户侧仓库浏览器。
- `server`：Spring Boot 运行时入口。
- `compat-test`：面向 Nexus 和 kkrepo 的黑盒兼容性测试。

## 设计约束

- 大 blob 只存储在 OSS/S3 中。MySQL 只存储元数据、状态、索引和引用。
- 协议逻辑不要放在 controller 中。Controller 应委托给协议适配器。
- 存储代码必须位于接口之后，以便 OSS、S3 和本地测试存储可以互换。
- 对 path、package、version、asset、token 和 permission identity 使用 MySQL 事务和显式唯一约束。
- 把迁移视为幂等的产品功能，而不是一次性脚本。
- 每个迁移步骤都应支持 dry-run、resume、checksum 校验和报告。
- 除非有明确的兼容性需求，否则避免复制 Nexus 内部机制，例如 Karaf、OSGi、OrientDB、Elasticsearch 和 task 子系统。
- 保持 admin UI 可运维且信息密集。它应优先展示仓库状态、权限状态、存储健康、迁移进度和审计可见性，而不是装饰性页面。
- 实现匹配的服务端 endpoint 前，先在 `compat-test` 中跟踪 Nexus UI 到后端的映射。
