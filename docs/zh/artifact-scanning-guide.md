# Artifact Scanning 使用指南

本文面向 kkRepo 部署人员、仓库管理员和安全管理员，说明如何部署扫描器、按仓库启用
扫描、查看 SBOM 与漏洞、配置策略和管理豁免。实现原理、数据模型和多副本语义见
[制品安全扫描开发设计说明](dev/security-scanning-design.md)。

英文版见 [Artifact Scanning Guide](../en/artifact-scanning-guide.md)。

## 能力边界

Artifact Scanning 使用 Syft 生成 CycloneDX SBOM，并使用 Grype 匹配已知漏洞。需要先
理解以下边界：

- 扫描是异步的。上传或代理缓存事务只提交制品与通用内容变更事件，不会在上传请求
  中调用扫描器。
- `KKREPO_SECURITY_SCANNING_ENABLED=true` 只开启 kkRepo 的部署能力、后台协调任务和
  下载策略集成，不会自动启用任何仓库。
- 仓库管理员必须在 **Admin > Security > Artifact Scanning > Repositories** 中逐个
  启用仓库。
- 扫描器是独立容器或 Pod。kkRepo 不在自身 JVM 内执行 Syft/Grype，也不需要 Docker
  socket。
- `AUDIT` 只记录策略判定，不阻断下载；只有仓库明确使用 `ENFORCE` 时，阻断判定才会
  影响下载。
- 下载热路径只读取关系数据库中已经物化的策略状态，不会同步访问扫描器或重新扫描。
- MySQL/PostgreSQL 保存候选、任务、租约、扫描结果、策略和豁免。扫描器本地卷只保存
  可重建的 Grype 漏洞数据库。

## 推荐上线顺序

生产环境建议按以下顺序上线：

1. 部署 scanner adapter，并开启 kkRepo 的部署能力，但先不启用任何仓库。
2. 确认页面显示 **Scanner Ready**，漏洞数据库版本和观测时间持续更新。
3. 选择少量仓库，以 `AUDIT`、异常状态全部 `ALLOW` 的配置试运行。
4. 等待存量回填完成，检查失败任务、partial/stale 状态、漏洞和 SBOM。
5. 建立漏洞处理和豁免审批流程。
6. 只对覆盖完整、运行稳定的仓库启用 `ENFORCE`。
7. 最后再根据业务风险决定 pending、failed、partial 是否需要 fail closed。

不要在首次启用扫描时同时开启严格阻断。存量回填、漏洞数据库初始化或扫描容量不足都
可能让大量制品暂时处于 pending 状态。

## 前置条件

- kkRepo 与 scanner adapter 应使用同一发行版本的镜像 tag。
- kkRepo 使用 MySQL 8.0 或 PostgreSQL；扫描状态由现有 Flyway migration 创建。
- 生产 kkRepo 应使用共享 OSS/S3 blob store。扫描器不需要访问 kkRepo 数据库或 blob
  store，它只通过受保护的内部 HTTP 接口接收输入。
- scanner adapter 需要可写临时目录和 Grype 数据库目录。
- 开启漏洞数据库自动更新时，scanner adapter 必须能够访问 Grype 数据库发布源。
- kkRepo 与 scanner adapter 必须配置同一份高强度 service credential。

默认 Helm 资源为 scanner adapter 请求 `500m CPU / 1 GiB`，限制
`2 CPU / 4 GiB`，并提供 `5 GiB` ephemeral-storage 上限和 `10 GiB` 漏洞数据库 PVC。
Compose 默认提供 `4 GiB` `/tmp` tmpfs。实际容量应根据最大制品、并发数和扫描耗时
压测后调整。

## Docker Compose 部署

MySQL quickstart：

```bash
export KKREPO_SECURITY_SCANNING_ENABLED=true
export KKREPO_SECURITY_SCANNING_SERVICE_CREDENTIAL="$(openssl rand -hex 32)"

docker compose \
  -f docker-compose.quickstart.yml \
  --profile security-scanning \
  up -d
```

PostgreSQL 使用同样的参数，只需替换 Compose 文件：

```bash
docker compose \
  -f docker-compose.quickstart-postgresql.yml \
  --profile security-scanning \
  up -d
```

两个条件缺一不可：

- `--profile security-scanning` 启动 scanner adapter 容器。
- `KKREPO_SECURITY_SCANNING_ENABLED=true` 启动 kkRepo 内的协调 worker 和下载策略集成。

