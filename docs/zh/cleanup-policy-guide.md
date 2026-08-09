# Cleanup Policy 使用指南

本文面向 kkRepo 仓库管理员，说明如何创建清理策略、通过有界 Try Run 预览判定结果、手动执行、
启用独立定时任务，以及如何理解制品删除和存储空间回收之间的关系。实现原理和多副本语义见
[Cleanup Policy 开发设计说明](dev/cleanup-policy-design.md)。

英文版见 [Cleanup Policy Guide](../en/cleanup-policy-guide.md)。

## 能力边界

- 清理策略覆盖当前全部仓库格式，可以选择 hosted 和 proxy 仓库。Group 不拥有独立的清理对象，
  因此不能作为策略目标；需要清理 group 内容时，应显式选择实际 hosted 或 proxy member。
- 一个策略最多可以选择 100 个同格式仓库。同一仓库可以属于多个策略，每个策略都可以配置自己的
  规则和执行时间。
- 清理对象是完整的逻辑制品版本，而不是任意 blob 或文件。格式对应的删除流程还会同步维护生成的
  metadata、index 和 cache。
- 当前全部格式都支持 Try Run、手动执行和定时执行。只有已经验证协议版本比较器的 Maven、
  Cargo/Rust、Dart/Pub、Terraform、Swift Package Registry、Ansible Galaxy、Conda 和
  APT/Debian 支持
  **保留最新版本**规则。
- 实际执行不可逆。kkRepo 当前没有 Cleanup 专属恢复窗口，因此生产环境启用删除前应准备并验证
  数据库与 blob store 备份。

Conda 的一个清理 subject 是完整的 channel/subdir/name/version/build package。**保留最新版本**在每个 `(channel, subdir, name)` family 内使用 Conda VersionOrder，并保留入选 version 的全部 build。Hosted 删除会写 tombstone 并递增 channel revision，使 repodata、channeldata、Browse、Search 和 group binding 一致重建；Proxy 清理只移除本地 package cache asset，保留已验证上游 inventory 供下次回源。

APT 的一个清理 subject 是 `(distribution, component, package, version)` 及其全部
architecture asset。**保留最新版本**在每个 package family 内使用 Debian version 排序。Hosted
删除会 tombstone component 选中的全部 architecture，并且每个受影响 distribution 只同步发布
一次。新完整 snapshot 生效前继续读取上一套签名 snapshot；保留的 by-hash 历史决定已删 package
blob 何时可以进入 GC 候选。APT proxy 清理只移除本地缓存内容。

## 规则组合语义

| 规则 | 语义 |
| --- | --- |
| **名称/路径模式** | 可选的范围过滤条件。**通配符**对完整名称或页面显示的坐标/路径进行匹配，支持 `*` 和 `?`；**正则表达式**使用 kkRepo 已校验的正则匹配器。模式本身不能使制品具备删除资格。 |
| **发布时间早于（天）** | 只有格式对应的发布/更新时间水位早于“本次运行开始时间减去配置天数”时才匹配。 |
| **最后下载早于（天）** | 只有已记录下载水位，且水位早于配置时间的制品才匹配。没有下载时间的制品会被跳过。 |
| **保留最新版本** | 使用对应格式的协议版本比较器，在每个包或模块 family 内保护最新 `N` 个版本。`0` 表示不保护任何版本；没有已验证比较器的格式会禁用此字段。 |

策略至少需要一个删除条件：发布时间、最后下载时间或版本保留。名称/路径模式只缩小范围。所有已
配置的时间条件必须同时满足；即使模式和时间都匹配，位于最新保留范围内的版本也不会被删除。

例如，策略配置 `jackson-*`、发布时间 `30` 天、最后下载时间 `14` 天、保留最新 `3` 个版本时，
只有名称匹配、版本不在该 family 最新三个版本内，并且发布时间与最后下载时间都足够早的制品才会
进入候选集合。

### 最后下载水位

当前全部格式都支持最后下载规则，具体语义如下：

- 成功且已授权的外部 `GET` 会记录使用水位；`HEAD`、被拒绝的请求和内部扫描器读取不会更新水位。
- 通过 group 下载时，水位归属到实际提供制品的 hosted 或 proxy 仓库，因此 member 上的策略可以
  看到正确的使用记录。
- 一个逻辑清理对象包含多个 asset 时，任一 asset 的最新水位都会保护整个对象。
- 当仓库第一次被包含“最后下载时间”规则的策略选中时，kkRepo 自动开始跟踪。在配置的观察周期
  加 safety lag 完整经过之前，运行会报告 usage tracking 正在预热，并且不会从该仓库选出候选。
  这样可以避免在 kkRepo 尚未观察到完整使用窗口时误删旧制品。

