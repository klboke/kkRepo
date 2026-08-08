# APT / Debian 仓库开发设计说明

本文记录 kkrepo APT / Debian 仓库格式的开发设计。目标不是把 `.deb` 和 `dists/` 目录当作 Raw 文件树保存，而是在 Debian 官方仓库格式、APT 客户端安全模型和 Sonatype Nexus Repository APT 行为之间取兼容交集，并按 kkrepo 的关系数据库 + OSS/S3 + 多副本约束落地可托管、可代理、可迁移、可检索和可观测的 APT 仓库。

## 当前支持状态与落地结论

截至 2026-08-08，kkrepo 尚未实现 APT 仓库：代码中没有 `RepositoryFormat.APT`、`apt-hosted`、`apt-proxy` 或 `protocol-apt`，路线图此前只保留了 `APT / Debian` 占位项。

本设计完成后，路线图状态调整为“设计已完成、待实现”，并把 APT 提升为下一个仓库格式优先项。落地判断如下：

- APT 适合在 kkrepo 中落地。官方仓库格式、真实客户端和 Nexus reference 都可用于黑盒验证，协议风险可控。
- 总体改动量为中到大。难点不在 `.deb` 下载，而在签名 metadata 的原子发布、压缩索引的一致性、Proxy 的签名边界、密钥轮换以及多副本重建协调。
- 第一版只注册 `apt-hosted` 和 `apt-proxy`。当前 Nexus 官方兼容矩阵明确不支持 APT group；APT 客户端本身支持配置多个有序 source，因此不为追求形式统一而发明 `apt-group`。
- Hosted 以结构化仓库、二进制 `.deb`、签名 `Release` / `InRelease`、`Packages` 索引和 Components API 上传为核心。
- Proxy 同时覆盖透明透传和 Nexus 风格可选重签名，但两种模式使用不同的缓存与可信边界。
- Flat repository、source package、metadata snapshot、PDiff、Contents 和 Translation 按分阶段边界实现，不阻塞第一版真实 `apt update` / `apt install`。

## 调研基线

实现时按以下顺序确定行为：

1. Debian 官方仓库格式、Debian Policy 和 APT manpage 是协议真相。
2. Nexus Repository APT 文档、recipe 和真实 HTTP 行为是兼容性参考。
3. kkrepo 现有 hosted/proxy、索引重建、OpenPGP、组件上传、迁移和多副本设施是落地基础。

协议关键事实：

- APT source 的 archive root 对应 `/repository/<repo>/`；结构化仓库从 `dists/<distribution>/InRelease` 或 `Release` 开始发现索引。
- `Release` 列出相对路径、size 和 checksum；`InRelease` 是 clear-signed `Release`，`Release.gpg` 是 detached signature。
- 现代 APT 默认拒绝没有可信 `Release` 签名的仓库。Hosted 不能把“允许客户端关闭校验”当作正常配置方案。
- `Packages` 是 deb822 stanza 集合。每条记录至少包含 `Package`、`Version`、`Architecture`、`Filename`、`Size` 和可用于安全校验的 SHA-256。
- `.deb` 是有固定 member 顺序的 ar archive，必须读取 `debian-binary` 和 `control.tar.*`；不能通过文件名猜包名、版本或架构。
- Debian version 不是 SemVer，比较规则包含 epoch、upstream version、Debian revision 和特殊的 `~` 排序。
- `Acquire-By-Hash: yes` 允许客户端按 index digest 读取 immutable metadata，避免 `Release` 与压缩索引更新窗口产生 hash mismatch。
- Flat repository 不使用 `dists/` 层级，source 行形如 `deb <uri> <directory>/`，其 `Packages` / `Sources` 位于该 directory 下。

Nexus 兼容结论：

- Nexus 当前提供 `apt-hosted` 和 `apt-proxy`，不提供 `apt-group`。
- Hosted 创建时需要 distribution 和 OpenPGP signing key；Nexus 对 repository metadata 签名，不对 `.deb` package 本身签名。
- Hosted 支持仓库根 POST 上传和 UI 上传；Components API 使用单一 `apt.asset` multipart 字段。
- Proxy 支持 distribution、`Enforce Distribution`、flat mode，以及未配置 signing 时的 metadata passthrough 和配置 signing 后的 metadata re-signing。
- Proxy 在未配置 distribution 时可工作在 multi-distribution 模式；启用 enforce 但未配置 distribution 属于错误配置，kkrepo 应在保存配置时直接拒绝，而不是复制参考实现的 fail-open 行为。
- Nexus 提供 `/gpg.key`、metadata rebuild 和 metadata snapshot 等产品行为；第一版优先完成安全发布和真实客户端闭环，snapshot 放在后续阶段。

## 功能范围

### 第一阶段必须实现

1. APT hosted
   - 新增 `RepositoryFormat.APT` 和 `apt-hosted` recipe。
   - 新增独立 `protocol-apt` 模块，承载 path、deb822、Debian version、`.deb` control、Release 和 media type 契约。
   - 仓库配置至少包含 distribution、默认 component、允许的 architecture、write policy、OpenPGP signing key 和可选 `Valid-Until` 策略。
   - 支持 `.deb` 上传、下载、HEAD、删除、条件请求和 Range。
   - 支持 Nexus 仓库根 POST、Admin UI 上传和 Components API `apt.asset`，三种入口复用同一 importer。
   - 生成 `Packages`、`Packages.gz`、`Packages.bz2`、`Packages.xz`、`Release`、`InRelease`、`Release.gpg` 和 SHA-256 by-hash 路径。
   - 暴露 ASCII-armored public key 的 `/gpg.key`。
   - 支持 Browse、Search、Usage、cleanup policy、审计和安全扫描。