建议把生成的 credential 保存到受保护的 `.env` 或 Secret 管理系统中。修改 credential
时必须同时滚动重启 kkRepo 和 scanner adapter。

检查容器和 scanner readiness：

```bash
docker compose \
  -f docker-compose.quickstart.yml \
  --profile security-scanning \
  ps

docker compose \
  -f docker-compose.quickstart.yml \
  --profile security-scanning \
  exec scanner \
  wget -qO- http://127.0.0.1:8080/actuator/health/readiness
```

默认访问地址：

- Admin UI：`http://127.0.0.1:19090/admin/`
- kkRepo 健康状态：`http://127.0.0.1:19091/actuator/health`
- Prometheus：`http://127.0.0.1:19091/actuator/prometheus`

如果 kkRepo 已经通过普通 quickstart 启动，补齐环境变量后重新执行带 profile 的
`docker compose up -d` 即可。仅启动 scanner 容器而不重建 kkRepo，不会改变
`KKREPO_SECURITY_SCANNING_ENABLED`。

## Helm / Kubernetes 部署

先创建独立的 scanner service credential：

```bash
kubectl create secret generic kkrepo-scanner \
  --from-literal=service-credential="$(openssl rand -hex 32)"
```

确认 chart 所需的数据库和加密 Secret 已按
[Helm chart README](../../deploy/helm/kkrepo/README.md) 创建，然后启用 chart：

```bash
helm upgrade --install kkrepo deploy/helm/kkrepo \
  --set database.type=postgresql \
  --set database.url='jdbc:postgresql://postgresql.example:5432/kkrepo' \
  --set database.username=kkrepo \
  --set securityScanning.enabled=true
```

`securityScanning.enabled=true` 会同时：

- 部署 scanner adapter StatefulSet、Service、探针和可选 NetworkPolicy。
- 把 kkRepo 的部署能力 gate 设置为 enabled。

它仍不会启用任何仓库。部署后检查：

```bash
kubectl get pods
kubectl get statefulset
kubectl logs statefulset/kkrepo-scanner
```

多 scanner 副本需要注意漏洞数据库卷：

- 每个 run 的 hash 会选择一个首选 StatefulSet ordinal 以分摊负载；catalog、match 和
  OCI 请求遇到可重试的传输、容量或可用性错误时，会依次切换到其余配置 ordinal。
  二进制输入在每次尝试时都从不可变 blob storage 重新打开。开始备用执行前，kkRepo
  会尽力定向取消失败 ordinal 上可能已经接受的执行，避免响应丢失后继续占用容量。
- 管理员和 worker 的取消请求会广播到配置的所有 ordinal，因为超时的首选请求可能
  仍在退出，同时备用副本上已经存在新的执行。
- capability/readiness 观测会在所有配置的 ordinal 间容灾；只要至少一个 adapter
  副本 ready，部署能力就保持可用。
- 使用多个 scanner 副本且需要共享持久缓存时，为
  `securityScanning.scannerDatabase.persistence.existingClaim` 提供支持
  `ReadWriteMany` 的 PVC。
- 不能提供共享卷时，关闭 scanner 数据库持久化，让每个 Pod 使用独立临时缓存。
- 不要让多个 Pod 以 `ReadWriteOnce` 卷跨节点共享同一挂载。

完整 chart 参数见 [Helm chart README](../../deploy/helm/kkrepo/README.md)。

## 进入管理页面与权限

入口为 **Admin > Security > Artifact Scanning**。

当部署能力关闭或状态尚未读取成功时，页面保持可见，但所有扫描控件会置灰。这样部署
人员和仓库管理员可以区分“当前部署未提供能力”和“某个仓库尚未启用”。

自定义角色按职责授予：

| 操作 | 权限 |
| --- | --- |
| 查看扫描页面、任务、结果和 SBOM | `nexus:security-scanning:read`，以及目标仓库的 browse 权限 |
| 新建或修订全局策略 | `nexus:security-scanning:update` |
| 配置仓库、rescan、retry、cancel | `nexus:security-scanning:update`，以及目标仓库的 repository administration 权限 |
| 创建豁免 | `nexus:security-scanning-waivers:create`，以及目标仓库的 administration 权限 |
| 删除豁免 | `nexus:security-scanning-waivers:delete`，以及目标仓库的 administration 权限 |

