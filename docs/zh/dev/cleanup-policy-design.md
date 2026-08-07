# kkRepo Cleanup Policy 开发设计说明

本文定义 kkRepo 自身的通用制品清理策略能力。
[GitHub Issue #61](https://github.com/klboke/kkRepo/issues/61) 只提供了最初的业务场景，不是
功能范围、兼容目标或实现约束。Cleanup Policy 是仓库平台能力，必须覆盖当前
`RepositoryFormat` 中的 Maven、npm、PyPI、Cargo、Pub、Composer、Go、Helm、Docker、
NuGet、RubyGems、Yum、Terraform、Swift、Ansible Galaxy 和 Raw，而不是 Maven 专属功能。
其中的 Maven 多模块场景只是一个用例和验收 fixture。

本文同时描述目标架构和本分支已经落地的生产运行基线。当前实现已经打通“策略聚合、多仓库、
Quartz Cron、有界 Try Run、异步手动/定时执行、逐条审计”主链路，并向当前 16 种格式开放扫描、
最后下载条件和应用层实际删除；同时补齐了数据库 claim、repository lease/fencing、心跳、接管、
有界重试、取消、持久 protection、跨 run 扫描游标、下载水位写合并、有界历史保留和双数据库
合同。不能通过直接写表绕过 API 校验启用自动清理。

本轮“生产化”边界是安全、可调度、可接管、可取消和可观测的不可逆清理。终态 run 已按保留
周期和每策略最小保留数做数据库协调的有界回收；冷归档/导出、Cleanup 专属 tombstone/restore
和归档存储层级仍是后续独立能力。当前物理 Blob 回收继续复用既有引用计数、soft-delete 与
Blob GC，不在本文中把尚未实现的恢复窗口标记为已交付。

## 当前落地范围

截至本次实现，已经落地以下可运行闭环：

- Admin UI 和 `/internal/cleanup` 原生 API 可以创建多个策略；一个策略可以选择多个**同格式**
  Hosted/Proxy 仓库，同一仓库也可以属于多个策略。Group 不持有独立清理对象，不能作为策略目标。
- 每个策略拥有自己的 Quartz Cron 表达式和 IANA 时区。新策略默认暂停；修改规则、目标仓库
  或限额会自动暂停 schedule，要求管理员重新确认后启用。
- 16 种 `RepositoryFormat` 都可以保存策略并执行有界 Try Run。扫描上限按仓库设置，单次
  Try Run 另受服务端 50,000 subject 总硬上限约束；被截断的 family 不给出删除结论。
- Maven、Cargo、Pub、Terraform、Swift 和 Ansible Galaxy 复用现有版本比较器支持
  `retainCount`；其他格式在比较器完成协议验证前不展示该规则。
- `lastDownloadedOlderThanDays` 对全部格式开放。各协议已有的 `ArtifactDownloadPolicy` 读取关口
  在成功授权的外部 GET 上更新具体 source asset 水位；Hosted、Proxy 以及通过 Group 访问时解析出的
  实际 source repository 都能正确归属。HEAD、内部扫描器请求和策略拒绝请求不更新；body 打开后的网络中断可能保守地保留一次
  使用记录。Docker 以 manifest GET 作为镜像 subject 的使用水位；共享 layer GET 不向所有引用
  manifest 扇出写入。
- 16 种格式都开放手动执行和定时执行。非 Docker subject 复用现有 Browse/协议应用层删除事务；
  Docker manifest 复用 `DockerManifestStore.deleteReference`；Swift、Ansible Galaxy 和 Terraform
  provider 同时清理各自 registry state。Hosted npm 以 tarball/version 为清理单位，在同一事务
  重写 packument 与 dist-tags；NuGet 把同一 id/version 的 nupkg 与 nuspec 作为一个 subject
  锁定和删除。删除不以通用 SQL 绕过应用层，既有 Maven metadata、Helm/PyPI index、
  Yum/RubyGems metadata、npm/PyPI group cache、Terraform metadata cache 和 Blob 引用处理继续生效。
- Hosted 仓库中的 packument、index、repodata 等协议生成物不是独立清理 subject，只由所属制品
  删除流程重写或重建；Proxy 持有的本地缓存可以作为清理 subject。Group 派生缓存由 member 变更失效
  和内部缓存维护机制管理，不通过 Cleanup Policy 直接扫描或删除。
- MySQL 8 与 PostgreSQL 12 使用同一 cleanup DAO contract，并使用 Quartz JDBC JobStore 的官方
  表结构做集群调度。`cleanup_policy_schedule` 是产品配置真相，Quartz job/trigger 是可重建的
  调度投影；`(policy_id, scheduled_for)` 唯一键保证 scheduled fire 幂等。旧 Docker fixed-delay
  cleanup worker 忽略 `mode=NATIVE` 的新策略，避免两套执行器重复消费。
- 手动和定时触发只创建持久 parent run/repository shard 并返回 `202`。每个副本都可从数据库
  claim；同一仓库的 EXECUTE 由 repository lease 串行化，每次 claim 增加 fencing token。worker
  在扫描期间以及批内等待期间续租，崩溃后由其他副本接管；旧 owner 的心跳、删除和完成提交均
  因 fence 不匹配而失败。claim 每次只选择同一仓库最早的可运行 shard，某个繁忙仓库不会挤占
  其他空闲仓库的领取窗口。失败按有界指数退避重试，用户可请求取消；若 owner 在收到取消前
  失联，lease 到期后其他副本仍会接管并把 shard 收敛到 `CANCELLED`，父任务最终聚合各 shard。
- EXECUTE 在策略与仓库维度持久保存扫描游标，并把游标 revision 快照到 shard；只有 shard 终态
  提交与游标 CAS 推进在同一事务成功后，下一次 run 才从后续 family/asset/manifest 继续。取消、
  删除上限或旧 owner 不推进游标，避免崩溃和接管时跳过内容；完成一轮后游标回绕。Try Run 始终
  从稳定起点扫描，便于重复核对结果。
- 被 cleanup policy target 或非终态 run shard 引用的仓库不能删除；服务层先给出可理解的冲突，
  数据库外键再以 `RESTRICT` 兜住并发竞态，避免级联删除策略绑定或留下无法解释的运行记录。
- 真正删除前在同一短事务里锁定格式原生 component/asset/manifest 状态，复检内容 token、usage
  revision、policy target、protection 和当前 repository fence，再调用既有应用删除入口。Try Run
  结果从不直接作为删除清单。
- `GLOBAL`、`REPOSITORY`、`SUBJECT` 三种持久 protection 可来自人工、外部系统、legal/security
  hold 或系统；外部 protection 必须携带 freshness deadline，过期数据不会无限阻止清理。
- 最近下载水位只为至少存在一个 usage-based policy 的系统进入数据库判断，并按实际 source
  repository 决定是否落库；因此从 Group 下载也能保护 member 中的真实制品。节点本地 TTL 只
  合并已经成功提交的重复写，水位单调递增，写失败默认 fail closed；tracking warm-up 与 safety
  lag 防止策略刚启用时删除尚未建立历史水位的内容。
- 终态运行历史默认保留 90 天且每个策略至少保留最近 10 次。后台任务每轮只锁定并删除有限批次，
  候选边界只计算一次，再按主键使用 `SKIP LOCKED` 锁定；run item 与 parent run 分开限批，避免
  外键级联形成无界事务。运行 backlog、最老等待年龄、过期 lease、takeover、游标冲突、run
  时长和历史回收都提供低基数指标。
- `scripts/ci/run-client-e2e.sh` 已为 Raw 及现有真实客户端矩阵增加 cleanup gate：在发布/下载
  fixture 完成后，针对对应 hosted 或 proxy 仓库执行 Try Run 和 Execute，要求完整命中、
  零失败和至少一个实际删除，并保存独立运行报告。本分支完成的是门禁实现和静态校验；
  全格式客户端矩阵的实际执行是发布环境门禁。单元测试和 JDBC contract 另覆盖下载/重发/
  protection 竞态、接管和旧 fence 拒绝。

本轮有意不做跨格式策略、策略模板和复杂规则编排。它们会显著增加 capability 交集和配置认知
成本，但不是验证清理主链路所必需；后续应由真实使用反馈决定是否增加。

下载水位投影由策略配置自动维护。完全没有 `lastDownloadedOlderThanDays` 策略时，下载热路径
不会查询或写入 cleanup usage；存在该类策略时，入口仓库或实际 source 仓库命中投影才持久化，
未命中的请求只做一次有索引的来源确认且不写水位。默认 5 分钟写合并与不小于写合并窗口的 safety lag
可配置，进程内 cache 丢失只增加写入，不改变清理正确性。

## 需求理解与结论

需求的本质是：仓库中的逻辑制品持续累积，需要按发布时间、最后使用时间、名称/路径规则
和“每个制品族保留最近 N 个版本”自动淘汰旧内容。Maven 示例中，一个项目有几十个子模块，
每次发版后各模块版本可能不同，希望只处理 `jackson-*` 并对每个模块独立保留 N 个版本。

这个需求不能实现成 Maven worker，也不能实现成逐个 Blob 或文件删除。正确边界是：

- 核心引擎只负责策略、匹配、run、Try Run、保护、lease、审计和恢复，不理解某一种协议。
- 每种仓库格式通过 `CleanupFormatAdapter` 定义自己的最小清理单位、版本族、版本顺序、
  下载归属、完整删除动作以及 metadata/index/cache 修复。
- 一次清理必须删除一个完整的逻辑制品版本。例如 Maven 是完整 GAV，PyPI 是一个 release
  下的 distributions，Go 是同一版本的 `.info/.mod/.zip`，不能留下协议不可用的“半个版本”。
- “保留 N 个”必须在格式适配器定义的制品族内独立计算，不能把一个仓库、一次 glob 或不同
  包的命中结果混在一起排序。
- hosted 清理自己发布的内容，proxy 清理自己缓存的内容；group 不持有独立清理对象，不能作为
  策略目标。需要清理 member 内容时必须显式选择对应 hosted/proxy 仓库，绝不通过 group 隐式级联。
- 用户可先做有扫描上限的 Try Run 查看预计结果；真正删除仍在短事务内重查内容、使用水位
  和保护状态，不能把 Try Run 结果直接当作删除清单。
- 多副本执行依赖数据库中的 claim、lease 和 fencing；单 JVM 定时器、锁或内存队列都不能
  成为唯一真相。

## 目标

1. 对当前 16 种 `RepositoryFormat` 的 hosted/proxy recipe 提供明确的清理能力，并明确排除
   不持有独立制品的 group；每种格式都必须注册适配器或声明某项规则不支持，不能回退为通用逐文件删除。
2. 支持发布时间/更新时间、最后下载时间、namespace/name/coordinate/path 的 glob/regex
   include/exclude，以及人工和外部 protection。
3. 对有版本语义的格式，按官方协议或真实客户端采用的版本顺序，在每个格式原生制品族内
   保留最近 N 个版本；不使用 SQL 字符串排序或一套通用 SemVer 代替所有协议。
4. 在管理后台提供带 `scanLimitPerRepository` 的 Try Run，限制每个目标仓库实际扫描 subject
   数量，并由服务端施加单次 run 总硬上限；分页展示预计删除、保护原因、扫描进度和截断状态。
5. 以 Cleanup Policy 为产品执行单位：一个策略包含规则、多个目标仓库和自己的执行时间；
   支持创建多个策略、立即执行和数据库协调的定时执行，并提供限速、取消、失败重试、
   跨副本接管和 fencing。
6. 删除后立即从协议读取、Browse 和 Search 中消失，并修复该格式的 metadata/index/cache；
   当前复用既有 Blob 引用、soft-delete 与 Blob GC 回收 OSS/S3 对象。Cleanup 专属恢复窗口是
   后续独立目标，不是本轮不可逆 execute 的隐含承诺。
7. 提供完全由 kkRepo 定义的策略聚合、run 和 protection 管理 API，API 直接表达 capability、
   revision、分页和逐条解释，不承载外部产品 DTO；恢复 API 随未来 tombstone 能力一并增加。
8. 在 MySQL 8 和 PostgreSQL 12+ 上得到相同结果，不依赖数据库 regex、方言版本排序或
   进程内唯一状态。

## 非目标

- 不为所有格式发明统一版本规范。Raw 默认没有版本保留能力；Docker tag 只有在策略明确
  选择可解析的版本模式时才按版本排序。
- 不支持把 group 作为 Cleanup Policy 目标。要清理 member，必须把策略明确绑定到对应
  hosted/proxy 仓库；group 派生缓存由 member 变更失效和内部缓存维护处理。
- 不在清理事务中同步调用 Kubernetes、CMDB 或发布平台判断“是否正在使用”。外部系统只
  能写入带 TTL 和 freshness 水位的持久 protection。
- 不把 bucket lifecycle 当作制品清理引擎。对象删除仍由 kkRepo 的引用和 Blob GC 驱动，
  不能绕开关系数据库直接删除 OSS/S3 key。
- 不用 `assetDao.deleteAssetById` 作为跨格式兜底。每个格式必须完成自身 checksum、索引、
  引用、签名和派生 metadata 的一致性处理。
- 不提供第三方 Cleanup Policy 规则导入或兼容层。kkRepo 原生规则使用线性时间 matcher，
  只接受自身 schema 明确定义的 glob/regex 语义。
- 本 Issue 不实现 archive/quarantine 存储层级，也不设计 timestamped Maven SNAPSHOT build
  级别的独立保留；这些可以在通用 subject/adapter 模型上另行扩展。

## 设计基线

### kkRepo 原生架构

Cleanup Policy 不属于仓库客户端协议，也不需要兼容外部仓库产品的管理 API、规则字段、任务
状态或边界行为。设计直接建立在 kkRepo 当前架构上：

- `core` 定义 format-neutral policy/subject/adapter 契约，`protocol-*` 提供坐标、版本和协议
  metadata 语义；`persistence-jdbc` 提供通用 DAO contract，数据库模块提供 migration，
  `server` 的各格式组合层复用现有 writer 实现 adapter，`admin-ui` 只调用原生管理 API。
- MySQL/PostgreSQL 保存策略、绑定、run、lease、usage 和 protection，是多副本共享真相；
  进程内状态只做可丢失热缓存。未来 tombstone 也必须遵循同一原则。
- OSS/S3 只保存 Blob，物理回收继续经过通用 Blob reference 与 GC，不引入第二套文件生命周期。
- 管理面只提供 `/internal/cleanup/...` 原生资源，使用 revision、按 id 的有界分页和稳定错误码；
  scheduled fire 以数据库唯一键幂等，当前手动 run 不宣称支持 `Idempotency-Key`。
- 规则语义由本文和 versioned JSON schema 定义。相同行为在 MySQL/PostgreSQL 和所有副本上
  保持一致，不以任何外部产品的实现细节作为决策输入。

### 各格式官方语义

每个 adapter 的清理单位、版本比较和 metadata 修复必须以该格式官方协议、真实客户端和
kkRepo 对应协议实现为准。已有设计基线优先复用：

- Maven 使用 [Maven Version Order Specification](https://maven.apache.org/pom.html#version-order-specification)
  和现有 `MavenVersionComparator`。
- Cargo、Pub、Composer、Terraform、Swift 和 Ansible Galaxy 分别复用现有
  [Cargo 设计](cargo-rust-repository-design.md)、[Pub 设计](dart-pub-repository-design.md)、
  [Composer 设计](composer-php-repository-design.md)、[Terraform 设计](terraform-repository-design.md)、
  [Swift 设计](swift-package-registry-design.md) 和
  [Ansible Galaxy 设计](ansible-galaxy-repository-design.md) 的协议真相与客户端 fixture。
- Docker/OCI 删除和 reachability 以
  [OCI Distribution Specification](https://github.com/opencontainers/distribution-spec/blob/main/spec.md)
  及现有 manifest/blob 引用模型为准。
- npm、PyPI、Go、Helm、NuGet、RubyGems、Yum 和 Raw 在实现 adapter 前，要把官方版本/索引
  规则和真实客户端删除后行为补进对应协议测试；不能仅凭字符串或路径命名猜测。

## 当前代码基线与剩余边界

| 已落地基线 | 实现位置与语义 | 仍未包含 |
| --- | --- | --- |
| 原生 policy aggregate | V38 与 `CleanupPolicyService` 提供 CRUD、revision、同格式多仓库 target、规则快照和独立 schedule | 跨格式单策略、策略模板 |
| Quartz 集群 Cron | V39 官方 JDBC JobStore 表；policy schedule 是真相，Quartz 是可重建投影，scheduled fire 有唯一键 | maintenance window、节假日日历 |
| 持久异步执行 | V38、`CleanupRunWorker` 与 cleanup DAO 提供按仓库公平 claim、重试、取消、心跳、owner 失联后的 cancel takeover、repository lease 与 fencing | 跨区域调度队列 |
| 游标与历史治理 | V38 提供 policy/repository 持久游标、shard 游标快照、revision CAS 与 Try Run 的 `wouldDeleteSubjects`；终态 run 按时间、最小保留数和有界批次回收 | 冷归档/导出 |
| 并发安全删除 | scanner 生成 content token/usage revision；execute 在事务中锁定原生行并复检，再调用 `RepositoryContentDeletionService`；Hosted 生成 metadata 不独立入选，npm 原子重写 packument/dist-tags，NuGet 成对处理 nupkg/nuspec | Cleanup 专属 tombstone/restore |
| 全格式 usage | 公共 `ArtifactDownloadPolicy` 只记录成功外部 GET，按 asset 实际 source repository 落库；HEAD、内部请求、拒绝请求不更新 | 对共享 Docker layer 向所有 manifest 扇出 usage |
| 写放大控制 | usage-policy 投影、节点 TTL coalescer、单调 upsert、warm-up/safety lag、默认 fail closed | durable usage event outbox 模式 |
| 持久保护 | GLOBAL/REPOSITORY/SUBJECT 与 MANUAL/EXTERNAL/LEGAL_HOLD/SECURITY_HOLD/SYSTEM，外部 freshness 必填 | 与具体 CMDB/Kubernetes 的内置连接器 |
| 运维面 | Admin UI 展示策略、Cron、recent runs、shard 进度/attempt/lease，支持 Try Run、Execute、取消 | protection 的专用可视化页面 |
| 验证 | 双数据库 contract、双节点 smoke、全格式 client cleanup gate 脚本、低基数 metrics | 发布环境实际执行全格式 client gate、超大仓库长期 soak 与恢复功能测试 |

当前运行时用 format-aware subject 投影区分 component、asset 与 Docker manifest，并只通过既有
应用删除入口落地。后续若把 projection/删除分拆为独立 `CleanupFormatAdapter` SPI，必须保持
现有 content token、原生锁顺序和应用删除契约，不能退化为未知格式的直接 SQL/逐 Blob 删除。

## 核心设计决策

- 核心模块与协议 adapter 分离。核心只操作 `CleanupSubjectRef` 和 capability，不 import 任一
  `protocol-*` 具体模型；server 组合层注册 adapter。
- 当前每个 `RepositoryFormat` 都必须有显式 capability，并由 format-aware projection 和应用删除
  分派覆盖。增加新格式时，只有 subject 投影与协议级删除测试齐全后才允许开放 execute。
- Cleanup Policy 是产品聚合根，拥有规则、目标 repository set、execution limits 和一个可选
  schedule；Try Run、立即执行和定时执行都只能以 `policyId` 创建 run。
- 每个策略只指定一个具体格式，所有目标仓库必须同格式。跨格式需求通过创建多个策略表达，
  避免用户误以为一套 retain/版本/删除语义能安全应用到不同协议；格式特有条件必须通过
  capability 校验。
- 一个 policy run 先快照策略 revision 和全部目标仓库，再拆为 repository shard；shard 独立
  claim/执行，父 run 聚合进度和结果，不尝试跨仓库事务回滚。
- `CleanupSubjectRef` 是运行时的稳定逻辑制品引用，不强制所有格式落到同一 component 表。
  它包含 repository、format、subject kind、canonical key、family key、展示字段、时间、大小、
  usage identity 和 opaque content token。
- Try Run 和 execute 都物化为持久 `cleanup_run`。用户可从 Try Run 页面发起立即执行，但必须
  创建新 EXECUTE run、使用新 cutoff 重新求值，并在每个删除事务中重新解析 subject 和复检 token。
- 数据库只做 repository/time/id 等有索引预筛选。matcher、格式版本比较和 release 分类在
  adapter/Java 层完成，保证 MySQL 与 PostgreSQL 一致。
- 当前删除采用“应用层移除 active metadata/引用 + 既有 Blob GC 延迟回收”的模型，不直接删除
  OSS/S3 对象。Cleanup 专属 tombstone 持有与 restore 是后续可选阶段，不是本轮 execute 语义。
- 下载使用时间是安全边界。usage-based policy 生效时，成功 GET 必须先提交持久使用水位，
  节点本地 TTL 只能合并已成功提交后的重复写。

## 格式能力矩阵

下表定义的是 adapter 责任，不表示当前代码已经全部实现。表中的“完整删除”必须覆盖协议
metadata、checksum、签名、索引、Browse/Search 和 group 派生缓存，不能只删除主文件。

| 格式 | 最小清理 subject | retain family / 版本语义 | 删除后的协议修复 |
| --- | --- | --- | --- |
| Maven2 | 一个 component base version 的 POM、主制品、classifier、checksum、signature | `(groupId, artifactId, releaseType)`；Maven `ComparableVersion` | 重建 GA/GAV `maven-metadata.xml`，失效 checksum、Browse/Search 和 group metadata cache |
| npm | 一个 package version 及其 tarball/版本 metadata | scoped/unscoped package identity；npm 接受的 SemVer | 重建 packument，校正 dist-tags，失效 tarball 与 group/proxy cache |
| PyPI | 一个 normalized project release 下的全部 distributions | normalized project；PEP 440 | 重建 project/simple index，移除对应文件、hash 和 metadata 引用 |
| Cargo | 一个 crate version、`.crate` 和 sparse index version entry | crate name；Cargo/SemVer 规则，复用 `CargoVersions` | 重写 sparse index 行并保持 yank/依赖/checksum 语义一致 |
| Pub | 一个 package version 的 archive 和 version metadata | package name；Pub 版本规则，复用 `PubVersions` | 重建 package metadata、`latest`/retracted 视图和 archive 引用 |
| Composer | 一个 vendor/package version 及其 dist/metadata | normalized `vendor/package`；Composer 版本规范 | 重建 p2/packages metadata、provider hash 和 dist 引用 |
| Go | 一个 module version 的 `.info/.mod/.zip` 三件套 | escaped module path；Go module/pseudo-version 规则 | 重建 `@v/list`，完整删除三件套和 checksum/cache 引用 |
| Helm | 一个 chart name/version archive | chart name；Helm 接受的 SemVer | 重建 `index.yaml`，清除 digest、provenance 和 group/proxy cache |
| Docker | image 的 tag/reference；无引用 manifest 作为二级 GC subject | image name；默认按最后更新时间，只有显式 tag-version 规则才按可解析版本 | 删除 reference，按 reachability 回收 manifest/config/layer，保持 digest/tag 引用和 OCI API 一致 |
| NuGet | 一个 package id/version 的 nupkg、nuspec 和 registration 内容 | normalized package id；NuGet version 规则 | 重建 registration/flat-container/search metadata 并失效 hash/cache |
| RubyGems | 一个 gem name/version/platform 的 gem 和索引记录 | `(gem name, platform)`；RubyGems version 规则 | 重建 specs/latest/prerelease/compact index 和 quick metadata |
| Yum | 一个 RPM NEVRA 制品记录 | `(name, arch)`；RPM epoch-version-release 比较 | 原子更新/重建 repodata、checksum、location 和签名相关视图 |
| Terraform | 一个 module release 或 provider release；provider 的全部平台包属于同一 version subject | module/provider identity；Terraform 接受的 SemVer，复用 `TerraformVersions` | 重建 versions/platforms metadata，保持 checksum/signature/platform 集合一致 |
| Swift | 一个 scope/name release 的 archive、manifest、metadata 和签名 | `(scope, name)`；SwiftPM SemVer，复用 `SwiftVersions` | 复用 release tombstone/revision 语义，重建 release/identifier 视图和 Links |
| Ansible Galaxy | 一个 namespace/name collection version、artifact、metadata 和签名 | `(namespace, name)`；Galaxy SemVer，复用 `AnsibleGalaxyVersions` | 更新 v3 collection/version index、artifact、依赖和 signature 视图 |
| Raw | 一个规范化 asset path | 默认无 version/retain；仅支持 path/name/time/usage，未来可显式配置 path extractor | 删除该 asset，更新 Browse/Search、cache 和 Blob 引用，不猜测文件名中的版本 |

矩阵中的版本比较器若尚未在协议模块中公开，必须先抽取到对应 `protocol-*` 模块并用官方
client fixture 验证，再由 cleanup adapter 复用。不能把私有 service comparator 复制到核心。

### Repository type 语义

- **Hosted**：候选是该仓库拥有的已发布 subject；`publishedAt` 取完整内容 generation 的
  最后提交时间。
- **Proxy**：候选是该 proxy 持久缓存的 subject；`publishedAt` 取首次完整缓存时间，普通
  revalidation/304 不能把旧内容伪装成新发布。删除后允许下一次请求按 proxy 规则重新回源。
- **Group**：不持有独立制品 subject，不允许作为 Cleanup Policy 目标。要清理聚合内容时，必须显式选择
  对应的 Hosted/Proxy member；Group 派生缓存由 member 变更失效和内部缓存维护处理。
- 通过 group URL 的下载要把 usage 归属到实际 source member 的 subject；可另记 group access
  审计，但不能只更新 group 派生缓存的使用水位。
- member 内容被清理时，不论 group 是否绑定策略，都要通过格式 adapter 失效引用它的 group
  metadata/content cache。

## 目标 Adapter SPI（后续抽取）

当前分支先用 `CleanupSubjectScanner`、`CleanupPolicyCapabilities` 与
`RepositoryContentDeletionService` 落地主链路，尚未把它们抽成逐格式 SPI。目标 SPI 如下；
名称是设计稿，后续抽取时不能改变已经验证的锁、fence 和应用删除语义：

```java
interface CleanupFormatAdapter {
  RepositoryFormat format();
  CleanupCapabilities capabilities(RepositoryType type);
  Page<CleanupFamilyRef> scanFamilies(CleanupRepositoryRef repository, Cursor cursor, Instant cutoff);
  Page<CleanupSubjectRef> scanSubjects(CleanupFamilyRef family, Cursor cursor, Instant cutoff);
  Optional<CleanupSubjectIdentity> resolveUsageSubject(CleanupDownloadContext download);
  VersionEvaluation evaluateVersion(CleanupSubjectRef subject, CleanupPolicy policy);
  LockedCleanupSubject lockAndRevalidate(CleanupSubjectRef subject, String expectedContentToken);
  CleanupDeletionResult delete(LockedCleanupSubject subject, CleanupDeletionContext context);
}
```

`CleanupCapabilities` 至少声明：支持的 repository type、字段、时间口径、release type、版本
范围、retain、usage attribution、try-run、execute 和 group-owned-content；future capability
可另行声明 restore。保存策略和
绑定仓库时取交集校验；不支持的条件返回明确的 4xx 错误，不能当作“无匹配”或静默忽略。

`CleanupSubjectRef` 至少包含：

```text
repositoryId, sourceRepositoryId, format, subjectKind
canonicalKey, canonicalKeyHash, familyKey
namespace, name, version, coordinate, paths
publishedAt, lastDownloadedAt, estimatedAssets, estimatedBytes
usageIdentity, contentToken, adapterMetadata
```

`adapterMetadata` 只能保存有界、可审计且 execute 可重取的数据，不保存整个制品或把 adapter
的数据库快照当作永久真相。删除事务必须根据 canonical identity 重新读取原生表并比较
`contentToken`。

## 策略模型与精确语义

当前 canonical criteria 已实现 `pattern/patternType`、`publishedOlderThanDays`、
`lastDownloadedOlderThanDays` 和受 capability 限制的 `retainCount`；pattern
对 subject 的 simple name/display name 做 full match。下文的多字段 include/exclude、版本范围和
release type 是目标规则扩展，不能在 API/UI 尚未支持时视为已经交付。

`lastDownloadedOlderThanDays` 只比较已经存在的最后下载时间。没有下载时间的 subject 不命中
该条件并直接跳过，不使用发布时间或其他时间字段回退。

### 策略作用域

策略使用数据库生成、不可变的 `policyId` 作为引用身份；`name` 是全局唯一但可通过 revision
保护后修改的显示名，长度与字符集由 kkRepo schema 自行定义。target repository、schedule
和 run snapshot 一律关联 `policyId`，不把可变 name 当外键。

一个策略至少选择一个、可以选择多个 Hosted/Proxy 目标仓库。同一仓库可以同时属于多个策略，这些策略拥有
各自规则、execution limits 和执行时间。每个策略的 `format` 必须是具体格式 id，所有目标仓库
必须是该格式；需要清理不同格式时创建不同策略，这也让各策略能够采用不同规则和执行时间。

公共匹配字段为：

```text
NAMESPACE   格式原生 namespace；没有时为 absent
NAME        package/chart/image/module/asset 的规范化名称
VERSION     adapter 定义的原生版本；没有时为 absent
COORDINATE  adapter 生成的稳定、可展示坐标
PATH        subject 拥有的任一规范化 repository path，带前导 /
SUBJECT_KIND 例如 PACKAGE_VERSION、IMAGE_REFERENCE、RAW_ASSET
```

字段 absent 时不会命中该字段的 include。若策略要求某个 adapter 永远无法提供的字段，绑定
阶段直接拒绝；例如 Raw 不能绑定带 `retainLastVersions` 的策略。

### 求值流水线

单个策略按固定顺序求值：

1. 读取 repository、adapter capability、policy revision 和数据库生成的 `evaluationCutoff`。
2. adapter 按稳定 family key/keyset page 枚举 subject，并构造公共字段与 content token。
3. 按 subject kind、release type、include 和版本范围建立候选集。
4. 任一 exclude 命中时，从本策略候选中移除。
5. 计算发布年龄和 usage age；所有已配置的删除时间条件按 AND 组合。
6. adapter 在 family 内按协议版本顺序或显式 recency 模式保护最近 N 个 subject。
7. 应用跨策略的人工 hold、外部 protection、legal/security hold 和运行中写入保护。
8. 输出最终删除列表以及每项的规则、时间、版本排名、保护和 adapter 解释。

`retain` 是保护规则，不是删除条件。每个策略至少配置 `publishedOlderThanDays`、
`lastDownloadedOlderThanDays` 之一，避免只误填一个 N 就删除其余全部内容。未来若允许
“只保留 N 个”的危险模式，必须要求窄 include、二次确认和最大删除量。

### Include、Exclude、Glob 与 Regex

- 同一 include 列表中的规则按 OR；不同已配置维度按 AND。
- 任一 exclude 命中就保护该 subject，但只对当前策略生效。
- `GLOB` 中 `*` 匹配字段内任意字符；PATH 中 `*` 不跨 `/`、`**` 可以跨 `/`，`?` 匹配
  一个字符。
- 原生 `REGEX` 默认大小写敏感并匹配整个字段；需要子串语义时显式写 `.*`。
- 原生 matcher 使用 RE2/J 可表达子集，并限制 pattern 长度、数量和编译结果，避免
  catastrophic backtracking。
- PATH 始终使用带前导 `/` 的规范化 repository-relative path，并执行 full match。subject
  任一路径命中 include 即选中，任一路径命中 exclude 即保护；这套语义由 kkRepo schema 固定，
  不随 format 或外部实现改变。

Issue 中的 `jaskson-*`（按常见包名应为 `jackson-*`）在 Maven 策略中配置为：

```json
{"field":"NAME","operator":"GLOB","value":"jackson-*"}
```

它会对每个匹配的 artifactId 独立计算 retain，而不会把几十个子模块混成一组。同一个公共
matcher 也能用于 npm package、Helm chart、Docker image 等 NAME，但 family 和版本顺序仍由
各自 adapter 决定。

### 时间与 usage 语义

所有时间在 run 的数据库 UTC cutoff 上比较：

- hosted `publishedAt` 是 subject 当前内容 generation 的完成提交时间；多文件发布要等协议
  认为该版本完整后才开始计时。
- proxy `publishedAt` 是首次完整缓存时间；后台 metadata refresh 不得无理由刷新制品年龄。
- `lastDownloadedAt` 是 subject 任一可下载主内容最近一次成功授权 GET 的持久水位。HEAD、
  metadata-only 请求、失败或拒绝请求不计；checksum/signature GET 是否归属主 subject 由
  adapter 按官方协议、kkRepo 的响应路径和真实客户端测试决定。
- 从未下载或历史记录无法 backfill 时，effective usage time 取
  `max(publishedAt, usageTrackingStartedAt)`。默认等待完整 usage days，避免首次启用 tracking
  时因 null usage 立即误删；这是 kkRepo 自己的安全规则。

临界比较统一为 `effectiveTime < cutoff - days`；等于边界的 subject 保留到下一轮。

### 版本与保留 N 个

- adapter 给出 repository-local `familyKey`、可选 `releaseType`、版本 parse/compare 结果和稳定
  tie-break；完整分组键始终是 `(repositoryId, familyKey)`，多仓库策略不能跨仓库合并 retain。
- `minimumVersion`/`maximumVersion` 是删除候选边界，使用格式比较器，不是普通字符串范围。
- `versionRegex` 只做选择，不改变版本排序。
- `retainLastVersions=N` 基于 family 的全部 active subject 计算，不只在已经满足 age 的删除
  候选中排序，因此最近 N 个始终受到保护。
- 比较器相等时依次使用规范化版本、原始版本、canonical key 作稳定 tie-break，Try Run 与
  execute 必须一致。
- 无法按该格式官方规则解析的历史版本默认 `KEEP_UNPARSEABLE_VERSION`；管理员只能用明确的
  time/path-only 策略处理，不能把它放进错误的字典序。
- 超大 family 使用 keyset page 和大小为 N 的 heap，内存上界为 `pageSize + N`。

### 多策略与全局保护

同一仓库可被多个策略选中，但每个 run 只执行一个 policy revision，不把多个策略临时合并成
一次求值。策略 A 的 exclude/retain 只约束策略 A，不能阻止策略 B 在自己的运行时间命中同一
subject；人工、外部和法务 protection 才是跨策略全局保护。

两个策略同时触发同一仓库时，repository shard 先竞争持久 lease：一个进入 `RUNNING`，另一个
保留 `PENDING/RETRY_WAIT`，之后获得 lease 时基于当前仓库状态重新求值，不使用等待前候选。
Try Run 同样通过短 shard lease 执行，以确保统一的接管/限额语义。

以下保护优先于所有策略：

- 人工 hold：按 repository、format coordinate、family 或 canonical subject，填写原因，
  可选过期时间。
- 外部 protection：由 `CleanupProtectionProvider` 物化，保存 source、external id、
  observed_at 和 valid_until。source freshness 过期时暂停其适用范围的删除并告警。
- 运行时保护：正在 publish、cache fill、迁移、扫描写回或持有格式原生写 lease 的 subject。
- 法务/安全 hold：复用持久保护模型，但只有更高权限可以解除。

本 Issue 先提供人工 hold 和 provider SPI；具体 CI/CD、Kubernetes、CMDB connector 独立交付。

### Maven Issue 示例

```json
{
  "name": "maven-jackson-retention",
  "format": "maven2",
  "repositoryIds": [42, 57, 81],
  "criteria": {
    "patternType": "GLOB",
    "pattern": "jackson-*",
    "publishedOlderThanDays": 14,
    "lastDownloadedOlderThanDays": 60,
    "retainCount": 5
  },
  "scanLimitPerRepository": 1000,
  "deleteLimitPerRepository": 100,
  "schedule": {
    "enabled": true,
    "cronExpression": "0 0 2 ? * SUN",
    "timeZone": "Asia/Shanghai"
  },
  "revision": 3
}
```

它每周在同一个策略时间点处理 3 个 Maven 仓库，只删除同时满足“发布超过 14 天”和“60 天
未下载”的 release，并在每个仓库、每个匹配 artifactId 中独立保护最近 5 个 Maven 版本。
这是通用引擎上的 Maven adapter 示例，不是功能边界。

## 产品配置与执行入口

### 后台显式配置流程

Cleanup Policy 不因系统升级或创建仓库而自动产生。用户在 Admin UI 中以策略为中心显式完成：

1. 在 **Cleanup Policies** 新建策略，先选择 format，再填写规则和每仓库执行限额。
2. 在同一个策略表单中多选一个或多个同格式 Hosted/Proxy 目标仓库；Group 不出现在候选项中，服务端同样拒绝。
3. 为该策略设置自己的 Quartz Cron 表达式和 IANA 时区；schedule 可暂存为 disabled。
4. 保存后，可在策略详情点击 **Try Run** 或 **立即执行**。
5. 显式启用 schedule 后，系统才在该策略的时间点自动创建 EXECUTE run。

系统允许创建任意多个策略。同一个仓库可以同时出现在多个策略中，每个策略拥有独立规则、
目标仓库集合、execution limits、schedule、最近运行和 `nextRunAt`。策略列表直接显示这些信息，
仓库详情只反向展示“被哪些策略引用”并链接回策略，不在仓库页临时拼装执行计划。

策略删除按聚合处理：先 disabled 并拒绝新 run，再删除其 repository binding 和 schedule；历史
run 依靠不可变 snapshot 保留审计。存在 active run 时只允许标记 `DELETING`，待 run 终态后完成。

### 执行时间与 schedule

每个策略最多拥有一个 schedule，生命周期归属于该策略，初始 `enabled=false`。不同策略可以
配置完全不同的 Quartz Cron 和 IANA 时区；服务端校验 6/7 段 Quartz 表达式并计算 UTC
`nextRunAt`。例如 `0 0 2 * * ?` 表示按所选时区每天 02:00 执行。

定时执行使用 Quartz JDBC JobStore，而不是把 Spring Task 当作执行真相，原因是产品已经要求
任意 Cron、多副本唯一触发、重启后保留 trigger 和调度状态。Quartz 使用 MySQL/PostgreSQL
共享表与集群 check-in；每个 policy 对应一个 durable job 和一个 Cron trigger，
`@DisallowConcurrentExecution` 防止同一 policy 重叠，并请求 cluster recovery。misfire 固定为
`DoNothing`，服务恢复后跳过错过的周期；同一 `(policyId, scheduledFor)` 仍只能创建一个父 run，
因此 recovery 重放不会创建第二个 scheduled run。
创建持久 run 遇到瞬时数据库错误时，Quartz job 最多立即 refire 3 次；校验类永久错误不忙循环，
保留下一周期并写错误日志。即使第一次提交后响应失败，唯一 fire key 也会让重试读取同一 run。

配置和表结构以 [Spring Boot Quartz JDBC 配置](https://docs.spring.io/spring-boot/reference/io/quartz.html)、
[Quartz 2.5 JDBC JobStore](https://www.quartz-scheduler.org/documentation/quartz-2.5.x/configuration/ConfigJobStoreTX.html)
及依赖 jar 内随版本发布的 MySQL/PostgreSQL schema 为准。Flyway 复制建表语句但移除官方脚本中的
`DROP TABLE`，避免应用重启或升级清空 trigger。

`cleanup_policy_schedule` 是产品配置真相。事务提交后先投递节点本地、可丢失的短延迟投影提示，
避免在 Spring 尚未解绑事务 JDBC connection 时进入 Quartz 锁事务；启动时和每 60 秒的全量
reconciliation 负责跨副本兜底。提示队列只降低配置生效延迟，不承担正确性；这里的 Spring
`@Scheduled` 只负责唤醒投影修复，不负责触发 cleanup run。Quartz job 带 policy revision，执行前再次校验
policy 仍为 ACTIVE 且 schedule enabled，旧 trigger 即使短暂触发也不会执行旧配置。触发时快照
策略规则、目标仓库、限额和 revision，再为每个目标仓库建立 repository shard。修改规则、目标
仓库或限额会暂停 schedule；管理员复核后重新启用，已经创建的 run 继续使用自己的快照。

定时触发只创建 EXECUTE run，不自动创建 Try Run。手动 Try Run/立即执行不修改 `nextRunAt`，
也不影响下一次计划任务。

### 手动触发

策略详情页提供两个独立操作，二者都以 `policyId` 为目标并覆盖策略中的全部仓库：

- **Try Run**：只扫描和解释，不删除内容；请求必须带 `scanLimitPerRepository`。
- **立即执行**：确认 policy revision、目标仓库列表和 execution limits 后创建 EXECUTE run。

立即执行和定时执行走完全相同的 worker、lease、fencing、复检、应用删除和 run item 路径，区别
只在 `trigger=MANUAL|SCHEDULED`。从 Try Run 结果页点击立即执行也必须重新求值，不能把有限扫描
得到的 item 直接升级为删除 item。

### Try Run 扫描上限

Try Run 的用户输入是“每个目标仓库最多检查多少个 subject”，不是“最多返回多少条结果”：

- 请求字段 `scanLimitPerRepository` 必填，范围为 `1..10000`，并且不能超过策略保存的每仓库
  scan limit。UI 从 capability/config API 读取边界，服务端再次校验。
- 服务端另外施加单次 Try Run 50,000 subject 总硬上限，防止一个包含大量仓库的策略通过
  `仓库数 × 每仓库上限` 放大扫描成本；该硬上限不是用户可调的业务规则。
- adapter 每向规则引擎交付一个 subject，parent/shard 的 `scannedSubjects` 都加一；未命中、被
  exclude、被 retain 或被 protection 保护的 subject 同样计数。
- 某仓库达到自己的上限时标记 `TRUNCATED`；总硬上限耗尽后，尚未开始的仓库以截断状态记录，
  父 run 以 `SUCCEEDED_TRUNCATED` 结束。
- 每个 shard 内使用稳定 family/keyset 顺序，因此结果可复现；它仍是有界评估，不是随机抽样，
  也不能外推为全仓库统计。
- retain 需要完整 family。若上限在 family 中间耗尽，运行摘要/错误信息会标记该 family
  不完整，不为其中任何 subject 输出 `WOULD_DELETE`；用户需要提高上限或缩小 pattern 范围后重试。
- `scanLimitPerRepository` 只限制求值读取量；结果 API 仍单独使用 cursor/limit 分页，分页不能
  触发继续扫描。

Try Run summary 至少返回：

```text
scanLimitPerRepository, serverTotalLimit, scannedSubjects, completedRepositories, truncatedRepositories
notScannedRepositories, completedFamilies, incompleteFamilies
matchedSubjects, protectedSubjects, wouldDeleteSubjects
estimatedAssets, estimatedBytes, truncated, cutoff, duration
```

summary 和 item 都可按 repository 下钻。Try Run 永远不创建 tombstone、不修改
metadata/index/cache，也不触发 Blob GC。它可以记录持久 run/shard/item 供审计和比较，但按
run retention 自动清理。

### EXECUTE 跨运行扫描游标

真正执行不能像 Try Run 一样永远从排序头部开始，否则长期被保护、未命中或总是保留的前部
family 会让后续制品永久饥饿。当前实现为每个 `(policyId, repositoryId)` 保存一个持久游标：

- 非 Docker 先按大小写敏感的稳定 family tuple 扫 component，再按 asset id 扫无 component
  资产；Docker 按 manifest asset id 扫描。到达末尾后 `wrappedCount` 增加并回到起点。
- shard claim 后把当前游标和 revision 固化到 `cleanup_run_repository`。重试和 owner takeover 使用
  同一快照；完成时先验证 shard lease/fence，再在同一事务完成 shard 并以 revision CAS 推进游标。
- 取消、达到删除上限或 shard 未完成时不推进。旧 owner 即使晚到也只能产生 fence/CAS 冲突指标，
  不能覆盖新 owner 已提交的进度。
- family 不能在中间求值。若页尾切断 family，则该 family 本轮不产生删除决策并在下一页完整重读；
  若单个 family 本身超过 `scanLimitPerRepository`，本轮明确记录 warning 并跳过该 family，避免它
  永久阻塞整个仓库。管理员应提高上限或缩小规则范围，该 family 会在下次回绕再次出现。

## 总体架构

```text
all format hosted/proxy/group GET
              |
              | adapter resolves source subject + durable usage upsert
              v
         cleanup_usage

Policy detail: Try Run / Run Now / own schedule
                    ---> cleanup_run (policy + repositories snapshot)
                                  |
                 per-repository limit + server hard cap
                                  |
                   +--------------+--------------+
                   v                             v
       cleanup_run_repository A       cleanup_run_repository B ...
     (bounded scan, lease, fence)   (bounded scan, lease, fence)
                   |                             |
                   +------ registry -> format adapter
                                  | family scan + matcher + native version order
                                  v
                         cleanup_run_item
                 WOULD_DELETE / DELETE / KEEP
                                  |
                                  | native lock + content token/usage/protection recheck
                                  v
                    format-aware deletion dispatch
                         | native application deletion API
                         | metadata/index/search/cache repair
                         v
                   active content disappears
                                  |
                    existing references / soft-delete
                                  v
                 existing BlobGarbageCollectionWorker
                                  |
                                  v
                              OSS / S3

future optional path: deletion -> cleanup tombstone retain -> restore or grace expiry -> Blob GC
```

## 持久化模型

### 策略与绑定

`cleanup_policy` 继续作为策略目录。现有自增 `id` 是不可变 `policyId`，`name` 是活跃策略唯一、
可修改的显示字段；当前持久字段包括 `format`、`mode`、`notes`、`criteria_json`、`revision`、
`state`、`scan_limit_per_repository`、`delete_limit_per_repository` 和审计时间。新策略写入
`mode=NATIVE`；旧 Docker cleanup 配置仍由旧 worker 识别，旧 worker 明确忽略 `NATIVE`，避免
两套执行器重复消费。

`format` 必须为具体 format。需要覆盖多个格式时创建多个策略，不把逗号列表或 `*` 塞入一列。

`repository_cleanup_policy` 是策略拥有的 target repository set，按 `cleanup_policy_id` 绑定。
更新策略时在同一事务中替换 target set；策略改名不影响关系。服务端校验仓库存在且所有目标
仓库与策略同格式，同一 repository 可出现在多个 policy 中。usage tracking 起点保存在独立的
`cleanup_usage_tracking_repository` 投影中，不塞进 binding。

`CleanupPolicyService.create/update/delete` 在同一数据库事务中校验 revision、更新
criteria/execution limits、替换 target set、upsert/delete schedule，并只递增一次 policy revision。
任何一步失败都回滚整个策略修改；schedule 与 usage 投影只在事务提交后 reconciliation。

### `cleanup_usage`

当前下载关口最终都能解析到实际返回内容的 asset，因此持久水位以 asset 为并发与归属单位：

```text
asset_id                   PK/FK -> asset
repository_id              实际 source repository
first_downloaded_at        UTC timestamp
last_downloaded_at         UTC timestamp
usage_revision             单调 bigint
updated_at
```

写入先 `FOR UPDATE` 锁定 asset，再按 `GREATEST(existing, observedAt)` 等价语义 upsert usage，
最后同步兼容字段 `asset.last_downloaded_at`。下载与删除因此使用相同的 asset-first 锁顺序：先
提交的下载会增加 revision，使后续删除复检为 stale；先提交的删除会让下载无法写入水位，并在
fail-closed 模式返回 503，而不是继续发送未记录的内容。component/Docker subject 的有效水位和
revision 在扫描时由其 asset 集合聚合；Group 请求写入实际 member/source asset。

### `cleanup_policy_schedule`

以 `policy_id` 为主键，一对一保存 `cron_expression`、IANA `time_zone`、`enabled` 和审计时间。
`nextRunAt` 由 Quartz Cron 在读取 API 时计算，不作为第二份可漂移的配置写回该表。新 schedule
默认 disabled，随策略删除而停用，但历史 run 不级联删除。

### Quartz JDBC JobStore

`QRTZ_JOB_DETAILS`、`QRTZ_TRIGGERS`、`QRTZ_CRON_TRIGGERS`、`QRTZ_FIRED_TRIGGERS`、
`QRTZ_LOCKS`、`QRTZ_SCHEDULER_STATE` 等官方表由 kkRepo Flyway migration 创建，Quartz 自身不
执行 schema 初始化。所有副本使用相同 scheduler name、数据库和 `instanceId=AUTO` 组成集群；
只有获得 Quartz trigger 的实例执行 fire。job/trigger 可以从 `cleanup_policy_schedule` 重建，
run 审计和 scheduled fire 幂等仍由 cleanup 表负责，不能把 Quartz 表暴露成产品 API。

### `cleanup_run`

保存 mode (`TRY_RUN`/`EXECUTE`)、trigger (`MANUAL`/`SCHEDULED`)、state/cancel flag、policy
id/revision、criteria 与 target repository 快照、请求人、scheduled fire、扫描/匹配/预计删除/删除/失败/
截断统计、error 和生命周期时间。`created_at` 同时作为本次求值 cutoff；run 保存有效的每仓库
scan/delete limit。`(policy_id, scheduled_for)` 唯一键是定时触发的最终幂等屏障。

### `cleanup_run_repository`

父 run 为 snapshot 中的每个 repository 建一行，保存 repository id/name/format/type、state、
扫描/候选/删除/失败统计、truncated、attempt/max attempts、Try Run scan budget、next attempt、
lease owner/token/until、heartbeat、fencing token 和有界错误。唯一键 `(run_id, repository_id)`
防止重复 shard；这些字段已经支撑异步 claim、退避重试、崩溃接管和旧 owner 拒绝。

父状态由 shard 聚合：全部成功为 `SUCCEEDED`；任一 shard 因 Try Run budget 截断为
`SUCCEEDED_TRUNCATED`；部分仓库成功、部分失败为 `PARTIAL`；全部失败才是 `FAILED`。一个 shard
失败不回滚其他仓库已提交的删除。

状态机：

```text
TRY_RUN parent: PENDING -> RUNNING
                -> SUCCEEDED / SUCCEEDED_TRUNCATED / PARTIAL / FAILED

EXECUTE parent: PENDING -> RUNNING
                -> SUCCEEDED / PARTIAL_LIMIT_REACHED / PARTIAL / FAILED

shard: PENDING -> RUNNING -> SUCCEEDED / SUCCEEDED_TRUNCATED /
                         PARTIAL_LIMIT_REACHED / PARTIAL / FAILED
       RUNNING -> RETRY_WAIT -> RUNNING
parent: PENDING/RUNNING -> CANCELLING -> CANCELLED
未 claim shard 立即 CANCELLED；RUNNING shard 在当前短事务边界观察取消后进入 CANCELLED
RUNNING lease 过期 -> 由其他副本以更高 fencing token 接管
```

### `cleanup_run_item`

只物化最终 delete candidate、显式 protection 和执行错误，不给每个无关 subject 写行。Try Run
使用 `WOULD_DELETE/KEEP_PROTECTED` decision；截断 family 不写可能误导的 `WOULD_DELETE` item。
item 保存 run repository id、subject kind、canonical key/hash、family/display/version/delete path、
发布时间/最后下载时间、估算资产/字节、expected content token、expected usage revision、
protection id、evaluated time、decision、reason JSON 和有界错误。

唯一键 `(run_repository_id, subject_kind, subject_key_hash)` 保证重试幂等；hash 冲突仍以
canonical key 复检。EXECUTE 的最终 decision 由删除服务在同一个短数据库事务内 upsert：审计写入
失败会使数据库删除一并回滚，worker 崩溃也不会留下“删除已提交但 DELETED decision 尚在内存”的
窗口。接管者在扫描前从这些幂等 decision 恢复已提交删除数，继续遵守每仓库 delete limit；终态
提交也从 decision 汇总恢复删除/失败统计。Try Run decision 和删除前失败不包含已提交删除，继续
使用有界批次写入。

### 未来：Cleanup Tombstone 与恢复

本节是独立后续设计，当前 V38–V40 没有 `cleanup_tombstone` 表，也没有 restore/release API；
当前 EXECUTE 一旦提交即不可逆。后续实现不得只增加 UI，而必须完成下列持久引用与冲突语义：

`cleanup_tombstone` 保存 format、subject identity、adapter schema/version、格式原生 metadata
快照、来源 run/policy、删除者、删除时间、恢复截止和状态；`cleanup_tombstone_asset` 保存 path、
kind、content type、attributes、blob id 和原生 binding。

删除事务先以 owner type `CLEANUP_TOMBSTONE` 调用通用 `BlobReferenceDao.retain`，再由 adapter
移除 active subject。恢复窗口内 Blob GC 因持久引用不能物理删除；到期 worker 释放引用，
现有 Blob GC 再按自己的 grace/claim/fence 回收。

恢复必须交回同一个 format adapter，重新校验 repository、自然键、path、tag/reference 和
revision 是否已被重发占用。有冲突时保持 tombstone 并报告 `RESTORE_CONFLICT`，不能覆盖新内容。

### Content token 与竞态

核心不强制所有格式增加 `component.content_generation`。adapter 从格式原生真相构造 opaque
`contentToken`：component-backed 格式可用 component generation，Docker 可用 reference/manifest
revision，Raw 可用 asset id + blob id + update revision，Swift/Ansible/Terraform 可复用 release
revision。所有 writer 必须在内容变化事务中更新对应 revision。

candidate 保存 token；执行时锁住原生 subject 并重新生成 token。subject 聚合得到的 asset usage
revision 变化时，item 记为 `SKIPPED_STALE`，下一轮重新求值。不能只比较
毫秒时间戳，也不能让 adapter 在 token 不匹配时“尽力删除”。

## 下载使用时间记录

新增通用 `CleanupUsageTracker`，接在所有格式 hosted/proxy/group 的实际内容读取路径：

1. 完成认证、权限和安全扫描下载策略判定。
2. 读取路径把实际返回的 source asset id 传给公共 policy 关口；group 命中归属 member。
3. HEAD 不记录。GET 在打开 response body 前持久 upsert usage。
4. upsert 成功后才打开 body；后续网络中断多保留一次是安全的 false positive。
5. 仅当 repository 或其 source member 绑定 usage-based policy 时启用严格写入，避免未使用该
   能力的部署增加热路径写放大。

节点本地 TTL cache 可在成功 DB commit 后合并同一 subject 的重复下载；本地未命中后，数据库
仍以 asset 行锁和持久水位执行集群级 TTL 合并，避免多副本各写一次：

- cache 丢失只增加 DB 写，不丢唯一真相；失败时不能写入 cache 伪装成功。
- cutoff 额外减去不小于 coalescing TTL 的 safety lag，覆盖最后一次被合并的请求。
- usage upsert 失败时，已启用 usage-based policy 的仓库默认 fail closed 并返回可重试 503，
  避免“下载成功但水位丢失”导致未来误删。

默认 coalescing TTL 建议 5 分钟，可配置 0 关闭。指标暴露 DB write、coalesced hit、失败和
fail-closed 次数。

## Try Run 与执行流程

### 创建和求值 run

API 读取 policy、target repositories、capability 和 revision，保存父 run snapshot、cutoff，并
为每个仓库创建 shard。TRY_RUN 把 `scanLimitPerRepository` clamp 到策略上限，同时对父 run
应用服务端总硬上限；EXECUTE 使用 policy snapshot 中的每仓库 scan/delete limits。

1. 父 coordinator 按稳定 repository order 启动 shard，并在父级累计总扫描量。
2. adapter 按稳定 `familyKey`/id 顺序扫描，不用无界读取。
3. 每交付一个 subject 就增加 shard/parent scanned counter；达到本仓库上限或父级硬上限即停止。
4. 对完整 family 流式读取 subject，按格式比较器计算 retain set；截断 family 不产出删除结论。
5. 执行 matcher、时间和 retain 求值并批量写 item；累计 scanned 永远不能超过本仓库 effective
   limit，也不能使父 run 越过服务端总硬上限。
6. 父 run 汇总所有 shard 后分页显示总体及按仓库结果；它只描述 cutoff 与扫描范围内的结果，
   不承诺未来 execute 时仓库没有变化。

### 执行前复检

普通格式的每个 item 在短事务中；npm Hosted 的同一 package family 可在有界批次中复检并删除，
以便 packument 只读写一次：

1. adapter 按 canonical identity 锁定原生 subject，验证 repository、ownership 和 content token。
   独立 asset 还必须保持未绑定 component，且仍属于该仓库类型允许清理的 subject；token 需包含
   component binding、blob、format、path、kind、content type、size 和更新时间等语义字段，避免
   扫描后重新绑定或变成 Hosted 生成元数据的 asset 被旧候选误删。
2. 在相同 asset-first 锁顺序下读取 usage，验证 revision 和最新 effective time。
3. 重读持久 protection，并确认 policy 仍存在、repository 仍属于 target set 且在线；移除仓库是
   对未执行 shard 的安全停止信号。
4. 锁定并检查 shard lease 与 repository fencing token；旧 worker 即使恢复也不能提交。该锁同时
   与 cancel 更新串行化，事务内发现 cancel 时直接返回 `CANCELLED`。
5. 状态变新则 `SKIPPED_STALE/KEEP_PROTECTED`；完全一致才调用应用删除入口。

从 Try Run 点击执行时提交 `policyId + expectedPolicyRevision`。revision 已变化时返回
`409 CLEANUP_POLICY_REVISION_CONFLICT`，要求用户重新确认；未变化时创建新的 EXECUTE 父 run，快照该策略的全部
目标仓库并使用新 cutoff 完整求值。不能照着有限 Try Run 列表直接删除。

### 限额、取消与失败

- TRY_RUN 用 `scanLimitPerRepository` 限制每个仓库被检查的 subject，并同时受父 run 总硬上限；
  任一范围未扫描完即以 `SUCCEEDED_TRUNCATED` 完成。该限制与结果分页、命中数和删除数无关。
- EXECUTE 用 `deleteLimitPerRepository` 限制每个 shard 实际删除的 subject；达到上限的 shard 以
  `PARTIAL_LIMIT_REACHED` 完成，下一次手动或计划执行重新求值后继续。后续再根据运行数据决定
  是否增加 bytes/asset 级限额，不在 MVP 一次暴露多套相似配额。
- item 用短事务删除；仅允许协议 metadata 必须整体修正的有界同 family 批次，当前 npm 上限为
  100 个 version。不能把整个仓库或无界制品族放进一个事务。
- 可重试 DB/Blob/cache 故障用有上限指数退避；校验错误进入有界终态错误。
- 取消父 run 后不再 claim 新 shard；PENDING/RETRY_WAIT shard 立即终止，RUNNING shard 在当前
  短删除事务边界观察 cancel 后终止。与 cancel 并发且已经进入合法 fenced 事务的单条删除允许
  完成；此前已经提交的 subject 不会自动恢复，当前产品语义明确为不可逆。

## 格式删除适配器

当前 `CleanupExecutionService` 通过 `RepositoryContentDeletionService` 分派到既有 Browse/协议
删除能力；Docker 使用 manifest reference 删除入口，其他格式使用 format-aware Browse 删除事务。
worker 禁止直接写 cleanup 之外的协议表。统一步骤是：

1. 锁定并重新解析完整 subject，列出主内容、metadata、checksum、signature、index entry 和
   Blob 引用。
2. 删除 active 协议记录、Browse/Search 叶子和空容器，并通过既有引用/soft-delete 语义标记
   无引用 Blob 等待 GC。
3. after commit 失效 path/content/group cache，并 bump 受影响 revision/cache version。
4. 写入该格式已有的 metadata/index rebuild marker；marker 可去重并由现有 worker 接管。
5. 返回实际删除 asset 数，写入 run item 与 shard 聚合；未来 tombstone 落地后再扩展返回契约。

格式特有要求由前述矩阵和协议测试固定。例如 Maven 要重建 GA/GAV metadata；npm 要修正
packument/dist-tags；PyPI 要重建 simple index；Docker 要先删除 reference，再按 reachability
回收 manifest/layer；Terraform provider version 要保持全部平台和 checksum/signature 一致。

## 性能与容量边界

- scanner 每页批量读取 component assets、usage、NuGet 关联文件、Docker manifest/tag 和 protection；
  DAO 把大 `IN` 集合切成最多 500 个参数的后端批次，扫描 SQL 数量不再随 subject 数线性增长。
- component family 使用 keyset cursor。V40 为 MySQL 提供二进制前缀加原始 SHA-256 的可索引排序
  键，为 PostgreSQL 提供 `C` collation expression index，并补齐 unbound asset、Docker manifest
  和 protection hash 索引。MySQL DDL 使用 `INSTANT/INPLACE + LOCK=NONE` 且每步可恢复；PostgreSQL
  使用可恢复的 `CREATE INDEX CONCURRENTLY`。
- V38 对 MySQL `cleanup_policy` 增加普通字段与 `VIRTUAL active_name` 时使用 `INSTANT`，替换唯一索引
  使用 `INPLACE + LOCK=NONE`；每一步通过 `information_schema` 守卫，可在 MySQL 隐式提交后恢复执行，
  且不因增加 `STORED` 生成列复制并阻塞整张策略表。
- EXECUTE 成功 decision 随删除事务写入；Try Run 和删除前失败每 100 条批量 upsert。循环最多每
  5 秒 pulse 一次 lease/cancel，达到 delete limit 立即停止，不为未执行候选制造
  `LIMIT_REACHED` 行。
- worker 在 claim 前用 semaphore 预留槽位，节点并发默认 2、最大 32；处理和 heartbeat 使用独立
  executor，空队列按 500ms 到 10s 指数退避，避免共享 scheduler 被长任务占住或空轮询打数据库。
- npm Hosted 将同一 package family 最多 100 个 version 合并删除：packument 与 dist-tags 只读取、
  重写和失效一次；其他格式继续走原生单 subject 删除路径。
- Try Run 每仓库最多扫描 10,000 个 subject，单 run 总预算 50,000；候选批次和返回分页均有
  服务端硬上限，不允许一次装载整个仓库的 subject 集合。
- 策略与 run 列表都使用 keyset 页，默认 10 条、最大 100 条，并只多读 1 条判断下一页；run
  列表按 `id DESC` 展示，跨页使用排他的 `id < before`，不执行随页深增长的 `OFFSET` 或总数
  `COUNT`。策略页批量读取 target/schedule；run 详情一次请求返回、按 50 个 shard 组成一批，
  每个 shard 最多读取 200 条 decision。Docker manifest 关联查询和所有大 `IN` 参数都按最多
  500 个 ID 分批。
- usage 本地快照每秒只读取一个共享 revision；只有 revision 变化才重载投影。完整 usage 与
  Quartz reconciliation 通过数据库 maintenance cursor 在集群内限频，策略变更只投递可丢失的
  节点提示。usage 投影先锁共享 revision 行，再只写新增、删除或向前移动的水位；配置未变化时
  不更新投影行，也不增加 revision。
- 历史保留不再对每个候选执行相关 `COUNT`。窗口查询一次求出每个策略的保留边界，随后按主键
  分批锁定；PostgreSQL 因禁止给含窗口函数的查询直接加 `FOR UPDATE`，候选计算与锁定明确拆成
  两步。每个事务同时受 parent run 数和 run item 数两个独立上限控制。

### 本地大仓库容量验证（2026-08-05）

`scripts/perf/run-cleanup-large-repository.sh` 会在已有本地 MySQL/PostgreSQL 容器中创建独立的
`kkrepo_cleanup_perf` 数据库，应用当前全部 migration、造数、执行 `EXPLAIN ANALYZE`、执行一次
真实有界历史删除，并在成功后删掉该数据库。默认数据集为：100 万 component、110 万 asset
（100 万绑定 component、10 万未绑定）、100 个仓库、100 个策略、199 个策略绑定、5,001 个 run、
5,100 个 repository shard 和 60 万个 run item。可通过同名 `CLEANUP_PERF_*` 环境变量缩小或放大，
但默认门禁必须直接运行：

```bash
scripts/perf/run-cleanup-large-repository.sh all
```

本次在 MySQL 8.0.46（128 MiB buffer pool）和 PostgreSQL 17.10（128 MiB shared buffers）的本地
容器上完成。时间是冷/热页混合的单次观测值，不作为生产 SLO；门禁判断的是访问路径和写入上限：

| 查询 | MySQL 实测 | PostgreSQL 实测 | 验证结论 |
| --- | ---: | ---: | --- |
| component 首页 / 中段 keyset，各 1,001 条 | 6.83 / 7.54 ms | 1.53 / 1.51 ms | 两页均命中 `idx_component_cleanup_scan`，无 offset 扫描 |
| 未绑定 asset 首页 / 中段 keyset，最多 1,001 条 | 70.9 / 3.16 ms | 30.1 / 3.22 ms | 两页均命中 `idx_asset_cleanup_unbound`；PostgreSQL 使用条件表达式索引，避免仓库数据倾斜时误扫全部 NULL component |
| policy 页 / 25 个策略 targets / schedules | 0.11 / 1.20 / 0.08 ms | 0.07 / 0.57 / 0.09 ms | policy keyset 加两次批量查询，不再逐策略 N+1 |
| 100 个 shard、每 shard 前 50 条 decision 的单查询上界探针 | 46.2 ms | 39.7 ms | 每个子查询命中 `idx_cleanup_run_item_repository`；生产 DAO 和当前门禁按 50-shard 分批 |
| 5,001 个 run 的历史候选 | 23.1 ms | 15.9 ms | 使用一次窗口边界；MySQL 无 dependent subquery，PostgreSQL 无 `SubPlan` |
| 100 行 usage 投影锁定读取 | 0.07 ms | 0.16 ms | 集群限频后才执行；稳定投影由双库 contract 证明 revision 不变且不进入 DML |

Run 列表分页另用 50 万行临时数据验证首页和深页游标：MySQL 全局查询为 0.06/0.04 ms、按策略
查询为 0.06/0.15 ms；PostgreSQL 全局查询为 0.04/0.05 ms、按策略查询为 0.04/0.06 ms。两种
数据库都只返回 11 条探针行；MySQL 分别命中主键和 `(policy_id, id)`，PostgreSQL 根据策略密度
在主键反向扫描与 `(policy_id, id)` 之间选择，未出现全表扫描。该探针使用临时表，会话结束自动
删除，不写入开发数据。

写放大探针给 25 个候选 parent run 设置 500 个 item 的事务上限；两种数据库均实际删除
`500 run item + 5 parent run`，表行数差值与 JDBC 返回值一致，没有通过外键一次级联全部 2,500
个候选 item。生产默认单事务上限是 25 个 parent run 与 5,000 个 run item；最多 100 个目标仓库
的产品约束继续限制 parent 删除时的 shard 级联规模。数据库 contract 另用 1,201 个 Docker
asset ID 验证 500 参数分批路径，并覆盖稳定 usage 投影第二次同步返回未变化且共享 revision 不变。
表中的 100-shard 数字是为确认 SQL 形态上界而执行的一次性单查询结果；仓库内可重复脚本和生产
DAO 都使用最多 50 个 shard 的批次，避免动态 SQL 随策略目标数继续增长。

这组结果关闭“本分支没有真实规模造数、查询计划和写放大证据”的缺口。它不替代正式数据分布、
持续写入下 autovacuum/undo/replication lag、对象存储延迟、跨区域数据库和多小时运行的发布环境
soak；这些仍保留为上线门禁。

当前 Cleanup 已复用 Browse/API 手工删除语义，后续应把遗留 controller 内部逻辑进一步下沉为
共享 service，减少不同入口分叉。proxy subject 删除后允许重新回源；group 永远不能把 member
delete 当作实现。

## API、Admin UI 与权限

### kkRepo 原生管理 API

```text
GET               /internal/cleanup/capabilities
GET/POST         /internal/cleanup/policies
GET/PUT/DELETE   /internal/cleanup/policies/{policyId}
POST              /internal/cleanup/policies/{policyId}/runs
GET               /internal/cleanup/runs
GET               /internal/cleanup/runs/{runId}
GET               /internal/cleanup/runs/{runId}/summary
GET               /internal/cleanup/runs/{runId}/details
POST              /internal/cleanup/runs/{runId}/cancel
GET               /internal/cleanup/runs/{runId}/repositories/{runRepositoryId}/items
GET/POST          /internal/cleanup/protections
GET/PUT/DELETE    /internal/cleanup/protections/{protectionId}
```

手动入口统一通过 `POST /internal/cleanup/policies/{policyId}/runs`：

```json
{"mode":"TRY_RUN","expectedPolicyRevision":12,"scanLimitPerRepository":10000}
```

```json
{"mode":"EXECUTE","expectedPolicyRevision":12}
```

POST/PUT policy body 同时包含规则、target repository ids、execution limits 和可选 schedule；
服务端在一个事务中校验并保存整个聚合。更新携带当前 policy `revision`，执行 compare-and-set
并整体递增 revision；过期返回 `409 CLEANUP_POLICY_REVISION_CONFLICT` 和当前 revision。定时 run
依靠 `(policy_id, scheduled_for)` 幂等；手动 run 当前每次请求创建新 run，因此 UI 在收到 `202`
后只轮询返回的 run id，不自动重放 POST。通用 `Idempotency-Key` 是后续 API 增强项。

policy、run、item 和 protection 列表使用 `after=<id>&limit=<n>` 的稳定 keyset 分页，不使用数据库
offset；policy 每页最多 100 条，Admin UI 使用 25 条。cleanup 业务错误至少包含稳定
`code` 与 message，revision/protection 冲突返回当前版本信息。

轮询只调用 run summary；打开详情弹窗时调用一次 details，由服务端按 shard 批量读取前 N 条
decision，避免浏览器逐仓库请求。item 兼容端点继续提供独立 keyset 分页，返回 repository、通用 subject
identity、format-specific coordinate、时间、命中规则、保护原因、版本排名、asset count、
estimated bytes 和 adapter explanation。默认分页，不返回无上限 JSON。

### Admin UI

- Cleanup Policies：每行显示 format scope、target repository 数量、schedule、`nextRunAt`、
  最近 Try Run/执行和启用状态；允许创建多个独立策略。
- Policy Detail：在一个聚合页面编辑规则、execution limits、多选 target repositories 和该策略
  自己的 schedule，并提供“Try Run”“立即执行”两个入口。
- Schedule：在策略页填写 Quartz Cron、IANA 时区、启用状态并查看 `nextRunAt`；
  不提供跨策略共享 schedule。
- Try Run：必须设置扫描数量，显示父 run 与各仓库扫描/匹配/截断统计，并按仓库分页查看前
  50 条 item；截断状态不能只显示为普通成功。
- Runs：展示最近 25 个 run、状态和进度；Try Run 显示 `WOULD_DELETE` 汇总，执行模式显示实际删除数。详情使用弹窗展示 mode、trigger、policy revision、执行时规则与目标仓库快照、attempt/lease、候选/删除/失败数，运行中可取消。
- 持久 protection 已提供管理 API；专用 UI、导出、Repository Detail 反向入口和 Recovery 页面
  是后续管理面增强，其中 Recovery 必须等 tombstone 后端落地。

UI 不能把 unsupported criterion 或 regex 编译失败展示为“无匹配”；保存前由服务端验证。

### 权限与审计

- 当前所有 cleanup 管理 API 都要求已认证主体拥有 `nexus:*`，因此首个生产版本是明确的
  administrator-only 能力；不能通过 repository 普通 read/write 权限触发删除。
- 手动 run 保存真实 `requested_by`，定时 run 使用 `system:cleanup-scheduler`；策略、目标仓库、
  criteria revision 和 item 决策都持久化，支持事后定位。
- 细粒度的 `kkrepo:cleanup-policy:*`、按仓库 try-run/execute/schedule 权限及专门 management audit
  event 是后续权限模型增强。在这些 action 完成前不得把 cleanup UI 开放给非管理员角色。
- 未来 restore、提前释放 tombstone、解除 legal/security hold 必须使用彼此独立的 action，不能
  因为当前管理 API 是管理员专用就省略恢复能力的权限边界。

## 多副本、一致性与故障语义

- Quartz clustered JDBC JobStore 负责 Cron trigger 的跨副本唯一获取；
  `(policy_id, scheduled_for)` 唯一 fire key 是最终幂等屏障。Spring `@Scheduled` 只做
  policy schedule 到 Quartz 投影的周期 reconciliation；完整 reconciliation 通过数据库 cursor
  在集群内至少间隔 5 分钟，策略变更仍可触发低延迟的单策略投影。
- cleanup repository shard 有 heartbeat、lease expiry 和 fencing token；已有 metadata/index/Blob GC
  worker 继续使用各自原有的可接管机制。旧 cleanup owner 的提交必须因 token 不匹配失败。
- 同一 repository 同时只允许一个 EXECUTE shard 持有 cleanup lease；来自其他 policy 的 shard
  等待并在获得 lease 后重新求值。claim 查询只让同一仓库最早的可运行 shard 竞争，避免一个
  繁忙仓库的积压耗尽候选窗口；不同 repository shard 可并行运行。
- cancel 会立即终止尚未 claim 的 shard；RUNNING owner 若失联，过期 lease 仍允许被更高 fence
  接管，接管者只做取消收敛，不继续扫描或删除。策略 target 或非终态 shard 引用存在时禁止删除
  repository，应用校验与数据库 `RESTRICT` 共同覆盖并发窗口。
- 数据库时间作为 cutoff、lease 和 schedule 时间源，避免 pod 时钟差改变删除边界。
- 当前公共锁顺序为 repository lease/fence -> native component/manifest -> asset -> usage/ref；下载
  写水位先锁 asset 再 upsert usage，避免与清理形成反向锁序或丢失并发下载。
- policy、matcher 和 usage 的进程内 cache 都是可丢失热缓存，带 TTL/revision key；丢失后只
  影响性能，不影响正确性。
- DB 不可用时不执行 cleanup；usage-based policy 生效的读取也不能在 usage 写失败后继续
  返回 body。
- BlobStorage 删除失败不回滚 active metadata 删除；Blob 仍由既有 reference/soft-delete row
  表示并由 Blob GC 重试。引入 Cleanup tombstone 后再增加恢复窗口语义。
- 单个坏 subject、异常 family 或资产过多不能终止整个 policy run；item/shard 保存有界错误，
  其他 repository shard 继续，父 run 汇总为 `PARTIAL`。

## 配置、指标与告警

当前暴露的主要运行配置：

```text
kkrepo.cleanup.enabled=true
kkrepo.cleanup.scheduler.enabled=true
kkrepo.cleanup.scheduler.projection-delay-ms=1000
kkrepo.cleanup.scheduler.projection-initial-delay-ms=1000
kkrepo.cleanup.scheduler.reconcile-interval-ms=60000
kkrepo.cleanup.scheduler.reconcile-initial-delay-ms=60000
kkrepo.cleanup.scheduler.full-reconcile-min-interval-ms=300000
kkrepo.cleanup.worker.poll-delay-ms=500
kkrepo.cleanup.worker.initial-delay-ms=1000
kkrepo.cleanup.worker.batch-size=2
kkrepo.cleanup.worker.concurrency=2
kkrepo.cleanup.worker.lease-duration=2m
kkrepo.cleanup.worker.heartbeat-interval=20s
kkrepo.cleanup.worker.retry-base-delay=5s
kkrepo.cleanup.worker.retry-max-delay=5m
kkrepo.cleanup.worker.idle-base-delay=500ms
kkrepo.cleanup.worker.idle-max-delay=10s
kkrepo.cleanup.metrics-refresh=15s
kkrepo.cleanup.history.enabled=true
kkrepo.cleanup.history.retention=90d
kkrepo.cleanup.history.batch-size=25
kkrepo.cleanup.history.max-batches-per-run=10
kkrepo.cleanup.history.minimum-runs-per-policy=10
kkrepo.cleanup.history.item-batch-size=5000
kkrepo.cleanup.history.cluster-interval=55m
kkrepo.cleanup.history.cleanup-delay=1h
kkrepo.cleanup.history.initial-delay=5m
kkrepo.cleanup.usage.projection-delay-ms=60000
kkrepo.cleanup.usage.projection-hint-delay-ms=1000
kkrepo.cleanup.usage.snapshot-delay-ms=1000
kkrepo.cleanup.usage.coalescing-ttl=5m
kkrepo.cleanup.usage.safety-lag=5m
kkrepo.cleanup.usage.fail-closed=true
kkrepo.cleanup.usage.local-cache-maximum=100000
spring.quartz.job-store-type=jdbc
spring.quartz.jdbc.initialize-schema=never
spring.quartz.properties.org.quartz.jobStore.isClustered=true
spring.quartz.properties.org.quartz.scheduler.instanceId=AUTO
```

新策略和新 schedule 都默认暂停，因此 Quartz 可以默认启动而不会自动清理任何仓库；
`kkrepo.cleanup.enabled=false` 停止 shard worker 并阻止 Quartz 投影启用，
`kkrepo.cleanup.scheduler.enabled=false` 可仅停止新的 Cron trigger。每仓库默认/最大扫描量
1,000/10,000、单次 Try Run 总硬上限 50,000、每仓库默认/最大删除量 100/1,000 当前是服务端
常量；worker batch 有上限，Try Run 预算在父 run 行上串行预留，重试沿用原 reservation。
`safety-lag` 实际值不会小于 `coalescing-ttl`。关闭 cleanup 后已存在的持久 run 保留，重新启用
后继续处理；不会把停机变成任务丢失。

当前已落地的低基数指标包括：

- `kkrepo_cleanup_usage_tracked_repositories`
- `kkrepo_cleanup_usage_updates_total{outcome}`，区分 written/coalesced/missing/fail_closed/failed_open
- `kkrepo_cleanup_repository_shards_total{outcome,mode}`
- `kkrepo_cleanup_subjects_total{kind,mode}`，kind 为 scanned/matched/deleted/failed
- `kkrepo_cleanup_fence_rejections_total`
- `kkrepo_cleanup_lease_takeovers_total`
- `kkrepo_cleanup_cursor_conflicts_total`
- `kkrepo_cleanup_run_duration_seconds{outcome,mode,trigger}`
- `kkrepo_cleanup_history_deleted_runs_total`、`kkrepo_cleanup_history_deleted_items_total` 与
  `kkrepo_cleanup_history_retention_failures_total`
- `kkrepo_cleanup_pending_shards`、`kkrepo_cleanup_retry_waiting_shards`、
  `kkrepo_cleanup_running_shards`、`kkrepo_cleanup_expired_running_leases` 与
  `kkrepo_cleanup_oldest_outstanding_age_seconds`

后续容量与 SLO 看板还应补充：

- `kkrepo_cleanup_items_total{format,subject_kind,decision,outcome}`
- `kkrepo_cleanup_try_runs_total{format,truncated}`
- `kkrepo_cleanup_bytes_total{format,phase}`
- tombstone count/bytes、各格式 metadata rebuild backlog 和 Blob GC backlog

coordinate、pattern、policy note、user 和 external id 不能作为 metric label。告警覆盖 usage
write failure、Try Run 频繁触及硬上限、反复 takeover、FAILED/PARTIAL 增长、格式索引重建 backlog
和 Blob GC backlog。

## 验证状态与测试计划

### 格式覆盖门禁

- `CleanupPolicyCapabilitiesTest` 遍历 `RepositoryFormat.values()`，要求每个当前 format 明确暴露
  Try Run、last-download 和 execute capability；retain 只对已有官方比较器的格式开放。
- `CleanupRepositoryContentDeletionServiceTest` 固化 Docker manifest 与非 Docker 应用删除分派，
  `CleanupExecutionServiceTest` 固化 fence、cancel、content token、usage revision、protection 复检，
  并要求 EXECUTE decision 在删除事务返回前持久化，审计失败不能返回删除成功。
- `scripts/ci/run-client-e2e.sh` 在现有格式客户端 fixture 后统一创建 policy，要求 Try Run 精确成功、
  Execute 至少删除一个 subject 且 failed=0，并保存独立 JSON 报告。增加新格式时必须补该 fixture。

### 管理 API 与规则引擎

- service/unit 与 Admin 静态 contract 覆盖 policy aggregate、target repositories、schedule、
  Try Run、execute、cancel、protection 和页面轮询主路径。
- 覆盖 policyId 稳定性、多仓库 target set、revision compare-and-set、keyset pagination、
  capability violation 和稳定 cleanup error code。
- 覆盖 aggregate PUT 的原子性：规则、target set 或 schedule 任一校验/写入失败时全部回滚，
  成功时 policy revision 只递增一次。
- 覆盖多个策略选择同一仓库并设置不同 schedule、策略删除聚合清理、manual run 不移动
  `nextRunAt`，以及 schedule fire 快照全部目标仓库。
- 固化 glob/regex、多个时间条件 AND、策略间相互独立、同格式多仓库校验和不支持 retain 的
  capability 拒绝。
- Try Run 验证每仓库 scan limit、服务端总硬上限、未命中也计数、不完整 family 不产生删除决策
  和 `SUCCEEDED_TRUNCATED`。
- API 与 Admin UI 只验证 kkRepo canonical schema；测试中不维护第三方 DTO fixture。

### 单元与数据库 contract

- canonical policy schema、capability intersection、glob/regex 和 rule explanation。
- 每种格式 comparator/family/subject mapping 的 fixture 和 property-based 测试。
- MySQL/PostgreSQL contract：数据库时间、usage 单调 upsert、大小写敏感 family keyset、
  claim/skip locked、lease/fence、
  `(policy_id, scheduled_for)` unique fire、parent/shard resume、scan budget reservation、repository
  execute lease、繁忙仓库不饿死其他仓库、取消后的 owner-loss takeover、repository 删除引用保护
  以及 retry 终态；同时覆盖 shard 游标快照、takeover 后续用、原子 CAS 推进和有界历史回收。
- upload/cache-fill/download/cleanup race：下载写水位先锁 asset；content token 或 usage revision
  变化必须 skip；取消与删除事务串行化后 shard 必须可终止。
- 双 worker claim、crash/takeover、旧 fencing owner 心跳/完成失败由 JDBC contract 覆盖；
  `DatabaseServerSmokeTest` 用两个 Spring context 验证共享 policy、异步 Try Run/Execute 和 Quartz fire。
- 双数据库百万级本地造数、关键查询计划和有界历史写放大由
  `scripts/perf/run-cleanup-large-repository.sh` 验证；真实生产分布的长期 soak、跨区域高延迟、
  持续写入和超大 run history 仍属于上线环境门禁，不由本地探针或单元测试替代。

### 协议与真实客户端

当前 CI 脚本已为每种已有 fixture 的格式注册一个可精确匹配的 cleanup subject，并实现以下门禁：

1. 用真实客户端发布/下载 fixture，再创建带 NAME/path glob 和发布时间条件的精确策略。
2. Try Run 必须以完整 `SUCCEEDED` 终态结束且至少命中一个 subject。
3. Execute 必须以 `SUCCEEDED` 终态结束、至少删除一个 subject 且 `failedSubjects=0`，并保存
   policy/Try Run/Execute JSON 报告。

这个门禁脚本在本分支已完成实现，但不把未在当前本地环境实际跑完的客户端矩阵写成已通过。
发布环境还必须扩展并执行以下复合场景：

1. 每种格式至少两个 package family、多个版本、pre-release、checksum/signature/metadata，并验证
   每个 family 独立 retain。
2. direct、group、proxy first fetch/cache hit/revalidation 的 usage 正确归属 source subject。
3. 清理完整 subject 后，真实客户端不会看到半个版本或索引指向 404。
4. Try Run 后新下载、重发、tag move、添加 hold 时 execute 为 stale/protected。
5. 删除经过现有应用入口后，metadata/index/cache 与 Blob 引用语义保持原有一致性。
6. MySQL/PostgreSQL 双副本下 active worker 丢失后，另一副本接管且旧 fence 不能提交。

Maven 多模块 fixture 覆盖多个不同版本的 `jackson-*` artifactId；其他格式继续使用官方客户端
（npm/pip/cargo/dart/composer/go/helm/docker/nuget/gem/dnf/terraform/swift/ansible）。Raw 使用 HTTP GET
和路径规则验收。

## 落地阶段与后续边界

允许为了评审和风险控制把实现拆成多个 PR，但格式批次不是产品范围降级；不存在“Maven 完成
就算通用 Cleanup Policy 完成”。

### 已落地 0：策略与运行 schema

- 冻结 kkRepo canonical policy JSON、管理 API、matcher/path、时间、retain 和 repository type
  语义，不引入第三方兼容 DTO。
- 建立 format capability、subject/content token 和应用删除入口契约。
- 增加 Flyway migration、公共 DAO 和双数据库 contract test。
- 为 16 种格式登记清理单位、官方版本来源和删除后修复清单。

### 已落地 1：全格式安全 Try Run

- 策略聚合 CRUD、多 target repositories、独立 schedule 配置、可选的单个 glob/regex pattern、
  发布时间、usage、protection 和受 capability 限制的 retain 引擎；pattern 只缩小范围，至少还必须有
  一项发布时间、最后下载时间或 retain 删除条件。
- format-aware scanner 实现 component/asset/Docker manifest projection、usage attribution 和 capability。
- 持久 TRY_RUN parent/repository shard、每仓库 scan limit、服务端总硬上限、分页解释和 Admin UI。
- 本地双数据库百万级造数、查询计划和受限写放大已闭环；生产影子流量、长时间 soak、跨区域
  延迟与恢复压力仍作为发布门禁，不标记为本分支已完成。

### 已落地 2：全格式手动执行的生产运行基线

- 实现原生锁/content token、应用级 subject deletion、既有 metadata/index/group cache 修复。
- 实现 delete/scan 限额、取消、有界重试、指标、repository lease、持续 heartbeat 和双副本 takeover。
- claim 按仓库公平选择；取消后 owner 失联可由更高 fence 接管并收敛终态；policy/active run 引用
  阻止 repository 删除，数据库 `RESTRICT` 兜住并发竞态。
- 下载水位使用持久投影、asset-first 锁序、单调 revision、节点写合并、safety lag 和 fail closed。

### 已落地 3：集群自动调度

- 每个 policy 自有 Quartz Cron/IANA 时区、集群 JDBC JobStore、唯一 fire 和可重建调度投影。
- 新建策略的 schedule 默认暂停；修改规则、target 或限额会自动暂停。管理员必须在不再修改清理配置的
  后续 revision 中显式启用；当前代码不伪造“已经成功人工执行”的持久认证状态。
- scheduled fire 幂等、repository lease/fencing 与竞争接管由双数据库 contract 覆盖，双节点 service smoke
  覆盖共享 policy、异步 Try Run/Execute 和 Quartz fire。
- 发布流程必须在所有当前格式的真实客户端 cleanup gate 通过后，再对正式策略启用 schedule；
  这是发布门禁，不冒充为服务端已持久的认证字段。

### 已落地 4：持续扫描与运行治理

- V38 为每个策略/仓库提供跨 run 持久游标，shard 固化 revision，终态提交和游标推进原子完成；
  takeover、取消、删除限额和 oversized family 都有明确的前进/不前进语义。
- 终态 run 按保留期、每策略最小保留数和每轮最大批次自动回收；多副本用行锁和
  `SKIP LOCKED` 协调，不依赖单 JVM leader。
- 补齐 backlog、最老等待年龄、过期 lease、takeover、fence、cursor conflict、run duration、
  subject outcome 和 retention 指标，并保留全局 kill switch。

### 后续 5：恢复、权限和运维增强

- Cleanup tombstone/blob retain、restore/release、恢复冲突与到期 worker。
- 细粒度 cleanup privilege、专门 management audit、run history 冷归档/导出和容量/SLO 告警规则。
- materialized external usage provider 与具体 CMDB/Kubernetes connector。
- Maven timestamped SNAPSHOT、Docker untagged reachability、Raw path version extractor 等独立规则。
- archive/quarantine 等新的 deletion mode。

## 验收标准

本轮不可逆清理生产基线必须同时满足：

1. `RepositoryFormat.values()` 中 16 种格式都有明确 capability；执行只走 format-aware subject
   projection 与既有应用删除入口，不存在 worker 直接删协议表或 Blob 的 fallback。
2. 一个策略可选择多个目标仓库并拥有自己的 schedule；可以创建多个策略，同一仓库可被不同
   schedule 的多个策略选择。
3. Try Run、立即执行和定时执行都以 `policyId` 创建父 run，并为策略快照中的每个仓库建立 shard；
   单仓库失败不回滚其他仓库，父状态和统计可按仓库下钻。
4. Try Run 的 `scanLimitPerRepository` 对每个 repository shard 严格生效，并受服务端单次 run
   总硬上限约束；未命中 subject 也计数，达到上限明确返回截断，且不对不完整 family 给出
   删除结论。
5. 每个 hosted/proxy 仓库都能使用其 adapter 支持的时间、usage、matcher 和 retain 规则；group
   不持有独立制品，不能绑定清理策略，也绝不通过清理 member 实现隐式级联。
6. `jackson-*` 示例能对每个 Maven GA 独立保留 N；其他有版本格式也按各自 family 和
   官方版本顺序独立保留，Raw 不伪造版本语义。
7. Try Run 列出扫描范围内的 subject 和逐条原因；之后有新下载、重发或 tag move 时不会误删。
8. 每种格式一次删除都不会留下半套制品；协议 metadata/index/checksum/signature、Browse、
   Search 和 group cache 最终收敛。
9. 两副本并行调度/执行只产生一次逻辑删除；不同策略同时命中同一仓库时 EXECUTE shard 串行，
   worker 崩溃可接管，旧 worker 不能越过 fence；单个繁忙仓库的积压不会饿死其他仓库。
10. MySQL 与 PostgreSQL 得到相同 matcher、retain、usage 和边界时间结果。
11. 取消不再 claim 新 shard，RUNNING shard 能在短事务边界进入终态；已经提交的删除明确不可逆，
    owner 在取消后失联时也能由 lease takeover 收敛；Blob 继续由既有引用/soft-delete/GC 路径处理。
12. kkRepo 原生 API 的 revision、scheduled fire 幂等、keyset pagination、capability validation 和
    administrator-only 权限边界有测试，管理层不存在第二套兼容 DTO 或规则求值路径。
13. 升级后没有 NATIVE policy 会被自动创建或启用；新策略和 schedule 默认暂停。完全没有
    last-download 规则时下载热路径不写 cleanup usage，且提供全局 kill switch。
14. repository 被 policy target 或非终态 cleanup run 引用时不能删除；服务端冲突提示和数据库
    `RESTRICT` 都不能让并发删除绕过这一约束，终态历史本身不永久阻止仓库删除。
15. EXECUTE 不会因每次从仓库头部扫描而饿死后续 subject；游标在 shard 完成时原子推进，重试、
    接管、取消和删除上限不会造成跳页，oversized family 会产生明确 warning 而不是无限阻塞。
16. 终态 run 历史按配置有界回收并保留每策略最近记录；多副本回收不会删除非终态 run，关键
    backlog、lease、takeover、cursor、run duration 和 retention 状态可从低基数指标观测。

## 后续需要生产数据确认的问题

- Docker 删除 tag 与 digest reference、manifest list、多 tag reachability 和 untagged manifest
  的精确候选边界。
- Raw 是否需要可选、显式且经过 Try Run 的 path version extractor；默认仍保持无版本语义。
- npm/PyPI/Composer/Go/Helm/NuGet/RubyGems/Yum 当前 service 内的版本/索引逻辑哪些可直接抽取，
  哪些必须先补协议模块公共 helper。
- usage 写失败时默认 fail closed 对大流量 proxy 的实际可用性影响；若未来改为 durable outbox，
  必须先证明下载确认与 usage event 不会丢失或乱序回退。
- adapter 被禁用、升级失败或 capability 降级时，引用它的 policy schedule 应进入
  `PAUSED_UNSUPPORTED` 还是自动降级为 Try-Run-only；不得继续使用旧 capability 执行。

这些问题不会改变“面向所有仓库格式”和“kkRepo 原生管理面”的产品边界，但会决定 adapter
capability 与生产默认值。在协议 spike 和真实客户端测试完成前，不能用推测写死格式行为。