2. APT proxy passthrough
   - 新增 `apt-proxy` recipe，支持 remote URL、remote authentication、metadata/content TTL、negative cache、auto-block、redirect 和出站地址策略。
   - 支持单 distribution、多 distribution 和 enforce distribution。
   - 透明缓存上游 `InRelease`、`Release`、`Release.gpg`、Packages/Sources/Contents/Translation/PDiff/by-hash 文件与 package blob，不改写已签名字节。
   - 保持上游相对 `Filename` 语义，使客户端仍通过 `/repository/<proxy>/...` 回到同一 proxy。
   - 解析上游索引只用于 Browse/Search 投影；下载正确性仍由上游签名链、Release checksum 和 package checksum 决定。

3. APT proxy re-signing
   - 配置本地 signing key 后，为指定 distribution 或已观察到的多个 distribution 生成本地 metadata。
   - 本地 metadata 只声明已经完整缓存并校验的 package，不把尚未缓存的上游 package 写入本地索引。
   - 重签名快照沿用 hosted 的 immutable build + CAS publish 机制，不能在同一 Release 下混用来自不同 rebuild 的压缩索引。
   - 首次本地 metadata 尚未生成时的 passthrough、rebuild 中的响应以及 key rotation 行为必须先记录 Nexus 黑盒结果，再固定状态码。

4. 管理、迁移与兼容性
   - Admin UI 支持创建和编辑 hosted/proxy、key 导入与轮换、强制 rebuild、proxy mode 和 distribution policy。
   - Browse UI 展示 distribution、component、package、version、architecture、source package、section、priority、checksum 和来源。
   - Nexus definition 迁移识别 `apt-hosted` 和 `apt-proxy`；hosted data 只在 source version/content shape 经过验证后自动恢复。
   - 真实 APT 客户端覆盖 `update`、`download`、`install`、指定版本安装、认证、key rotation 和失败路径。
   - Nexus 黑盒对照覆盖 recipe、配置、上传、路径、metadata、签名、状态码、header、proxy 和删除行为。

### 后续扩展

- Hosted source package：`.dsc`、`.orig.tar.*`、`.debian.tar.*`、`.diff.gz` 和 `Sources` 生成。
- Flat hosted repository；第一版只要求 flat proxy passthrough。
- `Contents-*`、Translation 和 PDiff 的 hosted 生成。
- `.udeb` 与 `debian-installer/binary-*` 索引。
- Nexus 风格 metadata snapshot；启用前必须同时解决 snapshot metadata 与 package blob 的保留关系。
- Release 中 `NotAutomatic`、`ButAutomaticUpgrades`、`Signed-By`、Changelogs 和 Snapshots 等高级字段。
- 可选的外部 KMS/HSM/OpenPGP signer；初版使用 kkrepo 加密后的 repository signing key。

### 明确不实现

- 不新增 `apt-group` recipe。未来如确有产品需求，需要按独立非 Nexus 扩展重新设计可信合并、冲突优先级和重新签名，不能复用 Raw group 拼文件。
- 不执行 `.deb` 中的 maintainer script，也不展开 `data.tar.*` 到本地文件系统。
- 不把 `.deb`、压缩索引或签名大对象存进 MySQL。
- 不把节点本地临时目录、内存 map 或单机定时任务作为 metadata 发布真相。
- 不接受 unsigned hosted 作为正常运行模式。
- 不把远端 proxy signing key、Basic password 或本地 private signing key 写入公开 metadata、日志或审计明文。

## 模块与职责

| 模块 | 设计职责 |
| --- | --- |
| `core` | `APT` format、`apt-hosted` / `apt-proxy` recipe、共享上传和权限契约 |
| `protocol-apt` | path parser、deb822 codec、Debian version comparator、`.deb` control parser、Release model、media type |
| `persistence-jdbc` | APT DAO、package projection、suite revision、snapshot、signing key、proxy distribution state 和 lease |
| `persistence-mysql` / `persistence-postgresql` | 同号 APT schema migration 与双库索引/约束 |
| `server/apt` | hosted importer、metadata builder、OpenPGP signer、proxy passthrough/re-signing 和 rebuild worker |
| server 通用入口 | Controller、安全过滤、Components API、Browse/Search、cleanup、metrics 和 repository 生命周期 |
| `migration-nexus` | Nexus APT definition/content shape 探测、迁移计划、writer 和校验器 |
| `admin-ui` / `browse-ui` | recipe 配置、key 管理、上传、浏览、Usage 和 rebuild 状态 |
| `security-scan` | `.deb` package 候选识别、Syft/Grype 输入和 policy enforcement |
| `compat-test` | Nexus/kkrepo 黑盒 fixture、可控上游和真实 `apt` 客户端 E2E |

Controller 只负责 HTTP route、认证上下文和响应适配；deb822、签名、package parsing、metadata generation 和 proxy mode 不进入 Controller。

## URL 与路由设计

### 客户端配置

推荐使用 scoped keyring，不再把 `apt-key add` 作为 kkrepo 文档的默认方式：

