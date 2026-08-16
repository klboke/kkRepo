# Alpine / APK 与 Nexus 本地性能基线

本文记录 Alpine APK v2 功能落地时 kkRepo 与 Sonatype Nexus Repository 的同机定向基线。它用于比较协议热路径，不等同于生产容量结论；TLS、反向代理、远端 OSS/S3、数据库高可用、读写混合负载以及大 namespace 的端到端发布仍需按目标环境单独测试。

英文版见 [Local Alpine / APK Performance Baseline Against Nexus](../../en/dev/alpine-performance-baseline.md)。

## 测试环境

- 时间：`2026-08-15T09:57:22Z`。
- 主机：Intel Core i9-9880H、64 GiB 内存、macOS 14.7.8 x86_64。
- 容器运行时：Docker 29.4.0（OrbStack），两个仓库容器均未设置独立 CPU 或内存限制。
- Reference：Sonatype Nexus Repository `3.94.0`。
- Candidate：当前分支的 kkRepo `0.8.0` 开发构建，使用 MySQL 8.0.46；另一个 kkRepo 副本共享同一数据库和本机文件型 blob volume，基准请求指向主副本。Nexus 同样使用本机文件型 blob 存储。
- 两端 hosted 仓库上传完全相同的 `4,196,328` byte APK，SHA-256 为 `ae4c61a2d34fa1e99962b38e138cd0ef5477097d0344833ffd9a077ad7e69da3`。
- 两端使用同一 RSA 私钥签名 v2 index；真实客户端按各自 index 声明的签名文件名信任同一公钥。

## 方法

使用 [`scripts/perf/compare-alpine-nexus.py`](../../../scripts/perf/compare-alpine-nexus.py) 测量 signed `APKINDEX.tar.gz` 的 GET、HEAD、条件 `304`，以及完整 APK GET、64 KiB Range 和 HEAD。计时前会拒绝 package bytes 不同、Range bytes 不同、signed index 结构无效或逻辑 `P/V/S/I` package 集合不同的目标。`C:` 必须是 Q1 identity，但不在两端直接比较，因为 Nexus 3.94 对 unsigned direct upload 可能生成不符合 apk-tools 的 Q1 值。

每个 HTTP 场景先预热 32 次，再以并发 16 请求 250 次；共运行 3 轮，在相邻轮次交替 Nexus/kkRepo 顺序，表格取三轮中位数。两个 index 均可见且后台仓库任务稳定后，先执行了一轮短预热。Alpine 3.23 客户端流程执行 `apk update`、exact `apk search` 和 `apk policy` 共 3 轮，同样交替目标顺序。

HTTP 响应时间（RT）从客户端准备写出请求前开始计时，到完整读取响应 body 后结束。16 个 worker 各自复用分配给它的连接，因此每个 worker 的首次请求可能包含建连时间，后续请求反映连接复用后的热路径。GET RT 包含完整 payload 传输，HEAD 与 `304` RT 包含响应 header 处理和预期空 body 的读取。每轮从 250 个请求样本独立计算 p50/p95/p99/max；汇总表取三轮中各项统计量的中位数，不把三轮 750 个样本混为一个分布。本次确认轮共包含 9,000 个计时 HTTP 请求。

运行器强制执行以下发布门禁：

- metadata 路径：kkRepo 吞吐不低于 Nexus 的 `0.80x`，p95 不高于 Nexus 的 `1.25x`；
- package GET/Range：kkRepo 吞吐不低于 Nexus 的 `0.90x`，p95 不高于 Nexus 的 `1.15x`；
- 真实 `apk` 客户端流程：kkRepo p95 不高于 Nexus 的 `1.25x`。

## 结果

9,000 个计时 HTTP 请求全部返回预期状态且 payload 校验通过，运行器输出的 `gate_failures` 为空。

