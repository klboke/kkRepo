# Composer / PHP 仓库开发设计说明

本文记录 kkrepo Composer / PHP 仓库格式的开发设计。目标不是重新发明 Composer 仓库协议，而是在 Composer 2 官方 repository metadata 协议、Packagist 公开行为和 Nexus Repository Composer proxy 行为之间取兼容交集，并按 kkrepo 的 MySQL + OSS/S3 + 多副本约束落地可代理、可托管、可组合、可迁移、可观测的 PHP 制品仓库。

## 当前支持状态

截至 2026-07-12，Composer / PHP 第一轮仓库能力已经落地，不再只是路线图设计：代码包含 `RepositoryFormat.COMPOSER`、`composer-hosted`、`composer-proxy`、`composer-group` recipe、独立 `protocol-composer` 模块，以及服务端、Components API、Admin UI、Browse/Search 和兼容性测试入口。

已实现能力分为两类：

- Nexus 兼容主线：Composer 2 proxy，代理 Packagist 或其它 Composer v2 repository，并缓存 metadata 与 dist archive。
- kkrepo 产品增强：Composer hosted、group、UI/API archive 上传和跨成员聚合。Composer 官方没有标准 publish API，Nexus 原生 Composer 也只提供 proxy，因此这些能力不能伪装成 Nexus 或 Composer 原生发布协议。

当前已实现：

- `composer-proxy`，对齐 Nexus 3.75.0+ Composer v2 proxy 和真实 Composer 2 客户端行为。
- `composer-hosted`，通过 kkrepo UI 和 Components API 写入 ZIP/TAR 包，不新增虚构的 `composer publish` endpoint。
- `composer-group`，按成员顺序提供统一仓库入口，并保持 Composer 2 canonical repository 的包级优先级语义。
- `packages.json`、`p2/%package%.json`、`p2/%package%~dev.json`、provider/list metadata 和 dist archive 下载。
- HTTP Basic、Bearer/GenericToken 认证，repository read/add/edit/delete 权限和 CI 上传。
- 面向 Nexus Composer proxy 参考实例和 kkrepo 的 live 黑盒对比，覆盖 packages、p2、conditional GET、404、dist 路由和响应类型；启用 Composer 兼容测试时，参考 Nexus 缺少 Composer proxy 能力会直接失败，不允许静默 skip。
- `scripts/ci/run-live-compat.sh client-e2e` 中的真实 Composer 2 流程，覆盖 Components API hosted 上传、hosted 包到 Packagist proxy 包的传递依赖解析、group 安装、`composer show`、运行时 autoload、错误 Basic 凭据的 `401`，以及清空 Composer 本地缓存并切断 Packagist upstream 后的 lock replay。
- `.github/workflows/migration-e2e.yml` 在 Nexus 3.92 datastore 组合中预热 Composer proxy cache，通过 `backupProxyRepositories` 显式选择迁移，并在切断目标上游后验证 metadata、dist 路径和 checksum。

仍属于增强项的是 Composer dependency/security 专用详情、security advisory/filter、最低 Composer 2 版本矩阵、`--prefer-source`/`composer audit` 边界，以及 Bearer 客户端 E2E。Nexus proxy cache migration adapter 已按 source profile 证明和管理员显式选择落地。Nexus live 对比需要实际可用的 Nexus Pro Composer 授权；启用测试后缺少该能力会直接失败，不能伪装成已完成对比。

## 调研基线

实现前必须对照以下协议和参考行为：

