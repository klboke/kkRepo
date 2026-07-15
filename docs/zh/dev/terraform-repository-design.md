# Terraform Provider / Module Registry 仓库开发设计说明

本文记录 kkrepo Terraform Provider / Module Registry 仓库格式的开发设计。目标不是重新发明 Terraform Registry，而是在 HashiCorp 官方 Module Registry Protocol、Provider Registry Protocol、Remote Service Discovery、Provider Network Mirror Protocol 与 Sonatype Nexus Repository Terraform 行为之间取兼容交集，并按 kkrepo 的关系数据库 + OSS/S3 + 多副本约束落地可托管、可代理、可组合、可迁移和可观测的私有 Terraform 仓库。

## 当前支持状态

截至 2026-07-14，Terraform 第一阶段仓库能力已经落地：代码包含 `RepositoryFormat.TERRAFORM`、`terraform-hosted`、`terraform-proxy`、`terraform-group` recipe、独立 `protocol-terraform` 模块、共享 Provider revision/签名/source binding 数据模型，以及服务端、UI/API 上传、Browse/Search、迁移和兼容性测试入口。

完整实现目标覆盖：

- Terraform Module Registry Protocol 和 Provider Registry Protocol 的客户端读路径。
- 与 Nexus 一致的 `/repository/{repo}/v1/modules/...`、`/repository/{repo}/v1/providers/...` 路径布局。
- Nexus 风格的 module/provider hosted `PUT` 上传路径、Provider checksum 清单和 GPG 签名元数据。
- Terraform hosted、proxy、group 仓库，以及 Terraform CLI `0.13+` 和当前稳定版真实客户端 E2E。
- Proxy 对上游 `/.well-known/terraform.json` 的动态服务发现、metadata/download URL 重写、校验和缓存。
- Group 版本聚合、冲突来源绑定、成员健康和多副本一致性。
- URL token、HTTP Basic、匿名读取和现有 kkrepo API key / CI token 的兼容认证。
- Browse/Search、Admin UI、UI/API 上传、Nexus repository definition 与 repository data 迁移。

## 调研基线

实现前必须对照以下协议和参考行为：

- HashiCorp Terraform Remote Service Discovery Protocol。Terraform 从 `https://{hostname}/.well-known/terraform.json` 发现 `modules.v1` 和 `providers.v1` 服务基址；发现文档必须是 `200 application/json`。
- HashiCorp Terraform Module Registry Protocol。模块地址为 `hostname/namespace/name/system`；核心操作是列出版本，以及通过 `204 No Content` + `X-Terraform-Get` 返回指定版本源码位置。
- HashiCorp Terraform Provider Registry Protocol。Provider 地址为 `hostname/namespace/type`；核心操作是列出 version/platform，以及返回 archive、SHA256 清单、清单签名和 GPG 公钥的下载元数据。
- HashiCorp Terraform Provider Network Mirror Protocol。它是由 `provider_installation.network_mirror` 显式启用的静态镜像协议，不等同于 Provider Registry Protocol。Nexus Terraform 仓库使用 registry service 路径，第一阶段不以 network mirror JSON 代替 registry protocol。
- Sonatype Nexus Repository Terraform 文档。Nexus 3.88.0 引入 proxy、3.89.0 引入 hosted、3.90.0 引入 group；当前文档使用 `terraform-hosted`、`terraform-proxy`、`terraform-group`，并把 module/provider 服务基址放在 `/repository/{repo}/v1/modules/` 和 `/repository/{repo}/v1/providers/`。
- Sonatype Nexus Terraform CLI 文档。Module 上传使用 `PUT /v1/modules/{namespace}/{name}/{provider}/{version}/{filename}`；Provider 上传使用 `PUT /v1/providers/{namespace}/{type}/{version}/download/{os}/{arch}`，文件名来自 `Content-Disposition`。
- Sonatype Nexus 当前参考实例。文档没有固定所有错误响应、生成 asset URL、group conflict strategy 字段、Provider protocol version 推导和重签名时机；这些细节必须先由 `compat-test` 对参考实例记录，不能凭推测固化。

关键结论：

- Terraform module 与 provider 共用一个 repository format 和一组 hosted/proxy/group recipe，但必须保持两套独立协议模型、路径 parser 和 metadata renderer。
- 官方 registry protocol 的服务基址可以通过 hostname discovery 获得，也可以在 Terraform CLI `host` 配置中显式指定。kkrepo 第一阶段采用 Nexus 的显式 `host.services` 配置，不在 `/repository/{repo}/...` 下伪造无效的 `/.well-known/terraform.json`。
- 一个部署可以有多个 Terraform 仓库，根域名只能有一个 `/.well-known/terraform.json`。后续若增加零配置 discovery，必须先引入域名到 repository/group 的显式绑定，不能根据请求临时猜仓库。
- Module 下载 metadata 中可直接识别为 archive 的 `X-Terraform-Get`，以及 Provider 下载 metadata 中的 `download_url`、`shasums_url`、`shasums_signature_url`，必须指回当前 hosted/proxy/group 路径；HTTP vanity、VCS 和带 `//subdir` 的 go-getter source 保持官方语义并原样透传。反向代理下使用可信外部 base URL。
- URL token 可能出现在 `/v1/modules/{token}/...` 或 `/v1/providers/{token}/...` 服务基址中。缓存中不得保存调用者 token；只缓存 token-free 规范化 metadata，请求返回时再渲染 URL。
- Provider archive、SHA256SUMS 和签名是一个一致性单元。新增一个 OS/architecture 平台会改变 version 的平台集合和 checksum 清单；必须通过关系数据库 revision 和原子可见状态协调多副本。
- Provider zip 只作为不可信数据检查，服务端绝不能执行其中的 Provider binary。无法安全推导的 protocol version 行为必须先以 Nexus 参考测试固定。

