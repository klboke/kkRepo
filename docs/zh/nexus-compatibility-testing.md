# Nexus 兼容性测试说明

kkrepo 的目标不是重新发明一套制品仓库行为，而是在客户端协议、权限认证模型和 `/repository/<repo>/...` URL 布局上尽量兼容 Nexus。CI 验证重点收敛为三块 E2E：Nexus compatibility、Client E2E compatibility 和 Migration E2E。迁移后的镜像流量观测和生产规模验证仍作为发布信心补充。

## 项目内兼容性测试模块

项目中有完整的兼容性测试模块：

```text
compat-test/
```

`compat-test` 面向真实 Nexus 参考实例和 kkrepo 实例做黑盒对比，覆盖协议请求、写入行为、管理接口形态、权限行为和部分性能 smoke test。测试不会依赖 Nexus 内部实现，而是对比客户端真实可见的行为，例如：

- HTTP status
- 关键 response header
- 响应体语义
- 协议元数据
- checksum
- hosted 写入、删除和重复上传行为
- proxy、group、browse/search 等客户端可见行为

当前模块中包含 Maven、npm、PyPI、Go、Helm、Cargo/Rust、Dart/Pub、Composer/PHP、Terraform、Swift Package Registry、Ansible Galaxy、Docker/OCI、NuGet、RubyGems、Yum、Raw、组件上传、安全管理接口等方向的兼容性测试类。

常规测试命令：

```bash
mvn -pl compat-test -am test
```

默认情况下，依赖真实 Nexus 和 kkrepo 地址的 live black-box 测试会跳过，避免本地和 CI 在没有参考实例时变得不稳定。

## 黑盒对比测试

运行 live black-box 测试时，需要同时提供 Nexus 参考实例和 kkrepo 候选实例：

```bash
NEXUS_COMPAT_BASE_URL=http://localhost:28090/ \
NEXUS_COMPAT_USERNAME=admin \
NEXUS_COMPAT_PASSWORD=Admin1234 \
KKREPO_COMPAT_BASE_URL=http://127.0.0.1:18090 \
KKREPO_COMPAT_USERNAME=admin \
KKREPO_COMPAT_PASSWORD=123456 \
mvn -pl compat-test -am \
  -DfailIfNoTests=false \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtest=MavenRepositoryBlackBoxCompatibilityTest,NpmRepositoryBlackBoxCompatibilityTest,PypiRepositoryBlackBoxCompatibilityTest \
  test
```

写入类测试默认关闭，需要显式开启：

```bash
COMPAT_WRITE_ENABLED=true
```

这样可以避免误向长期运行的参考 Nexus 写入测试包。写入测试通常使用一次性包名和一次性路径，并在可行时覆盖删除、重复上传和元数据更新行为。

统一的 Nexus 兼容性矩阵使用 datastore 时代的 Nexus PostgreSQL 参考实例来覆盖较新的格式。对一次性 compose 环境运行 `scripts/ci/run-live-compat.sh nexus`，会对比 kkrepo 和 Nexus 在 Maven、npm、PyPI、Cargo/Rust、Dart/Pub、Composer/PHP、Terraform、Raw、部分 NuGet/RubyGems/Yum 行为、Go proxy endpoints、Helm hosted round trip、component upload specs 和部分 security/admin 合约上的差异。Composer 和 Terraform reference endpoint 在启用时是必需能力，缺失时测试直接失败而不是 skip。该 suite 默认启用一次性写入检查。当前 PostgreSQL compose 将参考实例固定为 Nexus 3.92.0。在 GitHub Actions 中，通过 `run-live-compat` label 或定时 workflow 运行 `Live Compatibility / Nexus compatibility` job。