## 创建策略

1. 使用管理员账号登录，进入 **Admin > Repository > Cleanup Policies**。
2. 在 **Policies** Tab 中点击 **Create policy**。
3. 填写唯一的策略名称并选择仓库格式。
4. 选择一个或多个仓库。选择器只显示该格式下的 hosted 和 proxy 仓库。
5. 至少配置一个删除条件；如需进一步缩小范围，再配置通配符或正则表达式。
6. 可选填写 **Run schedule** 和 IANA **Time zone**。表单会校验 Quartz Cron，并预览下两次运行时间。
7. 按需填写备注，然后点击 **Create policy**。

新策略始终以 **PAUSED** 状态创建，即使已经填写运行时间也不会自动开始删除。

常用六段 Quartz Cron 示例：

| 运行时间 | 表达式 |
| --- | --- |
| 每天 02:00 | `0 0 2 * * ?` |
| 每周日 03:00 | `0 0 3 ? * SUN` |
| 每月 1 日 01:30 | `0 30 1 1 * ?` |

时区不是单纯的显示偏好，而是定时配置的一部分。即使应用副本运行在其他时区，任务仍会按所选 IANA
时区的本地时间执行，并按该时区规则处理夏令时变化。

## 使用 Try Run 预览

每个新策略以及规则或仓库发生实质变更后，都建议先执行 Try Run：

1. 打开策略行右侧的更多操作菜单，选择 **Try Run**。
2. 设置**每仓库扫描上限**，点击 **Start Try Run**。
3. 页面会切换到 **Runs** Tab。等待进入终态后点击 **View**。
4. 检查 **Run summary**、**Would delete**、截断提示、各仓库结果和逐条判定。详情会显示本次运行
   使用的策略 revision 与仓库快照。

Try Run 不会删除制品。它从稳定起点读取当前仓库状态，并记录 `WOULD_DELETE` 或
`KEEP_PROTECTED` 判定。详情弹窗每个仓库最多展示 50 条判定，但汇总计数覆盖完整的有界扫描范围。

新策略持久化的每仓库扫描上限默认为 1,000。Try Run 弹窗可以降低该值；服务端会取本次输入与策略
扫描上限中的较小值。通过管理 API 配置的策略最多可以把上限提高到 10,000。此外，单次 Try Run
跨全部目标仓库最多扫描 50,000 个 subject。

如果扫描边界切断了一个版本 family，kkRepo 会排除这个不完整 family，不给出删除结论，并把运行
标记为截断。因此不能把 `SUCCEEDED_TRUNCATED` 理解为“整个仓库已经验证完毕”。

## 手动执行清理

完成 Try Run 检查后：

1. 打开策略的更多操作菜单，选择 **Run now**。
2. 阅读不可逆删除提示并确认。
3. 在 **Runs** Tab 查看进度，通过 **View** 检查仓库和逐条判定详情。

新策略默认每个仓库单次最多扫描 1,000 个 subject、删除 100 个 subject。删除上限对每个目标仓库
独立计算。每次实际删除前，kkRepo 都会重新检查当前内容标识、下载水位、保护状态和仓库执行权；
Try Run 的结果不会被直接复用为删除清单。

达到删除上限时，运行以 `PARTIAL_LIMIT_REACHED` 结束。可以再次手动执行，或让后续定时运行继续
处理，直到符合条件的积压被清空。

非终态运行可以请求取消。worker 观察到请求后会停止后续工作，但已经提交的删除不会回滚。

## 启用或停用定时执行

策略处于暂停状态时，已经保存的 Cron 不会执行。启用自动执行需要：

1. 完成并检查一次有代表性的 Try Run。
2. 打开策略的更多操作菜单，选择 **Enable schedule**。
3. 确认策略状态变为 **ACTIVE**，并核对列表中的下一次运行时间。

选择 **Disable schedule** 可以停止未来的定时运行，但不会取消已经入队或正在执行的 run。

修改清理规则、仓库格式、目标仓库或执行上限会自动暂停策略。应重新执行 Try Run，确认后再启用
schedule，避免已经审批过的定时任务静默应用到一组实质不同的删除规则。

## 理解运行结果

常见运行状态如下：