## 功能范围

### 第一阶段必须实现

1. Terraform hosted
   - 新增 `RepositoryFormat.TERRAFORM`、`terraform-hosted` recipe 和 `protocol-terraform` 模块。
   - 支持 Module versions、download metadata、archive 下载和 Nexus 路径 `PUT` 上传。
   - 支持 Provider versions、platform download metadata、archive/checksum/signature 下载和 Nexus 路径 `PUT` 上传。
   - Module identity 使用 `namespace/name/system/version`；Provider identity 使用 `namespace/type/version/os/arch`。
   - Module archive 至少支持 Nexus 文档列出的 `.tar.gz`、`.tgz`、`.txz`、`.xz`、`.tar.xz`、`.tar.bz2`、`.tbz2`、`.zip`，实际 content-type、扩展名和压缩格式矩阵由 Nexus reference test 固定。
   - Provider package 只接受 `.zip`；校验 `Content-Disposition`、URL identity、文件名、zip 结构、平台 identity 和 SHA256。
   - 为 hosted Provider 生成 SHA256SUMS、detached GPG signature 和 registry metadata；签名私钥按 secret 保存，不进入日志、公开 metadata 或普通 repository JSON。
   - 支持同一 Provider version 增量上传多个平台，并保证 versions/platforms/checksum/signature 一致可见。
   - hosted archive 支持 `HEAD`、ETag、Last-Modified 和 conditional GET；group archive 的 `HEAD` 保持 Nexus 3.92 当前的 `404`。Range 请求保持 Nexus 3.92 当前的 `200` 全量响应语义（若后续 Nexus 改变则由兼容测试驱动调整）。

2. Terraform proxy
   - 新增 `terraform-proxy` recipe；每个 proxy 只配置一个上游 registry base URL。
   - 对上游根地址请求 `/.well-known/terraform.json`，动态保存并定期重校验 `modules.v1` / `providers.v1` 服务基址。
   - 支持 registry.terraform.io；OpenTofu registry 和 registry.coder.com 在 Nexus 3.94+ reference suite 通过后进入兼容范围。
   - 缓存 module/provider version metadata、module archive、provider archive、SHA256SUMS、signature 和 public signing key metadata。
   - 把上游 `X-Terraform-Get`、`download_url`、`shasums_url` 和 `shasums_signature_url` 改写到本地 repository path。
   - Provider archive 必须同时通过响应中的 `shasum` 和 SHA256SUMS 对应行校验；签名和 signing key 原样保留，不由 proxy 冒充上游重新签名。
   - 支持 metadata/content TTL、conditional revalidation、共享 negative cache、auto-block、stale policy、远端认证和 redirect/SSRF 防护。

3. Terraform group
   - 新增 `terraform-group` recipe，成员只允许 Terraform hosted/proxy/group，保存顺序并拒绝循环依赖。
   - Module 和 Provider version 列表按 Nexus 参考规则聚合；同一 coordinate/version 冲突时使用 group 配置的 conflict strategy。
   - 对选中的 module version 或 provider platform 建立来源绑定，metadata、archive、checksum、signature 必须来自同一成员，不能跨成员拼装。
   - Group 返回自身路径的 `X-Terraform-Get` 和 Provider 下载 URL；后续下载按同一来源绑定路由。
   - Group 只读，Module/Provider `PUT` 和管理删除的客户端路径返回 Nexus 对应状态。
   - 成员 revision、顺序或 conflict strategy 变化时使来源绑定和 metadata cache 失效；正确性不依赖单 JVM map。

4. 认证与权限
   - 支持匿名读取、HTTP Basic、现有 API key / `GenericToken` bearer，以及 Nexus 风格 URL token。
   - URL token 使用现有安全主体、hash、过期、禁用、审计和权限模型，不新增一套独立用户真相。
   - URL token 只在认证 filter 中解析；进入协议 service 前 path 必须已经剥离 credential segment。
   - versions、download metadata、archive/checksum/signature 读取映射 repository `READ`。
   - Hosted 首次上传映射 `ADD`；同 coordinate/platform 替换只有 write policy 允许时映射 `EDIT`；删除映射 `DELETE`。
   - Group 和 proxy 写请求不得因为具备 `ADD` 权限而绕过只读类型约束。

5. 管理、搜索和迁移
   - Admin UI 支持创建、编辑 hosted/proxy/group；hosted 配置签名 key，proxy 配置 upstream/TTL/auth，group 配置成员与 conflict strategy。
   - UI/API 上传与 Nexus 路径 `PUT` 复用同一 validation/publish service，不允许形成宽松的第二套写入逻辑。
   - Browse/Search 按 module/provider、namespace、name/type、version、system、OS、architecture、protocol、checksum 和 source member 展示。
   - Nexus metadata migration 识别 Terraform repository definition；repository data migration 对 hosted 和管理员显式选择的 proxy cache 支持 dry-run、resume、checksum、报告和 source profile gate。

