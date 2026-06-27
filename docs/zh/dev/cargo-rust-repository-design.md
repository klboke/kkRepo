# Cargo / Rust 仓库开发设计说明

本文记录 kkrepo Cargo / Rust 仓库格式的设计说明。目标不是重新发明 Rust 包管理协议，而是在 Cargo 官方 registry 协议和 Nexus Repository Cargo 行为之间取兼容交集，并按 kkrepo 的 MySQL + OSS/S3 + 多副本约束落地。

## 当前支持状态

Cargo / Rust 第一阶段仓库能力已实现，覆盖 hosted、proxy 和 group 仓库。本文继续作为协议兼容设计和验证说明使用。Cargo 仓库迁移能力当前为待定项，第一阶段暂不实现。

第一阶段支持：

- `cargo-hosted`、`cargo-proxy`、`cargo-group` recipe。
- Cargo sparse registry 协议，入口形态为 `sparse+http(s)://<host>/repository/<repo>/`。
- Hosted publish、download、index、yank 和 unyank。
- Proxy crates.io sparse index 和 `.crate` 文件缓存。
- Group 仓库按成员顺序合并 sparse index，并把下载请求路由到提供对应 crate version 的成员。
- Cargo token 认证、匿名/读/写权限、CI token 发布。
- 面向 Nexus 参考实例的 Cargo 黑盒兼容性测试，以及真实 `cargo` 客户端验证。

第一阶段不支持 Cargo git index 协议、`cargo search` 原生搜索、UI/API 组件上传、crates.io 风格 GitHub owner 邀请、删除已发布 crate version，以及 Nexus Cargo 仓库迁移。Nexus 原生 Cargo 支持也以 sparse protocol 为主，并明确不支持 Cargo 客户端原生搜索和 UI/API 上传组件。

## 调研基线

实现前必须对照以下协议和参考行为：

- Cargo Book: Registries。Cargo registry 由 index 和可选 web API 组成，alternate registry 通过 `.cargo/config.toml` 的 `[registries]` 配置，index URL 使用 `sparse+` 前缀时走 sparse protocol。
- Cargo Book: Registry Index。`config.json` 定义 `dl`、`api` 和 `auth-required`；每个 crate 对应一个 index 文件，每个版本一行 JSON；index JSON 追加后除了 `yanked` 字段外不应修改。
- Cargo Book: Registry Web API。`cargo publish` 使用 `PUT /api/v1/crates/new`，body 是 `metadata length + metadata JSON + crate length + .crate bytes`；`yank` 和 `unyank` 分别更新 index 中的 yanked 状态。
- Cargo Book: Registry Authentication。Cargo 把 registry token 放在 `Authorization` header 中；token 可来自 `credentials.toml`、环境变量或 credential provider。
- Cargo Book: Source Replacement。source replacement 要求替换源和原始源内容等价；混合私有包和 crates.io proxy 时，应优先作为 alternate registry 使用，不能假装是 crates.io 的精确镜像。
- Nexus Repository Rust / Cargo 文档。Nexus 支持 hosted、proxy、group Cargo 仓库，仅支持 sparse protocol；proxy remote URL 需要保留尾部 `/`；Cargo 版本要求 1.68+；支持 yank/unyank。
- Nexus Repository 3.73.0 与 3.77.0 release notes。3.73.0 是 Pro-only 原生 Rust / Cargo 初始支持，官方同时说明原生 Cargo 与旧 community plugin 不兼容，且 data migration 不支持；3.77.0 才随 Community Edition 把 previously Pro-only formats 正式放出来。kkrepo 后续兼容基线应以 3.77.0+ 参考实例为主。
- Nexus Repository 当前数据库形态。3.71.0 及以上不再支持 OrientDB，当前支持 embedded H2 和 external PostgreSQL。kkrepo 现有 Nexus 迁移链路主要围绕旧 OrientDB / Script REST API 补偿路径设计，不能直接假设适用于 3.77.0+ Cargo 数据。

关键结论：

