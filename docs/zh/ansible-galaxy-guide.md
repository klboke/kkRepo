# Ansible Galaxy 仓库使用指南

kkrepo 支持面向 Ansible Galaxy v3 **collection** 的 hosted、proxy 和 group 仓库，客户端入口保持 Nexus 布局：

```text
https://nexus.example.com/repository/<repo>/
```

Hosted 用于发布私有 collection，proxy 用于缓存上游 Galaxy server，group 作为聚合 hosted/proxy 的统一读取入口。Galaxy v1 role、GitHub role import 和 `ansible-galaxy role install` 不属于该仓库格式的支持范围。

## 创建仓库

在管理端 UI 或 repository API 中创建以下 recipe：

| 用途 | Recipe | 关键配置 |
| --- | --- | --- |
| 私有发布 | `ansiblegalaxy-hosted` | Blob store、online、write policy、严格内容校验 |
| 上游缓存 | `ansiblegalaxy-proxy` | Remote Galaxy 根地址、上游凭据、metadata/content/negative TTL |
| 统一读取 | `ansiblegalaxy-group` | 有序 Ansible Galaxy hosted/proxy/group 成员 |

代理 public Galaxy 时，remote root 配置为 `https://galaxy.ansible.com/` 并保留结尾 `/`。Proxy 先读取上游 discovery 定位 v3 base，再通过 Galaxy NG 的 `plugin/ansible/content/published/collections/index/...` 路径读取 collection 元数据。较短的 `/api/v3/collections/...` 只作为 kkrepo/Nexus 客户端兼容别名，不用于上游元数据回源。

## 配置 `ansible.cfg`

安装/下载使用 group，发布使用 hosted：

```ini
[galaxy]
server_list = kkrepo_group, kkrepo_hosted

[galaxy_server.kkrepo_group]
url = https://nexus.example.com/repository/ansible-group/
token = GenericToken.REDACTED
priority = 1

[galaxy_server.kkrepo_hosted]
url = https://nexus.example.com/repository/ansible-hosted/
token = GenericToken.REDACTED
priority = 2
```

URL 必须以 `/` 结尾。`ansible-galaxy` 会先读取 repository discovery，再选择服务端声明的 Galaxy v3 路径。

自动化场景建议在 **My Token** 创建 `GenericToken`，并确保凭据不进入源码。kkrepo 也接受 HTTP Basic，以及 Ansible route 内与 Nexus 兼容的 Base64 `username:password`。Base64 不是加密，只应作为 Nexus 客户端兼容方式。当前 ansible-core 使用 `Authorization: Bearer`，Ansible 2.9 使用 `Authorization: Token`；kkrepo 对这些 route-scoped credential 同时兼容两种 scheme。显式错误凭据返回 `401`，不会降级为匿名访问。

## 构建与发布

使用标准 CLI 创建和构建 collection：

```bash
ansible-galaxy collection init acme.tools
cd acme/tools
ansible-galaxy collection build --output-path ../../dist
```

把不可变 collection version 发布到 hosted：

```bash
ansible-galaxy collection publish ../../dist/acme-tools-1.0.0.tar.gz \
  --server https://nexus.example.com/repository/ansible-hosted/ \
  --token "$KKREPO_ANSIBLE_TOKEN" \
  --import-timeout 120
```

部分 Ansible 2.9 版本提供的是 `--api-key` 而不是 `--token`，此时把同一个 token 值传给客户端支持的选项。需要调用方自行轮询持久化 import task 时可以使用 `--no-wait`。

每个 `(namespace, name, version)` 都不可变。Multipart 路径从强制 canonical archive filename 得到 coordinate，并在写 staging 前拒绝已发布或已被活动任务预留的版本；创建 task 时会在数据库中原子预留 coordinate，因此并发中的重复请求不会再得到第二个 `202`，竞态中失败请求产生的 staging 引用会立即解除。Multipart task 创建、直接 PUT 和 worker promotion 共用同一个 fenced coordinate lease，并在 lease 内复查 immutable version 与 active reservation。Task 保留发布来源标识，使崩溃恢复可幂等完成。即使仓库 write policy 允许更新，重复发布同一 version 也会失败；请在 `galaxy.yml` 中提升版本。

既有 Nexus 自动化还可以使用兼容的直接上传路径：

```bash
curl -u alice:"$KKREPO_PASSWORD" \
  --upload-file acme-tools-1.0.0.tar.gz \
  https://nexus.example.com/repository/ansible-hosted/api/v3/plugin/ansible/content/published/collections/artifacts/acme-tools-1.0.0.tar.gz
```

CLI multipart、直接 PUT、管理端 UI 和 Components API 最终都进入同一套 archive 校验与不可变发布服务。

