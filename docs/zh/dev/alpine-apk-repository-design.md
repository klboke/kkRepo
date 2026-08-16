# Alpine / APK 仓库开发设计说明

本文记录 kkrepo Alpine / APK 仓库格式的落地设计。目标不是把 `.apk` 当作 Raw 文件保存，而是在 Alpine `apk-tools` 官方仓库协议、Sonatype Nexus Repository Alpine 行为和 kkrepo 当前的关系数据库 + OSS/S3 + 多副本架构之间取兼容交集，并复用已经落地的 signed metadata snapshot、Cleanup Policy 与制品安全扫描基建。

## 当前支持状态与落地结论

截至 2026-08-15，本文定义的 APK v2 第一阶段能力已经落地：代码已注册 `RepositoryFormat.ALPINE`、`alpine-hosted`、`alpine-proxy`、`alpine-group` 和独立 `protocol-alpine`，并接入双数据库持久化、签名不可变 snapshot、Admin/Browse/Search、UI/Components API 上传、Cleanup/扫描、Nexus definition/content migration、真实客户端与多副本协调。路线图同步调整为“已实现”；使用方法见 [Alpine / APK 仓库使用指南](../repository-guides/alpine-apk.md)，性能门禁与复现方法见 [Alpine / APK 性能基线](alpine-performance-baseline.md)。

落地验证还固定了 Nexus 3.94 的一个 checksum 差异：它可能为 unsigned upload 生成 data member 的 Q1，导致其自身 apk-tools v2 fetch 完整性校验失败。kkRepo 以官方 apk-tools 行为为协议真相，以原始压缩 control member 计算 `C:Q1`。Index `A:` 必须写 URL namespace 的 repository architecture，因为 `apk` 用它拼接 package 下载路径；原始 `.PKGINFO` architecture 另行持久化并在 Browse/搜索中展示。

落地结论如下：

- 第一阶段实现 **APK v2 repository format** 的 hosted、proxy 和 group，生成并签名 `APKINDEX.tar.gz`，支持真实 `apk update`、`apk add`、`apk fetch`、`apk search`、`apk info` 和 `apk upgrade`。Nexus Repository 从 3.93.0 开始提供这三类 Alpine repository，适合作为当前兼容参考。
- `apk-tools` 3.x 客户端不等于 APK v3 repository。官方 `apk-tools` 3.0.x 同时支持 v2 `APKINDEX.tar.gz` 和 v3 `Packages.adb`；截至本文调研时，Alpine v3.23 与 edge 公共镜像仍提供 v2 索引。首期只声明 v2，v3 ADB package/index 必须作为显式分代能力增加，不能用同名文件或隐式降级伪装支持。
- 总体改动量为中到大。难点不在 `.apk` 下载，而在 concatenated gzip/tar segment 的严格解析、`C:` package identity、APK version 顺序、签名 index snapshot、group 冲突与 source binding，以及上传/删除后跨副本原子切换完整索引。
- Hosted 与 group 的索引发布复用 APT 已落地的 durable desired/published revision、snapshot、lease/fencing 和旧快照保留模式；Alpine 不能另建依赖单 JVM 定时器的 metadata builder。
- 一个 `.apk` 就是一个可扫描的逻辑制品，因此继续使用通用 `ASSET_BLOB` 扫描主体；但 scanner adapter 必须增加 APK v2 安全展开和 identity sidecar，不能把 concatenated gzip streams 当普通单层 `tar.gz`。
- 所有 hot path 先定义访问形状再定义复合索引。实现完成前必须在 MySQL 和 PostgreSQL 的大数据集上保存 query-plan 证据，并用同机 Nexus reference 做可复现的 `apk` 客户端性能对比。

## 调研基线

实现时按以下顺序确定行为：

1. `apk-tools` 对应稳定 tag 的 source、man page、package/index codec、version comparator 和真实客户端结果是协议真相。
2. Sonatype Nexus Repository Alpine 文档、repository REST schema 和真实 HTTP 行为是兼容性参考。
3. kkrepo 现有 APT signed snapshot、Conan Cleanup/扫描/性能门禁、hosted/proxy/group、迁移和多副本设施是落地基础。

协议关键事实：

- 客户端 repository base 通常是 `https://host/repository/repo/v3.23/main`；v2 客户端在其后请求 `{arch}/APKINDEX.tar.gz`，再按 index identity 下载 `{arch}/{name}-{version}.apk`。
- `.apk` 后缀在这里表示 Alpine package，不是 Android application package。APK v2 package 由 signature、control、data 三段 concatenated gzip stream/tar segment 组成；control 段包含 `.PKGINFO` 和安装脚本，data 段包含实际文件。
- v2 `APKINDEX.tar.gz` 是签名 segment 加包含 `DESCRIPTION`、`APKINDEX` 的 tar.gz。`APKINDEX` 由空行分隔记录，字段包括 `C/P/V/A/S/I/T/U/L/o/m/t/c/D/p/i/k`；未知但合法字段不能在 round-trip 时被静默丢弃。
- `C:` 是客户端用于 package 认证、去重和 cache identity 的协议 checksum，常见 v2 表示为 `Q1` + Base64 SHA-1。它不是整个 `.apk` 文件的普通 SHA-1；生成器必须按 apk-tools 的 v2 multipart identity 边界计算，并同时保存内部 SHA-256。
- 逻辑 package coordinate 由 repository、distribution、channel、repository architecture、`P` name 和 `V` version 组成；immutable content identity 还包含 `C` checksum 与 whole-blob SHA-256。`A` package architecture 也必须保存并校验，但不能仅凭文件名猜 identity。
- APK version 不是 SemVer。官方规则为数字段、可选字母、`_alpha/_beta/_pre/_rc` 等 suffix、可选 commit hash 和 `-rN` package revision；Cleanup、搜索排序和 group 冲突必须使用与 apk-tools 相同的 comparator。
- 正常 `apk` 信任链由受信 signing key、signed index、index 中的 package checksum 和 package bytes共同构成。服务端生成 index 时不能把无法下载或 checksum 不匹配的 package 写入已签名快照。
- `apk-tools` 3.x 为 v3 repository 定义 `{arch}/Packages.adb`、ADB package/index 和不同的签名结构；它与 v2 `APKINDEX.tar.gz` 不是同一 serialization 的压缩变体。

Nexus 兼容结论：

- Nexus 3.93.0 起提供 `alpine-hosted`、`alpine-proxy`、`alpine-group`，支持 `.apk`、`.tar.gz` metadata、token/anonymous access 和 repository-specific RSA key。
- Nexus 文档给出的 client base 是 `/repository/{repo}/{alpine-version}/{channel}`，direct hosted upload 是 `PUT /repository/{repo}/{alpine-version}/{channel}/{architecture}/{file}.apk`，删除使用同一路径 `DELETE`。
- Nexus 要求为 proxy、hosted、group 配置 Alpine signing settings，并指导客户端把对应 public key 放入 `/etc/apk/keys/`。私钥使用 PKCS#8 PEM；其 key filename、signature entry、算法和轮换行为必须通过 M0 fixture 固定。
- Nexus 文档没有完整规定 index record 排序、`DESCRIPTION`、unsigned package、duplicate coordinate、group 冲突、proxy 重签、HEAD/Range、conditional request、key rotation 和失败发布语义；这些不能从通用 repository 行为猜测。
- 当前统一 compatibility lane 使用 Nexus 3.94.0。实现前先以 3.94.x 固定黑盒基线；迁移自动支持范围只能在 source datastore shape 被真实验证后扩展到其它版本。