- Composer 官方 Repositories 文档。Composer repository 的入口是 `packages.json`；Composer 2 优先使用 `metadata-url`，把 `%package%` 替换为 `vendor/package` 后读取 per-package metadata。
- Composer 官方 Schema。发布包名必须是小写 `vendor/package`，并满足 Composer 的名称格式；普通可安装 package 的每个版本至少需要 `name`、`version`，以及 `dist` 或 `source` 中至少一个来源，`metapackage` 是不需要文件来源的特例。
- Composer 官方认证文档。私有仓库可使用 HTTP Basic、HTTP Bearer 或自定义 header；凭据可来自项目/全局 `auth.json` 或 `COMPOSER_AUTH`。
- Composer 官方 repository priorities。Composer 2 默认把上方仓库视为 canonical：一旦较高优先级仓库包含某个 package，便不会再从较低优先级仓库选择该 package 的版本。这是依赖混淆防护边界。
- Packagist v2 公开入口。当前 `packages.json` 使用 `metadata-url` 指向 `/p2/%package%.json`，并暴露可选的 `providers-api`、`list`、`security-advisories`、`metadata-changes-url` 等能力。kkrepo 第一阶段只把依赖解析必需的 v2 metadata 和 dist 下载作为正确性基础。
- Nexus Repository Composer 文档和 3.75.0 release notes。Nexus 3.75.0 起在 Pro 版提供原生 Composer proxy，仅支持 Composer v2，不支持代理只提供 Composer v1 metadata 的仓库，也不支持从旧 community plugin 直接迁移。
- 当前 kkrepo 通用 repository、proxy、component、asset、blob、permission、token、browse 和 migration 原语。Composer 协议解析必须独立在 `protocol-composer`，不能堆入 controller，也不能把 Composer 伪装成 raw 仓库。

关键结论：

- 兼容基线只承诺 Composer 2。`provider-includes` / `providers-url` 属于 Composer 1 或 v2 缺少 `metadata-url` 时的旧路径，不进入第一阶段正确性主线；proxy 遇到仅提供 v1 metadata 的上游应在保存配置或首次探测时明确失败。
- `packages.json` 是 capability document。Hosted/group 由 kkrepo 生成稳定的本地 `metadata-url`、`providers-api` 和可选 `list`；proxy 读取上游入口，但必须把所有 metadata 和 dist URL 重写到当前 kkrepo 仓库入口，避免客户端绕过认证、审计和缓存。
- `p2/<vendor>/<package>.json` 只允许包含请求 package 的版本；允许 dev stability 时，Composer 还会请求 `p2/<vendor>/<package>~dev.json`。缺失 package 必须快速返回真实 `404`，不能重定向到 HTML 错误页或其它仓库。
- Composer 使用 `If-Modified-Since` 重校验 per-package metadata，服务端必须返回准确、跨副本稳定的 `Last-Modified`；同时可以支持 `ETag` / `If-None-Match`，但不能只靠进程内生成时间。
- Dist archive 是 Composer 默认安装来源。`dist.shasum` 非空时按官方字段执行 SHA-1 校验；为空时仍计算并保存 MD5、SHA-1、SHA-256、SHA-512，但必须把校验来源标记为 `computed-only`，不能宣称已得到上游完整性证明。
- Composer 没有标准 publish API。Hosted 写入只能通过 kkrepo Components API 或管理端上传；文档和 UI 不应展示不存在的 `composer publish` 命令。
- Composer 2 的 canonical 优先级是 package 级，不是 version 级。Group 对同名 package 必须选择第一个包含该 package 的成员，并只返回该成员的全部版本，不能把较低优先级成员的更高版本混入结果。
- 进程内 cache 只能作为有 TTL 的可重建热缓存。Metadata、remote validator、negative cache、dist 路由、group 来源、迁移 checkpoint 和 blob 引用都必须有 MySQL/OSS-backed 真相。

## 功能范围

### 第一阶段必须实现

1. Composer proxy 仓库
   - 新增 `RepositoryFormat.COMPOSER` 和 `composer-proxy` recipe。
   - 新建 `protocol-composer`，封装 package name、version metadata、path parser、Composer v2 JSON、URL 重写和错误响应。
   - 默认 remote URL 为 `https://repo.packagist.org/`，保存时规范化尾部 `/`，并探测上游 `packages.json` 是否包含可用 `metadata-url`。
   - 代理 `packages.json`、stable/dev `p2` metadata、providers/list 等必要读取路径，保存上游 `ETag`、`Last-Modified`、body hash、cache_until 和校验时间。
   - 把 metadata 中的 `dist.url` 改写为 kkrepo 下载路由；首次下载时回源、校验、写 OSS/S3 和 MySQL，后续直接从 blob store 服务。
   - 支持 remote HTTP Basic、Bearer 和自定义 header secret；secret 只存在加密配置中，不写 asset attributes、日志或下游响应。
   - 对上游 `404`、`410`、`451` 使用短 TTL negative cache；远端故障时按仓库策略返回 stale metadata/dist 或明确 upstream error。
   - 新增面向 Nexus 3.75.0+ Pro 参考实例的 proxy 黑盒兼容性测试。

