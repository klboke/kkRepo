# Dart / Pub 仓库开发设计说明

本文记录 kkrepo Dart / Pub 仓库格式的开发设计。目标不是重新发明 Pub 协议，而是在 Dart 官方 Hosted Pub Repository Specification V2、pub.dev 公开行为和 Nexus Repository Pub 支持之间取兼容交集，并按 kkrepo 的 MySQL + OSS/S3 + 多副本约束落地一套可托管、可代理、可组合、可迁移、可观测的私有 Pub 仓库。

## 当前支持状态

Dart / Pub 仓库基础能力已完成实现：当前代码已包含 `RepositoryFormat.PUB`、`pub-hosted`、`pub-proxy`、`pub-group` recipe、Hosted Pub Repository V2 metadata/archive 路由、Nexus 3.92.0 兼容的 `api/archives/...` 下载 URL、content browse `version.json` 路由、MySQL-backed publish upload session、Bearer `PubToken` 认证、proxy metadata/archive cache、group metadata 合并、Browse/Admin 基础展示、UI/API `.tar.gz` 上传、Nexus Pub repository-data 迁移/导入，以及真实 `dart pub` / `flutter pub` client E2E 覆盖。

已用本地 Nexus 3.92.0 + PostgreSQL 参考实例确认：metadata 和单版本 endpoint 返回 `application/vnd.pub.v2+json`，metadata 中的 `archive_url` 指向 `/api/archives/<package>-<version>.tar.gz`，content browse 额外暴露 `<package>/<version>/version.json` 和 `<package>/<version>/<package>-<version>.tar.gz`，而 `.sha1`、`.sha256`、`.sha512`、`.md5` checksum sidecar HTTP 路由返回 404；checksum 通过 metadata 的 `archive_sha256`、blob checksum 和 Search/Browse 资产信息体现。

不影响当前 Pub 核心仓库完成口径的可选扩展包括 retraction/discontinued 等管理语义、advisories 等可选 endpoint，以及继续细化错误响应 body/content-type：Nexus 404/unsupported method 常返回通用 HTML 错误页，而 kkrepo 优先返回更适合 `dart pub` 的 Pub JSON；后续只有在真实客户端兼容性要求时才需要收敛这些非关键 body 差异。

当前实现覆盖 hosted、proxy、group、迁移/导入和管理端能力：

- `RepositoryFormat.PUB` 和 `pub-hosted`、`pub-proxy`、`pub-group` recipe。
- `GET /repository/{repo}/api/packages/{package}` 返回 Hosted Pub Repository V2 package metadata。
- `GET /repository/{repo}/packages/{package}/versions/{version}.tar.gz` 下载 package archive。
- `GET /repository/{repo}/api/packages/versions/new` 启动官方两步 publish 流程。
- Multipart upload、finalize upload、package metadata 重建、`archive_sha256` 校验和下载 URL 重写。
- Proxy 上游默认为 `https://pub.dev/`，支持 metadata、archive、checksum、validator、TTL、negative cache 和 stale cache。
- Group 按成员顺序合并 package versions，并把下载路由到提供该 version 的成员。
- 支持 `dart pub token add` 的 `Authorization: Bearer <token>` 认证。
- Browse/Admin 创建、编辑、浏览、删除、UI/API 上传、管理端批量导入。
- Nexus Pub repository-data 迁移/导入支持 source profile 识别、dry-run、resume、checksum 校验、失败报告和显式 full plan。
- Nexus 3.92.0+ Pub 参考实例兼容性测试，以及真实 `dart pub` / `flutter pub` 客户端验证。

## 调研基线

实现和后续变更必须对照以下协议和参考行为：

- Dart 官方文档：Custom package repositories。`dart pub` 支持第三方 package repository，通过 `hosted` dependency、`PUB_HOSTED_URL` 和 `publish_to` 指定自定义仓库；私有仓库使用 `dart pub token add <hosted-url>` 配置 bearer token。
- Dart 官方 Hosted Pub Repository Specification V2。自定义 Pub 仓库通过 `<hosted-url>/api/packages/<package>` 返回 metadata；archive 是 gzip tar；publish 使用 `GET /api/packages/versions/new`、multipart `POST` upload、`GET` finalize 的两步流程。
- pub.dev API 文档。pub.dev 实现 Hosted Pub Repository V2；额外官方 API 包含 package-name completion、package-names、publisher、score 等。未在官方文档中列出的公开 endpoint 不应作为 kkrepo 协议兼容基础。
- Sonatype Nexus Repository Pub 文档。Nexus 3.92.0 新增 Pub / Flutter 仓库支持，覆盖 proxy、hosted、group，支持从 `pub.dev` 代理、hosted 发布 versioned archive、生成 package metadata / `version.json` / checksum 信息，并支持 token 认证和启用匿名时的只读访问；实测 checksum 不以 `.sha1`、`.sha256`、`.sha512`、`.md5` sidecar HTTP 文件暴露。
- 当前 kkrepo Maven、npm、PyPI、Cargo、Docker 等实现。Pub 的 package metadata、archive download、proxy cache、group merge 和 token 认证复用现有 repository / component / asset / blob / permission 原语，协议解析、metadata shape 和 publish flow 独立在 `protocol-pub` 中实现。