## 功能范围

### 第一阶段必须实现

1. 协议与 recipe
   - 新增 `RepositoryFormat.ALPINE`、`alpine-hosted`、`alpine-proxy`、`alpine-group` 和独立 `protocol-alpine` 模块。
   - 实现 APK v2 package/index parser、generator、version comparator、path model、signature codec、media type 和错误模型。
   - 路径、status、header、index bytes 语义、签名和错误正文由 apk-tools differential test 与 Nexus 黑盒 fixture 固定。

2. Hosted
   - 支持 Nexus-compatible raw `PUT` / `DELETE`、Components API 和 Admin UI `.apk` 上传。
   - 上传时严格解析 APK v2、校验 `.PKGINFO`、filename、path namespace、stream boundaries、package checksum、size 和 write policy。
   - package/blob/asset/component 先提交为 durable truth，再为对应 `(distribution, channel, repositoryArch)` 构建完整 signed index snapshot；对外永远只读取一个已发布 snapshot。
   - 支持 key 导入/生成/轮换、public key 下载、metadata rebuild、Browse/Search、Usage、审计、Cleanup Policy 和安全扫描。

3. Proxy
   - 默认 remote 可以是 `https://dl-cdn.alpinelinux.org/alpine/` 或其它兼容 mirror；支持 remote Basic/token credential、TLS、redirect、outbound policy、metadata/content TTL、negative cache、auto-block 和 stale policy。
   - Passthrough mode 原样缓存并返回上游 signed `APKINDEX.tar.gz`；只有 index/package checksum 可证明时才写可信 Browse/Search 投影。
   - Re-sign mode 建立新的本地信任边界，只能把已经完整缓存并验证的 package 写入本地 signed index；不能把部分 mirror 伪装成完整上游。
   - 同一上游 index/package 冷请求使用数据库 lease/fencing 合并；进程内 TTL cache 只保存可重建热结果。

4. Group
   - 按 member 顺序聚合相同 distribution/channel/architecture 的 package records，并生成 group 自有 signed `APKINDEX.tar.gz`。
   - 对每个 `{name}-{version}.apk` 保存 snapshot-scoped source binding；index record、download bytes 和 checksum 必须来自同一 member。
   - duplicate coordinate 默认 first member wins，但精确相等、不同 checksum、member failure、nested group 和 reorder 语义必须由 Nexus fixture 固定。
   - Group 只读，不作为 Cleanup target，不复制 member asset/SBOM；下载 usage 和安全策略落到实际 member。

5. 产品闭环
   - Admin UI 可创建三类 recipe、配置 path/signing/proxy/group、查看 desired/published revision、active key、proxy trust、binding 和失败状态。
   - Browse/Search 展示 distribution、channel、repository arch、package name/version/revision、package arch、origin、license、dependencies、provides、checksum 和真实 source repository。
   - Nexus definition/content migration 支持 dry-run、resume、checksum、幂等重试、shape gate 和逐仓库报告。
   - 真实 apk client、Nexus compatibility、Migration E2E、双数据库 contract、双副本 takeover、Cleanup/扫描和性能对比全部进入验收。

### 后续扩展

- APK v3 ADB package 与 `Packages.adb` index；必须先增加独立 codec、capability/config、fixtures、signer 和 migration shape，不能复用 v2 parser 猜测。
- v3 `pkgname-spec`、component list repository declaration和按 hash path 的扩展布局。
- 多 key 并行签名、HSM/KMS-backed signer 和 promotion workflow；首期只做加密落库的 active key revision 与明确轮换。
- 跨 repository copy/promotion；必须保留 package bytes、`C` checksum、metadata identity 和签名状态，不通过解包重打包改变制品。
- 面向超大 mirror 的增量 ADB/v2 index shard；只有真实 apk client 和上游规范允许时才能改变单体 index 形状。

### 明确不实现

- 不执行 `.pre-install`、`.post-install`、trigger、package payload、APKBUILD 或任何 package 内程序。
- 不在服务端构建 APKBUILD，也不把仓库管理器变成 Alpine build farm。
- 不把 `.apk`、`APKINDEX.tar.gz`、ADB 或大段未知 metadata 存进 MySQL/PostgreSQL；数据库只保存有界可查询投影、状态、索引和 Blob 引用。
- 不用普通 `tar.gz` parser 忽略 concatenated gzip member 边界，也不从文件名猜 `.PKGINFO` identity 或 `C` checksum。
- 不在首期把 APK v3 请求静默降级为 v2；`Packages.adb` 未实现时返回经客户端验证的明确 unsupported/not-found 行为。
- 不依赖单 JVM map、锁或 timer 维护 current snapshot、signing key、proxy validator、group binding、negative cache、scan task 或 cleanup 真相。
- 不在签名失败时返回 unsigned 新 index，也不修改上游 signed bytes 后继续声称 passthrough。
- 不为兼容错误行为关闭 archive/path/SSRF/资源限制；安全差异要记录并失败关闭。

## 模块与职责

| 模块 | 设计职责 |
| --- | --- |
| `core` | `ALPINE` format、三类 recipe、共享权限与上传契约 |
| `protocol-alpine` | v2 path、package/index/signature parser/generator、APK version comparator、media type 与错误模型 |
| `persistence-jdbc` | Alpine DAO、package projection、namespace revision、snapshot、signing key、lease、proxy state 和 group binding contract |
| `persistence-mysql` / `persistence-postgresql` | 同号 schema migration、约束、索引和 contract test |
| `server/alpine` | hosted importer、snapshot builder/signer、proxy client/cache、group resolver、删除、key 管理和迁移 writer |
| server 通用入口 | Controller、安全过滤、Components API、Browse/Search、Cleanup、扫描、metrics 和 repository 生命周期 |
| `security-scan` / `scanner-adapter` | `ASSET_BLOB` candidate、APK v2 受限展开、identity sidecar、Syft/Grype 和 policy decision |
| `migration-nexus` | Nexus Alpine version/shape 探测、definition/content plan、writer 和校验 |
| `admin-ui` / `browse-ui` | recipe 配置、上传、key、snapshot、浏览、Usage、scan/cleanup 状态 |
| `compat-test` | apk-tools fixture、可控 mirror、Nexus/kkrepo 黑盒对照和性能正确性探针 |

