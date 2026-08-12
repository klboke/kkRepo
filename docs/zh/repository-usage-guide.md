# 仓库使用指南

本页是 kkRepo 所有仓库格式的统一使用入口，集中提供包管理客户端配置、制品发布和依赖拉取说明。
使用下列文档时，请将示例域名、仓库名、用户名和令牌替换为实际部署配置。

需要发布私有制品时使用 **hosted** 仓库，需要缓存上游仓库时使用 **proxy** 仓库；对应格式支持
**group** 时，可将其作为按成员顺序解析的统一读取入口。各格式支持的仓库类型和协议边界以
[兼容性矩阵](compatibility-matrix.md#仓库格式矩阵)为准。

## 仓库格式

| 格式 | 仓库类型 | 客户端配置与使用 | 详细指南 |
| --- | --- | --- | --- |
| Maven | hosted / proxy / group | [Maven 使用示例](client-recipes.md#maven) | — |
| npm | hosted / proxy / group | [npm 使用示例](client-recipes.md#npm) | — |
| PyPI | hosted / proxy / group | [PyPI 使用示例](client-recipes.md#pypi) | — |
| Go | proxy / group | [Go 使用示例](client-recipes.md#go) | — |
| Helm | hosted / proxy | [Helm 使用示例](client-recipes.md#helm) | — |
| Cargo / Rust | hosted / proxy / group | [Cargo / Rust 使用示例](client-recipes.md#cargo--rust) | — |
| Dart / Pub | hosted / proxy / group | [Dart / Pub 使用示例](client-recipes.md#dart--pub) | — |
| Composer / PHP | hosted / proxy / group | [Composer / PHP 使用示例](client-recipes.md#composer--php) | — |
| Terraform Provider / Module Registry | hosted / proxy / group | [Terraform 使用示例](client-recipes.md#terraform-provider--module-registry) | — |
| Swift Package Registry | hosted / proxy / group | [Swift 使用示例](client-recipes.md#swift-package-registry) | — |
| Ansible Galaxy | hosted / proxy / group | [Ansible Galaxy 使用示例](client-recipes.md#ansible-galaxy) | [Ansible Galaxy 仓库使用指南](ansible-galaxy-guide.md) |
| Conda | hosted / proxy / group | [Conda 使用示例](client-recipes.md#conda) | — |
| APT / Debian | hosted / proxy | [APT / Debian 使用示例](client-recipes.md#apt--debian) | [APT / Debian 仓库使用指南](apt-debian-guide.md) |
| Conan 2 | hosted / proxy / group | [Conan 2 使用示例](client-recipes.md#conan-2) | [Conan 2 仓库使用指南](conan-guide.md) |
| NuGet | hosted / proxy / group | [NuGet 使用示例](client-recipes.md#nuget) | — |
| RubyGems | hosted / proxy / group | [RubyGems 使用示例](client-recipes.md#rubygems) | — |
| Yum | hosted / proxy / group | [Yum 使用示例](client-recipes.md#yum) | — |
| Raw | hosted / proxy / group | [Raw 使用示例](client-recipes.md#raw) | — |
| Docker / OCI | hosted / proxy / group | [Docker / OCI 使用示例](client-recipes.md#docker--oci) | — |

客户端使用示例涵盖常用访问地址、认证方式、发布命令和拉取命令。已有专项指南的格式还提供仓库创建、
proxy/group 行为、运维方式和已知限制等更完整的说明。

## 通用仓库运维

- [Artifact Scanning 使用指南](artifact-scanning-guide.md)说明扫描配置、策略判定和运维检查。
- [Cleanup Policy 使用指南](cleanup-policy-guide.md)说明保留规则、预览、执行和多副本行为。
- [安全模型](security-model.md)说明用户、角色、权限和 CI token。
- [Nexus 迁移说明](nexus-migration-guide.md)说明仓库迁移和校验流程。
- [排障指南](troubleshooting.md)说明常见部署问题和客户端问题的处理方式。

生产环境应使用 HTTPS，并优先使用用户专属 token 或 CI token，避免把密码写入客户端配置文件。