Dart / Pub 之外，部分 component upload specs 和 security/admin contracts 仍会对比 Nexus 旧 UI/internal API 入口。kkrepo 的生产和发行配置默认保持 `kkrepo.nexus.legacy-ui.enabled=false`，以一个粗粒度总开关关闭 Nexus Rapture、ExtDirect、Wonderland、internal UI REST、iframe upload 等旧 Web UI 兼容面；但一次性的 compatibility compose 环境会把候选服务的 `KKREPO_NEXUS_LEGACY_UI_ENABLED` 默认设为 `true`，专门用于这些 Nexus 旧入口兼容测试。该配置是服务启动时读取的 runtime flag：手工复现这类 live 测试时，必须在启动或重启候选 kkrepo 服务前设置该环境变量；只在 `mvn -pl compat-test ... test` 前设置不会改变已经启动的服务。正常部署仍应保持默认关闭，kkrepo 自身 Browse UI 使用 native `/internal/security/permissions`，不依赖 legacy internal UI 权限接口。

Dart / Pub 兼容性测试使用同一个 Nexus Repository 3.92.0+ PostgreSQL 参考实例，因为 Pub 仓库从该版本开始进入参考范围。`nexus` suite 会通过 `PubRepositoryBlackBoxCompatibilityTest` 覆盖 Pub hosted/proxy/group metadata、archive、publish、`version.json`、checksum 和错误状态。

Terraform 兼容性测试使用 Nexus 3.90.0+ hosted/proxy/group reference。`TerraformRepositoryBlackBoxCompatibilityTest` 会比较 module/provider version、download metadata、archive/checksum/signature、URL token 后续请求、write policy 和 group 解析；真实客户端 suite 继续使用 Terraform 0.13 与当前稳定版执行 `terraform init`。

Swift 兼容性测试会覆盖通用 compose 的 Nexus 3.92.0 默认值，使用 Nexus 3.94.x hosted/proxy/group reference。`SwiftRepositoryBlackBoxCompatibilityTest` 会比较 canonical Registry v1 JSON/`Link`、negotiation、release/manifest/archive/identifier、不可变 multipart publish、签名、`v`/`V` 与 renamed GitHub repository、group 重排/nested、并发首次访问、unavailable problem 和跨副本可见性。Swift 规范将 `POST /login` 定义为可选能力，所以未实现该能力的 reference 返回 `501` 是合法分支；kkRepo 已实现该端点，预期结果为 `200`/`401`。客户端 workflow 在 Linux 运行 SwiftPM 5.7/5.10/6.x，在 macOS 运行 Xcode registry fixture，在 Windows 运行 proxy resolve/build。

Ansible Galaxy 兼容性使用 opt-in `ansible` suite 和 Nexus 3.94.x reference，因为原生 `ansiblegalaxy` 仓库从 Nexus 3.93.0 开始提供。`AnsibleGalaxyRepositoryBlackBoxCompatibilityTest` 对比 discovery、Galaxy v3 短/长路径、hosted multipart publish/import task、Nexus raw PUT、版本不可变、dependency metadata、artifact GET/HEAD/validator、group priority、proxy read、pagination、认证和 reference 错误状态。执行 `scripts/ci/run-live-compat.sh ansible`；如果所选 reference 没有必需 Ansible recipe，该 suite 会明确失败。配置 `ANSIBLE_GALAXY_BINS` 后，真实客户端会覆盖 Ansible 2.9 与当前 ansible-core。

验证证据保持在对应层级：compatibility black box 覆盖 active/revoked/expired `GenericToken`，server/persistence test 覆盖 moving tag 不可变性、1,200 tag 分页上界、cleanup 和 429/5xx 传播。定时 Swift resilience lane 使用双副本和通过 AWS S3-compatible adapter 访问的 MinIO，验证多 MiB package、共享 429/5xx 水位与 stale fallback、lease takeover、restart 和破坏式关系数据库/object 备份恢复。阿里云 OSS Native 引擎有 adapter contract 覆盖，但不声称已运行真实 endpoint E2E。

## 真实客户端 E2E

`client-e2e` suite 会用真实包管理器客户端验证一次性 kkrepo 候选实例的行为：

```bash
scripts/ci/run-live-compat.sh client-e2e
```

