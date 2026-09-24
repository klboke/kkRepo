# 安全模型

本文说明 kkrepo 当前安全模型：认证、授权、密钥、审计日志和运维边界。

内容表达式、管理页面、预览限制和权限叠加语义见[内容选择器](content-selectors.md)。

安全漏洞报告请使用 [SECURITY.md](../../SECURITY.md)。

## 目标

kkrepo 安全模型目标：

- 保持 Nexus 风格用户、角色、权限、仓库权限和常见客户端行为。
- 支持 local 用户、LDAP、OIDC、API key 和 session。
- 安全状态存储在共享 MySQL/PostgreSQL 数据库中，确保多副本部署行为一致。
- 静态加密可复用凭据和用户可见 API-key payload。
- 对安全敏感管理动作记录审计日志。

## 认证来源

支持的认证来源包括：

| 来源 | 用途 |
| --- | --- |
| Local 用户 | 内置用户，密码 hash 存储在共享数据库 |
| LDAP realm | 外部目录认证，可选 group-to-role 映射 |
| OIDC realm | 基于 issuer/JWKS/client/scope/claim 配置的 bearer/auth-code 身份集成 |
| API key 和协议 token | 仓库协议和 CI 认证 |
| HTTP session | 浏览器 UI session，通过 Spring Session JDBC 存储 |
| Anonymous subject | 显式启用时的未认证读取 subject |

认证顺序和具体行为取决于请求类型。协议客户端通常使用 Basic auth、API key 或协议原生 token flow。浏览器用户登录后使用 session。

## 共享数据库状态

安全状态存储在所选 MySQL 或 PostgreSQL 数据库：

- `security_user`
- `security_role`
- `security_privilege`
- 角色继承和用户角色关系表
- `security_realm`
- `security_anonymous_config`
- `api_key`
- `auth_ticket`
- `SPRING_SESSION`
- `security_audit_log`

这使多个副本可以共享 session、认证 ticket、用户状态和权限变更。

数据库选择不会改变授权语义，详见[数据库后端](database-backends.md)。

## 授权模型

kkrepo 使用 Nexus 风格 privilege。仓库权限动作包括：

- `browse`
- `read`
- `add`
- `edit`
- `delete`

仓库权限判定会考虑仓库名、仓库 format、适用时的 path/content selector 信息，以及 action。action 由具体协议入口和仓库操作定义，不是一个全局的“asset 不存在就是 `add`、asset 存在就是 `edit`”分类器。

对于 Nexus 兼容仓库入口，基础映射如下：

| 操作 | 所需权限 |
| --- | --- |
| 列出仓库或浏览 metadata | `browse` |
| 下载制品内容 | `read` |
| 通用仓库 `GET` 或 `HEAD` | `read` |
| 通用仓库 `POST`、`PATCH` 或 `MKCOL` | `add` |
| 通用仓库 `PUT` | `edit` |
| 通用仓库 `DELETE` | `delete` |
| Nexus Components API `POST /service/rest/v1/components` | 仓库 `edit` |
| 管理仓库配置 | repository administration privilege |
| 管理用户、角色、realm、blob store | application/security/blob-store privilege |

通用 `PUT -> edit` 映射同样适用于目标路径尚不存在的情况。这符合
Nexus 的客户端可见行为；如果改成先查询 asset 是否存在再决定权限，
普通 Maven、Raw、Helm、Yum 等使用 PUT 的客户端就会产生兼容性差异。
协议专用 publish 入口可以有不同动作。例如 Cargo、Pub、Swift 和 Ansible
Galaxy collection 的发布流程使用 `add`；Terraform 会区分新的 module/provider coordinate 或
platform 与重新发布；Docker push 则可能对 blob 和 manifest/tag 使用
不同的 `add`、`edit` 组合。Conda package `PUT` 会解析规范 package path，
新 package 需要 `add`，已存在 package 需要 `edit`；生成的 channel metadata
路径不是 package 发布入口。Ansible collection version 无论调用方是否有 `edit`
都保持不可变；持久化 import task 只允许发起者或仍有目标仓库访问权的管理员读取。