关键结论：

- Pub 是独立仓库格式，已新增 `RepositoryFormat.PUB` 和 `protocol-pub` 模块，不把 Pub 伪装成 npm、PyPI 或通用 raw 仓库。
- Pub package namespace 是扁平 package name，不存在 npm scope 或 Maven group path。路径解析应拒绝包含 `/`、空 path segment、重复 URL 解码后不一致的 package id。
- Hosted URL 需要按 Pub 规范归一化为不带尾部 `/` 的仓库 URL；kkrepo 对外仍使用 `/repository/{repo}` 作为 hosted-url，并在 metadata、upload URL、finalize URL 和 archive URL 中稳定重写。
- Pub metadata 的 `archive_sha256` 是 archive 原始 gzip tar 字节的 SHA-256 hex，不能用解包内容、`pubspec.yaml` 内容或重新打包后的字节计算。
- `archive_url` 可以是临时 URL，但 kkrepo 默认返回 `/repository/{repo}/...` 下的稳定下载地址，让认证、审计、反向代理外部 URL 和 group download routing 保持可控。
- 如果 `archive_url` 仍以前缀形式落在同一 hosted-url 下，`dart pub` 下载 archive 时会继续携带 bearer token；这要求 kkrepo metadata 中的下载地址必须使用正确外部 base URL。
- Publish 是异步两步协议。`GET /api/packages/versions/new` 不能只返回进程内 session；upload session、临时 blob 引用、finalize 状态和过期清理必须以 MySQL 为真相，才能支持多副本。
- Pub 认证对 `401` 和 `403` 很敏感：无 token、无效 token 或过期 token 才返回 `401`；有效 token 权限不足必须返回 `403`，否则 `dart pub` 会删除本地 token。
- Nexus 已有 Pub 参考行为。本实现已用 Nexus 3.92.0+ 记录 hosted/proxy/group 的状态码、content-type、metadata 字段、`version.json`、checksum 字段和 sidecar 404 行为、错误响应、token challenge 和匿名读行为；后续变更仍需按兼容交集落地。
- 进程内缓存只能作为可重建 TTL 热缓存；package metadata、proxy validator、upload session、group merge 来源和 archive blob 引用都不能依赖单个 JVM。

## 功能范围

### 已实现核心能力

1. Pub hosted 仓库
   - 新增 `RepositoryFormat.PUB` 和 `pub-hosted` recipe。
   - 新建 `protocol-pub`，封装 package id、hosted-url 归一化、path parser、metadata JSON、publish upload session、pubspec 解析、archive 校验和错误响应模型。
   - `GET /repository/{repo}/api/packages/{package}` 返回 `application/vnd.pub.v2+json` package metadata。
   - `GET /repository/{repo}/api/packages/{package}/versions/{version}` 支持 Dart 2.8 前兼容 endpoint，响应从同一套 version metadata 派生。
   - `GET /repository/{repo}/packages/{package}/versions/{version}.tar.gz` 返回 archive 原始字节，可作为 metadata 中的 `archive_url`。
   - `GET /repository/{repo}/api/packages/versions/new` 创建 upload session，返回 multipart upload URL 和 fields。
   - `POST` multipart upload URL，接收字段和 `file` part，保存临时 upload blob，返回 `204` 和 finalize URL。
   - `GET` finalize URL，完成 archive 校验、MySQL 元数据写入、OSS/S3 blob 绑定、metadata cache 失效，并返回 success JSON。
   - 发布时校验 package name、version、`pubspec.yaml`、archive gzip tar 结构、archive size、checksum、禁止覆盖已存在 version。
   - 写入 component、asset、asset_blob、browse node，并在 attributes 中保存 pubspec metadata、archive SHA-256、archive size、publish user、upload session id、source client 和发布时间。
   - 生成或重建 Nexus 兼容的 package metadata 和 `version.json`；按 Nexus 3.92.0 参考行为保存 checksum 字段和 blob checksum，但不对 archive 或 `version.json` 暴露 `.sha1`、`.sha256`、`.sha512`、`.md5` sidecar 文件。
   - 真实 `dart pub publish`、`dart pub get`、`flutter pub get` 客户端验证。

