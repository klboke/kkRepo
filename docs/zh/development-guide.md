# 开发指南

本文档面向本地开发、测试和调试。项目概览和用户侧介绍放在根目录 `README.md`；数据库模型见[数据库 Schema](database-schema.md)和[MySQL ER 设计](mysql-er.md)。

## 前置要求

- JDK 25
- Maven 3.9 或兼容版本
- MySQL 8.0 或 PostgreSQL 12+
- 可选：Docker，用于构建镜像或启动本地依赖

服务运行时依赖 MySQL 或 PostgreSQL 和 blob 存储。开发时可以使用本地 File blob store；需要验证对象存储行为时再使用 S3 兼容对象存储，例如 MinIO、RustFS、阿里云 OSS 或 AWS S3。

短 TTL 性能缓存默认使用进程内存；HTTP session、认证 ticket、catalog 广播和跨副本 cache token 使用共享数据库。

## 本地依赖

本地运行至少需要 MySQL 或 PostgreSQL。MySQL 仍是默认 Compose 服务，`postgresql` profile 可启动另一种后端。blob store 可以使用 File blob store；如果要验证 S3 兼容对象存储行为，可以使用下表中的本地服务默认值：

| 依赖 | 默认值 |
| --- | --- |
| MySQL 地址 | `127.0.0.1:13306` |
| MySQL 数据库 | `kkrepo` |
| MySQL 用户名/密码 | `kkrepo` / `kkrepo` |
| File blob store 基准目录 | `blobs` |
| S3 endpoint | `http://127.0.0.1:9000` |
| S3 控制台 | `http://127.0.0.1:9001` |
| S3 access key / secret key | `minioadmin` / `minioadmin` |
| 开发 bucket | `kkrepo` |

### Docker 一键启动依赖

如果本机没有可用的 MySQL 或 S3 兼容对象存储，可以使用仓库内置的 Docker Compose 文件一键拉起开发依赖：

```bash
docker compose -f docker-compose.dev.yml up -d
docker compose -f docker-compose.dev.yml ps
```

如需使用 PostgreSQL 而不是默认 MySQL 服务：

```bash
docker compose -f docker-compose.dev.yml --profile postgresql up -d postgresql rustfs
export KKREPO_DATABASE_TYPE=postgresql
export SPRING_DATASOURCE_URL='jdbc:postgresql://127.0.0.1:15432/kkrepo'
```

该命令会启动：

| 服务 | 镜像 | 本地地址 | 说明 |
| --- | --- | --- | --- |
| MySQL | `mysql:8.0` | `127.0.0.1:13306` | 自动创建 `kkrepo` 数据库和 `kkrepo` 用户 |
| PostgreSQL | `postgres:16`（默认开发镜像；运行时最低版本为 12） | `127.0.0.1:15432` | 可选 `postgresql` profile；创建相同数据库和用户 |
| RustFS | `rustfs/rustfs:latest` | S3 API: `http://127.0.0.1:9000`；Console: `http://127.0.0.1:9001` | S3 兼容对象存储，用于验证 OSS/S3 blob store 行为 |

本地数据目录：

- MySQL：`.local/mysql`
- RustFS：`.local/rustfs/data`

RustFS 官方镜像以非 root 用户运行，挂载宿主机目录时需要确保数据目录可由 UID `10001` 写入。`docker-compose.dev.yml` 中的 `rustfs-perms` init service 会在启动 RustFS 前自动修正目录权限。

首次使用 S3/OSS blob store 时，需要在 RustFS 控制台或 S3 客户端中创建开发 bucket：`kkrepo`。

停止依赖：

```bash
docker compose -f docker-compose.dev.yml down
```

该命令会保留 `.local/mysql` 和 `.local/rustfs/data` 中的数据；如果需要重置本地依赖数据，可以停止容器后手动删除对应目录。

## 本地开发流程

辅助脚本位于 `scripts/`。这些脚本使用 Spring 的 `dev` profile，该 profile 会：

- 如果设置了 `KKREPO_PORT`，服务会绑定到该端口；否则使用 `18090` 端口
- 管理端口固定为 `18091`，`/actuator/health`、`/actuator/metrics` 和 `/actuator/prometheus` 从该端口暴露
- 直接从各模块的 `src/main/resources/META-INF/resources/` 提供静态 UI 资源，因此修改 HTML/CSS/JS 后刷新浏览器即可看到效果
- 启用 `spring-boot-devtools`，支持 Java 增量重载
- 启用 SQL DEBUG 日志

