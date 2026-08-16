# Alpine / APK 仓库使用指南

kkRepo 支持 Alpine Package Keeper v2 的 hosted、proxy 和 group 仓库，入口保持 Nexus 兼容：

```text
https://nexus.example.com/repository/<repo>/
```

Hosted 用于发布私有 `.apk` 和签名 `APKINDEX.tar.gz` 快照；proxy 可原样保留上游字节，或先验签再投影并重新签名 v2 index；group 按成员顺序发布一个本地签名的统一读取视图。

## 创建仓库

在 Admin UI 或 repository API 创建以下 recipe：

| 用途 | Recipe | 关键配置 |
| --- | --- | --- |
| 私有发布 | `alpine-hosted` | distribution/channel/architecture allowlist、write policy、RSA key filename/type、description |
| 上游缓存 | `alpine-proxy` | remote、TTL/negative cache/auto-block、`PASSTHROUGH` 或已验签 `RESIGN`、stale policy、上游公钥 |
| 统一读取 | `alpine-group` | 有序 Alpine member、namespace allowlist、仓库作用域 RSA signing key |

Hosted 与 group 始终发布本地签名的 v2 index。每个 `distribution/channel/repository-architecture` 都是独立不可变快照 namespace，例如 `v3.23/main/x86_64`。`PASSTHROUGH` proxy 保持上游 index/package 字节；`RESIGN` proxy 必须先使用配置的上游公钥验签，才能把校验后的 package record 投影到本地快照。为兼容 Nexus，`PASSTHROUGH` proxy 也可以作为 group member：group 遵循该 proxy 配置的上游验签策略，以 group key 签名聚合索引，并在首次下载时按 group snapshot 中绑定的精确 index identity 与 size 校验 package。关闭上游验签是管理员显式选择 transport trust，不会跳过 package checksum 校验。

## 信任仓库公钥

使用仓库管理读取权限下载公钥，并按配置中的精确文件名安装：

```bash
curl -u alice:"$KKREPO_PASSWORD" \
  -o kkrepo-alpine-group.rsa.pub \
  https://nexus.example.com/internal/repositories/alpine-group/alpine/public-key

sudo install -m 0644 kkrepo-alpine-group.rsa.pub \
  /etc/apk/keys/kkrepo-alpine-group.rsa.pub
```

私钥会加密保存且读取 API 永不回显。Admin UI 会展示 active filename、fingerprint、revision、signature type、namespace 发布状态，并提供 rebuild 与显式生成/导入轮换操作。所有副本和备份必须保持同一个 `KKREPO_CREDENTIAL_SECRET`，否则恢复后的私钥无法解密。

## 配置和使用 apk

Group 地址不带 architecture；`apk` 会追加当前架构和 `APKINDEX.tar.gz`：

```bash
echo 'https://nexus.example.com/repository/alpine-group/v3.23/main' \
  | sudo tee /etc/apk/repositories

apk update
apk search -x acme-agent
apk policy acme-agent
apk fetch acme-agent=1.2.3-r0
apk add acme-agent=1.2.3-r0
apk info -e acme-agent=1.2.3-r0
```

认证仓库应使用受保护的客户端配置，或由反向代理注入仓库作用域凭据。如果必须把 userinfo 写入 URL，请限制 `/etc/apk/repositories` 权限，且不要提交到代码仓库。

## 发布和删除 package

APK v2 的 package/version/repository-architecture coordinate 不可变。把规范文件名上传到 hosted namespace：

```bash
curl -u alice:"$KKREPO_PASSWORD" \
  -H 'Content-Type: application/vnd.alpine.apk' \
  --upload-file acme-agent-1.2.3-r0.apk \
  https://nexus.example.com/repository/alpine-hosted/v3.23/main/x86_64/acme-agent-1.2.3-r0.apk
```

UI 与 Components API 进入同一协议感知发布路径，需要 package file、distribution、channel 和 repository architecture。kkRepo 会安全解析 `.PKGINFO`，验证原始压缩 data member 的 SHA-256，以原始压缩 control member 计算官方 Q1 identity，保存 package，并原子推进新的签名 index。相同 coordinate 上传不同字节会失败关闭；相同字节重试幂等。