2. Composer hosted 仓库
   - 新增 `composer-hosted` recipe，作为 kkrepo 产品增强，不标记为 Nexus 原生能力。
   - 支持通过 `/service/rest/v1/components?repository={repo}` 或对应管理端上传单个 ZIP、TAR、TAR.GZ、TAR.BZ2 archive。
   - archive 必须包含一个有效 package 的 `composer.json`；允许位于 archive 根或唯一顶层目录下，拒绝多 package、路径穿越、符号链接逃逸、重复 entry 和解压炸弹。
   - 包名优先来自 `composer.json.name`，必须满足 Composer 2 小写 `vendor/package` 规则。
   - 版本可来自 archive 中的 `composer.json.version` 或上传字段 `composer.version`；两者同时存在时必须一致，均缺失时拒绝上传，不从文件名静默猜测。
   - 将 archive 原始字节保存到 OSS/S3，生成本地 `dist.url`，并保存 size、media type、MD5、SHA-1、SHA-256、SHA-512。
   - 同一 `(repository_id, package_name_lc, normalized_version)` 不可覆盖；更新 metadata 必须通过显式管理员 edit/replace 流程并保留审计。
   - 生成 `packages.json`、stable `p2`、dev `p2`、provider 和 list 响应，支持准确 `Last-Modified`、`ETag` 和 `304`。
   - 管理端删除 version 后同步更新 package metadata；删除最后一个 version 后 package endpoint 返回 `404`。

3. Composer group 仓库
   - 新增 `composer-group` recipe，成员可包含 hosted、proxy 和 group Composer 仓库，并拒绝循环依赖。
   - `packages.json` 的 metadata/provider/list URL 全部指向 group 自身。
   - 对请求 package，按成员顺序寻找第一个包含该 package 的成员；一旦命中，只返回该成员的版本集合。
   - Stable 和 `~dev` 查询必须使用同一 package 来源决策，不能分别从不同成员选取。
   - Provider 查询可聚合成员返回的候选 package name，但每个具体 package 仍要应用 first-match；list endpoint 对 package name 去重，不参与版本合并。
   - Group metadata 中的 dist URL 指向 group 下载路由；路由 token 在 MySQL 中绑定来源成员、package、version 和 blob/remote identity。
   - 成员顺序、成员 online 状态或 hosted metadata 变化后，通过共享版本水位/失效 marker 让所有副本观察到新来源；本地 TTL cache 丢失不影响正确性。
   - Group 只读，Components API 上传和管理端删除必须定位到具体 hosted 成员，不能直接写 group。

4. 管理端和迁移
   - Admin UI 支持创建、编辑 `composer-hosted`、`composer-proxy`、`composer-group`。
   - Browse UI 展示 vendor、package、version、type、license、description、dist/source、checksum、来源成员和依赖摘要。
   - UI/API 上传复用同一个 `ComposerHostedService`，不能绕过 archive、metadata、checksum、唯一约束、权限和审计校验。
   - Nexus 迁移只处理管理员显式指定、且已被 source profile 证明的原生 Composer proxy 仓库；未指定的 Composer 仓库不得被自动发现或默认迁移。
   - Composer 迁移保持 proxy 语义，只迁移所选 Nexus proxy 的已缓存 metadata、dist 和必要映射，不把缓存内容转换成 hosted package。
   - 旧 community plugin 没有官方直接迁移路径，标记为不支持并 fail closed，不得猜测 content model。

5. 认证和权限
   - Composer metadata 和 dist 下载走 repository `READ`。
   - Hosted Components API/UI 上传走 repository `ADD`；显式 metadata 修订走 `EDIT`；删除 version/package 走 `DELETE`。
   - 支持 Composer `auth.json` 的 HTTP Basic；Bearer 可复用 `GenericToken`，CI 通过 `COMPOSER_AUTH` 注入凭据。
   - 未认证或 token 无效返回 `401` 并带正确 `WWW-Authenticate`；认证成功但权限不足返回 `403`。
   - Proxy remote credential 和用户访问 kkrepo 的 credential 是两套边界，不能把上游认证 challenge 或 secret 原样透传给客户端。