- kkrepo 第一阶段应只实现 sparse registry。Cargo git index 需要维护可被客户端 clone/fetch 的 git 仓库，和 kkrepo 的 MySQL 真相、OSS/S3 blob 模型不匹配，且 Nexus 参考行为不要求第一阶段支持。
- Cargo sparse index 是大量小 HTTP 请求。服务端支持 `ETag`、`Last-Modified` 和 `304 Not Modified`，并用生成的 body hash 和 MySQL-backed updated timestamp 作为稳定缓存标识。
- `config.json` 是客户端入口合同。`dl` 应指向 kkrepo 的下载 endpoint，`api` 应指向同一仓库的 web API 根。`auth-required` 是 Cargo 协议里的客户端提示字段，不是 kkrepo 的仓库权限开关；hosted 仓库为了对齐 Nexus 3.77+ 始终返回 `auth-required: true`，proxy/group 仓库由 Admin UI 的 Authentication requirements 控制是否在本地 `config.json` 中包含该字段。
- Nexus 3.77+ 的 Cargo UI 文案说明：不勾选 Authentication requirements 也只有在实例启用 anonymous access 时才允许匿名访问。本地参考实例验证结果与此一致：关闭全局 anonymous 后，`config.json`、sparse index 和 `.crate` 下载都会返回 `401`；开启全局 anonymous 且匿名用户具备仓库 read 权限时，proxy/group 即使勾选该提示位也不会由服务端强制阻断匿名读取。因此 kkrepo 的 Cargo 读请求走普通 repository read 权限路径，未认证请求只在 anonymous fallback 启用并授权时通过；写操作必须认证并按 add/edit 权限判定。
- `.crate` 文件是不可变发布物。服务端必须按原始 `.crate` 字节计算 SHA-256，写入 index 的 `cksum`，不能用解包后内容或格式化后的元数据计算 checksum。
- Cargo publish 成功后客户端会轮询 index 等待新版本出现。kkrepo hosted publish 应在返回成功前完成 MySQL 版本行和 sparse index 可见状态的事务提交，避免客户端短时间内看不到刚发布的版本。
- `yank` 不删除 `.crate` 文件，只改变 index JSON 的 `yanked` 字段。已有 `Cargo.lock` 仍应可下载该版本；新解析不应选择 yanked 版本。
- crate index 文件名是小写，但 index JSON 里的 package name 是大小写敏感字段。为了避免同一 index path 上出现大小写冲突，kkrepo 应在同一仓库内禁止只差大小写的 crate name。
- Cargo 迁移需要重新设计源端读取策略。Nexus Cargo 正式放出时已经处在 H2/PostgreSQL 数据库时代，源端 repository、component、asset、token 和 blob 元数据读取方式可能不再等同于旧 OrientDB 脚本查询。第一阶段只实现协议能力和兼容测试，不承诺迁移 Cargo 仓库数据或 Cargo token。

## 功能范围

### 第一阶段必须实现

1. Cargo hosted 仓库
   - 新增 `RepositoryFormat.CARGO` 和 `cargo-hosted` recipe。
   - `GET /repository/{repo}/config.json` 返回 sparse registry 配置。
   - `GET /repository/{repo}/{index-path}` 返回 crate index 文件，支持 `ETag`、`Last-Modified`、`If-None-Match` 和 `If-Modified-Since`。
   - `GET /repository/{repo}/crates/{crate}/{version}/download` 返回 `.crate` 原始字节；`config.json` 的 `dl` 使用 `/crates` base URL，对齐 Nexus 和 Cargo 的默认 URL 拼接规则。
   - `PUT /repository/{repo}/api/v1/crates/new` 支持 `cargo publish --registry <name>`。
   - `DELETE /repository/{repo}/api/v1/crates/{crate}/{version}/yank` 支持 `cargo yank`。
   - `PUT /repository/{repo}/api/v1/crates/{crate}/{version}/unyank` 支持 `cargo yank --undo`。
   - 发布时校验 crate name、version、metadata、`.crate` gzip/tar 基本结构、`Cargo.toml` 中的包名和版本。
   - 版本不可覆盖；同一 `(repository_id, crate_name_lc, version)` 已存在时返回 Cargo 可展示的 JSON error。
   - 写入 component、asset、asset_blob、browse node，并在 component 和 asset attributes 中保存 Cargo version/index 元数据。

