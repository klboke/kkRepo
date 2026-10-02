# Docker / OCI 仓库使用指南

kkRepo 支持 Docker Registry HTTP API V2 和 OCI Distribution 的 `hosted`、`proxy`、`group`
仓库。Docker 客户端使用 registry `/v2/...` route，不使用普通制品的
`/repository/<repo>/...` URL。

## 创建仓库

| 用途 | Recipe | 推荐配置 |
| --- | --- | --- |
| 私有 image/artifact | `docker-hosted` | Blob store、write policy、connector/routing 配置 |
| 上游 pull-through cache | `docker-proxy` | Remote registry、凭据、缓存 TTL |
| 统一 pull 入口 | `docker-group` | Hosted 排在 proxy 前面 |

Docker Hub 使用 `https://registry-1.docker.io/` 这类 registry endpoint；kkRepo 会处理官方
image 的客户端可见 `library` namespace 行为。

## 选择访问布局

共享入口部署把 kkRepo 仓库名放在 image path 第一个 segment：

```text
<host>:<shared-port>/<repo>/<image>:<tag>
```

仓库级 connector port 可以提供标准路径：

```text
<host>:<repo-port>/<image>:<tag>
```

选择一种布局，为 `/v2/` 配置 TLS 与反向代理转发，并把准确 host:port 提供给用户。Docker
image reference 中不要加入 `/repository/<repo>/`。

## 登录、Push 与 Pull

共享入口示例：

```bash
docker login nexus.example.com
docker pull nexus.example.com/docker-proxy/library/alpine:3.20
docker tag alpine:3.20 nexus.example.com/docker-hosted/team/alpine:3.20
docker push nexus.example.com/docker-hosted/team/alpine:3.20
docker pull nexus.example.com/docker-group/team/alpine:3.20
```

只向 hosted push；即使调用者已认证，proxy 和 group 仍是读取入口。


Browse 中复制的镜像引用只包含 registry 主机、可选端口、仓库路由路径、镜像名以及 tag 或 digest。
`docker pull`、`docker tag` 和 `docker push` 参数不应包含 `http://`、`https://` 或 Registry API
前缀 `/v2/`。全局搜索从共享数据库读取当前 Docker tag，已有镜像无需重新推送；仓库权限和
内容选择器权限仍然生效。支持按镜像名、tag 或 manifest digest 搜索，删除 tag 后搜索结果同步更新。

## 仓库行为

- Hosted 支持 blob upload session、manifest、tag、cross-repository blob mount 和 OCI referrer。
- Blob 按 content address 安全共享，repository-level reference 仍是鉴权与生命周期状态的事实
  来源。
- Proxy 缓存上游 manifest/blob，并保持 digest 校验。
- Group 按成员顺序解析 manifest，并让后续 blob read 绑定选中的同一来源。
- Browse 与 Search 展示 manifest、tag、media type、platform、blob 和 referrer metadata。

## 清理与安全

管理员可以在 Browse 中选中 tag 或 manifest digest 并删除，每次只移除选中的引用。
删除 digest 条目会保留已有 tag 及其可下载的 manifest 内容，与 Nexus 管理界面的资产删除行为一致。
没有 tag 或 digest 条目引用内容后才释放内容，共享 blob 仍受保护；重新推送会恢复 digest 条目。
Registry V2 的 digest 删除仍会移除 manifest 及其全部标签。Docker 镜像和命名空间目录
不是删除目标，API 会拒绝目录路径。从 group 发起删除时只影响选中的成员；删除 proxy
内容只清理本地缓存，后续仍可从上游重新获取。Browse 管理删除遵循门户的管理员权限规则，
独立于 Registry V2 写入策略。

删除和 cleanup 会区分 tag、manifest 与共享 blob reference，不能直接从 blob storage 删除
对象。先执行 cleanup preview，并由引用计数判断 blob 何时不再被引用。安全扫描应面向已提交
manifest 与 layer 集合，而不是未完成的 upload session。

## 排障

先运行 `curl -I https://<host>/v2/` 检查认证 challenge。私有 registry 登录前返回 `401`
是正常行为；登录后持续 `401` 通常表示广告的 realm/service 与客户端可见 host 不一致。
反向代理后的 push 失败常见于 request body 限制、timeout 或 upload-session location 转发错误。

Docker proxy 会在获取或刷新 bearer token 前释放上游 401 challenge 占用的连接，token realm
与 registry 同主机时也适用。每个副本独立维护可重建的出站连接池。等待连接池分配连接的默认
超时为 10 秒，可通过 `KKREPO_OUTBOUND_PROXY_CONNECTION_REQUEST_TIMEOUT_MS`（毫秒）调整。
该配置适用于所有直连和经过出站代理的 HTTP 请求，与 TCP 建连超时及响应/下载超时相互独立。

## 相关文档

- [Docker / OCI 客户端配置示例](../client-recipes.md#docker--oci)
- [兼容性矩阵](../compatibility-matrix.md#仓库格式矩阵)
- [OCI Distribution Specification](https://github.com/opencontainers/distribution-spec/blob/main/spec.md)