2. Pub proxy 仓库
   - 新增 `pub-proxy` recipe，支持 remote URL、远端认证、metadata TTL、content TTL、auto-block、negative cache 和 stale cache 策略。
   - 默认 remote URL 为 `https://pub.dev/`，保存时按 hosted-url 规则归一化。
   - 拉取上游 `api/packages/{package}` metadata，保存远端 validator、cache_until 和 response hash，并把 `archive_url` 重写到 kkrepo proxy 下载地址。
   - 下载 archive 时按 metadata 中的 `archive_sha256` 校验；缺失 `archive_sha256` 时仍保存实际 SHA-256，但需要记录校验来源为 `computed-only`。
   - 远端 `404`、`410`、`451` 使用短 TTL negative cache；远端临时不可用时按仓库策略返回 stale cache 或 Pub 可读错误。
   - 上游需要 token 时，远端凭据只保存在加密配置/secret 中，不写入公开 metadata、asset attributes 或日志。
   - pub.dev 额外官方 API（package-names、publisher、score）只作为 Browse/Admin 增强或镜像预热输入；不要让 `dart pub get` 的正确性依赖这些额外 API。

3. Pub group 仓库
   - 新增 `pub-group` recipe，成员可包含 hosted、proxy 和 group Pub 仓库。
   - 对同一 package，按成员顺序合并 `versions`，同一 version 冲突时第一命中成员优先。
   - `latest` 由合并后的非 retracted version 按 Pub 版本排序规则计算；若 Nexus 参考行为不同，以参考行为为准并在兼容测试中记录。
   - 返回 group 自身的 `archive_url`；下载请求按 metadata 合并时的来源成员路由到对应 archive。
   - Group 只读，publish、upload、finalize 等写操作返回 Nexus 参考行为对应状态和 Pub error JSON。
   - 成员变化、成员 repository offline、proxy cache 刷新和 hosted publish 后必须触发 group metadata cache 失效或在 TTL 内自然恢复，不能依赖单副本内存映射作为正确性来源。

4. 管理端、导入和迁移
   - Admin UI 支持创建、编辑 `pub-hosted`、`pub-proxy`、`pub-group`。
   - Browse UI 展示 package、version、latest、archive size、archive_sha256、pubspec 摘要、retracted/discontinued 状态、来源成员、下载链接和 usage snippets。
   - UI/API 上传支持单个 `.tar.gz` archive，并复用 hosted publish 的完整校验、checksum、事务、权限和审计路径。
   - 管理端批量导入支持 Nexus Pub hosted/proxy 缓存数据导入，文件目录或 pub.dev 镜像目录导入可作为独立 importer 扩展。
   - Nexus 3.92.0+ Pub 迁移通过 source profile 证明 Pub content model、metadata、`version.json`、checksum、blob 引用和权限模型；不会把未知 raw 文件夹猜成 Pub 仓库。
   - 导入任务支持 dry-run、resume、checksum 校验、失败报告和 `MANUAL` 阻塞项。

5. 认证和权限
   - `dart pub token add <hosted-url>` 配置的 token 以 `Authorization: Bearer <token>` 发送；kkrepo 应复用现有 API key / GenericToken 的 hash、过期、禁用、审计和 cache invalidation 能力。
   - Fetch metadata、deprecated version metadata、archive 下载、advisories 和 Browse 读取走 repository `READ` 权限。
   - Publish init、upload 和 finalize 走 repository `ADD` 权限；如果未来支持 retraction、discontinued 或 metadata mutation，应走 repository `EDIT` 权限。
   - 管理端删除、隔离或清理 hosted version 走 repository `DELETE` 或管理员权限，不暴露成 Pub 客户端删除协议，除非 Nexus 参考行为和官方协议明确支持。
   - 无认证、无效 token 或过期 token 返回 `401`，并带 `WWW-Authenticate: Bearer realm="pub", message="..."`。
   - 有效 token 权限不足返回 `403`，避免 `dart pub` 删除本地 token。
   - 参数校验失败返回 `400` Pub error JSON；未知 package/version 返回 `404`；内部错误返回 `500`，并避免泄漏远端凭据和内部 blob key。

6. 兼容性测试
   - 新增 `PubRepositoryBlackBoxCompatibilityTest`，面向 Nexus 3.92.0+ 参考实例和 kkrepo 实例运行。
   - 覆盖 hosted、proxy、group、metadata、deprecated version endpoint、archive download、publish init、multipart upload、finalize，以及缺失 package、非法 package、非法 version、checksum sidecar、proxy/group publish init 等状态码级错误响应。
   - 对比 `Content-Type`、`Accept`、`WWW-Authenticate`、`archive_url`、`archive_sha256`、`version.json`、checksum sidecar 404、redirect 行为和反向代理外部 URL。
   - 404/unsupported method 的 body 和 `Content-Type` 需要单独记录策略：Nexus 3.92.0 多数情况下返回通用 HTML 错误页，kkrepo 当前返回 Pub JSON；除非真实客户端兼容性要求，否则优先固定状态码、auth challenge 和客户端行为。
   - 使用真实 `dart pub` / `flutter pub` 客户端覆盖 `pub get`、`pub add --hosted`、`PUB_HOSTED_URL`、`publish_to`、`pub token add`、`pub publish`。
   - 验证 Pub 客户端在 `401` 后会删除 token、在 `403` 后保留 token 的行为，防止权限错误被误报为 token 失效。