2. Cargo proxy 仓库
   - 新增 `cargo-proxy` recipe，支持 remote index URL、remote API/download URL、认证和 cache TTL。
   - 第一阶段 remote sparse index 推荐配置为 `https://index.crates.io/`，并在保存配置时规范化和校验尾部 `/`。
   - 读取远端 `config.json`，解析 remote `dl` 和 `api`，再生成指向 kkrepo proxy 仓库的本地 `config.json`。
   - 按 crate index path 拉取并缓存远端 index 文件，保存 `ETag`、`Last-Modified`、body hash、cache_until 和 negative cache 状态。
   - 下载 `.crate` 时根据本地 index entry 的 `cksum` 校验远端响应，缓存到 OSS/S3，并登记 asset/component。
   - 远端 404、410、451 应按 Cargo sparse 语义缓存为短 TTL negative entry，不应永久污染仓库。
   - 远端不可用时，如果缓存未过期或仓库配置允许 stale cache，可以继续服务已缓存 index 和 `.crate`；否则返回 Cargo 可读的 JSON error 或相应 HTTP 状态。

3. Cargo group 仓库
   - 新增 `cargo-group` recipe，成员可包含 hosted 和 proxy Cargo 仓库。
   - `config.json` 的 `dl` 和 `api` 指向 group 自身。
   - 对同一 crate index path，按成员顺序合并 version 行；同一 `(crate_name_lc, version)` 冲突时第一命中成员优先。
   - group index response 必须记录每个 version 的来源成员，下载时按相同成员解析规则找到 `.crate`，避免同名版本从错误成员下载。
   - 成员仓库变化必须通过重新解析成员 index 观察；任何 cache 都只能作为 TTL 热缓存，不作为正确性真相。
   - group 只读，publish、yank、unyank 返回 Nexus 参考行为对应状态。

4. 兼容性测试和真实客户端验证
   - 新增面向 Nexus 参考实例的 `CargoRepositoryBlackBoxCompatibilityTest`。
   - 对比 `config.json`、missing crate index、index ETag/304、private repository auth challenge、publish、download、yank、unyank、proxy cache 和 group merge 行为。
   - 使用 Cargo 1.68+ 和当前稳定版 Cargo 验证 `cargo fetch`、`cargo build --locked`、`cargo publish`、`cargo yank`、`cargo yank --undo`。
   - 验证 `.cargo/config.toml` 中 alternate registry 和 source replacement 两种配置，文档中明确二者适用边界。

### 可选加固和非目标

- Cargo git index protocol 为非目标。后续只有在明确 Nexus 迁移或旧客户端需求出现时再单独设计。
- `cargo search` 为非目标。管理 UI 和 browse UI 可以提供 kkrepo 自有搜索，但不承诺 Cargo client 原生搜索兼容。
- UI/API 组件上传为非目标。Cargo 包上传应通过 `cargo publish`，与 Nexus 行为保持一致。
- owner list/add/remove endpoint 第一阶段可返回未实现或只读最小响应，不作为权限真相。kkrepo 权限以用户、组、角色、仓库权限和 CI token 为准。
- 已发布 crate version 删除为非目标。即使后续为了合规提供管理员隔离能力，也必须和普通 yank 语义区分。
- Nexus Cargo 仓库迁移为待定项，第一阶段暂不实现。后续如果启动，需要单独设计 H2/PostgreSQL 源端读取、官方/非公开 API 可用性、token 迁移、blob 校验和切换验证方案。

## URL 与路由设计

Cargo 使用普通 `/repository/{repo}/...` 仓库入口，不需要 Docker 那样的专用 `/v2/...` 路由。

推荐客户端配置：

```toml
[registries]
cargo-hosted = { index = "sparse+https://repo.example.com/repository/cargo-hosted/" }
cargo-proxy = { index = "sparse+https://repo.example.com/repository/cargo-proxy/" }
cargo-group = { index = "sparse+https://repo.example.com/repository/cargo-group/" }

[registry]
default = "cargo-group"

[registries.cargo-hosted]
token = "Basic <kkrepo-user-token>"
```

若只把 kkrepo 当作 crates.io 镜像使用，可以配置 source replacement：

```toml
[registries]
cargo-proxy = { index = "sparse+https://repo.example.com/repository/cargo-proxy/" }

[source.crates-io]
replace-with = "cargo-proxy"
```

如果 group 同时包含私有 hosted 和 crates.io proxy，不应把它描述为 crates.io 的精确 replacement；应让业务 crate 通过 `registry = "cargo-group"` 或 `registry.default` 使用 group。

路由表：