Controller 只负责 route、认证上下文、流式 body 和响应适配。Package/index 解析、snapshot 状态机、签名、proxy/group 选择、Cleanup 与扫描映射必须位于协议 service 或格式 adapter 中。

## URL 与路由设计

客户端配置示例：

```sh
echo "https://repo.example.com/repository/alpine-group/v3.23/main" \
  >> /etc/apk/repositories
cp kkrepo-alpine.rsa.pub /etc/apk/keys/
apk update
apk add curl
```

首期 route 如下；精确 status/header/error 仍由 M0 固定：

| 路径 | 方法 | 语义 |
| --- | --- | --- |
| `/repository/{repo}/{distribution}/{channel}/{arch}/APKINDEX.tar.gz` | GET/HEAD | v2 signed repository index |
| `/repository/{repo}/{distribution}/{channel}/{arch}/{name}-{version}.apk` | GET/HEAD | package 下载；支持条件请求和经 fixture 验证的 Range |
| 同一 `.apk` path | PUT | hosted raw publish |
| 同一 `.apk` path | DELETE | hosted package 删除并触发新 snapshot |
| Components API / Admin UI | POST | 使用显式 distribution/channel/repository arch 上传 `.apk` |
| repository admin API | PUT/POST | 导入/生成/轮换 key、下载 public key、触发 rebuild；不是 apk client protocol |

Path 约束：

- `distribution` 至少支持 `v3.19`、`v3.23`、`edge` 形状，但允许的自定义值以 apk-tools 与 Nexus validation 交集为准，不能只硬编码当前 Alpine release。
- `channel` 支持 `main`、`community`、`testing` 和安全的自定义 segment；`arch` 由有界 allow-list/config 管理，并保存原始 display value 与 canonical key。
- 只 percent-decode 一次；拒绝 encoded separator、二次编码、空段、点段、反斜杠、NUL/控制字符、Unicode/大小写碰撞和超长 segment。
- Uploaded filename 必须与 `.PKGINFO` 的 name/version 一致。Path `arch` 与 package `A` 的允许关系由真实 apk-tools/Nexus fixture 固定；不接受任意 mismatch。
- 公开 URL 始终位于 `/repository/{repo}/...`。Metadata 内不得写内部 Blob URL、临时文件、预签名 OSS URL 或带 credential 的 remote URL。
- `APKINDEX.tar.gz`、`Packages.adb` 和 reserved hidden prefixes 不能作为 hosted 普通 package path 上传。

## APK v2 package 解析与内容安全

`AlpinePackageInspector` 使用流式、有界的格式专用 parser：

1. 先保存原始 bytes 到受限 spool，同时计算 SHA-256、size 和 gzip member boundaries；不在请求线程把 package 读入 heap。
2. 识别 signature/control/data 三段。每段都校验 gzip EOF、tar header、entry count、声明 size、重复 entry 和尾随垃圾。
3. Control 段只接受一个 `.PKGINFO`，并有界读取 name、version、arch、description、url、license、origin、maintainer、build time、installed size、depends、provides、install-if、provider priority、commit 和 datahash。
4. 按 apk-tools v2 算法从 raw compressed control gzip member 计算 `C` 的 `Q1`/SHA-1 identity，并独立校验 `.PKGINFO` 中 raw compressed data gzip member 的 SHA-256 `datahash`；同时保存 whole-blob SHA-256。三者用途不同，不能互相替代。
5. 校验 name/version grammar、APK version、filename、route namespace、datahash、signature entry 形状和 package total size；未知字段原样保留在有界 canonical metadata，不自动变成 SQL column 或 metric label。
6. Data 段只做安全结构检查和扫描输入准备；拒绝 absolute/dot path、重复 entry、device/FIFO、越界 symlink/hardlink、稀疏文件放大、超限 xattr/PAX、压缩炸弹和截断流。
7. 识别到 ADB/APK v3 时返回明确 unsupported generation，不把它送入 v2 parser。

Parser 的 golden/differential tests 固定到 apk-tools 稳定 tag。运行时不依赖 shell 调用 `apk index`、`abuild-sign` 或容器中的 apk binary 才能保证正确性；官方工具仅作为 build/test oracle。

## Hosted 上传与 signed snapshot 发布

Alpine 的正确性单位不是单个 index row，而是一个签名 `APKINDEX.tar.gz` 所声明的完整、可下载 package set。流程如下：

1. Controller 完成 authentication、recipe、online、write policy、`ADD`/`EDIT` 权限和 path 校验。
2. Inspector 在事务外流式解析 package，产生 canonical identity、v2 index record、`C` checksum、SHA-256、size 和安全报告。
3. 获取 package coordinate lease；复查重复 identity、filename/path conflict 和 write policy，再通过通用 hosted storage 提交 blob、asset、component、Browse 投影与通用 `artifact_change_event`。
4. `alpine_package` upsert 在短事务内分配 namespace mutation revision，推进 `(repository, distribution, channel, repositoryArch)` 的 desired revision。相同 coordinate + 相同 `C`/SHA-256 是幂等；同一 coordinate 的不同 bytes 必须拒绝，并要求发布者提升 package version 或 `-rN`。单一 canonical package URL 无法同时为持有旧、新 index 的客户端返回两套 bytes，因此不能把通用 `ALLOW` write policy 映射为静默覆盖；与 Nexus 的差异由 M0 fixture 记录。
5. Durable publication worker 对 namespace 做短 debounce 合并突发上传，并使用共享 lease/fencing 捕获 desired revision。持续写入必须有 max-delay，不能让 index 永久不发布。
6. Builder 通过 keyset/forward-only cursor 读取该 revision 的 package records，生成 deterministic `DESCRIPTION` + `APKINDEX` tar.gz，再用 active key 生成 signature segment，写入 `.alpine/snapshots/{namespaceHash}/{revision}/APKINDEX.tar.gz` hidden asset。
7. Builder 对每个 record 再验证 asset/blob reference、size 和 `C`；只有全部生成物完成后，才通过 `(desiredRevision, publishedRevision, keyRevision, owner, fencingToken, leaseExpiry)` CAS 切换 current snapshot。
8. 对外 index route 只解析 published snapshot。构建失败、节点退出或签名异常继续提供上一个完整 snapshot；没有旧 snapshot 时返回明确 unavailable，绝不返回 unsigned/partial index。
9. Snapshot 发布后 package 才被客户端发现。旧 snapshot 至少保留两个版本和可配置 grace，确保正在 update/install 的客户端不会拿到 index 后立刻遇到 package 404。

删除使用反向顺序：先写 tombstone/推进 desired revision并发布不再包含 package 的新 index；保留期内 package GET 仍可从 tombstone 指向的原 asset 返回旧 bytes，待所有可能引用它的 snapshot 过期后再解绑 package asset 和 Blob。同一 coordinate 在 tombstone 保留期内不能用不同内容重新上传。Browse/API delete 不能直接绕过 Alpine mutation service。

管理员 rebuild、key rotation、group member reorder 和迁移恢复共用同一 snapshot pipeline，不维护第二套 index generator。

