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
npm login --auth-type=legacy --registry=https://nexus.example.com/repository/npm-hosted/
npm publish --registry=https://nexus.example.com/repository/npm-hosted/
```

pnpm 用户也可以直接登录。pnpm 的 Web 登录探测会自动回退到同一套 legacy token 登录流程：

```bash
pnpm login --registry=https://nexus.example.com/repository/npm-hosted/
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

## Proxy tarball 地址

代理下载遵循上游包元数据中的 `dist.tarball` 地址，支持华为 CodeArts 的
`@scope/package/-/@scope/package-1.0.0.tgz` 路径，也兼容已有 lockfile 中的此类地址。
原始元数据保存在共享 blob 存储中，不同副本能解析到同一上游地址。跨主机下载需要将目标
加入代理仓库的 redirect hosts 白名单；上游凭据仍限定在受信任的源站。

直接使用 lockfile 下载时，也会先获取缺失或过期的包元数据，再选择上游 tarball 地址。
包含 scope 的文件路径与普通文件名保持独立，最小发布年龄检查也使用相同的路径标识。
升级后的旧发布年龄索引会从已存储的原始元数据自动重建，无需手动回填。

tarball 后缀中的子目录会保留。元数据暂时不可用时，直接下载仍可尝试标准仓库路径。
缓存的 HTTP 校验头只会用于原始上游 URL，查询参数变化时也会重新下载。


## Hosted 写入策略

`ALLOW_ONCE`（默认）禁止覆盖已存在的 hosted tarball，包括多个副本同时发布的情况。
数据库的 asset path 唯一约束决定获胜者；失败的发布返回现有写入策略错误，并回滚本次
发布的全部附件和 package metadata 修改。请先刷新 metadata 检查已发布版本，再使用
新版本重试。添加其他版本仍可正常更新 package root，multipart 上传也使用同一事务边界。
回滚后会清理没有引用的已上传对象。

`ALLOW` 保留 tarball 覆盖行为。npm 发布接口目前没有针对 `ALLOW` 仓库的可选原子
create-if-absent 契约；先前 GET 返回 `404` 不代表保留了发布位置。需要不可变发布时请使用
`ALLOW_ONCE`。Proxy cache 刷新以及单独授权的管理员 package-root 清理保持原有语义。