列表和详情只返回当前用户有权浏览的仓库数据。SBOM 下载也执行相同的仓库可见性检查。

## 按仓库启用扫描

1. 打开 **Repositories**。
2. 搜索目标仓库并点击 **configure**。
3. 勾选 **Enable scanning for this repository**。
4. 保持 **Mode = Audit only** 进行首次验证。
5. 选择结果有效期和适用的内容范围。
6. 检查 **Advanced exception handling**，首次启用建议全部保持
   **Allow download**。
7. 保存配置。

配置字段：

| 字段 | 含义 |
| --- | --- |
| Repository | 当前仓库，只读 |
| Scan profile | scanner 能力绑定，当前内置值为 `syft-grype-v1`，只读 |
| Vulnerability policy | 当前集中绑定的策略或内置 critical baseline，只读 |
| Mode | `AUDIT` 只记录；`ENFORCE` 才真正阻断 |
| Result validity | 使用策略默认值，或设置 1/7/30 天；过期结果按 pending 处理 |
| Enable scanning | 当前仓库的实际业务开关 |
| Scan hosted content | 扫描 hosted 内容 |
| Scan proxy content | 扫描 proxy 缓存内容 |

hosted 仓库只显示 hosted 开关，proxy 仓库只显示 proxy 开关，group 仓库同时显示两者。
group 配置作用于其可解析的 hosted/proxy 成员内容，不扫描一个额外的“group 文件副本”。

Scan profile 和 Vulnerability policy 不允许仓库管理员手填 ID 或任意切换。创建一个新
policy 不会自动修改任何仓库；编辑已经被仓库使用的 policy 会创建新 revision，并把
这些仓库迁移到新 revision，同时保留历史判定所引用的旧 revision。

### 高级异常处理

Advanced exception handling 决定“尚无完整、当前结果”时的行为：

| 状态 | 对应设置 | `BLOCK` 时的下载结果 |
| --- | --- | --- |
| PENDING、RUNNING、STALE，或尚未物化结果 | Scan pending | HTTP `503`，带 `Retry-After: 30` |
| FAILED、CANCELLED 或 profile 不可用 | Scan failed | HTTP `503`，带 `Retry-After: 30` |
| PARTIAL 或 inventory 不完整 | Partial result | HTTP `503`，带 `Retry-After: 30` |
| 完整结果命中未豁免漏洞策略 | Vulnerability policy | HTTP `403` |

这些设置只在 `ENFORCE` 下影响下载。在 `AUDIT` 下仍会记录 shadow decision，但客户端
继续收到制品。Docker/OCI 路径返回符合 Registry 错误结构的 `UNAVAILABLE` 或
`DENIED`。

仓库和 policy 都设置有效期时，系统采用更短的有效期。无有效结果到期时间时，结果
不会仅因为时间流逝变为 stale；漏洞数据库变化仍可能触发重新匹配。

## 什么内容会被扫描

扫描器只接收协议识别出的包、归档或 OCI manifest。checksum、签名、索引和普通协议
metadata 不会被送入扫描器。

| 仓库格式 | 当前扫描目标 |
| --- | --- |
| Maven | `.jar`、`.war`、`.ear`、`.zip` |
| npm | package `.tgz` |
| PyPI | `.whl`、`.tar.gz`、`.zip` |
| Go | module `.zip`，不扫描 `.info`/`.mod` |
| Helm | chart `.tgz`，不扫描 `.prov` |
| Cargo | `.crate` |
| Pub | package `.tar.gz`/`.tgz` |
| Composer | 支持的 package archive |
| Terraform | module/provider archive，不扫描 checksum/signature |
| Swift | source archive，不扫描 manifest/metadata |
| Ansible Galaxy | collection `.tar.gz` |
| Docker/OCI | image manifest/index 解析出的镜像；不把单独 layer/metadata 当成制品扫描 |
| NuGet | `.nupkg`，不扫描 `.snupkg` |
| RubyGems | `.gem` |
| Yum | `.rpm`，不扫描 `repodata` |
| Raw | `.zip`、`.tar`、`.tar.gz`、`.tgz`、`.jar`、`.war`、`.ear`、`.whl`、`.crate`、`.gem`、`.nupkg`、`.rpm` |