| 请求 | 行为 |
| --- | --- |
| `GET /repository/{repo}/config.json` | 返回 sparse index 配置，包含 `dl`、`api`，必要时可包含兼容用 `auth-required` |
| `GET /repository/{repo}/1/{crate}` | 1 字符 crate index 文件 |
| `GET /repository/{repo}/2/{crate}` | 2 字符 crate index 文件 |
| `GET /repository/{repo}/3/{first}/{crate}` | 3 字符 crate index 文件 |
| `GET /repository/{repo}/{first2}/{second2}/{crate}` | 4 字符及以上 crate index 文件 |
| `PUT /repository/{repo}/api/v1/crates/new` | publish 新 crate version |
| `GET /repository/{repo}/crates/{crate}/{version}/download` | 下载 `.crate` 文件；对外 advertised endpoint |
| `GET /repository/{repo}/api/v1/crates/{crate}/{version}/download` | 兼容保留的下载入口，不写入 `config.json` |
| `DELETE /repository/{repo}/api/v1/crates/{crate}/{version}/yank` | yank 已发布 version |
| `PUT /repository/{repo}/api/v1/crates/{crate}/{version}/unyank` | unyank 已发布 version |
| `GET /repository/{repo}/api/v1/crates` | 第一阶段返回不支持搜索的 Cargo JSON error |
| `GET /repository/{repo}/me` | 可返回登录说明页或 404；用于 `cargo login` 展示，不作为 API 真相 |

`config.json` 示例：

```json
{
  "dl": "https://repo.example.com/repository/cargo-hosted/crates",
  "api": "https://repo.example.com/repository/cargo-hosted/"
}
```

路径解析规则：

- index path 根据 Cargo 官方规则由 crate name 计算，服务端必须拒绝不匹配路径，避免同一 crate 通过多个 path 命中。
- index 文件名使用小写 crate name；写入时保留原始 `name` 字段，但唯一约束使用小写名称。
- index response 使用 `application/json` 或 `text/plain` 均需和 Nexus 参考行为对齐；body 是 newline-delimited JSON，每个 version 一行。
- `api/v1` endpoint 的错误响应使用 Cargo 规定的 `{"errors":[{"detail":"..."}]}` 结构。

## 数据模型落地

第一阶段复用 kkrepo 现有 MySQL 通用模型，不新增 Cargo 专用 Flyway 表。

- crate version 使用 `component` 行表示：`format=CARGO`、`namespace=NULL`、`name=<小写 dash/underscore 归一化 crate name>`、`version=<Cargo version 唯一键>`、`kind=crate`。
- 现有 `(repository_id, coordinate_hash)` 唯一模型保护 Cargo 的 `(repository, crate, version)` identity。
- Cargo 客户端可见的 index JSON line 存在 `component.attributes.indexEntry`；crate asset path 和 browse 元数据存在 component/asset attributes。
- `.crate` 对象使用 `asset` 行关联 `asset_blob` 行。大对象只存 OSS/S3；MySQL 保存 blob 引用、hash、size、content type 和 attributes。
- hosted 和 proxy crate 写入都按原始 `.crate` 字节计算 SHA-256。proxy 下载会先校验远端 index `cksum`，checksum 不一致时不持久化元数据。
- proxy `config.json` 和 sparse index 文件以 metadata asset 方式缓存，body 和远端 validator（`ETag`、`Last-Modified`）通过 MySQL asset/blob 引用和 OSS/S3 字节持久化。
- proxy remote auto-block 状态复用现有 MySQL `ProxyStateDao`。远端 `404`、`410`、`451` 使用共享 TTL `ProxyNegativeCache`，按 proxy 仓库和 path 隔离。
- group index response 按成员顺序从成员 index 重新生成。第一阶段不存专用 group merge 表；正确性来自每次请求解析成员的 MySQL/OSS-backed 状态。

后续如果 Cargo 需要更强查询能力、更完整审计报告，或现有共享仓库原语以外的跨副本 lease 语义，可以再评估 `cargo_crate`、`cargo_version`、`cargo_group_index_cache` 等专用表。

## Hosted 发布流程

`PUT /api/v1/crates/new` 不在 controller 中实现协议细节。Controller 只负责读取 HTTP 请求，认证和授权由 repository security filter 处理，再委托给 `CargoHostedService`。

已实现流程：

1. 校验 repository online 状态和 format/type 为 `cargo-hosted`。
2. 校验仓库 write policy，并要求 publish 具备 repository `ADD` 或 `EDIT` 权限。
3. 解析 Cargo publish body：
   - 读取 4 字节 little-endian metadata 长度。
   - 解析 metadata JSON 并校验 `name` 和 `vers`。
   - 读取 4 字节 little-endian `.crate` 长度。
   - 按声明长度把 `.crate` bytes 缓冲到临时文件。