6. 兼容性测试和真实客户端验证
   - 新增 `ComposerRepositoryBlackBoxCompatibilityTest`，proxy 用例同时运行在 Nexus 参考实例与 kkrepo；hosted/group 用例运行在 kkrepo，并以 Composer 官方协议作为基线。
   - 覆盖 `packages.json`、stable/dev p2、missing package 404、conditional GET、private repository auth、dist URL 重写、archive 下载、checksum、proxy cache 和 stale/negative cache。
   - 对比 HTTP 状态、`Content-Type`、`Cache-Control`、`ETag`、`Last-Modified`、`WWW-Authenticate`、JSON 字段和 redirect 行为。
   - 使用 Composer 2 当前稳定版和最低支持版本验证 `composer install`、`composer update`、`composer show`、`composer require`、`--prefer-dist`、`--prefer-source`、lock file 重放和 `composer audit` 的非回归边界。
   - Group 专门验证 dependency confusion：高优先级 hosted 已存在 `company/private` 时，不得从低优先级 Packagist proxy 混入该包的其它版本。

### 后续扩展和非目标

后续可选扩展：

- Packagist search API 兼容、下载统计和 Browse 搜索增强；这些都不能成为 Composer dependency resolution 的正确性依赖。
- Hosted metadata-only `metapackage` 管理入口；第一阶段 hosted 仅接受包含 `composer.json` 的 archive，proxy 仍需透明支持上游 metapackage metadata。
- Composer security advisories、metadata changes 和 dependency policy/filter endpoint。未实现前不得在 `packages.json` 中广告对应 capability。
- Webhook/VCS 扫描后生成 package version 和 dist archive。该能力属于外部构建流水线，不是 Composer repository publish 协议。

明确不实现：

- Composer 1 metadata 兼容主线，包括只提供 `provider-includes` / `providers-url` 且没有 v2 `metadata-url` 的上游。
- 虚构 `composer publish` CLI 或 REST 协议。
- 把 Composer 仓库注册为 npm、PyPI 或 raw 仓库。
- 把 `source.url` 指向的 Git/VCS 内容复制进 MySQL；大 archive 只存 OSS/S3。
- 依赖单 JVM 内存维护 metadata、negative cache、group 来源、上传状态或迁移 checkpoint。
- 默认执行 package 自带的 Composer plugin、script 或 autoload 代码。服务端 archive 检查必须是纯数据解析。
- 自动代理依赖 package metadata 中声明的 `repositories`；Composer 官方也只读取 root package 的 repository 配置。

## URL 与路由设计

Composer 使用普通 `/repository/{repo}/...` 仓库入口。

客户端项目配置：

```json
{
  "repositories": [
    {
      "type": "composer",
      "url": "https://repo.example.com/repository/composer-group"
    }
  ]
}
```

如果 group 已包含 Packagist proxy，建议显式关闭 Composer 默认追加的 Packagist：

```json
{
  "repositories": [
    {"type": "composer", "url": "https://repo.example.com/repository/composer-group"},
    {"packagist.org": false}
  ]
}
```

HTTP Basic 认证示例：

```bash
composer config --global http-basic.repo.example.com <username> <password>
```

CI 可使用环境变量，避免把凭据写入项目文件：

```bash
export COMPOSER_AUTH='{"http-basic":{"repo.example.com":{"username":"ci","password":"<token>"}}}'
composer install --no-interaction --prefer-dist
```

路由表：

| 请求 | 行为 |
| --- | --- |
| `GET /repository/{repo}/packages.json` | Composer repository capability 入口 |
| `GET /repository/{repo}/p2/{vendor}/{package}.json` | stable/non-dev package versions |
| `GET /repository/{repo}/p2/{vendor}/{package}~dev.json` | dev package versions |
| `GET /repository/{repo}/providers/{package}.json` | 可选 virtual package providers |
| `GET /repository/{repo}/packages/list.json?filter=...` | 可选 package name list |
| `GET /repository/{repo}/{vendor}/{package}/{version}/{vendor}-{package}-{version}.{type}` | hosted/proxy/group 统一的 Nexus 风格 dist 下载 |
| `HEAD /repository/{repo}/...` | 与对应 GET 一致的状态和 header，不返回 body |

