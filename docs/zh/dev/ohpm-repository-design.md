# ohpm / HarmonyOS 仓库开发设计说明

本文记录 kkrepo ohpm / HarmonyOS 仓库格式的开发设计。目标不是重新发明鸿蒙包管理协议，而是对齐华为官方 ohpm 仓库接口协议，并按 kkrepo 的 MySQL + OSS/S3 + 多副本约束落地一套可托管、可代理、可组合、可观测的私有 ohpm 仓库。

## 当前支持状态

ohpm 仓库能力处于路线图设计阶段，当前代码中尚未实现 `ohpm-hosted`、`ohpm-proxy` 或 `ohpm-group` recipe。

完整实现目标一次性覆盖 hosted、proxy、group、迁移/导入和管理端能力：

- `ohpm-hosted`、`ohpm-proxy`、`ohpm-group` recipe。
- `GET /repository/{repo}/:group?/:package_name` 返回 package metadata。
- `GET /repository/{repo}/-/ping` 连通性检查。
- `PUT /repository/{repo}/:package_name` 普通发布 HAR/HSP 包。
- `POST /repository/{repo}/stream/:package_name` 流式发布大 HAR/HSP 包。
- `DELETE /repository/{repo}/:package_name` 下架指定 version 或整个 package。
- `POST`、`PUT`、`DELETE /repository/{repo}/-/package/:package_name/dist-tags/:tag` 管理 dist tag。
- HAR/HSP 下载地址仍使用 `/repository/{repo}/...` 下的仓库路径。
- Proxy metadata 和 `.har/.hsp` 下载缓存、TTL、negative cache、远端认证和下载 URL 重写。
- Group metadata 合并、成员优先级、dist-tag 合并和下载路由。
- Browse/Admin 创建、编辑、浏览、删除、UI/API 上传和管理端批量导入。
- 从已有 ohpm-repo 或文件目录导入 hosted 仓库数据，支持 dry-run、resume、checksum 校验和报告。
- Access Token 认证默认启用；官方 Login 公私钥认证按可选协议能力设计为可配置入口。
- 面向真实 `ohpm` 客户端的完整黑盒兼容性测试。

## 调研基线

实现前必须对照以下协议和参考行为：

- 华为 HarmonyOS 文档：ohpm 仓库接口协议。ohpm 客户端与 ohpm-repo 私仓通过 REST API 交互，包含 Fetch Metadata、Login、Publish、Unpublish、Ping、DistTags 六类 API。
- 华为 HarmonyOS 文档：ohpm 存储插件。协议允许 metadata 中的下载地址由 ohpm-repo 自己实现，也允许代理给其它文件服务；kkrepo 默认自己提供下载地址，外部文件服务只能作为 proxy/import 场景的输入，不能成为正确性真相。
- 华为 HarmonyOS 文档：ohpm 自定义认证插件。协议允许 Access Token 由仓库管理界面生成，也允许委托外部认证服务；kkrepo 应复用现有 API key / CI token 能力，不新增认证真相表。
- 当前 kkrepo npm 实现。ohpm 的 metadata、`dist-tags`、`versions`、`_attachments` 和 tarball 下载地址与 npm registry 形态相近，但 `.har/.hsp`、`integrity_hsp/resolved_hsp`、流式上传和响应码语义不同，必须作为独立协议实现。

关键结论：