仓库权限和 hosted write policy 是两层独立检查。拥有 `edit` 权限不代表
可以绕过 `ALLOW_ONCE` 或 `DENY` 等 write policy；完成授权后，协议服务
仍会继续执行重复发布和覆盖规则。Content selector 仍会限制请求路径，
但不能用 asset 是否存在替代上面的协议专用权限映射。

新增或修改协议入口时，应在对应格式设计文档中记录权限动作映射，并
使用真实 Nexus 参考实例进行验证。

CI 用户应使用最小权限角色。除非确实是管理自动化，否则避免给自动化账号授予宽泛 `*` 权限。

## Anonymous Access

新安装会在共享数据库中持久化 anonymous read 设置，并默认保持关闭。首次创建管理员时，可以同时选择是否允许未认证用户浏览和下载仓库内容。

初始化完成后，通过管理界面的 **Security > Anonymous** 或安全 REST API 管理 anonymous access。应用配置不再提供覆盖数据库状态的开关。只有明确需要公开读取时才启用 anonymous access；对外暴露服务前，应审查哪些仓库可读。

## API Key 和 Token

kkrepo 将 API-key 兼容数据存储在共享数据库中。用户可见原始 token 以加密 payload 保护，查找使用 hash 材料而不是明文 token。

运维建议：

- 优先使用 API key 或 CI token，而不是共享密码。
- 用户角色变化或离职时轮换 token。
- 不要记录 token。
- 不要在公开 issue 中粘贴 token。
- 如果 API-key payload secret 丢失或被有意轮换，需要重新签发 token。

自定义 API-key header 是：

```text
X-Nexus-Plus-Token
```

协议客户端应继续使用各自原生认证机制和匹配的 token domain。当前协议 token domain 包含 `NpmToken`、`CargoToken`、`PubToken`、`NuGetApiKey` 和 `RubyGemsApiKey`；对应客户端协议使用 token 或 API key 时按各自协议处理，其中 Cargo、Pub 和 RubyGems 客户端通过 `Authorization` header 发送 registry/API key token。Composer 私有仓库优先使用 `COMPOSER_AUTH`/`auth.json` 的 HTTP Basic；能够显式发送 bearer 或自定义 API-key header 的 Composer/CI 场景可以使用 `GenericToken`。Terraform CLI 可把 `GenericToken` 仅放在已配置的 `modules.v1`/`providers.v1` service URL 中；生成的 archive/checksum/signature URL 会保留 credential segment，但日志、指标和上传的 CI 诊断产物必须脱敏。Ansible Galaxy 客户端可通过当前 ansible-core 的 Bearer scheme 或 Ansible 2.9 的 Token scheme 发送 `GenericToken`；仅在 Ansible route 内，kkrepo 还接受 Nexus 兼容的 Base64 `username:password`。后者只是密码传输而不是加密，不应写日志，也不应优先于 scoped token。显式错误 Ansible credential 必须返回 `401`，不能降级为 anonymous。Conda 私有 channel 通常通过 `.netrc` 提供 HTTP Basic 凭据；能够显式发送 bearer 或自定义 API-key header 的调用方可以使用 `GenericToken`。不要在 `.condarc` 中嵌入可复用密钥。`GenericToken` 不作为所有包管理客户端 token 格式的通用替代。

## 加密密钥

dev/test 以外的使用场景需要两个稳定部署密钥：

```bash
KKREPO_CREDENTIAL_SECRET=<strong-random-string>
KKREPO_API_KEY_PAYLOAD_SECRET=<strong-random-string>
```

`KKREPO_CREDENTIAL_SECRET` 保护可复用凭据，包括：

- Blob-store S3/OSS key。
- LDAP bind password。
- OIDC client secret。

`KKREPO_API_KEY_PAYLOAD_SECRET` 保护用户可见 API-key payload。

丢失这些密钥可能导致已有加密数据不可读。未经过迁移/重新加密流程直接修改密钥，可能破坏 blob-store 凭据、realm 凭据和 API key。

## LDAP

LDAP realm 配置可包含：

- LDAP URL/protocol/host/port。
- Bind DN 和 bind password。
- User base DN 和 user search filter。
- Group base DN 和 group search filter。
- 是否将 LDAP group 作为角色。

生产启用 LDAP 前，应测试 bind、user mapping 和 group mapping。LDAP bind 凭据应通过正常 realm 配置加密保存，而不是写在明文文件中。