6. 兼容性与真实客户端测试
   - 新增 `TerraformRepositoryBlackBoxCompatibilityTest`，同一请求分别运行在 Nexus reference 和 kkrepo。
   - 新增真实 `terraform init` / `terraform get` E2E，覆盖当前稳定版和 Nexus 声明的最低 Terraform 0.13 系列。
   - 覆盖 hosted/proxy/group、module/provider、匿名/Basic/URL token、缺失资源、非法 SemVer、错误平台、checksum/signature 失败和并发请求。
   - 只有 host、token、签名时间、上游生成时间等协议允许的非确定字段可以规范化；checksum、签名验证结果、状态码、header 语义和客户端 lock file 结果不能被忽略。

### 后续扩展和非目标

后续扩展：

- 根域名 `/.well-known/terraform.json` 与 repository/group 的 virtual-host 绑定。
- Provider Network Mirror Protocol 及 `terraform providers mirror` 静态目录导入。
- OpenTofu 3.94+ 完整 reference matrix、更多合规 registry 上游和 registry UI 扩展 API。
- Provider/module 文档、README、源码仓库信息等 Terraform Registry HTTP API 扩展。
- signing key 轮换、多把可信公钥和已有 version 的可控重签名工作流。

明确不实现：

- 不把 Terraform archive 当成 Raw 仓库文件，也不让 Raw path 代替 registry metadata。
- 不实现 Terraform Cloud/Enterprise 的 workspace、run、state、policy 或 private registry 管理 API。
- 不把 Provider Network Mirror Protocol 当成 Provider Registry Protocol 的兼容替代。
- 不执行上传的 Provider binary、module code、脚本或 Terraform 配置。
- 不从 archive 文件名猜测不在 URL 或 Nexus reference 行为中的 namespace、version、protocol 等关键 identity。
- 不把 GPG 私钥、上游凭据或 URL token 写入 asset attributes、普通日志、metrics label、缓存 key 或迁移报告。
- 不把大 archive、checksum response body 或签名文件存进关系数据库。

## URL 与路径设计

### 客户端配置

默认使用 Nexus 文档的显式 service 配置：

```hcl
# ~/.terraformrc
host "registry.terraform.io" {
  services = {
    "modules.v1"   = "https://repo.example.com/repository/terraform-group/v1/modules/"
    "providers.v1" = "https://repo.example.com/repository/terraform-group/v1/providers/"
  }
}
```

需要 Nexus 风格 URL token 时，token 是 service base 的最后一个 segment：

```hcl
host "registry.terraform.io" {
  services = {
    "modules.v1"   = "https://repo.example.com/repository/terraform-group/v1/modules/<url-token>/"
    "providers.v1" = "https://repo.example.com/repository/terraform-group/v1/providers/<url-token>/"
  }
}
```

设计约束：

- `{url-token}` 必须作为单个 URL-encoded segment 处理，解码一次后做长度限制和 constant-time credential lookup。
- 解析顺序是先识别固定的 `v1/modules` 或 `v1/providers`，再尝试认证可选 token，最后把剩余 path 交给协议 parser。
- token 无效时的 fallback、`401/404` 和 namespace 碰撞行为必须由 Nexus reference test 固定，不能用正则猜 token 外形。
- access log、trace、audit target、exception message 和 metrics route 必须在进入通用日志链之前把 token segment 替换为 `<redacted>`。
- 对外 metadata URL 在每次请求中由 repository external base URL 和当前认证上下文渲染；持久化与 cache 只保存 token-free route identity。

### Module 路由

以下路径全部位于 `/repository/{repo}` 下；带 URL token 时在 `v1/modules/` 后插入 `{url-token}/`，其余结构不变。

| 方法与路径 | 行为 |
| --- | --- |
| `GET /v1/modules/{namespace}/{name}/{system}/versions` | 返回 Module Registry Protocol version 列表 |
| `GET /v1/modules/{namespace}/{name}/{system}/{version}/download` | 返回 `204` 和 `X-Terraform-Get` |
| `GET/HEAD /v1/modules/{namespace}/{name}/{system}/{version}/{filename}` | 下载 module archive；路径与 Nexus hosted 上传布局一致 |
| `PUT /v1/modules/{namespace}/{name}/{system}/{version}/{filename}` | Nexus 兼容 hosted module 上传 |

其中 Nexus 文档把 `{system}` 称为 `{provider}`；内部模型使用官方协议名称 `system`，HTTP path 保持同一个位置，不另加 segment。

`X-Terraform-Get` 应返回当前 repository 下的 archive URL。优先使用相对 URL，避免错误信任请求 `Host`；是否绝对、相对以及 URL token 保留方式由 Nexus 黑盒结果固定。成功响应必须保持 `204 No Content` 和空 body。

### Provider 路由

以下路径全部位于 `/repository/{repo}` 下；带 URL token 时在 `v1/providers/` 后插入 `{url-token}/`。

| 方法与路径 | 行为 |
| --- | --- |
| `GET /v1/providers/{namespace}/{type}/versions` | 返回 versions、protocols 和 platforms |
| `GET /v1/providers/{namespace}/{type}/{version}/download/{os}/{arch}` | 返回 Provider Registry Protocol download metadata JSON |
| `PUT /v1/providers/{namespace}/{type}/{version}/download/{os}/{arch}` | Nexus 兼容 hosted provider 上传；文件名从 `Content-Disposition` 读取 |
| `GET/HEAD {download_url}` | 下载 provider `.zip` |
| `GET/HEAD {shasums_url}` | 下载该 provider version 的 SHA256SUMS |
| `GET/HEAD {shasums_signature_url}` | 下载 detached GPG signature |

`download_url`、`shasums_url`、`shasums_signature_url` 的具体 Nexus asset path 和 `Content-Disposition` 细节由 M0 reference suite 固定；实现不得另造 `/api/terraform/download` 之类的旁路。无论最终 URL 采用同级文件还是额外 path segment，都必须位于 `/repository/{repo}/v1/providers/...` 下并由相同权限检查覆盖。