| 场景 | Nexus req/s | kkRepo req/s | 吞吐比 | Nexus p95 | kkRepo p95 | p95 比 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| signed index GET | 1760.65 | 1609.49 | 0.914x | 19.300 ms | 15.291 ms | 0.792x |
| signed index HEAD | 1263.57 | 1566.47 | 1.240x | 24.054 ms | 24.249 ms | 1.008x |
| signed index 304 | 1286.85 | 1880.19 | 1.461x | 22.951 ms | 18.270 ms | 0.796x |
| 4 MiB package GET | 370.48 | 487.99 | 1.317x | 62.154 ms | 46.165 ms | 0.743x |
| package Range 64 KiB | 1361.38 | 1810.76 | 1.330x | 22.960 ms | 16.959 ms | 0.739x |
| package HEAD | 1980.28 | 2622.83 | 1.324x | 13.088 ms | 11.508 ms | 0.879x |

完整 package GET 的中位吞吐为 Nexus `1482.64 MiB/s`、kkRepo `1952.88 MiB/s`。六条 HTTP 门禁全部通过。

### 响应时间明细

下表是每轮 250 请求分布统计量的三轮中位数；`wall` 是一轮完整执行 250 个请求的 wall time 中位数。

| 场景 | 目标 | req/s | MiB/s | wall | p50 RT | p95 RT | p99 RT | max RT |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| signed index GET | Nexus | 1760.65 | 1.11 | 141.993 ms | 6.033 ms | 19.300 ms | 24.695 ms | 26.315 ms |
| signed index GET | kkRepo | 1609.49 | 1.06 | 155.328 ms | 6.807 ms | 15.291 ms | 18.566 ms | 24.399 ms |
| signed index HEAD | Nexus | 1263.57 | 0.00 | 197.853 ms | 9.648 ms | 24.054 ms | 29.264 ms | 39.092 ms |
| signed index HEAD | kkRepo | 1566.47 | 0.00 | 159.594 ms | 7.324 ms | 24.249 ms | 32.539 ms | 36.176 ms |
| signed index 304 | Nexus | 1286.85 | 0.00 | 194.273 ms | 9.683 ms | 22.951 ms | 33.204 ms | 34.021 ms |
| signed index 304 | kkRepo | 1880.19 | 0.00 | 132.965 ms | 6.314 ms | 18.270 ms | 24.343 ms | 32.273 ms |
| 4 MiB package GET | Nexus | 370.48 | 1482.64 | 674.799 ms | 29.671 ms | 62.154 ms | 84.836 ms | 218.357 ms |
| 4 MiB package GET | kkRepo | 487.99 | 1952.88 | 512.311 ms | 28.220 ms | 46.165 ms | 58.677 ms | 74.540 ms |
| package Range 64 KiB | Nexus | 1361.38 | 85.09 | 183.638 ms | 9.613 ms | 22.960 ms | 24.316 ms | 25.477 ms |
| package Range 64 KiB | kkRepo | 1810.76 | 113.17 | 138.064 ms | 6.937 ms | 16.959 ms | 24.845 ms | 29.775 ms |
| package HEAD | Nexus | 1980.28 | 0.00 | 126.245 ms | 6.547 ms | 13.088 ms | 21.860 ms | 23.863 ms |
| package HEAD | kkRepo | 2622.83 | 0.00 | 95.317 ms | 4.461 ms | 11.508 ms | 16.428 ms | 17.622 ms |

### 逐轮吞吐与 RT

下表每个 RT 元组依次为毫秒单位的 `p50 / p95 / p99 / max`。目标执行顺序按轮次交替，并非始终先跑 Nexus。

