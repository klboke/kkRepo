# Swift Package Registry 仓库开发设计说明

本文记录 kkrepo Swift Package Registry 仓库格式的开发设计。目标不是把 Git 仓库或 ZIP 文件简单包装成 Raw 内容，而是在 Swift 官方 Package Registry Service Specification、SwiftPM 客户端行为和 Sonatype Nexus Repository Swift 行为之间取兼容交集，并按 kkrepo 的关系数据库 + OSS/S3 + 多副本约束落地可托管、可代理、可组合、可迁移和可观测的 Swift 仓库。

## 当前支持状态

截至 2026-07-16，本设计已在 `feat/swift-package-registry-support` 完整落地。代码已包含 `RepositoryFormat.SWIFT`、`swift-hosted`、`swift-proxy`、`swift-group` recipe、独立 `protocol-swift` 模块、MySQL/PostgreSQL V31 schema，以及 server、UI、迁移、兼容性和真实客户端测试。

当前实现覆盖：

- Swift Package Registry API v1 的 release list、release metadata、manifest、source archive、identifier lookup 和 publish 端点。
- 与 Nexus 一致的 `/repository/{repo}/...` 客户端路径，不引入 kkrepo 私有协议前缀。
- Swift hosted、GitHub-backed proxy 和 group 仓库。
- `swift package-registry set/login/publish`、`swift package resolve/build --replace-scm-with-registry` 和 Xcode registry dependency 的真实客户端验证。
- Basic、Bearer token、匿名读取，以及现有 kkrepo API key / CI token 的统一认证授权。
- Source archive SHA-256、可选 CMS 签名、版本化 `Package.swift`、HTTP 缓存和 Range 请求。
- Browse/Search、Admin UI、UI/API 上传，以及 Nexus repository definition 和 hosted data 迁移。

实现以共享数据库和 blob store 为正确性边界：发布 lease/fencing、release revision、GitHub tag/commit binding、group source binding、negative cache、rate-limit 水位和 tombstone 均持久化；进程内状态只用于可重建热路径。CI 同时运行 MySQL/PostgreSQL contract test、Nexus 3.94.x 黑盒矩阵、SwiftPM 5.7/5.10/6.x、macOS Xcode、Windows proxy resolve/build、双副本 S3-compatible resilience 和 Nexus 迁移场景。

存储验证边界需要明确：定时 resilience E2E 使用 PostgreSQL 双副本，通过 AWS S3-compatible adapter 访问 MinIO，并执行破坏式数据库/object 备份恢复。阿里云 OSS Native 引擎有 adapter contract 测试，但本分支不声称已运行真实 OSS Native endpoint E2E。

## 调研基线

实现前必须对照以下协议和参考行为：