## 安装与下载

通过 group 统一解析私有 hosted collection 和 public Galaxy proxy：

```bash
ansible-galaxy collection install acme.tools:1.0.0 \
  --server https://nexus.example.com/repository/ansible-group/ \
  --token "$KKREPO_ANSIBLE_TOKEN"
```

`requirements.yml` 支持精确版本、范围、依赖和显式 source：

```yaml
collections:
  - name: acme.tools
    version: ">=1.0.0,<2.0.0"
    source: https://nexus.example.com/repository/ansible-group/
```

```bash
ansible-galaxy collection install -r requirements.yml --force
ansible-galaxy collection download acme.tools:1.0.0 \
  --server https://nexus.example.com/repository/ansible-group/ \
  --token "$KKREPO_ANSIBLE_TOKEN"
```

两个成员包含同一 version 时，以 group 成员顺序为准。kkrepo 会保存 source binding，保证 version metadata、依赖、签名、checksum 和 artifact 字节持续来自同一个成员。

## 存储与完整性边界

关系数据库只保存有上限的元数据和协调状态：collection coordinate、checksum、size、查询投影、依赖投影、import task、proxy validator/negative cache、group binding 和 fenced lease。

Collection tarball（其中包含完整 `MANIFEST.json`/`FILES.json`）和需要保留的 signature payload 存在配置的 blob store；数据库只保存引用、hash 和有界投影，不保存大 JSON。当前硬上限包括 version metadata 64 KiB、dependency projection 192 KiB、proxy protocol projection 256 KiB。在线上游 JSON 会流式解析，只投影客户端必需字段，不保留无关的大字段；未来任何确实需要原样保留的大 JSON 都必须写入 blob，并在数据库中只保存 hash/引用。

发布前会校验：

- 请求与 artifact SHA-256。
- tar/gzip 结构和规范 collection filename。
- `MANIFEST.json`、其引用的 `FILES.json` 以及逐文件 checksum。
- Namespace、collection name、SemVer、dependencies 和 `requires_ansible`。
- Archive path/link、entry 数、展开大小、压缩比和 parser 限制。

Proxy artifact 按上游 SHA-256 固定。已存在版本的上游 checksum 发生变化时 fail closed，不会覆盖本地缓存内容。

Proxy collection/version metadata 请求只持久化有界查询投影和 artifact identity，不会提前下载 collection tarball。第一次 artifact `GET` 才会在共享 lease 下下载并校验 archive，然后写入 blob store。Group 会在下载前持久化选定成员、filename 和 checksum，并在落地后把同一 binding 升级为 materialized version 引用，避免 metadata 与 artifact 请求落到不同成员或不同副本选择结果。

## 多副本语义

Import task、proxy state、source binding、lease、fencing token 和 repository revision 共享存储在 MySQL/PostgreSQL，artifact/staging bytes 共享存储在 OSS/S3。因此 publish 或 proxy miss 可由另一副本恢复/接管；进程内 cache 和 executor queue 仅是可重建优化。

发布请求只把正文流式写入一次 staging blob，持久化 `WAITING` task 后即返回 `202`，archive 检查不再占用请求线程。有界 worker 使用 `FOR UPDATE SKIP LOCKED` 原子批量领取 task，通过受限 inspection pool 校验 archive，并直接提升既有 staging blob 引用；promotion 阶段不再二次上传或重复执行 hash pass。慢速检查、proxy 下载和持久化期间会续租带 fencing token 的数据库 lease；同节点请求再通过 single-flight 合并，等待方使用指数退避，避免固定频率轮询数据库。

Version-list 读取一次批量 revision snapshot，并使用按 revision 校验的本地 cache；其有效期还受该 coordinate 所有 proxy inventory 中最早的 metadata 到期时间约束。Collection publish/delete 只递增精确 coordinate revision，并仅失效受影响的 group binding，不再扫描或重写仓库全部版本。Proxy version inventory 使用有上限的规范化行保存，只有 TTL 到期后才重新遍历上游全部分页。Proxy JSON 以有界流方式解析，只把 discovery、version、artifact、dependency 和客户端所需 metadata 投影到关系数据库 JSON 列。Artifact 热路径使用轻量 asset/blob 引用查询并直接执行 blob `GET`，不再先发一次对象存储 `stat`。

