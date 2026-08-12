# APT / Debian 仓库使用指南

kkrepo 通过 `apt-hosted` 和 `apt-proxy` 提供 Debian 二进制包仓库，并保持 Nexus 风格的客户端
根路径：

```text
https://nexus.example.com/repository/<repo>/
```

Hosted 用于发布私有 `.deb`，Proxy 用于代理 Debian/Ubuntu 上游 archive。当前边界不包含 APT
group、hosted source package、flat hosted、生成式 Contents/Translation index、PDiff 和 `.udeb`
index。英文版见 [APT / Debian Repository Guide](../../en/repository-guides/apt-debian.md)。

## 创建和配置仓库

在 **Admin > Repository > Repositories** 创建对应 recipe。APT 配置语义如下：

| 配置 | Hosted | Proxy |
| --- | --- | --- |
| Distribution | 必填，默认 `stable` | Passthrough 多 distribution 场景可不填 |
| Component | 默认 `main` | 本地重签名 metadata 使用的 component |
| Architectures | 默认 `amd64`；接受 `all` package | 重签名时生成投影的 architecture |
| Enforce distribution | 默认开启 | 拒绝配置 distribution 之外的请求 |
| Flat | 不支持 | 仅 `PASSTHROUGH` 支持 |
| Metadata mode | 固定为 `RESIGN` | 默认 `PASSTHROUGH`，也可选 `RESIGN` |
| Valid Until days | 可选，范围 `0`-`3650` | 只作用于本地重签名的 Release metadata |
| Origin / Label | 默认 `kkRepo` | 只作用于本地重签名的 Release metadata |

Distribution、component 和 architecture 都按安全 path segment 校验。Hosted 上传包的 control
archive architecture 既不是 `all`、也不在已配置列表中时，请求会被拒绝。

## 签名密钥

Hosted 和 re-sign proxy 会发布 `Release`、clear-signed `InRelease` 和 detached
`Release.gpg`。没有预先配置 key 时，kkrepo 会在首次需要签名材料时，为该仓库生成 RSA-3072
key。生产环境应明确选择继续使用这个生成的 identity，或者在客户端信任仓库前导入组织管理的
OpenPGP private key。

管理 UI 支持导入 private key、生成新 key、查看 active fingerprint 和轮换 key。等价的生成/轮换
接口为：

```bash
curl -u "admin:$KKREPO_PASSWORD" \
  -X PUT \
  -H 'Content-Type: application/json' \
  --data '{"generate":true}' \
  https://nexus.example.com/internal/repositories/apt-hosted/apt/signing-key
```

导入 private material 时，优先使用 UI；也可以向同一 endpoint 发送 `privateKey` 与可选
`passphrase`。不要把 private key JSON 或 passphrase 留在 shell history。Private material 使用
kkrepo credential secret 加密落库，API 永不回显；恢复后必须使用相同 secret 才能解密。

轮换会先保存新 key，再同步重建签名 metadata。Rebuild 提交前，客户端继续读取上一套完整 snapshot。
`/gpg.key` 会返回保留的当前与前一个 public key，允许客户端平稳经历轮换；旧信任锚退役前应先分发
更新后的 keyring。

## 配置 APT 客户端

创建独立 keyring 目录，下载仓库公钥，并设置 APT 可读权限：

```bash
sudo install -d -m 0755 /etc/apt/keyrings
curl --fail --show-error -u "alice:$KKREPO_PASSWORD" \
  -o /tmp/kkrepo-apt.asc \
  https://nexus.example.com/repository/apt-hosted/gpg.key
sudo install -m 0644 /tmp/kkrepo-apt.asc /etc/apt/keyrings/kkrepo.asc
rm -f /tmp/kkrepo-apt.asc
```

配置 `/etc/apt/sources.list.d/kkrepo.list`：

```text
deb [signed-by=/etc/apt/keyrings/kkrepo.asc] https://nexus.example.com/repository/apt-hosted stable main
```

私有仓库不要把凭据放进 URL。创建 `/etc/apt/auth.conf.d/kkrepo.conf`：

```text
machine nexus.example.com/repository/apt-hosted/
login alice
password <password-or-token>
```

```bash
sudo chmod 0600 /etc/apt/auth.conf.d/kkrepo.conf
sudo apt-get update
sudo apt-get install demo-package
```

Key endpoint 同样遵循仓库 READ/anonymous 策略。完全私有的仓库应先用有权限的身份下载 key，再配置
客户端。

## 发布二进制包

通过 Nexus 兼容的仓库根 endpoint 上传：

```bash
curl --fail --show-error -u "alice:$KKREPO_PASSWORD" \
  -H 'Content-Type: multipart/form-data' \
  --data-binary @demo-package_1.0.0-1_amd64.deb \
  https://nexus.example.com/repository/apt-hosted/
```

Components API 和管理 UI 使用唯一的 `apt.asset` 字段：