4. 校验 `.crate`：
   - gzip/tar 可读。
   - 包含 `Cargo.toml`。
   - `Cargo.toml` 中 package name/version 与 publish metadata 匹配。
5. 写入 OSS/S3 和 MySQL：
   - 上传原始 `.crate` 字节并计算 MD5、SHA-1、SHA-256、SHA-512。
   - 通过现有 DAO 唯一约束插入 `asset_blob`、`component`、`asset` 和 browse nodes。
   - 把 Cargo index entry 写入 `component.attributes.indexEntry`。
   - 事务提交后失效 asset metadata cache。
6. 返回 Cargo publish success JSON。index GET 从已提交 component 行读取，所以新版本可见性不依赖本地进程状态。

并发语义：

- 同一 crate/version 并发发布由现有 component coordinate 唯一约束裁决。
- 同一 crate 不同 version 可并发发布；index response 从 MySQL component 行稳定生成。
- 元数据持久化失败时，writer 会在确认没有 live MySQL 元数据引用后删除新上传 blob。

## Proxy 缓存流程

Proxy 仓库以 MySQL/OSS-backed asset 作为 cache 真相，进程内/共享 cache 只做 TTL 加速。

Index 请求流程：

1. 根据请求 path 解析 crate name 和 index path。
2. 按 repository id 和 path 查找已缓存 sparse index metadata asset。
3. 如果 cached asset 在仓库 metadata max-age 内仍新鲜，直接返回。
4. 如果命中共享 negative-cache，直接返回 Cargo not found，不请求远端。
5. 如果 proxy remote 已 auto-block，有 stale cache 则继续返回，否则返回 Cargo 可读的 upstream error。
6. 带上已保存的远端 `ETag` 或 `Last-Modified` revalidate remote sparse index。
7. 远端 `304` 时刷新 cached asset verification time 并返回缓存 body。
8. 远端 `200` 时把 index body 写成 metadata asset，把远端 validator 写入 blob attributes，并清理 negative cache。
9. 远端 `404/410/451` 时记录远端成功；有 stale cache 则返回 stale cache，否则写入短 TTL negative entry 并返回 Cargo not found。

Download 请求流程：

1. 通过本地 index cache 路径读取远端 index entries，解析 crate/version。
2. 按 upstream `config.json` 的 `dl` 构造 remote download URL，支持 `{crate}`、`{version}`、`{prefix}`、`{lowerprefix}`、`{sha256-checksum}` 模板替换。
3. 如果 `.crate` asset 已缓存且在 content max-age 内仍新鲜，直接返回。
4. 使用保存的远端 validator revalidate 或下载远端 `.crate`。
5. 远端 `304` 时刷新 verification time 并返回缓存文件。
6. 远端 `200` 时流式写 OSS/S3 并计算 hash，校验 SHA-256 等于 index `cksum` 后登记 asset/component 元数据。
7. checksum mismatch 时，如果新上传 blob 未被引用则删除，并返回 Cargo 可读的 upstream error；坏响应不能进入缓存。

## Group 合并流程

Group 仓库需要生成对 Cargo 客户端一致的 sparse index。

Index 合并规则：

- 按 group 成员顺序请求 eligible hosted/proxy 成员的同一 index path。
- 忽略成员的 missing crate 状态，除非所有成员都 missing。
- 对每行 index JSON 解析 `(name, vers)`。
- 同一 version 只保留第一个成员的 line。
- 返回时保留成员 index JSON 字段，避免无意义格式化。
- response `ETag` 由合并 body 生成，`Last-Modified` 使用成员 response 中最新的 `Last-Modified`。

Download 解析规则：

- 按 index merge 使用的相同成员顺序遍历。
- 委托第一个能服务该 crate/version 的 eligible 成员下载。
- 如果某个成员返回 upstream failure，先记录并继续查找后续成员；如果所有成员都没有该 crate，则返回最后一个 upstream failure 或 not found。
- group 不复制 `.crate` blob；缓存由成员仓库拥有。

## 权限与认证

Cargo token 不需要单独设计一套完全独立的存储模型。它应优先复用现有 `api_key` 的 hash、加密 payload、owner、状态、过期时间、审计和 cache invalidation 能力，像 npm 的 `NpmToken`、NuGet 的 `NuGetApiKey` 一样用协议 domain 或 token type 区分用途。Cargo 第一阶段建议新增约定 domain：`CargoToken`，但最终 domain/scope 设计必须以 Nexus Cargo 参考实例验证为准。

