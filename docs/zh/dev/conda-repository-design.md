# Conda 仓库开发设计说明

本文记录 kkrepo Conda 仓库格式的开发设计。目标不是把 `.conda` 或 `.tar.bz2` 文件当作 Raw 制品保存，而是对齐 Conda 官方 channel、package 和 repodata 规范，并以 Sonatype Nexus Repository 的客户端入口和仓库 recipe 作为兼容性参考，按 kkrepo 的关系数据库 + OSS/S3 + 多副本约束落地 hosted、proxy、group 三类 Conda 仓库。

## 当前支持状态

截至 2026-08-07，kkrepo 尚未实现 Conda 格式。本设计是后续实现基线，不表示当前版本已经可以创建或使用 Conda 仓库。

计划新增：

- `RepositoryFormat.CONDA`。
- `conda-hosted`、`conda-proxy`、`conda-group` 三个 recipe。
- 独立 `protocol-conda` 模块，以及 Admin、Browse、Search、上传、迁移和兼容性测试入口。
- `.tar.bz2` 与 `.conda` 两种 package format。
- `repodata.json`、`repodata.json.bz2`、`repodata.json.zst`、`channeldata.json` 和后续的 sharded repodata 能力。

三类仓库共用同一套 channel/path、package record、metadata revision、权限和多副本语义，因此在一份设计中统一说明；实现时仍须保持 hosted、proxy、group 的写入边界和服务职责独立。

## 调研基线

实现前必须对照以下官方规范、客户端行为和 Nexus 参考行为：

