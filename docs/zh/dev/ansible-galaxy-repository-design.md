# Ansible Galaxy 仓库开发设计说明

本文记录 kkrepo Ansible Galaxy 仓库格式的开发设计。目标不是把 collection tarball 当作 Raw 文件保存，而是在 Ansible 官方 Galaxy v3 collection API、`ansible-galaxy` 客户端行为和 Sonatype Nexus Repository Ansible Galaxy 行为之间取兼容交集，并按 kkrepo 的关系数据库 + OSS/S3 + 多副本约束落地可托管、可代理、可组合、可迁移和可观测的 Ansible collection 仓库。

## 当前支持状态

截至 2026-07-21，本设计已在 `feat/ansible-repository-support` 落地。代码包含 `RepositoryFormat.ANSIBLEGALAXY`、`ansiblegalaxy-hosted`、`ansiblegalaxy-proxy`、`ansiblegalaxy-group`、独立 `protocol-ansible` 模块、Galaxy v3 服务、V35 双数据库 schema、Admin/Browse/Search/UI 上传、Nexus 迁移适配、黑盒兼容测试和真实客户端 E2E 入口。

已实现范围：

- Galaxy v3 collection API discovery、collection/version metadata、artifact download、multipart publish 和 import task 查询。
- 与 Nexus 一致的 `/repository/{repo}/...` 仓库入口、`ansiblegalaxy` format/recipe 命名和直接 artifact 上传路径。
- Ansible Galaxy hosted、proxy、group 仓库，以及真实 `ansible-galaxy collection publish/install/download` E2E；server-provided signature 的 `verify` 分支在 Nexus 3.94 未提供可对照行为时明确保留为产品增强，不伪造签名。
- `ansible.cfg`、`requirements.yml`、Basic、Bearer/GenericToken、Nexus Base64 `username:password` token 和匿名读取；兼容 Ansible 2.9 的 `Authorization: Token` 与当前 ansible-core 的 `Authorization: Bearer`。
- collection tarball、`MANIFEST.json`、`FILES.json`、artifact SHA-256、依赖、`requires_ansible` 和可选 GPG detached signature。
- UI/API 上传、Browse/Search、Nexus repository definition 与 hosted data 迁移，以及显式选择的 proxy cache 迁移。
- MySQL/PostgreSQL 双数据库、多副本发布/回源去重、持久化 import task、group source binding 和可重建 TTL cache。

实现验证以 Nexus 3.94.0 为当前 reference。黑盒已固定 discovery、短/长 v3 路径、multipart publish/task、raw PUT、不可变版本、hosted/group/proxy 读取、分页、artifact GET/HEAD/conditional response、group source priority 和 public Galaxy CDN redirect。Nexus 对 group/proxy publish route 返回 `404`，kkrepo 跟随该行为；它不是通用 REST 语义推导出的 `405`。

第一阶段只支持 Ansible collections。旧 Galaxy v1 role API、GitHub role import、notification secret 和 `ansible-galaxy role install` 不属于 Nexus 3.93+ Ansible repository 的兼容主线，不能与 collection v3 API 混为同一格式能力。

## 调研基线

实现前必须对照以下协议、客户端实现和参考行为：

