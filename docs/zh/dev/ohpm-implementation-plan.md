# OHPM 仓库实现计划

状态：阶段 1–3 的实现和验收已完成，包括普通/流式 HAR/HSP、双数据库、双实例、真实 OHPM 6.0.1、全量 server 及默认兼容性回归。详见 [协议兼容性基线](ohpm-protocol-baseline.md) 和 [Hosted 实现说明](ohpm-hosted.md)。下一步为阶段 4 的 Proxy；阶段 4–8 尚未完成。

关联议题：[OHPM 推进情况与贡献意向 #492](https://github.com/klboke/kkRepo/issues/492)。
设计依据：[OHPM / HarmonyOS 仓库开发设计说明](ohpm-repository-design.md)。

## 交付目标与贡献方式

完整交付 hosted、proxy、group、管理端、导入和真实客户端兼容性测试。默认使用现有 Access Token 能力；官方可选 Login 认证以可配置、默认关闭的能力交付。

当前使用 `feat/ohpm-support` 分支，阶段 1 已提交为 `7f5f9b17`，阶段 2 已提交为 `8ec83517`，并创建关联 #492 的 [Draft PR #493](https://github.com/klboke/kkRepo/pull/493)。后续按里程碑完成实现、验证、提交，并更新 PR 检查清单，完整验收后再转为 Ready。若维护者希望拆分 PR，可沿里程碑边界拆分；拆分开发不代表缩减原设计的最终范围。

所有新增协议行为先核对华为官方协议及真实客户端行为。OHPM 不以 Nexus 为协议参考，不直接复制 npm 的响应码、路径解析或认证行为。

## 当前基础与实现边界

- `core/RepositoryFormat` 和 `RepositoryRecipes` 负责格式、类型及可创建能力。
- 新建 `protocol-ohpm`，承载包标识、路径、metadata、发布请求、校验和错误模型。
- 在 `server/routing/ohpm` 中新增 `RepositoryProtocolHandler` 实现，接入现有 dispatcher；业务逻辑放在 `server/ohpm` 服务中。
- 复用通用 component、asset、asset_blob、blob 存储、鉴权和审计能力。npm 实现只作为工程结构参考。
- 优先复用 `persistence-jdbc` API；确有必要新增表或查询时，同时考虑 MySQL、PostgreSQL 的迁移和契约测试。现有设计以 MySQL 为主要描述，当前代码和 CI 已支持两种数据库。
- 缓存统一使用 `cache` 模块提供的抽象。共享数据库承担一致性，进程内缓存有明确 TTL、失效规则和恢复路径。
- 逐阶段开放已实现的 recipe，避免管理端能创建尚不能工作的仓库。
- 普通功能 PR 不更新 `CHANGELOG.md`，按贡献规范在 PR 描述中说明变更。

## 实施里程碑

执行顺序为 1 → 2 → 3 → 4 → 5 → 6 → 7 → 8。每阶段完成相应测试、文档和独立提交后再进入下一阶段；不将未完成验收的工作标为已交付。

| 阶段 | 当前状态 | 交付边界 |
| --- | --- | --- |
| 1. 协议基线 | 已提交 | 协议模块、请求契约、客户端基线 |
| 2. Hosted HAR | 已提交 | 发布、安装、tag、下架及跨实例事务闭环 |
| 3. HSP 与 stream | 已验收 | 双附件一致性、大包流式上传、失败清理与重试语义 |
| 4. Proxy | 待实施 | 上游代理、缓存、完整性校验及凭据保护 |
| 5. Group | 待实施 | metadata 合并、确定性的版本及双文件来源 |
| 6. 管理与浏览 | 待实施 | Admin/Browse、统一写入入口、权限及可观测性 |
| 7. 独立导入 | 待实施 | dry-run、断点恢复、幂等导入及结果报告 |
| 8. Login 与完整验收 | 待实施 | 可选签名认证、端到端矩阵及 PR 合并门槛 |

### 当前优先事项：阶段 4 Proxy

1. 以官方 Fetch Metadata 协议及公共上游响应建立 metadata 模型，保存远端下载地址并生成本地规范路径。上游 scoped 包的文件名不一定再次包含 scope，不能从远端文件名推断包身份。
2. 复用远端 HTTP 访问与重定向安全策略，支持 OHPM 原始 Authorization Token，并验证跨源跳转不携带凭据。
3. 建立 metadata/content 的持久化缓存、validator、TTL、negative cache 和 auto-block 行为；缓存必须区分上游配置与完整性值，不能在配置变更后复用错误来源。
4. 在发布有效缓存前校验 HAR/HSP；覆盖上游删除、故障、校验失败、重新验证和双实例访问，固定 stale 策略。
5. 使用受控上游完成常规测试，并用真实客户端通过 proxy 安装公共上游包；验收后开放 recipe、更新文档、回归、提交和推送。

阶段 3 验收包含 49 项 server OHPM 测试；完整 server reactor 中 server 模块共 4,100 项测试、零失败/错误，跳过一项需显式启用的存储性能测试。双数据库运行时通过审计确认大 HAR/HSP 成功使用 stream 接口，另有 PAX/稀疏文件边界、64 MiB 固定缓冲区读取、暂存失败及双附件事务回滚测试。

### 1. 协议基线、测试夹具与模块骨架

工作：

- 核对官方 Fetch Metadata、Ping、Publish、Unpublish、DistTags、可选 Login 协议。
- 固定客户端版本矩阵：至少覆盖可验证普通上传的版本、支持流式上传的版本及当前稳定版本；实际版本与获取方式在调研后记录。
- 准备真实 HAR、HSP、小包、大包、scoped package 和多版本夹具，记录来源、生成方式、校验和以及可分发条件。
- 确认名称大小写、版本合法性、编码后的 slash、版本删除后缀、下载路径、HSP 流式包结构、认证 header 和错误语义。
- 新建 `protocol-ohpm` 及依赖接入，建立路径、包标识、metadata、SSRI、错误响应等模型和测试。
- 在 `compat-test` 建立 OHPM 专用测试入口及真实客户端调用方式，区分普通 CI 和需要客户端/服务的 live 测试。

验收：

- 形成可复现的请求/响应样例和协议差异清单，明确尚待确认项。
- 纯协议测试可独立运行；覆盖编码歧义、非法路径、非法包标识和校验失败。
- 真实客户端缺失时有明确诊断；要求运行真实客户端的验收任务不能通过静默跳过而显示成功。

### 2. Hosted HAR 基础闭环

工作：

- 新增 OHPM format 和可用的 hosted recipe，接入路由、仓库权限与现有 Token 认证。
- 实现 ping、metadata、HAR 下载、普通发布、按版本/整包下架和 dist-tags。
- 实现 `OhpmHostedService` 和统一写入路径，保存 component、asset、blob 引用、metadata 与审计信息。
- 明确 package/version 唯一约束、tag 更新及发布/删除并发下的事务边界。
- 保证拒绝越权写入时不产生 blob 或 metadata；处理 blob 上传成功但事务失败的补偿。
- 支持条件请求及外部下载 URL 生成；未实现的 stream 路径保持协议要求的 `404`。

验收：

- 真实客户端完成普通 HAR 发布、安装/下载、tag 管理和下架。
- 重复版本、同包多版本并发、tag 与删除竞争、事务失败均有测试。
- 两个服务实例共享数据库与存储后，写入结果和缓存失效满足约定的一致性。

### 3. HSP、流式上传和重试语义

工作：

- 支持普通发布的 HAR/HSP 双附件、`integrity_hsp` 和 `resolved_hsp`。
- 实现 multipart 的 `pkg_stream`、`metadata`，与普通发布共享校验和持久化流程。
- 根据第一阶段确认的格式处理 HSP 流式包；限制解包路径、条目及展开大小。
- 大包使用临时文件并保证失败/取消时清理；分别校验 HAR/HSP。
- 验证客户端 stream `404` 回退与明确临时失败时的 `598`；确认重试与版本不可覆盖的交互行为。OHPM 6.0.1 普通上传实测收到 `598` 后直接失败，服务端正确性不能依赖客户端自动重试。

验收：

- 真实客户端完成大包、HSP 发布和两个文件下载，校验和一致。
- 覆盖损坏包、缺失附件、请求中断、磁盘/存储失败和重复请求。
- 上传内存占用不随完整包大小线性增长，临时文件可回收。

### 4. Proxy 仓库

工作：

- 开放 proxy recipe，接入远端 URL、认证、metadata/content TTL、negative cache、auto-block 和 stale 策略。
- 获取 metadata 并重写 HAR/HSP 下载 URL，支持远端 validator。
- 下载并校验后才能将文件作为有效缓存发布。
- 复用现有远端访问约束，校验 metadata 中外部下载地址及重定向，避免泄漏上游凭据。
- 明确 `404`、`410`、`451`、超时及服务端故障的行为和缓存失效规则。

验收：

- 使用可控上游覆盖正常访问、缓存命中/过期、上游更新、断网、校验失败和远端认证。
- 使用实际 OHPM 上游进行一次真实客户端安装验证；不依赖公共网络完成所有普通 CI 测试。

### 5. Group 仓库

工作：

- 开放 group recipe，按成员顺序合并 versions、dist-tags、time，并改写为 group 下载地址。
- 下载选择与 metadata 的成员优先级一致；相同版本的不同文件内容不能因回退而混用。
- 限制为允许的 OHPM 成员类型，拒绝写操作。
- 实现成员配置变化后的缓存失效，以及明确的成员失败处理规则。

验收：

- 覆盖版本/tag 冲突、HAR/HSP 双文件路由、成员缺包、成员失败和顺序调整。
- 重启或切换服务实例不改变成员选择规则；真实客户端可从 group 安装。

### 6. 管理端、浏览与公共能力集成

工作：

- Admin 支持三种仓库的创建、编辑和配置；Browse 展示包、版本、文件、tag、校验和及来源。
- UI/API 上传复用协议写入流程；包/版本删除走同一业务服务，维护 metadata、tag 和 blob 引用。
- 接入搜索、内容选择器/路由规则、权限、指标和审计。
- 检查现有清理、下载策略及安全扫描对新格式的默认处理；明确支持边界，避免直接修改无关功能。
- 更新中英文用户文档和兼容性矩阵。

验收：

- 管理操作与客户端操作互相可见，权限检查一致。
- 覆盖仓库生命周期、上传/删除、搜索/浏览及配置保存的测试。
- 通过反向代理时，下载链接正确且不暴露内部地址。

### 7. 独立导入能力

工作：

- 从已有 ohpm-repo 或文件目录读取 metadata、HAR/HSP，并通过统一 hosted 写入路径导入。
- 复用适合的现有任务框架，实现 dry-run、resume、校验和检查、失败报告与 `MANUAL` 阻塞项。
- 明确重复导入、版本冲突、tag 同步和源数据缺失的处理策略。
- 在管理端提供批量导入及任务结果查看；不把 OHPM 假装成 Nexus 原生迁移格式。

验收：

- dry-run 不写入目标包/版本/blob，任务计划和报告允许持久化。
- 中断恢复及重复执行不产生重复版本或破坏既有内容。
- 导入完成后，真实客户端可使用目标仓库；下载内容和源数据校验和一致。

### 8. 可选 Login 与完整验收

工作：

- 基于第一阶段确认的协议，实现默认关闭的 Login 配置入口。
- 启用时校验 publishId、timestamp、nonce 和 signature，签发有期限的 Token，复用现有身份及权限体系。
- 防重放状态跨实例共享，并覆盖过期、禁用、配置关闭和审计场景。
- 汇总前面各阶段测试，补齐 `OhpmRepositoryBlackBoxCompatibilityTest`、客户端 E2E 和必要的 CI 入口。
- 核对 runtime hints/AOT；若新增序列化或反射路径影响 native 构建，补相应验证。

验收：

- 默认 Token 模式与可选 Login 模式分别验证，失败响应符合官方协议。
- hosted/proxy/group、HAR/HSP、流式/普通上传、tag、下架、导入及管理端的验收清单全部完成。
- 数据库相关修改通过 MySQL/PostgreSQL 契约测试，跨实例场景通过。
- PR 记录实际运行的命令、版本、结果和限制，未验证项不能标记通过。

## 测试与合并门槛

每阶段随实现提交相应单元测试、服务测试和兼容性测试。真实客户端测试从 hosted 闭环阶段开始运行，不集中留到最后。

已落地的协议测试采用：

```bash
mvn -pl protocol-ohpm -am test
mvn -pl compat-test -am -Dtest=OhpmClientContractTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dkkrepo.ohpm.client=true -Dkkrepo.ohpm.expectedVersion=6.0.1 test
```

Hosted 服务及黑盒测试类落地后，追加：

```bash
mvn -pl server -am -Dtest='Ohpm*Test' -Dsurefire.failIfNoSpecifiedTests=false test
mvn -pl compat-test -am -Dtest=OhpmRepositoryBlackBoxCompatibilityTest -Dsurefire.failIfNoSpecifiedTests=false test
```

`OhpmRepositoryBlackBoxCompatibilityTest` 尚待实现，live 测试的实例地址及配置需随 Hosted 测试入口落地。客户端请求录制测试不代替真实服务端验收。合并前运行仓库要求的 server 测试和默认兼容性回归；影响 JDBC 或 native 时追加对应检查。

关键故障测试包括：鉴权失败无副作用、并发重复发布、tag/删除竞争、blob 成功但数据库失败、HAR/HSP 部分上传失败、缓存陈旧、上游文件校验失败、成员变化和导入中断恢复。

## Hosted 阶段的执行顺序

1. **先确定事务与存储边界。** 验证现有通用 DAO 能否支持 package root 行锁、首次发布竞争、版本唯一性与 tag 原子更新；分别覆盖 MySQL 和 PostgreSQL。核对通用 blob 写入流程在外层事务回滚时的补偿，避免上传成功但数据库失败后留下失控引用。只有现有接口不足时才扩展持久化接口或表。
2. **接入格式、路由和鉴权。** 增加 OHPM format、hosted recipe、server 依赖和 handler；复用现有 Token，按协议操作映射权限。先验证公开 ping、私有读写拒绝及未实现 stream 的 `404`，确认拒绝请求没有写入副作用。
3. **打通发布与读取。** 普通 HAR 请求先校验并暂存附件，再通过统一服务写入 component、asset、blob 引用和 package metadata；事务重试可重新打开暂存文件。实现 metadata、下载、条件请求和外部 URL 重写。
4. **加入 tag 与下架。** 在同一 package 事务边界内维护版本和 tag，兼容当前 DELETE body 与旧版路径选择器，拒绝歧义删除；同步处理 browse 索引、blob 引用和审计。
5. **完成实际服务验收后提交。** 真实 OHPM 6.0.1 对 kkrepo 执行发布、安装、tag 和下架；补并发、回滚、双实例共享数据库/存储测试，运行项目要求的回归，再提交阶段 2 并更新 Draft PR。

## 尚待验证与环境准备

1. 当前客户端执行基线为 OHPM 6.0.1；历史版本尚未实际执行，后续按可获取版本补充，不外推兼容性结论。
2. 官方协议与实际客户端在认证、下架路径、HSP 和 `598` 上的差异已记录在协议兼容性基线中。新增接口须继续核对官方依据。
3. package root 锁定和 blob 回滚补偿已通过双数据库服务测试；实际 OSS/S3 故障注入仍需另行验证，导入恢复状态在阶段 7 单独验证。
4. Proxy 动态下载链接、Group 版本冲突及 HAR/HSP 来源一致性在阶段 4、5 固定规则并验证。
5. 本机 JDK 25 已通过国内镜像准备，Maven 3.9 和 OHPM 6.0.1 已可运行，Docker 已启动。Hosted 测试自动创建两种数据库及共享文件测试存储的双实例，生产云存储验证单独记录。

第一阶段建立协议模块和客户端请求基线；第二阶段增加实际 kkrepo 服务、数据库事务和跨实例验证。历史客户端版本的回归结果需单独记录。