### Service discovery 边界

- Proxy 的上游 discovery 固定请求上游 hostname 的 `/.well-known/terraform.json`，并从响应读取绝对或相对的 `modules.v1` / `providers.v1`。
- kkrepo 第一阶段不在 `/repository/{repo}/.well-known/terraform.json` 暴露 discovery；Terraform 官方不会把 repository path 当成 user-facing hostname discovery 根。
- 如果部署已经在根域暴露其它应用，不能由 kkrepo controller 抢占全局 `/.well-known/terraform.json`。
- 后续 virtual host discovery 必须使用显式 host binding，并从受信任 public URL 配置生成服务地址。

### Path 规范化

- namespace、name、system、provider type、version、os、arch 和 filename 分 segment 校验；拒绝空 segment、`.`、`..`、编码 slash、双重编码、NUL 和 Unicode 混淆路径。
- SemVer 使用 Terraform/Nexus reference 接受范围，预发布和 build metadata 用黑盒测试覆盖；不得使用字典序排序。
- identity 的大小写规则分别由官方协议和 Nexus reference 固定。持久化同时保留 display value 与用于唯一约束的 normalized value。
- Provider filename 必须与 `Content-Disposition` 安全解析结果一致，不允许目录、反斜线、CRLF 或重复 filename 参数。
- 所有返回 URL 只使用通过 reverse-proxy trust 配置计算的 external base URL，不直接信任任意 `Forwarded` / `X-Forwarded-*`。

## 数据模型落地

### Component 与 Asset

Module version：

- `component.format=TERRAFORM`
- `component.kind=terraform-module`
- `namespace={namespace}`
- `name={name}`
- `version={semver}`
- attributes 保存 `system`、normalized identity、source registry/member 和 archive format。
- 唯一 identity 为 `(repository_id, module, namespace_norm, name_norm, system_norm, version_norm)`。

Provider version：

- `component.format=TERRAFORM`
- `component.kind=terraform-provider`
- `namespace={namespace}`
- `name={type}`
- `version={semver}`
- attributes 保存 protocols、source registry/member、signing key fingerprint 和 metadata revision。
- 一个 component 下挂多个 `(os, arch)` platform archive。

Asset：

- Module archive、Provider archive、SHA256SUMS 和 signature 都是独立 asset/blob reference，大字节存 OSS/S3。
- Asset attributes 保存公开、可重建的 Terraform metadata，例如 kind、coordinate、platform、filename、SHA256、upstream validator 和来源；不保存 secret。
- Provider versions JSON、download metadata JSON 和 Module versions JSON 优先从规范化行与 revision 派生；如缓存为 asset，也必须标记为可重建派生 metadata。
- Browse node 只作为可重建索引，不作为协议查询真相。

### Terraform 专用关系

通用 component/asset JSON 不足以高效、强约束地表达 Provider platform 和 group source binding，因此需要最小的专用关系：

- `terraform_provider_platform`：`repository_id`、`component_id`、`os_norm`、`arch_norm`、`asset_id`、`filename`、`sha256`、`protocols_json`、`state`、`revision`；唯一约束 `(repository_id, component_id, os_norm, arch_norm)`。
- `terraform_provider_signing_state`：provider component、checksum asset、signature asset、public key fingerprint、content revision、state；只在 `READY` 时可对客户端可见。
- `terraform_source_binding`：group repository、coordinate hash、version/platform、member repository、member revision、group revision、strategy；用于保证 metadata 与 blob 来源一致。
- Proxy metadata body、validator、negative state 和 remote download route 优先复用现有 asset metadata cache、shared negative cache、`proxy_remote_state` 和 repository revision；只有现有唯一约束无法覆盖时才新增表。

MySQL 与 PostgreSQL migration 必须同步增加，DAO 放在 `persistence-jdbc` 公共契约之后，并运行双数据库 contract test。时间字段保持 wall-clock / instant 语义与现有数据库后端规范一致。

### Revision 与可见状态

- 每个 module coordinate、provider coordinate 和 group membership 都有共享 revision/watermark。
- Provider platform 上传先进入 `STAGING`，archive、SHA256SUMS、signature 和 metadata 全部完成后，在一个数据库事务中切换为 `READY` 并递增 revision。
- 读取只查询 `READY` revision；不得出现 versions 已广告新平台但 checksum/signature 尚未可读的窗口。
- 删除先事务性取消可见引用并递增 revision，再通过 marker 异步清理无引用 blob。
- metadata rebuild marker 以 repository + coordinate 去重，worker 使用可抢占 lease、heartbeat 和幂等写入。

## Hosted 写入流程

### Module 上传

1. Controller 只解析 repository、认证上下文和流式 request，委托 `TerraformHostedService`。
2. 校验 repository online、format/type、`ADD/EDIT` 权限、write policy、path identity、SemVer、filename、Content-Type 和大小限制。
3. 流式写入 OSS/S3 临时对象，同时计算 SHA256；不得把整个 archive 读入 JVM heap。
4. 在受限 reader 中检查 archive：至少包含 `.tf` 文件；拒绝 path traversal、绝对路径、symlink/hardlink 越界、重复危险 entry、zip bomb、超限文件数/展开大小和不匹配压缩格式。
5. 不执行 module code，不访问其中声明的 remote source，不将 archive 解压到持久化本地目录。
6. 在数据库事务中插入 component、asset、blob reference、browse/index marker 和 coordinate revision；唯一约束裁决并发同版本上传。
7. 事务提交后发布 cache invalidation；失败或冲突的临时对象进入幂等清理 marker。
8. 后续 `versions`、`download` 和 archive 请求可以在任意副本立即从共享状态恢复。