### 迁移范围和非目标

迁移/导入范围：

- Nexus Pub 支持从 3.92.0 起进入参考范围。kkrepo repository-data migration 已支持通过 preflight/source profile 识别 Pub hosted/proxy/group content model，并只在确认源端为 Pub 仓库后启用 Pub full 迁移。
- Hosted full 迁移读取 package metadata、Nexus `api/archives/<package>-<version>.tar.gz` 或标准 archive blob、checksum、published timestamp、browse metadata 和权限映射，写入当前 kkrepo hosted 仓库并重建 package/version metadata。
- Proxy cache 迁移遵守 repository-data migration 的显式 backup 语义：只有用户选择对应 Pub proxy，且 migration plan 为 `FULL` 时，才迁移已缓存 metadata/archive；否则 proxy 配置迁移后由远端自然回填。
- Proxy 迁移迁入已缓存 metadata/archive，并按策略重新校验远端；不能把过期 cache 当作无需验证的真实源。
- 迁移任务支持 dry-run、resume、checksum 校验、失败报告、`MANUAL` 阻塞项和 per-version checkpoint，失败后可从 MySQL job state 继续。
- 文件目录或 pub.dev 镜像导入属于独立 importer 扩展方向：扫描 `.tar.gz`、解析 `pubspec.yaml`、重算 SHA-256、生成 metadata，并输出 dry-run / resume / checksum 报告。

明确不实现：

- 不把 Pub 仓库注册为 npm、PyPI、raw 或 generic 仓库。
- 不维护与 pub.dev 账户、publisher、like、score、download count 完全一致的管理语义；这些可以作为 proxy 只读展示，不成为 kkrepo 权限或发布真相。
- 不支持把 git dependency 当作可解析 version range 的 Pub 仓库能力；Pub 官方也只把 git dependency 作为固定 revision 获取。
- 不在 MySQL 中存储 archive 大文件内容。
- 不依赖单个 JVM 的内存 map 保存 upload session、package metadata、proxy negative cache 或 group merge 来源。
- 不默认暴露 Pub 客户端删除已发布 version 的能力；清理和隔离是管理员操作。
- 不依赖未文档化的 pub.dev 公开 endpoint 实现协议兼容；如果用于 UI 搜索增强，必须标记为非协议依赖并具备降级路径。

## URL 与路由设计

Pub 使用普通 `/repository/{repo}/...` 仓库入口，不需要 Docker 那样的专用 `/v2/...` 路由。对 Pub 客户端来说，hosted-url 推荐配置为不带尾部 `/` 的仓库 URL：

```bash
dart pub token add https://repo.example.com/repository/pub-hosted
PUB_HOSTED_URL=https://repo.example.com/repository/pub-group dart pub get
```

依赖单个私有 package 时使用 hosted dependency：

```yaml
dependencies:
  example_package:
    hosted:
      url: https://repo.example.com/repository/pub-group
      name: example_package
    version: ^1.4.0
```

发布私有 package 时在 `pubspec.yaml` 中固定发布目标：

```yaml
name: example_package
version: 1.0.0
publish_to: https://repo.example.com/repository/pub-hosted
```

路由表：

| 请求 | 行为 |
| --- | --- |
| `GET /repository/{repo}/api/packages/{package}` | 获取 package metadata |
| `GET /repository/{repo}/api/packages/{package}/versions/{version}` | 兼容旧客户端的单版本 metadata |
| `GET /repository/{repo}/api/archives/{package}-{version}.tar.gz` | Nexus 3.92.0 metadata 中使用的 archive 下载 URL |
| `GET /repository/{repo}/packages/{package}/versions/{version}.tar.gz` | 下载 package archive |
| `GET /repository/{repo}/{package}/{version}/version.json` | Nexus content browse 兼容的单版本 JSON，`Content-Type: application/json` |
| `GET /repository/{repo}/{package}/{version}/{package}-{version}.tar.gz` | Nexus content browse 兼容的 archive alias |
| `GET /repository/{repo}/api/packages/versions/new` | 创建 publish upload session |
| `POST /repository/{repo}/api/packages/versions/upload/{session}` | multipart 上传 archive；具体 path 可调整，但必须由 `versions/new` 返回 |
| `GET /repository/{repo}/api/packages/versions/finalize/{session}` | finalize publish；具体 path 可调整，但必须由 upload `Location` 返回 |
| `GET /repository/{repo}/api/packages/{package}/advisories` | 可选安全公告 endpoint；未实现时不要在 metadata 中返回 `advisoriesUpdated` |
| `GET /repository/{repo}/api/package-names` | 可选管理/镜像增强；不作为 `dart pub` 协议必需入口 |
| `GET /repository/{repo}/api/package-name-completion-data` | 可选管理/搜索增强；不作为 `dart pub` 协议必需入口 |