## `APKINDEX.tar.gz` 生成与签名

Index generator 必须与 apk-tools v2 语义一致：

- 每条 record 从已验证 package projection 生成，至少包含准确 `C/P/V/A/S/I`，并保留合法的 description、URL、license、origin、maintainer、build time、commit、dependencies、provides、install-if 和 provider priority。
- `S` 是实际 served `.apk` bytes size；`C` 对应同一 bytes 的 v2 package identity。Index 不得引用另一个 revision 的 metadata 或 size。
- record 顺序、字段顺序、空行、`DESCRIPTION`、tar typeflag、uid/gid/mode、mtime、gzip 参数和 signature member 以 apk-tools golden + Nexus fixture 固定；同 revision 重建必须 byte-stable。
- 生成器使用数据库 cursor 和磁盘 spool，内存由单 record/buffer 上限决定。依赖/provides 大列表有硬上限，不能一次 materialize 全仓库。
- Private key 以加密 PKCS#8 material 保存，public key filename 与 signature entry 精确匹配。API 永不回显 private material。
- v2 支持的 `.SIGN.RSA*` 算法与 Nexus reference 可能包含 legacy SHA-1 语义。实现必须固定互操作结果，同时继续用 SHA-256 做内部 Blob、ETag、migration 和审计完整性；若提供 RSA256/RSA512，必须经目标 apk 客户端矩阵验证并作为显式配置。
- Key rotation 先保存/验证新 key，再以新 key revision构建完整 snapshot。新 index 发布前旧 index 和旧 public key保持可用；public key retention 覆盖所有保留 snapshot。

单体 index rebuild 的 CPU、临时磁盘和上传字节随 namespace package 数近似线性增长。Debounce 只能合并 burst，不能消除 O(N)；容量测试必须覆盖持续上传和大 index，必要时通过 namespace 分片而不是不兼容的增量补丁解决。

## Proxy 设计

### Passthrough mode

Passthrough 保留上游信任链：

1. 对 index/package path 执行 outbound URL、DNS/IP、redirect、TLS 和 credential scope 校验。
2. `APKINDEX.tar.gz` 使用 metadata TTL + ETag/Last-Modified；`.apk` 使用 content TTL。所有 bytes 原样进入共享 Blob，不重写 signed index。
3. 配置 upstream public key 时，server 验证 signature、index tar 和 package `C`/size 后写可信 projection；未配置 key 时可以透明缓存，但 UI/DB 标记为 `UNVERIFIED_PASSTHROUGH`。该 projection 不能用于 proxy 自身的 re-sign mode；唯一兼容例外是管理员把此 proxy 显式加入 group，此时 group 以 transport trust 接受 index、用 group key 签名聚合结果，并在 package 首次下载时强制校验 snapshot binding 中的 `C`/size。
4. 首次 package fetch 必须与当前 observed upstream snapshot binding；checksum mismatch、truncated body 或 signature drift 不替换旧 verified cache。
5. 404/410 进入短 negative cache；401/403、429、5xx、timeout、invalid signature 和 checksum mismatch 不进入 not-found cache。
6. 上游不可用时可以提供仍在 policy 有效期内的 verified cached index/package；不修改 signed bytes 的时间或内容来延长有效期。

### Re-signing mode

Re-signing 建立 kkrepo 自己的 trust root：

- 先验证 upstream index，拉取本地 snapshot 将声明的全部 package，并逐个验证 `C`/size；整批完成后才能生成本地 signed index。
- 不允许把 `UNVERIFIED_PASSTHROUGH` projection 重签；管理员必须导入 upstream trust key 并完成整批验证。不能用告警开关把未验证上游升级为 kkrepo 的本地信任。
- 单次刷新有 package count、总 bytes、单 package、展开 metadata、时间和并发硬上限；超限保留旧 snapshot并报告。
- 本地 snapshot 引用的 package 在 index retention 结束前不可被 proxy eviction 删除。
- Refresh 失败不能在同一路径回退到 trust root 不同的 upstream index；mode/key 变化必须触发明确 generation 边界。

`alpine_proxy_index_state` 持久化 upstream URL namespace、validator、raw index SHA-256、signature/trust 状态、observed revision、published local revision和失败信息。节点本地 cache丢失只增加 DB/Blob 读取，不改变选择。

## Group 设计

Group 不能对 member index 做文件级 first-hit；它必须生成新的 signed aggregate：

1. 对目标 distribution/channel/repositoryArch 读取每个 member 的 published snapshot 或 proxy projection 和 member repository revision。`PASSTHROUGH` proxy 按其配置决定是否强制 upstream signature；关闭验签时明确记录 transport-trust 状态。
2. 流式 merge canonical package records。Identity 相同且 `C` 相同可去重；identity 相同但 `C` 不同按 member order 选择，并记录 conflict metric/audit。
3. 为每个最终 filename/coordinate 写 snapshot-scoped `alpine_group_binding`，包含 member、member snapshot revision、asset path、`C`、SHA-256 和 size。
4. 使用 group active key生成新的 `APKINDEX.tar.gz`；只有 bindings、index hidden asset和 group snapshot一起 CAS 发布后才可见。
5. Package GET 先通过 current group snapshot + exact filename 查 binding，再从同一 member读取；不能因 member暂时失败改从后置成员返回不同 bytes。
6. Member mutation、cleanup、delete、key/trust变化、offline 或 reorder 使 group desired revision推进。旧 group snapshot/binding在 retention 期内继续可读。
7. Nested group有 cycle detection、最大深度和 snapshot revision传播。Group 不复制 member component/asset/SBOM/usage，只持有小的 durable binding。

Group index 签名意味着客户端必须信任 group public key；若 package 本身还包含独立签名，服务端原样保留 package bytes，不删除或伪造 embedded signature。真实 apk client 对 unsigned/foreign-signed package 的接受边界由 M0 与 E2E 固定。

## 数据模型与高效索引

大 `.apk`、index snapshot、原始 `.PKGINFO`/未知字段和扫描结果继续放 OSS/S3。数据库保存 identity、bounded index record、状态、checksum、lease 和 Blob/asset 引用。

建议的 Alpine 专用表和关键索引如下；MySQL/PostgreSQL 使用同号 migration 和同一 DAO contract：