| 状态 | 语义 |
| --- | --- |
| `PENDING` / `RUNNING` | 持久化的仓库任务正在等待 cleanup worker，或已经被某个 worker 领取。 |
| `SUCCEEDED` | 所有目标仓库都在配置上限内完成。 |
| `SUCCEEDED_TRUNCATED` | 扫描达到上限，结果只覆盖有界范围，并且被切断的版本 family 已排除。 |
| `PARTIAL_LIMIT_REACHED` | 至少一个仓库达到删除上限，剩余候选可由后续运行继续处理。 |
| `PARTIAL` | 部分仓库或制品操作失败，其他工作已完成；应查看详情并在修复原因后重试。 |
| `FAILED` | 本次运行未能成功完成。 |
| `CANCELLED` | 已请求取消；取消前已经提交的删除仍然有效。 |

常见逐条判定包括：Try Run 命中的 `WOULD_DELETE`、已提交删除的 `DELETED`、命中有效保护的
`KEEP_PROTECTED`、执行前内容变化或已不存在时的 `SKIPPED_STALE` / `SKIPPED_MISSING`，以及应用层
删除失败时的 `FAILED`。

每次策略编辑都会产生新 revision。历史 run 会保留当时的条件、上限和仓库快照，因此即使策略后来
修改或删除，旧运行仍然可以解释。

## Blob 存储何时回收

实际执行成功后，逻辑制品和协议 metadata 会立即从仓库读取路径中移除，但 cleanup 事务不会同步
删除 OSS/S3/File 中的对象。

常规 Blob GC worker 会重新检查引用，只有不再被任何有效 asset 或其他持久引用使用时才删除对象。
默认每 30 秒轮询一次，并等待 1 小时 soft-delete grace。积压和批次上限还可能增加延迟，因此制品
已经不可见时，blob store 容量可能暂时不变；共享 blob 会保留到最后一个引用释放后再回收。

不要把 bucket lifecycle 当成清理策略的替代品，否则可能绕过 kkRepo 引用检查，删除仍在使用的对象。

## 推荐的生产上线顺序

1. 在第一次真实删除前验证数据库和 blob store 备份及恢复流程。
2. 先选择少量仓库并使用较窄的名称/路径模式。
3. 运行有界 Try Run，同时检查汇总计数和有代表性的逐条判定。
4. 手动执行一次，并用真实包管理器、Browse/Search 和生成 metadata 验证结果。
5. 理解手动执行结果后，再启用保守的定时计划。
6. 在扩大策略范围前，持续检查前几次定时运行的截断、达到上限、失败和 Blob GC 积压。

如果多个策略选择同一仓库，实际执行会按仓库串行化，但先运行的策略可能删除后一个策略原本会匹配
的内容。重叠策略应有明确责任边界，排查时以各 run 保存的快照为准。

## 常见问题排查

| 现象 | 检查项 |
| --- | --- |
| 仓库选择器中没有目标仓库 | 确认所选格式一致，并确认仓库类型是 hosted 或 proxy，而不是 group。 |
| Try Run 匹配数为 0 | 确认所有时间条件都满足、模式能匹配制品名称或显示路径、版本已经超出最新 `N` 个，并检查最后下载跟踪是否仍在预热。最后下载规则会跳过没有下载时间的制品。 |
| 策略一直是 `PAUSED` | 新策略和发生实质变更的策略都需要从操作菜单选择 **Enable schedule**；只填写 Cron 不会启用执行。 |
| 运行状态为 `SUCCEEDED_TRUNCATED` | 扫描上限截断了结果。必要时通过管理 API 提高策略扫描上限，或先缩小仓库/模式范围再得出结论。 |
| 运行状态为 `PARTIAL_LIMIT_REACHED` | 已达到删除保护上限。检查已提交判定后再次运行，或等待下一次定时执行。 |
| 运行状态为 `PARTIAL` 或 `FAILED` | 打开 **View** 检查各仓库错误。常见原因包括目标仓库离线、blob store 故障或格式 metadata 更新失败。 |
| 仓库内容已消失，但存储容量未下降 | 等待 Blob GC grace，并在[监控与可观测性指南](monitoring-observability-guide.md)中检查 Blob GC backlog 和错误。 |

运维人员可以使用 `KKREPO_CLEANUP_ENABLED=false` 作为 cleanup worker 和 schedule 的全局紧急开关；
`KKREPO_CLEANUP_SCHEDULER_ENABLED=false` 只停止新的 Cron 执行，仍允许手动 run 和 worker 工作；
`KKREPO_BLOB_GC_ENABLED=false` 会停止物理 blob 回收，但不会恢复已被 cleanup 删除的 metadata。
关闭这些开关时，持久化策略和已经入队的 run 记录仍保留在数据库中。