- ohpm 是新格式，应新增 `RepositoryFormat.OHPM` 和 `protocol-ohpm` 模块，不应把 ohpm 伪装成 npm 仓库。
- ohpm package name 支持 `name` 和 `@group/name` 两类形态；URL 中 scoped package 经常以 `@group%2fname` 或 `@group/name` 出现。路径解析必须接受官方示例中的编码形式，并拒绝歧义路径。
- ohpm 的下载路径示例中，scoped package 的文件名部分也可能包含 `@group/package-version.har` 这样的多段路径，不能直接复用 npm tarball parser 的单段文件名假设。
- 普通发布通过 JSON body 携带 metadata 和 `_attachments`；流式发布通过 multipart/form-data 携带 `pkg_stream` 和 `metadata`。两种入口必须写入同一套 MySQL/OSS-backed 元数据和 blob 真相。
- HAR 包通常只有 `.har` 文件；应用内 HSP 包可能同时包含 `.har` 和 `.hsp` 两个客户端可下载文件，metadata 中用 `dist.integrity`、`dist.tarball`、`dist.integrity_hsp`、`dist.resolved_hsp` 表达。
- ohpm 5.0.1 及以上优先调用流式上传；如果流式上传接口返回 `404`，客户端会回退到普通上传。因此 kkrepo 实现该接口前必须让缺失路径真实返回 `404`，实现后必须兼容 multipart 行为。
- 官方响应码中 `598` 会触发 ohpm 5.0.1+ 重新上传。kkrepo 不应把所有服务端异常都映射为 `598`；只有明确可重试的上传中断或临时失败才使用它。
- `Login` 是官方可选 API。kkrepo 默认使用 Access Token，但完整设计需要保留 `POST /login` 的可配置实现入口；如果启用公私钥认证，必须把签名、nonce、timestamp、token 过期和审计纳入同一套认证设计。
- metadata 和 dist-tag 是跨副本共享状态，必须以 MySQL 为真相；进程内缓存只能作为可重建 TTL 热缓存。

## 功能范围

### 必须实现

1. ohpm hosted 仓库
   - 新增 `RepositoryFormat.OHPM` 和 `ohpm-hosted` recipe。
   - 新建 `protocol-ohpm`，封装 package id、path parser、metadata JSON、publish body、multipart stream、SSRI 和错误响应模型。
   - `GET /repository/{repo}/:group?/:package_name` 返回官方 metadata JSON。
   - `GET /repository/{repo}/-/ping` 返回 `{"code":200,"message":"success"}`。
   - `GET /repository/{repo}/{package}/-/{file}` 下载 `.har` 或 `.hsp` 原始字节。
   - `PUT /repository/{repo}/:package_name` 支持普通 publish JSON。
   - `POST /repository/{repo}/stream/:package_name` 支持流式 publish。
   - `DELETE /repository/{repo}/:package_name` 支持删除某个 version 或整个 package。
   - `POST /repository/{repo}/-/package/:package_name/dist-tags/:tag` 新增 tag。
   - `PUT /repository/{repo}/-/package/:package_name/dist-tags/:tag` 更新 tag。
   - `DELETE /repository/{repo}/-/package/:package_name/dist-tags/:tag` 删除 tag。
   - 发布时校验 package name、version、metadata、SSRI、附件数量、文件扩展名、HSP 必填字段和下载 URL。
   - 版本不可覆盖；同一 `(repository_id, package_name_lc, version)` 已存在时返回 ohpm 客户端可展示的错误。
   - 写入 component、asset、asset_blob、browse node，并在 attributes 中保存 ohpm metadata、dist、packageType、hspType 和校验信息。
   - 真实 `ohpm` 客户端验证安装、发布、流式发布、下架和 tag 管理。

2. ohpm proxy 仓库
   - 新增 `ohpm-proxy` recipe，支持 remote URL、远端认证、metadata TTL、content TTL、auto-block、negative cache 和 stale cache 策略。
   - 拉取上游 package metadata，保存远端 validator，并把 `dist.tarball` 和 `dist.resolved_hsp` 重写为本地 proxy 下载地址。
   - 下载 `.har/.hsp` 时按 metadata 中的 `integrity` 或 `integrity_hsp` 校验，校验失败不能进入缓存。
   - 远端 `404`、`410`、`451` 使用短 TTL negative cache；远端临时不可用时按仓库策略返回 stale cache 或客户端可读错误。
   - 上游需要 token 时，proxy 远端凭据只保存在加密配置/secret 中，不写入公开 metadata。