```bash
curl --fail --user "$APT_USER:$APT_PASSWORD" \
  https://repo.example.com/repository/apt-hosted/gpg.key \
  | gpg --dearmor \
  | sudo tee /etc/apt/keyrings/kkrepo.gpg >/dev/null

echo "deb [signed-by=/etc/apt/keyrings/kkrepo.gpg] https://repo.example.com/repository/apt-hosted stable main" \
  | sudo tee /etc/apt/sources.list.d/kkrepo.list

sudo apt-get update
sudo apt-get install demo
```

私有读取凭据放进 `/etc/apt/auth.conf.d/kkrepo.conf`，不要把 password 写入 world-readable source 文件：

```text
machine repo.example.com/repository/apt-hosted/
login ci-reader
password <token-or-password>
```

### 结构化仓库路径

| Path | Hosted | Proxy passthrough | Proxy re-signing |
| --- | --- | --- | --- |
| `/gpg.key` | 当前 repository public key | 按普通上游 asset 透传；不存在时 404，精确 Nexus 行为由 M0 固定 | 当前本地 public key |
| `/dists/<dist>/InRelease` | 当前已发布 clear-signed snapshot | 原样缓存上游 | 当前本地 clear-signed snapshot |
| `/dists/<dist>/Release` | 当前已发布 unsigned Release bytes | 原样缓存上游 | 当前本地 Release bytes |
| `/dists/<dist>/Release.gpg` | 对同一 Release 的 detached signature | 原样缓存上游 | 对同一 Release 的本地 signature |
| `/dists/<dist>/<component>/binary-<arch>/Packages` | 从已发布 package projection 生成 | 原样缓存上游 | 从已校验 cache projection 生成 |
| `Packages.gz/.bz2/.xz` | 同一 Packages bytes 的压缩表示 | 原样缓存上游 | 同一 Packages bytes 的压缩表示 |
| `.../by-hash/SHA256/<digest>` | immutable metadata blob | 原样缓存上游 | immutable metadata blob |
| `/pool/<prefix>/<package>/<file>.deb` | 读取 hosted blob | 按同一路径代理并缓存 | 读取已校验 proxy cache |
| `/snapshots/<id>/...` | 后续阶段 | 后续阶段 | 后续阶段 |

`pool` 的 prefix、filename 中 epoch 处理、architecture `all` 的索引归属，以及 Nexus 是否生成额外压缩表示，必须在 M0 对 Nexus reference 上传 fixture 后固化；实现不能靠示例文件名反推 package identity。

### Flat proxy 路径

Flat proxy 把 remote URL 视为 archive root，按客户端请求原样拼接受校验的相对路径：

```text
deb https://repo.example.com/repository/apt-flat-proxy stable/
```

对应读取：

```text
/repository/apt-flat-proxy/stable/InRelease
/repository/apt-flat-proxy/stable/Packages.xz
/repository/apt-flat-proxy/stable/<package-file>.deb
```

Flat mode 只改变路径解释，不能放宽 traversal、encoded separator、redirect 或 remote host 校验。

### 上传入口

需要兼容三类 hosted 上传：

```bash
# Nexus repository-root upload shape；精确 content type/body 由 M0 fixture 固定
curl --fail --user "$USER:$PASSWORD" \
  -H "Content-Type: multipart/form-data" \
  --data-binary @demo_1.0.0_amd64.deb \
  https://repo.example.com/repository/apt-hosted/

# Nexus-compatible Components API
curl --fail --user "$USER:$PASSWORD" \
  -F apt.asset=@demo_1.0.0_amd64.deb \
  "https://repo.example.com/service/rest/v1/components?repository=apt-hosted"
```

Admin UI upload 也调用同一 importer。Components API 成功保持 Nexus 的 `204 No Content`；repository-root POST 的成功码、错误 body 和重复上传行为由黑盒 fixture 固定。

### Path 与 HTTP 约束

- Path 只 percent-decode 一次，拒绝编码后的 `/`、反斜杠、点段、空关键 segment、控制字符、NUL 和超长字段。
- distribution 和 component 不做无依据的小写转换；保存配置时验证 Debian path-safe 字符集。
- GET/HEAD 必须共享相同的 status、Content-Length、ETag、Last-Modified 和 cache policy，HEAD 不读取完整 blob。
- `.deb` 支持 Range、`If-None-Match` 和 `If-Modified-Since`；生成型 metadata 至少支持稳定 ETag 与条件请求。
- Hosted 的 PUT 到任意 `dists/` metadata path 默认拒绝，避免用户绕过生成器写出签名不一致的仓库。
- Group recipe 不存在；对管理 API 提交 `apt-group` 返回 recipe validation error，而不是创建 Raw group。

## `.deb` 解析与规范化

上传流先写受限临时文件并计算 MD5、SHA-1、SHA-256 和 size，再由 `AptDebPackageInspector` 有界解析：

1. 校验 ar magic、header、member size、padding 和总字节边界。
2. 校验首个必需 member `debian-binary`，major format 必须为 `2`；只忽略规范允许的 `_` 前缀扩展 member。
3. 按顺序定位唯一的 `control.tar`、`control.tar.gz`、`control.tar.xz` 或 `control.tar.zst`，以及随后的唯一 `data.tar.*`；未知压缩、重复必需 member 和顺序异常 fail closed。
4. 只展开 control archive；拒绝绝对路径、`..`、特殊设备、越界 link、重复 `control`、超量 entry、超大展开字节和超时。
5. 按 Debian control 规则解析 UTF-8 deb822；保留 continuation line 语义和稳定字段顺序。
6. 强制校验 `Package`、`Version`、`Architecture`、`Maintainer` 和 `Description`，并对 Section、Priority、Source、Depends、Provides、Multi-Arch 等已知字段做语法检查。
7. 不展开或执行 `data.tar.*`；只校验其 ar member 边界，安全扫描由独立受限流程消费完整 `.deb`。