### Provider 上传与签名

1. 按 Nexus path 读取 namespace/type/version/os/arch，并从 `Content-Disposition` 安全取得 filename。
2. 流式写临时对象并计算 SHA256；以受限 zip reader 校验 archive 结构、文件名、展开大小和 platform identity，不执行 binary。
3. 从 repository signing config 取得加密私钥引用和 passphrase secret；业务对象只读取临时解密材料，使用后立即释放，不写日志/attributes。
4. 在数据库中锁定 provider version revision。并发上传不同 platform 可以写各自 staging archive，但 checksum 清单发布必须按 revision 串行化。
5. 根据全部 `READY` platform 加当前 staging platform 生成稳定排序的 SHA256SUMS，再生成 detached signature 和公开 signing key metadata。
6. 将新 checksum/signature 写入临时 blob；在单个事务中绑定 archive、checksum、signature、platform row 和 provider revision，并把新状态切换为 `READY`。
7. 旧 checksum/signature 只有在新 revision 提交后才失去引用，随后异步 GC；读取中的旧 revision 仍能完成一致下载。
8. Provider protocol version 的来源和默认值必须由 M0 对 Nexus hosted 上传实测确定。实现不得启动 provider binary 探测；若 Nexus 暴露显式 upload field/header，则完整对齐，否则只实现已验证的安全推导/default。

签名 key 轮换不会隐式重签所有历史 version。轮换时保存 key revision；新上传使用新 key，历史 metadata 继续引用生成其签名的 public key，除非管理员显式启动可恢复的重签任务。

## Proxy 缓存流程

### Service discovery

1. 保存 remote registry 根 URL，而不是管理员手填的猜测 API 子路径。
2. 请求 `/.well-known/terraform.json`，校验 `200`、`application/json`、body 大小、JSON shape 和 service URL。
3. 相对 service URL 按最终 discovery URL 解析；绝对/redirect URL 每一跳都重新执行 HTTPS、host allowlist、DNS/IP 和 credential scope 检查。
4. discovery 文档、ETag、Last-Modified、last verified、失败状态和到期时间保存在共享状态；进程内 cache 只做短 TTL 热缓存。
5. 上游没有某类 service 时只禁用对应 module/provider 能力，不伪造 endpoint。

### Metadata 与 archive

Module：

1. versions 请求优先读取新鲜共享 cache；过期后使用 conditional request 回源。
2. download 请求读取上游 `204 + X-Terraform-Get`；只有具有官方 archive 扩展名、且不含 `//subdir`/VCS 等 go-getter 语义的 HTTP(S) source 才保存为受保护 remote route。
3. 可缓存 archive 返回当前 proxy/group archive path；HTTP vanity、VCS 和其它 go-getter source 原样透传，避免丢失 Terraform 客户端负责解释的 query 与子目录语义。
4. 首次 archive 下载在分布式 singleflight/lease 下回源，流式写临时 blob并校验；提交后其它副本共享。

Provider：

1. versions 与 platform download metadata 分别缓存原始响应、规范化模型、validator 和 source service revision。
2. download metadata 中的 archive、SHA256SUMS 和 signature URL 全部解析为受保护 remote route，再渲染成本地 URL。
3. 下载 archive 时同时验证 response `shasum` 与 SHA256SUMS 中同 filename 的行；缺失、重复或不一致都不能发布 cache。
4. 上游 signing key metadata 和 signature 原样返回；可以验证签名并记录结果，但不得用 kkrepo key 替换上游身份。
5. metadata cache 不包含调用者 URL token；同一份规范化模型可以按匿名、Basic 或 URL token 请求分别渲染。

共享缓存规则：

- `404`/`410` 可以写短 TTL negative cache；`401`、`403`、`429`、timeout 和 `5xx` 不能当作不存在。
- metadata `304` 只刷新验证时间，不更换 source revision。
- 上游临时失败时，只有已通过校验且仓库策略允许的 stale metadata/blob 才能返回。
- 分布式 lease 只减少重复请求；最终正确性仍由唯一约束、revision 和 `READY` 状态保证。
- 下载 redirect 每一跳重新做 SSRF 校验，远端 Authorization 不跨 host 转发。

## Group 解析流程

Module 与 Provider 使用相同的成员快照，但分别解析：

1. 从关系数据库读取 group revision、成员顺序、成员健康和 conflict strategy。
2. 各成员返回 version 集合后按 Nexus reference 规则聚合；故障、认证失败和明确 `404` 必须区分。
3. 选择具体 module version 或 provider platform 时，在 `terraform_source_binding` 保存来源成员与其 revision。
4. Module `X-Terraform-Get` 由 group 自己生成；archive 请求按绑定回到同一成员。
5. Provider download metadata、archive、SHA256SUMS、signature 和 signing key 都取自同一绑定成员，禁止“版本来自 A、binary 来自 B、signature 来自 C”。
6. 成员 revision 或 group 配置变化时旧 binding 失效并重算；节点本地 cache key 必须包含 group revision。
7. 并发首次解析同一 coordinate 使用共享 lease 或唯一约束收敛，任何副本崩溃后都能从数据库重新计算。

Nexus 文档说明 group 支持多种冲突策略，但未公开固定策略名称和完整 REST shape。M0 必须从 Nexus repository create/update API、UI 请求和行为测试提取：