- Conda [Channel identifiers](https://conda.org/learn/specifications/channels/channel-identifiers/) 定义 channel base、`noarch`、平台 subdir 和 channel URL 语义。一个有效 channel 必须存在 `noarch/repodata.json`。
- Conda [Package specification](https://docs.conda.io/projects/conda-build/en/latest/resources/package-spec.html)、[`.tar.bz2` package format](https://conda.org/learn/ceps/cep-0034/) 和 [`.conda` package format](https://conda.org/learn/ceps/cep-0035/) 定义文件命名、`info/index.json`、归档布局和两种 package container。
- Conda [repodata specification](https://conda.org/learn/ceps/cep-0036/) 定义 `packages`、`packages.conda`、`removed`、checksum、size、`info.subdir`、`base_url` 和压缩变体。
- Conda [channeldata specification](https://conda.org/learn/ceps/cep-0038/) 定义 channel root 下可选的 `channeldata.json`。
- Conda [sharded repodata](https://conda.org/learn/ceps/cep-0016/) 定义 `repodata_shards.msgpack.zst` 和 content-addressed shard；当前客户端已经会探测该能力。
- Conda [run exports](https://conda.org/learn/ceps/cep-0012/)、[repodata `base_url`](https://conda.org/learn/ceps/cep-0015/)、[channel notices](https://conda.org/learn/ceps/cep-0006/) 和 [channel relations](https://conda.org/learn/ceps/cep-0042/) 用于补齐 package metadata、代理 URL 改写和可选 channel root 能力。
- Conda 官方 [Creating custom channels](https://docs.conda.io/projects/conda/en/stable/user-guide/tasks/create-custom-channels.html) 与 conda-build [Generating channel indexes](https://docs.conda.io/projects/conda-build/en/latest/concepts/generating-index.html) 给出真实 channel 目录和索引生成流程。
- Sonatype Nexus [Conda Repositories](https://help.sonatype.com/en/conda-repositories.html)、[Create a Conda Repository](https://help.sonatype.com/en/create-a-conda-repository.html)、[Configure Conda with Nexus](https://help.sonatype.com/en/configure-conda-with-nexus.html) 和 [Conda CLI Usage](https://help.sonatype.com/en/conda-cli-usage.html) 定义 Nexus 的 hosted/proxy/group recipe、`/repository/{repo}/...` 入口、客户端配置和 raw HTTP `PUT` 上传方式。
- Sonatype Nexus [3.92.0 release notes](https://help.sonatype.com/en/sonatype-nexus-repository-3-92-0-release-notes.html) 是 hosted/group 与自动 metadata 生成能力的版本基线，也说明 Nexus 数据迁移只迁移 package binary、在目标端重建生成 metadata。

关键结论：

- Conda 仓库的协议边界是 channel，不是文件目录列表。channel root 下按 `noarch` 或平台 subdir 分区，每个 subdir 都有独立 repodata。
- package identity 不能只用 `name + version`。同一 version 可以有多个 build；完整发布身份至少是 `(channel, subdir, name, version, build)`。
- `.tar.bz2` 记录位于 `packages`，`.conda` 记录位于 `packages.conda`。同一 build 的两种归档可以共存，不能互相覆盖。
- package filename 中的连字符不足以可靠拆分 name、version 和 build；服务端必须读取受限的 `info/index.json`，再校验 URL、filename 和 metadata 一致性。
- CEP 34 schema v2 要求 `subdir` 等字段，但历史 `.tar.bz2` package 可能只有旧版 `index.json`。Hosted importer 需要区分 strict v2 与经 fixture 允许的 legacy v1；legacy 缺少 `subdir` 时可以从已验证的上传路径派生 repodata 字段，但不能改写原 package bytes。
- `repodata.json` 的 package record 至少需要稳定的 MD5、SHA-256、size、build、build number、depends、name、version 和 subdir。完整 package bytes 及生成 metadata blob 放 OSS/S3，数据库只保存有界投影、引用和 revision。
- Nexus 文档中的 hosted 上传是 `PUT /repository/{repo}/{channel-prefix?}/{subdir}/{filename}`。Proxy 和 group 对该写路径只读；kkrepo 必须先以黑盒测试固定状态码，再实现相同行为。
- Channel prefix 必须进入 package identity。Nexus 3.94 黑盒中，同一 package 先上传到根 channel、再上传到嵌套 channel 时可能第二次返回 `201` 但不真正发布；kkrepo 不复制这种静默成功行为。
- Group 不能只合并 repodata。索引选中的记录与后续 package 下载必须绑定同一成员，否则客户端会收到索引中存在但下载 `404`，或 checksum 与 bytes 不一致的结果。
- `info.base_url`、shard base URL 和 channel relation 可以让客户端下载其它 origin。Proxy/group 必须删除或安全改写为当前 kkrepo 入口，避免绕过权限、审计、缓存和出站策略。
- Nexus 3.94 黑盒会生成 `repodata.json`、`repodata.json.bz2` 和 `channeldata.json`，但不会生成 `.zst`、`current_repodata.json` 或 shard。它也不会为空 channel 自动补 `noarch/repodata.json`。kkrepo 以 Nexus 路由兼容为基线，但对这些官方协议和当前客户端能力采用明确的产品增强，不复制已知缺口。
- Conda version/build 排序不是 SemVer。凡是需要选取 latest、生成 `channeldata.json` 或裁剪 `current_repodata.json` 的地方，必须使用与 Conda `VersionOrder`/build 规则一致并由 fixture 固定的比较器，不能复用通用 SemVer 工具。

## 功能范围

### 第一阶段必须实现

1. Conda hosted
   - 新增 `RepositoryFormat.CONDA`、`conda-hosted` recipe 和 `protocol-conda` 模块。
   - 支持根 channel 和有路径前缀的 channel；相同 package coordinate 可以独立发布到不同 channel。
   - 支持 raw HTTP `PUT`、Admin/UI upload 和 Components API upload，所有入口复用同一个 protocol-aware importer。
   - 支持 `.tar.bz2` 和 `.conda`，校验归档结构、canonical filename、`info/index.json`、subdir、依赖、checksum 和资源限制。
   - 自动生成每个 subdir 的 repodata，以及 channel root 的 `channeldata.json`；已知 channel 自动提供最小 `noarch/repodata.json`。
   - 支持 package `GET`/`HEAD`、Range、ETag、Last-Modified 和 conditional request。
   - 发布、替换或删除后以 revision snapshot 原子切换 package 可见性和生成 metadata，不能让客户端观察到 metadata 与 package bytes 的半完成状态。

2. Conda proxy
   - 新增 `conda-proxy` recipe；remote URL 表示一个准确的上游 channel root。
   - 缓存并校验上游 repodata、channeldata、package bytes、validator、negative result 和可选 shard。
   - 区分 metadata TTL 与 content TTL；metadata 高频 revalidate 不能让不可变 package blob 重复回源。
   - 首次下载 package 时依据同一 subdir 的 repodata 校验 MD5/SHA-256/size，校验失败时 fail closed。
   - 把 metadata 中会绕过本仓库的 URL 改写或删除；跨 origin redirect 不转发上游 credential。
   - 支持 remote Basic/Bearer/token、出站 HTTP/SOCKS5 proxy、auto-block、stale policy、SSRF 防护和凭据脱敏。
   - 多副本 miss 使用数据库 lease/fencing 合并回源；单 JVM single-flight 只能作为额外优化。

3. Conda group
   - 新增 `conda-group` recipe；成员只允许 Conda hosted/proxy/group，并拒绝直接或间接循环。
   - 按成员顺序聚合每个 `(channel, subdir)` 的 `packages` 与 `packages.conda`；同名记录冲突时第一成员优先。
   - 为选中的 package record 建立持久化 source binding；metadata、signature/checksum 和下载 bytes 来自同一成员。
   - 合并 `channeldata.json`、`removed` 和支持的压缩 metadata 变体，并把所有对外 URL 渲染为 group URL。
   - 成员发布、删除、revalidate 或重排后，通过共享 revision 使 materialized metadata 和 binding 失效。
   - Group 只读，不把上传请求自动转发到某个 hosted 成员。

4. 权限与认证
   - Metadata、package `GET`/`HEAD` 映射 repository `READ`。
   - Hosted 首次上传映射 `ADD`；允许 redeploy 时的替换映射 `EDIT`；删除映射 `DELETE`。
   - 支持 HTTP Basic、现有 API key/CI token 和 `GenericToken`。显式无效 credential 不得降级为 anonymous。
   - Proxy remote secret 加密保存，UI/API/log/audit/metric 不回显明文或带 credential 的 channel URL。

5. Browse、Search、管理和迁移
   - Admin UI 支持创建/编辑三类 recipe，以及 hosted write policy、proxy remote/TTL/stale/auth 和 group member 顺序。
   - Browse/Search 展示 channel、subdir、name、version、build、build number、格式、依赖、license、timestamp、size、checksum 和来源成员。
   - Nexus repository definition 迁移识别 `conda` hosted/proxy/group；hosted data 迁移 package binary 后由目标 importer 重建 metadata。
   - Proxy cache 仅在管理员显式选择且 source shape 可证明时迁移，不能把 cache package 变成 hosted publication。

6. 兼容性和真实客户端验证
   - 在实现前新增面向 Nexus 和 kkrepo 的 Conda black-box compatibility fixture。
- 覆盖 Nexus 支持的 Conda 4.6+ 基线和 CI 选定的当前稳定 Conda 客户端。
- Conda 4.6 lane 使用 legacy `.tar.bz2`；`.conda` format 从支持该 container 的 Conda 4.7+ lane 开始验证。
   - 使用真实 `conda search`、`conda create`、`conda install` 验证 hosted/proxy/group、认证、平台选择、依赖和离线 cache。
   - 独立比较状态码、header、JSON 语义、压缩内容、package bytes、checksum、channel prefix 和 group member priority。

### 后续扩展和非目标

后续可以扩展：

- CEP 16 sharded repodata 的 hosted/group 原生生成，以及 proxy shard 的安全改写和缓存。
- 与官方 `conda-index` 等价的 `current_repodata.json` 裁剪优化。
- CEP 6 notices、CEP 42 channel relations、CEP 47 indexed timestamp 和管理端 channel policy。
- 已验证的 package signatures、供应链策略、quarantine、promotion 和跨仓库 copy。
- Micromamba、Mamba、Libmamba solver 的独立兼容矩阵。

第一阶段明确不做：

- 不实现 Anaconda.org 的账号、组织、Web UI、构建云或 token issuance API。
- 不把 Conda package 当成 Raw asset 后要求管理员手工运行 `conda index`。
- 不解析或执行 package 内脚本，也不在服务端解出 payload 文件供浏览。
- 不自行伪造 `signatures`；没有可验证的签名来源时保持字段缺失或为空。
- 不把完整 package、完整 repodata、shard 或解压内容存入 MySQL/PostgreSQL。
- 不依赖单副本临时目录、内存锁、内存 metadata map 或本地定时任务作为正确性真相。
- 不把 Nexus 的无效 channel、静默发布、metadata 500 或索引/下载不一致当作必须复制的兼容行为；这些差异必须在 compatibility fixture 中显式记录。

## URL 与路由设计

### 客户端配置

推荐把 group URL 作为一个 Conda channel：

```yaml
# ~/.condarc
channels:
  - https://repo.example.com/repository/conda-group
show_channel_urls: true
```

也可以在命令行覆盖默认 channel：

```bash
conda search --override-channels \
  -c https://repo.example.com/repository/conda-group zlib

conda create --override-channels \
  -c https://repo.example.com/repository/conda-group \
  -n conda-smoke zlib
```

若仓库内使用嵌套 channel，channel URL 包含该前缀：

```text
https://repo.example.com/repository/conda-hosted/team-a/release
```

### Route contract

`{channel}` 表示仓库根下可为空的 channel path，`{subdir}` 是 `noarch` 或 Conda 平台 subdir：

| 请求 | Hosted | Proxy | Group |
| --- | --- | --- | --- |
| `GET/HEAD /repository/{repo}/{channel}/{subdir}/repodata.json` | 生成快照 | 回源/缓存/改写 | 聚合快照 |
| `GET/HEAD .../repodata.json.bz2` | 与 JSON 同 revision | 回源或本地重编码 | 与聚合 JSON 同 revision |
| `GET/HEAD .../repodata.json.zst` | 与 JSON 同 revision | 回源或本地重编码 | 与聚合 JSON 同 revision |
| `GET/HEAD .../current_repodata.json[.bz2|.zst]` | 后续优化；缺失时允许客户端回退 | 安全透传/缓存 | 后续聚合优化 |
| `GET/HEAD .../repodata_shards.msgpack.zst` | 后续生成 | 安全缓存/改写 | 后续聚合 |
| `GET/HEAD .../{package}.tar.bz2` | 读取 package | 校验后缓存 | 按 binding 读取 |
| `GET/HEAD .../{package}.conda` | 读取 package | 校验后缓存 | 按 binding 读取 |
| `PUT .../{package}.tar.bz2` | 发布 | 拒绝 | 拒绝 |
| `PUT .../{package}.conda` | 发布 | 拒绝 | 拒绝 |
| `GET/HEAD /repository/{repo}/{channel}/channeldata.json` | 生成快照 | 回源/缓存/改写 | 聚合快照 |
| `GET/HEAD /repository/{repo}/{channel}/notices.json` | 可选 | 可选缓存 | 可选聚合 |

表中 `...` 代表 `/repository/{repo}/{channel}/{subdir}`。实现必须同时覆盖 bare repository/channel URL 与尾部 `/`，但不能用浏览器 HTML 页面替代协议 metadata。

### Path 规范化

- HTTP path 只 percent-decode 一次；拒绝空段、`.`、`..`、反斜杠、控制字符、NUL 和编码后的路径穿越。
- 仓库根 channel 使用内部稳定 key，不把 `_root` 等内部占位暴露到 URL。
- Channel path、subdir 和 filename 共同参与 asset path；query string、host 和 credential 不参与 identity。
- 不对合法 package filename 做大小写折叠，也不通过连字符拆分 coordinate。
- Subdir 必须是 `noarch` 或符合 Conda channel identifier 规范的平台 subdir；schema v2 上传 metadata 中的 `subdir` 必须与 URL 一致，legacy v1 缺失时只允许按受测兼容规则从 URL 派生。
- 已知 channel root 必须能读取 `noarch/repodata.json`。没有 noarch package 时返回合法空索引，而不是 HTML `404`。
- Package response 保持原始 bytes；metadata 的 JSON、BZ2、ZSTD 变体来自同一个 canonical JSON snapshot。
- `HEAD` 与 `GET` 返回一致的状态和实体 header；Range 只用于 package/blob，不对动态 JSON 做不稳定的临时切片。

## 数据模型落地

### Component 与 Asset

优先复用通用 component/asset/blob 模型：

- `component.format = CONDA`。
- `component.kind = conda-package`。
- `component.namespace = {canonical-channel-key}/{subdir}`。
- `component.name = info/index.json.name`。
- `component.version = info/index.json.version`。
- `component.attributes` 保存有界的 build、build number、depends、constrains、license、timestamp、track features、features 和 noarch 类型投影。
- `coordinate_hash = sha256("conda-package", channelKey, subdir, name, version, build)`；repository 已由数据库唯一约束的 `repository_id` 维度隔离，不重复混入 hash。

同一 coordinate 可以有 `.tar.bz2` 和 `.conda` 两个 package asset；asset path、archive format、MD5、SHA-1、SHA-256、size、filename 和原始 package record 分开保存。唯一约束至少覆盖：

- `(repository_id, coordinate_hash)` 的 component 唯一性。
- `(repository_id, normalized_asset_path)` 的 asset 唯一性。
- `(repository_id, channel_key, subdir, filename)` 的 active package record 唯一性。

Filename 与 `info/index.json` 不一致、同一路径指向另一 coordinate、或同一 archive format 出现不同 bytes 时，按 hosted write policy 明确拒绝或原子替换，不能静默成功。

### Conda 专用投影

通用模型之外，需要双数据库 migration 增加有界协议投影；具体版本号在实现分支按当时最新 Flyway 序列分配：

- `conda_package_record`：package asset、channel/subdir、name/version/build、archive format、repodata 字段、checksum、size、状态和 content revision。
- `conda_channel_revision`：repository/channel/subdir 的 committed revision、metadata blob 引用、ETag、生成状态和更新时间。
- `conda_proxy_metadata_state`：上游 metadata path、validator、cache deadline、negative state、blob 引用、解析状态和 fencing token。
- `conda_group_source_binding`：group、channel/subdir、package filename/coordinate、member、member revision、checksum 和 group revision。
- 复用或扩展通用 durable marker/lease 表，驱动 publish、delete、metadata rebuild、proxy fetch 和 cleanup。

完整 package、完整 repodata JSON、压缩 metadata 和 shard 只存 OSS/S3/File blob store。数据库可以保存查询所需的有界 package record，但不允许把无上限上游 JSON 直接塞进 JSON/TEXT 列。

### Revision 与可见状态

每个 channel/subdir 有单调递增 content revision：

1. Package bytes 先流式写 staging blob，并完成格式与 checksum 校验。
2. 数据库事务预留 coordinate/path，写入 pending package record 和 durable rebuild marker。
3. Worker 或同步 writer 基于 fenced revision 生成 canonical repodata 及压缩 blob。
4. 最后一个短事务同时激活 package record、切换 metadata blob pointer、递增 committed revision，并写 group invalidation marker。
5. 读请求始终读取一个 committed revision；旧 snapshot 在新 snapshot 提交前继续可用。

发布接口只有在新 package 与对应 metadata snapshot 都可读后才返回成功。失败或失去 fencing token 的 worker 不能覆盖更新 revision；staging 和过期 snapshot 由可接管的有界 cleanup 回收。

## Hosted 发布与索引流程

### Package 导入

Hosted raw upload 路径与 Nexus 一致：

```bash
curl --fail --user "$CONDA_USER:$CONDA_PASSWORD" \
  --upload-file ./acme_tools-1.2.3-py_0.conda \
  https://repo.example.com/repository/conda-hosted/team-a/linux-64/acme_tools-1.2.3-py_0.conda
```

Admin/UI 和 Components API 需要显式选择 channel 与 subdir，随后调用相同 importer。Importer 流程：

1. 验证 repository online、hosted recipe、权限、write policy、channel/subdir/path 和上传字节上限。
2. 流式写 staging blob，同时计算 MD5、SHA-1、SHA-256 和 size；不把完整包读入 JVM heap。
3. `.tar.bz2` 只按需流式读取 `info/index.json` 等受支持的 `info/` entry；`.conda` 先验证 ZIP container、`metadata.json`、唯一的 `info-*.tar.zst` 与 `pkg-*.tar.zst`，再有界读取 info tar。
4. 校验 archive entry 数量、声明/实际解压大小、压缩比、路径、link、重复 entry 和嵌套 container 限制。
5. 从 `info/index.json` 读取 name、version、build、build number、subdir、depends 等字段，并与 filename、URL subdir 和 channel policy 对照；legacy v1 的缺失字段只按明确 compatibility profile 派生。
6. 在共享 coordinate lease 内复查 active record，按 write policy 决定 create、conflict 或 replace。
7. 写 pending record、生成新 metadata snapshot，并按前述 revision 流程原子发布。
8. 返回 Nexus compatibility fixture 固定的状态与 header；初始目标为成功 `PUT` 返回 `201`。

Channel path 是 coordinate 的一部分。同一 bytes 上传到 `team-a/linux-64` 与 `team-b/linux-64` 会创建两个独立 publication；任何 dedup 只允许复用底层 content-addressed blob，不能合并 publication 或 metadata。

### Repodata 生成

每个 `(repository, channel, subdir)` 生成一个 canonical `repodata.json`：

- `info.subdir` 等于当前 subdir，`repodata_version` 使用客户端兼容值。
- `.tar.bz2` record 放入 `packages`，`.conda` record 放入 `packages.conda`。
- Record key 是 package filename；MD5、SHA-256 和 size 来自最终 archive bytes。
- `removed` 只包含已从 active maps 移除且仍处于 tombstone 保留期的 filename，不得同时出现在 active map。
- 未经验证的上游/用户 signature 不进入 `signatures`；若未来支持签名，必须保持 filename 与 package hash 绑定。
- JSON key 与 package record 采用稳定排序，时间字段来自持久化 publish time，保证多副本生成相同 bytes 和 ETag。

同一 canonical JSON bytes 生成 BZ2 和 ZSTD 变体。压缩结果作为 metadata asset 写入 blob store，并与 revision 绑定；请求线程不能每次临时压缩整个索引。

根 channel 和每个已知嵌套 channel 都生成 `channeldata.json`。字段选择、latest version 和 subdirs 必须遵循 CEP 38 与 Conda 排序规则；不能复制 Nexus 3.94 黑盒中缺少 `subdirs` 或嵌套 channel 返回空 packages 的结果。

已知 channel 若没有 noarch package，仍生成最小合法 `noarch/repodata.json` 及压缩变体。删除最后一个 package 后 channel 是否保留，由显式 channel lifecycle 管理；在 channel 仍存在期间不能退化为 HTML `404`。

`current_repodata.json` 只有在裁剪算法经官方工具/客户端 fixture 验证后才生成。未实现时返回 reference 固定的 missing 状态，让客户端回退到完整 repodata，不能返回内容不完整却状态为 `200` 的伪索引。

### 删除与替换

- 删除 package 映射 `DELETE`，写 tombstone、生成新 metadata snapshot，再切换 committed revision。
- Package 是否在 tombstone 期间允许 direct download、保留多久，以及重复发布是否清除 tombstone，必须由 Nexus black-box 与产品 retention policy 共同固定。
- Disable Redeploy 下，同一路径或同一 coordinate/archive format 的第二次上传返回 conflict；允许 redeploy 时也必须把 blob、record 和 metadata 作为一个新 revision 原子替换。
- 删除 channel/subdir 是管理操作，必须先枚举影响、支持 dry-run，并用 durable job 有界处理；不能在一个 HTTP 事务中扫描和删除整个 channel。

## Proxy 缓存流程

Proxy remote URL 表示上游 channel root，例如 `https://conda.anaconda.org/conda-forge/`。本地 `/repository/conda-proxy/linux-64/...` 映射到该 root 的 `linux-64/...`，不能把本地 repository 名或任意客户端 Host 拼入上游 URL。

Metadata 请求：

1. 规范化请求为 channel/subdir/metadata variant，并检查本地 committed cache。
2. Fresh 时直接返回本地重写后的 snapshot；命中共享 negative cache 时返回缓存状态。
3. Stale 时获取数据库 revalidation lease，携带 ETag/Last-Modified 向上游请求；其它副本等候相同 revision。
4. 上游 `304` 只刷新 verified/cache time；`200` 以流式、有大小/深度限制的 parser 校验 schema、subdir、filename、record 和 checksum。
5. 保存完整原始 metadata blob及有界 record 投影，删除或改写不安全的 `base_url`、shard base URL、channel relation 和绝对 package URL。
6. 生成本地 canonical JSON/压缩 snapshot，提交 validator、cache deadline 和 revision；等待者读取同一结果。
7. 上游 `404/410` 写短 TTL negative cache；认证失败、限流和 5xx 按 auto-block/stale policy 处理，不能伪装成 package missing。

Package 请求：

1. 在同一 channel/subdir 的已验证 package record 中解析 filename，取得 archive format、expected MD5/SHA-256/size。
2. 若 checksum 对应 blob 已缓存且 asset binding 有效，直接返回。
3. 否则按 `(repository, channel, subdir, filename, checksum)` 获取 fenced fetch lease，流式回源到 staging blob。
4. Redirect 每一跳都通过 `OutboundRequestPolicy`；跨 origin 删除 Authorization、cookie 和其它 secret-bearing header。
5. 边下载边计算 checksum/size，与 repodata 不一致时删除 staging、记录 integrity failure 并 fail closed。
6. 成功后原子提交 asset/blob binding；等待者只读取已提交结果。

客户端直接请求一个尚未缓存且不在已验证 repodata 中的 package 时，proxy 应先 revalidate 对应 subdir metadata。官方 channel 未发布的任意文件不应无校验地成为持久 cache。

Metadata TTL 与 content TTL 分离：repodata 可以分钟级 revalidate，已按 hash 固定的 package blob 可以长期复用。Remote 配置/auth revision 变化后立即失效 metadata/negative state，但 content-addressed blob 只有在没有引用并满足 retention 时才清理。

Stale-if-error 必须由管理员显式启用并设最大窗口。只允许返回最近一次校验成功的完整 snapshot/package；从未成功验证的响应、checksum mismatch、显式权限失败或 remote identity 改变不能 stale serve。

## Group 聚合流程

Group 以调用者可读的有序成员为输入，对每个 channel/subdir 生成统一视图：

1. 读取成员 config revision 和各成员 committed content revision。
2. 按顺序读取成员 `packages`、`packages.conda` 与 tombstone 投影。
3. 同一 map 内相同 filename 第一成员优先，并记录 member、member revision、checksum、size 和 coordinate。
4. `.tar.bz2` 与 `.conda` map 分开合并；相同 coordinate 的两种 archive 可以分别来自不同成员，但每个 filename 的 record 与 bytes 必须固定到同一成员。
5. 生成 group canonical repodata、压缩变体、ETag 和 source binding，并以一个 group revision 原子提交。

Package GET/HEAD 先读取 source binding，再从绑定成员取 bytes。即使客户端没有先请求 repodata，也必须运行与聚合器相同的选择算法并保存/验证 binding，不能另做一次简单的“逐成员第一个 200”搜索。

成员中较高优先级的 active package 覆盖低优先级同名 package。`removed` 只有在最终 group 视图中确实没有 active record 时才输出；高优先级成员的 tombstone 默认不能遮蔽低优先级仍有效的 package，除非 Nexus/Conda compatibility fixture 证明 tombstone 具有跨成员遮蔽语义。

`channeldata.json` 按 package name 聚合 subdirs 和有界字段。发生冲突时保持成员优先级，latest 选择使用 Conda version/build 排序，并保存来源；不能混合一个成员的 version 与另一个成员的 summary/license 形成不存在的记录。

Group response 中的 URL、ETag 和 conditional semantics 属于 group 自身。成员变化、proxy revalidation、hosted publish/delete 或 member reorder 递增共享水位并失效受影响的 channel/subdir；本地 TTL cache 只缓存已提交 group revision。

Nexus 3.94 黑盒已经出现过 group repodata `500`，以及嵌套 channel 的 repodata 宣告 package 但 group package 下载 `404`。兼容测试保留这些 reference observation，但 kkrepo 的验收标准是返回可消费的聚合索引，并保证每个被宣告 package 都能通过同一个 group URL 下载并通过 checksum。

## 权限与认证

权限映射：

| Conda 操作 | kkrepo 权限 |
| --- | --- |
| repodata/channeldata/notices/shard `GET`/`HEAD` | `READ` |
| package `GET`/`HEAD` | `READ` |
| hosted package 首次 `PUT`/UI upload | `ADD` |
| hosted package redeploy | `EDIT`，且 write policy 允许 |
| package/channel 管理删除 | `DELETE` |
| proxy/group upload | 拒绝写入 |

认证规则：

- 支持 HTTP Basic 和 kkrepo 现有 token；认证完成后仍走 repository privilege，不因协议 route 绕过授权。
- 未提供 credential 且仓库允许匿名读取时可以匿名；提供了无效/过期/禁用 credential 时返回 `401`，不回落到 anonymous。
- `WWW-Authenticate`、`404`/`403` 的可见性行为由通用安全策略和 Nexus fixture 固定，不能泄露调用者无权读取的 channel/package 是否存在。
- Proxy remote credential 只加入允许的 upstream origin。Redirect、更换 remote、日志、异常、audit 和 debug dump 都不得暴露 secret。
- `.condarc`、`.netrc` 和环境变量是客户端凭据载体，不是服务端新建 token 协议的理由；文档优先推荐 scoped token，而不是在 URL 中嵌入用户名密码。

## 多副本与缓存语义

- Repository 配置、package record、asset/blob 引用、channel revision、metadata pointer、proxy validator/negative state、group source binding、lease、marker 和迁移进度以 MySQL/PostgreSQL 与 OSS/S3 为真相。
- 本地 cache 只保存可按 revision 重建的 metadata/binding/permission 热数据，必须有 TTL 或版本水位失效条件；缓存丢失不能改变结果。
- Hosted publish/delete、proxy fetch/revalidate、group rebuild、migration 和 cleanup 使用数据库 lease/fencing 或 durable marker queue。节点内锁只能减少本副本重复工作。
- 大 metadata 在 blob store 中物化；数据库只原子切换 blob pointer 和 committed revision，避免长 JSON 生成占用事务锁。
- Marker 消费者使用有界批次和可接管 claim；失去 lease 的 worker 不能提交。失败 marker 保留 error、retry count 和 next attempt，支持运维观察与重试。
- Staging package、孤立 metadata snapshot、过期 negative state 和无引用 blob 由 `FOR UPDATE SKIP LOCKED` 或等价方言能力分批清理，不能依赖原上传请求的 `finally`。
- Group source binding 包含 member config/content revision。任何副本发现 revision 不匹配时重新解析，不能继续按陈旧成员下载。
- 多副本故障测试至少覆盖并发上传同一 coordinate、并发 proxy miss、metadata rebuild 中途退出、group member 重排、数据库短暂故障和对象存储 promote 失败。

## Nexus 迁移设计

Conda repository migration 必须 version/shape gated：

- Repository definition 识别 `format=conda` 与 `conda-hosted`、`conda-proxy`、`conda-group` recipe，迁移名称、online、blob store、write policy、remote、TTL、negative cache、HTTP client/auth 和有序成员。
- 保留 repository 名称可以保持 `/repository/{repo}/...` channel URL 不变；重命名必须在报告中生成客户端配置 action。
- Hosted data 只迁移 `.tar.bz2`/`.conda` package binary。Nexus 生成的 repodata/channeldata 不作为源真相；目标端通过同一 importer 校验 package、恢复 channel/subdir/coordinate 并重建 metadata。
- 源 asset path 中的 channel prefix 必须保留。发现同一 coordinate 被 Nexus 静默折叠到其它 prefix、metadata 宣告但 package 缺失或 checksum 不一致时标记 `NEEDS_MANUAL_ACTION`，不能猜测目标路径。
- Proxy cache 默认不迁移。管理员显式选择时，只有已验证的 metadata、package checksum 与 asset shape 才能恢复，并继续保持 proxy cache 语义。
- Masked/missing remote secret 不写占位值；目标 proxy 保持 offline，并在报告中列出 manual action。
- Group 成员只在所有引用都能映射到目标 Conda repository 时创建；缺失成员或循环引用使该 definition 进入 manual action。
- Migration job 支持 dry-run、resume、checksum、幂等、分片 claim、失败报告和复跑。重复运行不能重复发布 package 或增加 blob 引用。

## 安全与资源限制

- 上传先限制 compressed bytes，再限制 entry 数、单 entry 大小、总声明/实际解压字节、压缩比、嵌套层数、JSON 大小/深度、字符串和集合数量。
- TAR/ZIP parser 拒绝绝对路径、`..`、反斜杠穿越、设备文件、FIFO、危险 symlink/hardlink、重复关键 entry 和多份 `info/index.json`。
- `.conda` container 只接受规范允许的成员；ZIP central directory 与实际 stream 必须一致，Zstandard 解压设置明确上限。
- Metadata parser 使用流式读取；不允许先把大型 repodata、channeldata、shard 或 package 解压到 heap。
- Name、version、build、subdir、channel path、filename、dependency、license 和 URL 都设置长度/数量限制；异常内容不能进入 metric label。
- Proxy remote、redirect、`base_url`、channel relation 和 shard URL 统一走 `OutboundRequestPolicy`，阻止 loopback、link-local、云 metadata service、DNS rebinding 和跨协议跳转。
- Upstream credential、client Authorization、signed URL、token 和 package 内可能包含的敏感 metadata 在日志、trace、audit detail、错误响应与 CI artifact 中脱敏。
- Package checksum/size 不一致、metadata subdir 不一致、同一路径 upstream drift 或 group binding checksum 不一致都 fail closed。

## Browse、管理和观测

Admin UI：

- Hosted：blob store、write policy、strict validation、upload/archive limit、channel lifecycle 和 tombstone retention。
- Proxy：remote URL/auth、metadata/content/negative TTL、stale window、auto-block、HTTP/SOCKS5 proxy 和 last validation error。
- Group：有序成员、循环校验、当前 config revision、待重建 channel 数和 binding 状态。
- 上传页要求选择 channel/subdir，显示解析出的 name/version/build、archive format、size、checksum、warning 和最终路径。

Browse/Search：

- Channel/subdir 层显示 package 数、两种 archive 数、metadata revision、更新时间和健康状态。
- Package 层显示 name、version、build、build number、depends、constrains、license、timestamp、size、MD5/SHA-256 和来源成员。
- 下载始终经过 repository route、权限、审计和 group binding，不暴露内部 blob key 或上游 credential URL。

建议指标：

- `conda_publish_total{result,format}`、`conda_publish_duration_seconds`。
- `conda_metadata_build_total{type,result}`、`conda_metadata_build_duration_seconds`、`conda_metadata_revision_lag`。
- `conda_proxy_request_total{kind,result}`、`conda_proxy_cache_total{kind,result}`、`conda_proxy_integrity_failure_total`。
- `conda_group_build_total{result}`、`conda_group_binding_miss_total`、`conda_group_source_drift_total`。
- `conda_archive_rejection_total{reason}`、`conda_marker_backlog`、`conda_staging_bytes`。

Repository、recipe、result、format 可以作为低基数 label；channel、subdir、package name/version/build、URL、token 和 user 不进入 metric label，只进入受控 audit/log field。

## 兼容性测试矩阵

### M0 Nexus 参考基线

在实现 controller/service 前，先把 Nexus reference 行为固化为黑盒 fixture。2026-08-07 对 Nexus 3.94 的本地预研观察包括：

- 根 channel 与嵌套 channel 的 package `PUT` 返回 `201`；proxy/group 对同一路径 `PUT` 返回 `404`。
- Hosted 会生成 `repodata.json`、`repodata.json.bz2` 和 `channeldata.json`，不会生成 `.zst`、`current_repodata.json` 或 shard。
- 空 hosted 不会自动返回 `noarch/repodata.json`；嵌套 channel 可以拥有独立 repodata。
- 同一 coordinate 跨 channel prefix 的第二次上传可能返回成功但不可见，属于需要避免的 reference quirk。
- Group 的 metadata 聚合和 package 下载必须分别探测；预研中出现过 metadata `500`，也出现过 metadata 宣告 package 但下载 `404`。

这些观察只能作为 fixture 种子。自动化测试仍需在 Nexus 3.92.x+ 和执行时当前稳定版本上重跑，记录 exact status、header、body、资产列表和版本差异。

### 自动化矩阵

| 场景 | Nexus 对照 | kkrepo 断言 |
| --- | --- | --- |
| 空根 channel / 空 noarch | 记录 reference | kkrepo 返回官方有效空 noarch 索引 |
| `.tar.bz2` 根 channel PUT | 状态、header、metadata | 可 search/install，checksum 一致 |
| `.conda` 嵌套 channel PUT | 状态、header、metadata | channel identity 独立、可 install |
| 同 coordinate 多 channel | 记录 Nexus quirk | 两个 publication 均可见 |
| 重复 PUT/write policy | 对照 Allow/Disable | conflict 或原子替换，无半状态 |
| repodata JSON/BZ2/ZSTD | 内容解压比较 | 同 revision、同语义、稳定 ETag |
| channeldata/noarch | schema 与缺失行为 | 符合 CEP、可被客户端消费 |
| GET/HEAD/Range/conditional | 状态与实体 header | package bytes 一致，无额外回源 |
| Proxy 首次/重复/离线读取 | remote 请求计数 | 校验 hash、cache 命中、stale 受控 |
| Proxy 404/401/429/5xx | 状态和 auto-block | negative/error 分类正确 |
| Group 同名冲突 | 成员优先级 | record 与 bytes 绑定同一成员 |
| Group member reorder/delete | revision/binding | 新请求观察新顺序，无陈旧下载 |
| Basic/token/anonymous | 认证挑战与权限 | 无效 credential 不匿名降级 |
| 多副本并发和故障接管 | N/A | 单次 publication/fetch、snapshot 原子 |
| Nexus hosted data migration | package 与生成 metadata | package hash 相同、metadata 重建 |

真实客户端 E2E 至少覆盖：

- Nexus 支持的 Conda 4.6+ 最低 lane，以及当前稳定 `conda`/libmamba solver lane。
- Linux CI 的 `linux-64` 与 `noarch`；能提供 runner 时扩展 `osx-arm64`、`osx-64`、`win-64`。
- `conda search --override-channels`、`conda create`、`conda install`、dependency solve、anonymous 和 authenticated channel。
- Hosted 两种 package format、proxy public channel、group 冲突优先级、嵌套 channel、离线 proxy cache 和 metadata revalidation。
- 通过请求计数证明多副本 miss 不形成回源风暴，并通过 kill/restart 证明 publish/rebuild 可接管。

## 实施顺序

1. M0：编写 Nexus Conda black-box fixture，固定三类 recipe、路由、上传、metadata、header、group priority 和错误行为。
2. M1：新增 format/recipe、`protocol-conda` 模块、路由骨架、双数据库 schema 和 repository definition API/UI。
3. M2：实现有界 `.tar.bz2`/`.conda` inspector、staging blob、coordinate/path 校验和 package download。
4. M3：实现 hosted revision writer、repodata/channeldata、BZ2/ZSTD、空 noarch 和 publish/delete 原子可见性。
5. M4：接入权限、Basic/token、Browse/Search、Components API/UI upload、HEAD/Range/conditional response。
6. M5：实现 proxy metadata/package cache、checksum 收口、TTL/negative/stale、remote auth、redirect 和 SSRF policy。
7. M6：实现 group 聚合、source binding、member revision invalidation 和 group conditional response。
8. M7：实现 Nexus repository definition 与 hosted data migration，再补显式选择的 proxy cache migration。
9. M8：补齐跨副本并发、worker 接管、对象存储/数据库故障注入、cleanup 和容量压测。
10. M9：在 fixture 验证后增加 `current_repodata` 与 CEP 16 shard，覆盖当前 Conda 客户端的现代 metadata fast path。
11. M10：完成真实 Conda 客户端矩阵、文档、示例配置、运维指标和兼容性报告。

## 验收标准

- Admin/API 可以创建 `conda-hosted`、`conda-proxy`、`conda-group`，配置与 Nexus recipe 迁移映射清晰。
- `.tar.bz2` 与 `.conda` 都能经同一 importer 安全发布，并被真实 Conda 客户端 search/create/install。
- 根 channel、嵌套 channel 和空 noarch 都返回合法 metadata；相同 coordinate 跨 channel 不互相覆盖。
- Repodata 中的 filename、record、checksum、size 与实际 package bytes 一致，JSON/BZ2/ZSTD 属于同一 revision。
- Proxy 可在多副本下合并回源、校验 package、正确处理 TTL/negative/stale/redirect，并在允许的离线窗口继续工作。
- Group 按成员顺序稳定聚合；索引中每个 package 都能通过 group URL 下载，bytes 来自绑定成员并通过 checksum。
- 显式无效认证不会降级匿名，上传/替换/删除权限与 write policy 生效，remote secret 不泄露。
- 任一副本在上传、回源或 metadata rebuild 中退出后，另一副本可以接管；客户端只观察旧或新完整 snapshot。
- Nexus migration 支持 dry-run/resume/checksum/idempotency，hosted package bytes 保持 hash，生成 metadata 在目标端重建。
- Archive bomb/path traversal、恶意 metadata、SSRF、cross-origin credential、checksum drift 和资源耗尽测试全部通过。
- `compat-test` 同时保存 Nexus reference 与 kkrepo 结果；所有故意优于 Nexus quirk 的差异都有测试名和设计说明。

## 参考资料

- [Conda channel identifiers](https://conda.org/learn/specifications/channels/channel-identifiers/)
- [Conda package specification](https://docs.conda.io/projects/conda-build/en/latest/resources/package-spec.html)
- [CEP 34: `.tar.bz2` package format](https://conda.org/learn/ceps/cep-0034/)
- [CEP 35: `.conda` package format](https://conda.org/learn/ceps/cep-0035/)
- [CEP 36: repodata](https://conda.org/learn/ceps/cep-0036/)
- [CEP 38: channeldata](https://conda.org/learn/ceps/cep-0038/)
- [CEP 16: sharded repodata](https://conda.org/learn/ceps/cep-0016/)
- [CEP 12: run exports](https://conda.org/learn/ceps/cep-0012/)
- [CEP 15: repodata base URL](https://conda.org/learn/ceps/cep-0015/)
- [CEP 6: channel notices](https://conda.org/learn/ceps/cep-0006/)
- [CEP 42: channel relations](https://conda.org/learn/ceps/cep-0042/)
- [CEP 47: indexed timestamp](https://conda.org/learn/ceps/cep-0047/)
- [Creating custom channels](https://docs.conda.io/projects/conda/en/stable/user-guide/tasks/create-custom-channels.html)
- [Generating channel indexes](https://docs.conda.io/projects/conda-build/en/latest/concepts/generating-index.html)
- [Sonatype Nexus Conda Repositories](https://help.sonatype.com/en/conda-repositories.html)
- [Create a Conda Repository](https://help.sonatype.com/en/create-a-conda-repository.html)
- [Configure Conda with Nexus](https://help.sonatype.com/en/configure-conda-with-nexus.html)
- [Conda CLI Usage with Nexus](https://help.sonatype.com/en/conda-cli-usage.html)
- [Nexus Repository 3.92.0 release notes](https://help.sonatype.com/en/sonatype-nexus-repository-3-92-0-release-notes.html)
