# Go 仓库使用指南

kkRepo 支持 Go module `hosted`、`proxy` 和 `group` 仓库。读取路径实现官方 `GOPROXY`
协议；hosted 发布对齐 Nexus 3.93+ 上传合同：向仓库根的 `<version>.zip` PUT 一个规范
module ZIP，module coordinate 从 archive root 中解析。

## 创建仓库

| 用途 | Recipe | 推荐配置 |
| --- | --- | --- |
| 私有 module | `go-hosted` | 不可变版本使用 `ALLOW_ONCE`；按需启用 Cleanup/扫描 |
| 公共 module 缓存 | `go-proxy` | Remote URL `https://proxy.golang.org/` 和缓存 TTL |
| 统一读取入口 | `go-group` | hosted 在前，随后配置一个或多个 proxy 成员 |

Go group 接受 Go hosted 和 proxy 成员，不允许 nested Go group。具体 `.info`、`.mod`、
`.zip` 请求按成员顺序解析；version list 会跨成员合并、去重、按 Go proxy 规则过滤并按 Go
SemVer 排序；`@latest` 跨成员选择，并优先 release，其次 prerelease，最后 pseudo-version。

## 发布私有 Module

ZIP 中所有文件必须位于同一个 `<module>@<version>/` root 下。Root 直接使用 module path，
不能使用 proxy URL 中为大小写编码的 `!` 形式。

```text
git.example.com/acme/payments@v1.2.3/go.mod
git.example.com/acme/payments@v1.2.3/payments.go
git.example.com/acme/payments@v1.2.3/LICENSE
```

以 version 作为仓库根文件名上传：

```bash
curl --fail-with-body \
  -u "$KKREPO_USER:$KKREPO_PASSWORD" \
  --upload-file v1.2.3.zip \
  https://nexus.example.com/repository/go-hosted/v1.2.3.zip
```

Admin UI 和 Components API 提供相同的单 ZIP 上传入口，并复用同一套校验器和事务 writer。
发布会生成规范 `.mod`、`.info`、`.zip` asset；三者的 metadata binding 在一个数据库事务
中一起可见，因此其它副本不会读到只完成一部分的 module version。

校验器覆盖官方 module path、规范 Go version、path-major suffix、ZIP root、大小写折叠冲突、
普通文件和大小限制。压缩 ZIP 与展开后文件总量都不得超过 500 MiB；root `go.mod` 和
`LICENSE` 分别不得超过 16 MiB。符号链接、不安全路径、嵌套或大小写错误的 `go.mod`、
以及 module directive 与 root 不一致都会被拒绝。Root `go.mod` 缺失时，kkRepo 会为
`.mod` endpoint 生成最小的 `module <path>` 文档。

Hosted write policy 作用于完整 module version：

- `DENY`：拒绝发布。
- `ALLOW_ONCE`：任一 release asset 已存在时拒绝重复 coordinate。
- `ALLOW`：在一个事务中替换三类 release asset，并回收不再引用的旧 blob。

## 配置 Go 客户端

把 `GOPROXY` 指向 group：

```bash
go env -w GOPROXY=https://nexus.example.com/repository/go-group/,direct
```

逗号表示 proxy 返回 `404` 或 `410` 时才回退到 `direct`。构建不得绕过 kkRepo 时移除
`,direct`。私有 module namespace 应显式配置 checksum 行为：

```bash
go env -w GOPRIVATE=git.example.com/acme/*
go env -w GONOSUMDB=git.example.com/acme/*
```

不要为必须经过 kkRepo 的 namespace 设置 `GONOPROXY`，否则 Go 会绕过配置的 proxy。
私有仓库凭据应放在权限受控的 `.netrc` 或 Go 客户端支持的其它凭据机制中，不要把可复用
凭据提交到 `go.mod` 或源码仓库 URL。

正常解析并校验 module：

```bash
go list -m git.example.com/acme/payments@latest
go mod download git.example.com/acme/payments@v1.2.3
go mod verify
```

## Cleanup 与 Artifact Scanning

每个 module version 是一个 component，包含 `.mod`、`.info`、`.zip` 三个 asset。Cleanup
`retainCount` 使用 Go SemVer 排序，并把旧迁移数据中的 `package` 与原生 `go-module` 当作
同一个 family。发布时间和最后下载时间复用现有共享策略引擎；删除命中的 component 会同时
删除三个 asset。通过 group 下载时，usage 记录到真实提供内容的 hosted/proxy member。

Release `.zip` 是 Artifact Scanning candidate，生成的 `.mod` 与 `.info` metadata 不是。
发布事务写入标准 asset-change event，扫描继续保持异步且适用于多副本。开启下载阻断后，
在打开对象存储中的 ZIP body 前会先对具体 blob 执行 policy，包括通过 group 的读取。

## 迁移与运维

当 source content model 已被证明时，Nexus Go hosted definition 和数据进入标准 preflight、
可恢复 metadata/blob、checksum 与报告流程；proxy cache 仍需管理员显式选择。Group member
顺序会被保留，迁移遗留 component kind 与原生 hosted list/Cleanup 保持兼容。迁移矩阵会在
Nexus 3.94 的 H2 与 PostgreSQL source shape 上覆盖两种目标数据库，校验原始 module ZIP
checksum，并通过两个应用副本上的真实 Go client 解析迁移后的 module。

Proxy cache 与 negative-cache TTL 控制上游刷新，Go 客户端本地 module cache 是另一层缓存。
排障时使用 `go env GOPROXY GONOPROXY GONOSUMDB GOPRIVATE` 检查实际配置。认证、授权、
扫描或 write-policy 拒绝不会被转换为“未找到”后继续 group fallback。

## 相关文档

- [Go 客户端配置示例](../client-recipes.md#go)
- [兼容性矩阵](../compatibility-matrix.md#仓库格式矩阵)
- [Cleanup Policy 使用指南](../cleanup-policy-guide.md)
- [Artifact Scanning 使用指南](../artifact-scanning-guide.md)
- [Go hosted 性能基线](../dev/go-hosted-performance-baseline.md)
- [Go Modules Reference 与 GOPROXY 协议](https://go.dev/ref/mod#goproxy-protocol)