- Ansible 官方 [Distributing collections](https://docs.ansible.com/projects/ansible/latest/dev_guide/developing_collections_distributing.html)。它定义 collection build/publish、distribution server、token、不可重发版本和 tarball 分发流程。
- Ansible 官方 [Collection Galaxy metadata structure](https://docs.ansible.com/projects/ansible/latest/dev_guide/collections_galaxy_meta.html) 与 [Collection structure](https://docs.ansible.com/projects/ansible-core/devel/dev_guide/developing_collections_structure.html)。它们定义 `galaxy.yml`、namespace/name/version、依赖和 collection 内容结构。
- Ansible 官方 `ansible-core` [Galaxy v3 client implementation](https://github.com/ansible/ansible/blob/devel/lib/ansible/galaxy/api.py)。客户端先发现 `available_versions.v3`，再访问 collection、version、artifact 和 import task endpoint；实现必须以真实客户端读取的字段为准。
- Ansible 官方 [Galaxy NG v3 API](https://github.com/ansible/galaxy_ng/blob/main/docs/community/api_v3.md)。它给出 collection index、版本分页、版本详情、artifact upload/download、SHA-256、signature 和错误响应的参考 schema。
- Ansible 官方 [Installing collections](https://docs.ansible.com/projects/ansible-core/devel/collections_guide/collections_installing.html) 与 [Ansible configuration settings](https://docs.ansible.com/projects/ansible/latest/reference_appendices/config.html)。`GALAXY_SERVER_LIST` 的顺序参与 collection 解析，requirements 支持 exact/range dependency 和指定 source。
- Galaxy NG [Collection signing](https://docs.ansible.com/projects/galaxy-ng/en/latest/config/collection_signing.html)。collection signature 是针对 `MANIFEST.json` 的 ASCII-armored detached GPG signature；`MANIFEST.json` 再固定 `FILES.json` 和 collection 文件 checksum。
- Sonatype Nexus [Ansible Repositories](https://help.sonatype.com/en/ansible-repositories.html)、[Create an Ansible Repository](https://help.sonatype.com/en/create-an-ansible-repository.html)、[Configure Ansible with Nexus](https://help.sonatype.com/en/configure-ansible-with-nexus.html) 和 [Ansible CLI Usage](https://help.sonatype.com/en/ansible-cli-usage.html)。Nexus 3.93.0 起提供 `ansiblegalaxy` hosted/proxy/group、Galaxy v3、token/anonymous access、CLI publish/install 和 `/repository/{repo}/api/v3/plugin/ansible/content/published/...` artifact 路径。
- Sonatype Nexus [3.93.0-3.93.1 release notes](https://help.sonatype.com/en/sonatype-nexus-repository-3-93-0-release-notes.html)。3.93.1 修复 hosted collection 依赖安装和 Disable Redeploy 下发布新版本的问题；兼容基线必须覆盖这些回归，不能复制 3.93.0 已知缺陷。

关键结论：

- Galaxy v3 是客户端协议真相。collection 客户端不需要旧 v1 role API；新版本 ansible-core 已移除 collection 的 v2 API 支持。
- Nexus 对外 format/recipe 名称是 `ansiblegalaxy`。kkrepo 应使用 `RepositoryFormat.ANSIBLEGALAXY` 和 `ansiblegalaxy-{hosted|proxy|group}`，避免在迁移和 Nexus API 兼容层再维护一套 `ansible` 到 `ansiblegalaxy` 猜测映射。
- collection identity 是 `namespace.name`，发布单元是 `(namespace, name, version)`。namespace/name 由官方 collection metadata 规则约束，version 使用 Ansible 支持的语义化版本。
- collection version 不可变。相同 coordinate 的第二次发布必须失败；`EDIT` 权限或 hosted write policy 不能让调用方静默替换 artifact、manifest、dependencies 或 checksum。
- `ansible-galaxy` 首先请求 server URL，并从 JSON `available_versions.v3` 获取真实 v3 base。配置 URL 必须以 `/repository/{repo}/` 结束；不能要求客户端自行拼接 kkrepo 私有前缀。
- publish 主路径是 `POST .../api/v3/artifacts/collections/`，multipart 包含 `file` 和调用方计算的 `sha256`。响应返回可轮询的 `task` URL，客户端读取 `state`、`finished_at`、`messages` 和 `error`。
- Nexus 还公开 raw `PUT .../api/v3/plugin/ansible/content/published/collections/artifacts/{filename}`。它是 Nexus 兼容入口，不应替代标准 `ansible-galaxy collection publish` 主路径；两者必须复用同一校验和持久化服务。
- version detail 中至少要稳定返回 `namespace.name`、`collection.name`、`version`、`artifact.sha256`、`metadata.dependencies`、`download_url`、`href`、`requires_ansible` 和 `signatures`。`download_url` 必须是 absolute URL 或以 `/` 开头的 root-relative URL；普通相对路径会被当前客户端拒绝。
- `MANIFEST.json` 是 collection metadata 真相，且包含 `FILES.json` 的 SHA-256；`FILES.json` 再固定每个文件。服务端还必须独立校验上传参数的 artifact SHA-256，三层校验不能互相替代。
- Nexus 文档中的 token 是 Base64 编码的 `username:password`，由 Ansible Galaxy Bearer Token Realm 接收。当前 ansible-core 通过 `Authorization: Bearer ...` 发送，2.9 客户端使用历史 `Authorization: Token ...` scheme；kkrepo 在 Ansible route 内兼容两者，同时继续支持安全得多的 GenericToken/API key。
- Nexus group 是单 URL 聚合。对于同一 `(namespace, name, version)`，metadata、artifact、SHA-256 和 signatures 必须来自同一优先成员；只合并版本号而不绑定来源会产生 checksum 与下载内容错配。
- public Galaxy 当前文档中的上传大小限制不是私有仓库协议常量。kkrepo 应提供可配置上限，并以 Nexus 黑盒和本地资源策略确定默认值，不能把公开服务运营限制写死成协议。

## 功能范围

### 第一阶段必须实现

1. Ansible Galaxy hosted
   - 新增 `RepositoryFormat.ANSIBLEGALAXY`、`ansiblegalaxy-hosted` recipe 和独立 `protocol-ansible` 模块。
   - 实现 Galaxy v3 discovery、collection metadata、version list/detail、artifact download、multipart publish 和 import task 查询。
   - 支持 `ansible-galaxy collection publish <artifact>.tar.gz`，以及 Nexus 文档中的 raw artifact `PUT`。
   - 流式接收 tarball，不把完整 artifact 或解压内容保存在 JVM heap。
   - 校验请求 SHA-256、tar/gzip 结构、canonical filename、`MANIFEST.json`、`FILES.json`、namespace/name/version、dependencies、`requires_ansible` 和逐文件 checksum。
   - 同一 version 不可重发；新 version 可以在禁止 redeploy 的 write policy 下发布，对齐 Nexus 3.93.1 修复后的语义。
   - 返回持久化 import task；发布请求所在副本退出后，另一副本可以继续或接管处理。
   - 支持可选 GPG detached signatures 的保存和读取，但不在未配置 signing key 时伪造签名。
   - 支持 `GET`/`HEAD` artifact、ETag、Last-Modified、conditional GET 和稳定的 `Content-Disposition`。
   - UI/API 上传必须进入相同 hosted publish service，不能形成跳过 manifest/checksum/不可变校验的第二套写入路径。

2. Ansible Galaxy proxy
   - 新增 `ansiblegalaxy-proxy` recipe，remote URL 默认可配置为 `https://galaxy.ansible.com/`，保存时规范化尾部 `/`。
   - 先读取上游 API discovery，再按上游返回的 v3 base 访问 collection/version endpoint；不能假定所有上游都使用同一个长路径。
   - 缓存 collection metadata、version list/detail、dependencies、signatures、artifact validator、artifact bytes 和 negative result。
   - 把 `href`、`download_url`、pagination links 和 signature URL 改写到当前 kkrepo proxy 入口，避免客户端绕过本地鉴权、审计和缓存。
   - artifact 首次回源后按上游 `artifact.sha256` 校验并固定。相同 version 后续出现不同 checksum 时 fail closed，不能静默替换已缓存字节。
   - 支持 remote Basic/Bearer/token、出站 HTTP/SOCKS5 proxy、redirect、rate limit、auto-block、stale cache 和 SSRF 防护。
   - 跨 origin redirect 不转发上游 Authorization；合法 CDN/object-store 下载必须经过出站策略校验并以 metadata SHA-256 收口。
   - 多副本同时 miss 同一 artifact 时，通过数据库 lease/fencing 合并回源；进程内 single-flight 只能作为额外优化。

3. Ansible Galaxy group
   - 新增 `ansiblegalaxy-group` recipe，成员只允许 Ansible Galaxy hosted/proxy/group，并拒绝循环引用。
   - collection version list 按成员顺序聚合并去重；不同 version 可以来自不同成员，同一 version 冲突时第一命中成员优先。
   - 对选中的 `(namespace, name, version)` 建立持久化 source binding；version detail、artifact、checksum、dependencies 和 signatures 必须来自同一成员。
   - 返回 group 自身的 `href`、`download_url` 和 pagination links，artifact 下载再通过 binding 路由到实际成员。
   - 依赖解析继续通过 group URL 完成，覆盖 exact/range dependency、transitive dependency 和成员优先级。
   - Group 只读；multipart publish 和 Nexus raw `PUT` 返回 Nexus 3.94 实测的 `404`，不能自动选择某个 hosted 成员写入。
   - 成员顺序或内容 revision 变化时，通过共享版本水位失效 binding/materialized metadata；不能依赖单副本内存映射。

4. 认证与权限
   - 支持 HTTP Basic，用于 Nexus 文档中的 curl upload/download 和显式 username/password 配置。
   - 支持 `Authorization: Bearer GenericToken.<token>`、Ansible 2.9 的 `Authorization: Token GenericToken.<token>`、现有 API key/CI token 和未来可选的 `AnsibleGalaxyToken` domain。
   - 支持 Nexus 兼容 Base64 payload：严格解码后只接受单个非空 username 与 password，并走现有 Basic 认证；设置长度、控制字符和失败次数限制。
   - 不把 Base64 当加密。管理端和文档优先推荐 GenericToken/API key，不回显 bearer payload 或解码后的密码。
   - discovery、metadata、version、artifact、`HEAD` 映射 repository `READ`。
   - hosted 首次 publish 映射 `ADD`；重复 version 始终冲突，不因 `EDIT` 放宽。
   - collection/version 管理端删除映射 `DELETE`；第一阶段不公开 Galaxy CLI delete 协议。
   - import task 仅允许任务发起者或管理员读取，且发起者仍需具备目标仓库权限。
   - 显式提交无效 credential 时禁止降级为 anonymous；只有未提交 credential 且仓库允许 anonymous read 时才使用匿名主体。

5. Browse、搜索、管理和迁移
   - Admin UI 支持创建/编辑 `ansiblegalaxy-hosted`、`ansiblegalaxy-proxy`、`ansiblegalaxy-group`。
   - Browse/Search 展示 namespace、name、version、artifact size/SHA-256、authors、tags、dependencies、`requires_ansible`、signature 状态和来源成员。
   - Components API/UI upload 接受单个 canonical collection `.tar.gz`，并复用 v3 publish importer。
   - Nexus metadata migration 识别 3.93+ `ansiblegalaxy-hosted/proxy/group`，迁移 write policy、remote、TTL、成员顺序和 online 状态。
   - hosted data 只在 source profile 能证明 3.93.x-3.94.x 原生 Ansible datastore shape 时计划为 `FULL`；未知版本/shape 必须 `NEEDS_MANUAL_ACTION`。
   - proxy cache 只在管理员显式选择且 source profile 为 `FULL` 时迁移，保持 proxy 语义，不能把缓存 artifact 重放成 hosted publication。
   - masked/missing proxy secret 不写占位值；目标 proxy 保持 offline 并生成 manual action。

6. 兼容性和真实客户端测试
   - 新增 `AnsibleGalaxyRepositoryBlackBoxCompatibilityTest`，对 Nexus 3.93.1+/3.94.x 与 kkrepo 比较 discovery、route、status、header、JSON schema、pagination、错误和 artifact 字节。
   - 新增真实 `ansible-galaxy` hosted/proxy/group E2E，覆盖 Nexus 声称支持的 2.9 基线和 CI 选定的当前稳定 ansible-core。
   - 覆盖 build、publish、`--no-wait`、install、download、requirements exact/range、transitive dependency、anonymous/token/Basic 和 group 优先级。
   - 覆盖 3.93.1 回归：禁止 redeploy 时可发布新 version、重复 version 被拒绝、hosted dependency install 成功。
   - 覆盖签名读取与 `ansible-galaxy collection verify`；Nexus 不支持或不回传的可选行为必须明确标记 N/A/产品增强。

### 后续扩展和非目标

后续可以扩展：

- namespace owner、deprecation、approval/staging、content signing service 和 signature upload UI。
- Galaxy NG richer index/search/docs-blob API，但必须与客户端必需的 v3 contract 分层。
- collection policy/quarantine、SBOM、malware scan 和依赖风险视图。
- 跨仓库 promote/copy、immutable retention 和签名门禁。
- 对 Red Hat Automation Hub 等需要 OAuth/OIDC token exchange 的上游认证模式。

第一阶段明确不实现：

- 不实现 Galaxy v1 standalone role、GitHub role import、webhook/notification secret API。
- 不把 `.tar.gz` 当 Raw asset 后只返回静态文件目录。
- 不复制 Pulp/Galaxy NG 的 repository、distribution、approval 或 task 内部模型；只实现客户端和 Nexus 兼容所需语义。
- 不在 MySQL/PostgreSQL 中存储 tarball、完整 `MANIFEST.json`、完整 `FILES.json`
  或解压后的文件正文。数据库只保存有明确大小上限、可用于协议查询的元数据投影；完整
  JSON 始终随原始 collection artifact 保存在 blob store 中。
- 不允许覆盖同一 collection version，也不通过删除后重发绕过不可变策略；删除后是否允许重发必须先由 Nexus 黑盒固定，默认 fail closed。
- 不依赖单个 JVM 的本地 task queue、临时目录、锁或 metadata cache 作为正确性真相。

## URL 与路由设计

Ansible Galaxy 使用普通 Nexus 风格仓库入口：

```ini
[galaxy]
server_list = kkrepo_group

[galaxy_server.kkrepo_group]
url = https://repo.example.com/repository/ansible-group/
token = GenericToken.REDACTED
```

单个 collection 也可以在 `requirements.yml` 中指定 source：

```yaml
collections:
  - name: community.general
    version: ">=8.0.0,<9.0.0"
    source: https://repo.example.com/repository/ansible-group/
```

客户端不会把 v3 读取前缀写死。它先读取 `available_versions.v3`，再分别追加 `collections/...` 或 `artifacts/collections/`。因此以下表格用 `{v3-base}` 表示 discovery 返回的前缀；Galaxy NG 文档中的完整读取路径是 `api/v3/plugin/ansible/content/published/collections/index/...`，公开 Galaxy 同时提供 `api/v3/collections/...` 别名。kkrepo 最终返回哪个前缀以及是否同时保留长路径别名，必须由 Nexus 黑盒固定。

推荐 route contract：

| 请求 | 行为 |
| --- | --- |
| `GET /repository/{repo}/` | 返回 API discovery，至少包含 `available_versions.v3` |
| `GET /repository/{repo}/api/` | Ansible client fallback；是否与根路径同时支持由 Nexus 黑盒固定 |
| `GET /repository/{repo}/{v3-base}/collections/{namespace}/{name}/` | collection metadata；路径由 discovery 驱动 |
| `GET /repository/{repo}/{v3-base}/collections/{namespace}/{name}/versions/?limit=...&offset=...` | version list/pagination |
| `GET /repository/{repo}/{v3-base}/collections/{namespace}/{name}/versions/{version}/` | version detail、dependency、artifact、signature |
| `POST /repository/{repo}/{v3-base}/artifacts/collections/` | 标准 multipart collection publish；路径由 discovery 驱动 |
| `GET <task URL returned by publish>` | 查询 durable import task |
| `GET/HEAD /repository/{repo}/api/v3/plugin/ansible/content/published/collections/artifacts/{filename}` | artifact download |
| `PUT /repository/{repo}/api/v3/plugin/ansible/content/published/collections/artifacts/{filename}` | Nexus 兼容 raw artifact upload，仅 hosted |

API discovery 示例：

```json
{
  "available_versions": {
    "v3": "api/v3/"
  }
}
```

kkrepo 的 `available_versions.v3` 应相对当前 repository base 渲染，并保留反向代理 context path。上面的 `api/v3/` 只是便于阅读的候选值；若 Nexus 返回更长的 v3 base，kkrepo 应跟随 reference contract。Controller 不能把客户端路径和内部 service path 写死为同一个字符串，兼容测试还要证明 discovery 值与实际 metadata、publish endpoint 一致。

version detail 至少包含真实客户端使用的字段：

```json
{
  "version": "1.2.3",
  "href": "/repository/ansible-group/api/v3/collections/acme/tools/versions/1.2.3/",
  "requires_ansible": ">=2.16",
  "artifact": {
    "filename": "acme-tools-1.2.3.tar.gz",
    "sha256": "...",
    "size": 12345
  },
  "collection": {
    "name": "tools"
  },
  "namespace": {
    "name": "acme"
  },
  "download_url": "https://repo.example.com/repository/ansible-group/api/v3/plugin/ansible/content/published/collections/artifacts/acme-tools-1.2.3.tar.gz",
  "metadata": {
    "dependencies": {
      "community.general": ">=8.0.0,<9.0.0"
    }
  },
  "signatures": []
}
```

路径和响应规则：

- repository base、`href`、pagination link、task URL 和 `download_url` 必须从可信 external base URL 渲染，不能使用未经校验的 `Host`/forwarded header。
- namespace/name/version 先按 URL segment 解码一次；拒绝二次编码、slash、反斜线、NUL、控制字符和 dot segment。
- JSON endpoint 返回 `application/json`；v3 error 使用 `errors` 数组并保留 `status`、`code`、`title/detail` 和可选 `source`。
- pagination 同时接受 `limit`/`offset`。响应具体使用 `data` 还是 `results`、`links` 形状和排序以 Nexus 黑盒为准；同一 endpoint 不能在不同副本随机切换 schema。
- `download_url` 默认通过 kkrepo 自身返回 artifact，以持续执行权限、审计和 checksum。只有显式启用安全 object-store redirect 时才生成短期签名 URL，且不得把 repository Authorization 转发到另一 origin。
- metadata response 的 `ETag`/`Last-Modified` 由共享 revision/内容 hash 生成，不能用当前副本的请求时间。

## Collection artifact 校验

上传入口必须先保存原始字节并计算 SHA-256，再解析内容。原始 tarball 是最终下载字节真相，重新打包会改变 artifact SHA-256。

校验顺序：

1. 校验请求 content length、全局/repository upload limit 和流式读取上限。
2. 计算原始 artifact SHA-256，并与 multipart `sha256` 比较；raw `PUT` 没有该字段时仍计算并保存。
3. 解析 gzip/tar，拒绝损坏流、trailing garbage、设备节点、FIFO、绝对路径、`..`、反斜线路径、重复 entry、hardlink 和过量 PAX metadata。symlink 只接受指向归档内普通文件的相对路径；越界、绝对、悬空、目录或循环 symlink 一律拒绝。
4. 限制 entry 数、单 entry size、总展开 size、压缩比、路径长度和解析时间，防止 tar bomb/zip-slip 类资源攻击。
5. 要求根目录存在合法 `MANIFEST.json` 和 `FILES.json`，且 JSON 大小、深度、字段长度受限。
6. 校验 `MANIFEST.json.file_manifest_file` 指向 `FILES.json`，算法为 SHA-256，checksum 与原始 `FILES.json` 字节一致。
7. 校验 `FILES.json` 中每个 file entry 的类型和 SHA-256；manifest 未声明的普通文件、重复文件或 checksum 不一致按官方 importer/Nexus 行为处理。
8. 解析 `collection_info` 的 namespace、name、version、authors、license、tags、dependencies、repository/documentation/homepage/issues。
9. 解析 `meta/runtime.yml` 的 `requires_ansible`；仅把客户端能理解且官方 importer 接受的 spec 暴露到 v3 metadata。
10. 由 manifest identity 生成 canonical filename `{namespace}-{name}-{version}.tar.gz`，并与上传 filename/path 对比。version 可能包含 prerelease hyphen，所以不能仅靠 `-` split filename 推导 identity。

校验失败分两类：

- 请求级错误，例如缺少 multipart file、请求 SHA-256 不匹配或 filename 非法，应按 Nexus v3 error schema立即返回 `400`。
- importer 级错误，例如 tar 可读取但 `MANIFEST.json` 缺字段，可先返回 durable task，再将 task 标记 `failed` 并写入稳定 error code/message；具体分界由 Nexus 兼容测试固定。

## 数据模型落地

通用 component/asset/blob 模型继续承载跨格式能力，Ansible 专用表只保存协议查询、异步任务和多副本协调所需状态。

### 通用 component 与 asset

- 每个 collection version 建立 component：`format=ANSIBLEGALAXY`、`namespace=<namespace>`、`name=<name>`、`version=<version>`、`kind=ansible-collection`。
- `(repository_id, coordinate_hash)` 唯一约束保护 `(repository, namespace_lc, name_lc, normalized_version)`。
- 原始 tarball 建立 artifact asset，关联 OSS/S3 blob，保存 SHA-256/SHA-512、size、media type、createdBy、createdByIp 和 canonical filename。
- `MANIFEST.json`、`FILES.json` 和其完整文件清单只存在于原始 artifact blob 中；导入时流式校验后不复制进关系数据库。
- collection/version JSON 是从有上限的专用状态生成的 protocol metadata；可以 materialize 为 metadata asset，但 materialized JSON 不是第二份独立真相。
- Browse node 使用 `namespace/name/version/filename` 层级，避免把 v3 API 路径本身当作用户目录模型。

### Ansible 专用状态

V35 已增加以下 JDBC contract 和 MySQL/PostgreSQL migration：

- `ansible_collection_version`
  - `repository_id`、`namespace_lc`、`name_lc`、`version_normalized` 唯一。
  - 保存原始/normalized version、component/asset id、artifact SHA-256/size、协议元数据投影、dependencies、`requires_ansible`、state、revision 和时间；不保存完整 manifest/files JSON。
- `ansible_collection_signature`
  - 绑定 collection version、signature hash/blob、key fingerprint、source、created_at；signature 可追加但不能替换 artifact。
- `ansible_import_task`
  - 保存 task UUID、repository、发起主体、staging blob、expected/actual SHA-256、state、messages/error、attempt、lease owner/expiry、fencing token、created/started/finished 时间。
- `ansible_proxy_version_state`
  - 保存 upstream href/download URL、metadata validator、artifact SHA-256、cache_until、verified_at、negative state 和有上限的协议元数据投影；上游返回的完整 files/contents/signatures 列表不复制入库。
- `ansible_proxy_inventory` / `ansible_proxy_inventory_version`
  - 按 `(repository, namespace, name)` 保存 inventory TTL/revision/count header，并把 version name 作为有上限的规范化行保存。TTL 有效时读取这些行，不重复遍历上游分页；不把整份 version inventory 写成大 JSON。
- `ansible_group_binding`
  - 以 group repository、namespace/name/version、member revision 为键，绑定 source repository、artifact filename 和 SHA-256。Proxy metadata 首次选源时允许 materialized version 引用为空；artifact `GET` 必须沿同一 binding 回源，并在 blob/asset/version 提交后原子回填 version 引用。
- `ansible_registry_lease`
  - 以 repository/operation/coordinate 为键保存 owner、lease expiry 与 fencing token，用于跨副本 publish、proxy revalidation/download 和任务接管。

所有 JSON/enum/hash 列必须通过 `persistence-jdbc` 公共 helper 和 dialect contract 写入。当前 version metadata 上限为 64 KiB、dependencies 为 192 KiB、proxy protocol metadata 为 256 KiB；超过上限必须裁剪为协议所需投影或转为 blob asset，不能扩大数据库字段绕过边界。时间、唯一约束、lock/claim 和 upsert 语义需同时由 MySQL/PostgreSQL integration test 验证。

## Hosted 发布与 import task

Controller 只负责 HTTP、认证上下文和 repository/path 解析，再委托统一的 `AnsibleGalaxyService` 与 `AnsibleCollectionArchiveInspector`；Components API 和 raw PUT 复用同一 archive importer。

标准 multipart publish 流程：

1. 校验 repository online 且 recipe 为 `ansiblegalaxy-hosted`，主体具备 `ADD`。
2. 流式写入 blob store 的 staging key，同时计算 SHA-256；失败请求清理未引用 staging blob。
3. 校验 multipart `sha256`、filename 和请求级限制。
4. 在数据库事务中创建 `WAITING` import task 和 marker/outbox，记录 staging blob 与调用者；返回 Nexus 对应的 `202` 和 `task` URL。
5. worker 通过数据库 lease/fencing claim task，执行 tar/manifest/files/metadata 校验。
6. 在事务中插入 collection version、component、asset、asset_blob、signature 和 browse node；coordinate 唯一约束裁决并发重复发布。
7. 原子切换 staging blob 为正式引用，提交 version revision，并失效 collection/version/group metadata cache。
8. task 标记 `COMPLETED`、写 `finished_at`；失败则保存稳定 error/message 并释放无引用 staging blob。

Nexus raw `PUT` 可以同步完成或返回异步结果，但必须调用同一 importer。若 reference 对 raw `PUT` 返回不同 status/body，compat adapter 只改变 transport response，不改变写入规则。

多副本任务语义：

- task、lease、fencing token、attempt 和结果以数据库为真相；本地 executor queue 只是唤醒手段。
- worker 在 lease 过期后可被另一副本接管；旧 worker 的 fencing token 不能提交 version 或覆盖新 task 结果。
- 同一 coordinate 并发 publish 最多一个成功，其余返回/终止为 conflict；不得产生两个 artifact blob 引用。
- staging blob cleanup 已按共享 asset 行、task 状态和保留期实现：所有副本用 `FOR UPDATE SKIP LOCKED` 有界领取 `.ansible/staging/`，保留 `WAITING`/`RUNNING` task，只清理 task 缺失或已终态的行；不能仅依赖原请求 `finally`。
- task 查询在重启、滚动升级和副本切换后继续可用；过期 task 按保留策略清理，但已发布 version 不依赖 task 行读取。

## Proxy 缓存流程

Proxy 以共享数据库和 blob store 为 cache 真相。

Metadata 请求：

1. 读取并缓存 upstream discovery，保存上游 v3 base；remote 配置 revision 变化时失效。
2. 查询本地 collection/version metadata。fresh 时直接重写本地 link 后返回。
3. 命中共享 negative cache 时返回 `404`。
4. 获得 `(repository, namespace, name[, version])` revalidation lease，携带上游 validator 请求 metadata。
5. 上游 `304` 刷新 verification/cache time；`200` 校验 schema 后保存 metadata、dependency、signature 和 artifact identity；`404/410` 写短 TTL negative cache。
6. 释放 lease并递增共享 revision；等待者读取相同结果。

Artifact 请求：

1. 从已保存 version detail 取得 upstream `download_url` 和 expected SHA-256。
2. 若该 SHA-256 blob 已缓存，直接返回；artifact immutability 不随 metadata TTL 过期。
3. 通过共享 lease 合并跨副本回源，执行 redirect/SSRF/credential policy。
4. 流式下载并计算 SHA-256；与 expected 值不一致时删除 staging blob、记录 upstream integrity failure 并 fail closed。
5. 成功后写 asset/blob binding，再返回原始字节。另一副本只能从已提交 binding 读取。

如果上游后来为同一 version 返回不同 artifact SHA-256，记录 drift 并阻止 metadata 替换。管理员必须显式调查/清除 proxy state，不能让正常 TTL revalidation 改变 lockfile 所依赖的内容。

## Group 聚合流程

Group 对客户端呈现一个完整 v3 server。

- collection 存在性按成员顺序检查；所有可见成员都 missing 时返回 `404`。
- version list 对所有可见成员取 union；同一 normalized version 冲突时保留第一成员。
- version detail 为 exact version 选择第一成员并写 source binding，随后从该成员读取完整 metadata。
- group response 中 `href`、`download_url` 和 pagination links 指向 group；dependencies 保持 collection metadata 原值，由客户端继续通过当前 server/group 解析。
- artifact 请求按 source binding 读取，校验 source revision 和 SHA-256；不能重新做一次可能选到不同成员的无状态搜索。
- signature 与 artifact 使用同一 binding；不跨成员拼接 signatures。
- 调用者无权读取的成员不能贡献 version、metadata 或错误细节，避免通过 group 枚举私有 collection。
- group ETag 由成员配置 revision、可见 version 集和 source revision 共同生成；member publish/delete/reorder 通过数据库 marker 触发失效。

## 权限与认证

权限映射：

| Galaxy 操作 | kkrepo 权限 |
| --- | --- |
| API discovery | `READ`；匿名仓库可匿名 |
| collection/version list/detail | `READ` |
| artifact `GET`/`HEAD` | `READ` |
| hosted multipart publish | `ADD` |
| hosted Nexus raw `PUT` | `ADD` |
| import task query | 发起者/管理员 + repository access |
| 管理端删除 version/collection | `DELETE` |
| group/proxy publish | 拒绝写入 |

认证适配顺序：

1. 正常解析现有 API key/GenericToken bearer，保持 domain、scope、expiry、disabled 和 audit 语义。
2. 正常解析 HTTP Basic。
3. 对 Ansible Galaxy route 的未识别 Bearer/Token credential，尝试有上限的严格 Base64 `username:password` 兼容解析，再走现有 Basic auth cache/realm。
4. 任一显式 credential 无效即返回 `401` 和正确 `WWW-Authenticate`，不尝试 anonymous fallback。

Base64 Bearer/Token compatibility 只在 Ansible Galaxy route 启用，不能扩散为全站通用 password transport。原始 token、Basic header、解码密码、remote token、task upload URL 和带签名 object URL 必须在 access log、audit detail、exception、trace、metrics 和 CI artifact 中脱敏。

## 多副本与缓存语义

- repository 配置、collection/version 的有界协议元数据、dependency、signature 引用、task、lease、source binding、negative cache、remote validator、asset/blob 引用和 revision 都以数据库/OSS 为真相。
- collection artifact（包含完整 `MANIFEST.json`/`FILES.json`）和 signature blob 只存 OSS/S3/File blob store；线上游 JSON 只保留有界协议投影，任何必须原样保留的大 JSON 也必须写 blob，数据库只保存引用、hash、size 和协议状态。
- 本地 cache 只保存可从共享真相重建的 discovery/metadata/binding 热数据，必须有 TTL 或 revision invalidation；version-list cache 的有效期不得晚于该 coordinate 所有 proxy inventory 中最早的 `cache_until`。
- publish/import、proxy download 和 group revalidation 使用数据库 lease/fencing 或 marker queue；单 JVM lock 只能减少本副本重复工作。
- hosted publish/delete、proxy metadata 更新、group member 变化都递增共享版本水位，使其它副本在 TTL 之前也能观察变化。
- 跨副本并发测试必须验证：同 version publish 一次成功、同 artifact 只回源一次、task 可接管、group metadata 与下载 source 一致。

### 性能与容量实现

- Version publish/delete 递增 repository content revision，并按 `(repository, namespace, name, version)` 精确失效 group binding；revision 分配、metadata 写入和失效在同一事务提交。Group 成员顺序/配置使用独立 config revision，避免一次发布触发全仓库 version 扫描和全部 binding 删除。
- Version-list 一次批量读取相关 repository revision，SemVer 排序时每个 version 只解析一次；按 `identity + revision snapshot` 使用可重建的本地 cache。正确性仍以数据库 revision 和 binding 为准。
- Proxy version inventory 以 header + version 行规范化保存。TTL 命中直接读取本地 inventory；TTL 到期才串行拉取所有上游分页，并通过数据库 lease、fencing token、节点内 single-flight 合并并发 refresh。
- Proxy JSON 使用 16 MiB 输入上限的 streaming parser，只提取协议字段，再执行 64/192/256 KiB 投影上限。完整 collection archive、`MANIFEST.json`、`FILES.json` 和大型 payload 始终在 blob storage；数据库没有无上限 JSON。
- 标准 multipart publish 只流式写一次 staging blob，创建 durable task 后立即返回 `202`。Worker 原子批量 claim task，并直接 promote 同一 blob 引用；promotion 阶段不复制临时文件、不重复上传或再次执行 hash pass。
- Archive inspection、import worker 和 migration 复用的 inspector 都有并发上限与 permit timeout。长操作续租 fenced lease；同副本等待者通过 single-flight 合并，跨副本等待使用 50-500 ms 指数退避。
- Artifact hot path 使用只包含 asset/blob reference 的轻量查询并直接读取 blob，避免先 `stat` 再 `GET`。Proxy page/negative state、终态 task 和过期 lease 由每副本幂等、有界批次 cleanup；staging asset 继续由独立 `SKIP LOCKED` worker 清理。

## Nexus 迁移设计

Ansible 是 Nexus 3.93.0 新增的原生格式，迁移必须 version/shape gated：

- repository metadata 识别 `format=ansiblegalaxy` 与 hosted/proxy/group recipe，保留 repository 名称，从而保持 `/repository/{repo}/` 客户端 URL 不变。
- hosted data adapter 读取并验证 namespace/name/version、原始 tarball、artifact SHA-256 和可恢复 metadata；写入时调用同一个 protocol-aware importer。
- source asset fingerprint 必须证明 Nexus 3.93.x-3.94.x 预期 shape。缺 manifest、checksum、component identity 或版本超出范围时生成 manual action。
- proxy/group 配置可以迁移；proxy secret 只有在源导出可恢复时加密写入，masked/missing secret 使目标 offline。
- proxy cache 需管理员在 `Optional proxy repositories` 显式选择。迁移只恢复协议识别的 metadata/artifact cache 和 checksum binding，不创建 hosted publication。
- migration job 支持 dry-run、resume、checksum、幂等、失败报告和跨副本 claim；重复运行不能重复创建 version 或 blob 引用。
- 3.93.0 已知 dependency/redeploy 缺陷不应成为目标行为。迁移 E2E 应使用 3.93.1+/3.94.x reference，并保留 3.93.0 source shape 只用于读取兼容验证。

## 安全与资源限制

- tar parser 禁止路径穿越、绝对路径、hardlink、设备文件、重复 entry 和解压炸弹；symlink 仅允许安全解析到同一归档内的普通文件，并纳入 `FILES.json` checksum 校验。
- JSON/YAML parser 限制大小、深度、alias、string/list/map 数量；`meta/runtime.yml` 不允许任意类型实例化。
- namespace/name/version、filename、dependency key/range、URL、authors/tags/license 都设置长度和数量上限。
- Proxy remote、metadata link、download URL、redirect 和 signature URL 统一走 `OutboundRequestPolicy`，阻止 loopback、link-local、metadata service 和 DNS rebinding。
- cross-origin redirect 删除 Authorization/cookie；remote credential 不写入下游 response、asset attributes 或日志。
- artifact SHA-256 不一致、manifest/files checksum 不一致、同 version upstream drift 都 fail closed。
- upload/import task 设置并发、字节、CPU time 和保留期配额；单用户/单仓库滥用不能耗尽所有 worker。
- metrics label 不包含 namespace/name/version/token/task UUID 等高基数字段；这些信息只进入受控 audit/log field。

## Browse、管理和观测

Admin UI：

- Hosted：blob store、write policy、strict content validation、upload limit、signature policy。
- Proxy：remote URL/auth、metadata/content TTL、negative TTL、stale policy、auto-block、HTTP/SOCKS5 proxy。
- Group：有序成员、循环校验、binding/cache revision。
- 上传页显示 importer task、进度、warning/error、artifact SHA-256 和最终 collection coordinate。

Browse/Search：

- collection 层显示 namespace/name、latest version、更新时间和来源 repository。
- version 层显示 SemVer、requires_ansible、authors、tags、license、dependencies、artifact size/SHA-256、signature 和 source member。
- artifact 下载始终经过 repository 权限和审计，不把内部 blob key 暴露给用户。

建议新增指标：

- `kkrepo_ansible_api_requests_total{repository,type,operation,result}`
- `kkrepo_ansible_publish_tasks_total{repository,result}`
- `kkrepo_ansible_publish_task_duration_seconds{repository,result}`
- `kkrepo_ansible_artifact_bytes_total{repository,type,direction}`
- `kkrepo_ansible_proxy_revalidate_total{repository,result,status}`
- `kkrepo_ansible_proxy_integrity_failures_total{repository}`
- `kkrepo_ansible_group_resolution_total{repository,result}`
- `kkrepo_ansible_active_import_tasks{repository}`
- `kkrepo_ansible_task_takeovers_total{repository}`

审计事件至少记录 repository、operation、namespace/name/version、artifact SHA-256、user/source、API key id、source member/upstream、task id 和结果；不得记录 credential。

## 兼容性测试矩阵

### HTTP 与 Nexus reference

- API discovery 根路径、`/api/` fallback、trailing slash 和 context path。
- collection missing、version missing、分页边界、`data/results`、links、排序、content type 和 v3 error schema。
- version detail 必填字段、dependency range、requires_ansible、absolute/root-relative download URL 和 signatures。
- multipart publish 的 content type、file/SHA-256、status、task URL、poll state/messages/error。
- raw `PUT`、Basic auth、canonical/non-canonical filename、错误 SHA、损坏 tar、重复 version、新 version 和 write policy。
- artifact `GET`/`HEAD`、ETag、Last-Modified、304、Content-Disposition、匿名/认证读取。
- proxy upstream 404/429/5xx、redirect、conditional request、checksum drift、negative/stale cache。
- group version union、duplicate version priority、dependency、source binding、member reorder 和权限隐藏。

对 timestamp、task UUID、host 和动态签名 URL 只做协议允许的规范化；status、header、error code、JSON field、checksum、依赖和 artifact 字节不做宽松归一化。

### 真实 ansible-galaxy 客户端

- `ansible-galaxy collection init/build` 生成测试 collection。
- publish 到 hosted，轮询完成后从 hosted/group install。
- 使用 `requirements.yml` 安装 exact、range 和 transitive dependencies。
- 从 proxy/group 安装 `community.general` 等固定版本，并在断开 upstream、清空客户端 cache 后复装。
- `collection download` 后校验 artifact SHA-256；安装后校验 `MANIFEST.json`/`FILES.json`。
- Basic、GenericToken、Nexus Base64 bearer、anonymous read、错误 credential 不降级。
- prerelease/版本排序、duplicate publish、`--no-wait`、task restart/takeover。
- 配置 keyring 时验证 server-provided signature；无 signature 时按客户端配置测试允许/拒绝分支。
- 双副本 + MySQL/PostgreSQL + S3-compatible blob store 验证 publish、proxy miss、group resolve 和 restart。

### 迁移

- Nexus 3.93.1+/3.94.x hosted collection 发布后迁移到 MySQL/PostgreSQL target，通过真实客户端安装并校验 SHA-256/dependencies。
- repository definition、proxy remote/TTL、group member order 和 anonymous/auth 配置迁移。
- 显式选择 proxy cache，断开目标 upstream 后验证已恢复 artifact；未选择的 proxy 不迁移 cache。
- masked secret、未知 source version、shape drift、损坏 artifact、restart/resume 和重复运行。

## 实施顺序

1. M0 Nexus/客户端基线
   - 在 Nexus 3.93.1+/3.94.x 创建 isolated hosted/proxy/group。
   - 固定 discovery、v3 endpoint、publish task、raw PUT、download、error、auth 和 group 行为。
   - 保存最小 collection、依赖 collection、prerelease、signed 和恶意 tar fixtures。

2. core 与 recipe
   - 新增 `RepositoryFormat.ANSIBLEGALAXY` 和 `ansiblegalaxy-*` recipes。
   - 更新 repository validation、搜索、权限、Admin/Browse format catalog。

3. `protocol-ansible`
   - 实现 coordinate/SemVer、path parser、discovery、v3 models、pagination、error、manifest/files models 和 media type。
   - 单元测试覆盖 URL encoding、schema、filename/manifest identity 和 dependency range。

4. JDBC schema 与 importer task
   - 增加 version/signature/task/proxy/binding contract 和 MySQL/PostgreSQL migration。
   - 落地 claim/lease/fencing、staging blob 和 cleanup。

5. hosted read path
   - 实现 discovery、collection/version metadata、artifact GET/HEAD 和 conditional response。

6. hosted publish
   - 实现 multipart POST、raw PUT、durable task、artifact/manifest/files validation 和不可变写入。
   - 用真实 `ansible-galaxy publish/install` 验证。

7. 认证适配
   - 实现 Basic、GenericToken 和 route-scoped Nexus Base64 Bearer/Ansible 2.9 Token compatibility。
   - 验证错误 credential 不降级、日志脱敏和 rate limit。

8. proxy
   - 实现 upstream discovery、metadata/artifact cache、URL rewrite、SHA-256 pinning、redirect/SSRF 和跨副本合并回源。

9. group
   - 实现 version union、priority、source binding、dependency flow、member invalidation 和只读行为。

10. Admin/Browse/Search/UI upload
    - 完成三类 repository 配置、task 进度、collection/version 视图和搜索字段。

11. Nexus migration
    - 完成 repository metadata、shape-gated hosted data、optional proxy cache、dry-run/resume/checksum/idempotency。

12. 生产加固
    - 完成真实客户端矩阵、双数据库、双副本、S3-compatible resilience、备份恢复、指标、审计和文档。

## 验收标准

- `ansible-galaxy` 2.9 基线和当前稳定版都能通过 `/repository/{repo}/` discovery 使用 hosted/proxy/group。
- hosted publish/install/download、dependency range、transitive dependency、GenericToken/Basic/Nexus bearer 和 anonymous policy 有真实客户端证据。
- multipart publish 返回可跨副本轮询/接管的 durable task；同 version 并发发布最多一个成功。
- artifact 原始 SHA-256、`MANIFEST.json`、`FILES.json` 和逐文件 checksum 全部校验；恶意 tar 不落正式 blob 引用。
- proxy 固定 artifact SHA-256，跨副本 miss 不重复写入，upstream drift/SSRF/credential redirect fail closed。
- group 的 metadata、dependency、artifact 和 signature 使用同一 source binding；成员顺序变更可在共享 revision 后恢复。
- MySQL/PostgreSQL contract/integration test 均通过；完整 manifest/files 与其它大 JSON 和 blob 只存 OSS/S3，数据库 JSON 均有硬上限。
- Nexus 3.93.1+/3.94.x compatibility test 固定 status/header/body/task/error，并覆盖 3.93.1 dependency/redeploy 回归。
- Nexus hosted data 可 dry-run、resume、checksum、幂等迁移；显式选择的 proxy cache 可在断上游后由真实客户端使用。
- README 中 Ansible Galaxy 已在协议、持久层、服务、UI、迁移、黑盒和真实客户端 E2E 入口落地后标记为 `✅`；签名验证等 Nexus reference 不提供的可选增强仍单独跟踪，不影响 Nexus 兼容完成度。

## 参考资料

- Ansible: Distributing collections: https://docs.ansible.com/projects/ansible/latest/dev_guide/developing_collections_distributing.html
- Ansible: Collection Galaxy metadata structure: https://docs.ansible.com/projects/ansible/latest/dev_guide/collections_galaxy_meta.html
- Ansible: Installing collections: https://docs.ansible.com/projects/ansible-core/devel/collections_guide/collections_installing.html
- Ansible: Configuration settings: https://docs.ansible.com/projects/ansible/latest/reference_appendices/config.html
- ansible-core Galaxy v3 client: https://github.com/ansible/ansible/blob/devel/lib/ansible/galaxy/api.py
- Galaxy NG v3 API: https://github.com/ansible/galaxy_ng/blob/main/docs/community/api_v3.md
- Galaxy NG collection signing: https://docs.ansible.com/projects/galaxy-ng/en/latest/config/collection_signing.html
- Sonatype Nexus: Ansible Repositories: https://help.sonatype.com/en/ansible-repositories.html
- Sonatype Nexus: Create an Ansible Repository: https://help.sonatype.com/en/create-an-ansible-repository.html
- Sonatype Nexus: Configure Ansible with Nexus: https://help.sonatype.com/en/configure-ansible-with-nexus.html
- Sonatype Nexus: Ansible CLI Usage: https://help.sonatype.com/en/ansible-cli-usage.html
- Sonatype Nexus 3.93.0-3.93.1 Release Notes: https://help.sonatype.com/en/sonatype-nexus-repository-3-93-0-release-notes.html