3. ohpm group 仓库
   - 新增 `ohpm-group` recipe，成员可包含 hosted 和 proxy ohpm 仓库。
   - 按成员顺序合并 `versions`、`dist-tags` 和 `time`，同一 version 或 tag 冲突时第一命中成员优先。
   - Group 对外返回自身下载地址；下载请求按 metadata 合并时的同一成员顺序路由到提供对应 version/file 的成员。
   - Group 只读，publish、stream publish、unpublish 和 dist-tags 写操作返回客户端可读错误。
   - 成员变化必须触发 group metadata cache 失效或在 TTL 内自然恢复，不能依赖单副本内存映射作为正确性来源。

4. 管理端、导入和迁移
   - Admin UI 支持创建、编辑 `ohpm-hosted`、`ohpm-proxy`、`ohpm-group`。
   - Browse UI 展示 package、version、`.har`、`.hsp`、dist-tags、packageType、hspType、checksum 和来源成员。
   - UI/API 上传支持 `.har`、`.hsp` 或官方 stream 包，并复用 hosted publish 的完整校验路径。
   - 管理端批量导入支持从已有 ohpm-repo 或文件目录读取 package metadata 和文件，写入 hosted 仓库。
   - 导入任务必须支持 dry-run、resume、checksum 校验、失败报告和 `MANUAL` 阻塞项。
   - ohpm 不属于 Nexus 原生格式；Nexus 迁移流程中不能猜测 ohpm content model，只能通过独立 ohpm 导入器接入。

5. 认证和权限
   - 默认采用 Access Token，复用现有 API key / GenericToken 生成、hash、过期、禁用、审计和 cache invalidation 能力。
   - `POST /login` 按官方可选 API 保留可配置实现入口；启用时必须验证 publishId、timestamp、nonce、signature，并签发有过期时间的短期 token。
   - Fetch Metadata、Ping 和下载走 repository `READ` 权限。
   - Publish 走 repository `ADD` 权限；覆盖已存在 metadata 或 tag 时走 `EDIT` 权限。
   - Unpublish 走 repository `DELETE` 权限。
   - DistTags 新增、更新、删除都走 repository `EDIT` 权限。
   - 认证失败返回 `401`，参数校验失败返回 `400`，未知路径返回 `404`，内部错误返回 `500`，明确可重试上传失败才返回 `598`。

6. 兼容性测试
   - 新增 `OhpmRepositoryBlackBoxCompatibilityTest`，直接对真实 kkrepo 实例和真实 `ohpm` 客户端运行。
   - 覆盖 hosted、proxy、group、导入、metadata、ping、普通 publish、stream publish、HAR 下载、HSP 双文件下载、unpublish、dist-tags、401/400/404/500/598 响应。
   - 对 scoped package 同时覆盖 `@group/name` 和 `@group%2fname` 路径。
   - 验证流式接口不存在时客户端能按官方语义回退到普通上传；实现后验证大包优先走 stream。
   - 验证 metadata 中的 `dist.tarball` 和 `dist.resolved_hsp` 在 hosted、proxy、group 和反向代理下使用正确外部 base URL。

### 迁移范围和非目标

迁移/导入范围：

- Nexus 不提供 ohpm 原生参考格式，ohpm 不进入 Nexus content model 迁移路径。
- 从已有 ohpm-repo 或文件目录迁移时使用独立导入器：读取 package metadata、校验下载文件、重算 SSRI、写入 hosted 仓库，并输出 dry-run / resume / checksum 报告。
- 导入器可以读取源端 metadata 中的外部下载地址，但写入 kkrepo 后必须把下载真相收敛到当前 hosted 仓库的 OSS/S3 blob。

明确不实现：

