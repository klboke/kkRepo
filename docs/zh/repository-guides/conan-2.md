# Conan 2 仓库使用指南

kkRepo 支持 Conan 2 hosted、proxy 和 group 仓库，客户端入口保持 Nexus 兼容布局：

```text
https://nexus.example.com/repository/<repo>/
```

Hosted 用于发布私有 recipe/binary，proxy 用于缓存 Conan 2 上游，group 按成员顺序提供统一读取入口。Conan 1 不与此格式混存。

## 创建仓库

在管理端 UI 或 repository API 中创建以下 recipe：

| 用途 | Recipe | 关键配置 |
| --- | --- | --- |
| 私有发布 | `conan-hosted` | Blob store、online、write policy、严格内容校验 |
| 上游缓存 | `conan-proxy` | Conan 2 remote URL、上游凭据、metadata/content/negative TTL、auto-block |
| 统一读取 | `conan-group` | 有序 Conan hosted/proxy/group 成员 |

代理 ConanCenter 时，把 remote 配置为 `https://center2.conan.io/`。Group 只读；向 hosted 发布，通过 group 消费。

## 配置客户端与登录

把 group 作为日常读取 remote，把 hosted 作为发布 remote：

```bash
conan remote add kkrepo-group \
  https://nexus.example.com/repository/conan-group/ --force
conan remote add kkrepo-hosted \
  https://nexus.example.com/repository/conan-hosted/ --force

conan remote login kkrepo-hosted alice -p "$KKREPO_CONAN_PASSWORD"
```

Conan 客户端会用提供的凭据交换短生命周期、仓库作用域的 bearer token。密码和自动化 scoped credential 不应进入源码。显式错误、过期或已撤销凭据返回 `401`，不会降级为匿名访问。

## 构建与发布

使用标准 Conan 2 CLI 构建 recipe 与 binary：

```bash
conan create . \
  --name=acme-lib \
  --version=1.0.0 \
  --user=acme \
  --channel=stable

conan upload 'acme-lib/1.0.0@acme/stable:*' \
  -r=kkrepo-hosted \
  --confirm
```

Conan 会把每个 recipe/package revision 拆成多个文件上传。kkRepo 先把文件写入持久 staging，只有最后到达的 `conanmanifest.txt` 能校验全部文件 checksum 时，RREV/PREV 才整体可见。重试可以续传同一 upload；相同 identity 对应不同内容会失败关闭。UI 和 Components API 上传也进入同一 manifest-gated 发布路径。

## 列出、下载、安装与删除

读取使用 group：

```bash
conan list 'acme-lib/1.0.0@acme/stable:*' -r=kkrepo-group
conan download 'acme-lib/1.0.0@acme/stable:*' -r=kkrepo-group
conan install --requires='acme-lib/1.0.0@acme/stable' \
  -r=kkrepo-group \
  --build=missing
```

只在 hosted 删除：

```bash
conan remove 'acme-lib/1.0.0@acme/stable:*' \
  -r=kkrepo-hosted \
  --confirm
```

Lockfile 固定的精确 RREV/PREV 可继续寻址。Recipe、RREV、package、PREV 或全部 package 删除都会重算对应 latest，并失效 group source binding。与 Nexus 3.94 一致，Conan 协议路由不合成 `HEAD`；文件 `GET` 与 byte Range 可用。

## Browse、Search 与 Usage

Browse 使用 Nexus 3.94 的展示树，和协议/blob 路径有意区分：

```text
<user>/<name>/<version>/<channel>#<rrev>/conanfile.py
<user>/<name>/<version>/<channel>#<rrev>/packages/<package-id>/revisions/<prev>/files/conan_package.tgz
```

缺省 user/channel 展示为 `_`。最终 asset、component、Conan typed row、latest、scan outbox 与该 Browse path 在同一写入事务内投影并持久化。正常 Browse 请求只读取带索引的 `browse_node`，不会反解 storage path，也不会在首次读取时补映射；staging 和 proxy discovery 路径始终隐藏。

Search 以完整 recipe version 为 component，并保留规范 user/channel namespace。Usage 页面提供 remote、login、list、download、install 与 hosted upload 命令，但不会回显已存凭据。

## Cleanup、安全扫描与多副本语义

Cleanup 把完整 Conan recipe version（含全部 RREV/PREV 文件）作为一个 subject，并使用 Conan 版本顺序；不会逐个删除 manifest member 后留下半可见 revision。

仓库启用制品扫描后，每个 package archive 作为 `CONAN_PACKAGE`。异步 scanner 接收不可变 package archive 和精确、独立校验的 `conaninfo.txt` sidecar；recipe/source/manifest/metadata 不成为独立扫描候选。Audit/Enforce、waiver、SBOM 复用和下载判定继续走共享扫描基建，上传请求不会同步调用 scanner。

Upload session、短期 bearer token、repository revision、proxy/group source binding、lease、fencing token、cleanup claim 和 scan task 都持久化在 MySQL/PostgreSQL；blob/staging bytes 留在 OSS/S3。其他副本可以安全续跑或接管，进程内 cache 只作可重建优化。

## Nexus 迁移

Metadata migration 可识别 Conan hosted/proxy/group definition。只有 source 被证明为 Nexus 3.94、具备规范 Conan 2 revision path、manifest 和 SHA-1 的 datastore shape 时才允许 content migration。Conan 1、未知/混合 shape、不完整 revision、损坏 Blob 和不可恢复 proxy secret 都生成 manual action，不猜测导入。

Hosted content 通过正常 manifest-gated writer 导入；proxy cache 仍需管理员显式选择。迁移支持 dry-run、resume、checksum、幂等重放和报告，并在发布事务中直接写入 Nexus 对齐 Browse 投影，不依赖后续 backfill。

## 故障排查

| 现象 | 检查项 |
| --- | --- |
| 登录返回 `401` | 检查仓库 URL、用户名/密码或 scoped token、过期时间、仓库权限和匿名策略 |
| 上传后不可见 | 确认 revision 包含最终且合法的 `conanmanifest.txt`，并声明了全部文件/checksum |
| Binary 上传返回 `404` | 先发布父 recipe RREV，再上传 binary PREV |
| Proxy install 失败 | 检查 Conan 2 上游 URL、凭据、出站策略、redirect、negative cache/auto-block 和 checksum 漂移 |
| Group 文件不一致 | 检查成员顺序和 source-binding 诊断；一个 RREV/PREV 的全部文件必须来自同一成员 |
| Browse 看不到 revision | 检查发布失败原因；正常 Browse 有意不为缺失投影做回填 |

## 参考资料

- [Conan 2 remotes](https://docs.conan.io/2/reference/config_files/remotes.html)
- [Conan 2 upload](https://docs.conan.io/2/reference/commands/upload.html)
- [Conan 2 list](https://docs.conan.io/2/reference/commands/list.html)
- [Conan 2 revisions](https://docs.conan.io/2/tutorial/versioning/revisions.html)
- [Conan 实现设计](../dev/conan-repository-design.md)
- [Conan 性能基线](../dev/conan-performance-baseline.md)
