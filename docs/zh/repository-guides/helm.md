# Helm 仓库使用指南

kkRepo 支持经典 Helm chart repository 的 `hosted`、`proxy` 和 `group` recipe，通过
`/repository/<repo>/...` 提供 chart archive、provenance 文件与 `index.yaml`。使用 OCI
方式分发 Helm chart 时仍应选择独立的 Docker / OCI 仓库格式。

## 创建仓库

| 用途 | Recipe | 推荐配置 |
| --- | --- | --- |
| 私有 chart | `helm-hosted` | Blob store、online、write policy、strict validation |
| 上游 chart 缓存 | `helm-proxy` | Remote chart repository root 和缓存 TTL |
| 统一读取入口 | `helm-group` | 有序的 hosted、proxy 或嵌套 group member |

建议在 group 中把私有 hosted 放在公共 proxy 前。不同团队或保留策略需要隔离时，应创建
不同仓库名。

## 配置并读取 Chart

向 Helm 客户端添加 group 读取入口：

```bash
helm repo add acme https://nexus.example.com/repository/helm-group/
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
- Proxy 会获取上游 index，并重写 chart 与派生 `.prov` URL，使读取保持指向 kkRepo host。
- Group 按配置顺序聚合可用 member。相同 chart 名称和版本保留第一个 member 的 entry，
  后续 member 的唯一 release 仍会保留；chart 和 provenance 读取采用相同的首个成功 member
  顺序。
- Group 可以嵌套 group。运行时读取有 cycle 防护，仓库配置校验会拒绝跨格式 member 和循环
  definition。
- 合并 index 作为普通 blob-backed asset 持久化。成员 index 或仓库配置事务提交后，会通过
  数据库 cache watermark 递归失效包含它的嵌套 group，使所有副本共享同一 freshness 边界；
  节点本地 metadata cache 只是可丢失、可重建的热缓存。
- `index.yaml`、chart archive、checksum、validator、Browse 和 Search 都基于已提交仓库状态。
- Group index 聚合上限为 64 MiB。不可用、offline 或 index 无效的 member 会被隔离，健康
  member 仍可继续提供服务。

## 运维与排障

只有 hosted 接受发布。`helm repo update` 仍返回旧数据时，要检查客户端缓存、proxy
metadata TTL、group member 顺序和 member online 状态。Index 已列出版本但下载失败时，
检查 chart URL 是否已重写为仓库内相对路径，以及调用者是否拥有 group 路径的读取权限。

## 相关文档

- [Helm 客户端配置示例](../client-recipes.md#helm)
- [兼容性矩阵](../compatibility-matrix.md#仓库格式矩阵)
- [Helm Chart Repository Guide](https://helm.sh/docs/topics/chart_repository/)
- [Sonatype Helm 仓库配置](https://help.sonatype.com/en/create-a-helm-repository.html)