Package coordinate 使用：

```text
(repository_id, distribution, component, package_name, version, architecture)
```

通用 component 使用：

- namespace：`<distribution>/<component>`
- name：control 中的 `Package`
- version：完整 Debian `Version`，包括 epoch
- asset：每个 architecture 对应的 canonical `.deb` path

Debian version comparator 必须按官方 algorithm 实现并做 differential test，不能复用 Maven `ComparableVersion` 或 SemVer。Package filename 不包含 epoch 的规则、prefix 规则和 Nexus canonical path 由 reference fixture 固定；任意 filename 都必须与 control identity 反向校验。

## 数据模型

大 blob 继续只存 OSS/S3；MySQL/PostgreSQL 保存可查询投影、发布状态、引用和协调信息。

### 通用表复用

- `repository` / `repository_member`：APT repository 定义；第一版没有 APT member。
- `component`：distribution/component 下的 package name + version。
- `asset` / `asset_blob` / `blob`：`.deb`、public key 和 immutable/generated metadata。
- `browse_node`、search projection、audit、cleanup 和 scan outbox：复用通用能力。
- `repository_index_rebuild_marker`：扩展 `APT_METADATA` kind，用于 crash recovery 和管理员 rebuild。

### APT 专用表

建议新增：

1. `apt_repository_config`
   - `repository_id` unique FK。
   - `distribution`、`default_component`、`architectures`、`flat_mode`、`enforce_distribution`。
   - `proxy_mode`：`PASSTHROUGH` 或 `RESIGN`。
   - `valid_until_seconds` nullable。
   - `active_signing_key_revision` nullable；hosted 必填，passthrough proxy 可为空。

2. `apt_package`
   - repository/distribution/component/package/version/architecture identity 与 immutable generation。
   - component id、asset id、blob id、canonical path、size、MD5、SHA-1、SHA-256。
   - source package、section、priority、maintainer、description、dependency fields、Multi-Arch。
   - canonical Packages stanza 或生成 stanza 所需的受限字段投影。
   - `introduced_revision` 与 nullable `retired_revision`；target revision 只读取在该 revision 可见的 generation。
   - state：`STAGED`、`PUBLISHED`、`RETIRED`、`FAILED`。
   - unique `(repository_id, distribution, component, package_name, version, architecture, generation)`。

3. `apt_package_head`
   - unique `(repository_id, distribution, component, package_name, version, architecture)`。
   - current package generation、last mutation revision 与 tombstone 状态。
   - coordinate lease 下更新，保证任一时刻只有一个期望中的当前 generation。

4. `apt_suite_revision`
   - `(repository_id, distribution)` unique。
   - desired revision、published revision、published snapshot id、config revision、updated_at。
   - 任何 package mutation 或 signing config mutation 递增 desired revision；mutation 同时记录其 revision。

5. `apt_metadata_snapshot`
   - repository/distribution/revision、state、signing key revision、Release digest、Date、可选 Valid-Until。
   - 记录 immutable metadata asset id 集合的 manifest blob 或规范化子表。
   - state：`BUILDING`、`READY`、`PUBLISHED`、`SUPERSEDED`、`FAILED`。

6. `apt_signing_key`
   - repository id、revision、fingerprint、key id、public armor、encrypted private material、active、created_at、retired_at。
   - private key 与 passphrase 使用 `SecretCipher(EncryptionSecrets.credentialSecret())` 加密；API 永不回显 private material。

7. `apt_proxy_distribution_state`
   - repository/distribution、last observed Release identity、validator、expiry、projection revision、re-sign status 和 last error。
   - multi-distribution proxy 通过此表恢复已观察 distribution，不能依赖单 JVM set。

8. `apt_coordinate_lease`
   - lease key、owner、fencing token、expires_at、attempt count、updated_at。
   - 用于 package coordinate mutation、suite snapshot build 和 key rotation。

所有 identity 字段都使用显式长度上限与 canonical form。MySQL 和 PostgreSQL migration 使用相同版本号并由 persistence contract 覆盖 unique constraint、CAS publish、lease takeover、分页读取和清理。

## Hosted 上传与原子发布

APT 的正确性单位不是单个 `Packages.gz`，而是一个由同一 signed Release 引用的完整 metadata snapshot。上传流程：

1. Controller 完成认证、recipe、online、write policy 和 `ADD`/`EDIT` 权限检查。
2. 上传流写入受限临时文件，流式计算 checksum；`.deb` inspector 提取并校验 control identity。
3. 把 blob 写入隐藏 staging asset；长时间 OSS/S3 上传期间不持有数据库事务。
4. 获取 package coordinate lease，在短事务内复查 write policy、重复 identity、checksum 和目标 revision。
5. 创建 immutable package generation、更新 `apt_package_head`、为被替换 generation 写 `retired_revision`，递增 suite desired revision，并 enqueue scoped `APT_METADATA` marker。
6. 当前请求尝试获取 suite snapshot lease。获得者捕获 target desired revision，在事务外只读取该 revision 可见的 generation 并构建 immutable snapshot；更高 revision 的并发 mutation 留给下一轮。
7. Builder 从数据库 cursor 流式生成每个 architecture 的 Packages spool，再生成压缩表示、Release、InRelease 和 Release.gpg，写入 `.apt/generated/<distribution>/<revision>/...` 隐藏资产。
8. Builder 在短事务内用 `(target_revision, fencing_token, config_revision, signing_key_revision)` CAS 发布 snapshot pointer，并把该 revision 已纳入的 package generation 切换为 `PUBLISHED`。
9. 对外 route 只读取 published snapshot manifest 引用的 package generation。任一构建失败时继续提供上一个完整 snapshot，不暴露半套新 metadata。
10. 提交后解除 staging 引用；旧 snapshot 和 tombstoned package blob 按 by-hash/client grace period 延迟清理。