需要新增的是 Cargo 协议侧 token 适配，而不是新的 token 真相表：

- 用户自创建 token 时，创建 `domain=CargoToken` 的 API key，返回值仍采用现有 `<domain>.<raw-token>` 形态。
- 迁移时，必须迁移 Nexus Cargo 支持的所有 token 形态，并保证迁移后原 Cargo 客户端凭据不用重新签发即可继续使用。不能只迁移 `api_key` 表中恰好暴露的 `NpmToken` 类数据。
- 如果 Nexus Cargo 使用 repository-scoped User Token 或其它不在现有 `api_key` 导出中的 token 存储，迁移工具必须新增对应导出和导入路径。只有源 Nexus 确实不暴露 token 校验材料时，才能在 preflight/报告中标记为 `MANUAL`，且该状态不能算作 Cargo token 迁移验收通过。
- 如果 Nexus 允许同一用户为多个 Cargo 仓库创建多个 token，kkrepo 必须全部保留。实现时不能被现有 `UNIQUE(domain, owner_source, owner_user_id)` 语义覆盖成每个用户只有一个 Cargo token；可以通过扩展 `api_key` 唯一维度、引入 repository scope，或使用稳定的 repository-scoped domain 命名来解决，但必须由 Nexus 兼容测试锁定。
- Cargo endpoint 鉴权应把解析出的 token 委托给现有 API key 校验和权限判定，认证结果仍是 API key 对应 owner 用户。
- API key 的 owner、过期时间、状态、last used、加密 payload、审计和 cache invalidation 均沿用现有实现。

Cargo Web API 的 `Authorization` header 值就是 Cargo 保存的 token 字符串。和 npm `_authToken` 常见的 `Authorization: Bearer <token>` 不同，Cargo 可以直接发送裸 token，也可以按 Nexus 文档发送 `Basic <base64-user-token>` 这样的完整 header 值。因此为了兼容 Cargo 和 Nexus 用户 token 示例，kkrepo 第一阶段应支持：

- `Authorization: Basic <base64-user-token>`
- `Authorization: Bearer <kkrepo-token>`
- `Authorization: <raw-token>`，用于 Cargo 默认 token provider 存储的裸 token
- `Authorization: CargoToken.<raw-token>`，用于 kkrepo 自生成 Cargo API key

现有全局 API key 认证只从配置 header、`X-Nexus-Plus-Api-Key` 和 `Authorization: Bearer ...` 取 token。Cargo 不能简单依赖全局 Bearer 解析；应在 Cargo controller/filter 中按 Cargo 规则读取完整 `Authorization` header，再调用共享 API key 解析能力。这样可以避免把普通 HTTP Basic 登录误判成 Cargo token，也不会影响 Maven/npm/PyPI 等已有认证行为。

权限映射：

| Cargo 操作 | kkrepo 权限 |
| --- | --- |
| 读取 `config.json` | repository read；未认证时仅在全局 anonymous 启用且匿名用户有权限时通过 |
| 读取 index 文件 | repository read；未认证时仅在全局 anonymous 启用且匿名用户有权限时通过 |
| 下载 `.crate` | repository read，path 为 crate name 或 crate/version |
| publish | repository add/update，path 为 crate name |
| yank/unyank | repository update，path 为 crate name/version |
| group read | group 仓库 read + 目标成员 read |
| proxy remote fetch | 用户 read 权限通过后由服务端使用仓库 remote credential |

私有仓库行为：

- 仓库是否允许读取、下载和写入由用户、角色和 `admin/security/privileges` 下的 repository privileges 控制，不由 Cargo `auth-required` 控制。
- Hosted 仓库不暴露 Cargo Authentication requirements 配置，生成的 `config.json` 固定包含 `auth-required: true`。Proxy/group 仓库暴露该配置，但它只影响 Cargo 客户端提示，不会授予或撤销匿名访问。
- `config.json`、sparse index 和 `.crate` download 都走普通 read 权限判定；匿名是否可用取决于全局 anonymous 配置及匿名用户的 repository privileges。Publish、yank 和 unyank 请求必须认证，并分别走 add/edit 权限判定。
- 未认证请求应返回 `401`，并可带 `www-authenticate: Cargo login_url="<url>"`。
- 认证失败应返回 `403` 或 Nexus 参考行为对应状态。
- 所有 API error body 使用 `{"errors":[{"detail":"..."}]}`，保证 Cargo CLI 输出可读。

