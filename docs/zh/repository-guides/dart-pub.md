# Dart / Pub 仓库使用指南

kkRepo 为 Dart 与 Flutter 支持 Hosted Pub Repository v2 的 `hosted`、`proxy` 和 `group`
仓库。Hosted 接收私有 package 发布，proxy 缓存其他 Pub server，group 提供统一的有序读取
入口。

## 创建仓库

| 用途 | Recipe | 推荐配置 |
| --- | --- | --- |
| 私有 package | `pub-hosted` | Blob store、write policy、strict validation |
| pub.dev 缓存 | `pub-proxy` | Remote URL `https://pub.dev/` 和缓存 TTL |
| 统一读取入口 | `pub-group` | Hosted 排在 proxy 前面 |

Pub token 按 hosted URL 存储，因此客户端必须始终使用完全一致的仓库 URL。

## 认证与依赖解析

创建 `PubToken`，然后为客户端访问的每个私有 endpoint 注册 token：

```bash
dart pub token add https://nexus.example.com/repository/pub-group
dart pub token add https://nexus.example.com/repository/pub-hosted
```

CI 可以使用 `dart pub token add <url> --env-var KKREPO_PUB_TOKEN`。通过 group 解析全部依赖：

```bash
PUB_HOSTED_URL=https://nexus.example.com/repository/pub-group dart pub get
PUB_HOSTED_URL=https://nexus.example.com/repository/pub-group flutter pub get
```

单个 dependency 也可以在 `pubspec.yaml` 中使用 `hosted` 配置。

## 发布 Package

发布私有代码前设置 hosted 目标：

```yaml
name: demo_package
version: 1.0.0
publish_to: https://nexus.example.com/repository/pub-hosted
```

```bash
dart pub publish --dry-run
dart pub publish
```

已发布 package version 不可变，新 release 必须递增语义化版本。

## 仓库行为

- Hosted 把 package archive 与解析后的 `pubspec.yaml` metadata 作为一次发布存储。
- Metadata 响应包含当前客户端使用的 archive URL 和 `archive_sha256`。
- Proxy 缓存上游 package metadata 与 archive。
- Group source binding 保证 package metadata 与 archive download 来自选中的同一成员。
- Browse 与 Search 展示 package/version metadata 和 archive 属性。

## 运维与排障

只有 hosted 接受发布。Token 与传给 `dart pub token add` 的准确 URL 绑定，scheme、host、port
和 repository path 必须一致。Flutter 与 Dart 行为不一致时，检查同一进程环境中的实际
`PUB_HOSTED_URL`。

## 相关文档

- [Dart / Pub 客户端配置示例](../client-recipes.md#dart--pub)
- [兼容性矩阵](../compatibility-matrix.md#仓库格式矩阵)
- [Dart 自定义 package repository](https://dart.dev/tools/pub/custom-package-repositories)
