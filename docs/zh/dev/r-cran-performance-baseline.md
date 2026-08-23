# R / CRAN 与 Nexus 本地性能基线

本文记录 kkRepo R / CRAN 仓库实现的同机发布基线。它比较协议热路径和数据库访问路径，不等同于
生产容量承诺；TLS、反向代理、远端 OSS/S3、读写混合流量以及部署环境的 CPU/内存限制仍需按
实际工作负载验证。

英文版见 [Local R / CRAN Performance Baseline Against Nexus](../../en/dev/r-cran-performance-baseline.md)。

## 测试环境

- 时间：2026-08-21。
- 主机：Intel Core i9-9880H、64 GiB 内存、macOS 14.7.8 x86_64。
- 容器运行时：Docker 29.4.0（OrbStack）；两个仓库容器均未设置独立 CPU 或内存限制。
- Reference：Sonatype Nexus Repository 3.94.0，使用 PostgreSQL 16。
- Candidate：当前分支的 kkRepo 0.9.0 开发构建，使用 PostgreSQL 16.15；第二个 kkRepo 副本共享
  数据库和本机文件型 blob volume。数据库保留了索引门禁写入的 100 万条 R projection，HTTP fixture
  使用独立 repository。Nexus 同样使用本机文件型 blob 存储。
- 两端 hosted 仓库包含完全相同的 4,196,303-byte source package，MD5 为
  `a3ec5c3433f446c96584e0f50dfa2494`，SHA-256 为
  `a138ae4d1bafeb4689bb703e19ae06a4d55fc4771d14ca15430b405cf7cc4d0c`。

## 正确性预检

对比运行器会在计时前校验逻辑 DCF package record、完整 package bytes、64 KiB Range bytes、必要
状态码和所有已声明的 `MD5sum`；任一不一致都会拒绝产生性能结果。运行基准前，Nexus/kkRepo
黑盒兼容套件也已通过，覆盖 source package、生成 index、HEAD/Range 和条件请求行为。

Nexus 3.94 对本次直接上传的 source package 没有在 index 中输出 `MD5sum`，kkRepo 则按 CRAN
工具预期输出 checksum。运行器因此比较除此字段之外的全部 DCF 字段，并把两端实际声明的每个
checksum 独立对照 byte-identical package。Nexus 对测试的 `If-None-Match` 返回 `200`，kkRepo
返回 `304`；运行器分别验证并报告真实状态，不为凑齐结果而归一化。

真实客户端 E2E 使用隔离的 R 4.5.3 与 R 4.6.1 容器。两个版本均通过
`available.packages()`、依赖安装、package 更新、经 group 安装 proxy package、直接读取 gzip/RDS
proxy 内容和双副本一致性验证。Cleanup dry-run/execute 也成功删除选中的 package 并重建 group
发布视图。

## HTTP 方法与门禁

[`scripts/perf/compare-r-nexus.py`](../../../scripts/perf/compare-r-nexus.py) 测量预热后的
`PACKAGES.gz` GET、HEAD、条件请求，以及完整 package GET、64 KiB Range 和 package HEAD。每个
场景预热 32 次，再以并发 16 请求 250 次；共执行 3 轮并交替目标顺序，各项汇总取逐轮统计量的
中位数。本次记录共包含 9,000 个计时 HTTP 请求。

运行器强制执行以下发布门禁：

- metadata 吞吐不低于 Nexus 的 `0.80x`，p95 不高于 Nexus 的 `1.25x`；
- package GET/Range 吞吐不低于 Nexus 的 `0.90x`，p95 不高于 Nexus 的 `1.15x`；
- 传入成对 R 客户端命令时，kkRepo client-flow p95 不高于 Nexus 的 `1.25x`。

## HTTP 结果

全部正确性预检和 9,000 个计时请求均通过，机器可读结果中的 `gate_failures` 为空。

| 场景 | Nexus req/s | kkRepo req/s | 吞吐比 | Nexus p95 | kkRepo p95 | p95 比 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| `PACKAGES.gz` GET | 1449.35 | 1412.80 | 0.975x | 27.288 ms | 25.160 ms | 0.922x |
| `PACKAGES.gz` HEAD | 1408.73 | 1736.60 | 1.233x | 29.062 ms | 17.849 ms | 0.614x |
| `PACKAGES.gz` 条件请求 | 1395.73 | 1811.50 | 1.298x | 18.829 ms | 16.819 ms | 0.893x |
| 4 MiB package GET | 276.92 | 442.26 | 1.597x | 88.223 ms | 48.998 ms | 0.555x |
| package Range 64 KiB | 1146.96 | 1355.08 | 1.181x | 30.996 ms | 26.484 ms | 0.854x |
| package HEAD | 1389.28 | 1864.14 | 1.342x | 26.040 ms | 21.392 ms | 0.822x |

配对的 R 4.6.1 client flow 每次启动新容器，执行 `available.packages()`，把 source package 安装到
隔离 library 并校验已安装版本。3 轮交替执行中，Nexus p50/p95 为 3400.573/7437.542 ms，kkRepo
p50/p95 为 4669.828/5118.196 ms；kkRepo/Nexus p95 比为 `0.688x`，真实客户端门禁通过。

包含正确性预检、全部逐轮数据和真实客户端样本的结果文件为
[`docs/perf-data/r-cran-nexus-2026-08-21.json`](../../perf-data/r-cran-nexus-2026-08-21.json)，
SHA-256 为 `dcaacb60171fbb9e0dc2621751267d65369de3e5ca75b871b83fe143cc3c696f`。

## 百万行数据库索引门禁