它会覆盖 Maven、npm、PyPI、Helm、Cargo/Rust、Dart/Pub、Composer/PHP、Terraform 0.13/当前稳定版、SwiftPM/Xcode、Ansible Galaxy 2.9/当前版、NuGet、RubyGems、Yum、Docker/OCI 的发布/上传和下载/解析流程。Composer 覆盖 hosted archive、group、Packagist proxy、传递依赖、Basic 认证和服务端 cache lock replay；Terraform 覆盖 hosted module/provider 上传、registry.terraform.io proxy、group 解析、URL token 认证、checksum 和 signature；Swift 5.7 覆盖 registry/proxy resolve/build，5.10/6.x 额外覆盖 hosted publish、Basic/GenericToken login、group resolve/build、GitHub SCM replacement、checksum replay 和跨副本读取；Ansible 会构建有依赖关系的 collection、通过 hosted 发布、拒绝重复版本、通过 group 安装/下载、验证 GenericToken/Basic/anonymous、访问 public Galaxy proxy，并可从第二副本读取。macOS/Windows 分别提供 Xcode 和 proxy-only 专用 lane；Go 通过 Go module proxy 做 resolve-only 验证，因为 Go hosted 发布不是当前仓库模式。Docker image push/pull 会固定覆盖；如果运行环境里有 `oras`，还会额外 push/pull 一个通用 OCI artifact。

当变更影响真实客户端会直接走到的仓库协议行为时，应运行这个 suite，例如认证 header 或 API key、发布/上传路径、生成的 metadata、包索引形态、checksum/download 行为、Docker connector port、group/proxy 解析等。在 GitHub Actions 中，可以手动选择 `Live Compatibility` workflow 的 `client-e2e` suite，或给 PR 加 `run-client-e2e` label。Spring AOT、runtime hint 或 Native 打包变更应添加 `run-native-client-e2e`；独立 Native workflow 会复用 Linux 客户端 suite，分别覆盖 MySQL 和 PostgreSQL，同时不替代默认 JVM lane。

客户端命令日志、下载到的 metadata、部分 inspect 输出和诊断信息会写入 `artifacts/client-e2e/`；可能包含 URL token 的 Terraform metadata 会在上传前脱敏。该 suite 依赖 [compat-test README](../../compat-test/README.md) 中列出的真实工具，本地机器可能需要先安装对应 SDK 或包管理器才能跑完整矩阵。

## 迁移 E2E

`Migration E2E` workflow 会从受支持的 Nexus 代际导入配置、安全元数据和仓库数据，包括历史 3.29.x embedded/OrientDB 参考实例，以及 datastore 时代的 H2/PostgreSQL 参考实例。Swift hosted 数据仅对已验证 Nexus 3.92.x-3.94.x datastore shape 规划为 `FULL`，版本超出范围或 shape 漂移保持 manual。Swift live 矩阵使用 Nexus 3.94，覆盖 H2 源到 MySQL，以及 PostgreSQL 源到 MySQL/PostgreSQL 目标、restart/resume、精确行数幂等和被遮蔽/缺失 proxy secret 的 fail-closed 路径；同时证明原生 Nexus 3.94 不会把上传的可选签名、原始 metadata 和 repository URL 写入源导出，目标也不会伪造，独立 writer contract 则保证源导出确有这些字段时原样保留。Ansible migration contract 强制 Nexus 3.93.x-3.94.x version/shape gate、协议感知 archive 恢复、不可变幂等、显式选择 proxy cache 和 proxy secret fail closed；完整 collection manifest/files JSON 保存在 blob storage，而不是关系数据库 JSON 列。这类 proxy 保持 offline 且不写入占位 credential，直到在目标端显式补齐。当变更影响 source detection、迁移 adapter、仓库数据导入、blob/checksum 校验、权限或迁移后的协议行为时，给 PR 加 `run-migration-e2e` label。

## 流量镜像验证

除项目内的黑盒兼容测试和真实客户端 E2E 外，我们在 Nexus 迁移到 kkrepo 后，还在 Istio 侧把真实线上流量 100% 镜像到 kkrepo，用来观测 kkrepo 对真实客户端请求的响应情况。

这个历史验证阶段的目标是：