如果请求在发布提交后断连，客户端重试同一 coordinate + checksum 应返回幂等成功；同一 coordinate + 不同 checksum 按 write policy 拒绝或替换，并生成新 revision。

管理员“Rebuild APT metadata”和 key rotation 使用同一 pipeline，不允许维护第二套生成逻辑。Worker claim、lease 和 snapshot pointer 都持久化，使任意副本都能接管崩溃任务。

## Metadata 生成

### Packages

- 每个 `(distribution, component, architecture)` 生成一个稳定的未压缩 Packages snapshot。
- `Architecture: all` package 如何进入各 architecture index 以 Nexus reference 和真实 APT 客户端结果为准，避免重复或漏包。
- stanza 从已校验 control fields 生成，再追加 canonical `Filename`、`Size`、`MD5sum`、`SHA1` 和 `SHA256`。
- `Filename` 必须是 archive root 相对路径，不含 `.`、`..`、encoded separator 或 query。
- 稳定排序至少以 package name、Debian version、architecture 和 filename 为键；具体同版本排序通过 fixture 固定。
- 生成器使用 JDBC cursor + 临时 spool，不把完整仓库的 stanza list、四种压缩表示和签名字节同时放进 heap。
- GZIP/BZIP2/XZ 参数固定，保证同 revision 重建产生相同 Packages 表示；timestamp 字段不得使用请求时间。

### Release 与签名

Release 至少写入：

- Origin、Label、Suite 和/或 Codename。
- Date；从 snapshot persisted timestamp 生成。
- Architectures、Components。
- `Acquire-By-Hash: yes`。
- SHA256；为 Nexus/旧客户端兼容可同时生成 MD5Sum 和 SHA1，但安全判断不依赖弱 hash。
- 可选 Valid-Until；关闭时省略而不是写无限未来时间。

每个 checksum 行的 size 和 digest 必须基于已经写完且 fsync/close 的最终 bytes。生成顺序为 Packages -> compressed indices -> Release -> signatures；只有所有 blob 写入并复读校验成功后才能发布 pointer。

OpenPGP signer：

- 复用 Bouncy Castle provider 和 kkrepo credential encryption，不调用外部 `gpg` 进程作为运行时正确性依赖。
- `InRelease` 使用 cleartext signature，`Release.gpg` 对完全相同的 Release bytes 生成 detached signature。
- signature hash 至少为 SHA-256；key algorithm、过期时间和 subkey 选择在导入时校验。
- `/gpg.key` 返回 active public key。轮换窗口可同时发布旧/新 public key bundle，但每个 snapshot 只记录实际 signing key revision。
- key rotation 先保存并验证新 key，再触发全量 rebuild；新 snapshot 发布前旧 snapshot 保持可用。

### By-hash 与保留

- 每个 index 表示同时写 canonical path 和 `by-hash/SHA256/<digest>` identity；route 可以通过 snapshot manifest 映射到同一 blob，避免重复存储。
- 当前版本必须保留，至少再保留两个历史版本；实际保留时间还要覆盖 metadata cache age、最大客户端离线窗口和 cleanup policy。
- canonical path 的 ETag 基于当前 blob digest；by-hash path 使用 immutable cache headers。
- 清理 snapshot 前先证明没有 published pointer、snapshot retention、迁移任务或 blob reference 依赖。

## 删除与 write policy

- 删除 component/package 先获取 coordinate/suite lease，在事务中写 tombstone、递增 desired revision 并 enqueue rebuild。
- 新 snapshot 成功发布后，该 package 才从 Packages 消失；旧 snapshot 和 package blob 在 grace period 内仍可服务已有客户端。
- 删除失败或 rebuild 失败时继续提供旧 snapshot，不允许 Packages 已删但 Release 仍引用旧 checksum，或反向出现新 Packages 未签名的状态。
- `ALLOW_ONCE` 对已存在 coordinate 拒绝；`ALLOW` 允许相同 identity 替换，但必须经过完整 snapshot pipeline；`DENY` 拒绝所有写入。
- Browse/API 删除与 repository content DELETE 复用同一路径，不能直接删 asset 绕过 APT projection。

## Proxy 设计

### Passthrough mode

Passthrough 保留上游信任链，不生成本地签名：

1. 根据 distribution policy 校验 path，再用 `RemoteUrlBuilder` 构造 upstream URL。
2. `InRelease`、`Release`、signature 和 index 使用 metadata TTL；`.deb` 使用 content TTL。
3. 上游响应按 validator 重新验证并写共享 blob，远端凭据不进入 cache key 或响应 metadata。
4. 已签名/校验覆盖的 bytes 原样返回，不改写 Release、Packages stanza、compression 或 signature。
5. 对 by-hash path 使用长 immutable cache；对 canonical metadata path 使用短 TTL 和条件请求。
6. `.deb` 下载在首次缓存时校验已知 Packages checksum/size；如果尚无可信投影，先按 RawProxy 安全边界缓存，客户端仍会根据 Packages 校验，后台投影不能声称已验证。
7. 上游 404/410 使用短 negative TTL；auth error、429、5xx 和 timeout 不进入 not-found cache。