| 表 | 关键约束与索引 | 热查询 |
| --- | --- | --- |
| `alpine_package` | `UNIQUE(repository_id,namespace_hash,coordinate_hash)`；`UNIQUE(repository_id,id)`；`UNIQUE(asset_id)`；`idx_alpine_package_page(repository_id,namespace_hash,name_key,id)`；`idx_alpine_package_family(repository_id,distribution_key,channel_key,repository_arch,name_key,version_order_key,id)` | exact package、index cursor、Search/Cleanup keyset |
| `alpine_package_relation` | `FOREIGN KEY(repository_id,package_id) REFERENCES alpine_package(repository_id,id)`；`UNIQUE(package_id,relation_kind,token_hash,constraint_hash)`；`idx_alpine_relation_exact(repository_id,relation_kind,token_hash,package_id)` | dependency/provide/install-if exact token 反查 |
| `alpine_package_tombstone` | `UNIQUE(repository_id,namespace_hash,coordinate_hash,deleted_revision)`；`idx_alpine_tombstone_cleanup(created_at,id)` | delete retry、snapshot-safe GC |
| `alpine_namespace_state` | `PRIMARY KEY(repository_id,namespace_hash)`；`idx_alpine_publish_due(desired_at,last_error_at,repository_id)` | desired/published revision、pending claim |
| `alpine_index_snapshot` | `UNIQUE(repository_id,namespace_hash,revision)`；`UNIQUE(index_asset_id)`；`idx_alpine_snapshot_cleanup(published_at,created_at,repository_id)` | current/retained snapshot、cleanup |
| `alpine_signing_key` | `UNIQUE(repository_id,revision)`；`UNIQUE(repository_id,key_filename)`；`UNIQUE(repository_id,active_slot)`；`idx_alpine_signing_active(repository_id,active_slot)` | active/retained key lookup |
| `alpine_publish_lease` | `PRIMARY KEY(repository_id,lease_key_hash)`；`idx_alpine_lease_expiry(expires_at)` | package mutation、snapshot build fencing |
| `alpine_proxy_index_state` | `UNIQUE(repository_id,namespace_hash)`；`idx_alpine_proxy_refresh(next_refresh_at,id)` | validator/trust/stale refresh |
| `alpine_group_binding` | `UNIQUE(group_repository_id,namespace_hash,snapshot_revision,path_hash)`；`idx_alpine_group_member(member_repository_id,member_revision,id)` | exact package source、member invalidation |

`namespace_hash`、`coordinate_hash`、`token_hash`、`constraint_hash`、`lease_key_hash` 和 `path_hash` 只用于控制索引宽度。`coordinate_hash` 对 namespace 内 canonical `P`/`V` coordinate 计算，不包含 `C`；内容相等性另行复核 `C` 与 whole-blob SHA-256。Hash 命中后必须比较持久化 canonical 字段；理论碰撞不能变成 identity match。

`alpine_package_tombstone` 必须保存 canonical path、retired asset/blob reference、`C`、SHA-256、size、deleted revision 和 retain-until，而不是只保存审计事件。`alpine_package_relation` 的 token/constraint canonical 字段和 hash 均为非空；无版本约束使用显式 empty sentinel，避免 MySQL/PostgreSQL 的 nullable unique 语义分叉。`alpine_signing_key.active_slot` 只允许 active key 写 `1`，inactive key 写 `NULL`，从数据库约束保证每个 repository 最多一个 active key。

数据库访问规则：

- Exact package GET 从 current snapshot/binding或 repository + namespace/coordinate unique index进入，禁止 `%filename%` 扫描 asset 表。
- `version_order_key` 是带 codec version 的有界二进制排序键，其字节序必须与 `AlpineVersions` 完全一致，并通过 apk-tools differential corpus 证明；不能把原始 version 字符串直接用于 retain-N、Search 或 group 排序。
- Index build、Browse/Search、Cleanup、migration 和 snapshot cleanup 全部使用稳定 keyset/forward-only cursor，不使用随页数增长的 `OFFSET`。
- `index_record_text` 是有界、非查询列；搜索只使用显式 name/version/arch/origin/license/checksum 列和 `alpine_package_relation` 的 normalized token，禁止对全表 metadata JSON/TEXT 做 predicate。
- Browse exact/root/child 继续命中 `uk_browse_node_path`、`idx_browse_node_root`、`idx_browse_node_parent`；写入时持久化投影，不在读取请求临时解析全 index。
- Batch asset/blob/usage/scan 查询有默认 500 和硬上限，避免 N+1、超大 `IN` 和全仓库 materialization。
- Claim/takeover 使用数据库时间、短事务、`FOR UPDATE SKIP LOCKED` 与 fencing token；旧 owner heartbeat/commit 必须因 token 不匹配失败。
- Migration 上线前保存 MySQL/PostgreSQL 关键 SQL 的 `EXPLAIN ANALYZE`。Exact/高选择性查询不得全表扫描；keyset examined rows与 `limit + 1` 同阶。

## Cleanup Policy 接入

Alpine 复用当前 `CleanupSubjectScanner`、`CleanupPolicyCapabilities`、`CleanupUsageTrackingService`、`CleanupExecutionService` 和 `RepositoryContentDeletionService`，不另建 Alpine 定时删除器。

主体语义：

```text
subject       = 一个可安装 package build（distribution/channel/repositoryArch/name/version）
family        = distribution/channel/repositoryArch/name
version       = 完整 APK version（包含 suffix 与 -rN）
usage         = package asset 的最后下载水位
publishedAt   = package committed time
contentToken  = package mutation revision + asset/blob/usage revision 摘要
```

接入要求：

- `CleanupPolicyCapabilities` 注册 `AlpineVersions.COMPARATOR`；不能复用 SemVer、Debian 或 lexicographic comparator。
- Try Run 从 `idx_alpine_package_family` keyset索引进入。Retain N在同一 distribution/channel/repositoryArch/name内排序，不把不同发行线或架构误删成一个 family。
- Execute 获取 repository/namespace cleanup lease，锁定 package state并复查 content token、protection和 fencing；先发布不再引用 package 的 index，再在 snapshot retention后异步回收 asset/blob。
- 同一 origin 的多个 subpackage不是一个 cleanup subject；除非后续提供显式 origin-bundle policy，不能因 `o:` 相同批量猜测删除。
- Proxy cleanup只删除本地 cache并触发安全 refresh；Group不能绑定 Cleanup Policy。Group下载更新实际 member usage。
- RUNNING/PENDING scan通过 `CleanupProtectionProvider` 或 scanner Blob reference保护 subject；过期 protection有 deadline，不能永久阻止清理。
- Upload/delete/cleanup/scan race在双数据库 contract中覆盖：新下载使旧 Try Run stale，新 snapshot mutation使旧 content token skip，旧 fencing owner不能提交。

## 制品安全扫描接入

Alpine package 使用现有 capability gate、通用 `artifact_change_event`、candidate/task lease、Syft/Grype adapter、SBOM reuse、policy/waiver、Audit/Enforce 和下载策略。

扫描主体：

```text
subjectKind       = ASSET_BLOB
classification    = PACKAGE
repositoryId / componentId / assetId / blobId / blobSha256
distribution / channel / repositoryArch
name / version / packageArch / origin / apkChecksum
inputSchema        = alpine-apk-v2
```

具体要求：

