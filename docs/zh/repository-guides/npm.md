# npm 仓库使用指南

kkRepo 支持 npm `hosted`、`proxy` 和 `group` 仓库。Hosted 接收私有 package 发布，proxy
缓存上游 registry，group 为私有包和上游包提供统一读取入口。

## 创建仓库

| 用途 | Recipe | 推荐配置 |
| --- | --- | --- |
| 私有 package | `npm-hosted` | Blob store、online、write policy、strict validation |
| 公共 registry 缓存 | `npm-proxy` | Remote URL `https://registry.npmjs.org/` 和缓存 TTL |
| 统一安装入口 | `npm-group` | Hosted 排在 proxy 前面 |

下文使用 `npm-hosted`、`npm-proxy` 和 `npm-group` 作为仓库名。

## 配置安装

在用户或项目 `.npmrc` 中配置 group：

```ini
registry=https://nexus.example.com/repository/npm-group/
always-auth=true
```

私有 scope 应把 scope 与 token 绑定到同一个仓库根路径：

```ini
@acme:registry=https://nexus.example.com/repository/npm-group/
//nexus.example.com/repository/npm-group/:_authToken=${NPM_TOKEN}
```

使用 `NpmToken` 或部署明确支持的其他凭据。用户级凭据不要放入项目仓库。

## 发布 Package

直接登录并发布到 hosted：

```bash
npm login --registry=https://nexus.example.com/repository/npm-hosted/
npm publish --registry=https://nexus.example.com/repository/npm-hosted/
```

需要确保 package 永远不会发到公共 registry 时，在 `package.json` 中配置
`publishConfig.registry`。发布前用 `npm pack --dry-run` 检查 tarball 内容，用
`npm whoami --registry=...` 验证凭据。

## 仓库行为

- Hosted 发布会一起存储 package metadata、tarball、integrity 和 dist-tag。
- Proxy 会把上游 tarball URL 重写为 kkRepo 地址，并分别缓存 metadata 与 content。
- Group 按成员优先级读取，并保证 metadata/tarball 从选中的同一来源解析。
- 支持客户端所需的 npm audit 兼容入口；策略执行仍属于 kkRepo 安全扫描能力。

## 运维与排障

发布权限只授予 hosted。`401` 通常表示凭据缺失或无效，`403` 表示身份已认证但缺少仓库
权限。修改 `.npmrc` 后应先正常重试和刷新 metadata，仍无法恢复时再使用
`npm cache clean --force` 清理客户端缓存。

## 相关文档

- [npm 客户端配置示例](../client-recipes.md#npm)
- [兼容性矩阵](../compatibility-matrix.md#仓库格式矩阵)
- [npm registry 配置](https://docs.npmjs.com/misc/registry/)
- [`npm publish` 参考](https://docs.npmjs.com/cli/publish/)