Proxy metadata projection 以 Release identity 为 revision。只有 index bytes 与 Release 中的强 hash/size 一致时才解析并写入 Browse/Search；若配置了 upstream public key，再额外验证 Release signature。未知字段保留在 bounded raw stanza 中，解析失败不影响透明下载，但不会生成可信 component 投影。

### Re-signing mode

Re-signing 是新的本地 archive，不再是透明 mirror：

- 只索引已经缓存、checksum 已验证且 blob 可读的 package。
- 通过本地 Release 和 signing key 建立新的信任边界；UI 必须明确展示“本地重签名，不代表完整上游镜像”。
- single-distribution 模式只生成配置的 distribution；multi-distribution 模式把已观察 distribution 持久化到数据库。
- 上游 package 从缓存淘汰前必须先发布不再引用它的新 snapshot；否则本地签名会引用不可下载的 Filename。
- rebuild 失败继续提供上一个本地 snapshot；不能回退到同一路径下签名身份不同的上游 metadata，除非黑盒证明 Nexus 客户端流程需要且 UI 已明确配置该策略。
- `Enforce Distribution=true` 且 distribution 为空在保存配置时返回 400，避免不安全的 fail-open。

### Stale 与 auto-block

- Proxy block state、last failure、retry-after 和 metadata revision 存 MySQL；节点本地 cache 只是可丢失热缓存。
- 上游不可用时可以提供仍在签名/Valid-Until 有效期内的 cached metadata；过期后是否允许 stale 由显式策略决定，并在 UI/metric 中标注。
- 不修改上游 signed metadata 的 Date 或 Valid-Until 来延长缓存寿命。
- Redirect 每一跳都经过 `OutboundRequestPolicy`；禁止访问 loopback、link-local、metadata service、私网或配置未允许的目标。

## 为什么第一版不做 APT group

APT group 不是按路径 first-hit 就能正确实现：

- 每个成员有独立 Release signature、distribution、component、architecture 和时间有效性。
- 合并 Packages 后，原成员签名全部失效，group 必须生成并签署新的 Release。
- 同名同版本不同 checksum、epoch/version 优先级、architecture `all`、删除和成员重排都需要确定性冲突规则。
- Group metadata 声明的每个 Filename 必须与实际下载成员绑定，否则会把一个成员的 checksum 配到另一个成员的 blob。

当前 Nexus 官方 formats 表明确将 APT group 标为不支持，而 APT 客户端能配置多个 source 并自行排序。因此第一版只实现 hosted/proxy；若未来新增 group，必须作为独立产品扩展设计、使用持久化 source binding 和本地 signing key，不能宣称 Nexus 兼容。

## 权限、认证与密钥安全

- GET/HEAD metadata、public key 和 package 走 repository `READ`；匿名读取按 repository policy 决定。
- Hosted upload 走 `ADD`；覆盖走 `EDIT`；删除走 `DELETE`；rebuild 和 key rotation 走 repository admin 权限。
- Components API、repository-root POST 和 Admin UI upload 都必须经过相同权限和 write policy。
- APT private source 支持 Basic authentication 和现有 GenericToken/Bearer 能力；真实客户端重点验证 `/etc/apt/auth.conf.d` 的 Basic 行为。
- Repository private signing key 和 passphrase 加密落库，读取后只在最小签名作用域存在；日志、metrics、异常和 API DTO 不包含 key material。
- Proxy remote credential 继续使用 repository attributes 的 credential encryption；迁移时缺失/遮蔽 secret 必须 fail closed 并将 repository 置为 offline。
- Public key endpoint 仍受 repository `READ`/anonymous policy；对完全私有仓库不自动绕过权限。
- 审计记录 repository、distribution、component、package、version、architecture、operation、actor、key revision、checksum 和结果，不记录 password/token/private key。

## 资源与安全边界

- 上传 byte limit、control archive 展开 byte limit、entry count、field count、line length、description size 和 dependency size 全部可配置并有安全默认值。
- ar/tar/decompressor 使用流式读取和明确 EOF；拒绝 truncated member、超大声明、压缩炸弹、路径穿越、symlink/device 和重复关键 entry。
- deb822 parser 对字段名、continuation、UTF-8 和 stanza 数设置上限；不能把整个远端多 GB Packages 文件一次性读入内存。
- Metadata builder 使用有界 worker pool、临时磁盘配额和 build timeout；每个 distribution 最多一个跨副本 active build。
- 上传临时文件、build spool 和孤儿 staging asset 由持久化 marker 驱动的 cleanup worker 回收。
- `Filename`、redirect 和 remote URL 不能绕过出站访问策略；query 中的 credential 不写 cache key、日志或 Browse。
- Package 安全扫描在 snapshot 发布后异步执行；是否下载阻断由现有 scanning policy 决定，不能在 metadata 中宣称可安装却永远阻断而无可观测原因。

## Browse、Search、Usage 与运维

Admin UI：