- 不把 ohpm 仓库注册为 npm 仓库，也不允许 npm 客户端访问 ohpm 仓库。
- 不把 Login 公私钥认证作为默认必启能力；它是可配置认证模式，不影响 Access Token 私仓场景。
- 不在 MySQL 中存储 HAR/HSP 大文件内容。
- 不依赖单个 JVM 的内存 map 保存 package metadata、dist-tag 或上传 session。
- 不把外部存储插件作为运行时正确性依赖；下载首先由 kkrepo 自己提供。

## URL 与路由设计

ohpm 使用普通 `/repository/{repo}/...` 仓库入口，不需要 Docker 那样的专用 `/v2/...` 路由。

推荐客户端配置形态：

```bash
ohpm config set registry https://repo.example.com/repository/ohpm-hosted/
ohpm config set //repo.example.com/repository/ohpm-hosted/:_authToken <token>
```

路由表：

| 请求 | 行为 |
| --- | --- |
| `GET /repository/{repo}/-/ping` | 连通性检查 |
| `GET /repository/{repo}/{name}` | 获取 unscoped package metadata |
| `GET /repository/{repo}/@{group}/{name}` | 获取 scoped package metadata |
| `GET /repository/{repo}/@{group}%2f{name}` | 获取 URL encoded scoped package metadata |
| `GET /repository/{repo}/{package}/-/{file}.har` | 下载 HAR 文件 |
| `GET /repository/{repo}/{package}/-/{file}.hsp` | 下载 HSP 文件 |
| `PUT /repository/{repo}/{package}` | 普通 publish |
| `POST /repository/{repo}/stream/{package}` | multipart stream publish |
| `DELETE /repository/{repo}/{package}` | 下架整个 package 或指定 version |
| `POST /repository/{repo}/-/package/{package}/dist-tags/{tag}` | 新增 dist tag |
| `PUT /repository/{repo}/-/package/{package}/dist-tags/{tag}` | 更新 dist tag |
| `DELETE /repository/{repo}/-/package/{package}/dist-tags/{tag}` | 删除 dist tag |
| `POST /repository/{repo}/login` | 可选 Login API；默认可关闭，启用时返回短期 token |

metadata 示例中的下载地址应由 kkrepo 动态重写：

```json
{
  "dist": {
    "integrity": "sha512-...",
    "tarball": "https://repo.example.com/repository/ohpm-hosted/@scope/pkg/-/@scope/pkg-1.0.0.har",
    "integrity_hsp": "sha512-...",
    "resolved_hsp": "https://repo.example.com/repository/ohpm-hosted/@scope/pkg/-/@scope/pkg-1.0.0.hsp"
  }
}
```

路径解析规则：

- package id 统一保存为官方形态：`name` 或 `@group/name`。
- scoped package 的 `%2f`、`%2F` 和路径 slash 形式应解析到同一个 package id。
- tarball 文件名中允许出现官方示例里的 `@group/name-version.har` 形态；实现时需要把 `/-/` 后面的剩余路径作为 logical file path，而不是只取一个 path segment。
- `DELETE /:package_name` 中的 `@version` 后缀表示删除指定版本；例如 `@scope%2fpkg@1.0.0`。解析时必须从最后一个 `@` 判断版本，不能把 scope 前缀误判为 version 分隔符。
- 未知 API 必须返回 `404`，以保留 ohpm 对 stream 回退的官方兼容语义。

## 数据模型落地

优先复用 kkrepo 现有 MySQL 通用模型；如果完整 proxy/group/导入实现证明共享模型不足，再补充 ohpm 专用 Flyway 表。

