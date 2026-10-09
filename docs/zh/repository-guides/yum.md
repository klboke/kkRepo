# Yum 仓库使用指南

kkRepo 支持 Yum/DNF `hosted`、`proxy` 和 `group` 仓库。Hosted 接收 RPM 上传并生成仓库
metadata，proxy 缓存上游 RPM repository，group 提供统一的有序 `baseurl`。

## 创建仓库

| 用途 | Recipe | 推荐配置 |
| --- | --- | --- |
| 私有 RPM | `yum-hosted` | Blob store、write policy、strict validation |
| 上游缓存 | `yum-proxy` | Remote repository root 和缓存 TTL |
| 统一读取入口 | `yum-group` | Hosted 排在 proxy 前面 |

Remote repository root 应能提供 `repodata/repomd.xml`，不要把 proxy 指向单个 RPM 或 metadata
文件。

## 配置 Yum 或 DNF

创建 `/etc/yum.repos.d/kkrepo.repo`：

```ini
[kkrepo]
name=kkRepo
baseurl=https://nexus.example.com/repository/yum-group/
enabled=1
gpgcheck=0
```

示例关闭 package signature 校验，只因为密钥管理由部署决定。生产环境的 RPM 已由可信密钥
签名时，应启用 `gpgcheck=1` 并配置 `gpgkey`。

验证 endpoint：

```bash
dnf clean metadata
dnf makecache --disablerepo='*' --enablerepo=kkrepo
dnf install --enablerepo=kkrepo demo-package
```

## 发布 RPM

直接上传到 hosted，或使用 Admin UI/component upload：

```bash
curl -u alice:"$KKREPO_PASSWORD" \
  --upload-file demo-1.0.0-1.x86_64.rpm \
  https://nexus.example.com/repository/yum-hosted/Packages/demo-1.0.0-1.x86_64.rpm
```

只有 hosted 接受发布。Package path 应保持稳定，使外部脚本和仓库 metadata 指向同一 asset。

## 按发行版生成子目录元数据

如果 DNF 的 baseurl 以 `/fedora-$releasever/` 结尾，请将 hosted 仓库的 **元数据目录深度
（Repodata Depth）** 设为 `1`（API：`"yum": {"repodataDepth": 1}`）。默认值 `0` 在仓库根目录
生成元数据；`2` 则在 `fedora-45/x86_64/` 这样的目录生成。RPM 可以放在更深的目录，但不能上传
到比配置深度更浅的位置。

```bash
curl -u alice:"$KKREPO_PASSWORD" --upload-file demo-1.0.0-1.x86_64.rpm \
  https://nexus.example.com/repository/yum-hosted/fedora-45/
```

元数据会异步生成在 `fedora-45/repodata/`，其中包路径相对于 `fedora-45/`。各发行版分别维护
包索引。Group 使用 hosted 成员中相同的子目录端点，请为这些成员配置一致的深度。

Nexus 迁移会保留 `yum.repodataDepth`；已迁移仓库也会从保留的源配置中读取，无需重新导入包。
受影响仓库升级后，在设置页确认深度并保存，即可触发现有 RPM 的元数据重建。如果源导出没有
包含该设置，请手动填写。随后执行 `dnf clean metadata` 并重试。重建沿用数据库持久化队列和
共享仓库配置，支持多副本部署。

行为参考：[Nexus Repodata Depth](https://help.sonatype.com/en/yum-repositories.html)。

## 仓库行为

- Hosted 解析 RPM identity，并从已提交 package 重建 `repodata`。
- Proxy 使用 validator 与 TTL 缓存 `repomd.xml`、引用 metadata 和 RPM file。
- Group 按顺序解析成员，并提供 group-scoped metadata 与 package path。
- Browse 与 Search 展示 RPM name、epoch、version、release、architecture 和 asset。

## 运维与排障

发布后先刷新客户端 metadata，再排查缺失 package。`repomd.xml` 可用但引用文件不可用时，
检查仓库权限与反向代理缓存。删除部署清单仍引用的 RPM version 前先执行 cleanup preview。

## 相关文档

- [Yum 客户端配置示例](../client-recipes.md#yum)
- [兼容性矩阵](../compatibility-matrix.md#仓库格式矩阵)
- [DNF repository 配置参考](https://dnf.readthedocs.io/en/latest/conf_ref.html)