- 创建 `apt-hosted`：distribution、component、architectures、write policy、signing key、Valid-Until policy。
- 创建 `apt-proxy`：remote URL、flat、distribution、enforce、passthrough/re-sign、remote auth、TTL、negative cache、auto-block 和 signing key。
- 只显示 hosted/proxy，不显示 group member editor。
- 显示 desired/published revision、last successful rebuild、pending marker、active key fingerprint、proxy Release age 和失败原因。
- 支持导入/轮换 key、下载 public key、触发 rebuild、重试失败 marker 和查看审计。

Browse/Search：

- Browse tree 按 distribution/component/package/version/architecture 展示逻辑节点，并链接真实 pool asset。
- Search 支持 `format=apt`、name、version、architecture、distribution、component、source package 和 checksum。
- Usage 生成 `signed-by` source 示例与私有仓库 `auth.conf` 示例，不默认输出包含明文 credential 的 URL。
- Component delete 和 asset delete 都调用 APT mutation service；generated metadata 默认不可直接删除。

观测指标建议：

- `kkrepo_apt_requests_total{repository,type,path_kind,status}`
- `kkrepo_apt_upload_total{repository,result}`
- `kkrepo_apt_metadata_build_total{repository,distribution,result}`
- `kkrepo_apt_metadata_build_seconds{repository,distribution}`
- `kkrepo_apt_metadata_build_bytes{repository,distribution,encoding}`
- `kkrepo_apt_published_revision{repository,distribution}`
- `kkrepo_apt_pending_revision{repository,distribution}`
- `kkrepo_apt_sign_total{repository,key_revision,result}`
- `kkrepo_apt_proxy_revalidate_total{repository,path_kind,result}`
- `kkrepo_apt_proxy_release_age_seconds{repository,distribution}`
- `kkrepo_apt_rebuild_backlog` 与 `kkrepo_apt_rebuild_failures_total`

## Nexus 迁移设计

### Definition 迁移

识别 Nexus `apt-hosted` 和 `apt-proxy` recipe，映射：

- blob store、online、write policy、strict content type。
- distribution、flat、enforce distribution。
- remote URL、HTTP authentication、metadata/content max age、negative cache 和 auto-block。
- signing key public fingerprint；private key/passphrase 只有在 source exporter 能安全读取且用户明确允许时迁移。

Nexus 没有 APT group；遇到未知 `apt-*` recipe 或第三方 plugin shape 必须 `NEEDS_MANUAL_ACTION`。

### Content 迁移

- Probe 先识别 Nexus version、metadata engine、APT asset attributes、blob reference、package coordinate 和 signing config shape。
- `.deb` 作为原始 blob 迁移后重新运行 inspector，并比较 package identity、size 和 checksum。
- `dists/`、Packages 压缩表示、Release 和 signatures 视为 generated metadata；默认在目标端基于 package projection 与目标 key 重建，不盲目复制旧 snapshot。
- 若必须保持原 signing identity 但 private key 不可导出，迁移计划标记 `NEEDS_MANUAL_ACTION`，要求管理员在目标端提供同一 key；不能生成占位 key 后声称无感迁移。
- Proxy cache 默认不迁移。用户显式选择时，只迁移有 Release/index checksum 证据的 cache；缺少 remote credential 时目标 proxy 保持 offline。
- Flat repository 和 source package 只有在目标实现对应能力且 source shape 经过验证后才可标为 `FULL`。

迁移任务继续支持 dry-run、resume、checksum、精确计数、失败报告和 profile hash。至少覆盖 Nexus 3.29.2 OrientDB 与 datastore-era Nexus 3.92.x；自动范围由真实 schema fingerprint 决定，不仅按版本字符串判断。

## 兼容性测试矩阵

### M0：Nexus reference 基线

实现前先对当前统一 reference（默认 Nexus 3.92.0 PostgreSQL）运行一次性仓库 fixture，记录：

- `apt-hosted` / `apt-proxy` recipe schema，确认 `apt-group` 不存在。
- hosted distribution、signing key、public key 和 default component 行为。
- repository-root POST、Components API `apt.asset`、UI upload 的 multipart shape、status 和 error body。
- canonical pool path、epoch、`lib*` prefix、architecture `all`、duplicate/write policy 和 delete。
- Packages 表示集合、stanza 字段/排序、Release 字段/排序、signature armor、content type、ETag、Last-Modified、HEAD 和 Range。
- key rotation、rebuild 中/失败时行为，以及 `/gpg.key`。
- proxy single/multi distribution、enforce、flat、passthrough、re-sign、auth、redirect、404、5xx 和 stale cache。

只有 host、Date、signature packet timestamp 等已证明非确定的字段可以规范化；path、checksum、status、header、stanza 语义和真实客户端结果不得无依据放宽。

### 协议与服务测试

- `.deb` fixture 覆盖 gzip/xz/zstd control archive、epoch、`~` version、Multi-Arch、architecture `all`、长 description 和依赖 continuation。
- 恶意 fixture 覆盖 truncated ar、duplicate member、zip/tar bomb、path traversal、symlink/device、超大 field、非法 UTF-8 和 checksum mismatch。
- Debian version comparator 与 `dpkg --compare-versions` 做 differential matrix。
- Release/Packages parser/generator 做 round-trip、golden bytes 和 signature verification。
- Hosted 并发上传/删除、key rotation 和 rebuild 在 MySQL/PostgreSQL contract 中验证 CAS、lease takeover 和 snapshot atomicity。
- 两副本同时冷构建只能发布一个 fencing-token 有效 snapshot；失败副本不能覆盖较新 revision。

### 真实客户端 E2E

至少使用一个 Debian stable、一个 Ubuntu LTS 和一个当前 APT 客户端：