1. Classifier只接受 committed `.apk` package asset；`APKINDEX.tar.gz`、public key、hidden snapshot、signature、tombstone和staging均为 `NOT_APPLICABLE`。
2. Scanner adapter增加 `ApkV2Extractor`，复用 ingest parser的边界规则，将 data segment安全展开到隔离目录，并把已验证 `.PKGINFO` 转换为只存在于扫描工作目录的 canonical identity sidecar/installed-db projection。
3. Syft运行 Alpine/APK、binary和language catalogers；输出至少要能与 expected name/version/packageArch对齐。Grype使用 `pkg:apk/...` identity时保留 distribution qualifier，避免不同 Alpine release误配。
4. Fingerprint包含 whole APK SHA-256、canonical `.PKGINFO` SHA-256 和 `alpine-apk-v2` schema；相同 payload但 identity不同不能错误复用声明型 SBOM。
5. Scanner不执行 control scripts、trigger、ELF或其它 payload；symlink不 materialize，展开 bytes、entries、single file、ratio、timeout、process tree和输出继续受 resource budget控制。
6. Hosted上传线程只提交outbox，不同步调用scanner。Proxy Audit可边下载边缓存；Enforce pending需要先完整缓存/校验并返回真实apk client可重试的错误。
7. Group下载解析到实际 member后调用 `ArtifactDownloadPolicy`，复用 member扫描状态；group不生成重复task或SBOM。

Scanner capability digest必须包含 APK input schema和所用cataloger。滚动升级时不支持 `alpine-apk-v2` 的旧 adapter不能领取此task。

## 认证、权限与密钥安全

- Metadata/package GET/HEAD走 repository `READ`；anonymous按 repository policy决定。Hosted PUT按新增/覆盖分别走 `ADD`/`EDIT`，DELETE走 `DELETE`，key/rebuild走 repository admin权限。
- Private Alpine repository支持apk客户端实际可用的 Basic/token方式；显式错误 credential不能降级为anonymous。URL内credential不得写入日志、Browse、metric或cache key。
- Signing private key以 `SecretCipher(EncryptionSecrets.credentialSecret())` 加密，数据库只保存encrypted material、public PEM、key filename、fingerprint/revision和active状态。
- Key filename严格校验并在signature entry/public key下载中保持一致；拒绝path separator、控制字符、重复active filename和无法签名的key。
- Proxy remote credential继续使用现有encrypted repository secret；客户端Authorization不转发给上游。Masked/missing secret迁移后repository保持offline。
- Audit记录repository、namespace、package identity、operation、actor、key revision、`C`/SHA-256、source member和结果，不记录password/token/private key或完整package metadata。

## Browse、Search、Components API 与运维

Browse建议逻辑树：

```text
{distribution}/{channel}/{repositoryArch}/
  ├── APKINDEX.tar.gz
  └── {name}/{version}/{name}-{version}.apk
```

最终 Nexus Browse API 的 `id`、`text`、node type、排序、component/asset linkage和Search asset path由3.94.x M0 fixture固定；不能把建议树误写成已证明的Nexus行为。

写入要求：

- `AlpineBrowsePathProjector`只接受typed canonical identity，不从原始URL、filename切割或APKINDEX字符串反推。
- Hosted package commit时同步写component/asset/Browse和outbox；snapshot index leaf在snapshot CAS事务中发布。任一投影失败都不能产生可发现package。
- Proxy只在verified package/index projection提交时写可信Browse/Search；passthrough-unverified可显示cache asset，但必须标注trust状态。
- Group Browse合并member逻辑节点并携带source repository，不复制member asset；index leaf显示group snapshot/key revision。

Search 至少支持 format、distribution、channel、repository arch、name、version、package arch、origin、license、maintainer、dependency/provide exact token、`C` checksum、SHA-256 和 source repository。Dependency/provide 搜索从 `alpine_package_relation` 的 normalized token 索引进入，不对全文字段做无索引 contains。

Components API / UI upload必须显式提供distribution、channel和repository architecture，并只接受一个`.apk` asset。服务端从package内提取name/version/arch；用户输入不能覆盖已验证identity。响应返回canonical path、checksum、snapshot desired/published revision和index pending状态。

Admin UI显示：

- active/retained key filename、fingerprint、revision和public key下载；private material不回显；
- 每个namespace的desired/published revision、package count、index SHA-256/size、last publish/error和active lease；
- proxy upstream validator/trust/stale/auto-block；group member revision/conflict/binding计数；
- snapshot rebuild、key rotation、cache invalidate和失败重试入口，全部复用同一durable pipeline。

## 多副本、一致性与故障语义

- Package upload/delete、snapshot build、proxy refresh和group aggregate都使用MySQL/PostgreSQL lease + fencing；本地scheduler只负责唤醒due work。
- Builder在事务外执行大index生成/签名/Blob上传，事务内只做有界CAS。数据库锁期间不读写大Blob。
- 一个副本在package Blob提交后、index构建前崩溃：package暂不可发现，另一个副本根据desired revision接管并发布。
- 一个副本写完hidden index后、CAS前崩溃：旧snapshot继续服务；orphan hidden asset由fencing-aware cleanup回收。
- 一个副本CAS后、response前崩溃：current pointer已是唯一真相，其它副本立即可读；重试幂等。
- Delete/rebuild失败：旧signed snapshot和旧package继续完整可用，不出现index声明404或unsigned partial metadata。
- Proxy上游失败：只按明确stale policy返回最后一个verified generation；auth/signature/checksum错误不写negative cache。
- DB不可用时不发布、不轮换key、不执行cleanup/scan claim。已有immutable Blob是否可读沿用全局数据库故障策略，不能由Alpine单独fail open。
- 所有worker有batch、max batches、grace、lease、metric和kill switch；节点本地cache有明确TTL/水位失效，丢失只影响性能。

## Nexus 迁移设计

Definition migration：

- 识别Nexus 3.93+ `alpine-hosted`、`alpine-proxy`、`alpine-group`，迁移name、online、write policy、blob store、remote、TTL、negative cache、member及顺序和可证明的Alpine config。
- Signing public key/key filename/fingerprint可以迁移；private key只有source export明确包含且管理员授权时才导入。Masked/unavailable private key不得生成placeholder，target保持offline并报告manual action。
- Proxy secret同样fail closed；group没有可用signing key时不能上线生成unsigned aggregate。

Content migration：

- 自动`FULL`只适用于source profile能证明的Nexus version/datastore shape、canonical Alpine path、package identity、size和checksum。初始live gate使用Nexus 3.94.0 exact shape；其它3.93.x/3.94.x/更新版本需fixture证据后扩展。
- 只迁移原始`.apk` package Blob；Nexus生成的`APKINDEX.tar.gz`、Browse/Search派生数据、lease、negative cache和临时文件不作为真相，在target从package rows重建。
- Import先在事务外解析/校验package，再通过同一hosted importer和Browse projector幂等提交；checkpoint使用source repository + stable source asset identity。
- Proxy cache默认不迁移；管理员显式选择且upstream snapshot/package binding可证明时才恢复为proxy cache，不能转成hosted package。
- Group只迁移definition/member order；index和binding在target使用member snapshots与target key重建。
- Dry-run报告repository、namespace、package、bytes、invalid/unsupported v3、missing key/secret、generated metadata filtered和预计Blob复用；resume/retry/takeover保持幂等。