- 确认 Maven、npm、PyPI、Go、Helm、Cargo/Rust、Dart/Pub、Composer/PHP、Docker/OCI、Terraform、SwiftPM/Xcode、Ansible Galaxy 等真实客户端请求都能被 kkrepo 正确识别。
- 对比 Nexus 主链路和 kkrepo 镜像链路的 HTTP status、错误类型和关键响应行为。
- 观察 proxy 回源、blob 存储、权限认证、metadata/index 重建在真实流量下的稳定性。
- 发现 `compat-test` 未覆盖的边缘请求，例如客户端特殊 header、老版本客户端行为、CI 插件探测请求和偶发代理请求。

Istio 流量镜像只复制请求到 kkrepo，客户端仍接收主链路响应，因此可以在不影响客户端的情况下观察 kkrepo 的真实兼容性表现。镜像验证期间，重点结合以下信息判断是否存在兼容性问题：

- kkrepo 应用日志
- Istio access log
- Prometheus 指标
- Grafana dashboard
- 仓库请求 4xx/5xx 分布
- proxy 回源错误和延迟
- blob 存储读写错误

需要注意，Nexus UI 管理请求、ExtDirect 轮询、脚本 API 请求等管理面流量，不等同于 Maven/npm/PyPI/Go/Helm/Cargo/Pub/Composer/Docker/OCI/Terraform/Ansible 等仓库协议流量。分析镜像异常时要先区分请求类型，避免把管理面请求误判为仓库协议兼容性问题。

## 生产规模验证

kkrepo 已经经过一轮真实生产规模验证。验证场景主要使用以下 5 种仓库类型：

- Maven
- npm
- PyPI
- Go
- Helm

整体规模和观测结果：

| 维度 | 规模或结果 |
| --- | --- |
| 包总量 | 约 `180W` 个包 |
| QPS 峰值 | 约 `200` |
| 本地缓存命中时平均 RT | `50ms` 以下 |
| proxy 偶发回源时 RT | 可能突增到 `600ms` 以上 |
| hosted 仓库迁移规模 | 约 `50W` 个包 |
| hosted 仓库迁移耗时 | 一个晚上完成 |

这组数据用于说明 kkrepo 在真实业务流量和迁移规模下的验证结果，不代表固定 SLA。实际吞吐和延迟仍会受到 MySQL 规格、OSS/S3 性能、网络、proxy 上游质量、仓库数量、包大小和副本数影响。

Cargo / Rust、Dart / Pub、Terraform、Swift 和 Ansible Galaxy 不包含在上述历史生产规模验证数据中。生产切换前应通过 Nexus 3.77.x+ 兼容性测试套件和真实 Cargo 客户端验证 Cargo，通过 Nexus 3.92.0+ 兼容性测试套件和真实 `dart pub` / `flutter pub` 客户端验证 Pub，通过 Nexus 3.90.0+ reference 与 Terraform 0.13/当前稳定版 `terraform init` 验证 Terraform，通过 Nexus 3.94.x reference 与跨平台 SwiftPM/Xcode matrix 验证 Swift，并通过 Nexus 3.94.x `ansible` suite 与 Ansible 2.9/当前版 `ansible-galaxy` 验证 Ansible。

## 兼容性问题处理流程

发现兼容性差异时，按以下顺序处理：

1. 先确认请求类型：仓库协议请求、管理 UI 请求、Script API 请求还是健康检查。
2. 如果是仓库协议请求，优先在 `compat-test` 中补一个可复现的 Nexus 对比用例。
3. 对比 Nexus 和 kkrepo 的 status、header、响应体、metadata、checksum 和真实客户端行为。变更路径会被包管理器客户端直接触达时，应运行 `client-e2e`。
4. 在 kkrepo 中实现最小兼容修复。
5. 重新运行对应 `compat-test`，必要时再用镜像流量观察真实请求是否恢复。

只有协议允许的非确定性字段才做归一化，例如 host、timestamp、排序或生成 ID。对于 checksum、metadata 语义、权限判定和客户端可见状态码，应尽量和 Nexus 对齐。

## 相关文档

- [监控观测指南](monitoring-observability-guide.md)
- [Nexus 迁移说明](nexus-migration-guide.md)
- [开发指南](development-guide.md)
- [compat-test README](../../compat-test/README.md)