- 默认策略和可选值。
- version 列表是否合并、覆盖或过滤。
- 同版本不同成员时 module/provider/platform 的选择规则。
- 成员超时、offline、stale cache 和 nested group 的行为。

在这些值被 reference test 固定前，不在公开 API 中发明自有策略名称。

## 权限、认证与凭据安全

权限映射：

| 操作 | kkrepo 权限 |
| --- | --- |
| Module/Provider versions | `READ` |
| Module download metadata / Provider platform metadata | `READ` |
| Archive、SHA256SUMS、signature 下载 | `READ` |
| Hosted 新 coordinate/platform 上传 | `ADD` |
| Hosted 允许覆盖时更新 | `EDIT` |
| 删除/隔离 module version 或 provider platform | `DELETE` 或管理员权限 |
| 修改 proxy remote credential、signing key、group members/strategy | repository 管理权限 |

认证约束：

- URL token 复用 API key/user subject 解析能力，支持失效、过期、last-used 和审计；第一阶段不新建 `TerraformToken` domain。
- 如果迁移的 Nexus User Token 需要兼容，preflight 必须证明可验证材料；无法迁移明文/等价 verifier 时要求用户重新生成，不伪造可用状态。
- 未认证请求对私有仓库返回 `401` 和 Nexus reference 对应 challenge；已认证无权限返回 `403`。
- Token segment 必须在所有 access/error 日志中脱敏。审计只记录 token id、principal、repository、operation 和结果，不记录 token material。
- 外部生成 URL 不把 Basic password、Bearer token 或 upstream credential 写入 query string。
- Provider signing private key 与 passphrase使用现有 encryption secret 体系加密；启动时缺少生产加密 secret 应 fail closed。

## 多副本语义

Terraform 实现不得依赖单个 JVM 的内存状态作为唯一真相：

- Component、asset、Provider platform、signing state、source binding、proxy validator、negative cache、revision、迁移 checkpoint 和 blob reference 以共享关系数据库为真相。
- Archive、checksum 文件和签名存 OSS/S3；数据库只保存 metadata、hash、状态和引用。
- 进程内 versions/download metadata cache 必须有 TTL，并以 repository/member/content revision 校验；丢失后可从数据库和对象存储重建。
- 同 coordinate 并发上传由唯一约束和状态机裁决；不能用本地 `synchronized` 作为跨副本锁。
- Provider checksum/signature 重建、proxy 首次回源和 group 首次绑定使用共享 lease；lease 到期后任何副本可接管。
- Worker marker 包含 attempt、next-attempt、lease owner/expiry 和错误摘要，支持 crash resume 与幂等重试。
- 权限/token cache 失效使用现有共享 watermark；protocol metadata negative cache 不能缓存认证结果或跨 principal 复用 `403`。
- 先写临时对象，再提交数据库引用；事务失败、pod 崩溃和 superseded revision 的孤儿对象由 marker GC。

必须增加多副本测试：

- 副本 A 上传 Module，副本 B 立即返回新 versions 和 archive。
- 两副本同时向同一 Provider version 上传不同 platform，最终 versions、SHA256SUMS 和 signature 完整一致。
- 两副本同时代理同一大 archive，只发布一个已校验 blob；lease owner 崩溃后可恢复。
- Group 成员顺序/revision 在副本 A 修改后，副本 B 不继续返回旧 source binding。
- Token 禁用后所有副本在共享 watermark 生效窗口内拒绝 URL token 请求。

## Browse、Admin、搜索和迁移

Admin UI：

- `terraform-hosted`：blob store、write policy、archive size/entry/ratio 限制、GPG private key、passphrase secret reference、key fingerprint 和 key revision。
- `terraform-proxy`：remote registry root、remote auth、discovered services、metadata/content/negative TTL、stale policy、auto-block 和 SSRF policy。
- `terraform-group`：成员顺序、nested group 循环检测、Nexus-aligned conflict strategy 和 source binding 诊断。
- Repository 详情提供可复制的 `.terraformrc` / `terraform.rc` 片段；token 值只能在创建时显示，不回显已有 secret。

Browse/Search：

- Module 展示 namespace/name/system/version、archive、checksum、来源和 Terraform `module` 配置片段。
- Provider 展示 namespace/type/version、protocols、platform、filename、SHA256、signing key、签名验证状态和 `required_providers` 配置片段。
- Proxy 展示 discovery URL/service base、validator、last verified、stale/negative 状态和 blob cached 状态。
- Group 展示 coordinate 的 source member、binding revision、strategy 与失效原因。
- 搜索走关系数据库反范式索引和权限过滤，不引入 Elasticsearch。

Nexus 迁移：

- Repository definition：识别 Nexus `terraform-hosted`、`terraform-proxy`、`terraform-group` 及引入版本，映射 remote URL、blob store、write policy、成员和已验证的 conflict strategy。
- Hosted data：只对 Nexus 3.89+ source profile 证明的 module/provider component、asset、platform、checksum、signature 和 metadata 执行 full migration。
- Proxy cache：只在管理员显式选择且 source profile 证明 remote service、cached metadata、remote route、validator、checksum/signature 和 blob 引用时迁移；仍保持 proxy 类型，不转换 hosted。
- Group：Nexus 3.90+ 迁移配置与成员关系；source binding/cache 可重建，不要求搬运为长期真相。
- Nexus 中加密保存的 GPG private key 如果没有受支持的安全导出形式，则标记 `MANUAL`，迁移已有 archive/checksum/signature/public key，但要求管理员在新增 hosted platform 前重新配置 signing key。
- 迁移支持 dry-run、resume、checksum 校验、逐仓库报告、checkpoint 和幂等重试；未知 Nexus schema/version fail closed。

