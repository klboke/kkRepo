# OHPM 仓库实现计划

状态：阶段 1 的协议模型和 OHPM 6.0.1 客户端请求测试已落地，详见 [协议兼容性基线](ohpm-protocol-baseline.md)。后续服务端阶段尚未完成。

关联议题：[OHPM 推进情况与贡献意向 #492](https://github.com/klboke/kkRepo/issues/492)。
设计依据：[OHPM / HarmonyOS 仓库开发设计说明](ohpm-repository-design.md)。

## 交付目标与贡献方式

完整交付 hosted、proxy、group、管理端、导入和真实客户端兼容性测试。默认使用现有 Access Token 能力；官方可选 Login 认证以可配置、默认关闭的能力交付。

建议使用 `feat/ohpm-support` 分支，在首个有测试的实现提交完成后创建关联 #492 的 Draft PR。按下面的里程碑组织提交和 PR 检查清单，完整验收后再转为 Ready。若维护者希望拆分 PR，可沿里程碑边界拆分；拆分开发不代表缩减原设计的最终范围。

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
- 实现客户端 stream `404` 回退与明确临时失败时的 `598`；确认重试与版本不可覆盖的交互行为。

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

新增模块后，针对性测试采用：

```bash
mvn -pl protocol-ohpm -am test
mvn -pl server -am -Dtest='Ohpm*Test' -Dsurefire.failIfNoSpecifiedTests=false test
mvn -pl compat-test -am -Dtest=OhpmRepositoryBlackBoxCompatibilityTest -Dsurefire.failIfNoSpecifiedTests=false test
```

这些命令中的 OHPM 模块和测试类尚待实现，live 测试的实例地址及客户端配置也需在第一阶段确定。合并前运行仓库要求的 server 测试和默认兼容性回归；影响 JDBC 或 native 时追加对应检查。

关键故障测试包括：鉴权失败无副作用、并发重复发布、tag/删除竞争、blob 成功但数据库失败、HAR/HSP 部分上传失败、缓存陈旧、上游文件校验失败、成员变化和导入中断恢复。

## 实施前待明确项

1. 可在本机和 CI 使用的 OHPM 客户端版本、安装方式，以及 HAR/HSP 测试夹具。
2. 官方协议与设计草案在路径、认证、HSP 流式包、响应码及重试方面是否存在差异。
3. 通用数据模型能否满足 package 锁定、tag 原子更新及导入恢复；完成验证后再决定是否新增表。
4. Proxy 的动态下载链接与 group 冲突场景如何保持 metadata 和实际内容一致。
5. Docker 当前未启动；服务集成测试开始前准备数据库、存储及两个服务实例。

第一阶段已建立协议模块编译、单元测试及 OHPM 6.0.1 客户端请求基线。下一阶段实现 hosted 服务后，开始验证真实 kkrepo 实例的持久化、鉴权和跨实例一致性；历史客户端版本的回归结果需单独记录。
