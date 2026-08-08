# Conda 仓库开发设计说明

本文记录 kkrepo Conda 仓库的协议边界、当前实现和后续演进约束。Conda 仓库不是 Raw 文件目录：服务端必须理解 channel、subdir、package record 和 repodata，并保证索引中声明的 checksum、size 与实际下载字节一致。

## 当前状态

截至 2026-08-08，kkrepo 已实现 Conda hosted、proxy、group 三类仓库：

- 新增 CONDA format、conda-hosted、conda-proxy、conda-group recipe 和独立 protocol-conda 模块。
- 支持仓库根 channel、嵌套 channel、label channel，以及 noarch 和平台 subdir。
- 支持传统 .tar.bz2 与 Conda v2 .conda package。
- 支持 repodata.json、repodata.json.bz2、repodata.json.zst、current_repodata 的三种编码、channeldata.json、notices.json 和空 noarch 索引。
- 支持 hosted HTTP PUT、管理端上传、Components API 上传、GET、HEAD、DELETE、write policy、权限，以及基于 Syft/Grype 的 Conda 感知安全扫描。
- 支持 proxy repodata 投影、CEP 15 package base URL、package checksum/size 校验、共享 proxy cache、negative cache、auto-block、remote auth、redirect 和出站访问策略。
- 支持 group 按成员顺序聚合 metadata，并持久化 package source binding，确保索引记录与下载来源一致。
- 支持 Admin、Browse、Search、cleanup policy、请求指标和双数据库持久化。
- 支持 Nexus Conda repository definition 迁移，以及经过 source version/shape gate 的 hosted package 数据迁移。
- 提供本地 package fixture、协议单元/集成测试、可选的 Nexus/kkrepo 黑盒对照测试，并接入现有 Client E2E 与 Nexus Migration E2E。

CEP 16 sharded repodata 尚未实现，对应路径返回 404。current_repodata 当前返回完整 repodata snapshot，属于客户端可回退的兼容实现；按最新版裁剪仍是后续容量优化。CEP 43/44/45 引入的 schema v3 package record 需要避免进入面向旧客户端的 repodata v1；在尚未提供对应协商或衍生 metadata 前，hosted importer 明确拒绝 schema v3，而不是生成会被旧 solver 误解的索引。真实 Nexus 黑盒矩阵依赖外部环境，compat-test 默认不启动，必须显式设置 CONDA_COMPAT_ENABLED=true 执行；当前 Miniforge Conda 客户端验证则复用现有 Client E2E 流程。

## 协议基线

实现以官方规范为协议真相，以 Nexus Repository 的 recipe、URL 和可观测客户端行为作为兼容性参考：