- package version 使用 `component` 行表示：`format=OHPM`、`namespace=<group or null>`、`name=<package name>`、`version=<ohpm version>`、`kind=ohpm-package`。
- 现有 `(repository_id, coordinate_hash)` 唯一模型保护 ohpm 的 `(repository, package, version)` identity。
- package root metadata 使用 metadata asset 保存，path 为 package id，例如 `@scope/pkg`。
- `.har` 和 `.hsp` 使用普通 asset 关联 asset_blob，path 使用 metadata 中对外暴露的下载路径。
- 大文件只存 OSS/S3；MySQL 保存 blob 引用、hash、size、content type、createdBy、createdByIp 和 attributes。
- component attributes 保存当前 version 的 ohpm metadata、dist 字段、packageType、hspType、dependencies、devDependencies、dynamicDependencies、发布时间。
- package root metadata 可按请求从 MySQL component 行生成，也可以保存一份 materialized JSON asset；无论采用哪种实现，MySQL component/asset/blob 仍是正确性真相。
- `dist-tags` 保存为 package root metadata 的一部分；更新 tag 时必须在 MySQL 事务中校验目标 version 存在。
- `time.created`、`time.modified` 和每个 version 的发布时间由 MySQL 中的 package version 行稳定生成，避免多副本时间漂移导致 metadata 不一致。

如果 package 数量、tag 数量、导入增量状态或 group 合并成本明显变高，可以引入 `ohpm_package`、`ohpm_version`、`ohpm_dist_tag`、`ohpm_group_metadata_cache`、`ohpm_import_job` 等专用表。专用表必须服务于查询、合并、导入恢复或锁语义，不能复制 blob 数据。

## Hosted 发布流程

Controller 不实现协议细节，只负责读取 HTTP 请求、提取仓库名和路径、认证上下文，再委托给 `OhpmHostedService`。

普通 publish 流程：

1. 校验 repository online 状态和 format/type 为 `ohpm-hosted`。
2. 校验用户或 token 具备 repository `ADD` 权限。
3. 解析 path package id，并校验 body 中 `_id`、`name` 与 path 一致。
4. 解析 `versions`，要求本次发布只新增一个 version；如果官方客户端未来会一次提交多个 version，需要先用真实客户端确认再扩展。
5. 校验 `dist-tags.latest` 指向存在的 version。
6. 解析 `_attachments`：
   - HAR 包必须包含一个 `.har` 附件。
   - HSP 包可以包含 `.har` 和 `.hsp` 两个附件。
   - 附件 `length` 必须与解码后的 byte 长度一致。
7. 计算 `.har` 和 `.hsp` 的 SHA-512，校验 `integrity` 和 `integrity_hsp`。
8. 写入 OSS/S3 和 MySQL：
   - 先上传 blob 并计算 MD5、SHA-1、SHA-256、SHA-512。
   - 在事务内插入 component、asset、asset_blob、browse node。
   - 写入或重建 package root metadata。
9. 事务提交后失效 package metadata TTL cache。
10. 返回 `{"code":200,"message":"success","additionalMsg":""}`。

流式 publish 流程：

1. 校验 `Content-Type: multipart/form-data`。
2. 读取 `metadata` part，按普通 publish 的 metadata 规则校验。
3. 读取 `pkg_stream` part 到临时文件，避免大包进入内存。
4. 根据 metadata 判断 HAR/HSP 类型：
   - HAR stream 可以直接保存为 `.har`。
   - HSP stream 如果是 tgz 包，必须解包定位 `.har` 和 `.hsp`，分别计算 SSRI 并存储为客户端可下载文件。
5. 如果 stream 解析失败且属于客户端可修复问题，返回 `400`；如果属于临时 IO 或上游存储失败，可返回 `598` 触发客户端重试。
6. 随后写入 MySQL/OSS/S3，与普通 publish 共用同一套 writer。

并发语义：

- 同一 package/version 并发发布由 component coordinate 唯一约束裁决。
- 同一 package 不同 version 可并发发布；package root metadata 从 MySQL version 行生成或在事务内 CAS 更新。
- dist-tag 更新必须在事务内读取 package 当前版本集合并写回，避免两个副本互相覆盖。
- 上传 blob 成功但元数据事务失败时，writer 只能删除确认未被任何 asset_blob 引用的新 blob。