| 脚本 | 作用 |
| --- | --- |
| `scripts/dev.sh` | 增量编译并在后台启动。PID 写入 `logs/server.pid`，输出写入 `logs/server.log`。如果服务或端口已经存活，会拒绝重复启动 |
| `scripts/stop.sh` | 停止记录的进程、子 JVM、本仓库残留的 `spring-boot:run` 进程，以及仍监听开发端口的进程 |
| `scripts/restart.sh` | 先执行 `stop.sh`，再执行 `dev.sh` |
| `scripts/logs.sh` | `tail -F logs/server.log` |
| `scripts/recompile.sh` | 修改 Java 后在第二个终端运行。传入 `-l`/`--loop` 可进入交互式重编译循环 |

常用开发循环：

```bash
./scripts/dev.sh
KKREPO_PORT=48092 ./scripts/dev.sh
./scripts/logs.sh
curl -sS http://127.0.0.1:18091/actuator/health
./scripts/recompile.sh
./scripts/restart.sh
./scripts/stop.sh
```

启动后访问：

- 管理控制台：`http://127.0.0.1:18090/admin/`
- 用户侧浏览器：`http://127.0.0.1:18090/browse/`
- 健康检查：`http://127.0.0.1:18091/actuator/health`

LiveReload 已启用，端口为 `35729`。安装 LiveReload 浏览器扩展后可以省去手动刷新。

## 热重载范围

| 变更 | 操作 | 生效方式 |
| --- | --- | --- |
| HTML / CSS / JS / 静态资源 | 刷新浏览器 | 直接从源码资源目录读取 |
| Java 方法体 | `scripts/recompile.sh` 或 IDE 自动构建 | devtools 重启 Spring context |
| 新 Spring bean、schema/SQL、`application.properties` | `scripts/restart.sh` | 完整重启 |
| `pom.xml` | `scripts/restart.sh` | Maven 重新解析依赖并完整重启 |

## 构建和测试

完整构建：

```bash
mvn -DskipTests package
```

开发中更常用的是只编译或测试 `server` 及其依赖模块：

```bash
mvn -pl server -am compile
mvn -pl server -am test
```

指定单个 `server` 测试时需要带上 `-am`，并加 `-Dsurefire.failIfNoSpecifiedTests=false`，避免上游模块没有匹配测试类时让 reactor 提前失败：

```bash
mvn -pl server -am \
  -Dtest=RepositorySecurityFilterTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  test
```

打包 Spring Boot 可执行 jar：

```bash
mvn -pl server -am -DskipTests package spring-boot:repackage
java -jar server/target/kkrepo-server-*.jar
```

注意：普通 `server` 模块 jar 没有 Spring Boot 可执行入口。需要复制或部署 `server/target/kkrepo-server-*.jar` 时，必须先执行 `spring-boot:repackage`。

Docker 镜像、压缩包产物和生产部署说明见 [构建部署指南](build-deployment-guide.md)。

## 兼容性测试

协议功能必须优先对齐官方协议和 Nexus 行为。新增或修改协议行为时，应优先补充 `compat-test` 中面向 Nexus 参考实例运行的黑盒兼容性测试，再实现 kkrepo 中的最小兼容行为。

默认兼容性测试：

```bash
mvn -pl compat-test -am test
```

Live black-box 测试默认跳过，需要显式提供 Nexus 参考实例和 kkrepo 地址。具体命令见 [compat-test/README.md](../../compat-test/README.md)。

如果变更路径会被真实包管理器客户端直接触达，请在一次性兼容性环境准备完成后运行真实客户端 E2E：

```bash
scripts/ci/run-live-compat.sh client-e2e
```

