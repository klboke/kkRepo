# WinGet 仓库指南

kkRepo 实现 WinGet REST source API（`/api/...`），支持 `hosted`、`proxy` 和 `group` 三种仓库。
WinGet 客户端可以通过 HTTP 搜索包、解析精确版本并读取合并后的包清单。

## 创建仓库

| 用途 | Recipe | 建议配置 |
| --- | --- | --- |
| 私有清单 | `winget-hosted` | 不使用 Blob 存储；清单保存在元数据表中 |
| 上游镜像 | `winget-proxy` | Remote URL 指向另一个 WinGet REST source |
| 统一入口 | `winget-group` | Hosted 在前，Proxy 在后 |

Proxy 的 Remote URL 是上游 REST source 的根地址。WinGet 会在 source 地址后追加
`/information`、`/manifestSearch` 和 `/packageManifests`；kkRepo 同时接受仓库根路径和参考实现使用的
`/api/` 前缀形式。

## 配置客户端

```bash
winget source add \
  --name kkrepo \
  --arg https://nexus.example.com/repository/winget-group \
  --type Microsoft.Rest
winget source list
winget search --source kkrepo contoso
winget install --source kkrepo --id Contoso.Tool --version 1.2.3
```

`--arg` 只填写仓库 URL。WinGet 会追加 `/information`、`/manifestSearch` 和 `/packageManifests`；
kkRepo 同时接受这些路径和参考 REST source 使用的 `/api` 前缀形式。

私有 source 可以通过 WinGet 自定义 header 传入 kkRepo API token：

```bash
winget source add --name kkrepo-private \
  --arg https://nexus.example.com/repository/winget-hosted \
  --type Microsoft.Rest \
  --header "Bearer $KKREPO_TOKEN"
```

kkRepo 将 `Windows-Package-Manager` 的值识别为 API token（可带或不带 `Bearer ` 前缀），
同时也支持常规 `Authorization` header。

## 发布清单

Hosted 仓库接受 WinGet 使用的合并清单：

```bash
curl --fail-with-body \
  -H "Authorization: Bearer $KKREPO_TOKEN" \
  -H "Content-Type: application/json" \
  --data @manifest.json \
  https://nexus.example.com/repository/winget-hosted/api/packageManifests
```

`POST /api/packageManifests` 追加版本，已存在相同
`(PackageIdentifier, PackageVersion, Channel)` 时返回 `409`。
`PUT /api/packageManifests/{id}` 替换该包的全部版本；
`DELETE /api/packageManifests/{id}` 删除整个包或通过查询参数删除指定 `PackageVersion`。
发布需要 hosted 仓库的 add/edit/delete 权限。

## API 行为

- `GET /api/information` 返回支持的契约版本，不要求认证。
- `POST /api/manifestSearch` 是只读操作，需要 read 权限；支持 query、inclusions、filters、
  maximum results 和 continuation token。
- `GET /api/packageManifests/{PackageIdentifier}` 返回合并清单，可用 `PackageVersion` 和
  `Channel` 过滤。
- 非法 JSON、未知匹配字段、标识不一致和重复版本会返回 JSON 错误体。
- Proxy 和 group 只读。Proxy 清单按仓库 content TTL 缓存在共享元数据表中；group 按配置顺序
  合并成员结果。

## 运维与排查

- WinGet 清单是元数据而不是 Blob；Cleanup、制品扫描和 Browse 资产视图不适用于该格式。
- Proxy 搜索只返回上游第一页。由于出站 fetcher 不支持任意请求 header，`ContinuationToken`
  不会转发给上游；Group 搜索返回一个合并页。Hosted 搜索实现 continuation token。
- `packageManifests` 返回 `404` 表示标识或版本不存在；发布返回 `409` 表示相同版本和通道已存在，
  应发布新版本或使用 `PUT` 替换。
- `415` 表示请求的 `Content-Type` 不是 `application/json`。
- 如果 `winget search` 没有结果，确认 source 地址能访问
  `.../repository/<name>/information`（或 `/api/information`），并确认客户端信任 HTTPS 证书。

## 相关文档

- [WinGet REST source 参考](https://github.com/microsoft/winget-cli-restsource)
- [WinGet 清单 schema](https://github.com/microsoft/winget-cli/tree/master/schemas/JSON/manifests)
- [兼容性矩阵](../compatibility-matrix.md#repository-format-matrix)