## Proxy 缓存流程

Proxy 仓库是完整 ohpm 仓库能力的一部分。它以 MySQL/OSS-backed asset 作为 cache 真相，进程内缓存只做 TTL 加速。

Metadata 请求流程：

1. 解析 package id。
2. 查找本地 metadata asset 或从 component 行重建已缓存 metadata。
3. 如果 cache 在 metadata max-age 内仍新鲜，直接返回并动态重写下载 URL。
4. 如果命中共享 negative cache，直接返回 `404`。
5. 带上已保存的远端 validator 请求上游 metadata。
6. 远端 `304` 时刷新 verification time 并返回缓存。
7. 远端 `200` 时保存 metadata，重写 `dist.tarball` 和 `dist.resolved_hsp` 指向本地 proxy。
8. 远端 `404` 时写入短 TTL negative cache；如果存在 stale cache 且仓库允许 stale，可继续返回 stale。

Download 请求流程：

1. 根据本地 metadata 找到对应 version 的 remote download URL。
2. 如果 `.har/.hsp` 已缓存且仍新鲜，直接返回。
3. 拉取远端文件，按 metadata 中 `integrity` 或 `integrity_hsp` 校验。
4. 校验成功后写入 OSS/S3、asset_blob、asset 和 component attributes。
5. 校验失败不能进入缓存，返回客户端可读错误。

## Group 合并流程

Group 仓库是完整 ohpm 仓库能力的一部分。它需要生成对 ohpm 客户端一致的 package metadata。

Metadata 合并规则：

- 按 group 成员顺序请求 eligible hosted/proxy 成员的同一 package metadata。
- 忽略成员的 package missing 状态，除非所有成员都 missing。
- `versions` 以 `(package, version)` 为键去重；冲突时第一命中成员优先。
- `dist-tags` 按成员顺序合并；同名 tag 冲突时第一命中成员优先。
- `time.created` 使用保留版本中的最早时间，`time.modified` 使用保留版本中的最新时间。
- 返回前把每个 version 的 `dist.tarball` 和 `dist.resolved_hsp` 改写为 group 自身下载地址。
- group metadata response 的 ETag 由合并 body hash 生成；Last-Modified 使用成员 response 中最新时间。

Download 解析规则：

- 根据 group metadata 合并时相同的成员顺序查找提供该 version/file 的成员。
- 找到后从成员仓库读取，不把 group 自身作为 blob 真相。
- 成员变化后 group cache 必须失效或自然 TTL 过期；正确性不能依赖旧的进程内成员映射。

Group 只读，publish、stream publish、unpublish 和 dist-tags 写操作都应返回与客户端可读的错误。

## 权限与认证

ohpm 客户端会把配置的 token 放到 `Authorization` header 中，kkrepo 应支持以下形态：

- `Authorization: Bearer <token>`，用于现有通用 bearer token。
- `Authorization: <raw-token>`，用于 ohpm 客户端保存的裸 token。
- `Authorization: GenericToken.<raw-token>`，用于 kkrepo 自生成 GenericToken。
- 如新增协议专用 token，可支持 `Authorization: OhpmToken.<raw-token>`。

需要新增的是 ohpm 协议侧 Authorization 适配，而不是新的 token 真相表。认证结果仍应映射为现有用户、用户组、角色和 repository privilege。

权限映射：

| ohpm 操作 | kkrepo 权限 |
| --- | --- |
| `GET /-/ping` | `READ`，也可按仓库 anonymous read 配置放行 |
| `GET /:package` | `READ` |
| `GET /:package/-/:file` | `READ` |
| `PUT /:package` publish | `ADD` |
| `POST /stream/:package` publish | `ADD` |
| `DELETE /:package` unpublish | `DELETE` |
| `POST /-/package/:package/dist-tags/:tag` | `EDIT` |
| `PUT /-/package/:package/dist-tags/:tag` | `EDIT` |
| `DELETE /-/package/:package/dist-tags/:tag` | `EDIT` |

