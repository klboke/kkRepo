# 为什么开发 nexus-plus 替换 Nexus

nexus-plus 的诞生不是因为我们想重新做一个制品仓库，而是因为现有 Nexus 部署在稳定性、升级、成本和迁移成本上都已经无法满足我们的规模要求。

## 事故起点

我们长期使用的是 Nexus Repository `3.29.2-02`。它部署在 Kubernetes 中，存储使用 ESSD 挂载成本地盘，元数据仍依赖 Nexus 当时的 OrientDB。

早在 2023 年，我们只是调整了一次 JVM 内存配置并重启服务，就触发了 OrientDB 文件损坏。Nexus 随后彻底崩溃，服务无法拉起，直接影响了所有业务的上线构建。

那次故障大约花了 1 天时间才把 Nexus 重新恢复。排查过程中去网上搜索 OrientDB 相关异常时，会发现大量类似问题都来自 Nexus 运维场景。对我们来说，这不是一次普通故障，而是一个非常明确的信号：当制品仓库已经成为全公司 CI/CD 的核心基础设施时，内嵌数据库和本地文件状态是很高风险的架构。

## 规模变化

时间来到 2026 年，我们对 Nexus 的使用规模又上了一个台阶，对稳定性、可扩展性和可运维性的要求也更高。

我们需要的是：

- 可以多副本部署和滚动升级。
- 核心状态存储在可治理、可备份、可恢复的外部数据库中。
- blob 数据存放在 OSS/S3 这类对象存储中。
- 故障时可以快速切换和恢复，而不是依赖单个本地数据目录。
- 客户端协议、URL 和权限认证模型尽量保持和 Nexus 兼容。

自然的第一反应是升级 Nexus。

## 升级和商业版本的现实问题

部署新版本后，我们才发现新的 Nexus Community Edition 对我们的规模并不友好。

根据 Sonatype 官方文档，Nexus Repository Community Edition 面向个人和小团队，支持最多 `40,000` 个 components 和每天 `100,000` 次 requests。Sonatype 官方价格页显示 Nexus Repository 的 Pro Edition 起步价格为 `$1,620 / year + consumption`；对于更大规模或特殊部署形态，还需要和商务单独沟通。

Sonatype `nexus-public` 仓库里的公开讨论也反映了类似担忧：[Issue #883](https://github.com/sonatype/nexus-public/issues/883) 中有用户在升级到 `3.87.0` 后发现 Community Edition 的 component 和 request 限制被下调，并进一步追问这些限制未来是否还会继续变化。对我们来说，这类公开反馈说明 Nexus 社区版的用量边界不只是当前额度问题，还会带来后续升级和容量规划的不确定性。

我们是愿意为基础设施软件付费的，但在我们的制品规模和访问规模下，新的社区版本限制不适用，商业版本的价格和采购不确定性也超过了预期。

这意味着，继续沿着 Nexus 升级路线走，并不能低成本解决我们真正关心的问题：

- 旧版本的 OrientDB 稳定性风险。
- 新版本社区版的用量限制。
- Pro 版本的商业成本和规模化价格不确定性。
- 从老版本升级到新架构时仍然要承担迁移、验证和回滚风险。

## 为什么不直接换一个现成替代品

我们也认真看过其他制品仓库方案，但没有找到一个足够合适的替代品。

真正的成本并不只是“部署一个新服务”，而是整个公司级迁移：

- 服务端运维同学需要把历史数据全部迁到新平台。
- 需要重新维护用户、角色、权限、token、仓库配置和 blob 数据。
- 使用方需要逐个修改 CI、构建脚本、Maven settings、npm registry、PyPI index-url、Go proxy、Helm repo 等配置。
- 大规模公司里，这类改造会影响非常多的业务线、构建任务和发布链路。

如果替代品不能兼容 Nexus 的 `/repository/<repo>/...` URL 布局、客户端协议和权限认证模型，那么迁移成本会非常高，而且很难做到无感切换。

## 为什么选择自研 nexus-plus

在有 AI 编程助手的当下，我们选择自己解决这个问题。

nexus-plus 的核心思路是：

- 兼容 Nexus 的客户端协议行为。
- 兼容 Nexus 的 `/repository/<repo>/...` URL 布局。
- 兼容 Nexus 的权限认证模型。
- 使用 MySQL 存储元数据、权限、token、审计、迁移状态和跨副本协调状态。
- 使用 OSS/S3 存储制品 blob。
- 不依赖 OrientDB、内嵌 Elasticsearch 或本地持久化 blob 文件系统。
- 支持从存量 Nexus 迁移到 nexus-plus，并尽量让用户无感。

这个项目是在 AI 的加持下诞生的。主体代码由 AI 在约一周内完成，人主要负责产品目标、架构约束、兼容性要求、问题定位和少量代码 Review。

为了控制这种开发方式的风险，我们没有依赖“看起来实现了”的主观判断，而是把正确性放在黑盒测试和真实流量验证上：

- 项目内有独立的 `compat-test` 模块，面向真实 Nexus 参考实例做黑盒兼容性测试。
- Nexus 迁移到 nexus-plus 后，我们在 Istio 侧 100% 镜像真实线上流量到 nexus-plus，观察响应状态、错误、延迟和协议行为。
- 所有功能、协议和边界，都尽量通过黑盒测试、镜像流量和生产指标来兜底。

感谢 Codex 和 Claude。没有 AI 的加持，我们不会选择用这种方式解决问题。

## 迁移结果

现在，我们已经从 Nexus 0 停机迁移到了 nexus-plus。

迁移完成后：

- 原 Nexus 域名指向 nexus-plus。
- 客户端配置不需要修改。
- CI 继续使用原来的 Maven settings、npm registry、PyPI index-url、Go proxy、Helm repo。
- 使用方没有感知到迁移过程。
- 迁移后没有收到业务侧失败反馈。

更重要的是，迁移之后我们获得了之前很难拥有的运维能力：

- 可以滚动升级。
- 可以扩容多个实例。
- 可以做自动弹性伸缩。
- 核心状态在 MySQL 中，便于备份、排查和恢复。
- blob 数据在 OSS/S3 中，不再绑定单个本地数据目录。

这就是 nexus-plus 的起点：不是为了做一个更复杂的 Nexus，而是为了在我们的规模下，用更简单、可控、可恢复的架构，替换掉已经成为稳定性风险的旧 Nexus。

## 为什么开源

我们选择把 nexus-plus 开源出来，是希望更多遇到类似问题的团队可以多一个开源选择。

如果你也在使用旧版本 Nexus，也遇到了内嵌数据库稳定性、升级路径、Community Edition 用量限制、Pro 版本成本、迁移工作量或多副本部署能力不足等问题，nexus-plus 希望能提供一条更平滑的替代路径。

更宏大的期望是，这个项目可以在开源社区中不断成长：继续完善 Nexus 兼容性，持续增强迁移和运维能力，并在社区反馈和贡献下不断丰富更多制品类型仓库的支持。

## 参考

- Sonatype Community Edition Onboarding: https://help.sonatype.com/en/ce-onboarding.html
- Sonatype Platform Pricing: https://www.sonatype.com/products/pricing
- Sonatype nexus-public Issue #883: https://github.com/sonatype/nexus-public/issues/883