`packages.json` 最小示例：

```json
{
  "packages": [],
  "metadata-url": "https://repo.example.com/repository/composer-group/p2/%package%.json",
  "providers-api": "https://repo.example.com/repository/composer-group/providers/%package%.json",
  "list": "https://repo.example.com/repository/composer-group/packages/list.json"
}
```

Per-package metadata 示例：

```json
{
  "packages": {
    "company/example": [
      {
        "name": "company/example",
        "version": "1.2.0",
        "version_normalized": "1.2.0.0",
        "type": "library",
        "dist": {
          "type": "zip",
          "url": "https://repo.example.com/repository/composer-group/company/example/1.2.0/company-example-1.2.0.zip",
          "reference": "release-1.2.0",
          "shasum": "..."
        },
        "require": {
          "php": ">=8.2"
        }
      }
    ]
  }
}
```

路由规则：

- Package path 必须恰好是两个小写 segment；拒绝 `..`、空 segment、重复解码、编码斜线造成的歧义和不匹配的 metadata name。
- hosted、proxy、group 的公开 dist path 统一采用 Nexus 风格的 `vendor/package/version/vendor-package-version.type`；proxy 的远端 URL 绑定只保存在内部 `_composer/routes` 资产中，不能暴露内部 blob key 或远端 URL。
- 对外 metadata URL 使用反向代理感知的 external base URL；不能把 pod hostname、内部端口或 remote credential 写入响应。
- Stable/dev 拆分必须使用 Composer 版本语义；`~dev` 不是字符串 contains 判断。版本归一化实现需要用官方 Composer/semver fixture 做差分测试。
- Metadata JSON 的字段顺序不作为语义，但响应 body hash、`Last-Modified` 和 `ETag` 必须由确定性排序和持久化版本水位生成，避免不同副本不断互相打破缓存。

## 数据模型落地

第一阶段优先复用 kkrepo 现有 MySQL 通用模型，只在共享模型无法表达路由和缓存状态时新增 Composer 专用表。

- package version 使用 `component` 行：`format=COMPOSER`、`namespace=<vendor>`、`name=<package>`、`version=<raw version>`、`kind=composer-package`。
- `coordinate_hash` 基于 repository、规范化 package name 和 normalized version，唯一约束保护同一版本不可覆盖。
- component attributes 保存 raw/normalized version、type、description、license、time、dist/source、require/require-dev/conflict/replace/provide/suggest、autoload 摘要、abandoned 和来源信息。
- Archive 使用 `asset` + `asset_blob`。OSS/S3 保存原始字节；MySQL 保存 path、size、content type、checksums、blob reference 和 attributes。
- Proxy metadata 作为 metadata asset 持久化，保存 remote URL hash、upstream validators、body hash、verified_at、cache_until、stale_until 和 rewrite version。
- `composer_dist_route`（如需要）保存 route token、repository、component/asset、source member、remote URL hash 和状态；token 唯一，删除/隔离后路由状态同步失效。
- `composer_repository_watermark`（如通用版本水位不足）保存 repository metadata version、last_modified 和变更原因，用于跨副本 conditional GET 和 group cache 失效。
- Negative cache 使用共享 TTL cache，key 至少包含 repository id、remote identity 和规范化 path；进程内只保留更短 TTL 热副本。

## Hosted 上传与元数据流程

Hosted 不提供 Composer 原生 publish endpoint。Controller 只接收 Components API/UI 请求，再委托 `ComposerHostedService`。

流程：

1. 校验 repository online、recipe、write policy 和 `ADD` 权限。
2. 把上传流写入临时对象/临时文件并同时计算 size 和 checksums；不得把大 archive 全量放入 JVM heap。
3. 安全读取 archive 中的 `composer.json`，限制 entry 数量、单 entry size、总解压大小、压缩比、嵌套深度和解析时间。
4. 校验 package name、version、dist type、依赖字段、autoload 数据形状；忽略 root-only 的 repositories/config/scripts 执行语义，绝不执行 package code。
5. 生成规范化 package/version identity 和本地 dist metadata。
6. 先把 archive 写入 OSS/S3 临时/内容寻址 key，再在 MySQL 事务中写 component、asset、blob reference、browse node、内部 remote route 和 repository watermark。
7. 唯一约束冲突时返回确定的 package/version already exists 错误；仅在确认无 live metadata 引用时清理新上传 blob。
8. 事务提交后发布 cache invalidation marker；其它副本从 MySQL 水位重建 metadata。

