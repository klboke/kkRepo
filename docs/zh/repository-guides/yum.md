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
