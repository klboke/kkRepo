# R / CRAN 仓库使用指南

kkRepo 通过 `r-hosted`、`r-proxy` 和 `r-group` 支持 CRAN-style source package 仓库，客户端
地址保持 `/repository/<name>/...` 布局。Hosted 与 group 在 `src/contrib` 下发布 source package，
并提供生成的 `src/contrib/PACKAGES.gz`；proxy 还可以缓存上游暴露的其它安全路径。

## 仓库类型

| 用途 | Recipe | 行为 |
| --- | --- | --- |
| 私有 source package | `r-hosted` | 不可变 `.tar.gz` 上传和确定性生成的 `PACKAGES.gz` |
| 上游 CRAN mirror | `r-proxy` | `PACKAGES*`、source/binary package 和 Archive 路径的回源缓存 |
| 统一 source 入口 | `r-group` | 有序 hosted/proxy 成员、合并 `PACKAGES.gz`、按 snapshot 绑定 `.tar.gz` |

本版本的 hosted/group 明确限定为 source-only。Windows `.zip`、macOS `.tgz`、未压缩
`PACKAGES` 和 `PACKAGES.rds` 只能直连 `r-proxy`。Group 发布索引后不会在下载时切到另一个
成员；每个 package path 都绑定到该 snapshot 选中的成员 revision 和 checksum。

## 创建仓库

创建 `r-hosted` 保存私有包；创建 `r-proxy` 并把 remote root 指向
`https://cloud.r-project.org` 一类 CRAN-style 根地址；最后创建 `r-group`，通常把私有 hosted
放在前面、proxy 放在后面。Group 只能包含 R format 成员，并拒绝循环引用。

读取、浏览、上传和删除沿用现有仓库权限。自动化任务建议使用只授权目标 hosted 仓库的用户或
CI token。

## 配置 R

在站点级或用户级 `.Rprofile` 中配置 group：

```r
local({
  repos <- getOption("repos")
  repos["KKRepo"] <- "https://repo.example.com/repository/r-group"
  options(repos = repos)
})
```

首期 hosted/group 只提供 source package。在默认偏好 binary 的平台上验证时，应显式指定
`type = "source"`：

```r
available.packages(
  repos = "https://repo.example.com/repository/r-group",
  type = "source"
)
install.packages(
  "acmepkg",
  repos = "https://repo.example.com/repository/r-group",
  type = "source"
)
update.packages(
  repos = "https://repo.example.com/repository/r-group",
  type = "source",
  ask = FALSE
)
```

私有仓库可使用部署允许的 HTTP Basic 或只读 token。不要把凭据提交到 `.Rprofile`。

## 发布 source package

在可信构建机上生成标准 R source package，再上传到规范 coordinate：

```bash
R CMD build acmepkg

curl -u alice:"$KKREPO_PASSWORD" \
  -H 'Content-Type: application/x-gzip' \
  --upload-file acmepkg_1.2.3.tar.gz \
  https://repo.example.com/repository/r-hosted/src/contrib/acmepkg_1.2.3.tar.gz
```

也可以通过 Admin UI 或 Components API 上传。kkRepo 会在不执行 package 代码的前提下，对
gzip/tar 结构和有界 `DESCRIPTION` metadata 做校验；package name/version 必须与 filename 和 URL
一致。Coordinate 不可变：相同 bytes 重试是幂等的，同一 package/version 的不同 bytes 会被拒绝。

上传会推进持久化 namespace revision。带 fencing 的 publisher 生成 byte-stable
`PACKAGES.gz`，并原子切换 current snapshot。读请求只会看到旧的完整 snapshot 或新的完整
snapshot，不会看到半份索引。

## Proxy 与 group 行为

R proxy 只回源经过规范化和策略校验的相对路径，并只使用仓库配置的上游凭据，绝不转发客户端
凭据。`PACKAGES.gz` 通过有界 DCF parser 生成可重建数据库投影；`PACKAGES.rds` 仅作为 opaque
bytes 缓存，不在服务进程反序列化。

Group 按 R 版本顺序与成员优先级为每个 package 选择一条记录。Proxy package 在首次读取时才
回源，并校验所绑定 `PACKAGES.gz` 声明的 MD5。无法验证或校验失败的记录会 fail closed，不会从
group 暴露。

## Cleanup 与安全扫描

Cleanup Policy 可以绑定 R hosted 仓库。Retain-count 使用 R 的 numeric version 规则（例如
`0.9 < 0.75`），不使用字典序或 SemVer。删除会先发布不再声明该 package 的 index，再退出 package
投影；生成 metadata 和 group 不作为独立清理主体。

只有通过校验的 source package archive 会进入制品扫描。`PACKAGES*`、生成 snapshot、opaque
proxy binary 和任意 proxy 静态文件都排除。由于扫描器没有专用 CRAN advisory matcher，R 生态
漏洞覆盖会明确显示为 partial；SBOM、通用/native findings 仍遵循 Audit 或 Enforce 策略。

## 运维与迁移

仓库详情 API 会展示 desired/published revision、最近发布错误和 proxy projection 状态。管理员可
触发 rebuild；任务真相位于数据库，多副本竞争时由 lease/fencing 保证单一发布。

迁移支持 Nexus 3.94 R hosted/proxy/group definition。只有 source datastore shape 能证明规范
source path、typed identity、size 和 checksum 时才自动恢复 hosted content。生成 index、group
binding、lease 和 cache 都在 kkRepo 重建；未知版本或 shape 会保留为 `NEEDS_MANUAL_ACTION`，不会
靠猜测迁移。

## 当前边界

- Hosted/group 仅支持 source `.tar.gz` package 和 `PACKAGES.gz`。
- 未实现自动生成 CRAN `Archive`，也不提供 `renv`/`remotes` 旧版本 alias。
- kkRepo 不执行 `R CMD build`、`R CMD check`、安装 hook、native code 或 package test。
- 不会从普通 CRAN-like path 推断 Bioconductor、R-universe 或 Posit Package Manager 的专用发布语义。

更多细节见 [落地设计](../dev/r-cran-repository-design.md)、
[性能基线](../dev/r-cran-performance-baseline.md)和
[R 客户端示例](../client-recipes.md#r--cran)。
