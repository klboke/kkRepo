# Helm 仓库使用指南

kkRepo 支持经典 Helm chart repository 的 `hosted` 和 `proxy` recipe，通过
`/repository/<repo>/...` 提供 chart archive 与 `index.yaml`。当前不暴露 Helm group；使用
OCI 方式分发 Helm chart 时应选择独立的 Docker / OCI 仓库格式。

## 创建仓库

| 用途 | Recipe | 推荐配置 |
| --- | --- | --- |
| 私有 chart | `helm-hosted` | Blob store、online、write policy、strict validation |
| 上游 chart 缓存 | `helm-proxy` | Remote chart repository root 和缓存 TTL |

不同团队或保留策略需要隔离时，应创建不同仓库名。

## 配置并读取 Chart

向 Helm 客户端添加 proxy 或 hosted：

```bash
helm repo add acme https://nexus.example.com/repository/helm-proxy/
helm repo update
helm search repo acme
helm pull acme/demo --version 1.0.0
```

私有仓库可传入 `--username`、`--password`，或使用部署选定的凭据机制。生产客户端应信任
部署 CA，不要关闭 TLS 校验。

## 发布经典 Chart

打包并上传到 hosted：

```bash
helm lint ./charts/demo
helm package ./charts/demo
curl -u alice:"$KKREPO_PASSWORD" \
  --upload-file demo-1.0.0.tgz \
  https://nexus.example.com/repository/helm-hosted/demo-1.0.0.tgz
```

也可以使用 Admin UI 或 Nexus 兼容的 component upload。Helm 内置 `helm push` 面向 OCI
registry；经典 chart repository 应使用 kkRepo 上传 endpoint 或支持经典仓库上传约定的插件。

## 仓库行为

- Hosted 发布会解析 `Chart.yaml`、存储 chart archive，并根据已提交 metadata 重建
  `index.yaml`。
- Proxy 会获取并重写上游 index，使 chart URL 保持指向 kkRepo host。
- `index.yaml`、chart archive、checksum、validator、Browse 和 Search 都基于已提交仓库状态。
- 当前没有 Helm group recipe；客户端配置一个受支持入口，或使用上游已提供聚合 index 的
  proxy。

## 运维与排障

只有 hosted 接受发布。`helm repo update` 仍返回旧数据时，要同时检查客户端缓存和 proxy
metadata TTL。Index 已列出版本但下载失败时，检查 chart URL 是否已重写为 kkRepo，以及
调用者是否拥有 archive 路径的读取权限。

## 相关文档

- [Helm 客户端配置示例](../client-recipes.md#helm)
- [兼容性矩阵](../compatibility-matrix.md#仓库格式矩阵)
- [Helm Chart Repository Guide](https://helm.sh/docs/topics/chart_repository/)