只在 hosted 删除：

```bash
curl -u alice:"$KKREPO_PASSWORD" -X DELETE \
  https://nexus.example.com/repository/alpine-hosted/v3.23/main/x86_64/acme-agent-1.2.3-r0.apk
```

旧 package blob 会保持 snapshot pin，直到所有保留的签名 generation 都不再引用它。因此客户端只会看到完整旧快照或完整新快照，不会看到 index 指向 404 package 的中间态。

## Browse、Search、Cleanup 与安全扫描

Browse 投影 distribution/channel/repository architecture/package/version，并隐藏生成快照、proxy staging、lease 和 tombstone。Search 会返回 package name/version、package architecture、repository architecture、Q1 identity、data/whole-blob SHA-256、origin、license，以及适用时的 source member。

对于 `noarch` upload，生成索引的 `A:` 字段使用 URL namespace 中的 repository architecture，确保 `apk` 能定位 canonical package path；原始 `.PKGINFO` architecture 独立保留，并继续显示在 Browse 与搜索详情中。

Cleanup 把一个 package coordinate 作为一个 subject，使用 apk-tools version ordering，先 tombstone typed row，再发布所有受影响 namespace，最后才允许 GC 字节。Group source binding 保证 index record 与 package bytes 始终来自同一个 member。

开启制品扫描后，`.apk` 分类为 `ALPINE_PACKAGE`。异步 scanner 通过有界、不执行脚本的 archive inspection 编目 installed database 与 payload tree。上传请求不会同步运行 Syft/Grype；Audit/Enforce、waiver、SBOM 复用与下载阻断继续使用共享扫描基建。

## 多副本与迁移语义

Package row、desired/published revision、不可变 snapshot、加密 signing-key revision、proxy state、group binding、tombstone、lease 与 fencing token 持久化在 MySQL/PostgreSQL；Blob 留在 OSS/S3/File。进程内 cache 由版本/TTL 失效，不作为正确性真相。任一副本崩溃后，其他副本可以接管发布，不暴露 partial 或 unsigned generation。

Nexus definition migration 可识别 Alpine hosted/proxy/group。Hosted content 只在精确验证的 Nexus 3.94 datastore shape、规范 package path、identity、size 与 SHA-256 均可证明时为 `FULL`；生成 index 会被过滤并重建。Signing private key 与 proxy secret 不会被猜测；材料不可用时 target 保持 offline 并报告 manual action。Import 支持 dry-run、resume、checksum 与幂等重放。

## 限制与排障

| 现象 | 检查项 |
| --- | --- |
| `UNTRUSTED signature` | 安装 active hosted/group 公钥的精确文件名；有意轮换后更新客户端 key |
| 上传后不可见 | 检查 namespace allowlist 与发布状态/错误，并 rebuild 精确的 `distribution/channel/architecture` |
| 上传被拒绝 | 检查 APK v2、规范文件名、`.PKGINFO` identity、path architecture、datahash、write policy 与 add/edit 权限 |
| Proxy refresh 失败 | 检查 remote、出站策略、validator、上游 key、签名/checksum 漂移、auto-block 与 stale policy |
| Group 读取错字节 | 检查 member order、重复 coordinate 诊断和持久化 source binding |

本 v2 实现不提供 `Packages.adb` 和 APK v3 package container。DSA index key、unsigned hosted/group index、任意生成目录列表与私钥下载均有意不支持。

## 参考资料

- [Alpine package specification](https://wiki.alpinelinux.org/wiki/Apk_spec)
- [Alpine repositories](https://wiki.alpinelinux.org/wiki/Repositories)
- [apk-tools](https://gitlab.alpinelinux.org/alpine/apk-tools)
- [Alpine 实现设计](../dev/alpine-apk-repository-design.md)
- [Alpine 性能基线](../dev/alpine-performance-baseline.md)
