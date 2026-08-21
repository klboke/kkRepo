# R / CRAN 仓库开发设计说明

本文记录 kkrepo R / CRAN-style package repository 的落地设计。目标不是把
`src/contrib` 当作 Raw 文件目录，而是在 R 官方仓库布局、`install.packages()` /
`available.packages()` 客户端行为、Sonatype Nexus Repository R format 与 kkrepo 当前的
关系数据库 + OSS/S3 + 多副本架构之间取兼容交集，并复用已经落地的 Cleanup Policy 与
制品安全扫描基建。

## 当前状态与落地结论

截至 2026-08-21，本文定义的第一阶段能力已经落地：代码已注册 `RepositoryFormat.R`、
`r-hosted`、`r-proxy`、`r-group` 与独立 `protocol-r`，并接入双数据库 V49 schema、不可变
`PACKAGES.gz` snapshot、proxy projection、group source binding、Admin/Browse/Search、
Cleanup、安全扫描、Nexus 3.94 shape-gated 迁移和真实 R 客户端 E2E。路线图同步标记为已实现；
使用方法见 [R / CRAN 仓库使用指南](../repository-guides/r-cran.md)，性能门禁与复现方法见
[R / CRAN 性能基线](r-cran-performance-baseline.md)。

落地结论如下：

- 第一阶段实现 Nexus-compatible `r-hosted`、`r-proxy` 和 `r-group`，公开 URL 继续使用
  `/repository/{repo}/...`。
- 按 Nexus 当前边界，hosted 与 group 只承诺 `.gz` 内容：hosted 发布 R source package
  `.tar.gz` 并生成 `PACKAGES.gz`；group 聚合 `PACKAGES.gz` 并解析同一快照绑定的
  `.tar.gz`。不把 Windows `.zip` 或 macOS `.tgz` 混入首期 hosted/group。
- Proxy 保持 Nexus 的“任意文件类型”能力，可直接缓存官方 CRAN 的 `PACKAGES`、
  `PACKAGES.gz`、`PACKAGES.rds`、source、Windows/macOS binary 和 `Archive` 路径；但
  proxy 加入 group 后，非 `.gz` 内容仍只能从 proxy 直连访问。
- `PACKAGES.gz` 是由数据库 package projection 生成的不可变 snapshot，不从 Blob 列表或
  Browse 树临时推导。索引记录、package 下载字节和 group member source binding 必须属于
  同一 revision。
- R package version 使用官方 `package_version()` / `compareVersion()` 语义；同一个比较器同时
  服务 hosted latest 选择、group 合并、Search 排序和 Cleanup `retainCount`，禁止字典序比较。
- source package 只作为不可信 archive 做有界检查与扫描。服务端绝不执行 `R CMD build`、
  `R CMD check`、`R CMD INSTALL`、`configure`、`cleanup`、R 代码、native library 或测试。
- package Blob、生成的 index snapshot 和扫描原始文档继续存 OSS/S3；数据库只保存有界
  identity、DCF 投影、revision、binding、lease、checksum 和 Blob 引用。
- 所有核心 SQL 先定义访问形状再定义复合索引。实现完成前必须在 MySQL 与 PostgreSQL 的
  大数据集保存 query-plan 证据，并用同机 Nexus reference 做可复现的性能对比。

## 调研基线

实现时按以下顺序固定行为：

1. R Core 的仓库布局、package metadata、版本与客户端文档是协议真相。
2. 当前稳定 R 客户端及对应 `utils` / `tools` 实现用于固定真实请求、fallback 和 DCF 解析行为。
3. Sonatype Nexus Repository R 文档、repository REST schema 与 Nexus 3.94 reference 的真实
   HTTP 行为是兼容性参考。
4. kkrepo 现有 hosted/proxy/group、Cleanup Policy、安全扫描、迁移和多副本设施是落地基础。

R 官方关键事实：

- CRAN-style repository base 下，source package 位于 `src/contrib`，标准文件为 `.tar.gz`。
  Windows binary 位于 `bin/windows/contrib/{R-major.minor}`，文件扩展名为 `.zip`；macOS
  binary 位于 `bin/macosx/{build}/contrib/{R-major.minor}`，文件扩展名为 `.tgz`。
- 每个终端 package 目录必须有 `PACKAGES`；可以同时提供 `PACKAGES.rds` 和
  `PACKAGES.gz`，客户端会优先尝试压缩/序列化表示并在缺失时 fallback。
- `tools::write_PACKAGES()` 从 package 内 `DESCRIPTION` 生成 `PACKAGES`、`PACKAGES.gz`
  和 `PACKAGES.rds`。默认字段覆盖 `Package`、`Version`、`Priority`、依赖字段、`OS_type`、
  `License` 和 `Archs`；binary 或非标准文件名可通过 `File`，子目录可通过 `Path` 指定。
- `Package` 至少两个字符，以 ASCII 字母开头，只包含 ASCII 字母、数字与点且不能以点结尾。
  `Version` 是至少两个非负整数组成的序列，段之间使用单个 `.` 或 `-`；`0.01.0` 与
  `0.1-0` 等价，`0.9 < 0.75`。
- `install.packages()` 默认根据平台选择 source 或 binary，并先通过 repository metadata
  解析依赖，再下载具体 package。一个只提供 source 的首期 group 必须用 `type="source"` 明确
  验证，不能用本机 binary cache 掩盖协议问题。

Nexus 兼容关键事实：

- Nexus 提供 `r-hosted`、`r-proxy` 与 `r-group`。官方示例把 repository base 配进
  `.Rprofile`，并以 PUT 上传
  `/repository/{repo}/src/contrib/example_1.0.0.tar.gz`。
- Nexus proxy 支持任意文件类型；hosted 与 group 只支持 `.gz`。一个 proxy 成员经 group
  暴露时，`.zip`、`.tgz`、`.rds` 等非 `.gz` 请求不可用，必须直连 proxy。
- Group 文档声明同路径内容可合并，但没有完整固定重复 package/version、member priority、
  `PACKAGES.gz` 字段顺序、错误 body、HEAD/Range 和条件请求行为；这些必须进入 M0 黑盒 fixture。
- Nexus 3.95 及更早版本对 `devtools::install_version()`、`renv::install("pkg@version")` 的旧版本
  路径存在已知 404 边界。首期不臆造 Archive alias；对 Nexus 3.94 的现状与后续 reference 漂移
  都通过 versioned fixture 记录。