| 场景 | 轮次 | Nexus req/s | Nexus p50/p95/p99/max ms | kkRepo req/s | kkRepo p50/p95/p99/max ms |
| --- | ---: | ---: | ---: | ---: | ---: |
| signed index GET | 1 | 1758.77 | 6.900 / 15.074 / 24.695 / 26.315 | 1860.63 | 6.665 / 13.860 / 18.566 / 20.216 |
| signed index GET | 2 | 1902.16 | 5.805 / 19.300 / 31.674 / 33.134 | 1609.49 | 6.807 / 23.202 / 43.001 / 44.755 |
| signed index GET | 3 | 1760.65 | 6.033 / 22.967 / 24.488 / 24.644 | 1599.50 | 7.332 / 15.291 / 17.175 / 24.399 |
| signed index HEAD | 1 | 1239.25 | 9.678 / 24.391 / 38.102 / 50.197 | 1566.47 | 7.324 / 30.141 / 38.179 / 51.923 |
| signed index HEAD | 2 | 1263.57 | 9.648 / 23.548 / 29.264 / 37.793 | 1960.21 | 5.683 / 19.549 / 28.286 / 30.477 |
| signed index HEAD | 3 | 1277.53 | 9.460 / 24.054 / 29.212 / 39.092 | 1498.05 | 7.431 / 24.249 / 32.539 / 36.176 |
| signed index 304 | 1 | 1329.09 | 9.617 / 23.490 / 33.204 / 34.021 | 2418.70 | 5.131 / 11.089 / 12.954 / 13.475 |
| signed index 304 | 2 | 1286.85 | 9.824 / 20.749 / 41.666 / 45.499 | 1751.37 | 6.314 / 18.270 / 24.343 / 34.435 |
| signed index 304 | 3 | 1281.03 | 9.683 / 22.951 / 26.289 / 30.674 | 1880.19 | 6.353 / 20.581 / 30.946 / 32.273 |
| 4 MiB package GET | 1 | 401.24 | 32.821 / 62.154 / 84.836 / 86.612 | 485.72 | 28.220 / 46.165 / 51.425 / 74.540 |
| 4 MiB package GET | 2 | 370.48 | 29.671 / 51.378 / 77.458 / 218.357 | 496.24 | 26.468 / 52.505 / 58.677 / 60.340 |
| 4 MiB package GET | 3 | 333.73 | 29.389 / 75.649 / 236.996 / 263.338 | 487.99 | 28.831 / 45.213 / 59.775 / 100.809 |
| package Range 64 KiB | 1 | 1381.76 | 9.613 / 22.960 / 24.316 / 25.477 | 1818.28 | 7.351 / 14.738 / 17.555 / 19.782 |
| package Range 64 KiB | 2 | 1361.38 | 9.577 / 20.891 / 22.650 / 23.246 | 1810.76 | 6.937 / 21.775 / 24.845 / 29.775 |
| package Range 64 KiB | 3 | 1267.61 | 10.569 / 23.712 / 28.927 / 30.413 | 1789.66 | 6.852 / 16.959 / 27.862 / 31.658 |
| package HEAD | 1 | 2424.55 | 5.489 / 13.088 / 15.547 / 15.988 | 2622.83 | 4.461 / 11.508 / 18.099 / 19.535 |
| package HEAD | 2 | 1980.28 | 6.547 / 12.582 / 21.860 / 23.863 | 2761.58 | 4.450 / 10.049 / 16.428 / 17.622 |
| package HEAD | 3 | 1538.17 | 7.624 / 20.253 / 33.648 / 34.613 | 2372.15 | 5.245 / 11.736 / 13.344 / 15.159 |

### 真实 Alpine 3.23 客户端 RT

每个样本都启动一个新容器执行 `apk update`、exact `apk search` 和 `apk policy`。

| 目标 | 第 1 轮 | 第 2 轮 | 第 3 轮 | p50 | p95 | kkRepo / Nexus p95 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Nexus | 1215.133 ms | 1428.791 ms | 1290.416 ms | 1290.416 ms | 1428.791 ms | — |
| kkRepo | 1037.012 ms | 1099.141 ms | 923.475 ms | 1037.012 ms | 1099.141 ms | 0.769x |

真实客户端门禁同样通过。两端均计入容器启动时间，因此绝对耗时不如同机相对结果有意义。