错误响应：

- 成功：`{"code":200,"message":"success","additionalMsg":""}`。
- 参数错误：HTTP `400`，body 包含 `error` 或 `message`，保证 ohpm CLI 能打印。
- 认证失败：HTTP `401`。
- 未知路径：HTTP `404`，尤其要保留 stream fallback 语义。
- 内部错误：HTTP `500`。
- 可重试上传失败：HTTP `598`，仅用于临时失败。

## 多副本语义

ohpm 仓库实现不得依赖单 JVM 进程内状态作为唯一真相。

- repository 配置、package metadata、dist-tags、version、asset、blob 引用、upload audit 都以 MySQL 为真相。
- HAR/HSP 大文件只存 OSS/S3/File blob store；MySQL 只保存引用和校验信息。
- package metadata TTL cache 可放进进程内缓存，但必须能从 MySQL component/asset 重建。
- dist-tag 更新、unpublish 和 package root metadata 更新必须在 MySQL 事务中完成。
- group metadata cache 只能作为带 TTL 的 materialized view；成员仓库变化必须触发失效或在 TTL 内自然恢复。
- stream upload 的临时文件只能作为当前请求的本地中间态；请求结束后必须清理，不能作为跨副本恢复状态。
- 若支持断点续传或异步上传，需要使用 MySQL upload session 和分布式锁/marker 队列单独设计。

## Browse、管理和迁移

管理端需要覆盖完整 ohpm 仓库生命周期：

- 仓库创建页新增 `ohpm-hosted`、`ohpm-proxy`、`ohpm-group`。
- 仓库详情页显示 ohpm 格式、类型、blob store、write policy、online 状态。
- Proxy 仓库编辑页显示 remote URL、远端认证、metadata/content TTL、auto-block 和 stale cache 策略。
- Group 仓库编辑页显示成员顺序，并限制成员只能选择 ohpm 仓库。
- Browse UI 能展示 package、version、`.har`、`.hsp`、dist-tags、packageType、hspType、checksum 和来源成员。
- 搜索组件时支持 `format=ohpm`，显示 package name、version、repository、更新时间。
- 删除 package/version 时复用 ohpm hosted delete 路径，不能绕过 metadata 和 dist-tag 清理。
- 管理端批量导入提供 dry-run、正式导入、resume、失败重试和报告下载。
- 对无法读取的 token、权限或外部存储插件配置，应在报告中标记 `MANUAL`，不能假定迁移成功。

## 观测指标

建议新增指标：

- `kkrepo_ohpm_metadata_requests_total{repository,type,result}`
- `kkrepo_ohpm_download_requests_total{repository,type,result,artifact}`
- `kkrepo_ohpm_publish_requests_total{repository,result,mode}`
- `kkrepo_ohpm_unpublish_requests_total{repository,result}`
- `kkrepo_ohpm_dist_tag_updates_total{repository,result,operation}`
- `kkrepo_ohpm_stream_upload_bytes_total{repository}`
- `kkrepo_ohpm_stream_upload_retries_total{repository}`
- `kkrepo_ohpm_proxy_revalidate_total{repository,result,status}`
- `kkrepo_ohpm_group_merge_total{repository,result}`
- `kkrepo_ohpm_active_uploads{repository}`

审计事件应至少包含 package、version、operation、repository、user、token type、remote address、artifact path、hash 和结果。

## 实施顺序

1. 协议调研和真实客户端夹具
   - 固定 ohpm 客户端版本矩阵。
   - 准备 HAR、HSP、小包、大包、scoped package、dist-tag、unpublish 测试夹具。
   - 记录官方接口响应码和 CLI 输出。