删除 version 时反向执行 metadata 更新：先以事务删除/隔离 component、asset 和 route reference，递增水位，再异步清理无引用 blob。不得先删除 blob 再更新 metadata。

## Proxy 缓存流程

Proxy 以 MySQL metadata 和 OSS/S3 blob 为 cache 真相。

Metadata 请求：

1. 解析并规范化本地 path，映射到上游 `packages.json` 广告的 v2 URL。
2. 命中新鲜 metadata asset 时直接返回重写后的本地响应。
3. 命中共享 negative cache 时快速返回 `404`。
4. 过期时携带上游 `If-None-Match` / `If-Modified-Since` 重校验；并发回源通过分布式 singleflight/lease 合并，但 lease 失败不能破坏正确性。
5. 上游 `304` 时刷新校验时间；`200` 时校验 JSON 和 package identity、重写 dist URL、持久化原始/规范化 metadata 和 validator。
6. 上游临时失败时按 stale policy 返回已验证 cache；没有可用 cache 时返回明确的 upstream error。
7. 上游 `404`、`410`、`451` 写短 TTL negative cache；认证失败和限流不能当作 package 不存在缓存。

Dist 请求：

1. 通过 route token 从 MySQL 解析 package/version 和原始 remote dist URL。
2. 已有有效 blob 时直接服务，支持 HEAD、Range、ETag 和 conditional GET。
3. 未缓存时在分布式 lease 下回源；其它副本等待或在 lease 超时后重新检查 MySQL，不能各自把部分文件当作完成对象。
4. 流式写临时对象并计算 checksums；`dist.shasum` 非空时严格校验 SHA-1。
5. 校验成功后原子绑定 asset/blob reference 和 route；失败对象进入清理队列，不发布 metadata 可见状态。
6. Redirect 只允许受控跟随，并应用 SSRF 防护；每次跳转都重新校验 scheme、host、IP 和远端凭据作用域。

## Group 解析流程

Group 的核心是 package-level first match：

1. 对 package 请求读取 group member snapshot，按配置顺序遍历。
2. 成员明确返回 package metadata 时立即选为来源，后续成员不再参与该 package 的版本集合。
3. 成员明确 `404` 时继续；成员超时/5xx 时依据 group policy 使用该成员 stale cache、跳过或失败，但行为必须可配置、可观测，不能把故障默认等价成“不存在”。
4. Stable 和 dev metadata 共享来源 binding；若只请求 `~dev`，也要根据成员是否拥有该 package，而不是只看该成员是否有 dev version。
5. 返回前把 dist URL 重写到 group，自身 route token 绑定来源成员。
6. Member revision 或 group membership revision 变化时失效来源 binding；正确性来自 MySQL revision，不来自本地 map。

这种语义有意不合并同名 package 的跨成员版本。它与 Composer 2 canonical repository 的安全边界一致，可避免低优先级公网源用更高版本覆盖内部包。

## 权限与认证

Composer 客户端访问私有仓库时优先支持 HTTP Basic：

- 用户名/密码可走现有用户认证。
- CI token 可作为 Basic password 或通过既有 GenericToken Bearer 入口使用，具体形态必须在客户端配方中固定。
- `COMPOSER_AUTH` 适合 CI；不得建议把 secret 提交到项目 `composer.json`。

权限映射：

| Composer/管理操作 | kkrepo 权限 |
| --- | --- |
| 读取 packages/p2/provider/list metadata | `READ` |
| 下载 dist archive | `READ` |
| Hosted UI/API 上传新 version | `ADD` |
| 修改可变管理 metadata | `EDIT` |
| 删除/隔离 package version | `DELETE` 或管理员权限 |
| 修改 proxy remote credential/group members | repository 管理权限 |

认证和错误边界：