超过 profile 输入限制的制品会标记为失败，而不是显示为 clean。内置 profile 默认
最大输入为 `1 GiB`；adapter 默认硬上限为 `2 GiB`，实际生效值取更严格的限制。

内置 OCI profile 默认要求 `linux/amd64`。多平台镜像只有在要求的平台完成时才能得到
完整结果；只有明确的平台解析不存在错误才会形成 partial 结果。registry 传输、
授权服务和 5xx 错误保持可重试，不能记录成缺失平台。

## 扫描何时发生

- 新增或替换制品后，事务提交的持久内容事件会自动生成候选和扫描任务。默认 worker
  轮询间隔约 1 秒，不是每分钟全库扫描。
- 首次启用仓库或扩大 hosted/proxy 范围时，会为现有制品创建持久 backfill。
- 点击 **rescan** 会创建一个高优先级、原因标记为 `MANUAL` 的新任务。
- scanner engine 或漏洞数据库 revision 变化时，已保存 SBOM 可以进入重新匹配流程；
  不需要再次读取和 catalog 未变化的制品。
- policy revision、结果有效期或 waiver 变化会触发 policy-only 重算；不需要重新扫描
  制品字节。
- 漏洞库重匹配与策略重算使用数据库行锁保护的共享游标；有界批次会越过仍在处理的
  早期任务，并在多副本下公平轮转仓库策略上下文。
- 所有任务、租约和重试状态都在共享数据库中，多 kkRepo 副本可以安全接管。

## 页面使用说明

所有列表支持搜索、前后翻页和 10/15/25/50/100 行 page size。默认每页 10 行。

每个页签都有可分享的 hash 路由。Overview 使用
`/#admin/security/artifact-scanning`，其余页签在后面追加 `/tasks`、`/findings`、
`/repositories`、`/policies` 或 `/waivers`。直接打开链接、刷新页面以及浏览器
前进/后退都会恢复对应页签。

### Overview

顶部展示：

- Scanner：`Ready`、`Degraded` 或 `Disabled`。
- Vulnerability DB：当前用于漏洞匹配的数据库版本。
- Candidate backlog、Pending、Running、Failed。
- Complete assets、Partial/stale、Policy blocks。
- Critical/high finding 数量。

Vulnerability DB 的版本值由 scanner adapter 执行
`grype db status --output json` 获取。adapter 优先使用 checksum、digest 或 revision；
没有这些字段时使用 built 时间或 schema version，因此当前界面可能显示 ISO 时间。
它不是 kkRepo 关系数据库的 schema/Flyway 版本。

下方 Runs 表展示完成的扫描 run、完整性、finding 数量和完成时间。点击 **download**
下载受鉴权保护的 CycloneDX JSON SBOM。

### Tasks

Tasks 展示任务 stage、触发原因、状态、尝试次数、lease 和错误码。

- `PENDING` / `RETRY_WAIT` / `RUNNING`：可 cancel。
- `FAILED` / `CANCELLED`：可 retry。
- 有 asset 的任务：可 rescan。

容量不足、临时网络失败和 scanner `429/502/503/504` 会进入有上限的自动重试。默认
最多尝试 5 次，退避上限 30 分钟。人工 retry 只适用于失败或已取消任务。

### Findings

列表展示 severity、Advisory、仓库、package、installed/fixed version 和 waiver 状态。

- 点击 Advisory 在新标签页打开扫描结果提供的主公告 URL。
- 点击 **view** 查看 title、PURL、CVSS、aliases、source、locations、scan run 和
  waiver coverage。
- 点击 **waive** 为 finding 关联的某个仓库制品创建豁免。
- 已全部覆盖时按钮变为不可点击的 **waived**；只覆盖部分目标时显示
  **waive remaining**。

Finding 表示 scanner 数据库在某次 run 中的匹配结果。`0 findings` 只有在 run
完整、scanner 数据库有效且目标适用时才可以解释为“当前未匹配到已知漏洞”。

### Repositories

Repositories 展示每个可见仓库的 Enabled/Disabled、format、type、profile、policy
和 mode。这里是仓库扫描业务开关的唯一 UI 入口。

### Policies

Policies 用于新建和修订集中管理的漏洞策略：