metadata 示例：

```json
{
  "name": "example_package",
  "latest": {
    "version": "1.0.0",
    "archive_url": "https://repo.example.com/repository/pub-hosted/api/archives/example_package-1.0.0.tar.gz",
    "archive_sha256": "95cbaad58e2cf32d1aa852f20af1fcda1820ead92a4b1447ea7ba1ba18195d27",
    "pubspec": {
      "name": "example_package",
      "version": "1.0.0"
    }
  },
  "versions": [
    {
      "version": "1.0.0",
      "archive_url": "https://repo.example.com/repository/pub-hosted/api/archives/example_package-1.0.0.tar.gz",
      "archive_sha256": "95cbaad58e2cf32d1aa852f20af1fcda1820ead92a4b1447ea7ba1ba18195d27",
      "pubspec": {
        "name": "example_package",
        "version": "1.0.0"
      }
    }
  ]
}
```

路径解析规则：

- `package` 必须与 `pubspec.yaml` 的 `name` 一致，且遵守 Dart package name 规则。
- URL path 中的 package name 不允许包含 `/`；如果 URL 解码后出现 path separator，直接返回 `400`。
- `version` 必须与 `pubspec.yaml` 的 `version` 一致，按 Pub / Dart semver 规则解析。
- Archive 下载 path 只作为客户端可见地址；真实 blob key 不暴露在 URL、metadata 或日志中。
- `Content-Type`、错误 JSON、redirect 和 `Location` header 必须以 Nexus 参考行为和 Hosted Pub Repository V2 为准。

## 数据模型落地

Pub package/version/archive 优先复用 kkrepo 现有 MySQL 通用模型；官方 publish 的多步骤 upload session 已通过 `pub_upload_session` 专用 Flyway 表落地，保证多副本下 init、upload、finalize 和过期清理都有共享真相。

- package version 使用 `component` 行表示：`format=PUB`、`namespace=NULL`、`name=<package>`、`version=<pub version>`、`kind=pub-package`。
- 现有 `(repository_id, coordinate_hash)` 唯一模型保护 Pub 的 `(repository, package, version)` identity。
- `.tar.gz` archive 使用 `asset` 行关联 `asset_blob`，path 使用客户端可见下载路径，例如 `packages/example_package/versions/1.0.0.tar.gz`。
- 大文件只存 OSS/S3；MySQL 保存 blob 引用、MD5、SHA-1、SHA-256、SHA-512、size、content type、createdBy、createdByIp 和 attributes。
- component attributes 保存 pubspec JSON、environment、dependencies、dev_dependencies、executables、topics、homepage、repository、issue_tracker、screenshots、publish metadata、retracted/discontinued 状态。
- package root metadata 可以按请求从 MySQL component 行生成，也可以保存一份 materialized JSON asset；无论采用哪种实现，MySQL component/asset/blob 仍是正确性真相。
- `archive_sha256` 使用原始 archive 字节计算并保存到 asset attributes；proxy 下载必须先校验再登记 component/asset。
- `version.json` 可按请求从同一 version metadata 派生，或作为可重建 metadata asset 管理；对外路径为 `<package>/<version>/version.json`，`Content-Type` 为 `application/json`。Nexus 3.92.0 对 checksum sidecar HTTP 文件返回 404，kkrepo 不生成这类 sidecar，checksum 保存在 metadata 字段、asset/blob checksum 和 Browse/Search 信息中。
- proxy metadata asset 保存远端 response body、`ETag`、`Last-Modified`、cache_until、negative cache 状态和 upstream URL hash。
- group metadata response 可以按成员实时生成；如引入 materialized cache，必须保存来源成员、version 到 member 的映射、cache_until 和 invalidation watermark。
- `pub_upload_session` 是 publish upload session 的 MySQL 真相表，包含 repository_id、session_id、field_token、principal_user_id/principal_api_key_id、status、expires_at、temp blob reference、parsed package/version、pubspec_json、checksum、size、error_message 和 finalized_at。

如果 package 数量、metadata 查询、导入恢复或 group 合并成本明显变高，可以继续评估 `pub_package`、`pub_version`、`pub_group_metadata_cache`、`pub_import_job` 等专用表。专用表必须服务于查询、合并、导入恢复或锁语义，不能复制 archive blob 数据。

## Hosted 发布流程

Controller 不实现协议细节，只负责读取 HTTP 请求、提取仓库名和路径、认证上下文，再委托给 `PubHostedService`。

Publish init 流程：