```bash
curl -u "alice:$KKREPO_PASSWORD" \
  -F apt.asset=@demo-package_1.0.0-1_amd64.deb \
  'https://nexus.example.com/service/rest/v1/components?repository=apt-hosted'
```

所有入口复用同一个 importer。成功响应前，kkrepo 已解析 Debian control archive，确认
package/version/architecture identity，校验 archive 安全上限和 checksum，推导 canonical
`pool/` path，并持久化 blob、asset、component 和 APT package record。Hosted 写入是否合法不依赖
后续 metadata 投影决定。

### 异步发布 Metadata

上传成功表示 package 已经持久化，但不保证它立刻出现在 `Packages` 中。Publication worker 会对
突发写入 debounce，流式生成完整的 `Packages`、`Packages.gz`、`Packages.bz2`、
`Packages.xz`、`Release`、`InRelease`、`Release.gpg` 和 by-hash asset，最后原子切换
published snapshot。读取方只会看到上一套或新一套完整签名 snapshot，不会看到混合的半成品。

管理 UI 会展示发布状态；运维人员也可以直接查看：

```bash
curl -u "admin:$KKREPO_PASSWORD" \
  https://nexus.example.com/internal/repositories/apt-hosted/apt/status
```

每个 suite 的 `desiredRevision == publishedRevision` 且 `lastError` 为空，表示发布已追平。
`lastPublishedAt` 记录最近一次成功切换。构建失败时旧 snapshot 继续在线，pending revision 会从
持久化状态重试。

显式重建也使用同一套同步 pipeline：

```bash
curl -u "admin:$KKREPO_PASSWORD" \
  -X POST \
  -H 'Content-Type: application/json' \
  --data '{"distribution":"stable"}' \
  https://nexus.example.com/internal/repositories/apt-hosted/apt/rebuild
```

这些 `/internal/repositories` 操作需要仓库管理权限；交互式操作优先使用管理 UI。

## Proxy 模式

| 模式 | 信任与响应字节 | 本地目录 |
| --- | --- | --- |
| `PASSTHROUGH` | 缓存并原样返回上游签名 Release、index、by-hash path 和 package bytes；客户端信任上游 key。 | 只在 Release/Packages size 与 checksum 匹配时，best-effort 写 Browse/Search 投影。投影失败不会改写或阻断本来有效的透传响应。 |
| `RESIGN` | 构建由仓库 key 签名的新本地 archive；客户端信任 kkrepo key。 | 先校验 Release 中 index 的 size/SHA-256，再下载并校验全部已声明 binary package，最后原子发布本地 metadata。 |

因此 proxy projection 不是 hosted 写入延迟校验机制，而是对上游 archive 的有界目录/cache 投影。
Re-sign projection 不完整、超过 10,000 个 package 或 20 GiB、或出现 checksum 不一致时会失败关闭并
保留旧 snapshot。Flat proxy 只支持 passthrough。

## 多副本发布与保留

Suite revision、snapshot、proxy observation、signing key 与 fenced lease 都在共享关系数据库；
package 和生成 metadata bytes 在已配置 blob store。任一副本都能发布，lease 过期后也可以接管。
节点本地 settings/snapshot cache 只是可重建热缓存，由共享 version watermark 失效。

Index 构建使用数据库 forward-only cursor 和临时 spool。堆内存随单个 package-name group 与固定
I/O buffer 变化，不随整个 suite package 数直接增长。但每次 publish 仍会重写完整 APT index，所以
CPU、临时磁盘和 metadata 上传量会随当前包量近似线性增加。Debounce 能把突发写入合并为一次
rebuild，但不会把完整签名 index 生成变成 O(1)。

已发布 metadata 不可变。Cleanup 至少保留当前 snapshot 和两个历史 snapshot，并在删除更老版本前
等待 grace period，从而保护 by-hash 读取和正在进行的客户端更新。已删除 package blob 只有在所有
保留 snapshot 都不再引用它、且通过通用 blob GC 安全检查后才可回收。

高级发布和 snapshot 保留配置如下：