## 功能范围

### 第一阶段必须实现

1. 协议与 recipe
   - 新增 `RepositoryFormat.R`、`r-hosted`、`r-proxy`、`r-group` 与独立 `protocol-r` 模块。
   - 实现严格 path、R package name/version、DCF、media type、checksum 与错误模型。
   - status、header、body、HEAD、Range、conditional request 和认证细节由真实 R client 与 Nexus
     黑盒 fixture 固定。

2. Hosted source repository
   - 支持 source `.tar.gz` 的 HTTP PUT、Admin UI、Components API、GET、HEAD 和删除。
   - 有界读取唯一顶层 package 与 `DESCRIPTION`，校验 archive、path、filename、`Package`、
     `Version`、依赖字段和 checksum；原始 package bytes 不被重打包。
   - 每次 package mutation 推进 namespace desired revision；durable worker 生成确定性的
     `src/contrib/PACKAGES.gz` snapshot 并原子切换 current revision。
   - 支持 write policy、幂等上传、Browse/Search、Usage、审计、Cleanup Policy 与安全扫描。

3. Proxy repository
   - 支持任意 CRAN-style path，包括 `PACKAGES*`、source、Windows/macOS binary、
     `src/contrib/Archive/...` 与其它上游静态文件。
   - 复用共享 RawProxy 的 validator、TTL、negative cache、auto-block、remote auth、redirect、
     routing rule 与 outbound request policy；相同 miss 由数据库 lease 合并。
   - `PACKAGES.gz` 在有界校验后保存可重建投影，供 Browse/Search、group merge、checksum 校验
     和迁移报告使用；`PACKAGES.rds` 首期只原样缓存，不在 JVM 内反序列化不可信 R object。

4. Group repository
   - 只接受 R hosted/proxy/group 成员，保持顺序并拒绝循环。
   - 只暴露 `.gz`；聚合成员 `PACKAGES.gz`，为最终 package path 建立 snapshot-scoped source
     binding，确保 metadata、checksum 和下载 bytes 来自同一成员 revision。
   - 同一 package/version/path 冲突时使用成员优先级，并把选择写入 binding；不能在 package GET
     时重新 first-hit 到另一份 bytes。
   - Group 只读，不接受上传，也不作为 Cleanup Policy target。

5. 产品闭环
   - Admin UI 创建三类 recipe、配置 proxy/group、上传 source package 并查看 publish/cache 状态。
   - Browse/Search 展示 package、version、repository namespace、依赖、license、checksum、大小、
     scan/cleanup 状态和实际 source repository。
   - Nexus definition/content migration 支持 dry-run、resume、checksum、幂等重试、shape gate 和
     逐仓库报告。
   - 真实 R client E2E、Nexus compatibility、Migration E2E、双数据库 contract、双副本接管、
     Cleanup/扫描 race 和性能对比全部进入验收。

### 后续扩展

- Hosted/group 的 Windows `.zip` 与 macOS `.tgz` binary repository；必须先引入明确 package type、
  build、R major.minor namespace、`File` 字段和对应真实平台客户端矩阵，不能仅放宽 suffix。
- Hosted/group 的未压缩 `PACKAGES` 与 `PACKAGES.rds`。RDS 生成必须由版本化、安全、确定性的
  codec 或隔离 worker 完成，不能在 server 进程执行任意 R 序列化内容。
- CRAN Archive-compatible 自动归档与 `renv` / `remotes` 旧版本解析；必须先对齐当前 Nexus
  reference 和客户端构造路径，不从 package name/version 猜测重定向。
- Bioconductor、R-universe、Posit Package Manager 等 CRAN-like 上游的专用能力；普通静态内容可
  先经 proxy，但 release/channel/snapshot 语义不自动等同于 CRAN。
- package promotion/copy、签名/证明材料和时间点 snapshot。Promotion 必须复用原始 package
  Blob 与 canonical projection，不能下载后重打包。

### 明确不实现

- 不实现 CRAN submission、incoming queue、人工审核、反向依赖检查或 build farm。
- 不执行 package 中的 R、shell、Make、configure、cleanup、test、vignette 或 native code。
- 不把 `.tar.gz`、完整 `DESCRIPTION`、`PACKAGES.gz`、SBOM 或 vulnerability report 放进数据库。
- 不把 generated `PACKAGES.gz` 当成独立可清理 package，也不允许 Raw delete 绕过 R mutation
  与 snapshot pipeline。
- 不在首期让 hosted/group 接受 `.zip`、`.tgz`、`.rds` 或其它仅 proxy 支持的扩展。
- 不依赖单 JVM map、锁、timer 或 queue 维护 current snapshot、proxy fill、group binding、
  negative cache、scan task、cleanup 或 migration 真相。
- 不为复制 Nexus 的不安全行为而关闭 archive/path/SSRF/资源限制；安全差异必须记录并 fail closed。

## 模块与职责

| 模块 | 设计职责 |
| --- | --- |
| `core` | `R` format、三类 recipe、共享权限与上传契约 |
| `protocol-r` | R path、package/version、DCF parser/renderer、media type 与错误模型 |
| `persistence-jdbc` | R DAO、package projection、namespace revision、snapshot、tombstone、proxy state、group binding 与 lease contract |
| `persistence-mysql` / `persistence-postgresql` | 同号 schema migration、约束、索引与 contract test |
| `server/r` | hosted importer、snapshot publisher、proxy projector、group resolver、删除与迁移 writer |
| server 通用入口 | Controller、安全过滤、Components API、Browse/Search、Cleanup、扫描、metrics 与 repository 生命周期 |
| `security-scan` / `scanner-adapter` | `ASSET_BLOB` package candidate、受限展开、Syft/Grype 与 policy decision |
| `migration-nexus` | Nexus R version/shape 探测、definition/content plan、writer 与校验 |
| `admin-ui` / `browse-ui` | recipe 配置、上传、浏览、Usage、publish/cache/scan/cleanup 状态 |
| `compat-test` | R package fixture、可控 CRAN upstream、Nexus/kkrepo 黑盒对照与性能正确性探针 |

