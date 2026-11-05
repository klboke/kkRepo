# Arch Linux / Pacman 仓库指南

kkRepo 实现 pacman 的扁平仓库布局：包文件、包数据库、文件列表数据库，支持 hosted、proxy、
group 三种仓库类型。

## 仓库类型

| 场景 | Recipe | 说明 |
| --- | --- | --- |
| 私有包仓库 | `arch-hosted` | 上传 `.pkg.tar.zst/.xz/.gz/.bz2`，自动生成数据库 |
| 镜像 Arch 仓库 | `arch-proxy` | 缓存上游包、数据库和签名 |
| 合并多个仓库 | `arch-group` | 按成员顺序合并，第一个在线成员优先 |

Proxy 远端必须指向包含包文件和数据库的目录，例如：

```text
https://geo.mirror.pkgbuild.com/core/os/x86_64/
```

## 客户端配置

```ini
[kkrepo-demo]
SigLevel = Required DatabaseRequired
Server = https://nexus.example.com/repository/arch-group
```

先安装仓库公钥：

```bash
curl -fsSL https://nexus.example.com/repository/arch-hosted/public-key \
  -o /tmp/kkrepo-arch.asc
sudo pacman-key --add /tmp/kkrepo-arch.asc
sudo pacman-key --lsign-key "$(gpg --show-keys --with-colons /tmp/kkrepo-arch.asc \
  | awk -F: '/^fpr:/ { print $10; exit }')"
```

未配置签名密钥时请使用 `SigLevel = Optional TrustAll` 或省略 `DatabaseRequired`。

kkRepo 提供：

```text
/repository/arch-hosted/demo.db
/repository/arch-hosted/demo.db.tar.gz
/repository/arch-hosted/demo.files
/repository/arch-hosted/demo-1.0.0-1-x86_64.pkg.tar.zst
```

配置 OpenPGP 签名密钥后，kkrepo 会生成 `<repo>.db.sig` 和 `<repo>.files.sig`。公钥通过
`/repository/<repo>/public-key`（或 `/repository/<repo>/gpg.key`）提供。包签名是独立的：
上传 `<package>.pkg.tar.zst.sig`，pacman 会在包旁边下载它。

## 发布

```bash
curl -u admin:password \
  -T demo-1.0.0-1-x86_64.pkg.tar.zst \
  https://nexus.example.com/repository/arch-hosted/demo-1.0.0-1-x86_64.pkg.tar.zst
```

上传时会解析包内 `.PKGINFO`，并校验文件名
`<pkgname>-<pkgver>-<pkgrel>-<arch>.pkg.<compression>`。依赖、provides、conflicts、license、
packager、builddate 等字段会写入生成的数据库。

## 签名密钥

导入 ASCII-armored OpenPGP 私钥，或生成新密钥：

```http
PUT /internal/repositories/arch-hosted/arch/signing-key
Content-Type: application/json

{ "privateKey": "-----BEGIN PGP PRIVATE KEY BLOCK-----\n...", "passphrase": "可选" }
```

```json
{ "generate": true }
```

私钥使用共享凭据密钥加密存储，API 不会返回私钥。轮换后，公钥接口会同时返回当前和上一把公钥。
`GET /internal/repositories/arch-hosted/arch/status` 返回当前 revision/fingerprint，
`GET .../arch/public-key` 下载公钥。

启用密钥后：

- 生成 `<repo>.db.sig`、`<repo>.files.sig` 分离签名；
- 签名时间戳确定，空仓库使用密钥创建时间，因此同一包集合在各副本上产生相同签名和 ETag；
- group 使用自己的密钥签合并后的数据库；
- proxy 原样透传上游数据库和签名。

包签名上传：

```bash
curl -u admin:password \
  -T demo-1.0.0-1-x86_64.pkg.tar.zst.sig \
  https://nexus.example.com/repository/arch-hosted/demo-1.0.0-1-x86_64.pkg.tar.zst.sig
```

kkrepo 原样提供上传的 `.sig`，不校验、也不写入数据库 `%PGPSIG%`。

## 数据库生成

hosted 仓库按需生成确定性的 `<repo>.db`、`<repo>.db.tar.gz`、`<repo>.files`、
`<repo>.files.tar.gz`：

- 按包名、版本、架构排序；
- gzip 时间戳为 epoch，文件属主固定，因此各副本生成相同字节和 ETag；
- 文件列表数据库目前输出空 `%FILES%`，不影响正常 pacman 安装/升级。

## Proxy / Group

- Proxy 原样缓存上游包、数据库别名和签名。
- Group 读取成员数据库并解析 `desc`，按成员顺序合并后重新生成数据库；包文件按成员顺序回源。
- 支持嵌套 group 和循环检测。

## 已知限制

- 已支持 OpenPGP 数据库签名和密钥导入/生成；包签名只提供下载，不校验，也不写入 `%PGPSIG%`。
- `.files` 数据库暂不包含真实文件列表。
- 不支持 `.pkg.tar.lz4/.lzo/.lrz/.lz` 上传解析（可通过 proxy 原样提供）。
- proxy 缓存暂不回写 component 索引，Browse/Search 可能看不到未重新索引的包。
- Nexus 没有原生 Arch Linux 格式，因此不涉及该格式的 Nexus 内容迁移。

## 相关文档

- [repo-add 手册](https://man.archlinux.org/man/repo-add.8)
- [设计说明](../../en/dev/arch-repository-design.md)