完整机器可读结果已提交到 [`docs/perf-data/alpine-nexus-2026-08-15.json`](../../perf-data/alpine-nexus-2026-08-15.json)，其中包含正确性 preflight、36 组逐轮 HTTP 测量、全部客户端样本和空的 gate failure 列表。文件 SHA-256 为 `6ab6530dda96b5a8e5e489f241b1ef9715baa44b6df426fe246f98867717a550`。

## 10 万行数据库增长验证

HTTP 基线完成后，对数据库访问路径做了独立验证。MySQL 8.0.46 和 PostgreSQL 12.22
加载相同的逻辑数据：10 万 package（路径带刻意构造的长公共前缀）、10 万 relation
（其中 100 条命中）、1 万 suite state（其中 100 条待发布）、100,001 条已发布 snapshot、
10 万条存活 group binding，以及 2 条 group snapshot stage；测量前均执行统计信息更新。

下表是连续 7 次预热 `EXPLAIN ANALYZE` 的数据库 executor time，p50/p95 按 7 个样本的
nearest-rank 取值。结果不包含 JDBC 映射、网络传输、索引序列化、签名、blob 访问和应用层工作。

| 查询形态 | 基数 | MySQL p50 / p95 | PostgreSQL p50 / p95 | 有界访问路径 |
| --- | --- | ---: | ---: | --- |
| asset path 精确查询 | 10 万取 1 | 0.000114 / 0.000121 ms | 0.058 / 0.185 ms | repository + SHA-256 path key，再做完整路径碰撞校验 |
| namespace 尾部分页 | 10 万第 90,000 行后取 2,048 | 5.260 / 5.800 ms | 1.979 / 2.774 ms | repository/distribution/package/id 索引 |
| 待发布 worker | 1 万中 100 条待发布，limit 256 | 0.403 / 0.441 ms | 0.317 / 0.805 ms | pending partial/generated-column 索引，再按 repository PK 点查 |
| snapshot 清理候选 | 100,001 中取 32，保留最新 3 版 | 0.851 / 0.944 ms | 0.681 / 1.272 ms | created-at 清理索引和有界 newer snapshot PK 探测 |
| relation 查询 | 10 万中命中 100 | 0.992 / 1.190 ms | 1.438 / 3.334 ms | repository/kind/token-hash/package 索引 |
| 已发布 group token | 单条 snapshot | 0.000110 / 0.000119 ms | 0.065 / 0.267 ms | snapshot 主键 |
| group binding 尾部分页 | 10 万第 90,000 行后取 2,048 | 3.850 / 5.600 ms | 0.880 / 0.947 ms | group/distribution/revision/token/id 索引 |
| group 发布 stage 锁 | 单条 staged snapshot | 0.0668 / 0.0944 ms | 0.059 / 0.136 ms | stage 主键加 `FOR UPDATE` |
| orphan group stage 扫描 | 2 条 stage、10 万存活 binding | 0.0482 / 0.0562 ms | 0.086 / 0.187 ms | stage 清理索引加 `SKIP LOCKED`；binding 通过外键级联删除 |

MySQL 将两条主键/唯一键点查显示为 `Rows fetched before execution`；其亚微秒值属于优化器/
executor 内部计时，不代表应用层 RT。分页、worker、清理和 relation 行才是更有意义的容量信号。

第一次自然执行计划检查暴露了数据库特有的优化器退化，因此最终 DAO 不会强行让两种数据库
使用完全相同的 SQL 写法：

| 已修复执行计划 | 修复前 | 最终 p50 | 变化 |
| --- | ---: | ---: | ---: |
| PostgreSQL namespace：布尔 keyset filter 改为 row-value range | 67.443 ms | 1.979 ms | 快 34.1 倍 |
| PostgreSQL pending worker：repository-first join 改为 pending-first 相关 PK 点查 | 22.878 ms | 0.317 ms | 快 72.2 倍 |
| PostgreSQL snapshot cleanup：宽范围 join 改为 cleanup-index-first 相关探测 | 862.993 ms | 0.681 ms | 快 1,267 倍 |
| MySQL pending worker：低选择性扫描改为 generated pending 索引 | 145.0 ms | 0.403 ms | 快 360 倍 |
| MySQL snapshot cleanup：全局候选枚举改为有界清理扫描 | 1,824.0 ms | 0.851 ms | 快 2,143 倍 |
| MySQL group binding：snapshot join 改为 token 点查加索引分页 | 20.2 ms | 3.850 ms | 快 5.2 倍 |