## OIDC

OIDC 配置可包含：

- Issuer。
- JWKS URI。
- Client ID 和 client secret。
- Authorization endpoint 和 token endpoint。
- Redirect URI。
- Scope。
- Claim mapping。

请使用 HTTPS endpoint，并验证 issuer、audience/client、JWKS 设置与身份提供方一致。OIDC client secret 应视为生产凭据。

## Session 和 CSRF

浏览器 session 使用 Spring Session JDBC，并可在多副本之间共享。

生产建议：

```bash
KKREPO_SESSION_STORE_TYPE=jdbc
KKREPO_SESSION_COOKIE_SECURE=true
KKREPO_CSRF_COOKIE_SECURE=true
KKREPO_HSTS_ENABLED=true
```

只有在 HTTPS 后面才启用 secure cookie。确保反向代理正确传递 cookie 和 forwarded headers。

## Rate Limit

登录和 bootstrap flow 有限流配置：

```bash
KKREPO_LOGIN_RATE_LIMIT_PER_MINUTE=20
KKREPO_BOOTSTRAP_RATE_LIMIT_PER_MINUTE=5
```

这些限制可以降低误操作或基础滥用流量影响，但不能替代网络层限流、WAF 策略或身份提供方控制。

## 出站请求策略

Proxy 仓库会拉取上游内容。默认关闭 private-address 出站访问：

```bash
KKREPO_OUTBOUND_ALLOW_PRIVATE_ADDRESSES=false
KKREPO_OUTBOUND_ALLOWED_HOSTS=
```

只有确实需要内部上游仓库时才允许内部 host。这可以降低 proxy repository 配置错误带来的 SSRF 类风险。

直连出站请求仍由 kkrepo 在本机解析，并固定到策略允许的 IP。仓库显式配置 HTTP 或 SOCKS5
出站代理后，上游域名会交给该代理解析，确保代理 DNS 规则和基于域名的路由生效。此时应把所配置的
代理视为可信出站边界：kkrepo 默认仍会拒绝显式私网 IP 和仅限本地的域名，但无法检查代理内部 DNS
最终返回的 IP。除非业务明确需要，代理侧应拒绝 loopback、私网、link-local 和云 metadata 目标。

## 审计日志

安全敏感动作应记录到 `security_audit_log`，包括用户、角色、权限、realm、token 等管理变更。

运维建议：

- 按合规和事件响应需要保留足够长的审计日志。
- 如需集中留存，应导出或采集审计数据。
- 不要只依赖应用日志还原安全历史。

## 反向代理边界

反向代理必须：

- 按部署模型终止 HTTPS 或透传 TLS。
- 保留 `Authorization` header。
- 保留浏览器 session cookie。
- 一致设置 `X-Forwarded-*` header。
- 将管理端点限制在可信网络。
- 为制品流量设置合适的 body size 和 timeout。

错误代理配置可能导致认证失败、重定向异常或 cookie 不安全。

## 安全问题报告

普通 bug、兼容差异和文档问题可以用公开 issue。

如果问题可能导致以下影响，请私下报告：

- 认证绕过。
- 授权绕过。
- Token、凭据或 cookie 暴露。
- 仓库内容泄露。
- 权限提升。
- 远程代码执行。
- 迁移数据泄露。

详见 [SECURITY.md](../../SECURITY.md)。

## NuGet 上游 NTLM 认证

NuGet proxy 仓库支持使用 NTLM 访问要求 Windows 凭据的上游。在管理控制台的仓库配置中，将「上游认证方式」设为 **NTLM**，填写远端用户名、密码，以及可选的 NTLM 域和工作站名称。用户名也可以填写 `DOMAIN\username`；显式配置的域优先。切换前须清除已保存的 Bearer token。

对应 `/internal/repositories` 的 proxy 配置如下：

```json
{
  "remoteAuthenticationType": "ntlm",
  "remoteUsername": "service-user",
  "remotePassword": "<password>",
  "remoteNtlmDomain": "DOMAIN",
  "remoteNtlmHost": "KKREPO"
}
```