| 字段 | 含义 |
| --- | --- |
| Block severity | 阻断该级别及以上的未豁免 finding |
| Result validity | No expiry，或 1/7/30 天 |
| Only block fixable findings | 只有存在 fixed version 的 finding 才参与阻断 |
| Block unknown severity | UNKNOWN 也参与阻断 |
| Require complete inventory | SBOM inventory 不完整时按 partial 处理 |

编辑 policy 不会覆盖旧行，而是创建新 revision。历史 run 和判定继续引用原 revision，
已经绑定该 policy 的仓库迁移到新 revision。

### Waivers

新建 waiver 必须从 Findings 的 **waive** 发起：

1. 选择 finding 实际关联的 Repository artifact。
2. 选择 1、7、30、90 天或 **Never expires**。
3. 填写必填的 Reason。
4. 创建后返回 Findings，状态立即刷新。

Waivers 页签用于查看 Active/Expired、scope、仓库、制品、exception、审批人、reason
和到期时间，也可删除 waiver。无期限 waiver 必须定期复核；删除或到期后，系统会重新
计算关联制品的策略状态。

同一个仓库制品已经被有效 waiver 覆盖时，服务端会拒绝重复创建，而不只依赖前端按钮
置灰。

## 常用配置

### kkRepo

| 环境变量 | 默认值 | 用途 |
| --- | ---: | --- |
| `KKREPO_SECURITY_SCANNING_ENABLED` | `false` | 部署能力 gate |
| `KKREPO_SECURITY_SCANNING_ADAPTER_BASE_URL` | `http://scanner:8080` | 单 adapter 内部地址，Compose 使用，也是列表为空时的回退值 |
| `KKREPO_SECURITY_SCANNING_ADAPTER_BASE_URLS` | 空 | 逗号分隔的稳定 adapter 地址；配置后覆盖单地址，并启用按 run 选择首选副本、可重试执行容灾和取消广播 |
| `KKREPO_SECURITY_SCANNING_SERVICE_CREDENTIAL` | 启用扫描时必填 | kkRepo 调用 adapter 的共享凭据；启用扫描后为空会拒绝启动 |
| `KKREPO_SECURITY_SCANNING_OCI_REGISTRY_URL` | `http://kkrepo:8080` | scanner 拉取精确 OCI digest 时访问的 kkRepo 地址 |
| `KKREPO_DOCKER_AUTH_TOKEN_CLEANUP_INTERVAL_MS` | `60000` | 过期 Docker/scanner bearer token 的清理间隔；不受 upload cleanup 开关影响 |
| `KKREPO_DOCKER_AUTH_TOKEN_CLEANUP_BATCH_SIZE` | `256` | 单个短事务最多领取并删除的过期 bearer token 数量 |
| `KKREPO_DOCKER_AUTH_TOKEN_CLEANUP_MAX_ITEMS_PER_RUN` | `4096` | 每个副本每轮最多删除的过期 bearer token 数量；满批次会持续清理，直到达到上限或短批次表明积压已排空 |
| `KKREPO_SECURITY_SCANNING_DATABASE_MAX_AGE` | `48h` | 漏洞数据库最大允许运维年龄 |
| `KKREPO_SECURITY_SCANNING_OBSERVATION_MAX_AGE` | `2m` | scanner snapshot 最大观测年龄 |
| `KKREPO_SECURITY_SCANNING_MAX_RESPONSE_BYTES` | `67108864` | kkRepo 接收 adapter JSON 响应的上限，包含原始文档 Base64、JSON 字段和投影 |
| `KKREPO_SECURITY_SCANNING_MAX_RESPONSE_TOKENS` | `262144` | 解码单个 adapter 响应时强制执行的 JSON token 总量上限 |
| `KKREPO_SECURITY_SCANNING_RESPONSE_MEMORY_BUDGET_BYTES` | `268435456` | 根据 byte/token 上限推导的进程内准入预算；必须至少容纳一个有界响应，且不得超过 JVM 最大堆的一半 |
| `KKREPO_SECURITY_SCANNING_WORKER_BATCH_SIZE` | `4` | 每轮任务领取上限 |
| `KKREPO_SECURITY_SCANNING_WORKER_MAX_ATTEMPTS` | `5` | 自动尝试上限 |
| `KKREPO_SECURITY_SCANNING_METRICS_COUNT_LIMIT` | `10000` | 指标聚合饱和值，避免无界 count |
| `KKREPO_SECURITY_SCANNING_TERMINAL_TASK_RETENTION_DAYS` | `30` | 终态 task 保留天数 |
| `KKREPO_SECURITY_SCANNING_RESULT_RETENTION_DAYS` | `90` | 无引用历史结果保留天数 |