Controller 只负责 route、认证上下文、流式 body 与响应适配。Archive/DCF 解析、版本比较、
snapshot 状态机、proxy/group 选择、Cleanup 与扫描映射必须位于协议 service 或格式 adapter 中。

## URL 与路由设计

推荐把 group base 写入 `.Rprofile`：

```r
local({
  repos <- getOption("repos")
  repos["KKRepo"] <- "https://repo.example.com/repository/r-group"
  options(repos = repos)
})
```

首期 source 安装示例：

```r
available.packages(
  repos = "https://repo.example.com/repository/r-group",
  type = "source"
)
install.packages(
  "example",
  repos = "https://repo.example.com/repository/r-group",
  type = "source"
)
```

路由基线如下；精确 status/header/error 由 M0 固定：

| 路径 | Hosted | Proxy | Group |
| --- | --- | --- | --- |
| `/src/contrib/PACKAGES.gz` | 当前生成 snapshot | 原样缓存并投影 DCF | 聚合成员 snapshot |
| `/src/contrib/{Package}_{Version}.tar.gz` | package GET/HEAD/PUT/DELETE | 回源缓存 | 按 snapshot binding 读取 |
| `/src/contrib/PACKAGES` | 首期不支持 | 原样缓存 | 不支持 |
| `/src/contrib/PACKAGES.rds` | 首期不支持 | 原样缓存 | 不支持 |
| `/src/contrib/Archive/{Package}/{file}` | 不自动生成 | 原样缓存 | `.gz` only；是否可聚合由 M0 固定 |
| `/bin/windows/contrib/{rVersion}/*` | 首期不支持 | 原样缓存 | 仅 `.gz` 请求可通过，`.zip` 不支持 |
| `/bin/macosx/{build}/contrib/{rVersion}/*` | 首期不支持 | 原样缓存 | 仅 `.gz` 请求可通过，`.tgz` 不支持 |
| Components API / Admin UI | source `.tar.gz` 上传 | 不适用 | 不适用 |

表格路径省略 `/repository/{repo}` 前缀。公开响应、`PACKAGES.gz` 的 `File`/`Path` 字段和 UI
Usage 片段不得暴露内部 Blob key、预签名 OSS URL、staging path 或 upstream credential。

Path parser 必须满足：

- 只 percent-decode 一次；拒绝 encoded separator、二次编码、空段、点段、反斜杠、NUL、控制字符、
  Unicode/大小写碰撞和超长 segment。
- Hosted 首期只允许 canonical `src/contrib/{Package}_{Version}.tar.gz` package path 及保留的
  generated namespace；`PACKAGES.gz`、隐藏 `.r/` 前缀和 binary 路径不能作为普通 package PUT。
- Filename 不能只靠最后一个下划线猜 identity；最终 `Package` / `Version` 来自有界解析的
  `DESCRIPTION`，再反向验证 canonical filename。
- Proxy 的 path 是不可信 upstream-relative URL；在拼接 remote base 前仍要经过 routing、
  redirect、DNS/IP、TLS 与 credential scope 校验。

## Source package 检查与内容安全

`RSourcePackageInspector` 使用流式、有界 parser：

1. 把上传流写入受限 spool，同时计算 MD5、SHA-256、size 与 gzip 完整性；不把 package 读入 heap。
2. 有界遍历 tar，拒绝截断流、尾随垃圾、absolute/dot path、重复 entry、device/FIFO、越界
   symlink/hardlink、稀疏文件放大、超限 PAX/xattr、压缩炸弹、过多 entry 和过深路径。
3. 要求一个 canonical 顶层 package 目录及唯一 `DESCRIPTION`；只读取 `DESCRIPTION` 所需字节，
   不展开整个 archive 到长期目录。
4. 使用 DCF parser 处理 continuation line、空行、字段名与声明 encoding；限制字段数、单字段字节、
   总 metadata 字节和依赖 token 数。`Authors@R` 只作为文本保存，绝不求值。
5. 校验必填 `Package`、`Version`、`Title`、`Description`、`License` 及 `Author`/`Maintainer` 或
   `Authors@R` 的存在性；name/version grammar 与 filename 必须一致。
6. 对 `Depends`、`Imports`、`LinkingTo`、`Suggests`、`Enhances`、`OS_type`、`Archs`、
   `NeedsCompilation`、`SystemRequirements` 等只保存有界 canonical projection；未知字段不自动
   变成 SQL column、metric label 或搜索 predicate。
7. 保存原始 package bytes。Inspector 的输出是 metadata 与安全报告，不是新的 package archive。

Golden tests 使用 `R CMD build` 产物以及手工恶意 fixture，并与当前稳定 R 的 DCF/version 行为做
differential test。运行时不能依赖容器中安装 R 才能解析 package；R binary 只作为 build/test oracle。

## Hosted 发布与 index snapshot

一个 source package mutation 的正确性单位是 package record 与随后发布的完整 `PACKAGES.gz`
snapshot：

1. Controller 完成认证、recipe、online、write policy、权限与 path 初检。
2. Inspector 在数据库事务外流式验证 package，生成 canonical identity、DCF projection、MD5、
   SHA-256、size 与安全报告。
3. 原始 Blob 先绑定隐藏 `.r/staging/{uuid}/...` asset。对象存储上传、archive 读取与压缩不能占用
   长数据库事务。
4. 获取 coordinate lease；在短事务内复查 path、identity、write policy 与已有 record，提交最终
   asset/component、`r_package`、Browse projection、通用 `artifact_change_event` 和 namespace
   desired revision。相同 path + 相同 SHA-256 是幂等；相同 package/version 的不同 bytes 按
   write policy 与 Nexus fixture 明确拒绝或替换，不得静默产生两个 current identity。
5. Durable publisher 以数据库 lease/fencing claim namespace revision，使用 keyset cursor 读取
   active package projection，按 R version comparator 为每个 package 选择 current record，生成
   deterministic DCF，再生成 byte-stable gzip。
6. Builder 把新 index 写入 `.r/snapshots/{namespaceHash}/{revision}/PACKAGES.gz` hidden asset；
   只有 Blob、snapshot row 与 checksum 完整后，才用 CAS 推进 published revision/current snapshot。
7. 对外 metadata 只读取 current published snapshot。构建失败、节点退出或 DB/OSS 暂时不可用时
   继续提供旧完整 snapshot；没有旧 snapshot 时返回明确 retriable unavailable，不返回半份 DCF。