- 未提供凭据或凭据无效：`401`，返回合适的 `WWW-Authenticate`。
- 凭据有效但无权限：`403`。
- Package 不存在：快速 `404`，不 redirect。
- 非法 path/metadata：`400` 或 `404`，具体状态由 Nexus/Composer 黑盒测试固定。
- 上游认证失败：不把上游 challenge 和 secret 透传；转换为受控 proxy error 并记录脱敏审计。

## 多副本语义

Composer 实现不得依赖单个 JVM 进程内状态作为唯一真相。

- Hosted component/asset、proxy metadata、validator、内部 remote route、group 来源 binding、watermark、迁移任务和 blob reference 以 MySQL 为真相。
- Archive 字节和缓存 response body 存 OSS/S3；MySQL 不保存大 blob。
- 同一 package/version 并发上传由 MySQL 唯一约束裁决。
- 同一 remote metadata/dist 的并发回源使用 MySQL/共享锁实现 lease；锁只减少重复工作，最终发布仍由唯一约束和状态机保证。
- 进程内 metadata/route cache 必须有 TTL，并由 repository/member revision 校验；丢失后可从 MySQL/OSS 重建。
- Negative cache 使用共享 TTL 层，并允许更短的节点热缓存；权限结果不能与 package negative cache 共用 key。
- Blob 上传采用临时对象 + MySQL 引用提交 + marker 清理，避免 pod 崩溃产生已发布半文件。
- 后台 cleanup/migration/revalidate 任务使用可抢占 lease、heartbeat、checkpoint 和幂等操作，任一副本死亡后可续跑。

## Browse、管理和迁移

管理端需要覆盖：

- `composer-hosted`：blob store、write policy、archive size/format 限制和 duplicate policy。
- `composer-proxy`：remote URL、remote credential、metadata/content TTL、negative TTL、auto-block、stale policy、redirect/SSRF policy。
- `composer-group`：成员顺序、循环检测和 package 来源诊断。
- Browse/Search：按 vendor、package、version、type、license、description、dependency、来源和 checksum 检索。
- Package 详情：展示稳定版/dev 版、dist/source、依赖、abandoned、provider/replace 信息和 Composer 配置片段。
- Cache 诊断：展示 upstream validator、last verified、stale 状态、negative cache、dist cached 和 source member。

迁移：

- Composer 迁移仅面向 Nexus 3.75.0+ 原生 Composer proxy 仓库。
- 管理员必须在迁移任务中显式指定 Nexus 源 Composer proxy；迁移扫描不得自动选择 Composer 仓库，也不得因为格式匹配就默认批量迁移。
- 只有 source profile 已证明 repository recipe、datastore schema、asset path、component attributes 和 blob 引用的源仓库才允许执行；不满足条件时 fail closed。
- 迁移目标保持 `composer-proxy` 类型和远端配置语义，搬迁所选仓库的已缓存 metadata、dist、checksum、validator 和必要路由映射，不把 proxy cache 转换成 hosted component。
- Nexus community Composer plugin 属于不同 source family，没有经过验证的 adapter 时明确标记为不支持。
- 迁移复用通用 Nexus migration job/checkpoint，支持 dry-run、resume、checksum 校验和逐项报告；任务真相必须在 MySQL 中，不能依赖本地目录游标或单个 JVM。

## 观测指标

建议指标：

- `kkrepo_composer_metadata_requests_total{repository,type,path_kind,result,cache}`
- `kkrepo_composer_dist_requests_total{repository,type,result,cache}`
- `kkrepo_composer_proxy_remote_requests_total{repository,kind,status,result}`
- `kkrepo_composer_proxy_revalidate_total{repository,kind,result}`
- `kkrepo_composer_group_resolution_total{repository,result,member}`
- `kkrepo_composer_uploads_total{repository,result,archive_type}`
- `kkrepo_composer_checksum_failures_total{repository,source,algorithm}`
- `kkrepo_composer_negative_cache_total{repository,result}`
- `kkrepo_composer_migration_items_total{source_repository,result}`

日志携带 request id、repository、vendor/package、version、operation、principal、source IP、user agent、cache result、source member、remote URL hash 和 checksum result。不得记录 password、token、remote credential、带签名 query 的 URL 或内部 blob key。