所有 kkRepo 副本必须使用一致的 enabled、有序 adapter URL 列表、service credential
和 OCI registry URL。

### Scanner adapter

| 环境变量 | 应用默认值 | 用途 |
| --- | ---: | --- |
| `KKREPO_SCANNER_SERVICE_CREDENTIAL` | 必填 | 必须与 kkRepo credential 相同；为空时 adapter 拒绝启动 |
| `KKREPO_SCANNER_DB_AUTO_UPDATE` | `false` | 自动更新漏洞数据库；Compose/Helm 模板默认设为 `true` |
| `KKREPO_SCANNER_DB_DIRECTORY` | `/var/lib/kkrepo-scanner/grype` | Grype 数据库目录 |
| `KKREPO_SCANNER_DB_UPDATE_INTERVAL` | `6h` | 目标更新间隔 |
| `KKREPO_SCANNER_DB_UPDATE_CHECK_INTERVAL` | `1m` | 更新资格检查间隔 |
| `KKREPO_SCANNER_MAX_CONCURRENT_SCANS` | `2` | 单 Pod 并发扫描上限 |
| `KKREPO_SCANNER_MAX_QUEUED_SCANS` | `4` | 单 Pod 等待队列上限 |
| `KKREPO_SCANNER_ADMISSION_TIMEOUT` | `1s` | 等待容量的时间 |
| `KKREPO_SCANNER_RETRY_AFTER_SECONDS` | `5` | 容量拒绝时的重试提示 |
| `KKREPO_SCANNER_MAX_INPUT_BYTES` | `2147483648` | adapter 输入硬上限 |
| `KKREPO_SCANNER_MAX_OUTPUT_BYTES` | `16777216` | 单份原始 SBOM/report 上限；OCI 同时用作平台原始文档总量和合并 SBOM 上限 |

完整低级参数见
[scanner adapter application.yml](../../scanner-adapter/src/main/resources/application.yml)。

`MAX_RESPONSE_BYTES` 是传输 JSON envelope 上限，不等同于 scanner 原始输出上限。
kkRepo 直接从有界流解析 envelope，不再额外保留一份完整响应字节数组；解析过程中还会
强制执行 token、嵌套深度、字段名、字符串、component/finding 投影、嵌套列表和属性数量
上限。adapter 返回的任意 `summary` 对象不会被物化，内嵌原始 SBOM/report 也改用 token
流校验 schema，不再构造第二份 JSON tree。component 与 finding 投影分别最多 4096 和
2048 条；engine 原始结果超过上限时仍保留不可变原始文档，但策略计算会明确标记为
partial。

共享响应内存预算按受约束的 envelope 推导单任务预留：
`3 * MAX_RESPONSE_BYTES + 256 * MAX_RESPONSE_TOKENS`。byte 项覆盖 UTF-8/Base64 临时缓冲
和文档防御性复制，token 项覆盖解码后的 record、集合、引用和标量；预算租约一直持有到
校验与持久化结束。若提高 `KKREPO_SCANNER_MAX_OUTPUT_BYTES`，必须同步为 Base64 膨胀和
投影提高 envelope，并一起提高内存预算和 JVM 堆。预算必须至少容纳一个推导后的预留，
且不得超过 JVM 最大堆的一半。

## 监控与告警

kkRepo 在 management port 的 `/actuator/prometheus` 暴露以下关键指标：

| 指标 | 建议用途 |
| --- | --- |
| `kkrepo_security_scan_scanner_ready` | scanner 是否 ready |
| `kkrepo_security_scan_database_age_seconds` | 漏洞数据库年龄 |
| `kkrepo_security_scan_artifact_event_backlog` | 未处理内容事件数量 |
| `kkrepo_security_scan_artifact_event_oldest_age_seconds` | 最老内容事件年龄 |
| `kkrepo_security_scan_backlog` | 待处理/等待重试任务 |
| `kkrepo_security_scan_oldest_age_seconds` | 最老待处理任务年龄 |
| `kkrepo_security_scan_running` | 当前有效任务租约 |
| `kkrepo_security_scan_failures` | 终态失败任务 |
| `kkrepo_security_scan_partial` | partial asset 数 |
| `kkrepo_security_scan_findings` | critical/high finding 聚合 |
| `kkrepo_security_scan_tasks_total` | 按 format/stage/reason/outcome 的任务结果 |
| `kkrepo_security_policy_decisions_total` | allow/block/shadow 判定 |
| `kkrepo_security_policy_evaluation_duration_seconds` | 下载策略数据库判定耗时 |

