# NuGet 仓库使用指南

kkRepo 支持带 NuGet V3 service index 的 `hosted`、`proxy` 和 `group` 仓库。Hosted 接收
私有 `.nupkg` 发布，proxy 缓存上游 service，group 提供统一 restore/search endpoint。

## 创建仓库

| 用途 | Recipe | 推荐配置 |
| --- | --- | --- |
| 私有 package | `nuget-hosted` | Blob store、write policy、strict validation |
| nuget.org 缓存 | `nuget-proxy` | Remote URL `https://api.nuget.org/v3/index.json` |
| 统一 restore | `nuget-group` | Hosted 排在 proxy 前面 |

Proxy 应配置上游 V3 service index，不要直接配置某个 flat-container resource。

## 配置 Package Source

添加用于 restore 的 group：

```bash
dotnet nuget add source \
  https://nexus.example.com/repository/nuget-group/v3/index.json \
  --name kkrepo
```

私有 source 优先使用平台支持的 credential provider。下列跨平台示例会明文存储密码，只能
用于权限受控的用户配置文件：

```bash
dotnet nuget add source \
  https://nexus.example.com/repository/nuget-hosted/v3/index.json \
  --name kkrepo-hosted \
  --username alice \
  --password "$KKREPO_PASSWORD" \
  --store-password-in-clear-text
```

## 打包、发布与 Restore

创建用于发布的 `NuGetApiKey` token：

```bash
dotnet pack --configuration Release
dotnet nuget push bin/Release/Demo.1.0.0.nupkg \
  --source https://nexus.example.com/repository/nuget-hosted/ \
  --api-key "$KKREPO_API_KEY"
dotnet restore \
  --source https://nexus.example.com/repository/nuget-group/v3/index.json
```

只向 hosted 发布。Proxy 与 group service index 只公布读取/搜索 resource，不会因为客户端
持有 API key 而变成可写仓库。

## 仓库行为

- Hosted 解析 package identity/version，并提供 package content、registration 和 search 数据。
- Proxy 从配置的 service index 发现上游 resource，并缓存 metadata/content。
- Group service-index resource 指回 group，并在 registration、flat-container、search 和
  autocomplete 请求中保持成员优先级。
- Browse 与 Search 展示 package coordinate 和已存储 asset。

## 运维与排障

API key 通过 `X-NuGet-ApiKey` 发送，与 HTTP source 凭据相互独立。Push 返回 `401` 通常
表示 key 或 source credential 无效，`403` 表示缺少 add/edit 权限。使用
`dotnet nuget list source` 和详细 restore 日志确认实际 V3 endpoint。

## 相关文档

- [NuGet 客户端配置示例](../client-recipes.md#nuget)
- [兼容性矩阵](../compatibility-matrix.md#仓库格式矩阵)
- [NuGet V3 Server API](https://learn.microsoft.com/en-us/nuget/api/overview)
- [NuGet package publish resource](https://learn.microsoft.com/en-us/nuget/api/package-publish-resource)