Migration E2E在Nexus 3.94 reference上传可安装dependency graph，迁移到MySQL/PostgreSQL target，显式导入测试key后运行`apk update/add/info`，校验exact version、package bytes、index signature、row count和跨副本读取。Unknown shape、corrupt package、missing key/secret和未选择proxy cache必须失败关闭。

## 性能与 Nexus 对比验收

Alpine 实现不能以“apk 可以安装”替代性能验收。实现 PR 必须新增 `scripts/perf/compare-alpine-nexus.py` 和 `docs/zh/dev/alpine-performance-baseline.md`，方法沿用 Conan/APT 基线但覆盖 signed index snapshot 和真实 apk solver 流程。

### 对比环境与方法

- Reference固定为implementation compatibility lane使用的Nexus版本，初始为3.94.0；报告记录edition、JVM、PostgreSQL、Blob store、repository/key配置和精确image digest。
- Candidate使用同机同资源kkrepo PostgreSQL做主对比，MySQL另跑相同正确性、客户端和query-plan矩阵，不把不同数据库结果混成一个ratio。
- 两端使用相同package bytes、namespace、key强度、权限、Blob store类型和TLS/反向代理层；proxy使用本地可控mirror，排除公网抖动。
- HTTP热路径先预热至少32次，在并发16下至少250次，执行3轮并交替Nexus/kkrepo顺序；报告三轮中位数req/s、p50、p95、错误率和MiB/s。
- 每次计时前验证index signature、record identity/`C`/size、download bytes和真实`apk update/add/info`。错误页、stale错误generation、空index或不同package set不能计入性能。

### 必测场景

1. 1k、10k、100k package namespace的warm `APKINDEX.tar.gz` GET/HEAD/304和完整download。
2. 4 MiB与256 MiB `.apk` GET、64 KiB Range、ETag/If-Modified-Since和真实`apk fetch`。
3. 真实`apk update` + `apk add` dependency graph，cold/warm client cache和server cache各一轮。
4. Hosted单package PUT/DELETE到new signed snapshot visible latency；16并发不同coordinate和持续60秒写入的debounce/max-delay。
5. Key rotation/rebuild 100k record index的build、sign、CAS和旧snapshot并发读取。
6. Proxy cold index/package fill、warm hit、validator revalidate、404 negative、5xx stale、invalid signature/checksum drift。
7. Group 4/16 members的index merge、duplicate coordinate/source binding、member reorder和warm package GET。
8. Search exact/prefix、Browse root/namespace/package和Components page，在100k/1M package dataset记录SQL数与rows examined。
9. Cleanup Try Run/Execute与16并发`apk add`同时运行，报告foreground p95、subjects/s、snapshot lag、lock wait和stale/skip。
10. Scanning capability disabled与enabled/Audit上传；同步outbox开销单独报告，异步scanner时间不混入PUT latency。

### 发布门禁

- 所有场景成功率100%，签名、index/package checksum、HTTP语义和真实客户端结果先于速度通过。
- Warm index metadata与真实`apk update/add`吞吐不得低于同机Nexus的`0.80x`，p95不得高于Nexus的`1.25x`。
- Package GET/Range吞吐不得低于Nexus的`0.90x`，p95不得高于`1.15x`。
- Hosted upload到signed snapshot可见p95不得高于Nexus的`1.25x`；scanner disabled->Audit的同步上传p95增长不得超过10%。
- 100k record index build内存保持有界，不能一次性materialize全部records；持续写入时max publish lag必须落在配置门禁内，不能因trailing debounce饥饿。
- Group/proxy cold generation p95不得高于Nexus的`1.25x`；warm hit使用普通热读门禁。Trust verification成本单独报告但不能绕过。
- Browse每次请求命中共享root/path/parent索引；exact package/group binding命中本文unique/exact索引。SQL数保持常数级，不随ancestor深度或repository总asset数增长。
- 大数据集至少含1,000 namespace、总1,000,000 package rows、一个100,000 package热点namespace、16-member group和10% duplicate coordinates。MySQL/PostgreSQL关键SQL都命中声明索引。
- Exact/list/claim/cleanup/scan candidate不得出现不必要full scan、external sort或unbounded temp table；keyset examined rows与page/batch同阶。
- Background publish/cleanup/scan期间foreground p95相对无后台基线增长不得超过20%，且不能出现长事务、未处理deadlock或旧fencing owner提交。

未达门禁时必须用profile、statement digest和`EXPLAIN ANALYZE`定位并复测。阈值调整必须在实现PR中附Nexus/kkrepo原始结果、正确性证据、风险和明确批准记录。

## 测试与兼容性矩阵

### M0：Nexus 3.94 reference 基线

在注册可创建recipe前先固定：

- `alpine-hosted/proxy/group` REST schema、默认值、key字段、member规则和unsupported generation。
- PKCS#8 key导入/生成、public key filename/download、signature tar entry/algorithm、rotation和invalid key错误。
- Signed/unsigned/foreign-signed APK v2 raw PUT、duplicate/write policy、filename/path/arch mismatch和DELETE。
- `DESCRIPTION`、APKINDEX字段/顺序、`C`计算、tar/gzip headers、Content-Type、ETag、Last-Modified、HEAD、Range和conditional response。
- Browse/Search的component/asset identity、storage path、node tree和generated metadata可见性。
- Proxy passthrough/re-sign、upstream key、validator、auth、redirect、404/5xx/stale/checksum drift。
- Group duplicate/same-version-different-checksum、member priority、nested group、member unavailable和key behavior。

只有Date、request ID、tar/gzip mtime、signature timestamp等已证明非确定字段可规范化；path、record、checksum、status、header、signature validity和客户端结果不得无依据放宽。

### 协议、数据库与安全测试

- APK v2 fixture覆盖RSA/RSA256等可接受signature、unsigned、multiple signature、datahash、scripts、large metadata和常见architectures。
- 恶意fixture覆盖truncated/extra gzip member、duplicate `.PKGINFO`、tar bomb、path traversal、symlink/device、PAX/xattr放大、invalidUTF-8、bad `C`/datahash和伪装ADB。
- `AlpineVersions`与apk-tools 2.14.x/3.0.x `apk version -t/-c`做differential corpus，覆盖suffix、leading zero、commit hash和`-rN`。
- Package/index parser/generator做golden bytes、round-trip、property-based和官方tool differential；签名由真实apk keyring验证。
- MySQL/PostgreSQL contract覆盖unique/FK、coordinate idempotency、desired/published CAS、snapshot retention、key rotation、lease takeover、group binding、tombstone和repository delete。
- 双副本覆盖并发upload/delete、same coordinate conflict、proxy cold fill、group rebuild、worker crash/takeover和rolling restart。
- Cleanup/scanning race、恶意archive、large index资源上限和query-plan regression进入CI。

### 真实 apk 客户端 E2E