## 多副本语义

Cargo 仓库实现不得依赖单 JVM 进程内状态作为唯一真相。

- Published crate version、index JSON、yank 状态、proxy revalidate 状态和仓库配置保存在 MySQL-backed component/asset/repository 行中。
- `.crate` 文件只保存在 OSS/S3；MySQL 保存 blob 引用、checksum、size 和状态。
- In-memory/shared cache 只能缓存 repository runtime snapshot、asset metadata snapshot、permission decision 和 negative result，必须有 TTL 或显式失效条件，并且可从 MySQL/OSS/S3 重建。
- 包含 upstream proxy password 或 bearer token 的 repository runtime snapshot 不写入共享 runtime cache。
- Proxy revalidate 和远端 `.crate` 下载在并发下可能重复请求远端，但正确性由 MySQL asset/component 唯一约束和 checksum 校验保护。
- publish 和 yank 更新 MySQL component 行；其它副本通过 DAO 读取和有界 TTL cache 失效观察已提交状态。
- blob cleanup 通过元数据引用检查保护失败写入，避免新上传未引用对象成为 live metadata truth。

## Browse、管理和迁移

Admin UI：

- 仓库创建页新增 `cargo-hosted`、`cargo-proxy`、`cargo-group`。
- Proxy 配置需要 remote sparse index URL，保存时提示必须以 `/` 结尾。
- Cargo proxy/group 显示 Authentication requirements 配置，用于控制 `config.json` 的 `auth-required` 客户端提示；Cargo hosted 不显示该配置，并固定返回 `auth-required: true`。页面文案必须说明访问控制仍由 anonymous 设置和 security privileges 决定。
- Group 配置显示成员顺序，提示冲突 version 第一成员优先。

Browse UI：

- crate 列表显示 name、latest version、description、yanked count、last published time。
- crate 详情显示 versions、checksum、yanked 状态、dependencies、features、license、repository/homepage/documentation 链接。
- yanked version 必须有明确标识，但仍允许下载。

迁移：

- Nexus Cargo 仓库迁移能力待定，第一阶段暂不实现，也不在 UI 或文档中承诺可迁移。
- 背景：Nexus Repository Pro 3.73.0 已有 Pro-only 原生 Cargo 初始支持，但 3.77.0 才随 Community Edition 正式放出 previously Pro-only formats。此时 Nexus 已进入 H2/PostgreSQL 数据库形态；现有面向旧 Nexus 的 OrientDB / Script REST API 迁移补偿路径不能直接复用。
- 后续如果启动 Cargo 迁移，需要先在 Nexus 3.77.0+ 参考实例上确认源数据可读入口：官方 REST API、数据库导出、blob metadata、sparse index、token/user-token 存储和权限映射。
- 后续迁移设计必须支持 dry-run、resume、checksum 校验和报告，并明确 H2 与 PostgreSQL 两种源端数据库的读取策略。
- 后续 Cargo token 迁移必须在 Nexus 参考实例中创建该版本 Nexus Cargo 支持的所有 token/credential 形态，迁移到 kkrepo 后，原 `.cargo/config.toml` 和 `credentials.toml` 中的 token 不重新生成、不手工替换，`cargo fetch`、`cargo publish`、`cargo yank` 和 `cargo yank --undo` 仍应按原权限成功或失败。
- 如果未来发现任何 Nexus 已支持的 Cargo token 不能自动迁移并继续使用，Cargo 迁移验收应失败；只能在报告中标记明确的 `MANUAL` 阻塞项，不能静默降级为“迁移后重新签发”。
- 对 Nexus 文档明确不兼容的旧 community plugin 数据，不承诺自动迁移；如果用户有需求，应单独做源数据分析和一次性转换方案。
- 迁移状态写入 MySQL，不使用本地文件作为唯一 checkpoint。

## 观测指标

建议新增指标：

- `kkrepo_cargo_index_requests_total{repository,type,result}`
- `kkrepo_cargo_index_cache_hits_total{repository,type}`
- `kkrepo_cargo_download_requests_total{repository,type,result}`
- `kkrepo_cargo_publish_requests_total{repository,result}`
- `kkrepo_cargo_yank_requests_total{repository,result}`
- `kkrepo_cargo_proxy_revalidate_total{repository,result,status}`
- `kkrepo_cargo_proxy_download_bytes_total{repository}`
- `kkrepo_cargo_group_merge_total{repository,result}`
- `kkrepo_cargo_active_downloads{repository}`
- `kkrepo_cargo_active_publishes{repository}`