2. core 和 recipe 接入
   - 新增 `RepositoryFormat.OHPM`。
   - 新增 `ohpm-hosted`、`ohpm-proxy`、`ohpm-group` recipe。
   - 更新 repository 创建、搜索、权限和 Roadmap 文档。

3. protocol-ohpm
   - 实现 `OhpmPackageId`、`OhpmPathParser`、metadata model、SSRI parser、publish body parser、error model。
   - 单元测试覆盖 URL encoding、scoped package、version suffix、download path 和 dist-tags path。

4. hosted read path
   - 实现 metadata GET、ping、HAR/HSP download。
   - 支持 ETag、Last-Modified、If-None-Match 和 If-Modified-Since。

5. hosted publish path
   - 实现普通 publish JSON 和 `_attachments` 解码。
   - 写入 component、asset、asset_blob、browse node 和 audit。
   - 用真实 ohpm 发布 HAR 包验证。

6. stream publish path
   - 实现 multipart `pkg_stream` + `metadata`。
   - 大包走临时文件，HSP tgz 解析后分别保存 `.har/.hsp`。
   - 覆盖 `404` fallback 和 `598` retry 语义。

7. mutation path
   - 实现 unpublish、dist-tags add/update/delete。
   - 保证 MySQL 事务和多副本一致性。

8. UI、搜索和运维
   - Browse/Admin 展示 ohpm package/version/file。
   - 支持 hosted/proxy/group 仓库创建和编辑。
   - metrics、audit 和管理端错误提示。

9. proxy/group 和导入实现
   - 实现 proxy metadata、HAR/HSP 下载缓存、negative cache、远端认证和 URL 重写。
   - 实现 group metadata 合并、下载路由和成员变化失效。
   - 实现管理端批量导入、dry-run、resume、checksum 校验和报告。

## 验收标准

完整验收：

- `ohpm-hosted` 仓库可以通过 `/repository/<repo>/...` 被真实 ohpm 客户端访问。
- `ohpm-proxy` 可以代理上游 metadata 和 HAR/HSP 下载，并按 TTL、negative cache 和 checksum 规则缓存。
- `ohpm-group` 可以按成员顺序合并 versions 和 dist-tags，下载路由到正确成员。
- `ohpm publish` 可以发布 HAR 包，随后 `ohpm install` 或等价下载流程可以拉取。
- 大于 stream 阈值的包可以通过 `/stream/:package_name` 发布。
- HSP 包发布后 metadata 中同时包含 `.har` 和 `.hsp` 下载信息，并且两个文件 checksum 与 metadata 一致。
- scoped package 在 `@scope/name` 和 `@scope%2fname` 两种路径下行为一致。
- dist-tags 新增、更新、删除后，metadata 立即反映变化。
- unpublish 单个 version 后，该 version 不再出现在 metadata；删除整个 package 后 metadata 返回 `404`。
- 未认证写操作返回 `401`；权限不足不写入任何 blob 或 metadata。
- 未知接口返回 `404`，stream fallback 行为不被破坏。
- 反向代理场景下 metadata 中的下载 URL 使用外部 host/proto/port。
- 多副本部署中任意副本发布后，其它副本可以从 MySQL/OSS-backed 真相读取同一 metadata 和文件。
- Browse/Admin 能展示和删除 ohpm package/version/file。
- 管理端批量导入支持 dry-run、resume、checksum 校验和报告。

## 参考资料

- Huawei HarmonyOS ohpm 仓库接口协议: https://developer.huawei.com/consumer/cn/doc/harmonyos-guides-V5/ide-interface-protocol-V5
- Huawei HarmonyOS ohpm 存储插件: https://developer.huawei.com/consumer/cn/doc/harmonyos-guides-V5/ide-ohpm-repo-storageplugin-V5
- Huawei HarmonyOS ohpm 自定义认证插件: https://developer.huawei.com/consumer/cn/doc/harmonyos-guides-V5/ide-custom-auth-plugin-V5