## 安全边界

- Archive reader 设置压缩前/后大小、entry 数、单 entry 大小、压缩比、嵌套 archive 深度和读取时间限制。
- 拒绝绝对路径、`..`、Windows drive、UNC、symlink/hardlink 逃逸、device file、FIFO 和重复冲突 entry。
- Provider zip 和 Module archive 永不执行；安全检查使用无副作用 parser。
- Proxy remote 与每次 redirect 执行 DNS rebinding/SSRF 防护，默认拒绝 loopback、link-local、私网、Unix socket 和云 metadata 地址，除非管理员在受控策略中显式允许。
- 上游 response body、header、URL 和 filename 都有长度限制；不把上游 `Set-Cookie`、认证 challenge 或 hop-by-hop header 透传给客户端。
- Provider `download_url` / `shasums_url` / signature URL 只能经 opaque route id 或受校验的内部映射访问，不能把任意 URL 变成开放代理。
- GPG 签名生成失败时整个 Provider revision 不可见；不能降级为无签名 metadata。
- External base URL 与 proxy header trust 必须有明确配置，避免 host-header injection 污染 lock file、metadata 或下载 URL。

## 观测指标

建议指标：

- `kkrepo_terraform_metadata_requests_total{repository,type,kind,result,cache}`
- `kkrepo_terraform_downloads_total{repository,type,kind,result,cache}`
- `kkrepo_terraform_uploads_total{repository,kind,result}`
- `kkrepo_terraform_provider_platforms_total{repository,state}`
- `kkrepo_terraform_provider_sign_total{repository,result}`
- `kkrepo_terraform_checksum_failures_total{repository,kind,source}`
- `kkrepo_terraform_proxy_discovery_total{repository,result,status}`
- `kkrepo_terraform_proxy_remote_requests_total{repository,kind,result,status}`
- `kkrepo_terraform_group_resolution_total{repository,kind,result,member}`
- `kkrepo_terraform_source_binding_invalidations_total{repository,reason}`
- `kkrepo_terraform_active_uploads{repository,kind}`
- `kkrepo_terraform_active_downloads{repository,kind}`

日志字段包含 `repository`、`repository_type`、`artifact_kind`、`namespace`、`name`、`version`、`os`、`arch`、`operation`、`status`、`cache`、`source_member`、`principal` 和 `request_id`。不得使用 token、完整 remote signed URL、GPG private key id 以外的私钥材料或高基数 checksum 作为 metrics label。

告警至少覆盖：

- service discovery 连续失败或 service base 漂移。
- checksum/signature 校验失败。
- Provider signing/rebuild marker 长时间未完成。
- proxy lease 堆积、孤儿 staging blob 增长、negative cache 异常升高。
- group source binding 反复抖动或成员大面积 unhealthy。

## 兼容性测试矩阵

### M0 Nexus 参考基线

先在 Nexus 参考实例创建 hosted/proxy/group，并通过 UI 网络请求、repository REST API 和客户端请求固定：

- Recipe create/get/update JSON，包括 signing key、proxy discovery 和 group conflict strategy 字段。
- 有/无 URL token 的 service base 和 path 消歧义。
- Module 上传扩展名、Content-Type、大小、重复版本、预发布版本和 archive validation。
- Provider `Content-Disposition` 语法、protocols 推导、增量 platform、overwrite、SHA256SUMS 排序、signature 文件名和 GPG metadata。
- `X-Terraform-Get`、`download_url`、`shasums_url`、`shasums_signature_url` 的精确路径与相对/绝对规则。
- Hosted/proxy/group 的状态码、Content-Type、ETag/Last-Modified/Range/HEAD、错误 body 和 auth challenge。
- Group nested member、offline/stale member、同版本冲突和 platform 来源一致性。

### 自动化矩阵

| 场景 | Nexus reference | kkrepo | 真实客户端 |
| --- | --- | --- | --- |
| Module hosted versions/download/archive | 必须 | 必须 | `terraform init/get` |
| Provider hosted versions/platform/checksum/signature | 必须 | 必须 | `terraform init` + lock file |
| Module/Provider proxy registry.terraform.io | 必须 | 必须 | `terraform init -upgrade` |
| Hosted + proxy group 冲突 | 必须 | 必须 | `terraform init` |
| 匿名、Basic、URL token、无权限 | 必须 | 必须 | CLI + HTTP probe |
| 0.13 与当前稳定 Terraform | 必须 | 必须 | 双版本 CLI |
| OpenTofu / alternate registry | Nexus 3.94+ | 通过后支持 | `tofu init` |
| 多副本并发上传/回源/失效 | 行为参考 | 必须 | CLI + 并发 probe |
| Nexus hosted/proxy migration | 源数据 | 必须 | 迁移后 `terraform init` |

Provider 验收不能只比较 JSON：测试必须下载 archive、SHA256SUMS 和 signature，用响应中的 public key 验证 detached signature，并确认 archive checksum 命中对应 filename。Module 验收必须实际解包和执行 `terraform init`，不能只看到 HTTP 200。

## 实施顺序

1. M0：协议与 Nexus reference 基线
   - 建立 Nexus 3.88+/3.89+/3.90+ capability matrix，默认用当前稳定 Nexus 跑完整 suite。
   - 新增 `TerraformRepositoryBlackBoxCompatibilityTest` 骨架和可重放 exchange fixture。
   - 固定路径、状态/header、上传约束、URL token、Provider signing 和 group strategy。