8. Publisher 对突发 mutation 做短 debounce，但设置 max-delay；持续上传不能让新 package 永久不可见。

上传成功到 `PACKAGES.gz` 可见的时间进入指标和性能门禁。不能为了压低 PUT 延迟而省略 package
校验、通用 outbox 或 durable desired revision，也不能在请求线程每次全量扫描 package Blob。

删除使用 index-first 的两阶段语义：先写 tombstone、推进 desired revision 并发布不再包含该 package
的新 index；package Blob 在 snapshot/cache grace 内仍可读取，待旧 snapshot 不再可能引用后才解除
asset/blob binding。Cleanup、Browse/API delete 与 migration rollback 都复用同一 mutation service。

## `PACKAGES.gz` 生成语义

- 每条 DCF record 来自已验证的 bounded projection，至少包含 R client 所需默认字段，并按 Nexus
  fixture 决定是否输出 `MD5sum`、`NeedsCompilation`、`File` 与额外 repository 字段。
- DCF field order、record order、continuation folding、空行、newline、gzip level/header/mtime 由官方
  R parser golden 与 Nexus fixture 固定；同 revision 重建必须 byte-stable。
- Package/version 使用原始 display value，同时保存 binary canonical key 与 version order key。
  `0.01.0` 与 `0.1-0` 的比较等价不表示可以覆盖彼此；identity 冲突规则由原始 canonical coordinate
  与 Nexus 行为共同固定。
- 默认 snapshot 对齐 `write_PACKAGES(latestOnly = TRUE)` 的选择语义。若后续支持同 package 多个
  version entry，必须作为显式 repository option，并用真实 R 依赖过滤验证，不能悄悄改变默认。
- 空 hosted/group namespace 返回客户端可接受的 empty `PACKAGES.gz`，而不是 HTML 404 页面；
  Nexus 对未知 namespace 的差异另由 fixture 固定。
- ETag 按实际 gzip bytes 计算；Last-Modified、HEAD、If-None-Match、If-Modified-Since 和 Range 均复用
  通用 Blob 响应能力，并与 Nexus status/header 对照。
- Builder 使用 forward-only cursor + bounded spool；数据库连接只覆盖投影读取，不在 gzip/OSS 上传
  期间持有。内存由单 record、batch 与 buffer 上限决定，不随 namespace package 数线性增长。

## Proxy 设计

Proxy remote URL 表示 CRAN-style repository root，本地 path 经过严格解析后追加到 remote base。

Metadata 流程：

1. 所有文件先经过 `HttpRemoteFetcher` 的 URL、DNS/IP、redirect、TLS、credential 与 size policy。
2. `PACKAGES`、`PACKAGES.gz`、`PACKAGES.rds` 使用 metadata TTL 与 ETag/Last-Modified；package
   与静态文件使用 content TTL。404/410 可进入短 negative cache，401/403、429、5xx、timeout、
   archive/DCF invalid 和 checksum drift 不能伪装成 not-found。
3. `PACKAGES.gz` 原始 bytes 进入共享 Blob；在解压字节、record 数、字段数和字段长度上限内流式解析
   DCF，写入可重建 proxy projection 与 metadata state。投影失败不能替换旧 verified snapshot。
4. `PACKAGES.rds` 只原样缓存和服务，不反序列化；Browse/Search/group 需要的投影来自同 namespace
   的 `PACKAGES.gz`。上游只提供 RDS 时，proxy 仍可直连使用，但首期不声称完整 inventory。
5. Package 首次下载按当前 metadata 中的 `File`/standard filename 与可用 checksum 校验；上游未提供
   checksum 时仍保存实际 SHA-256/MD5 并标记 provenance。校验失败删除临时 cache，不污染旧 asset。
6. `src/contrib/Archive` 与 binary path 使用相同 RawProxy cache，不从 current index 猜旧版本 URL。
7. 相同 remote path 的并发 miss 由数据库 lease 合并。节点内 single-flight 只减少等待者，不承担
   correctness；任意副本可从数据库状态与共享 Blob 接管。

扫描 Audit 模式可边下载边缓存并在事务提交后异步处理；Enforce 且 pending/failure policy 要求阻断
时，必须先完成 cache、outbox 与扫描决策，再提供 package bytes 或返回可重试策略响应。Metadata
`PACKAGES.gz` 永不作为 package candidate 送入 scanner。

## Group 设计

Group 对有序 R 成员生成自己的 `PACKAGES.gz` snapshot：

1. 读取每个成员 namespace 的 published/verified metadata revision 和 group config revision；offline
   成员、format 不匹配、循环与超深 nested group 按 Nexus fixture 和安全规则处理。
2. 流式归并 DCF record。不同 package 正常聚合；相同 package/version/path 使用最前成员并记录
   conflict metric/audit。相同 package 的多个 version 按 R comparator 选择 current record。
3. 对最终 record 的 package path 写 snapshot-scoped `r_group_binding`，包含 member repository、
   member revision、asset/path、MD5、SHA-256 与 size。
4. 聚合 DCF、bindings 与 hidden snapshot 全部完成后 CAS 发布。Metadata 记录与 package binding
   不得跨 revision。
5. Package GET 先按 current snapshot + exact path 查 binding，再从同一 member/asset 读取；member
   暂时失败不能切到后置成员的另一份 bytes。
6. Member publish/delete/cleanup、proxy metadata refresh、offline、reorder 或 group config 变化推进 group
   desired revision。旧 snapshot/binding 在 grace 内继续可读。

若 Nexus 3.94 的重复 record 合并规则会产生 metadata/package bytes 不一致，kkrepo 不复制这种客户端
checksum 会失败的行为；兼容测试同时记录 Nexus 观测值，并强制 kkrepo 的 metadata、binding 与下载
字节一致。Group 不复制成员 component/asset/SBOM/Usage，只保存小的 durable binding 与生成 snapshot。

## 数据模型与高效索引

大 package、index snapshot、完整 DESCRIPTION/未知字段和扫描文档继续放 OSS/S3。数据库保存
canonical identity、有界 DCF 投影、revision、状态、checksum、lease 与 Blob/asset 引用。

建议的 R 专用表和关键索引如下；MySQL/PostgreSQL 使用同号 migration 与同一 DAO contract：

