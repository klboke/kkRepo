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
受影响仓库升级后，在设置页确认深度并保存一次，即可初始化当前 Yum 配置，并触发现有 RPM
的元数据重建。此后仅在深度实际改变时才由配置保存触发重建；重复保存或修改其他设置不会触发。
如果源导出没有包含深度设置，请手动填写。随后执行 `dnf clean metadata` 并重试。
重建沿用数据库持久化队列，直接读取已提交的
仓库配置，不等待其他副本缓存失效。切换深度后，旧元数据目录会改为空索引；RPM 保留原位，
由新目录的元数据索引。

RPM 上传、删除仅为受影响的元数据目录提交维护任务。例如深度为 `1` 时，变更
`fedora-45/Packages/demo.rpm` 只读取并重建 `fedora-45/`，`fedora-44/` 的索引保持不变。
Group 读取也仅查询请求目录。同一目录的待处理变更共享持久化任务标记。目录任务键使用可逆的 UTF-8 十六进制编码，
避免 MySQL 队列排序规则把大小写或重音不同的 URL 合并。后台任务通过数据库
行锁串行处理同一仓库，防止目录更新与全仓修复、配置变更并发覆盖。删除最后一个 RPM 后，
该目录会发布空索引。

仅旧配置首次初始化、深度变更、历史全仓任务，以及按过期深度提交的任务恢复使用全仓重建。
目录过长、编码后超出共享队列 512 字符的 scope 限制时，也回退为全仓重建。深度为 `0` 时所有包共用
一个索引，因此更新它仍需处理全仓 RPM。已有的 RPM 元数据会复用；缺失时才读取 RPM 头部
并保存。修改深度前应先升级所有副本；旧版本后台任务尚未实现目录范围和新的重建协调机制。

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