数据库门禁向 MySQL 与 PostgreSQL 写入相同逻辑规模：1,000,000 条 package projection、100,000
条 relation、10,002 条 suite state（100 条待发布）、100,002 条 snapshot、100,000 条 group
binding、100,000 条 tombstone 和 100,000 条 lease。更新统计信息后再采集 `EXPLAIN ANALYZE`。

| 查询形态 | 基数 | MySQL | PostgreSQL | 索引访问路径 |
| --- | --- | ---: | ---: | --- |
| coordinate 精确查询 | 100 万取 1 | 0.000130 ms | 0.178 ms | repository + coordinate SHA-256 唯一索引 |
| asset path 精确查询 | 100 万取 1 | 0.000126 ms | 0.206 ms | repository + path SHA-256 唯一索引，再校验完整 path |
| package 尾部 keyset 分页 | 第 900,000 个 package 后取 2,048 | 13.300 ms | 7.928 ms | namespace/package/id range；只检查 2,048 行 |
| latest package version | 100 个版本取 1 | 0.193 ms | 0.185 ms | package/version-order 索引 |
| relation 查询 | 10 万中命中 100 | 17.000 ms | 4.062 ms | relation token 索引 + 100 次 package 点查 |
| 待发布 worker | 10,002 个 suite 中 101 个 | 0.645 ms | 0.289 ms | pending-suite 索引 + repository PK 点查 |
| snapshot 清理 | 100,002 中取 256 | 8.290 ms | 5.191 ms | cleanup 索引 + 每个候选固定 3 条 retention 探测 |
| group binding 精确查询 | 10 万取 1 | 0.000118 ms | 0.065 ms | snapshot/path 唯一索引，再校验完整 path |
| group binding 尾部 keyset 分页 | 10 万中取 2,048 | 5.220 ms | 1.218 ms | ID 索引范围；只检查 2,048 行 |
| 过期 lease 分页 | 10 万中命中 103 | 0.326 ms | 0.059 ms | expiry 索引范围 |

这些时间是提交门禁中一次预热后的 executor capture，不代表应用层 RT。MySQL 把唯一键点查显示为
`Rows fetched before execution`，因此表中的亚微秒值只属于优化器/executor 内部计时。

第一版 MySQL retention plan 暴露了一个真实增长缺陷：`EXISTS` 让优化器忽略 revision 排序，首个
batch 已平均为每个候选检查 132 行，后续 batch 还可能继续增长。最终 schema 增加带索引的
publish-complete 判别列，并用第 N 个最新 revision 水位判断候选。两种数据库现在都只为每个候选
检查 3 条 retention 行；MySQL 的 256-row batch 从 28.7 ms 降到 8.29 ms。

坐标/path 精确查询、latest-version、relation、group binding 与过期 lease 必须使用高选择性索引；
package/group 遍历必须使用 keyset 分页，发布与清理每次只 claim 有界 batch。出现全表扫描、无界
排序，或 examined rows 随百万 package 表线性增长都会使门禁失败。提交的
[`docs/perf-data/r-cran-database-1m-2026-08-21.json`](../../perf-data/r-cran-database-1m-2026-08-21.json)
同时记录两种数据库实际选中的索引、actual rows/loops、executor time 和修复证据，
`gate_failures` 为空；其 SHA-256 为
`c4d90b197ffaf3910c5e5104221a9e0070fa127d8bf4bdb5338c56b5029ffb59`。

## 安全扫描与 Cleanup 的性能语义

只有 hosted/proxy source `.tar.gz` asset 进入现有 durable scan candidate/outbox；生成的
`PACKAGES.gz`、snapshot 与 group binding 不进入扫描。因此关闭扫描能力时 upload 不增加扫描表
写入；Audit 不阻塞发布，Enforce 复用现有 pending/block 判定，不增加 R 专用同步 scanner 路径。

Cleanup 使用 R version comparator、keyset 分页、usage/protection 校验和协议删除路径。删除会写
package tombstone 并推进持久 suite revision；index/group 发布在事务外异步执行，并由跨副本
lease/fencing 保护。这样清理扫描与重建成本是显式的，不会被隐藏在 HTTP delete 事务内。

## 复现

向两端 hosted 仓库上传相同 package 后执行：

```bash
python3 scripts/perf/compare-r-nexus.py \
  --nexus-base-url http://127.0.0.1:48380/repository/<nexus-repo> \
  --kkrepo-base-url http://127.0.0.1:59380/repository/<kkrepo-repo> \
  --nexus-auth "$NEXUS_USER:$NEXUS_PASSWORD" \
  --kkrepo-auth "$KKREPO_USER:$KKREPO_PASSWORD" \
  --package-path src/contrib/perfR_1.0.0.tar.gz \
  --requests 250 --concurrency 16 --warmups 32 --rounds 3 \
  --enforce-gates --output /tmp/r-cran-performance.json
```

配对使用 `--nexus-r-command`、`--kkrepo-r-command` 和
[`scripts/perf/r-client-flow.sh`](../../../scripts/perf/r-client-flow.sh)，可加入隔离的真实 R 安装耗时；
凭据不会写入结果文件。

创建仓库后先停止 kkRepo worker，再按数据库执行对应脚本：

```bash
docker exec -i <mysql-container> mysql -u... -p... kkrepo \
  < scripts/perf/r-database-mysql.sql

docker exec -i <postgres-container> psql -U kkrepo -d kkrepo \
  < scripts/perf/r-database-postgresql.sql
```

脚本会替换 `r-hosted`/`r-group` 的 R 性能 fixture 行，只能用于隔离测试数据库，禁止对生产数据库
执行。