| 表 | 关键约束与索引 | 热查询 |
| --- | --- | --- |
| `r_package` | `UNIQUE(repository_id,namespace_hash,coordinate_hash)`；`UNIQUE(repository_id,path_hash)`；`UNIQUE(asset_id)`；`UNIQUE(component_id)`；`idx_r_package_index_page(repository_id,namespace_hash,name_key,version_order_key,id)`；`idx_r_package_name(repository_id,name_hash,version_order_key,id)` | exact package、latest/index cursor、Browse/Search、Cleanup family |
| `r_package_tombstone` | `UNIQUE(repository_id,namespace_hash,coordinate_hash,deleted_revision)`；`idx_r_tombstone_cleanup(retain_until,id)` | delete retry、snapshot-safe GC |
| `r_namespace_state` | `PRIMARY KEY(repository_id,namespace_hash)`；`idx_r_publish_due(publish_state,next_attempt_at,repository_id,namespace_hash)` | desired/published revision、pending claim |
| `r_index_snapshot` | `UNIQUE(repository_id,namespace_hash,revision)`；`UNIQUE(index_asset_id)`；`idx_r_snapshot_cleanup(retain_until,id)` | current/retained snapshot、cleanup |
| `r_proxy_index_state` | `UNIQUE(repository_id,namespace_hash)`；`idx_r_proxy_refresh(next_refresh_at,repository_id,namespace_hash)` | validator/TTL/verified revision、stale refresh |
| `r_group_binding` | `UNIQUE(group_repository_id,namespace_hash,snapshot_revision,path_hash)`；`idx_r_group_member(member_repository_id,member_revision,id)` | exact package source、member invalidation |
| `r_publish_lease` | `PRIMARY KEY(repository_id,lease_key_hash)`；`idx_r_lease_expiry(expires_at,repository_id,lease_key_hash)` | coordinate mutation、snapshot/group build fencing |

`namespace_hash` 对终端 repository directory（首期为 `src/contrib`）计算；`coordinate_hash` 对
package name + original version + package type/build namespace 计算；`name_hash`、`path_hash` 和
`lease_key_hash` 只用于控制索引宽度。Hash 命中后必须比较持久化 canonical 字段；理论碰撞不能成为
identity match。`name_key` 使用 binary collation，不能让数据库默认大小写/Unicode collation 改变 R
package identity。

`version_order_key` 是带 codec version、逐段长度与数值的有界二进制排序键，字节序必须与官方
`package_version()` 完全一致。超出编码上限的合法 version 可以保存并通过 comparator 处理，但不能
进入宣称可索引排序的状态；实现应选择覆盖官方允许输入的上限并以 Rscript differential corpus 证明。

通用表继续使用现有索引：`uk_asset_path`、`uk_component_coordinate`、
`idx_component_cleanup_scan`、`idx_asset_cleanup_unbound`、`idx_component_repo_format_updated`、
`idx_security_scan_candidate_queue`、scan task claim/lease index 与 Blob reference index。R writer 必须
在 package 提交事务中写好 component/asset/Browse 投影，正常读路径不允许依赖事后全表 backfill。

数据库访问规则：

- Exact package、current snapshot、proxy state 与 group binding 必须从 unique/primary index 进入，
  禁止 `%filename%`、asset 全表扫描或 JSON/TEXT predicate。
- Index build、Browse/Search、Cleanup、scan backfill、migration 与 snapshot cleanup 全部使用稳定
  keyset/forward-only cursor，不使用随页数增长的 `OFFSET`。
- Latest 选择按 `(repository, namespace, name_key, version_order_key, id)` 覆盖索引流式完成；不能为
  每个 package name 发一条 N+1 query。
- Queue/lease claim 只扫描 due/expired 状态索引；所有 owner update 带 fencing token，失去 lease 的
  worker 不能 finalize。
- 上线前在双数据库各装载至少 1,000,000 个 `r_package`、100,000 个 group binding 与真实比例的
  component/asset/scan/cleanup 行，保存关键 SQL 的 `EXPLAIN ANALYZE`。Exact/高选择性查询不得全表
  扫描；keyset/claim examined rows 必须与 `limit + 1` 同阶。

## Cleanup Policy 接入

R 必须复用当前 `CleanupSubjectScanner`、run/repository lease、cursor、protection、usage 与 history
模型，只增加格式 comparator 和协议删除 adapter：

- 一个 source package version 对应一个 `component` cleanup subject；`PACKAGES.gz`、snapshot、
  tombstone、proxy metadata 与 group binding 都不是独立 cleanable subject。
- `CleanupPolicyCapabilities` 注册 `RVersions.COMPARATOR`，`retainCount` 与 index latest 使用同一官方
  R version 语义。Package family 使用 repository namespace + package name，不能跨 source/binary 或
  R build namespace 互相计数。
- Hosted batch delete 必须调用 `RService.deleteComponentsForCleanup(...)`，先 tombstone + 发布新
  index，再按 grace 解绑 package Blob；不能走通用 Raw asset delete。
- Proxy cleanup 可以淘汰已缓存 package，但必须同时失效对应 projection/source binding；metadata
  snapshot 自身按 TTL/revision 维护，不能因清理一个 package 而返回损坏 DCF。
- Group 不作为 cleanup target。成员被 cleanup 后，group 通过 member revision 重建并保留旧 binding
  到 grace 结束。
- `lastDownloadedAt` 以 package body 的实际成功读取更新；metadata GET、被安全策略阻断、scanner
  读取和内部 snapshot build 不计为用户下载。
- Cleanup dry-run/execute、scan cursor 和 protection lookup 继续命中现有高效索引；R 专用删除过程
  的 tombstone/retention 另由上表索引覆盖。

Race 测试必须覆盖 cleanup 与上传同 coordinate、snapshot build、proxy fill、group GET、安全扫描和
Blob GC 并发。expected content token、usage revision、repository revision 或 fencing token 任一变化时
删除必须安全失败并在下一 cursor cycle 重评估。

## 制品安全扫描接入

R 复用部署能力 gate、通用 `artifact_change_event`、candidate/task/result、scanner snapshot、policy、
waiver、download enforcement 与 Blob reference；asset 写入路径仍不依赖安全扫描表。

格式适配要求：