2. M1：Core、recipe、持久化和安全入口
   - 新增 `RepositoryFormat.TERRAFORM`、三类 recipe 和 `protocol-terraform`。
   - 增加 path/identity/SemVer/model parser 与双数据库 DAO/migration。
   - 在通用安全 filter 中加入 URL token 脱敏和认证，不把 token 传给 controller。

3. M2：Hosted Module
   - 实现 versions、download、archive 和 Nexus path PUT。
   - 完成 archive 安全检查、共享 revision、Browse/Search 和真实 CLI E2E。

4. M3：Hosted Provider
   - 实现 versions、platform metadata、archive/checksum/signature 和 Nexus path PUT。
   - 完成增量 platform、GPG secret、atomic revision、并发与真实 CLI lock file 测试。

5. M4：Proxy
   - 实现 dynamic discovery、metadata/route rewrite、validator、negative/stale cache 和 SSRF 防护。
   - 完成 registry.terraform.io module/provider、checksum/signature 和离线缓存 E2E。

6. M5：Group
   - 实现 Nexus-aligned version aggregation/conflict strategy、source binding、nested group 和 revision invalidation。
   - 验证 hosted + proxy、同版本冲突、成员故障与多副本一致性。

7. M6：Admin、Browse、上传和迁移
   - 补齐 repository 配置、usage snippet、搜索、UI/API 上传和诊断页。
   - 完成 Nexus definition/hosted/full migration 与显式 proxy cache migration。

8. M7：硬化与文档
   - 补齐高并发、大 archive、故障注入、key rotation、backup/restore 和告警。
   - 更新 compatibility matrix、client recipes、migration guide、security model 和中英文用户文档。

每个 milestone 都先增加 Nexus/官方协议兼容测试，再实现最小行为；不得把 M0 留到代码完成后补做。

## 验收标准

第一阶段完成时必须满足：

- Terraform 0.13 系列和当前稳定版能通过 `/repository/{repo}/v1/modules/...` 与 `/v1/providers/...` 从 hosted、proxy、group 执行真实 `terraform init`。
- 所有客户端路径与 Nexus 对齐；没有要求用户改用 kkrepo 自有 `/api/...` 下载或上传路径。
- Module versions/download 遵循官方 JSON 和 `204 + X-Terraform-Get` 语义；archive 内容与上传 SHA256 一致。
- Provider versions/platform metadata 完整，archive、SHA256SUMS、signature 和 public key 能被 Terraform CLI 与独立 GPG 校验通过。
- 同一 Provider version 增量上传 platform 后，任意副本读取到的平台集合、checksum 清单和 signature 属于同一个 committed revision。
- Proxy 动态读取上游 discovery，不假设 registry.terraform.io 的固定内部 API path；所有下载 URL 都改写到当前 repository。
- Group 同 coordinate 冲突遵循已记录的 Nexus strategy，且 metadata/archive/checksum/signature 不跨成员拼装。
- URL token、Basic、匿名和权限不足的状态/header 与 Nexus reference 一致；token 不出现在日志、metrics、cache 或返回给其他 principal 的 metadata 中。
- Archive traversal/zip bomb、SSRF/redirect、host-header injection、checksum/signature mismatch 都有拒绝测试。
- 多副本上传、回源、metadata invalidation、source binding 和 worker failover 不依赖单 JVM 状态。
- MySQL 与 PostgreSQL contract/integration test 都通过；大 blob 只在 OSS/S3。
- Nexus Terraform hosted 数据可 dry-run、resume、校验并迁移；显式选择的 proxy cache 只有 source profile 证明后才迁移；迁移后真实 `terraform init` 成功。
- Compatibility suite 记录所有有意差异，并只规范化协议允许的非确定值。

## 参考资料

- HashiCorp Terraform: Remote Service Discovery Protocol: https://developer.hashicorp.com/terraform/internals/remote-service-discovery
- HashiCorp Terraform: Module Registry Protocol: https://developer.hashicorp.com/terraform/internals/module-registry-protocol
- HashiCorp Terraform: Provider Registry Protocol: https://developer.hashicorp.com/terraform/internals/provider-registry-protocol
- HashiCorp Terraform: Provider Network Mirror Protocol: https://developer.hashicorp.com/terraform/internals/provider-network-mirror-protocol
- HashiCorp Terraform: CLI Configuration File: https://developer.hashicorp.com/terraform/cli/config/config-file
- HashiCorp Terraform: Provider Requirements: https://developer.hashicorp.com/terraform/language/providers/requirements
- Sonatype Nexus Repository: Terraform Repositories: https://help.sonatype.com/en/terraform-repositories.html
- Sonatype Nexus Repository: Create a Terraform Repository: https://help.sonatype.com/en/create-a-terraform-repository.html
- Sonatype Nexus Repository: Configure Terraform with Nexus: https://help.sonatype.com/en/configure-registry.html
- Sonatype Nexus Repository: Terraform CLI Usage: https://help.sonatype.com/en/cli-usage-and-options.html
- Sonatype Nexus Repository 3.88.0 Release Notes: https://help.sonatype.com/en/sonatype-nexus-repository-3-88-0-release-notes.html
- Sonatype Nexus Repository 3.89.0 Release Notes: https://help.sonatype.com/en/sonatype-nexus-repository-3-89-0-release-notes.html
- Sonatype Nexus Repository 3.90.0 Release Notes: https://help.sonatype.com/en/sonatype-nexus-repository-3-90-0-release-notes.html
