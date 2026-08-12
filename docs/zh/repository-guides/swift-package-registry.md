# Swift Package Registry 使用指南

kkRepo 支持 Swift Package Registry v1 的 `hosted`、GitHub-backed `proxy` 和 `group` 仓库。
Hosted 发布不可变 source archive，proxy 把 GitHub release 投影为 registry identity，group
提供统一的有序解析入口。

## 创建仓库

| 用途 | Recipe | 推荐配置 |
| --- | --- | --- |
| 私有 release | `swift-hosted` | Blob store、write policy、signature policy |
| GitHub-backed 缓存 | `swift-proxy` | GitHub 凭据、缓存 TTL、请求水位 |
| 统一读取入口 | `swift-group` | Hosted 排在 proxy 前面 |

生产 SwiftPM registry 访问需要 HTTPS，应在客户端登录或发布前配置信任证书。

## 配置与认证 SwiftPM

把 group 设置为 registry endpoint 并登录：

```bash
swift package-registry set \
  https://nexus.example.com/repository/swift-group/

swift package-registry login \
  https://nexus.example.com/repository/swift-group/login \
  --username alice \
  --password "$KKREPO_PASSWORD" \
  --no-confirm
```

CI 可以用 `GenericToken` 执行 `swift package-registry login --token <token>`。kkRepo 实现了
可选 `/login` endpoint，无效凭据返回 `401`。

## 发布与使用 Release

Source archive 必须包含单一顶层 package root 和有效 `Package.swift`：

```bash
swift package-registry publish acme.demo 1.2.3 \
  --url https://nexus.example.com/repository/swift-hosted/ \
  --metadata-path package-metadata.json
```

在 `Package.swift` 中使用不可变 identity：

```swift
dependencies: [
    .package(id: "acme.demo", exact: "1.2.3")
]
```

然后运行 `swift package resolve` 与 `swift build`。已知 SCM URL 可通过 `/identifiers`
mapping 支持 `--replace-scm-with-registry`。

## 仓库行为

- Hosted 在提交 release 前校验 scope、package、SemVer、manifest、archive checksum 与可选
  CMS signature。
- 已发布 identity/version 不可变，包括 versioned `Package@swift-X.Y.swift` manifest。
- GitHub-backed proxy 固定首次观察到的 tag commit 与 archive checksum；tag 移动不会改写缓存
  release。
- Group source binding 保证 release metadata、manifest、signature 与 archive 位于同一成员。
- 保持 Range request、cache validator、problem details 和 Registry v1 media type 语义。

## 限制与排障

Proxy 面向 GitHub source-to-registry 行为，不提供任意 registry chaining；不暴露可选
`/availability` endpoint。登录成功但解析失败时，检查 package identity、group 成员顺序，
以及每个 manifest/archive 请求的读取权限。

## 相关文档

- [Swift 客户端配置示例](../client-recipes.md#swift-package-registry)
- [兼容性矩阵](../compatibility-matrix.md#仓库格式矩阵)
- [Swift Package Registry Service Specification](https://github.com/swiftlang/swift-package-manager/blob/main/Documentation/PackageRegistry/Registry.md)
- [SwiftPM registry usage](https://github.com/swiftlang/swift-package-manager/blob/main/Documentation/PackageRegistry/PackageRegistryUsage.md)