1. 校验 repository online 状态和 format/type 为 `pub-hosted`。
2. 校验用户或 token 具备 repository `ADD` 权限。
3. 创建 upload session：生成 session id、field token、过期时间和审计上下文，写入 MySQL。
4. 返回 `application/vnd.pub.v2+json`，包含 kkrepo 本地 multipart upload URL 和 fields。

Multipart upload 流程：

1. 校验 `Content-Type: multipart/form-data` 和 `file` part。
2. 校验 session 存在、未过期、未完成、repository/user/token 与 init 一致。
3. 流式读取 archive 到临时文件或 OSS/S3 staging blob，避免大包进入内存。
4. 解析 gzip tar，定位并解析 `pubspec.yaml`。
5. 校验 package name、version、archive size、禁止 path traversal、禁止重复关键文件和不支持的 archive 结构。
6. 计算 SHA-256，保存到 upload session。
7. 返回 `204 No Content`，`Location` 指向 finalize URL。

Finalize 流程：

1. 校验 session 状态为 uploaded，且 finalize 未执行。
2. 在 MySQL 事务中检查同一 `(repository_id, package, version)` 不存在。
3. 把 staging blob 绑定为正式 `asset_blob`；插入 component、asset、browse node 和 metadata asset。
4. 从 MySQL version 行生成或更新 package root metadata 和 `version.json`；checksum 写入 metadata 字段、asset attributes 和 blob checksum，不生成 Nexus 未暴露的 checksum sidecar。
5. 事务提交后失效 package metadata、browse、group metadata TTL cache。
6. 返回 Pub success JSON；此时 `GET /api/packages/{package}` 必须能读到新 version。

并发语义：

- 同一 package/version 并发发布由 component coordinate 唯一约束裁决。
- 同一 package 不同 version 可并发发布；package metadata 从已提交 MySQL version 行生成，避免本地进程顺序差异。
- 同一 upload session 的 finalize 需要幂等：已成功 finalize 时返回成功或明确已完成结果，不能二次插入 version。
- upload blob 成功但元数据事务失败时，只能删除确认未被任何 asset_blob 引用的新 staging blob。
- 过期 upload session 清理由后台任务扫描 MySQL 状态并删除 staging blob；后台任务不能只扫描本地 JVM 内存。

## Proxy 缓存流程

Proxy 仓库是完整 Pub 仓库能力的一部分。它以 MySQL/OSS-backed asset 作为 cache 真相，进程内缓存只做 TTL 加速。

Metadata 请求流程：

1. 解析 package id。
2. 查找本地 metadata asset 或从 component 行重建已缓存 metadata。
3. 如果 cache 在 metadata max-age 内仍新鲜，直接返回并动态重写 archive URL。
4. 如果命中共享 negative cache，直接返回 `404` Pub error JSON。
5. 带上已保存的远端 validator 请求上游 `api/packages/{package}`。
6. 远端 `304` 时刷新 verification time 并返回缓存。
7. 远端 `200` 时保存 metadata，解析 versions，并把 `archive_url` 重写为本地 proxy 下载地址。
8. 远端 `404/410/451` 时写入短 TTL negative cache；如果存在 stale cache 且仓库允许 stale，可继续返回 stale。

Archive 下载流程：

1. 根据本地 metadata 找到对应 version 的 remote `archive_url` 和 `archive_sha256`。
2. 如果 archive asset 已缓存且仍新鲜，直接返回。
3. 拉取远端 archive，跟随 redirect，但禁止 redirect 到被安全策略拒绝的地址。
4. 按 `archive_sha256` 校验远端响应；checksum mismatch 不能进入缓存。
5. 校验成功后写入 OSS/S3、asset_blob、asset 和 component attributes。
6. metadata 缺失 checksum 时仍可缓存，但必须记录 checksum 来源和上游缺失状态，供审计和后续重校验。

## Group 合并流程

Group 仓库需要生成对 Pub 客户端一致的 package metadata。

Metadata 合并规则：

- 按 group 成员顺序请求 eligible hosted/proxy/group 成员的同一 package metadata。
- 忽略成员的 package missing 状态，除非所有成员都 missing。
- `versions` 以 `(package, version)` 为键去重；冲突时第一命中成员优先。
- 每个 version 保留来源成员 id，用于后续 archive download routing。
- `latest` 使用合并后的 version 集合计算，并保留来源成员的 pubspec/archive 信息。
- 返回时统一把 `archive_url` 重写为 group 自身 URL。
- 如果成员返回 `advisoriesUpdated`，group advisories 需要按来源成员聚合；第一阶段可以不支持 advisories，并且不在 group metadata 中声明。

Download 解析规则：

- 按 metadata 合并时的相同成员顺序查找 package/version。
- 找到来源成员后代理该成员的 archive download，或按来源成员的 archive asset 直接返回。
- 不能只凭同名 package/version 从第一个可读成员下载，否则 hosted 私有包和 pub.dev proxy 同名冲突时会取错 blob。