- Conda [Channel identifiers](https://conda.org/learn/specifications/channels/channel-identifiers/) 定义 channel base、label、noarch 和平台 subdir。
- [CEP 15](https://conda.org/learn/ceps/cep-0015/) 定义 repodata 与 package 分离托管时 `info.base_url` 的下载语义。
- [CEP 26](https://conda.org/learn/ceps/cep-0026/) 定义 package name、version、build、filename、channel、subdir 和 label 标识规则。
- [CEP 34](https://conda.org/learn/ceps/cep-0034/) 与 [CEP 35](https://conda.org/learn/ceps/cep-0035/) 定义 .tar.bz2 和 .conda package container。
- [CEP 36](https://conda.org/learn/ceps/cep-0036/) 定义 packages、packages.conda、removed、checksum、size 和 repodata 压缩变体。
- [CEP 38](https://conda.org/learn/ceps/cep-0038/) 定义 channeldata.json。
- [CEP 16](https://conda.org/learn/ceps/cep-0016/) 定义可选的 sharded repodata。
- [CEP 43](https://conda.org/learn/ceps/cep-0043/)、[CEP 44](https://conda.org/learn/ceps/cep-0044/) 与 [CEP 45](https://conda.org/learn/ceps/cep-0045/) 定义 schema v3 的条件依赖、可选依赖组与 variant flags，以及对旧客户端 repodata 的隔离要求。
- Sonatype [Conda Repositories](https://help.sonatype.com/en/conda-repositories.html) 与 [Conda CLI Usage](https://help.sonatype.com/en/conda-cli-usage.html) 用于固定 Nexus recipe 和 /repository/{repo}/... 入口。

关键约束：

- package identity 至少包含 channel、subdir、name、version、build 和 archive format；同一 package 可以发布到不同 channel。
- package filename 不能靠连字符切分，必须读取 info/index.json，并反向校验 canonical filename。
- .tar.bz2 record 写入 packages；.conda record 写入 packages.conda。
- Conda 版本不是 SemVer。latest 选择使用 protocol-conda 中按 VersionOrder 规则实现的比较器，再比较 build number、build 和 timestamp。
- Proxy 和 group 不能把上游 base_url 或 download_url 原样暴露给客户端。
- Group 的 metadata 选择与 package 下载必须绑定同一成员和同一 checksum。

## 模块与职责

| 模块 | 职责 |
| --- | --- |
| core | CONDA format 和三类 recipe |
| protocol-conda | 严格路径解析、media type、版本比较和协议常量 |
| persistence-jdbc | Conda DAO、revision、tombstone、group binding 和 lease |
| persistence-mysql / persistence-postgresql | V41 Conda schema |
| server/conda | archive inspector、metadata codec、hosted/proxy/group 服务和迁移 writer |
| server 通用入口 | Controller、安全、上传、Browse、Search、cleanup、指标和 repository 生命周期 |
| migration-nexus | Nexus format/recipe 探测、shape gate 和 definition 迁移 |
| admin-ui / browse-ui | recipe、上传、浏览、使用说明和格式图标 |
| compat-test | package fixture 与可选 Nexus/kkrepo 黑盒对照 |

Controller 只负责 HTTP 路由与通用条件请求处理；Conda 协议行为集中在 CondaService、CondaArchiveInspector 和 CondaMetadataCodec。

## URL 与路由

推荐把 group URL 配置为 channel：

    channels:
      - https://repo.example.com/repository/conda-group
    show_channel_urls: true

也可以直接使用嵌套 channel：

    conda search --override-channels \
      -c https://repo.example.com/repository/conda-group/team-a/release demo

路由契约：

| 路径 | Hosted | Proxy | Group |
| --- | --- | --- | --- |
| {channel}/{subdir}/repodata.json | 从数据库投影生成 | 优先复用 zstd/bz2 共享缓存并流式解压，缺失时原样缓存 | 按成员顺序聚合 |
| repodata.json.bz2 / .zst | 同一 JSON snapshot 压缩 | 原样缓存上游表示 | 同一聚合 snapshot 压缩 |
| current_repodata.json 及压缩变体 | 返回完整 snapshot | 返回完整 snapshot | 返回完整 snapshot |
| {channel}/channeldata.json | 从 package record 生成 | 优先安全缓存上游，缺失时生成 | 聚合生成 |
| {channel}/notices.json | 返回空 notices | 安全缓存上游，缺失时返回空 | 返回空 notices |
| {channel}/{subdir}/{package} | 读取 hosted blob | canonical URL 直取并在写缓存时建组件 | 按成员优先级读取 |
| PUT package | 发布 | 405 | 405 |
| DELETE package | 删除并写 tombstone | 405 | 405 |
| repodata_shards.msgpack.zst | 404 | 404 | 404 |

路径只 percent-decode 一次，并拒绝 query、fragment、编码后的分隔符、二次编码、空段、点段、反斜杠、控制字符和超长字段。普通 channel segment 使用小写规范；label 后允许官方 label 形态。subdir 必须是 noarch 或合法的平台-架构形式。

已知 channel 始终可以读取 noarch/repodata.json；没有 noarch package 时返回合法空索引。未知的非 noarch subdir 返回 404。

## Hosted 发布

Raw HTTP 上传示例：

    curl --fail --user "$CONDA_USER:$CONDA_PASSWORD" \
      --upload-file ./demo-1.2.3-py_0.conda \
      https://repo.example.com/repository/conda-hosted/team-a/linux-64/demo-1.2.3-py_0.conda

所有上传入口复用同一个 importer：

1. 校验 hosted recipe、write policy、权限、channel、subdir 和 filename。
2. 将上传流写入受限临时文件，同时计算 MD5、SHA-256 和 size。
3. .tar.bz2 有界读取 info/index.json；.conda 校验 ZIP_STORED outer entries、metadata.json、唯一且 identity 一致的 info/pkg tar.zst，再有界读取 info/index.json。
4. 对传统 archive 和 v2 info archive 拒绝路径穿越、绝对路径、重复 entry、special file、越界 link、超量 entry、超大展开字节、超时和并发检查过载。
5. 校验 name、version、build、build_number、subdir、dependency 集合和 canonical filename。
6. 在坐标 lease 和数据库事务之外，把已校验的 package 绑定到隐藏的 `.conda/staging/<uuid>/...` asset；长时间对象存储上传不会占用数据库事务。
7. 获取数据库 lease，在一个短事务内同步续租并锁定 lease 行，复查目标路径、write policy、已有 record 和实际 blob checksum/size，再把同一 blob 引用晋升到最终 asset/component，提交 Conda package record、noarch 状态和 repository revision。
8. 事务提交后解除 staging 引用；失败时也做 best-effort 清理。metadata 和 Conda package GET 只以已提交的 Conda package record 为可见真相，因此 staging、回滚或孤儿 blob 都不会进入 channel。副本异常退出遗留的 staging asset 由所有副本均可运行的有界 worker 通过数据库行锁和 `SKIP LOCKED` claim，解除最后引用后再交给全局 Blob GC。

删除先在数据库中删除 active record、写 tombstone 并递增 revision，再删除 asset。repodata 的 removed 集合只包含当前没有 active record 的 filename。

## Metadata 生成与性能边界

Hosted/group repodata 按 repository、channel、subdir、有序成员及各成员当前 channel state
计算内容 identity，生成型 channeldata 按 repository revision 物化为隐藏的
`.conda/generated/<channel-hash>/.../<identity>/...` asset。命中时直接读取共享 blob；只有新
identity 第一次访问才从数据库投影，并按请求编码为 JSON、BZIP2 或 Zstandard。这样同仓库其它
subdir 的变化不会使当前 subdir 的大索引失效：

- package filename 使用有序 map，removed 使用有序集合。
- record 顶层字段、info、channeldata package 和可选字段顺序固定。
- JSON、BZ2、ZSTD 分别按响应字节计算稳定 ETag。
- info.subdir 和 repodata_version 都写入响应。
- channeldata 按 package name 聚合 subdir，并按 Conda VersionOrder/build 规则选 reference_package。
- package record 使用 JDBC cursor 按 filename 流式写入可重放 spool，随即释放数据库连接；renderer 再从 spool 写入压缩临时文件，不会在压缩期间持有数据库游标，也不同时保留完整 record list、JSON 和压缩字节数组。channeldata 只保留当前 package name 的聚合状态。
- BZIP2 使用与 Nexus 一致的 block size 9。小型 group 冷合并先一次序列化完整 JSON，再批量压缩，避免 Jackson 的细粒度写入放大 BZIP2 CPU；超过内存上限的输入先落盘为 package collection spool，再批量压缩。64 KiB 缓冲位于压缩器输出侧、SHA-256 和文件 sink 之前，避免压缩器的细粒度输出放大 digest 与文件写入成本。
- 同一节点先按生成路径 single-flight，再用有界 semaphore 限制 CPU/临时磁盘密集的 metadata build；不同坐标可并行，多副本通过数据库 lease 合并同一 identity 的冷构建，等待者直接消费胜者写入的 blob。
- `current_repodata` 在尚未实现裁剪时与完整 snapshot 复用同一 revision/编码物化结果。
- 旧 revision 物化 asset 由有界 cleanup worker 清理，默认保留 7 天；删除后仍可由数据库状态确定性重建。

物化结果只是可丢失缓存，不是协议真相。任意副本只依赖数据库 revision/package state 和 blob
store 即可验证或重建响应；节点本地缓存丢失不影响正确性。

## Proxy

Proxy remote URL 表示上游 channel root。本地 channel/subdir 路径直接追加到该 root。

Repodata 流程：

1. 客户端直接请求 `repodata.json.zst` 或 `repodata.json.bz2` 时，按原路径、原编码通过共享 RawProxy 缓存；RawProxy 负责 validator、TTL、negative cache、auto-block、remote auth、redirect 和 OutboundRequestPolicy。
2. 客户端请求未压缩 `repodata.json` 或 `current_repodata.json` 时，proxy 依次选择对应的 Zstandard、BZIP2 共享快照作为 backing blob，读取 frame 声明的解压长度并流式解压响应，不再把同一份百兆级 JSON 作为第二个 S3 object 同步写入。ETag 从 backing blob identity 稳定派生，HEAD、条件请求和多副本 cache miss 继续使用同一数据库 lease；上游没有压缩表示时才回退原始 JSON。
3. 直连 proxy 的首请求不写 package projection，也不重压缩上游 BZIP2/Zstandard。异常 package URL 确需 inventory 时也复用同一压缩快照有界解压，不再固定缓存原始 JSON。
3. 常规 metadata、group 聚合和可由请求路径解析的 package 都不触发 inventory projection。只有非标准 `info.base_url`、无法按 Nexus route token 解析的异常 filename，或已有受校验 inventory 的刷新才按需投影完整索引。节点本地延迟队列只负责限流和相同 coordinate 去重，真正的跨副本互斥与完成状态仍由数据库 lease、channel state 和共享 blob 保证。
4. 若共享 RawProxy blob 的 SHA-256 与 channel state 一致且仍在 TTL 内，直接复用 inventory，不再解析 JSON 或写数据库。
5. 上游字节变化时限制 metadata 最大字节数，流式解析 packages 与 packages.conda，拒绝无 checksum、size/coordinate/subdir 不一致、重复 filename 或记录过大的上游数据。记录写入可重放临时 spool，解析期间只保留单条 record。
6. 解析并验证 `info.base_url`；将它作为内部 channel state 持久化，同时递归删除 package record 中的 `base_url` 与 `download_url`。数据库以 canonical record SHA-256 计算增删改，只批量写入变化行并删除消失行；只有协议投影变化才递增 revision，仅上游 validator/base URL 变化时更新 state 而不使大 channel metadata 失效。
7. `record_sha256` 为空的存量行只在第一次真实上游变化时流式计算并分批回填，后续同步不再读取整列 record JSON。

Package 流程：

1. 严格路径解析后先查已投影 record。已知 filename 按 CEP 15 `info.base_url` 或 repodata 同目录回源，缓存完成后校验 size 与 SHA-256；只有上游未给 SHA-256 时才回退 MD5，checksum drift 会删除 asset 并 fail closed。
2. 首个尚未投影的 canonical package 请求和 Nexus 一样直接走共享 proxy blob cache。服务端按 Nexus package route 从 filename 右侧提取 name、version、build，并在同一个缓存写事务中创建 Conda component 与 `channel/subdir/name/version/filename` Browse 路径；不会为了显示目录而扫描、解析和批量写入整个 repodata。
3. 旧版本留下的未绑定缓存 asset 在下一次命中时复用同一 blob 原地补齐 component/Browse binding，不重新下载。Conda 客户端仍按刚取得的上游 repodata checksum 校验下载；已有 inventory 的记录继续在服务端校验 size 与 checksum，drift 时删除缓存并 fail closed。
4. 无法可靠从 filename 投影坐标时才异步构建 inventory；非标准 `info.base_url` 导致 canonical URL 404 时同步投影后按已验证 URL 重试。未投影 asset 仍是共享 asset/blob，继续进入通用 cleanup、审计和按扩展名分类的安全扫描链路。

跨副本 metadata/package miss 继续由数据库 lease 和通用 RawProxy 共享缓存合并；组件、asset 和 Browse 节点持久化在共享数据库中，节点本地调度器只承担异常路径的投影限流，不承担正确性。

## Group

Group 对有序 Conda 成员递归生成 snapshot：

- 跳过 offline 或非 Conda 成员，并检测运行时循环。
- package filename 冲突时第一成员优先；packages 与 packages.conda 根据文件格式分别输出。
- tombstone 不会覆盖仍由其它成员提供的 active package。
- 只有一个 proxy 成员的 group 直接委托该 proxy 的共享缓存，不做无意义的 JSON 解析和重压缩。
- 纯数据库成员的 repodata 使用 window query 按成员优先级选择同 filename 的首条记录，并通过 JDBC cursor 直接送入 renderer；不会在 JVM 中构建完整成员 snapshot。Tombstone 是否仍有 active record 也使用分批集合查询，避免逐条 N+1。
- 含 proxy 的 mixed group 冷请求不等待 package inventory 写入 MySQL。已有新鲜快照时按 Zstandard、BZIP2、JSON 顺序复用；全冷时也依次回源，并把上游压缩表示原样存入共享 RawProxy，只在合并阶段有界解压。这样 OSS/S3 的首次写入和跨副本读取通常从百兆级 pretty-printed JSON 降到十余兆，而上游缺少压缩表示时仍兼容回退原始 `repodata.json`。解压后的聚合原始字节不超过 16 MiB 时按 Nexus 3.94 的方式把每个成员解析一次、合并 JsonNode、一次序列化后批量压缩；更大的聚合只扫描一次 JSON，把连续且无需改写的安全 package record 作为原始字节大区间复制到磁盘 spool，复制时用字符串感知状态机删除 JSON 非语义空白，仅对含 `base_url`/`download_url` 的记录重新序列化，再整块压缩。两条路径都会递归移除远端地址字段、执行成员优先级去重并合并 `removed`，不会在响应后追加仅供 Browse 使用的全量 inventory 任务。
- 客户端实际请求某个 package 时先按成员顺序尝试 canonical URL，并在被选成员的 cache-write 事务中只创建该制品的 component/Browse 路径；已有可信 record 时才持久化该 filename 的 group repository、channel、subdir、member repository、member revision、content identity 和 group config revision，不为大 channel 全量写 binding。
- Package GET 先刷新已绑定的 proxy 成员 inventory，再验证 binding、group revision、member revision、content identity 和 checksum；任一值变化就重新聚合并只绑定当前请求的 package。验证通过后按该次快照中的精确 record 取包，不再做第二次 inventory 刷新。
- 成员发布、删除或 group 配置变更会递增相关 repository revision；旧 binding 通过 revision 比较惰性失效，不再同步批量删除整组 binding。

Group 不接受上传，也不会把写请求隐式转发到 hosted 成员。

真实 Nexus 3.94 对重复 filename 存在不一致：group repodata 可能被后置成员的 checksum
覆盖，但 package GET 仍返回首成员字节；嵌套 channel 的 hosted PUT 也会规范化到根 channel。
kkrepo 不复制这个会导致客户端 checksum 校验失败的行为，而是让首成员的 metadata、持久 binding
和下载字节保持一致；黑盒测试同时记录 Nexus 的可接受观测值并强校验 kkrepo 的一致性。

## 数据模型与多副本语义

V41 在 MySQL 和 PostgreSQL 中增加：

| 表 | 用途 |
| --- | --- |
| conda_package_record | hosted/proxy package 的有界协议投影和 asset/component binding |
| conda_channel_state | repository/channel/subdir revision、proxy metadata hash 与 CEP 15 package base URL |
| conda_package_tombstone | hosted 删除记录 |
| conda_group_source_binding | group record 到成员/checksum/revision 的持久绑定 |
| conda_coordinate_lease | hosted publish 与 proxy inventory 的可续约 fenced lease |

Repository revision 复用共享 cache_version 表，并向包含该成员的 group 传播失效。package blob 仍只存 OSS/S3 或开发用 File storage；数据库保存 metadata 投影、checksum、引用和协调状态。

V41 在初始 Conda schema 中直接包含 package record fingerprint，以及面向 repodata 流式扫描和 channeldata/group 优先级查询的复合索引。异常协议路径确需同步 inventory 时使用 500 行有界 batch，避免十几万行记录变化时形成超大 JDBC 参数集合或全表 delete/insert；常规 Browse 不执行这条批处理链路。

正确性不依赖 JVM 内存：

- lease owner、fencing token 和到期时间持久化在数据库；获取与释放使用独立短事务，使租约在外层发布、迁移或 cleanup 事务中也对其它副本可见。
- lease 在工作期间由 virtual thread 续约；最终状态变更前还会在同一数据库事务内条件续租并锁定 lease 行，外层事务存在时延迟到事务完成后释放，从而阻止已失去租约的副本提交可见状态，也避免 cleanup 锁定 asset 后另起删除事务造成反向等待。
- package record、channel state、tombstone 和 group binding 都可由其它副本直接读取。
- 上传临时文件仅用于流式检查；共享 blob/asset staging 也保持隐藏。节点退出最多留下不可见的 staging/orphan blob，不会形成 active Conda record；staging cleanup 使用共享数据库年龄水位、有界 batch 和行锁跨副本接管，不依赖某个 JVM 存活。
- metadata build、archive inspection、hosted publish 和 migration importer 都有节点级有界并发；数据库 lease 提供跨副本 single-flight 与 fencing。进程内 cache 和 semaphore 只作为可丢失的节点热状态，丢失后可从数据库和 blob store 重建。

## 权限、管理与浏览

权限沿用 repository privilege：

| 操作 | 权限 |
| --- | --- |
| metadata/package GET、HEAD | READ |
| hosted 首次上传 | ADD |
| hosted overwrite | EDIT 且 write policy 允许 |
| hosted DELETE | DELETE 且 write policy 允许 |
| proxy/group 上传 | 拒绝 |

路径明确的 HTTP PUT 可以在安全过滤器中区分首次上传与覆盖。Components API 和管理端 multipart 上传在鉴权阶段尚不能可信解析最终路径，因此统一要求 EDIT，避免允许 redeploy 时由 ADD 权限绕过覆盖授权。

Conda 已接入 Basic/token/anonymous 的通用安全链路；显式无效 credential 不回退匿名。Proxy remote secret、redirect 和 SSRF 防护沿用共享 remote HTTP 实现。

安全扫描只把 `.conda` 和 `.tar.bz2` package 判为候选，repodata、channeldata 和其它协议 metadata 不进入扫描队列。Conda package 使用独立 subject kind，使其专用 catalog 结果不会命中或污染普通压缩包的 content-addressed SBOM 复用。Scanner adapter 先按通用输入、entry、展开字节、嵌套深度和 deadline 限额检查整个 archive；Conda tar payload 只允许解析后仍位于 archive 内的 symlink/hardlink，路径穿越、绝对目标和 special file 继续 fail closed。该遍检查同时捕获有界的 `info/index.json`，catalog 准备阶段复用它，不再第二次展开 info tar；`.conda` 仍会快速复核 outer ZIP、payload identity 和 `metadata.json`。随后在请求临时目录中生成唯一的 `conda-meta/package.json`，让 Syft 的 `conda-meta-cataloger` 生成 CycloneDX；原 package payload 不落地、不执行。响应中必须存在 name/version 匹配且 `syft:package:type=conda` 的 component，否则以 `CONDA_CATALOG_EMPTY` 失败，不能把零 component 当作完成。匹配后的 SBOM 继续进入既有 Grype、快照一致性、重试、结果保留和下载策略链路；扫描器数据库过期或不可用时沿用全局 readiness 的 fail-closed 行为。

Admin UI 可以创建三类 recipe、设置 remote、write policy 和有序 group 成员。Browse UI 提供 Conda 图标、上传表单，以及与 Nexus 一致的 channel/subdir/name/version/package 层级；build 保留在 package identity 和详情中，不额外占用一层目录。Browse 路径只是逻辑投影，不改变客户端下载路径或 blob 存储结构。Cleanup 使用 Conda VersionOrder，不复用 SemVer。

## Nexus 迁移

迁移必须同时通过 source version 和 datastore shape gate：

- 识别 format=conda 的 hosted、proxy、group definition。
- 迁移名称、online、blob store、write policy、remote URL 和有序成员；remote URL 使用统一规范化。
- hosted data 只接收 .tar.bz2 与 .conda package asset，过滤 Nexus 生成的 repodata/channeldata。
- source package 必须带可验证 SHA-256；目标端重新执行 archive inspector，核对 size、name、version 和 checksum，再通过正常 hosted importer 发布。
- 同 checksum 的重复运行幂等；同路径不同字节 fail closed。
- 迁移导入和在线 hosted 发布共用有界 publish permit，archive inspector 也有独立有界并发，避免批量迁移压垮临时磁盘、CPU 或对象存储连接池。
- 当前不迁移 proxy cache，也不把 proxy package 提升为 hosted publication。

源 Nexus 无 Conda datastore shape、版本早于已知 Conda recipe，或 asset 不能证明 package 身份时，迁移报告必须阻止自动数据恢复。

## 测试与兼容性

自动测试覆盖：

- path、label、subdir、percent decoding 与非法路径。
- VersionOrder 代表性序列。
- .tar.bz2/.conda 正常归档，以及 traversal、link、special file、identity、compression method 和资源限制。
- repodata parse/render、空上游、JSON/BZ2/ZSTD 等价、稳定输出、tombstone、channeldata latest。
- hosted 发布/删除、空 noarch、current_repodata fallback。
- cleanup retain 对同一 version 的全部 build 一致保留、hosted 协议删除、proxy cache 删除，以及跨副本 staging 回收和 Blob GC 交接。
- 安全扫描候选分类、两种 package 的有界 `info/index.json` 投影、安全 link、真实 Conda component 识别和零 component fail-closed。
- proxy inventory、CEP 15 绝对/相对 package base URL、package checksum drift 和缓存删除。
- group member priority、持久 binding 和 revision 失效。
- Controller GET/HEAD/PUT/DELETE 路由。
- MySQL/PostgreSQL V41 migration、DAO revision、binding 和 lease。
- Nexus version/shape gate、definition migration 和 hosted data writer。
- Admin/Browse 合约与本地生成的两种 package fixture。
- 现有 Client E2E 中使用 Miniforge Conda 完成 hosted 上传、group search/create/list、proxy search/create/list，并通过现有 cleanup Try Run/Execute 删除真实客户端 fixture。
- 现有 Nexus Migration E2E 中在 Nexus 3.92/3.94、H2/PostgreSQL source lane 创建 hosted/proxy/group、发布可安装 package、执行 source/target Conda 客户端验收，并校验定义、blob checksum、协议表行数与多副本读取。

compat-test 中的 CondaRepositoryBlackBoxCompatibilityTest 默认只运行自包含 package fixture。设置下列环境变量后，才运行真实 Nexus/kkrepo 对照：

    CONDA_COMPAT_ENABLED=true
    CONDA_NEXUS_COMPAT_BASE_URL=http://...
    CONDA_KKREPO_COMPAT_BASE_URL=http://...
    CONDA_NEXUS_COMPAT_USERNAME=...
    CONDA_NEXUS_COMPAT_PASSWORD=...
    CONDA_NEXUS_COMPAT_BLOB_STORE=default
    CONDA_KKREPO_COMPAT_USERNAME=...
    CONDA_KKREPO_COMPAT_PASSWORD=...
    CONDA_KKREPO_COMPAT_BLOB_STORE=default

黑盒用例覆盖 hosted 根/嵌套 channel、两种 package、repodata JSON/BZ2/ZSTD/current/noarch、group priority、conditional request 和 proxy package 校验。真实 `conda search/create/list` 已在现有 Client E2E 和 Migration E2E 中运行，自包含 HTTP fixture 仍只用于快速协议回归，不能替代真实客户端验收。

## 已知限制与后续演进

- 实现 CEP 16 sharded repodata，并安全处理 proxy shard URL。
- 为 CEP 43/44/45 schema v3 package records 增加客户端能力协商或独立 metadata 衍生路径，再开放 hosted 发布。
- 根据 conda-index 语义生成裁剪后的 current_repodata，而不是当前完整 snapshot fallback。
- 为 group 超大 channel 增加可观测的预热/容量策略，进一步降低首次本地投影等待。
- 在当前 Miniforge Conda/libmamba Linux lane 基础上，增加 Conda 4.6+、micromamba 与 macOS/Windows 多平台矩阵。
- 增加多副本 kill/restart、对象存储故障、长时间 lease 接管和大 channel 容量压测。
- 视实际兼容需求扩展 notices、channel relations 和 verified signature。

这些优化不能改变现有安全边界：数据库仍是 active package、revision 和 binding 的真相，package blob 仍在 OSS/S3，任何缓存或物化结果都必须可丢失、可校验、可重建。

## 验收边界

当前代码级验收要求：

- 三类 recipe 可创建，并能通过统一 /repository/{repo}/... 路由使用。
- 两种 package format 都经过安全 inspector，hosted metadata 与 package checksum/size 一致。
- 根 channel、嵌套 channel、空 noarch、JSON/BZ2/ZSTD/current fallback 可用。
- Proxy 压缩 metadata 首次请求按 Nexus 语义原样缓存；未压缩 JSON 从同一共享压缩 blob 流式派生，上游无压缩表示时兼容回退。Package 在同一写事务中建立单制品 component/Browse 路径；已有 inventory 的 package 在响应前校验，checksum drift 删除缓存并 fail closed，常规请求不做全 channel Browse projection。
- Group 索引选择与 package bytes 绑定同一成员。
- MySQL/PostgreSQL、迁移、UI、Browse、Search、cleanup 和安全链路都有 Conda 接入；安全扫描必须产出匹配 package name/version 的 Conda component。
- 单元、集成和自包含 black-box fixture 全部通过；现有 Client E2E 与 Nexus Migration E2E 包含 Conda lane，不另建独立 workflow/job。
- 当前 Miniforge Conda 能通过 group 搜索并安装 hosted fixture、通过 proxy 搜索并安装上游 package，cleanup 能删除该 fixture；迁移 E2E 能从受支持 Nexus source 恢复同一可安装 package。

上线前环境级验收另行要求：

- 对目标 Nexus 版本重跑 opt-in 对照测试。
- 补充 Conda 4.6+、micromamba 与目标操作系统平台的客户端矩阵。
- 在真实 MySQL/PostgreSQL、S3/OSS 和多副本部署中验证并发、故障接管和容量。

## 参考资料

- [Conda channel identifiers](https://conda.org/learn/specifications/channels/channel-identifiers/)
- [Conda package specification](https://docs.conda.io/projects/conda/en/24.11.x/user-guide/concepts/pkg-specs.html)
- [CEP 15: separate repodata and package hosting](https://conda.org/learn/ceps/cep-0015/)
- [CEP 26: package and channel identifiers](https://conda.org/learn/ceps/cep-0026/)
- [CEP 34: .tar.bz2 package format](https://conda.org/learn/ceps/cep-0034/)
- [CEP 35: .conda package format](https://conda.org/learn/ceps/cep-0035/)
- [CEP 36: repodata](https://conda.org/learn/ceps/cep-0036/)
- [CEP 38: channeldata](https://conda.org/learn/ceps/cep-0038/)
- [CEP 16: sharded repodata](https://conda.org/learn/ceps/cep-0016/)
- [CEP 43: conditional dependencies](https://conda.org/learn/ceps/cep-0043/)
- [CEP 44: optional dependency groups](https://conda.org/learn/ceps/cep-0044/)
- [CEP 45: simplified variant selection](https://conda.org/learn/ceps/cep-0045/)
- [Creating custom channels](https://docs.conda.io/projects/conda/en/stable/user-guide/tasks/create-custom-channels.html)
- [Sonatype Nexus Conda Repositories](https://help.sonatype.com/en/conda-repositories.html)
- [Configure Conda with Nexus](https://help.sonatype.com/en/configure-conda-with-nexus.html)
- [Conda CLI Usage with Nexus](https://help.sonatype.com/en/conda-cli-usage.html)