- `SecurityScanCandidateClassifier` 只把 R package asset kind 且 path 为 source `.tar.gz`（后续可扩展
  binary）分类为 `SubjectKind.ASSET_BLOB` + `TargetClassification.PACKAGE`。
- `PACKAGES.gz`、hidden snapshot、signature/checksum、staging、tombstone 与任意 metadata/static
  `.gz` 不得仅凭扩展名进入 scanner。
- Subject identity 使用 package Blob SHA-256；attributes 增加 package、version、namespace、type、
  path 与 DESCRIPTION provenance。相同 bytes 可复用结果，asset 被新 Blob 覆盖时必须推进 generation。
- scanner-adapter 对 `.tar.gz` 做受限展开，Syft 的 R cataloger 从 `DESCRIPTION` 形成 CycloneDX SBOM；
  Grype 匹配结果保留 scanner/database provenance。服务端和 adapter 都不执行 package 代码。
- 当前 Grype 官方 dedicated language ecosystem 列表不包含 R/CRAN。首期不得把 CPE fallback 或
  缺少 R advisory feed 的结果标成完整 R vulnerability coverage；root R package coverage 应明确为
  `PARTIAL`/capability-limited，policy 的 partial action 由管理员决定。
- Hosted/proxy package GET 在返回 body 前调用 `ArtifactDownloadPolicy`；group 对实际绑定的成员 asset
  评估。Scanner 自身的受控读取绕过下载 enforcement，避免递归阻断。
- Enforce + pending block 的 proxy cold miss 不能先流给客户端再扫描。Audit 默认保持异步，不把
  scanner latency 放到正常下载热路径。

删除与扫描并发时，task 只读 generation 对应 Blob reference；旧 generation 结果不能覆盖新 package，
Cleanup 也不能在 scanner/SBOM document 仍引用 Blob 时物理 GC。部署能力关闭时，R 上传与其它格式
一样不追加 outbox；开启后在同一 package 提交事务写通用 change event，保证无 dual-write 漏洞。

## 认证、权限与 secret

- 读、写、删除继续使用 repository `BROWSE` / `READ` / `ADD` / `EDIT` / `DELETE` 权限与 content
  selector；生成 metadata 不能泄露当前 principal 无权读取的 package。若 selector 过滤会改变
  `PACKAGES.gz`，按 selector/security revision 物化或使用安全过滤后的稳定 snapshot，不能返回全量
  index 后只在 package GET 阻断。
- R base client 可通过 URL credential 访问 Basic Auth，但 UI/文档优先建议从环境或受控配置注入；
  access/error/audit/metric 不记录 URL userinfo、Authorization、proxy credential 或 secret query。
- Proxy 只使用 repository 配置的上游凭据，绝不把客户端 Basic/Bearer 原样转发到 CRAN mirror。
- 401/403/404 的隐藏语义、`WWW-Authenticate` 与匿名访问由 Nexus fixture 固定；不能因为 metadata
  merge 或 negative cache 绕过权限。

## Browse、Search、上传与运维

- Package component 使用 `namespace=src/contrib`、`name=<Package>`、`version=<Version>`、
  `kind=r-source-package`；binary 扩展后使用独立 namespace/kind，不与 source 混成一个 component。
- Asset 展示 canonical path、MD5/SHA-256、size、content type、DESCRIPTION 摘要、依赖、license、
  NeedsCompilation、source member、scan 状态与 last downloaded。
- `PACKAGES.gz`、snapshot、lease 和 proxy validator 在管理端以 metadata/operational state 展示，
  不进入普通 package 搜索结果或 cleanup candidate。
- Admin/UI 与 Components API 上传要求显式 source package file；package/path/version 由 inspector
  校验，不能让表单字段覆盖 archive identity。
- Search 空关键词与 format/repository filter 继续使用 component ordering index；关键词走现有
  `component_search`。R 专用字段只在确认有高选择性产品查询后增加列/索引，不对 DESCRIPTION JSON
  做任意查询。
- Metrics 覆盖 upload/inspect、publish lag/build、snapshot bytes/records、proxy hit/miss/validator、
  group conflict/binding、cleanup、scan policy 与 error reason；package name、path、version 不作为
  无界 label。

## 多副本、一致性与故障语义

R 实现不得依赖单个 JVM 状态作为唯一真相：

- package、namespace desired/published revision、snapshot、tombstone、proxy validator、group binding、
  lease/fencing 与 migration checkpoint 都在数据库；bytes 与生成文档在共享 Blob。
- 节点本地强类型热缓存只能通过 `LocalCacheFactory` 创建，并有 TTL/revision invalidation；跨业务
  TTL/negative cache/per-pod 计数使用 `SharedCache`。缓存丢失只增加数据库/Blob 读取，不改变结果。
- 同 identity build/fill 先以数据库 lease 合并；节点内 single-flight 只是性能优化。Worker takeover
  使用 DB time、lease expiry 与 fencing token，旧 owner finalize 必须失败。
- Package 已提交但 snapshot 未发布时不出现在 metadata；snapshot 已发布但 package 尚未满足 Blob/
  policy 可读条件时不得发布。Group 也遵守相同顺序。
- 旧 snapshot、binding 与 tombstone 有明确 grace/retention；跨副本读到旧 validator 或本地 cache 时
  仍能解析到同一 package bytes。
- Repository offline/delete、member reorder、credential change、scanner policy change 与 migration
  restore 都推进相应 revision/marker，不能只清理当前节点 cache。

## Nexus 迁移设计

- Definition migration 识别 Nexus `r-hosted`、`r-proxy`、`r-group`，保存 blob store、write policy、
  online、remote URL/auth、content/max metadata age、negative cache、routing rule 与 member order。
- Source profile 绑定 Nexus product/version、database generation 与 R content shape。未知版本、插件
  recipe、缺字段或 shape drift 返回 `NEEDS_MANUAL_ACTION`，不能按文件后缀猜完整迁移。
- Hosted content 只迁移可读取、checksum 可验证且通过 `RSourcePackageInspector` 的 `.tar.gz` package。
  目标端通过同一 importer 写 package projection；Nexus `PACKAGES.gz` 与 Browse/Search 派生数据不作为
  真相复制，目标端从已验证 package 重建 snapshot。