缓存语义：

- Group metadata cache key 包含 repository id、package、成员列表版本、水位和外部 base URL。
- 成员 repository 配置变更、hosted publish、hosted cleanup、proxy metadata revalidation 后必须更新 group invalidation watermark。
- 进程内 cache 丢失后可从成员 MySQL/OSS 状态重建，不影响正确性。

## 权限与认证

Pub 认证默认复用 kkrepo 已有 token 能力，不新增认证真相表。

- Token 类型已新增 `PubToken` 作为 Pub 客户端推荐 token domain；`GenericToken` 仍可用于能够发送 `Authorization: Bearer` 的脚本或自定义 HTTP 客户端。两者底层都进入现有 API key / token hash、过期、禁用和审计路径。
- `dart pub token add` 存储的 token 是 opaque string，服务端不能要求客户端发送额外 header。
- token 校验成功后再做 repository permission 判定。
- anonymous read 只在全局匿名访问启用且匿名用户具备 repository `READ` 权限时通过。
- proxy 远端凭据和用户访问 token 必须隔离；用户 token 不能转发给 pub.dev，远端 token 也不能暴露给客户端。
- 上传、下载、metadata、proxy miss、group miss 和权限失败都需要记录 audit event，包含 repository、package、version、principal、token type、source IP、user agent 和 request id。

错误响应：

| 场景 | 状态 | 说明 |
| --- | --- | --- |
| 缺少 token、token 无效或过期 | `401` | 带 `WWW-Authenticate: Bearer realm="pub", message="..."` |
| token 有效但权限不足 | `403` | 带 Pub bearer challenge，避免客户端删除 token |
| package name、version、pubspec 或 archive 无效 | `400` | Pub error JSON |
| package 或 version 不存在 | `404` | Pub error JSON |
| group 写操作 | 以 Nexus 参考行为为准 | 通常为 `400`、`403` 或 `405`，必须通过兼容测试固定 |
| 上游临时不可用 | `502` 或 stale cache | 按 proxy 策略和现有 kkrepo upstream error 语义 |
| 内部错误 | `500` | Pub error JSON，不泄漏内部 key |

兼容性测试优先锁定状态码和客户端可见行为。Nexus 3.92.0 对缺失 package、非法 package/version、proxy/group 非客户端写路径等错误通常使用通用 repository HTML 错误页；kkrepo 返回 Pub JSON 是更适合 `dart pub` 的协议错误体，是否改成 Nexus HTML 需要单独以客户端行为验证为准。

## Browse、管理和迁移

Browse UI：

- package 列表展示 package、latest version、version 数量、来源 repository、archive size、checksum 和发布时间。
- package 详情展示每个 version 的 pubspec 摘要、dependencies、environment、homepage、repository、issue tracker、topics、archive_sha256、retracted/discontinued 状态。
- proxy/group 详情展示来源成员和 cache 状态，方便排查同名 version 冲突。

Admin UI：

- `pub-hosted` 配置 storage、write policy、strict content validation、anonymous read 提示和 publish size limit。
- `pub-proxy` 配置 remote URL、远端 token、metadata/content TTL、negative cache TTL、auto-block、stale cache 和 checksum strictness。
- `pub-group` 配置成员顺序，并在保存时拒绝非 Pub format 成员。
- UI/API 上传 `.tar.gz` 走同一 `PubHostedService` 校验路径，不允许绕过 archive SHA-256、pubspec 和唯一约束。

迁移/导入：

- Nexus Pub source profile 已识别 repository recipe、content model、blob store、metadata asset、`version.json`、checksum、package/version identity 和权限映射。
- 导入任务 dry-run 输出 package/version 数量、archive 总大小、checksum 缺失数、冲突数、不可自动处理项。
- resume 使用 MySQL job state 和 per-version checkpoint，不能靠本地文件游标。
- checksum mismatch、缺失 `pubspec.yaml`、package/version 不一致、同名 version 冲突进入报告，并默认阻塞写入。

## 观测指标

当前 Pub 已通过通用 repository/proxy/blob 指标暴露 `format="pub"` 和 `pub_*` operation 标签，覆盖 metadata、archive、publish init/upload/finalize、proxy 回源和 blob 读写。若后续需要独立 Pub dashboard，可在通用指标之外扩展以下专用指标：

- `kkrepo_pub_metadata_requests_total{repository,type,result}`
- `kkrepo_pub_archive_downloads_total{repository,type,result,cache}`
- `kkrepo_pub_publish_sessions_total{repository,result}`
- `kkrepo_pub_publish_finalize_seconds{repository,result}`
- `kkrepo_pub_proxy_remote_requests_total{repository,remote,status,result}`
- `kkrepo_pub_group_merge_seconds{repository,result}`
- `kkrepo_pub_checksum_failures_total{repository,source}`
- `kkrepo_pub_upload_sessions_active{repository}`

