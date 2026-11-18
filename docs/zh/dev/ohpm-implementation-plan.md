# OHPM 仓库实现计划

状态：OHPM 实现已通过 [PR #494](https://github.com/klboke/kkRepo/pull/494) 于 2026-11-07 合并，包含 hosted/proxy/group、管理、独立导入及可选签名 Login。Full E2E 已接入既有 core 分片；具体版本、运行报告与边界见[验收说明](ohpm-acceptance.md)。当前操作入口见[使用指南](../repository-guides/ohpm.md)和[公钥登录说明](ohpm-login.md)。下文保留阶段拆分与开发记录，不代表当前 PR 或 CI 状态。

关联议题：[OHPM 推进情况与贡献意向 #492](https://github.com/klboke/kkRepo/issues/492)。
设计依据：[OHPM / HarmonyOS 仓库开发设计说明](ohpm-repository-design.md)。

## 交付目标与贡献方式

完整交付 hosted、proxy、group、管理端、导入和真实客户端兼容性测试。默认使用现有 Access Token 能力；官方可选 Login 认证以可配置、默认关闭的能力交付。

开发阶段使用 `feat/ohpm-support` 分支，阶段 1 已提交为 `7f5f9b17`，阶段 2 已提交为 `8ec83517`，阶段 3 已提交为 `b26ac81f`，阶段 4 已提交为 `9172a5d2`，阶段 5 已提交为 `54f86a5c`，阶段 6 已提交为 `fbe60c6c`，阶段 7 已提交为 `84f95c8c`，并创建关联 #492 的 [Draft PR #493](https://github.com/klboke/kkRepo/pull/493)。后续按里程碑完成实现、验证、提交，并更新 PR 检查清单，完整验收后再转为 Ready。若维护者希望拆分 PR，可沿里程碑边界拆分；拆分开发不代表缩减原设计的最终范围。

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
| 3. HSP 与 stream | 已提交 | 双附件一致性、大包流式上传、失败清理与重试语义 |
| 4. Proxy | 已提交 | 上游代理、缓存、完整性校验及凭据保护 |
| 5. Group | 已提交 | metadata 合并、确定性的版本及双文件来源 |
| 6. 管理与浏览 | 已提交 | Admin/Browse、统一写入入口、权限及可观测性 |
| 7. 独立导入 | 已提交 | dry-run、断点恢复、幂等导入及结果报告 |
| 8. Login 与完整验收 | 本地验收完成，远端 CI 待批准 | 可选签名认证、端到端矩阵及 PR 合并门槛 |

### 阶段 8：可选 Login 与完整验收

1. 核对官方 Login 和 OHPM 6.0.1 的签名原文、请求/响应及 `login_pss` 回退行为，固定兼容性测试。
2. 实现默认关闭的配置入口、公钥与用户绑定、跨实例防重放及有期限 Token；复用现有身份、权限和 Token 能力。
3. 覆盖过期、签名错误、重复 nonce、用户/密钥禁用及配置关闭，验证真实客户端签名认证后的发布闭环。
4. 补齐独立服务端黑盒验收、客户端 E2E/CI 入口及必要的 AOT/runtime hints 验证。
5. 运行最终完整回归，记录已验证版本、命令及未验证边界，更新 PR 验收清单后再判断 Ready 条件。

#### 阶段 8 执行拆解

已实现 Login 请求解析、V62 双库迁移、密钥与 nonce DAO、Token 策略、服务、管理 API/页面和中英文[签名登录说明](ohpm-login.md)，并提供独立黑盒、CI/E2E 入口和[验收说明](ohpm-acceptance.md)。编译、专项、最新客户端增量及最终完整本地回归均通过；远端 Actions 待维护者批准，不能用本地结果替代。

| 顺序 | 实现内容 | 验收证据 |
| --- | --- | --- |
| 8.1 协议契约与编译 | 固定经典 Login 的 RSA-SHA256 签名原文、响应和客户端回退契约；运行协议专项测试及 server reactor 编译 | 请求字段、重复 JSON 字段、时间边界、签名篡改测试通过；模块依赖可编译 |
| 8.2 持久化与事务 | 完成公钥绑定、唯一约束、nonce 生命周期和清理；同步迁移版本断言，补 MySQL V62 中途 DDL 恢复测试 | MySQL/PostgreSQL 契约通过；同 nonce 跨实例仅一次成功，事务回滚不遗留状态 |
| 8.3 认证与权限 | 默认关闭；签发有期限且限 OHPM 协议使用的 Token；密钥轮换、禁用及用户状态即时生效；正常操作继续校验包权限 | 过期、错误签名、重放、配置关闭均拒绝；管理接口拒绝 Login Token；既有 GenericToken 行为不变 |
| 8.4 真实客户端闭环 | 注册测试公钥，使用加密私钥运行 OHPM 6.0.1 签名发布与安装；验证并发不同 nonce 登录的 Token 复用 | `login_pss` 缺失时回退经典 Login 成功；双实例发布、读取及权限撤销通过，日志不含凭据 |
| 8.5 管理与文档 | 补公钥配置、启用/禁用及删除界面；明确配置、期限、轮换和部署行为；更新中英文文档 | 管理界面测试、接口权限和审计验证通过；文档与实际配置一致 |
| 8.6 独立验收入口 | 实现 `OhpmRepositoryBlackBoxCompatibilityTest`，接入客户端 E2E/CI，核对 AOT/runtime hints | 独立服务端黑盒实际运行；要求客户端的任务缺少客户端时明确失败；新增反射或序列化路径通过相应检查 |
| 8.7 回归与交付 | 完整 server、兼容性、双库和受影响前端回归；汇总实际版本及未验证项；提交、推送并更新 Draft PR | PR 中附可复现命令与结果；全部合并门槛满足后才转 Ready |

经典 Login 使用 `v1-<publishId>-<timestamp>-<nonce>` 作为签名原文；`login_pss` 保持未实现并返回 `404`，真实 OHPM 6.0.1 的回退测试已通过。公钥与防重放状态保存在共享数据库，Token 复用既有 `api_key` 存储并使用独立域；不能通过通用认证缓存绕过期限和密钥撤销。公钥轮换保留 publishId，同时撤销旧 Token。

阶段 8 当前专项证据：105 项协议测试、7 项持久化测试、155 项 server 测试（含 OHPM、鉴权及 Jackson runtime hints）通过；Admin JavaScript 174 项及 Maven 中的 OHPM 页面测试通过；客户端及默认 Maven/npm 兼容性 28 项通过。V62 双库迁移和 MySQL 中途 DDL 恢复已单独验证。双实例检查覆盖 nonce 竞争及大小写归一、并发 Token 复用、期限、密钥轮换/启停、用户禁用、管理接口隔离、内容选择器与权限撤销。默认继承角色在路径权限用例中显式关闭，避免其额外授权掩盖选择器的实际效果。

上述验证发现并修复了两个实现问题：签发 Token 必须使用与现有鉴权相同的 SHA-256；JDBC 读取无时区 API key 日期时间必须保持字段值，避免 MySQL 连接时区与 JVM 时区不同造成期限偏移。密钥变更按域与用户直接删除 Token，双库契约证明即使事务已有旧快照，也能撤销另一事务刚签发的 Token，而不影响 GenericToken。

补充的 45 项运行时、Login 服务及鉴权测试通过，包含审计无 Token/签名泄漏和保留 Token 域的大小写隔离。`mvn -pl server -am ... package spring-boot:process-aot` 成功；生成的 reachability metadata 包含 `KeyCommand`、`EnabledCommand`、`KeyView`、`Configuration` 的构造器/访问器，以及 nonce 清理方法。这证明 AOT 处理及绑定信息生成成功，不等于已执行 native 可执行文件。

最终可执行 jar 的独立黑盒通过五组验收，每组 8 项、无失败或跳过：OHPM 5.0.2 的 MySQL/PostgreSQL × Login 关闭/开启完整四组合，以及 OHPM 6.0.1 的 PostgreSQL/Login 开启。开启 Login 的客户端用例包含加密私钥签名发布及安装。CI 工作流已编写并通过语法检查，远端执行结果尚待确认。

#### 阶段 8 收尾核对清单

| 顺序 | 工作 | 完成条件 |
| --- | --- | --- |
| 1 | 修复 nonce 日期时间绑定：先运行已补充的跨时区契约复现，再统一按 UTC 读写无时区字段 | MySQL/PostgreSQL 在 UTC、Asia/Shanghai、America/Los_Angeles 切换下不会提前清理 nonce，过期后正常清理，重复请求仍被拒绝 |
| 2 | 修复运行时测试的持久化分层违规，使用项目允许的 API 或持久化测试设施 | `PersistencePackageBoundaryTest` 通过；保留原有 Login 运行时验证，不放宽架构规则 |
| 3 | 验证新抽取的签名夹具和黑盒真实客户端签名发布 | OHPM 5.0.2、6.0.1 均使用加密私钥完成签名发布与安装；原客户端契约继续通过；CI Login 开启分支覆盖此流程 |
| 4 | 在实际 S3 兼容存储上补故障注入 | HAR/HSP 下载字节一致；第二附件写入失败时不发布不完整版本，重试成功；保存可复现步骤和报告 |
| 5 | 顺序运行最终本地回归并核对 CI 定义 | server reactor、双库契约/迁移、OHPM 与默认兼容性、受影响前端测试通过；按修改范围复核 AOT，远端 CI 在推送后单独核对 |
| 6 | 完成文档、需求逐项审计及阶段提交 | 文档和 PR 清单与实际证据一致；提交并推送阶段 8，确认远端提交和 CI；所有合并门槛满足后再转 Ready |

清单第 1–5 项已通过本地验证。跨时区契约在修复前分别复现 MySQL/PostgreSQL 提前清理 nonce；DAO 改用 UTC `LocalDateTime` 读写后通过。运行时测试使用 `SecurityDao`，未放宽分层规则。最终 server reactor 中 server 共 4,174 项、零失败/错误，跳过一项显式启用的存储性能测试；双库迁移/契约随 reactor 通过。105 项 OHPM 协议、28 项客户端及默认 Maven/npm 兼容性、174 项 Admin JavaScript 测试通过。可执行 jar 与 AOT 处理成功。

最终 jar 分别在 MySQL 和 PostgreSQL 上通过[实际 S3 故障验收](ohpm-s3-acceptance.md)：HAR 写入成功后四次 HSP PUT 均被阻断，发布 `500`、metadata/下载 `404`、回滚后 bucket 为空；恢复后重试成功，候选下载及直接 RustFS 下载的 HAR/HSP 字节一致。脚本保留可复现报告并记录残留 blobstore 清理边界。

同一工作区的 Maven 构建依次执行，避免生成文件和测试报告相互覆盖。每项修复先跑对应专项，再集中执行最终完整回归。阶段 8 在本地验收后提交、推送及更新 PR 检查清单；远端 CI 未获批准或未通过时保持 Draft。

最终验收区分历史客户端、实际 S3 兼容存储与生产云服务：OHPM 5.0.2 和 RustFS 故障注入已验证；AWS/OSS 云服务、S3 multipart 中断、最终 GC、native 可执行文件及 HSP 设备运行未验证。文件测试存储、AOT 处理和 HSP 文件校验不能代替这些场景；相关限制在 PR 和验收说明中明确记录。

阶段 3 验收包含 49 项 server OHPM 测试；完整 server reactor 中 server 模块共 4,100 项测试、零失败/错误，跳过一项需显式启用的存储性能测试。双数据库运行时通过审计确认大 HAR/HSP 成功使用 stream 接口，另有 PAX/稀疏文件边界、64 MiB 固定缓冲区读取、暂存失败及双附件事务回滚测试。

阶段 4 增加 12 项 metadata 模型测试、17 项 Proxy 服务测试及双数据库/双实例 HTTP 验证。真实 OHPM 6.0.1 已通过 Proxy 安装受控包与公共 `@ohos/axios@2.2.15`，并断言公共包进入 kkRepo 缓存。全量 server reactor 通过，server 模块共 4,122 项测试、零失败/错误，跳过同一项可选性能测试。公共文件 GET 会跳转到 `contentcenter-drcn.dbankcdn.cn`，配置示例显式列出该下载域名。

阶段 5 增加 6 项 Group metadata 测试和 16 项 Group 服务测试，并扩展双数据库/双实例测试，验证成员顺序、HAR/HSP 同源、旧链接拒绝、实际 blob 校验及 ETag 条件请求。真实 OHPM 6.0.1 可从 Group 安装，并保留下载 URL 的来源参数。全量 server reactor 通过，server 模块共 4,140 项测试、零失败/错误，跳过同一项可选性能测试；兼容性 reactor 的 90 项 OHPM 协议测试和 27 项客户端/Maven/npm 测试通过，Browse 布局测试通过。

阶段 6 增加管理端 HAR/HSP 双文件与完整 tgz 上传、JSON5 和归档边界、包路径选择器与即时权限撤销、反向代理链接、Group 来源链接、浏览/搜索和整版本删除验证。双数据库、双实例管理上传后的真实 OHPM 6.0.1 安装通过。全量 server reactor 中 server 模块共 4,167 项测试、零失败/错误，跳过同一项可选性能测试；兼容性 reactor 的 100 项 OHPM 协议测试及 27 项客户端/Maven/npm 测试通过；Browse JavaScript 77 项测试通过。管理端生成的 `_ohpmVersion` 表示已验证的 metadata 兼容基线，不表示上传所用客户端版本。

阶段 7 增加独立导入任务与版本结果、MySQL/PostgreSQL V61 迁移，以及 Admin 目录/已有仓库 dry-run、执行/恢复和报告。双数据库、双实例覆盖检查点失败回滚、并发恢复、相同内容跳过、manifest/版本/tag 冲突、源数据变化、完整 HSP 和下载字节一致性；真实 OHPM 6.0.1 可安装导入后的包。任务计划限制为 1,000 项、单项 2 MiB 和合计 16 MiB；迁移契约验证 MySQL 中途建表后的恢复。

阶段 7 全量 server reactor 通过，server 模块仍为 4,167 项、零失败/错误及同一项可选性能测试跳过。收紧 manifest 比较后追加的 109 项 server OHPM 专项测试通过；最终兼容性 reactor 的 101 项协议测试和 27 项客户端/Maven/npm 测试通过；Admin JavaScript 171 项测试及 Flyway V61 双库版本一致性检查通过。

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

Hosted 服务专项使用：

```bash
mvn -pl server -am -Dtest='Ohpm*Test' -Dsurefire.failIfNoSpecifiedTests=false test
```

独立黑盒使用 `bash scripts/ci/run-ohpm-compat.sh blackbox`，含真实客户端使用 `bash scripts/ci/run-ohpm-compat.sh client-e2e`。先按[独立验收说明](ohpm-acceptance.md)准备可销毁的服务实例、配置地址及凭据，并固定客户端版本。脚本检查用例数量及跳过数，不能以未启用 live 测试的普通 Maven 成功代替验收。客户端请求录制测试不代替真实服务端验收。合并前运行仓库要求的 server 测试和默认兼容性回归；影响 JDBC 或 native 时追加对应检查。

关键故障测试包括：鉴权失败无副作用、并发重复发布、tag/删除竞争、blob 成功但数据库失败、HAR/HSP 部分上传失败、缓存陈旧、上游文件校验失败、成员变化和导入中断恢复。

## Hosted 阶段的执行顺序

1. **先确定事务与存储边界。** 验证现有通用 DAO 能否支持 package root 行锁、首次发布竞争、版本唯一性与 tag 原子更新；分别覆盖 MySQL 和 PostgreSQL。核对通用 blob 写入流程在外层事务回滚时的补偿，避免上传成功但数据库失败后留下失控引用。只有现有接口不足时才扩展持久化接口或表。
2. **接入格式、路由和鉴权。** 增加 OHPM format、hosted recipe、server 依赖和 handler；复用现有 Token，按协议操作映射权限。先验证公开 ping、私有读写拒绝及未实现 stream 的 `404`，确认拒绝请求没有写入副作用。
3. **打通发布与读取。** 普通 HAR 请求先校验并暂存附件，再通过统一服务写入 component、asset、blob 引用和 package metadata；事务重试可重新打开暂存文件。实现 metadata、下载、条件请求和外部 URL 重写。
4. **加入 tag 与下架。** 在同一 package 事务边界内维护版本和 tag，兼容当前 DELETE body 与旧版路径选择器，拒绝歧义删除；同步处理 browse 索引、blob 引用和审计。
5. **完成实际服务验收后提交。** 真实 OHPM 6.0.1 对 kkrepo 执行发布、安装、tag 和下架；补并发、回滚、双实例共享数据库/存储测试，运行项目要求的回归，再提交阶段 2 并更新 Draft PR。

## 尚待验证与环境准备

1. 当前客户端执行基线为 OHPM 6.0.1，历史 OHPM 5.0.2 和最新签名发布增量均已通过独立黑盒验收，不外推到其他版本。
2. 官方协议与实际客户端在认证、下架路径、HSP 和 `598` 上的差异已记录在协议兼容性基线中。新增接口须继续核对官方依据。
3. package root 锁定和 blob 回滚补偿已通过双数据库服务测试，实际 RustFS S3 故障注入也在两种数据库的最终候选上通过；生产 AWS/OSS 服务验证仍独立记录，导入恢复状态在阶段 7 验证。
4. Proxy 动态下载链接、Group 版本冲突及 HAR/HSP 来源一致性在阶段 4、5 固定规则并验证。
5. 本机 JDK 25 已通过国内镜像准备，Maven 3.9 和 OHPM 6.0.1 已可运行，Docker 已启动。Hosted 测试自动创建两种数据库及共享文件测试存储的双实例，生产云存储验证单独记录。

第一阶段建立协议模块和客户端请求基线；第二阶段增加实际 kkrepo 服务、数据库事务和跨实例验证。历史客户端版本的回归结果需单独记录。