该 suite 会通过 Maven、npm、PyPI、Helm、Cargo/Rust、Dart/Pub、Composer/PHP、Terraform 0.13/当前稳定版、SwiftPM/Xcode、Ansible Galaxy 2.9/当前版、NuGet、RubyGems、Yum、Docker/OCI 客户端发布/上传并下载/解析。Composer 额外覆盖 hosted 到 proxy 的传递依赖、错误 Basic 凭据和清空客户端缓存后的断上游 lock replay；Terraform 覆盖 group 中的 hosted 与 registry.terraform.io proxy module/provider，并验证 Provider checksum/signature metadata；Swift 5.7 覆盖 registry/proxy resolve/build，5.10/6.x 额外覆盖 HTTPS login、不可变发布、hosted/group、SCM replacement 和跨副本读取；Ansible 覆盖 Galaxy v3 discovery、持久化 publish task、不可变版本、range/transitive dependency、hosted/group 下载、public Galaxy proxy、Basic/GenericToken/Nexus bearer 认证和跨副本读取。Swift 独立 lane 覆盖 macOS Xcode 与 Windows proxy resolve；Go 通过 Go module proxy 做 resolve-only 验证。客户端诊断信息会写入 `artifacts/client-e2e/`，包含 credential 的 metadata 会在上传 artifact 前脱敏。

Pull Request 上的 `run-client-e2e` 用于验证默认 JVM 候选镜像。Spring AOT、runtime hint 或 Native 打包变更应使用独立的 `run-native-client-e2e` 标签；该 workflow 会构建 Native 候选镜像，并分别针对 MySQL 和 PostgreSQL 运行 Linux 真实客户端矩阵。

Swift 的生产加固证据拆分为独立 lane：定时 S3-compatible resilience job 使用双副本、PostgreSQL 和通过 AWS S3 adapter 访问的 MinIO，验证 lease takeover、429/5xx stale 行为、restart、大 package 和破坏式备份恢复；阿里云 OSS Native 仍是 adapter contract 覆盖，不声称真实 endpoint E2E。迁移 workflow 使用 Nexus 3.94 H2 源到 MySQL，以及 PostgreSQL 源到 MySQL/PostgreSQL 目标的 lane，并验证 source profile/proxy credential fail closed、restart/resume、checksum 和幂等性。

## 开发设计文档

仓库格式和迁移工作的详细设计、实现计划集中放在 `docs/zh/dev/`：

- [Docker 仓库实现说明](dev/docker-repository-implementation-plan.md)
- [Cargo / Rust 仓库开发设计说明](dev/cargo-rust-repository-design.md)
- [Dart / Pub 仓库开发设计说明](dev/dart-pub-repository-design.md)
- [Composer / PHP 仓库开发设计说明](dev/composer-php-repository-design.md)
- [Terraform Provider / Module Registry 开发设计说明](dev/terraform-repository-design.md)
- [Swift Package Registry 开发设计说明](dev/swift-package-registry-design.md)
- [Ansible Galaxy 仓库开发设计说明](dev/ansible-galaxy-repository-design.md)
- [Ansible Galaxy 仓库使用指南](ansible-galaxy-guide.md)
- [Nexus 兼容迁移重构开发计划](dev/nexus-migration-compatibility-refactor-plan.md)

## 配置中心

服务接入 Apollo ConfigData，但未配置 Apollo meta 时默认不启用 Apollo，避免本地启动时访问不存在的配置中心。配置了 Apollo meta 但 Apollo 暂不可用时，服务仍使用本地配置启动。

生产或联调环境可以通过运行参数指定 Apollo meta 地址：

```bash
KKREPO_APOLLO_META=http://apollo-config:8080 java -jar server/target/kkrepo-server-*.jar
java -Dapollo.meta=http://apollo-config:8080 -jar server/target/kkrepo-server-*.jar
```

## 实现约束

- 设计和实现默认按多副本部署考虑。状态、缓存、锁、后台任务、session、上传/删除、索引重建、metadata、negative cache 和权限判定不能只依赖单 JVM 进程内状态。
- 进程内缓存只能作为节点本地热缓存，必须可重建、有 TTL 或明确失效条件，并以共享数据库、OSS/S3、共享 TTL cache、marker 队列或其他协调机制作为正确性来源。
- 实现任何仓库格式功能前，先检查该仓库的官方协议和 Nexus 参考行为。
- 协议逻辑不要放在 controller 中。Controller 应委托给协议适配器或服务层。
- 大 blob 只存储在 OSS/S3/File blob store 中。所选关系数据库只保存元数据、状态、索引和引用。