1. 导入 scoped public key 并运行 `apt-get update`。
2. 安装带依赖的 hosted package，验证 package bytes 和 installed version。
3. 上传新版本后 update/upgrade；删除后新 metadata 不再声明该版本。
4. 验证 architecture-specific 与 `all` package。
5. 通过 Basic auth / `auth.conf` 读取私有 repository。
6. 代理 Debian/Ubuntu 可控上游，验证 passthrough signature、by-hash、package cache 和断网复用。
7. 验证 key rotation、invalid signature、expired Valid-Until、checksum mismatch 和 partial upstream update fail closed。
8. 在 metadata build、节点重启和另一个副本接管期间持续执行 update/install，客户端只能看到旧完整 snapshot 或新完整 snapshot。

### 迁移 E2E

- 创建 Nexus hosted/proxy、上传可安装 package、执行 `apt update/install` 基线。
- 迁移 definition 与 hosted blob 到 MySQL/PostgreSQL 目标，重建 signed metadata，再次真实安装。
- 验证 dry-run、resume、重复运行幂等、精确 package/asset/blob/projection 计数、checksum 和跨副本读取。
- 验证缺失 signing private key、masked proxy secret、未知 source shape 和未选择 proxy cache 均 fail closed 并给出可操作报告。

## 实施顺序

1. PR 1：M0 与 protocol foundation
   - 增加 Nexus APT 黑盒 probe、真实 `.deb` fixture 和可控 upstream。
   - 新增 `protocol-apt`、path/deb822/version/deb parser 与单元测试。
   - 不先注册可创建 recipe，避免 skeleton 被误认为可用。

2. PR 2：Hosted persistence 与安全发布
   - 增加双数据库 schema、DAO contract、format/recipe。
   - 实现 importer、component/asset 投影、suite revision、lease、snapshot builder 和 OpenPGP signer。
   - 接入 repository-root POST 和 content GET/HEAD/Range。

3. PR 3：Components API、Admin/Browse/Search
   - 增加 `apt.asset`、UI upload、Usage、key 管理、rebuild、delete、cleanup 和 scanning。
   - 完成真实 hosted `apt update/install` E2E。

4. PR 4：Proxy passthrough
   - 接入 RawProxy 共享 cache、distribution policy、flat、auth、redirect、negative cache、projection 和断网验证。
   - 完成可控 Debian/Ubuntu upstream 与真实客户端 E2E。

5. PR 5：Proxy re-signing
   - 增加 cached-package projection、multi-distribution state、本地 snapshot、key rotation 和 cache-retention 约束。
   - 对齐 Nexus re-sign 初始/重建/失败状态。

6. PR 6：Nexus 迁移与发布闭环
   - 增加 definition/content adapter、shape gate、dry-run/resume/checksum 报告。
   - 更新 compatibility matrix、client recipes、migration docs、backup/restore 和 Nexus compatibility 说明。
   - 跑 Nexus compatibility、Client E2E、Migration E2E 和双数据库/双副本矩阵。

## 验收标准

- `apt-hosted` 和 `apt-proxy` 可创建、编辑、停用、删除；系统不暴露 `apt-group`。
- Hosted 接受有效 `.deb`，拒绝伪造/损坏/越界 package，所有上传入口写入同一真相。
- 真实 APT 客户端能用 `signed-by` 完成 update、download、install 和 upgrade。
- Packages 中 identity、Filename、size/checksum 与实际 blob 一致；Release 中每个 index size/checksum 与响应 bytes 一致。
- InRelease 和 Release.gpg 能由 `/gpg.key` 对应 public key 验证；key rotation 不暴露半完成 snapshot。
- 同一客户端轮询、多副本并发和 worker 接管期间，不出现 Release/Packages 压缩表示互相不一致。
- Proxy passthrough 不修改上游 signed bytes，且 package cache、TTL、negative cache、auth、redirect 和 auto-block 按策略工作。
- Proxy re-signing 只声明已验证且可下载的 cached package，不把部分上游目录伪装成完整 mirror。
- Browse/Search/Usage、cleanup、security scanning、metrics 和 audit 都能识别 APT package 而非 Raw file。
- MySQL 和 PostgreSQL contract、真实客户端、Nexus 黑盒与迁移 E2E 全部通过。
- Nexus migration 对未知 shape、缺失 signing key 或 proxy secret 失败关闭，不生成占位凭据或虚假 `FULL` 报告。

## 参考资料

- Debian Repository Format: https://wiki.debian.org/DebianRepository/Format
- Debian Policy, Control files and their fields: https://www.debian.org/doc/debian-policy/ch-controlfields.html
- Debian `deb(5)`: https://manpages.debian.org/unstable/dpkg-dev/deb.5.en.html
- Debian `apt-secure(8)`: https://manpages.debian.org/bookworm/apt/apt-secure.8.en.html
- Debian `sources.list(5)`: https://manpages.debian.org/bookworm/apt/sources.list.5.en.html
- Debian `apt_auth.conf(5)`: https://manpages.debian.org/unstable/apt/apt_auth.conf.5.en.html
- Debian `apt-ftparchive(1)`: https://manpages.debian.org/testing/apt-utils/apt-ftparchive.1.en.html
- Sonatype APT Repositories: https://help.sonatype.com/en/apt-repositories.html
- Sonatype Formats: https://help.sonatype.com/en/formats.html
- Sonatype Components API: https://help.sonatype.com/en/components-api.html