## 实施顺序与状态

第一轮能力已按以下顺序落地。各阶段仍可继续扩展，当前完成边界以上方“当前支持面”、兼容矩阵和自动化测试为准。

1. 协议调研和兼容基线
   - 固定 Composer 2 客户端版本矩阵。
   - 搭建 Nexus 3.75.0+ Pro Composer proxy 参考实例。
   - 记录 Packagist/Nexus 的 packages、p2、404、conditional GET、auth、dist redirect/cache 行为。
   - 将差异写入 compat baseline，只有 host、timestamp、ETag 等协议允许的非确定字段可规范化。

2. 格式和协议模块
   - 新增 `RepositoryFormat.COMPOSER`、recipe 和 `protocol-composer`。
   - 实现 package/version parser、v2 metadata model、deterministic serializer、path safety 和 URL rewrite。

3. Proxy
   - 先实现 `packages.json` 和 stable/dev p2。
   - 再实现 Nexus 风格 dist path、内部 remote route、stream cache、checksum、validator、negative/stale cache 和 remote auth。
   - 用 Nexus 参考实例和真实 Composer 2 client 验证。

4. Hosted
   - 实现安全 archive inspector、Components API/UI 上传、component/asset/blob 写入和 metadata 生成。
   - 实现删除/隔离、watermark 和跨副本 cache invalidation。

5. Group
   - 实现 package-level first match、source binding、group dist 分派和成员 revision。
   - 增加 dependency confusion 和成员故障测试。

6. Browse/Admin/迁移
   - 完成创建编辑、Browse/Search 和 diagnostics。
   - 在 source profile 可证明时增加 Nexus Composer proxy cache migration adapter，并要求管理员显式选择源仓库。

7. 真实客户端 E2E
   - 把 Composer 加入 `scripts/ci/run-live-compat.sh client-e2e`。
   - 覆盖匿名/私有仓库、Basic/Bearer、hosted/group、proxy cold/warm cache、lock replay 和故障降级。

## 验收标准

- Composer 2 可以通过 `composer-proxy` 安装 Packagist 包，metadata 和 dist 都经 kkrepo，第二次安装命中缓存。
- Nexus 参考兼容测试覆盖 proxy 的关键路径，所有有意差异均有文档和测试固定。
- `composer-hosted` 可通过 UI/API 上传 archive，随后 `composer require company/example` 和 lock replay 成功。
- Hosted 上传不执行 package code；恶意 archive、路径穿越、解压炸弹、非法 name/version 和重复 version 被拒绝。
- `composer-group` 对同名 package 使用第一个包含该包的成员，不混合低优先级成员版本。
- Metadata 返回稳定、准确的 `Last-Modified` / `ETag`，跨副本 conditional GET 能得到 `304`。
- Proxy 能校验非空 `dist.shasum`；校验失败不发布 blob/asset，并有指标和审计。
- 上游不可用时按配置返回已验证 stale cache 或明确错误，不把 401/403/429 缓存成 package 404。
- 反向代理部署下所有 metadata/dist URL 使用正确 external base URL，不泄漏内部 host、blob key 或 remote credential。
- 任一副本在 metadata 回源、dist 下载、上传或 proxy cache 迁移中退出后，任务可由其它副本安全恢复，不出现半发布 package。
- 未显式选择 Nexus Composer proxy 时不执行 Composer 迁移；已选择的迁移任务支持 dry-run、resume、checksum 校验和报告。

## 参考资料

- [Composer: Repositories](https://getcomposer.org/doc/05-repositories.md)
- [Composer: The composer.json schema](https://getcomposer.org/doc/04-schema.md)
- [Composer: Authentication for private packages](https://getcomposer.org/doc/articles/authentication-for-private-packages.md)
- [Composer: Repository priorities](https://getcomposer.org/doc/articles/repository-priorities.md)
- [Packagist Composer repository](https://repo.packagist.org/packages.json)
- [Sonatype Nexus Repository: Composer Repositories](https://help.sonatype.com/en/composer-repositories.html)
- [Sonatype Nexus Repository 3.75.0 release notes](https://help.sonatype.com/en/sonatype-nexus-repository-3-75-0-release-notes.html)