scanner adapter 自身还暴露 active、queued、admission rejected 和数据库更新指标。

至少为以下情况告警：

- scanner 长时间 degraded。
- 漏洞数据库年龄超过 `KKREPO_SECURITY_SCANNING_DATABASE_MAX_AGE`。
- oldest event/task age 持续增长。
- terminal failure 持续增长。
- enforce 仓库长期存在大量 pending/partial block。

## 故障排查

| 现象 | 检查项 |
| --- | --- |
| 整个页面置灰 | 确认 scanner profile 已启动、`KKREPO_SECURITY_SCANNING_ENABLED=true`，并重启了所有 kkRepo 副本 |
| Scanner 显示 Degraded | 检查 adapter 日志、service credential、API 版本、Syft/Grype、漏洞数据库更新时间和网络 |
| 仓库 Enabled 但没有任务 | 检查内容范围、制品是否属于支持的扫描目标、backfill/candidate backlog 和仓库权限 |
| 大量 `RETRY_WAIT` | 检查 scanner 并发/队列、CPU/内存、临时空间和网络；adapter 容量不足会返回可重试 `429` |
| 任务最终 FAILED | 在 Tasks 查看 error code/summary；修复原因后点击 retry 或针对 asset rescan |
| Findings 有漏洞但下载未阻断 | 检查仓库是否为 `AUDIT`、policy threshold、fixable 设置和有效 waiver |
| 下载返回 `503` | pending/failed/partial 被配置为 `BLOCK`；等待任务完成或先恢复为 `ALLOW` |
| 下载返回 `403` | 完整结果命中未豁免漏洞策略；升级制品、调整策略或按审批流程创建 waiver |
| OCI 扫描失败 | 确认 `KKREPO_SECURITY_SCANNING_OCI_REGISTRY_URL` 可从 scanner 访问，credential 一致，要求的平台存在 |
| Vulnerability DB 过旧 | 检查自动更新、scanner HTTPS 出站、数据库卷权限和可用空间 |
| SBOM 下载失败 | 检查用户 browse/read 权限、SBOM blob 引用和底层 blob store |

查看日志时不要记录 service credential、临时 registry token、制品签名 URL 或完整敏感
路径。

## 关闭与回滚

- 在 Repositories 中关闭某个仓库，只停止该仓库后续扫描和策略应用；历史 run、finding
  和 waiver 按保留策略继续存在。
- 把 `KKREPO_SECURITY_SCANNING_ENABLED=false` 并滚动重启所有 kkRepo 副本后，协调
  worker 停止，下载策略直接放行，Admin UI 控件置灰；已有仓库配置和历史结果不会被
  删除。
- 全局 gate 关闭后再停止 scanner adapter，避免仍在运行的 kkRepo worker持续产生
  可重试失败。
- 默认终态 task 保留 30 天，无引用历史结果保留 90 天。不要手工删除扫描表或 SBOM
  blob；由内置 retention 和通用 blob reference/GC 管理生命周期。

## 安全建议

- scanner adapter 只应暴露在内部网络，不要直接发布到互联网。
- 使用随机 service credential，并通过 Secret 注入；不要写入镜像、仓库或日志。
- 不挂载 Docker socket，不授予额外 Linux capability。
- 保持 read-only root filesystem、non-root 用户、临时目录和资源上限。
- 自动更新漏洞数据库前明确允许的 HTTPS 出站；不需要更新时关闭多余出站。
- 先运行 Audit，再逐仓库启用 Enforce；对无期限 waiver 定期审计。
- 定期备份 kkRepo 关系数据库和 blob store。scanner 漏洞数据库卷是缓存，可重建，
  不替代业务备份。

相关文档：

- [制品安全扫描开发设计说明](dev/security-scanning-design.md)
- [安全模型](security-model.md)
- [监控观测指南](monitoring-observability-guide.md)
- [生产加固指南](production-hardening.md)
- [备份恢复指南](backup-restore.md)