日志需要携带 request id、repository、package、version、session id、principal、token type、source IP、user agent、remote URL hash、cache result 和 checksum result。不要记录 bearer token、远端凭据、signed URL query 或内部 blob key。

## 实现状态和后续扩展

已完成：

1. 协议调研和 Nexus 参考测试
   - 已搭建 Nexus 3.92.0+ Pub hosted/proxy/group 参考实例。
   - 已用真实 `dart pub` / `flutter pub` 记录 metadata、publish、download、token、匿名读、group 写操作和错误响应。
   - 已把 `version.json`、checksum sidecar 404、archive URL、auth challenge 和错误状态写入兼容性测试 baseline。

2. 仓库格式和基础模块
   - 已新增 `RepositoryFormat.PUB`、`pub-hosted` / `pub-proxy` / `pub-group` recipe、Admin UI 创建/编辑表单和 `protocol-pub` 模块。
   - 已实现 hosted-url、path parser、Pub error JSON、content-type、archive validator、pubspec parser 和 archive metadata helper。

3. Hosted 读取和发布
   - 已实现 package metadata、deprecated version endpoint、archive download、Nexus `api/archives` 下载别名和 content browse `version.json`。
   - 已实现 publish init、multipart upload、finalize、MySQL-backed upload session 和过期清理。
   - 已接入 `PubToken` bearer 认证、权限、审计和真实客户端测试。

4. Proxy 和 Group
   - Proxy 已支持 upstream metadata fetch、validator、negative cache、archive fetch、checksum、URL 重写和 stale cache。
   - Group 已支持 metadata merge、download routing、group readonly write response 和 cache invalidation。
   - 已验证 hosted 私有包 + pub.dev proxy 的单 group 工作流。

5. Browse/Admin/导入/迁移
   - 已补齐 Browse 展示、Browse 详情 usage snippets、UI/API 上传、bulk import、Nexus Pub migration preflight、报告和 repository request operation labels。
   - 已支持 Nexus Pub hosted full 迁移，以及显式选择且 plan 为 `FULL` 的 proxy cache 迁移。

6. 文档和客户端配方
   - 已更新中英文 client recipes、deployment notes、Roadmap、兼容性说明和 Pub 搜索/迁移说明。
   - 已提供 `PUB_HOSTED_URL`、hosted dependency、`publish_to`、token、CI 发布示例和 Browse/Search 入口说明。

后续可选扩展：

- 如果生产观测需要独立 Pub dashboard，可在通用 repository/proxy/blob 指标之外补充 Pub 专用 dashboard 和生产限流视图。
- 如果真实 `dart pub` / `flutter pub` 客户端兼容性要求收敛错误 body，再把非关键 Pub JSON/HTML 错误体差异纳入兼容性修订。
- 如果业务需要 retraction/discontinued 管理、advisories 或 pub.dev social/publisher/score 等增强能力，应按可选 endpoint 单独设计和验证。

## 验收标准

- `dart pub get` 能从 `pub-hosted`、`pub-proxy`、`pub-group` 拉取依赖。
- `flutter pub get` 能通过 `pub-group` 同时消费私有 hosted package 和 pub.dev proxy package。
- `dart pub token add https://repo.example.com/repository/pub-hosted` 后，`dart pub publish` 能发布新 version。
- 发布成功返回后，另一个副本立即能通过 `GET /api/packages/{package}` 看到新 version，并能下载 archive。
- 同一 package/version 并发发布只成功一次，失败方返回 Pub 客户端可读错误。
- Pub 客户端 `401`、`403` 行为符合规范：无效 token 被客户端删除，权限不足不会删除 token。
- Proxy 在远端可用时校验并缓存 metadata/archive；远端不可用时按配置返回 stale cache 或明确 upstream error。
- Group 同名 version 冲突按成员顺序稳定选择，metadata 和 download routing 使用同一来源成员。
- 反向代理部署下，metadata 中的 `archive_url` 使用正确外部 base URL，下载时携带 token 并命中 kkrepo。
- Nexus 参考兼容性测试覆盖 hosted/proxy/group 关键路径，并记录所有有意差异。
- 所有状态、缓存、upload session、metadata 和 blob 引用都满足多副本语义；杀掉任一副本不影响后续读写正确性。

## 参考资料

- [Sonatype Nexus Repository: Pub or Flutter Repositories](https://help.sonatype.com/en/pub-repositories.html)
- [Dart: Custom package repositories](https://dart.dev/tools/pub/custom-package-repositories)
- [Hosted Pub Repository Specification Version 2](https://github.com/dart-lang/pub/blob/master/doc/repository-spec-v2.md)
- [pub.dev API for developers](https://pub.dev/help/api)
- [pub.dev](https://pub.dev/)