PostgreSQL 的 row-value keyset 谓词是刻意按方言生成的；同一写法在 MySQL 上会扫描约
92,000 行、耗时 348 ms，而 MySQL 等价的布尔范围谓词尾部分页 p50 为 5.260 ms。
Orphan 清理只扫描轻量 stage 表，不遍历 10 万条存活 binding；只有选中的 stage 被删除时，
binding 才通过外键级联删除。发布会锁定自己的 stage row，清理跳过已锁 stage，从而避免
清理与发布并发时出现 snapshot 已发布但 binding 被移除的窗口。

全部逐次样本、基数、索引说明和修复前观测值见
[`docs/perf-data/alpine-database-100k-2026-08-15.json`](../../perf-data/alpine-database-100k-2026-08-15.json)，
SHA-256 为 `abbe1f1ade8088a0fc881bdb20de875b8bce6dd078447b0d2c1d4b151934582b`。
这些结果证明 10 万行下数据库热查询仍然有界；完整 index 发布仍不可避免地执行 O(n) package
解析和序列化，CPU、heap、blob I/O 与总 wall time 需要单独设定容量目标。

## 结论与边界

- immutable snapshot 与 asset cache 使预热后的 index 读取不访问 Alpine 业务表；MySQL 仍是跨副本失效所需的持久真相和 version watermark。
- MySQL/PostgreSQL 集成门禁会写入 2,048 条干扰 package/relation 数据，并断言 exact coordinate、有界 namespace keyset 与 relation lookup 的优化器实际选中索引。Namespace 发布沿 `(repository_id, distribution_name, component_name, architecture, package_name, id)` 以 2,048 行 keyset 分页读取，不使用无界排序或 `OFFSET`。
- kkRepo 六条 HTTP 路径的吞吐均达到门禁，完整 package 与 Range 也通过更严格的 package latency 门禁。
- 刻意在上传后立即执行的首轮出现了明显本机抖动，来源包括仓库后台任务和 JVM 路径预热。本文记录的确认轮等待 index 可见和任务稳定，但它仍只是方向性的本机结果，不是 SLA。
- 10 万行数据库基准验证了有界查询计划；在给出生产容量上限前，仍需端到端测量完整发布耗时、heap 上界、生成 index 大小、blob I/O 和 solver 行为。

## 复现

向两端 hosted 仓库上传同一 APK、导入同一 RSA signing key，并等待两个 signed index 都出现该 package，然后执行：

```bash
python3 scripts/perf/compare-alpine-nexus.py \
  --nexus-base-url http://127.0.0.1:48090/repository/<repo> \
  --kkrepo-base-url http://127.0.0.1:59090/repository/<repo> \
  --nexus-auth "$NEXUS_USER:$NEXUS_PASSWORD" \
  --kkrepo-auth "$KKREPO_USER:$KKREPO_PASSWORD" \
  --package-path v3.23/main/x86_64/kkrepo-alpine-benchmark-1.0.0-r0.apk \
  --requests 250 \
  --concurrency 16 \
  --warmups 32 \
  --rounds 3 \
  --enforce-gates \
  --output /tmp/alpine-performance.json
```

使用 `--nexus-apk-command` 和 `--kkrepo-apk-command` 可加入成对的真实客户端命令；运行器会在轮次间交替顺序、记录每个 RT 样本，并把客户端 p95 比纳入门禁。凭据必须留在仓库外；只有经过检查且不包含授权材料的脱敏结果文件才可以提交。