至少覆盖一个apk-tools 2.14.x客户端、Alpine 3.23上的apk-tools 3.0.x v2 mode和实现时current stable/edge：

1. 安装scoped public key，配置hosted/group base并运行`apk update`。
2. 上传包含transitive dependency与subpackages的多版本fixture，执行`apk add/search/info/policy/fetch`并校验installed version和bytes。
3. 上传`-r1`后update/upgrade；DELETE旧版本后新index不再声明，保留snapshot仍可完成在途download。
4. 覆盖x86_64/aarch64/noarch语义、main/community/custom channel和v3.x/edge distribution。
5. 通过Basic/GenericToken与anonymous策略访问；错误credential不降级。
6. Proxy可控mirror验证cold/warm/offline/stale/404/auth/signature/checksum drift；公网不作为CI正确性真相。
7. Group hosted+proxy、duplicate coordinate、member order变化和另一副本读取；package bytes与index binding一致。
8. Key rotation期间持续update/install，只能看到由已受信旧key或新key签名的完整snapshot。
9. Audit下载不改变client；Enforce pending/blocked错误和重试由真实apk固定。
10. APK v3/`Packages.adb`请求在首期以明确generation边界失败，不误读成v2。

### 迁移 E2E

- 在Nexus 3.94 hosted/proxy/group创建dependency graph并保存index signature、records、package bytes和真实install基线。
- 迁移definition与hostedcontent到MySQL/PostgreSQL target，显式完成signing key后重建index并再次安装。
- 验证dry-run、resume、重复运行、worker takeover、checksum、计数、Browse/Search和跨副本读取。
- 验证unknown source shape、APK v3、损坏package、missing private key、masked proxy secret和未选择proxy cache全部fail closed。

## 实施顺序

1. M0 与 protocol foundation
   - 增加Nexus Alpine黑盒probe、apk-tools 2.x/3.x fixture和可控mirror。
   - 新增`protocol-alpine`的v2 package/index/path/version/signature契约与测试。
   - 此阶段不注册可创建recipe。

2. Hosted persistence 与 signed snapshot
   - 增加双数据库schema/DAO/索引、format/recipe、package importer、namespace state、lease/fence、snapshot builder和RSA key管理。
   - 完成raw PUT/DELETE、GET/HEAD/Range、真实hosted apk E2E和多副本接管。

3. Cleanup、安全扫描与产品面
   - 接入Alpine comparator、snapshot-safe delete、APK scanner input、Audit/Enforce、Admin/Browse/Search、Components API和metrics。

4. Proxy 与 group
   - 接入共享cache、validator/negative/stale、upstream trust、re-sign、group aggregate/source binding和nested invalidation。
   - 完成可控mirror、offline、duplicate coordinate、key rotation和真实client E2E。

5. 迁移与性能门禁
   - 增加Nexus shape gate、definition/content writer、dry-run/resume/checksum报告和Migration E2E。
   - 增加Alpine/Nexus性能脚本、双数据库百万package query-plan gate和基线文档；达到本文门禁后才能把路线图标记为完成。

## 验收标准

- `alpine-hosted`、`alpine-proxy`、`alpine-group` 可创建、编辑、停用和删除；首期明确只支持 APK v2。
- 真实 apk-tools 2.x/3.x 客户端能 update、search、info、fetch、add 和 upgrade，且 signed index/`C`/size/package bytes 一致。
- Hosted 接受有效 APK v2，拒绝损坏、伪装 ADB、path/identity mismatch 和资源超限输入；所有上传入口写入同一真相。
- 上传/删除/key rotation/member reorder 期间，多副本客户端只能看到旧完整或新完整 signed snapshot，不出现 index 声明 404、错 checksum 或 unsigned partial index。
- Proxy passthrough 不修改 upstream signed bytes；re-sign 只声明 verified 且可下载 package。TTL、negative、auth、redirect、auto-block 和 stale 按策略工作。
- Group aggregate 按 Nexus fixture 处理冲突，index record/package download 始终命中同一 source binding。
- Cleanup 使用 APK version comparator 并 snapshot-safe 删除；安全扫描使用受限 APK v2 input，不执行 package scripts；Group 复用 member 状态。
- MySQL/PostgreSQL 所有关键查询命中高效索引，无 unbounded scan/materialization；百万 package 数据集与同机 Nexus 性能门禁通过。
- Nexus migration 对 unknown shape、APK v3、损坏 Blob、missing key 或 proxy secret 失败关闭，不生成 placeholder 或虚假 `FULL`。
- Protocol、双数据库、双副本、真实客户端、Nexus 黑盒、Migration E2E、Cleanup/扫描 race 和恶意输入测试全部通过后，路线图才给 Alpine / APK 增加 `✅`。

## 参考资料

- [apk-tools v3.0.7: repository layout](https://gitlab.alpinelinux.org/alpine/apk-tools/-/blob/v3.0.7/doc/apk-repositories.5.scd)
- [apk-tools v3.0.7: package metadata and version rules](https://gitlab.alpinelinux.org/alpine/apk-tools/-/blob/v3.0.7/doc/apk-package.5.scd)
- [apk-tools v3.0.7: APK v2 format](https://gitlab.alpinelinux.org/alpine/apk-tools/-/blob/v3.0.7/doc/apk-v2.5.scd)
- [apk-tools v3.0.7: APK v3 format](https://gitlab.alpinelinux.org/alpine/apk-tools/-/blob/v3.0.7/doc/apk-v3.5.scd)
- [apk-tools v3.0.7: v2 index generation](https://gitlab.alpinelinux.org/alpine/apk-tools/-/blob/v3.0.7/doc/apk-index.8.scd)
- [apk-tools v3.0.7: v3 index generation](https://gitlab.alpinelinux.org/alpine/apk-tools/-/blob/v3.0.7/doc/apk-mkndx.8.scd)
- [Alpine Linux APK format notes](https://wiki.alpinelinux.org/wiki/Alpine_package_format)
- [Sonatype Nexus Repository: Alpine Repositories](https://help.sonatype.com/en/alpine-repositories.html)
- [Sonatype Nexus Repository: Create an Alpine Repository](https://help.sonatype.com/en/create-an-alpine-repository.html)
- [Sonatype Nexus Repository: Configure Alpine with Nexus](https://help.sonatype.com/en/configure-alpine-with-nexus.html)
- [Sonatype Nexus Repository: Alpine CLI Usage](https://help.sonatype.com/en/alpine-cli-usage.html)
- [Sonatype Nexus Repository 3.93.x Release Notes](https://help.sonatype.com/en/sonatype-nexus-repository-3-93-0-release-notes.html)
- [Syft supported package ecosystems](https://github.com/anchore/syft)
- [kkRepo APT / Debian 仓库开发设计说明](apt-debian-repository-design.md)
- [kkRepo Conan 2 仓库开发设计说明](conan-repository-design.md)
- [kkRepo Cleanup Policy 开发设计说明](cleanup-policy-design.md)
- [kkRepo 制品安全扫描开发设计说明](security-scanning-design.md)
