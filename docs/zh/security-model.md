# 安全模型

本文说明 kkrepo 当前安全模型：认证、授权、密钥、审计日志和运维边界。

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

仓库权限判定会考虑仓库名、仓库 format、适用时的 path/content selector 信息，以及 action。

典型映射：

| 操作 | 所需权限 |
| --- | --- |
| 列出仓库或浏览 metadata | `browse` |
| 下载制品内容 | `read` |
| 上传新制品 | `add` |
| 覆盖或修改已有制品 metadata/content | `edit` |
| 删除制品内容 | `delete` |
| 管理仓库配置 | repository administration privilege |
| 管理用户、角色、realm、blob store | application/security/blob-store privilege |

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

协议客户端应继续使用各自原生认证机制和匹配的 token domain。当前协议 token domain 包含 `NpmToken`、`CargoToken`、`PubToken`、`NuGetApiKey` 和 `RubyGemsApiKey`；对应客户端协议使用 token 或 API key 时按各自协议处理，其中 Cargo、Pub 和 RubyGems 客户端通过 `Authorization` header 发送 registry/API key token。Composer 私有仓库优先使用 `COMPOSER_AUTH`/`auth.json` 的 HTTP Basic；能够显式发送 bearer 或自定义 API-key header 的 Composer/CI 场景可以使用 `GenericToken`。Terraform CLI 可把 `GenericToken` 仅放在已配置的 `modules.v1`/`providers.v1` service URL 中；生成的 archive/checksum/signature URL 会保留 credential segment，但日志、指标和上传的 CI 诊断产物必须脱敏。`GenericToken` 不作为所有包管理客户端 token 格式的通用替代。

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