日志字段应包含 `repository`、`crate`、`version`、`operation`、`status`、`checksum`、`cache`、`remote_status`、`principal` 和 `request_id`。

## 实施顺序

1. 新增兼容性测试骨架
   - `compat-test` 新增 Cargo Nexus reference 测试。
   - 固定 `config.json`、missing index、download、publish、yank/unyank 的期望行为。

2. 新增 core recipe 和协议模块
   - 新增 `RepositoryFormat.CARGO`。
   - 新增 `cargo-hosted`、`cargo-proxy`、`cargo-group` recipes。
   - 新建 `protocol-cargo`，封装 index path、publish body、index JSON、checksum、error model。

3. 新增持久化集成
   - 复用 `component`、`asset`、`asset_blob`、browse node、proxy state、shared negative cache 和 asset metadata cache。
   - 补测试覆盖唯一约束、yank、生成 validator、proxy cache 和 checksum mismatch。

4. Hosted 最小可用
   - 实现 `config.json`、publish、index、download、yank/unyank。
   - 用真实 `cargo publish` 和 `cargo fetch` 验证。

5. Proxy 最小可用
   - 实现 remote `config.json` 解析、index cache、download cache、checksum 校验和 negative cache。
   - 用 crates.io sparse index 验证常见依赖拉取。

6. Group 最小可用
   - 实现成员 index merge、download origin 解析和 cache invalidation。
   - 验证私有 hosted + crates.io proxy 组合。

7. UI 和运维补齐
   - Admin UI 创建/编辑 Cargo 仓库。
   - Browse UI crate 详情。
   - 指标、日志、告警和文档。

8. 迁移预研，暂不进入第一阶段实现
   - 在 Nexus 3.77.0+ 参考实例确认 Cargo 数据在 H2/PostgreSQL 下的可读入口。
   - 梳理 Cargo token/User Token 的存储和可验证材料。
   - 输出单独的 Cargo 迁移设计后，再决定是否进入实现。

## 验收标准

第一阶段完成时必须满足：

- Cargo 1.68+ 可以通过 `sparse+.../repository/cargo-hosted/` 发布、拉取、yank、unyank。
- Cargo 当前稳定版可以通过 `cargo-group` 拉取私有 hosted crate 和 crates.io proxy crate。
- `cargo build --locked` 在依赖已缓存时不依赖远端 crates.io。
- `.crate` 下载 checksum 与 index `cksum` 一致。
- index 请求支持 `ETag` 或 `Last-Modified`，客户端条件请求能得到 `304`。
- publish/yank 后多副本读取新 index 不依赖本地进程状态。
- Proxy 远端 404/410/451、远端 304、checksum mismatch、网络失败都有明确测试。
- 第一阶段不提供 Nexus Cargo 仓库迁移能力；迁移页面、兼容矩阵和 release note 不能把 Cargo 标记为可迁移。
- Nexus reference 兼容测试记录所有已知差异，并只在协议允许的位置规范化 host、timestamp 或 header 顺序。

## 参考资料

- Cargo Book: Registries: https://doc.rust-lang.org/cargo/reference/registries.html
- Cargo Book: Registry Index: https://doc.rust-lang.org/cargo/reference/registry-index.html
- Cargo Book: Registry Web API: https://doc.rust-lang.org/cargo/reference/registry-web-api.html
- Cargo Book: Registry Authentication: https://doc.rust-lang.org/cargo/reference/registry-authentication.html
- Cargo Book: Source Replacement: https://doc.rust-lang.org/cargo/reference/source-replacement.html
- Sonatype Nexus Repository Rust / Cargo Repositories: https://help.sonatype.com/en/rust-cargo.html
- Sonatype Nexus Repository 3.73.0 Release Notes: https://help.sonatype.com/en/sonatype-nexus-repository-3-73-0-release-notes.html
- Sonatype Nexus Repository 3.77.0 Release Notes: https://help.sonatype.com/en/sonatype-nexus-repository-3-77-0-release-notes.html
- Sonatype Nexus Repository System Requirements: https://help.sonatype.com/en/sonatype-nexus-repository-system-requirements.html