每个副本还会运行有界 staging cleanup。默认通过 `FOR UPDATE SKIP LOCKED` 领取超过 24 小时的 `.ansible/staging/` asset 行，保留仍属于 `WAITING`/`RUNNING` import task 的内容，只解除 task 缺失或已经终态的引用。只有最后一个 asset 引用消失后才把 blob 交给全局 GC，因此进程在 staging、task 创建、task 完成或请求清理之间崩溃都不会永久泄漏 collection bytes。运维可通过 `KKREPO_ANSIBLE_STAGING_CLEANUP_*` 环境变量调整 interval、initial delay、batch size 和 grace period；grace period 的安全下限为 5 分钟。

另一个幂等 cleanup 会限制过期 negative/page cache、终态 task 和过期 lease 行数量。它在每个副本上按小批次执行；默认保留过期 proxy page 24 小时、完成/失败 task 30 天、过期 fencing lease 7 天。

| 环境变量 | 默认值 | 用途 |
| --- | ---: | --- |
| `KKREPO_ANSIBLE_IMPORT_WORKER_CONCURRENCY` | `4` | 每副本并发处理 collection import 的上限（强制限制为 1-64） |
| `KKREPO_ANSIBLE_ARCHIVE_MAX_CONCURRENT_INSPECTIONS` | `4` | 每副本并发 archive 检查上限 |
| `KKREPO_ANSIBLE_ARCHIVE_INSPECTION_PERMIT_WAIT_MS` | `5000` | 容量繁忙时返回可重试错误前的等待时间 |
| `KKREPO_CACHE_ANSIBLE_VERSION_LIST_ENABLED` | `true` | 启用按 revision 校验、可重建的 version-list cache |
| `KKREPO_ANSIBLE_CLEANUP_BATCH_SIZE` | `256` | 每批最多清理的共享状态行数 |
| `KKREPO_ANSIBLE_CLEANUP_MAX_BATCHES` | `8` | 每轮 cleanup 最多执行的批次数 |
| `KKREPO_ANSIBLE_PROXY_PAGE_RETENTION_HOURS` | `24` | 过期分页状态保留时间 |
| `KKREPO_ANSIBLE_TERMINAL_TASK_RETENTION_DAYS` | `30` | 完成和失败 import task 保留天数 |
| `KKREPO_ANSIBLE_EXPIRED_LEASE_RETENTION_DAYS` | `7` | 过期 fencing lease 保留天数 |

## Nexus 迁移

Repository metadata migration 可识别 Nexus 3.93+ `ansiblegalaxy-hosted`、`ansiblegalaxy-proxy`、`ansiblegalaxy-group`。仅当 Nexus 3.93.x-3.94.x source profile 能证明预期原生 datastore shape 时，才导入 hosted collection data。版本未知、collection identity 不完整、完整性信息缺失或 shape 漂移时生成 manual action，不做猜测式导入。

Proxy cache migration 需要在 **Optional proxy repositories** 中显式选择，并要求 source plan 为 `FULL`。Proxy secret 被遮蔽或缺失时，目标 proxy 保持 offline，直到管理员补齐凭据。迁移支持 preflight/dry-run、resume、checksum、幂等和报告。

## 故障排查

| 现象 | 检查项 |
| --- | --- |
| Discovery 或 install 返回 `404` | 确认 recipe 为 `ansiblegalaxy-*`、仓库 online、URL 以 `/` 结尾，group 包含当前主体可见的 Ansible 成员 |
| Publish 返回 `401` | 检查 token domain、过期时间/scope、Ansible 2.9 `--api-key` 与当前版 `--token`，或 Base64 `username:password` 兼容值 |
| Publish 返回 conflict | Collection version 已存在且不可变；提升 `galaxy.yml` 的 `version` |
| Import task 失败 | 根据 task message 检查 filename、SHA-256、manifest/files、archive 安全或大小限制 |
| Proxy install 失败 | 检查上游 discovery、出站/SSRF policy、credential、redirect、TTL 和上游 artifact checksum |
| Group metadata 与 artifact 不一致 | 检查成员顺序与 source-binding 诊断；同一 coordinate 不应由客户端跨多个独立 server 拼装 |

## 参考资料

- [Ansible：Distributing collections](https://docs.ansible.com/projects/ansible/latest/dev_guide/developing_collections_distributing.html)
- [Ansible：Installing collections](https://docs.ansible.com/projects/ansible-core/devel/collections_guide/collections_installing.html)
- [Ansible：Collection metadata](https://docs.ansible.com/projects/ansible/latest/dev_guide/collections_galaxy_meta.html)
- [Galaxy NG：API v3](https://docs.ansible.com/projects/galaxy-ng/en/latest/community/api_v3.html)
- [Sonatype：Configure Ansible with Nexus](https://help.sonatype.com/en/configure-ansible-with-nexus.html)
- [Ansible Galaxy 开发设计与兼容说明](dev/ansible-galaxy-repository-design.md)
