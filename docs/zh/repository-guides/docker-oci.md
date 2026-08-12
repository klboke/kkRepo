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

## 仓库行为

- Hosted 支持 blob upload session、manifest、tag、cross-repository blob mount 和 OCI referrer。
- Blob 按 content address 安全共享，repository-level reference 仍是鉴权与生命周期状态的事实
  来源。
- Proxy 缓存上游 manifest/blob，并保持 digest 校验。
- Group 按成员顺序解析 manifest，并让后续 blob read 绑定选中的同一来源。
- Browse 与 Search 展示 manifest、tag、media type、platform、blob 和 referrer metadata。

## 清理与安全

删除和 cleanup 会区分 tag、manifest 与共享 blob reference，不能直接从 blob storage 删除
对象。先执行 cleanup preview，并由引用计数判断 blob 何时不再被引用。安全扫描应面向已提交
manifest 与 layer 集合，而不是未完成的 upload session。

## 排障

先运行 `curl -I https://<host>/v2/` 检查认证 challenge。私有 registry 登录前返回 `401`
是正常行为；登录后持续 `401` 通常表示广告的 realm/service 与客户端可见 host 不一致。
反向代理后的 push 失败常见于 request body 限制、timeout 或 upload-session location 转发错误。

## 相关文档

- [Docker / OCI 客户端配置示例](../client-recipes.md#docker--oci)
- [兼容性矩阵](../compatibility-matrix.md#仓库格式矩阵)
- [OCI Distribution Specification](https://github.com/opencontainers/distribution-spec/blob/main/spec.md)
