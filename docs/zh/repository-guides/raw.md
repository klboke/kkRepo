# Raw 仓库使用指南

kkRepo 支持通用 HTTP `hosted`、`proxy` 和 `group` 仓库。Raw 保留调用方定义的路径，适合
没有专用 package 协议的文件。

## 创建仓库

| 用途 | Recipe | 推荐配置 |
| --- | --- | --- |
| 私有文件 | `raw-hosted` | Blob store、write policy、content validation policy |
| 远程文件缓存 | `raw-proxy` | Remote root 和缓存 TTL |
| 统一读取入口 | `raw-group` | Hosted 排在 proxy 前面 |

发布前先确定稳定路径规范，例如 `<product>/<channel>/<version>/<filename>`。Raw 没有 package
manager 能在之后规范化 identity。

## 上传与下载

使用 PUT 上传到 hosted：

```bash
curl -u alice:"$KKREPO_PASSWORD" \
  --upload-file archive.tar.gz \
  https://nexus.example.com/repository/raw-hosted/releases/1.0.0/archive.tar.gz
```

从 group 读取：

```bash
curl --fail --remote-name \
  https://nexus.example.com/repository/raw-group/releases/1.0.0/archive.tar.gz
curl --head \
  https://nexus.example.com/repository/raw-group/releases/1.0.0/archive.tar.gz
```

操作人员不便手工构造 HTTP 请求时，可以使用 Admin UI 和 component upload。

## 仓库行为

- 规范化后的仓库相对路径就是 asset identity。
- Hosted write policy 决定能否替换已有路径。
- Proxy 把请求相对路径映射到 remote root，并缓存成功 content、validator 和 negative lookup。
- Group 按成员顺序解析，首个包含该路径的成员生效。
- Browse 展示路径层级，Search 使用已存储 asset metadata。

## 安全与运维

Raw 没有协议坐标提供额外 namespace，因此 path-level content selector 尤其重要。按最小权限
授予 read/add/edit/delete，对符合条件的 archive 扫描，并让 cleanup policy 作用于明确前缀
或 metadata 条件。不能因为文件名隐蔽就把 secret 放入公共 Raw 仓库。

## 排障

Group 返回 `404` 表示没有可读成员包含该路径。检查大小写、URL encoding、成员顺序、
negative-cache TTL 和权限过滤。PUT 返回 `409` 或 write-policy 错误通常表示路径已存在且不
允许覆盖。

## 相关文档

- [Raw 客户端配置示例](../client-recipes.md#raw)
- [兼容性矩阵](../compatibility-matrix.md#仓库格式矩阵)
- [HTTP 语义](https://www.rfc-editor.org/rfc/rfc9110)