| 环境变量 | 默认值 | 用途 |
| --- | ---: | --- |
| `KKREPO_APT_PUBLICATION_ENABLED` | `true` | 运行持久化后台发布 worker |
| `KKREPO_APT_PUBLICATION_POLL_INTERVAL_MS` | `500` | Pending suite 轮询间隔 |
| `KKREPO_APT_PUBLICATION_INITIAL_DELAY_MS` | `1000` | Publisher 首次轮询前延迟 |
| `KKREPO_APT_PUBLICATION_BATCH_SIZE` | `16` | 每轮处理 suite 数，限制为 1-256 |
| `KKREPO_APT_PUBLICATION_DEBOUNCE_MS` | `500` | 合并写入的静默窗口 |
| `KKREPO_APT_PUBLICATION_MAX_DELAY_MS` | `30000` | 持续繁忙 suite 被尝试发布前的最大延迟 |
| `KKREPO_APT_PUBLICATION_RETRY_MS` | `30000` | 失败 revision 的最小重试间隔 |
| `KKREPO_APT_SNAPSHOT_CLEANUP_ENABLED` | `true` | 运行 snapshot 与 tombstone 清理 |
| `KKREPO_APT_SNAPSHOT_CLEANUP_INTERVAL_MS` | `300000` | 清理间隔 |
| `KKREPO_APT_SNAPSHOT_CLEANUP_INITIAL_DELAY_MS` | `120000` | 首次清理前延迟 |
| `KKREPO_APT_SNAPSHOT_CLEANUP_BATCH_SIZE` | `32` | 每轮候选数，限制为 1-256 |
| `KKREPO_APT_SNAPSHOT_CLEANUP_MIN_SNAPSHOTS` | `3` | 保留 snapshot 数；运行时下限为 3 |
| `KKREPO_APT_SNAPSHOT_CLEANUP_GRACE_SECONDS` | `86400` | 旧 snapshot 进入候选前的最小年龄 |

除非事故 runbook 明确要求，生产环境应保持 publication 和 cleanup 开启。

## Cleanup、扫描与迁移

Cleanup Policy 把 `(distribution, component, package, version)` 及其全部 architecture asset
视为一个 APT component。**保留最新版本**使用 Debian version 排序。Hosted 删除会 tombstone
该 component 的全部 architecture asset，并且每个受影响 distribution 只发布一次，使签名
metadata、Browse 和 Search 一起推进。详见 [Cleanup Policy 使用指南](../cleanup-policy-guide.md)。

全局和仓库都启用 Artifact Scanning 时，canonical `.deb` 内容会在持久化后进入扫描。生成的
`dists/`、`.apt/` snapshot、checksum 和 signature asset 不作为独立扫描候选。详见
[Artifact Scanning 使用指南](../artifact-scanning-guide.md)。

Nexus migration 支持已验证 Nexus 3.92.x-3.94.x datastore profile 下 shape-gated APT repository
definition 和 hosted package content。源端生成的 `dists/` metadata 会在目标端重建。Private
signing material 不会隐式复制：迁移后的 hosted 仓库保持 offline，直到管理员显式导入预期 key 并
重建 metadata。详见 [Nexus 迁移说明](../nexus-migration-guide.md)。

## 备份与恢复

关系数据库和 blob store 必须按一致恢复点备份。数据库保存加密 signing material、package record、
desired/published revision、immutable snapshot manifest 与 lease；blob store 保存 `.deb` bytes
和生成 snapshot asset。必须保留原 `KKREPO_CREDENTIAL_SECRET`，否则恢复后无法解密 private key。

恢复后检查 `/gpg.key`、`dists/<distribution>/InRelease`、一个压缩 Packages index 和代表性
package checksum，再从一次性客户端执行 `apt-get update` 与安装。对比 desired/published
revision；目录过期时使用受支持的 rebuild 操作，不要手改 APT 表。详见
[备份恢复指南](../backup-restore.md)。

## 排障

| 现象 | 检查项 |
| --- | --- |
| `NO_PUBKEY` 或签名无效 | 刷新 scoped `/gpg.key` keyring，检查 `signed-by`，并确认轮换后的预期 fingerprint |
| 上传成功但 `apt-get update` 看不到版本 | 检查 desired/published revision 和 `lastError`；等待 debounce 或执行有权限的 rebuild |
| 上传返回 `400` | 检查 `.deb` control identity、canonical filename/path、仓库 distribution/component/architectures 和 archive 安全上限 |
| `apt-get update` 返回 `401` | 检查 path-scoped `auth.conf`、文件模式 `0600`、仓库 READ 权限与 token/password |
| Proxy metadata 可读但 Browse/Search 为空 | Passthrough 的 checksum-verified projection 是 best effort；检查 observed Release 与 index checksum |
| Re-sign proxy 一直保留旧 snapshot | 检查上游 Release/index/package checksum 以及 10,000-package/20-GiB 投影限制 |
| 旧 by-hash 数据过早消失 | 保持 cleanup 开启，至少保留 3 个 snapshot，并把 grace period 设得长于客户端更新窗口 |

## 参考

- [客户端配置示例](../client-recipes.md#apt--debian)
- [兼容性矩阵](../compatibility-matrix.md)
- [APT 性能基线](../dev/apt-performance-baseline.md)
- [APT 开发设计与兼容性说明](../dev/apt-debian-repository-design.md)
- [Debian Repository Format](https://wiki.debian.org/DebianRepository/Format)
- [Debian `apt-secure(8)`](https://manpages.debian.org/bookworm/apt/apt-secure.8.en.html)
- [Debian `sources.list(5)`](https://manpages.debian.org/bookworm/apt/sources.list.5.en.html)
- [Sonatype APT Repositories](https://help.sonatype.com/en/apt-repositories.html)