- Proxy/group 配置可迁移。Proxy cache 只有管理员显式选择且 source profile 证明 path/blob/validator
  shape 时才迁移；`PACKAGES.rds` 可作为 raw cache，`PACKAGES.gz` 还需重新验证/投影。Group binding、
  lease、negative/local cache 全部在目标端重建。
- Dry-run 报告 repository、namespace、package/version、bytes、checksum、invalid/conflict、unsupported
  binary、proxy cache 与预估 Blob 复用；checkpoint 使用 source repository + stable source identity。
- Migration E2E 在 Nexus 3.94 reference 上传带依赖的 source packages，迁移到 MySQL/PostgreSQL target，
  再用 R 4.5.3 与 4.6.1 运行 available/install/update、checksum 与 Browse/Search 校验。
- 安全扫描部署能力关闭时迁移不创建 scan event；能力开启且管理员选择 backfill 后，通过现有 durable
  backfill cursor 分批进入 candidate，不在迁移事务同步扫描。

## 性能与 Nexus 对比验收

R 实现不能以“`install.packages()` 成功”替代性能验收。实现 PR 必须新增
`scripts/perf/compare-r-nexus.py`、原始 JSON artifact，以及中英文
`r-cran-performance-baseline.md`。方法沿用当前 APT/Conan/Alpine 基线，但覆盖 R metadata、真实客户端、
snapshot publish、proxy/group 和 Cleanup/扫描接入。

### 对比环境与方法

- Reference 使用仓库当前 pin 的官方 Nexus 3.94.x PostgreSQL image；升级 reference 时保留版本化结果，
  不能把跨版本数据混成一个 ratio。
- Candidate 与 Nexus 在同一主机、同一 CPU/memory limit、同类本地 Blob、相同 auth/TLS 层和相同
  package bytes 下交替执行。kkrepo PostgreSQL 做主对比；MySQL 另跑相同正确性、客户端、性能与
  query-plan 矩阵并单独报告。
- 使用 R 4.5.3 与当前稳定 R 4.6.1。每个 HTTP 热场景预热 32 次，并发 16 请求 250 次，执行至少
  3 轮且交替目标顺序，报告每轮和三轮中位数；真实 R client 场景使用隔离 library/cache。
- 基准至少包含一个 4 MiB source package、一个真实 dependency graph、重复 member coordinate、
  20,000-package CRAN-scale namespace 和用于 query-plan 的 1,000,000-row synthetic projection。
- 计时前先校验 status/header、规范化 DCF record、package SHA-256/MD5、Range bytes、group source
  binding 与真实安装结果；错误页、不同 package 或旧 client cache 不能计入吞吐。
- File storage 结果用于与 Nexus 同机方向性比较；另跑 S3-compatible Blob 的 kkRepo-only 多副本
  容量门禁，不能用本地文件结果推断生产对象存储 SLA。

### 必测场景

1. Hosted `PACKAGES.gz` GET、HEAD、If-None-Match/304 与空 repository metadata。
2. 4 MiB source package GET、64 KiB Range、HEAD 与并发相同 path 热读。
3. `available.packages()`、带一层/多层依赖的 `install.packages(type="source")` 与 `update.packages()`。
4. Hosted PUT 的 request latency、package durable commit 到 `PACKAGES.gz` 可见延迟，以及 100 package
   burst 的 debounce/build amplification。
5. Proxy `PACKAGES.gz` 与 package cold fill/warm hit、validator 304、stale/negative cache、32 个相同
   cold request 的 upstream fetch 合并。
6. Group 冷合并、热 metadata、重复 package member priority、nested group 与绑定 package GET。
7. 20k/100k/1m package 的 snapshot build 时间、cursor rows、临时磁盘、peak heap 与 DB connection
   hold time；压缩/OSS 上传阶段不得继续持有读取事务。
8. 安全扫描能力关闭、开启 Audit、开启 Enforce/pending-block 三种模式的 upload/download；关闭时没有
   outbox write，Audit 不等待 scanner，Enforce 的额外延迟单独报告而不与普通 Nexus ratio 混淆。
9. Cleanup dry-run/execute、retain-N、usage/protection 与 package delete 后 group rebuild；所有扫描/删除
   分页都要在大数据集验证 query plan。

### 发布门禁

- 正确性预检、真实客户端和 Nexus 黑盒未通过时，性能数字无效。
- 热 metadata 场景吞吐不得低于 Nexus `0.80x`，p95 不得高于 Nexus `1.25x`。
- 完整 package GET/Range 吞吐不得低于 Nexus `0.90x`，p95 不得高于 Nexus `1.15x`。
- Hosted publish、proxy cold fill 与 group cold merge 的 p95 不得高于 Nexus `1.25x`；同 key 并发 cold
  fill 每个有效 validator generation 最多一次 upstream body fetch。
- 20k package snapshot 常驻 heap 由固定 batch/buffer 上限决定；100k/1m 扩容时 peak heap 不能随
  record 总数线性增长。结果必须同时记录 CPU、heap、临时磁盘、DB rows/queries 与 Blob requests。
- 双数据库 exact/high-selectivity query plan 不得出现全表扫描；keyset/claim examined rows 与 batch
  同阶，snapshot build 不得 N+1。CI 对 index 定义与 optimizer plan 都做 contract assertion。
- 未达门禁时必须附 profile、statement digest、Blob request trace 与 `EXPLAIN ANALYZE` 后复测；不能
  只写“网络抖动”豁免。阈值变更必须在实现 PR 提供原始结果、正确性证据、风险与明确批准记录。

## 测试与兼容性矩阵

### M0：Nexus reference 基线

新增 `RRepositoryBlackBoxCompatibilityTest`，对 Nexus 3.94 与 kkrepo 记录并规范化：

- 三类 recipe 的 REST schema、默认值、write policy、member validation 与 remote configuration。
- Empty repository、source PUT/duplicate/invalid filename、GET/HEAD/Range/conditional/delete 的 status、
  header、content type 与 body。
- `.tar.gz`、普通 `.gz`、`.zip`、`.tgz`、`PACKAGES`、`PACKAGES.rds` 在 hosted/proxy/group 的
  allow/deny 边界。