- Swift Evolution [SE-0292: Package Registry Service](https://github.com/swiftlang/swift-evolution/blob/main/proposals/0292-package-registry-service.md)。它定义 `scope.package-name` identity、registry dependency、SCM-to-registry 转换和 SwiftPM checksum 固定语义。
- SwiftPM [Package Registry Service Specification](https://github.com/swiftlang/swift-package-manager/blob/main/Documentation/PackageRegistry/Registry.md) 与对应 [OpenAPI](https://github.com/swiftlang/swift-package-manager/blob/main/Documentation/PackageRegistry/registry.openapi.yaml)。它们是 HTTP endpoint、media type、header、problem details、publish multipart 和签名字段的协议真相。
- Swift Evolution [SE-0378: Package Registry Authentication](https://github.com/swiftlang/swift-evolution/blob/main/proposals/0378-package-registry-auth.md)。SwiftPM registry login 支持 Basic 和 token 两种模式，默认认证检查端点是 `POST /login`。规范将该端点定义为可选能力，未实现的服务端可返回 `501`；kkrepo 已实现该端点，所以自身验收只期望 `200`/`401`。
- SwiftPM [Using a package registry](https://docs.swift.org/swiftpm/documentation/packagemanagerdocs/usingswiftpackageregistry/)。registry 配置写入项目或用户级 `registries.json`；registry package identity 与 SCM URL dependency 的转换策略不同，必须分别验证。
- Sonatype Nexus [Swift Repositories](https://help.sonatype.com/en/swift-repositories.html)、[Create a Swift Repository](https://help.sonatype.com/en/create-a-swift-repository.html)、[Configure Swift with Nexus](https://help.sonatype.com/en/configure-spm-registry.html) 和 [Swift CLI Usage](https://help.sonatype.com/en/swift-cli-usage.html)。Nexus 使用 `/repository/{repo}/` 作为 registry base，当前支持 hosted、proxy、group、匿名/认证读取和 SwiftPM publish。
- Nexus [2026 release notes](https://help.sonatype.com/en/nexus-repository-2026-release-notes.html)。Swift proxy、hosted、group 分别从 3.89.0、3.90.0、3.91.0 引入；3.92.0 增加 hosted 迁移；3.93.0 修复版本化 manifest fallback；3.93.0/3.94.0 修复 Git tag 的 `v`/`V` 前缀规范化。兼容测试参考实例应使用当前 3.94.x，并保留这些历史版本作为回归说明。

关键结论：

- Swift Registry v1 使用 `scope.package-name` 作为逻辑 identity，但 HTTP path 是 `/{scope}/{name}/...`。scope 最长 39 字符，只允许字母、数字和非连续中划线；name 最长 100 字符，允许字母、数字、非连续中划线和下划线；两者都大小写不敏感。
- Release version 使用 SemVer。Hosted 发布后的 release、archive、manifest、metadata 和签名不可变；同一版本重复发布必须返回 `409 Conflict`，不能因调用者拥有 `EDIT` 权限而覆盖。
- SwiftPM 通过 `Accept: application/vnd.swift.registry.v1+json|swift|zip` 协商 API v1。JSON 响应和 problem details 必须带正确 `Content-Type` 与 `Content-Version: 1`；manifest/archive 按规范可省略 `Content-Version`，是否省略以 Nexus 黑盒结果为准。
- Source archive 的 SHA-256 是 SwiftPM `Package.resolved` 和 TOFU 校验的一部分。Proxy 第一次固定某个 release 后，不得因 tag 移动、另一副本回源或 cache 过期而静默替换 archive 字节。
- `Package.swift` 与 `Package@swift-X[.Y[.Z]].swift` 是协议资源。带 `swift-version` 查询但没有精确版本 manifest 时，应返回 `303` 到无版本 `Package.swift`；不能返回一个相近版本的 manifest。
- Nexus 客户端路径直接以 `/repository/{repo}/` 为 registry base。所有 metadata URL、`Location`、`Link` 和 redirect 必须在可信 external base URL 下渲染，并保留反向代理 context path。
- Nexus 当前 Swift proxy 的 remote storage 仅支持 `https://github.com/`。kkrepo 第一阶段先实现 Nexus 兼容的 GitHub source-to-registry 模式；任意上游 Swift Registry 的级联代理是合理扩展，但不能与 GitHub 合成逻辑混在同一个未标记模式中。
- `swift package resolve/build --replace-scm-with-registry` 依赖 `/identifiers?url=...` 把 GitHub URL 映射为 registry identity。URL normalization、tag 到 SemVer 的转换、archive 生成方式和 canonical/alternate `Link` 必须由 Nexus reference test 固定。
- SwiftPM 的 availability check 是客户端配置能力，不属于 Registry v1 六个核心 endpoint。Nexus Xcode 示例显式使用 `supportsAvailability: false`；第一阶段保持相同配置，不自行发明 `/availability` 行为。

## 功能范围

### 第一阶段必须实现

1. Swift hosted
   - 新增 `RepositoryFormat.SWIFT`、`swift-hosted` recipe 和独立 `protocol-swift` 模块。
   - 实现 Registry v1 release list、release metadata、manifest、source archive、identifier lookup 和 publish。
   - 支持 `swift package-registry publish <scope.name> <version>` 的 multipart 请求。
   - 流式接收 `source-archive`，可选接收 `source-archive-signature`、`metadata` 和 `metadata-signature`。
   - 校验 scope、name、SemVer、ZIP MIME/签名、archive 结构、根 manifest 和版本化 manifest 文件名。
   - 按原始 ZIP 字节计算 SHA-256；保存可选 `cms-1.0.0` 签名并在 metadata/archive response 中一致返回。
   - Release 一经发布不可覆盖；第一次成功发布同步返回 `201 Created` 和当前 repository 下的 `Location`。
   - 支持 `GET` 和 `HEAD`，source archive 支持 `ETag`、`Last-Modified` 和 conditional GET；Range 的 `200/206`、`Accept-Ranges` 与非法区间语义先由 Nexus 黑盒测试固定。
   - 支持 UI/API 上传，但必须复用 SwiftPM publish 的 validation/persistence service。

2. Swift proxy
   - 新增 `swift-proxy` recipe，第一阶段 remote storage 只接受并规范化为 `https://github.com/`，对齐 Nexus 当前配置边界。
   - 通过 `/identifiers?url=...` 识别 GitHub HTTPS、SSH 和带 `.git` 变体，并生成稳定的 `owner.repository` identity；大小写与特殊名称边界由 Nexus 黑盒测试确定。
   - 从 Git tag 生成 release list，只接受可规范化为 SemVer 的 tag；兼容 Nexus 3.93+ 对前导 `v`/`V` 的移除行为。
   - 按 tag 对应 commit 获取 source tree、manifest 和 source archive。tag 到 commit 的首次成功绑定必须持久化，不能只保存在单 JVM。
   - archive 生成算法、ZIP 根目录名、entry mode、timestamp、排序和 checksum 必须先与 Nexus 对比；黑盒基线未固定前，不承诺自行重打包与 Nexus 产生相同字节。
   - 缓存 release list、release metadata、manifest、archive、tag/commit binding、validator、cache TTL 和 negative cache。
   - GitHub 认证、API/Git rate limit、HTTP proxy、redirect 和 SSRF 防护使用共享 proxy 基础设施；凭据只保存在 secret/config 中。
   - 远端不存在返回短 TTL negative cache；远端临时不可用时按仓库 stale policy 服务已固定内容或返回 problem details。

3. Swift group
   - 新增 `swift-group` recipe，成员只允许 Swift hosted/proxy/group，并拒绝循环依赖。
   - Release list 按成员顺序聚合；同一 normalized package/version 冲突时第一命中成员优先。
   - 对选中的 version 建立来源绑定，release metadata、manifest、archive、checksum 和签名必须来自同一成员。
   - `/identifiers` 按成员顺序合并并去重，不能泄露调用者无权读取的成员映射。
   - Group 返回自身 repository base 下的 release URL、`Location` 和 `Link`，后续请求再按共享来源绑定路由。
   - Group 只读；publish `PUT` 返回 Nexus 对应的 `405`/problem details，不能因调用者拥有 `ADD` 权限而写入某个成员。

4. 认证与权限
   - `POST /repository/{repo}/login` 支持 SwiftPM Basic 和 Bearer token 凭据检查；成功返回 `200`，无效凭据返回 `401`。
   - 官方协议允许未实现 login 的服务端返回 `501`；这是 reference 的 optional 分支，对已实现 login 的 kkrepo 为 N/A。
   - Basic 复用现有用户名/密码认证；Bearer token 复用 API key、`GenericToken` 和 CI token 的 hash、过期、禁用和审计能力。
   - 匿名读取只在全局 anonymous fallback 已启用且匿名主体具备 repository `READ` 时允许。
   - Release list、metadata、manifest、archive、identifier lookup 和 `HEAD` 映射 `READ`。
   - Hosted 首次 publish 映射 `ADD`。重复 coordinate 始终 `409`；管理端删除映射 `DELETE`，删除后是否允许重新发布同版本必须由 Nexus 黑盒测试决定，默认 fail closed。
   - 登录路径、Authorization、token 和带凭据 registry URL 必须在 access log、trace、metrics label、exception 和 audit target 中脱敏。

5. 管理、搜索和迁移
   - Admin UI 支持创建/编辑 hosted、proxy、group；proxy 配置 GitHub remote/auth/TTL，group 配置有序成员。
   - Browse/Search 按 scope、name、version、source repository、checksum、签名状态、Swift tools version 和 source member 展示。
   - Repository REST API 和 UI/API 上传支持 Swift format，上传入口仍调用 hosted publish service。
   - Nexus repository definition migration 识别 `swift-hosted`、`swift-proxy`、`swift-group`。
   - Hosted data migration 迁移 archive、manifest、签名、原始 metadata 和 repository URL mapping；release list 与 release metadata 等可生成内容在目标端重建。
   - 迁移必须支持 dry-run、resume、checksum、报告和 source profile gate；无法证明 Nexus Swift content model 时默认 `MANUAL`，不猜表或 asset kind。

6. 兼容性与真实客户端测试
   - 新增 `SwiftRepositoryBlackBoxCompatibilityTest`，同一请求分别运行在 Nexus 3.94.x reference 和 kkrepo。
   - 使用 SwiftPM 5.7 验证 registry/proxy resolve/build，使用 Sonatype 推荐的 5.9+ 和当前稳定 Swift 6.x 额外验证 HTTPS login、publish 和 checksum 固定；不对 5.7 虚构它尚未提供的 CLI 命令。
   - 在可用 macOS runner 上验证 Xcode registry dependency；Linux 验证 SwiftPM，Windows 只验证 Nexus 文档声明支持的 proxy/resolve/build，不把 publish 列为 Windows 验收项。
   - 覆盖 hosted/proxy/group、匿名/Basic/Bearer、SCM-to-registry 转换、signed/unsigned archive、版本化 manifest 和多副本并发。
   - 只规范化 host、时间、临时 ID 等协议允许的非确定字段；状态码、media type、header 语义、checksum、signature 和 `Package.resolved` 结果不能忽略。

### 后续扩展和非目标

后续扩展：

- 明确标记的 generic registry proxy mode，用于级联代理任意符合 Swift Registry v1 的上游。
- SwiftPM availability endpoint，以及 registry status/maintenance 信息。
- 异步 publish、可取消 submission 和持久化进度查询。
- Scope ownership、组织委派和更细粒度的 namespace policy。
- 更多签名格式、服务端签名和签名证书信任策略。

明确不实现：

- 不把 Swift ZIP 当成 Raw asset 后直接返回；release list、metadata、manifest 和 checksum 都是协议合同。
- 不把 Git URL dependency 与 registry identity 当成天然相同；只有 `/identifiers` 和显式 SwiftPM 转换策略可以建立关联。
- 不执行上传或代理获得的 `Package.swift`、plugin、macro、build script 或源码。
- 不从不可信 ZIP 路径直接写本地文件系统，不允许 zip-slip、symlink escape、超大展开、超高压缩比或无限 entry 数。
- 不允许覆盖已发布 release，也不把移动 Git tag 静默变成已固定 release 的新内容。
- 不在关系数据库中存储 source archive 大字节；数据库只保存 metadata、状态、索引、hash 和 blob 引用。
- 不把进程内 map、local disk archive 或单节点 cache 当成 publish、tag binding、group source binding 或 negative cache 的唯一真相。
- 不实现协议未定义的 unpublish endpoint；管理端删除是 kkrepo 运维能力，必须与客户端协议隔离。

## URL 与路径设计

### 客户端配置

SwiftPM 使用 Nexus 风格 repository base：

```bash
swift package-registry set \
  "https://repo.example.com/repository/swift-group/"

swift package-registry login \
  "https://repo.example.com/repository/swift-group/login" \
  --username ci-user \
  --password '<password-or-token>'
```

`registries.json` 的推荐形态：

```json
{
  "authentication": {
    "repo.example.com": {
      "loginAPIPath": "/repository/swift-group/login",
      "type": "basic"
    }
  },
  "registries": {
    "[default]": {
      "supportsAvailability": false,
      "url": "https://repo.example.com/repository/swift-group/"
    }
  },
  "version": 1
}
```

项目可按 scope 配置不同 registry；kkrepo 不假设 `[default]` 是唯一入口。Registry base 必须保留尾部 `/`，文档和 UI 复制命令统一输出 Nexus 路径。

### Registry v1 路由

以下路径全部位于 `/repository/{repo}` 下：

| 方法与路径 | 行为 |
| --- | --- |
| `GET/HEAD /{scope}/{name}` | 列出 package releases；接受可选 `.json` |
| `GET/HEAD /{scope}/{name}/{version}` | 获取单个 release metadata；接受可选 `.json` |
| `GET/HEAD /{scope}/{name}/{version}/Package.swift?swift-version={toolsVersion}` | 获取默认或版本化 manifest |
| `GET/HEAD /{scope}/{name}/{version}.zip` | 下载不可变 source archive |
| `GET/HEAD /identifiers?url={scmUrl}` | 按 SCM URL 查询 registry identities |
| `PUT /{scope}/{name}/{version}` | 向 hosted 发布 release |
| `POST /login` | 校验 Basic 或 Bearer token 凭据 |

`POST /login` 在官方规范中是 optional endpoint。kkrepo 的实现返回 `200`/`401`；`501 Not Implemented` 只用于比较未提供 login 能力的其它 reference，不是 kkrepo 的正常验收结果。

路径解析约束：

- 先由通用 controller 解析 repository 名称，再把剩余 raw path 和 query 交给 `SwiftPathParser`；controller 不包含协议业务逻辑。
- scope/name 按 UTF-8 percent decoding 一次后校验官方 ASCII 规则，拒绝二次编码、`.`、`..`、空 segment、encoded slash 和反斜线。
- 内部 identity key 使用 locale-independent lowercase；response 可保留首次发布或上游的 display case，但不得因此产生第二个 package。
- Version 必须按 SwiftPM 接受的 SemVer 解析。Proxy 的 Git tag `v1.2.3`/`V1.2.3` 只在 tag-to-release 层规范化，客户端 path 始终使用 `1.2.3`。
- `.json` alias、尾斜线、大小写、encoded path、无 `Accept`、重复 query 参数和非法 `swift-version` 的结果必须由 Nexus 黑盒矩阵固定。

### Media type、header 与错误

| 请求资源 | 推荐 `Accept` | 成功 `Content-Type` |
| --- | --- | --- |
| Release list / metadata / identifiers / publish | `application/vnd.swift.registry.v1+json` | `application/json` |
| Manifest | `application/vnd.swift.registry.v1+swift` | `text/x-swift` |
| Source archive | `application/vnd.swift.registry.v1+zip` | `application/zip` |
| Error | 同请求 | `application/problem+json` |

协议要求：

- JSON 响应包含 `Content-Version: 1`。非法 API version 返回 `400`，合法但不支持的 version 返回 `415`。
- Error body 使用 RFC 7807 problem details；不得把 HTML error page、Spring 默认错误结构或堆栈返回给 SwiftPM。
- Release list 使用 `releases` object，可通过 `problem` 标识 unavailable release；版本顺序按 SemVer precedence 降序，但客户端不应依赖顺序。
- Release metadata 的 `source-archive` resource 保存 hex SHA-256，并在签名存在时同时返回 base64 signature 和 format。
- Manifest 返回 `Content-Disposition`，并通过 `Link rel="alternate"` 列出每个合法 `Package@swift-*.swift` 与 `swift-tools-version`。
- Archive 返回准确 `Content-Length`、`Content-Disposition`、稳定 validator 和可选 `Digest`；有签名时同时返回 `X-Swift-Package-Signature-Format` 与 `X-Swift-Package-Signature`。
- `429` 带 `Retry-After`；认证缺失返回 `401`。已认证但无权访问时使用 `403` 或隐藏式 `404`，最终跟随 Nexus reference 行为。

## 数据模型落地

优先复用 kkrepo 现有 JDBC 通用模型，同时为不可变发布、manifest、SCM URL mapping 和 proxy source binding 增加最小 Swift 专用表。MySQL 与 PostgreSQL migration 必须同步。

### 通用 component / asset

- 一个 Swift release 对应一个 component：`format=SWIFT`、`namespace=<scope>`、`name=<package name>`、`version=<normalized SemVer>`、`kind=swift-package-release`。
- `coordinate_hash` 基于 `(repository_id, scope_lc, name_lc, normalized_version)`，数据库唯一约束负责裁决并发 publish。
- Source archive asset path 为 `{scope}/{name}/{version}.zip`，原始 ZIP 只存 OSS/S3，`asset_blob` 保存 blob 引用和 MD5/SHA-1/SHA-256/SHA-512。
- 默认和版本化 manifest 各自是独立 asset/blob，logical path 与公开协议 path 一致；manifest 内容从已验证 archive 提取，不重复执行或重新格式化。
- Release list 与 release metadata 从共享行生成；如果物化为 JSON asset，其 revision 必须由数据库维护并可安全重建。

### Swift 专用状态

建议引入：

- `swift_release`：component id、scope/name normalized/display、SemVer、published_at、original metadata JSON、archive SHA-256、signature format、source/metadata signature blob reference、source kind 和 immutable revision。
- `swift_manifest`：release id、filename、tools version、asset id、SHA-256；`(release_id, tools_version)` 唯一。
- `swift_repository_url`：repository/package identity 与 normalized SCM URL 的映射，用于 `/identifiers`。
- `swift_proxy_source`：proxy release、upstream repository URL、tag、commit SHA、archive generation profile、verified_at 和 cache state。
- `swift_group_source_binding`：group、package/version、member repository、member revision 和 group configuration revision。

专用表只保存协议 identity、查询索引、不可变性和协调状态，不复制 archive/manifest 大字节。进程内 cache 只能按 repository revision + identity 设置短 TTL，丢失后可从数据库和 OSS/S3 重建。

## Hosted 发布流程

`RepositoryContentController` 只读取 HTTP method/path/header 和认证上下文，再委托 `SwiftHostedService`。Multipart、ZIP、manifest、metadata 和签名解析位于 `protocol-swift` / hosted service，不进入 controller。

同步 publish 流程：

1. 校验 repository online 且为 `swift-hosted`，认证主体具备 `ADD`。
2. 解析并规范化 scope、name、version；先做轻量存在性检查，但不以此代替数据库唯一约束。
3. 处理 `Expect: 100-continue`。只有认证、权限、repository type、路径和大小上限初检通过后才接受 body。
4. 流式解析 multipart 到有界临时文件；`source-archive` 必须唯一，其他 part 至多一个，未知或重复关键 part 拒绝。
5. 对原始 ZIP 流式计算 hash 和 size，校验 MIME、ZIP magic、最大压缩/展开大小、压缩比、entry 数、路径、symlink 和重复文件名。
6. 提取根 `Package.swift` 和所有合法 `Package@swift-X[.Y[.Z]].swift`；只解析首行 tools version，不执行 manifest。
7. 校验 metadata JSON schema、URL 数量/长度和 `originalPublicationTime`；规范化 `repositoryURLs` 用于 identifier mapping。
8. 若存在 source signature，要求 `X-Swift-Package-Signature-Format` 与 signature part 一致并限制 format/size；source/metadata signature 均持久化，source signature 按协议回传，不冒充客户端信任策略执行任意证书链。
9. 先把 archive、manifest 和可选 signature 写入 OSS/S3，并用请求唯一的 `.swift/staging/<uuid>/...` asset 路径记录临时引用，再在一个关系数据库事务中插入 component、公开 asset、`swift_release`、`swift_manifest`、URL mapping 和 browse node，同时移除 staging asset。
10. 所有副本都可运行 staging cleanup：默认只领取超过 24 小时的 `.swift/staging/` 行，按 repository path-prefix 索引有界扫描，并用 `FOR UPDATE SKIP LOCKED` 避免多副本重复处理；事务内移除 browse/asset 引用，只有最后一个引用消失时才把 blob 标记给全局 GC。进程在 promote 前终止也不会永久遗留隐藏对象。
11. 唯一约束冲突返回 `409`。事务失败后只清理确认未被引用的新 blob；不能删除并发请求已经引用的去重 blob。
12. 提交后失效共享 revision 关联的本地 cache，返回 `201`、`Content-Version: 1` 和当前 repository 下的 `Location`。

并发语义：

- 同一 package/version 并发发布由数据库唯一约束裁决，只允许一个 `201`，其余稳定返回 `409`。
- 同一 package 的不同 version 可并发；release list 从已提交行读取，不能用 read-modify-write 覆盖整个 JSON。
- Blob 上传成功不等于 release 可见；只有数据库事务提交后的 release 才能出现在 list/metadata/manifest/archive。
- 删除和发布同 coordinate 必须通过数据库状态/锁协调。默认 tombstone 阻止旧版本被意外复用，直到 Nexus reference test 明确允许。

## Proxy 缓存流程

GitHub-backed proxy 把 GitHub source repository 映射为 Swift Registry 资源。远端 tag、commit、archive 和 manifest 是输入；对客户端暴露的唯一协议仍是当前 `/repository/{repo}/...`。

### Identifier 与 release list

1. `/identifiers?url=` 解析允许的 GitHub URL 变体，拒绝非 GitHub host、credential-bearing URL、fragment 和可疑路径。
2. 将 owner/repository 规范化为候选 identity；实际大小写、`.git`、重定向和 renamed repository 行为先由 Nexus reference test 固定。
3. 查询 Git tags，按 Nexus 规则剥离单个前导 `v`/`V`，只保留合法 SemVer，并记录 tag、normalized version、commit SHA。
4. 在共享数据库中以 CAS/唯一约束固定 version 到 commit；多个副本并发发现同一 tag 时得到同一 binding。
5. 生成 release list 和 canonical/alternate `Link`。metadata TTL 到期可以发现新 tag，但不得改写已固定 version 的 commit。

### Manifest 与 archive

1. 根据持久化 tag/commit binding 获取 source tree。
2. Manifest 请求只读取根 `Package.swift` 或精确 `Package@swift-{version}.swift`；缺少精确版本时返回 `303` 到默认 manifest。
3. Archive 首次生成/下载时使用分布式 lease 合并同 coordinate 的跨副本 cache miss；等待者在 lease owner 提交后读取共享 blob。
4. 对 archive 算法结果记录 generation profile、SHA-256、size、entry manifest 和 commit SHA。没有完整验证的 blob 不进入可见状态。
5. 一旦 release metadata 向客户端公开 checksum，该 archive 成为不可变快照。TTL 只控制 tag/repository metadata 重校验，不控制已固定 archive 的替换。
6. GitHub 404/410 写短 TTL negative cache；403/429 保存 rate-limit 状态并遵守 `Retry-After`；5xx/timeout 按 stale policy 返回已固定内容或 problem details。

Redirect 必须限制 scheme/host、DNS rebinding 和私网地址；跨 host redirect 不转发 GitHub Authorization。临时 clone/archive 文件只位于有界 scratch 目录并在请求/任务结束后清理，不能成为可恢复真相。

## Group 聚合流程

Group 读取必须保持一个 version 的所有资源来自同一个成员：

1. 按 group member 顺序读取可见的 release list。
2. 以 `(scope_lc, name_lc, normalized_version)` 去重，第一个成员获胜。
3. 保存或校验 `swift_group_source_binding`，包含成员 id、成员 release revision 和 group config revision。
4. Release metadata、manifest、archive、checksum、signature 和 repository URL mapping 均路由到绑定成员。
5. 对外 URL 和 `Link` 重新渲染到 group base；resource body、checksum 和 signature 不跨成员拼接或改写。
6. 成员顺序、成员 revision 或 group 配置变化时使绑定失效并重新选择；已有客户端固定的 checksum 可能受成员切换影响，因此变更需要审计和可观察告警。

Group release list 的 ETag 由成员 revision 与生成 body 共同决定。进程内合并结果可以短 TTL 缓存，但成员选择和 revision 必须来自共享数据库。

## 安全与资源限制

- 仅 HTTPS 是 Swift Registry 规范默认。kkrepo 可在开发环境允许 HTTP，但 Admin/UI 文档必须警告 SwiftPM 登录凭据和 archive 完整性风险。
- 所有 ZIP entry 在解压前做 normalized path confinement；拒绝绝对路径、盘符、NUL、`..`、symlink/hardlink escape 和大小写冲突路径。
- 配置 archive compressed size、expanded size、entry count、single entry size、compression ratio、multipart part count 和解析 timeout 上限。
- 不编译、不执行、不动态加载任何 Swift 源码、plugin、macro、二进制 target 或 manifest。
- Metadata URL 只作为展示/identifier mapping 数据；server 不因为 `readmeURL`、`licenseURL` 或 `repositoryURLs` 自动抓取任意地址。
- Proxy 上游只允许配置白名单模式并使用统一 SSRF validator；GitHub credential、client Authorization 和 internal redirect header 不进入公开 metadata/cache key。
- Scope/name/version、repository 和 route 用低基数 metrics；token、SCM URL query、commit 之外的任意用户输入不得作为无界 label。
- 审计 publish、delete、repository config、group member change、proxy tag binding 冲突和签名状态，但不记录 credential 或签名私钥。

## 缓存、多副本与后台任务

- Release、tag/commit binding、group source binding、negative cache、rate-limit waterline、migration checkpoint 和 tombstone 都以共享数据库为真相。
- Archive、manifest 和 signature blob 以 OSS/S3 为真相；节点本地文件只作为请求期 scratch 或可丢失 read-through cache。
- 进程内 metadata cache 必须有明确 TTL，并把 repository/config/release revision 纳入 key；发布、删除和 group 配置变更后主动失效本节点，其他节点靠 revision 检查恢复。
- Proxy 同 coordinate 回源使用数据库 lease/分布式锁，包含 owner、deadline 和 fencing token；lease 过期可接管，旧 owner 不能覆盖新 owner 已提交结果。
- 后台 refresh、metadata rebuild、cleanup 和 migration job 通过持久化 marker/claim 领取，支持 retry/backoff 和幂等恢复。
- Readiness 不依赖本地 cache 是否预热。任一副本重启后仅凭数据库和 OSS/S3 即可继续提供已发布/已缓存 release。

## Nexus 迁移设计

Nexus 3.92+ Instance Migrator 对 Swift hosted 迁移 archive 和 manifest，自动生成 metadata 在目标端重建。kkrepo 采用相同边界：

1. Definition migration 迁移 repository type、name、online、blob store 映射、write policy、proxy remote/TTL 和 group member order。可恢复的 proxy secret 加密写入目标；被遮蔽或缺失的 secret 生成 `NEEDS_MANUAL_ACTION`，目标 proxy 保持 offline 且不写入占位 credential。
2. Data discovery 只对 Nexus 3.92.x-3.94.x 且 archive path、manifest shape 和 Swift asset attribute fingerprint 完整的 source 开启 `FULL`；版本超出范围、未知 schema/profile 或 shape 漂移返回 `NEEDS_MANUAL_ACTION`。
3. Hosted 迁移读取 source archive 和源端实际持久化的 attributes，逐个校验 path identity、size 和 SHA-256；默认/版本化 manifest 从 archive 校验并重建。Signature、原始 metadata 和 repository URL mapping 只在源导出实际包含时传入，不从上传请求或公开响应反推，也不伪造。
4. Release list、release metadata JSON、browse/search document 等生成物不作为唯一输入，在目标端从迁移后的共享行重建。原生 Nexus 3.94 虽接受签名、metadata 和 repository URL，却不把它们持久化或重新暴露；这种源 release 在目标端保持 unsigned、空 metadata 和无 URL mapping，并使用源 blob 时间作为 `publishedAt`。
5. Proxy cache 不作为 hosted publication 重放，也不猜测缺失的 tag/commit binding；当前迁移边界只保留可验证的 repository definition。
6. Group 不复制内容，只迁移有序成员和配置；目标端重新建立 source binding。
7. Job 支持 dry-run、resume cursor、批量 checkpoint、checksum、失败重试、manual action 和最终报告；重复运行不得重复 component/blob 引用。

当前 migration E2E 使用 Nexus 3.94 fixture，覆盖 Datastore H2 源到 MySQL 目标，以及 Datastore PostgreSQL 源到 MySQL/PostgreSQL 目标。每个 lane 都向源端发布带签名、metadata 和 repository URL 的 archive，并校验 Nexus 实际保留的 archive/checksum、默认 manifest、从 archive 重建的版本化 manifest、源 blob 时间、跨副本读取、restart/resume、proxy secret fail-closed 和精确行数幂等；同时断言目标没有伪造 Nexus 已丢弃的签名、metadata 或 URL mapping。独立 writer contract 覆盖源 attributes 确有这些可选字段时的原样恢复。3.92.x-3.94.x 范围与 shape drift gate 由 source-profile contract 覆盖，不把单一 live 版本扩大成范围外承诺。

## 兼容性测试矩阵

### HTTP 协议

- `Accept`：v1 json/swift/zip、无 header、非法 version、未支持 version 和错误 media type。
- Identity：scope/name 长度、字符、大小写、encoded path、同名冲突和 SemVer/pre-release/build metadata。
- Release list：`.json` alias、排序、latest link、unavailable problem、空/缺失 package，以及 Nexus 实际返回时的标准 `Link` relation；不自行发明官方 OpenAPI 未定义的 page query。
- Release metadata：id/version/resources/metadata/publishedAt、checksum、signature 和 predecessor/successor/latest links。
- Manifest：默认、所有合法版本化文件、`Link` 属性、精确 `swift-version`、缺失 fallback `303`、`HEAD` 和 conditional request。
- Archive：ZIP 原始字节、SHA-256、Digest、Content-Disposition、Content-Length、signature headers、Range、`HEAD`、ETag/304 和 redirect。
- Identifier lookup：GitHub HTTPS/SSH/`.git`/大小写/尾斜线、多个 identity、未知 URL、缺少 query 和 unauthorized member。
- Publish：合法/非法 multipart、`Expect: 100-continue`、metadata、signed archive、同步 `201`、重复 `409`、group/proxy `405`、过大 `413` 和不可处理 `422`。
- Auth：anonymous、Basic、Bearer token、kkrepo login `200`/`401`、过期/撤销 token 和权限隐藏语义；`501` 是官方 optional-login 分支，对 kkrepo 为 N/A。

### Nexus 与真实客户端

- Nexus 3.94.x 与 kkrepo 对同一 fixture 对比状态码、headers、JSON、manifest、archive layout 和 checksum。
- Nexus live 对比：GitHub tag `v1.2.3`/`V1.2.3`、renamed repository、canonical JSON/`Link`、group 重排/nested 和跨副本并发读；candidate black box 额外覆盖 active/revoked/expired `GenericToken` 和真实 5 MiB 限制拒绝。
- Unit/contract：普通/非法/移动 tag、1,200 tag 有界分页、cleanup、tombstone 和上游失败传播。
- Hosted publish 后立即在另一副本执行 resolve/build；同 coordinate 32 路并发 publish 只成功一次。
- Proxy 32 路并发首次下载只形成一个共享可见 archive；lease owner 终止后可恢复且 checksum 不变。
- Group 同 version 不同 archive/checksum 时，metadata、manifest、archive 和 signature 始终来自同一优先成员。
- `Package.resolved` 首次生成、重复构建、切换副本、重启和 cache 过期后 checksum 保持稳定。
- SwiftPM 5.7 registry/proxy resolve/build、5.9+/Swift 6.x 的 HTTPS login/publish/resolve/build，以及 Xcode registry dependency 的 E2E。
- S3-compatible live：多 MiB hosted package、共享 429/5xx 水位与 stale fallback、lease 过期接管、双副本 restart，以及 PostgreSQL/MinIO 的破坏式备份恢复。OSS Native 仅计入 adapter contract，不计为 live endpoint E2E。

## 实施记录

原设计按四个阶段拆分，最终在同一 feature 分支中一次性交付并保留以下验收边界。

### ✅ 协议骨架与 hosted read/publish

- `RepositoryFormat.SWIFT`、recipes、`protocol-swift`、path/media/error 模型。
- Hosted schema、multipart/ZIP/manifest 校验、immutable publish 和六个 Registry v1 endpoint。
- Basic/Bearer/anonymous 权限、最小 Admin recipe、Browse/Search 和 focused tests。
- Nexus hosted 黑盒基线和真实 `swift package-registry publish/resolve/build`。

验收：单副本和双副本环境都能 publish 后 resolve/build；重复/并发 publish 不覆盖；checksum、manifest、header 和 problem details 与 Nexus reference 结果一致。

### ✅ GitHub-backed proxy

- GitHub URL/identity、tag/SemVer、commit binding、manifest/archive 生成与共享 cache。
- TTL、negative cache、rate limit、remote auth、SSRF/redirect 和 distributed miss coalescing。
- `--replace-scm-with-registry` E2E，以及 `v`/`V` tag 和 versioned manifest 回归。

验收：冷/热 cache、多副本、上游限流/失败和重启下均不改变已固定 release checksum；Nexus proxy 矩阵通过。

### ✅ group 与管理面

- Group merge、source binding、identifier aggregation、cache invalidation 和只读语义。
- 完整 Admin UI、client recipe、Browse/Search、metrics 和 audit。
- 多成员冲突、nested group、权限和跨副本 E2E。

验收：冲突版本不跨成员拼装，成员/顺序变更可审计且最终一致，真实 SwiftPM/Xcode 通过 group 完成构建。

### ✅ 迁移与生产加固

- Nexus 3.92.x-3.94.x verified-shape definition/hosted data migration、dry-run/resume/checksum/report，以及版本/shape 漂移 fail-closed。
- Data repair/rebuild、cleanup/tombstone contract、多 MiB package live E2E、1,200 tag 有界单测，以及 PostgreSQL/MinIO 双副本破坏式备份恢复。
- Nexus 3.94.x reference matrix、H2 源到 MySQL 与 PostgreSQL 源到 MySQL/PostgreSQL 目标的 migration E2E，以及运维文档。

验收：Nexus hosted fixture 迁移后 component/release/archive/manifest/checksum 对账通过，重复迁移行数精确幂等，三个源/目标数据库 lane 和跨副本恢复通过；无法恢复的 proxy secret 必须留在 manual/offline 状态。

## 完成标准

Swift Package Registry 不能仅以“接口能返回 ZIP”视为完成。完整完成必须同时满足：

- [x] Hosted、GitHub-backed proxy 和 group recipe 均可从 Admin/API 创建并由真实客户端使用。
- [x] Registry v1 endpoint、media negotiation、problem details、headers、checksum、signature 和 immutable release 语义与官方协议/Nexus reference 对齐。
- [x] `swift package-registry login/publish`、registry identity dependency、SCM replacement、resolve/build 和 Xcode 场景进入 E2E。
- [x] MySQL/PostgreSQL 的 schema/persistence contract、PostgreSQL + MinIO S3-compatible 双副本 live E2E 与 OSS Native adapter contract 分层验证；不声称真实 OSS Native endpoint E2E。
- [x] Nexus 迁移支持 dry-run、resume、checksum 和报告；只有 3.92.x-3.94.x verified shape 是 `FULL`，版本/shape/secret 无法识别时 fail closed。
- [x] Compat test、模块测试、server 集成测试、真实 SwiftPM E2E 和文档全部进入 CI。
