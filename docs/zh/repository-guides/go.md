# Go 仓库使用指南

kkRepo 支持 Go module `proxy` 和 `group` 仓库，实现官方 `GOPROXY` 读取协议；当前不暴露
Go hosted 仓库和直接 module 发布能力。

## 创建仓库

| 用途 | Recipe | 推荐配置 |
| --- | --- | --- |
| 公共 module 缓存 | `go-proxy` | Remote URL `https://proxy.golang.org/` 和缓存 TTL |
| 统一读取入口 | `go-group` | 按顺序配置 Go proxy/group 成员 |

需要按可控顺序访问多个 module proxy 时使用 group。Group 不会把仓库转换成发布入口。

## 配置 Go 客户端

把 `GOPROXY` 指向 group：

```bash
go env -w GOPROXY=https://nexus.example.com/repository/go-group/,direct
```

逗号表示 proxy 仅返回 `404` 或 `410` 时才回退到 `direct`。构建必须禁止绕过 kkRepo 时，
移除 `,direct`。私有 module namespace 需要显式配置 checksum 行为：

```bash
go env -w GONOSUMDB=git.example.com/acme/*
```

不要为必须经过 kkRepo 的 namespace 设置 `GONOPROXY`，该配置会让 Go 客户端绕过 proxy。

## 解析与校验 Module

```bash
go list -m github.com/pkg/errors@latest
go mod download github.com/pkg/errors
go mod verify
```

私有 proxy 凭据可通过 Go 客户端支持的 HTTP 凭据机制提供，例如权限受控的 `.netrc`。
不要把可复用凭据写入 `go.mod` 或提交到源码的仓库 URL。

## 仓库行为

- Proxy 提供 module version list、`.info`、`.mod`、`.zip` 和 `@latest` endpoint。
- 缓存的 module version 通过共享 metadata 与 blob storage 在多副本间复用。
- Group 遵循成员顺序，仅当前一个成员明确表示 module/version 不存在时继续查找。
- Proxy cache 和 negative-cache TTL 控制刷新；Go 本地 module cache 是另一层缓存。

## 排障

使用 `go env GOPROXY GONOPROXY GONOSUMDB GOPRIVATE` 检查实际客户端配置。上游变更后仍
读到旧版本时，应先区分 Go 本地 module cache 与 kkRepo proxy cache，再决定清理哪一层。
认证失败和策略拒绝不能当作“未找到”继续回退。

## 相关文档

- [Go 客户端配置示例](../client-recipes.md#go)
- [兼容性矩阵](../compatibility-matrix.md#仓库格式矩阵)
- [Go Modules Reference 与 GOPROXY 协议](https://go.dev/ref/mod#goproxy-protocol)