默认的 `auto` 模式保留现有 Basic/Bearer 行为。NTLM 模式收到上游挑战后才开始握手，不预先发送 Basic 密码，也不回退到 Basic。本功能用于 NuGet proxy 上游认证，不包含 Kerberos/SPNEGO 或使用 Windows 账号登录 kkRepo。私有地址仍需要出站白名单；支持 NTLM 不代表已验证所有 Azure DevOps 专有 NuGet API 行为。

密码继续使用加密存储的 `remotePassword`，API 不返回明文。Nexus NuGet 迁移会保留导出的 NTLM 类型、域和工作站；缺失或被掩码遮蔽的密码仍需由管理员重新填写。认证连接按仓库、源站、出站代理和凭据指纹隔离。各副本依据数据库配置独立建立可重建的本地连接池；凭据更新会选择新连接池，本节点更新/删除会清理旧池，空闲池按 `kkrepo.outbound-proxy.idle-ttl-ms` 到期。相同源站的重定向保留认证，跨源站重定向必须通过原有白名单检查且不携带 NTLM 凭据。

NuGet service index 发现不会向其他源站委托仓库凭据。发现的资源遵循现有出站请求的同源校验；私有地址白名单和 `allowedRedirectHosts` 均不授权跨源共享 Basic/Bearer/NTLM 凭据。如果需要认证的资源使用规范主机名，且该源站也提供 service index，可将其配置为上游地址。需要跨多个源站共享凭据的 feed 需要单独配置凭据委托功能，目前尚不支持。

当前 Apache HttpClient 依赖保留了已弃用、需显式注册的 NTLM 实现。仅 NTLM 请求启用该实现，升级依赖时必须保留 NTLMv2 握手回归测试。参考对照脚本为 `compat-test/scripts/ntlm-upstream.py`，可分别或同时连接临时 Nexus/kkRepo 实例，校验 NTLMv2 密码证明、包内容和 GET/HEAD 响应，并清理测试仓库及临时 Nexus SSRF 白名单项。指定双方可达的 `--upstream-host`；添加 `--dotnet` 可使用独立包缓存执行真实 .NET 8 restore。

## 扫描活动排序

管理控制台 → Security Scanning → Tasks / Overview（扫描运行记录）默认按实际完成时间降序显示（最新优先）。点击任务的 **Finished** 或运行记录的 **Completed** 列表头可切换升降序，箭头显示当前排序方向，与仓库列表一致。任务列表同时显示完成时间。未完成任务在两个方向都排在最后；完成时间相同时按所选方向的 ID 排序。搜索和仓库可见性过滤均在分页前执行。

管理 API 支持相同排序：

- `GET /internal/security/scanning/tasks?sort=finished_at&direction=desc&limit=25`，可附加 `status=FAILED`、`repositoryId` 或 `q`。
- `GET /internal/security/scanning/runs?sort=completed_at&direction=asc&limit=25`，可附加 `repositoryId` 或 `q`。

选择完成时间排序但省略方向时，默认 `direction=desc`。将响应中的不透明 `nextCursor` 作为下一页的 `cursor`，并保持排序、方向和过滤条件一致；游标为空表示没有下一页。修改排序或过滤条件后应从第一页重新查询。完成时间排序不使用 `after`/`nextAfter`。省略 `sort`（或使用 `sort=id&direction=asc`）时，保留原有 ID 升序和 `after`/`nextAfter` 分页，兼容旧客户端。不支持的字段、方向、格式错误或与排序不匹配的游标，以及超出 JDBC 连接支持范围的时间戳均返回 HTTP 400。

游标保存完成时间和 ID，任何服务副本都能继续分页，无需重新查询上一页的边界记录。列表反映实时数据，并非固定快照：任务重试、新完成任务或历史清理可能在浏览期间改变列表。查看最新活动时请从第一页刷新。

完成时间分页使用 V55 迁移新增的时间戳／ID 索引，并为按状态筛选的任务列表提供状态前导索引（包括指定仓库的列表）。任务分别查询已完成和未完成区间，每次最多读取该页剩余条数，服务端在同一个只读事务内完成两次读取。这样既保持未完成任务排在最后，也避免对全部历史记录进行空值排序。MySQL 使用在线索引构建，PostgreSQL 使用并发索引构建；迁移支持中断后重试。