- `PACKAGES.gz` 字段、排序、gzip、latest version、duplicate member merge、empty/unknown namespace。
- Basic/anonymous/permission/content selector 行为，以及 401/403/404 隐藏语义。
- Proxy redirect、validator、negative/stale cache、Archive path 与文档已知旧版本 404。
- Group member priority、同 path 不同 bytes、nested group、offline member 与 member mutation。

只允许规范化 Date、Server、request ID、gzip timestamp 等已证明非语义字段；package record、checksum、
member choice、status、validator 与响应体语义不能为让测试通过而归一化。

### 协议、数据库与安全测试

- Path/name/version/DCF codec 的 property test、恶意 corpus 与 Rscript differential test。
- Archive traversal、link escape、duplicate DESCRIPTION、encoding、field/entry/size/depth/ratio/timeout
  限制和 fuzz test。
- Hosted idempotency、write policy、snapshot CAS、publisher crash/takeover、delete grace 与 deterministic
  rebuild。
- Proxy metadata/package drift、RDS passthrough、cold-fill lease、negative/stale cache 与 SSRF/redirect。
- Group merge/source binding、member reorder/delete/cleanup、nested cycle 与跨 revision 一致性。
- MySQL/PostgreSQL migration parity、DAO contract、unique conflict、hash collision recheck、version key、
  million-row query-plan 与 online upgrade。
- Cleanup retain-N/usage/protection、scan generation/blob reference、Audit/Enforce、partial R matcher coverage、
  delete/scan/GC race。
- 两副本并发 publish/fill/build/cleanup/scan/migration、owner crash、lease expiry、fencing 与旧 cache 读取。
- Native runtime hints、cache abstraction boundary、AOT smoke 与 controller/protocol module boundary。

### 真实 R 客户端 E2E

Linux lane 运行 R 4.5.3 与 4.6.1：

- `.Rprofile`/explicit repos、`available.packages()`、source install、dependency graph、update 与 reinstall。
- Hosted upload 后安装、proxy CRAN-style controlled upstream、group hosted + proxy resolution。
- Basic/anonymous、GenericToken-capable HTTP 调用方、错误 credential、offline/stale 与第二副本读取。
- Cleanup 后 metadata 不再列出 package、旧 snapshot grace 内读一致、scan policy allow/pending/deny。

Windows/macOS lane 首期验证 proxy 直连 binary `.zip` / `.tgz` 与 `PACKAGES.rds` fallback，同时断言同一
内容经 group 按 Nexus `.gz` 限制失败。Hosted/group binary 只有后续扩展完成后才改为成功断言。

### 迁移 E2E

- Nexus hosted 发布两个 package、多个 version 与依赖，迁移到双数据库 target 后重新生成
  `PACKAGES.gz` 并真实安装。
- Proxy/group definition、member order、TTL/routing/secret handling；显式 proxy cache 选择、resume 与
  checksum drift。
- Unknown source version/shape、invalid package、unsupported binary 与 partial repository 的
  `NEEDS_MANUAL_ACTION` 报告。
- Dry-run 不写 Blob/metadata；中断后 resume 不重复上传；重复执行得到同一 logical result。

## 实施记录

本次落地按原 M0 到 PR5 的边界一次性闭环：Nexus fixture 与 `protocol-r` codec；双数据库
schema/DAO、source inspector 与 durable snapshot；proxy/group、UI/API 与真实客户端；Cleanup、
扫描与多副本 fencing；以及迁移、性能脚本、查询计划门禁和双语文档。后续扩展仍必须保持其它格式
协议、Cleanup、扫描与通用 asset 写入路径回归通过，且不能放宽本文“后续扩展”和“明确不实现”的
边界。

## 验收标准

- `RepositoryFormat.R`、三类 recipe、独立 protocol module 与双数据库 schema 完整，Controller 不含
  协议/metadata 业务逻辑。
- Hosted source publish、deterministic `PACKAGES.gz`、proxy 任意类型直连与 `.gz`-only group 均符合
  官方 R 客户端和 Nexus reference。
- Metadata record、checksum、group binding 与实际 package bytes 始终一致；失败不发布 partial snapshot。
- 所有 durable 状态位于数据库/共享 Blob；双副本故障接管不重复发布、不串 member、不丢 scan/cleanup。
- Cleanup 以 package version 为主体，使用官方 R comparator，并以 snapshot-first 删除避免 index/blob
  竞态。
- 扫描只处理 package，不处理 metadata；R vulnerability coverage 限制明确呈现，Audit/Enforce 与
  Blob reference race 通过。
- MySQL/PostgreSQL 所有 exact/list/latest/claim/expiry 查询命中高效索引；million-row query-plan gate
  通过且无 OFFSET/N+1/JSON scan。
- 同机 Nexus 性能对比、真实 R 4.5.3/4.6.1 E2E、Migration E2E、恶意输入、双副本与 Native/AOT
  测试全部通过，并提交可复现脚本、原始结果和双语性能基线。

## 参考资料

- [R Installation and Administration: Setting up a package repository](https://stat.ethz.ch/CRAN/doc/manuals/r-devel/R-admin.pdf)
- [R `tools::write_PACKAGES`](https://stat.ethz.ch/R-manual/R-devel/RHOME/library/tools/html/writePACKAGES.html)
- [R `utils::available.packages`](https://stat.ethz.ch/R-manual/R-devel/library/utils/html/available.packages.html)
- [R `utils::install.packages`](https://stat.ethz.ch/R-manual/R-devel/library/utils/html/install.packages.html)
- [R `utils::compareVersion`](https://stat.ethz.ch/R-manual/R-devel/library/utils/html/compareVersion.html)
- [R `package_version` / `numeric_version`](https://www.stat.ethz.ch/R-manual/R-devel/library/base/html/numeric_version.html)
- [Writing R Extensions: DESCRIPTION and package structure](https://stat.ethz.ch/R-manual/R-devel/doc/manual/R-exts.html)
- [Sonatype Nexus Repository: R Repositories](https://help.sonatype.com/en/r-repositories.html)
- [Sonatype Nexus Repository 3 Release Status](https://help.sonatype.com/en/sonatype-nexus-repository-3-versions-status.html)
- [R Developer Page and release status](https://developer.r-project.org/)
- [Syft supported package ecosystems](https://oss.anchore.com/docs/capabilities/all-packages/)
- [Grype supported vulnerability ecosystems](https://oss.anchore.com/docs/guides/vulnerability/ecosystems/)
