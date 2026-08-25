# Go Hosted 与 Nexus 本地性能基线

本文记录 Go hosted 发布与 hosted-first group 读取的同机基线。它是协议路径的方向性对比，不是
生产容量承诺；TLS、反向代理、远端 OSS/S3、数据库高可用、负载均衡和读写混合负载仍需按实际
部署验证。

英文版见 [Local Go Hosted Performance Baseline Against Nexus](../../en/dev/go-hosted-performance-baseline.md)。

## 测试环境

- 时间：2026-08-25。
- 主机：MacBookPro16,1、8 核、64 GiB 内存、macOS 14.7.8 x86_64。
- 容器运行时：Docker 29.4.0（OrbStack）；两个仓库容器均未设置独立 CPU 或内存限制。
- Reference：Sonatype Nexus Repository 3.94.0，使用本机 datastore 与 Docker volume。
- Candidate：当前分支的 kkRepo 0.9.0 开发构建，MySQL 8.0 与 Docker volume 上的 file blob
  adapter。
- 两端各使用一个 hosted 仓库和一个仅包含该 hosted 成员的 group。仓库未绑定 Cleanup policy，
  Artifact Scanning 关闭，因此本次测量覆盖仓库协议与正常 metadata/blob 持久化路径。

## 方法与正确性预检

[`scripts/perf/compare-go-hosted-nexus.py`](../../../scripts/perf/compare-go-hosted-nexus.py)
会构建确定性的 Go module ZIP，并向两端发布相同的 12 个版本。每个 archive 包含规范
`go.mod`、一个源文件和 1 MiB 的 stored payload。计时前，运行器要求 Nexus 与 kkRepo 的
version list、选中版本、`go.mod` hash，以及 hosted/group ZIP 响应中每个文件的
name/size/SHA-256 manifest 完全一致。

下方原始基线中的 8 个读取场景分别先执行 32 次 warmup，再以并发 16 请求 250 次；共执行 3
轮并交替目标顺序，表格取各轮统计量的中位数。发布场景每轮向每端并发 8 路发布 24 个唯一
module，覆盖 archive 校验、3 个 blob 写入和 `.mod`/`.info`/`.zip` 原子 metadata 事务。该次
记录包含 12,144 个计时 HTTP 请求，全部正确性检查和计时请求均成功。

当前 runner 会分开记录预热前样本与稳态结果。默认先记录 250 个 pre-warm 请求，然后要求每端
同时完成至少 2,000 个请求和至少 5 秒预热，之后再执行 3 轮稳态测试；目标执行顺序仍会交替。
pre-warm 样本发生在正确性预检之后，除非外部先重启两端服务，否则不能视为进程冷启动数据。

## 原始基线结果

吞吐比高于 `1.0x` 表示 kkRepo 每秒完成更多请求；延迟单位均为毫秒。

| 场景 | Nexus req/s | kkRepo req/s | 吞吐比 | Nexus p50 | kkRepo p50 | Nexus p95 | kkRepo p95 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| hosted list | 1182.50 | 634.87 | 0.537x | 8.858 | 12.566 | 25.489 | 65.226 |
| hosted info | 960.43 | 1714.04 | 1.785x | 11.361 | 6.474 | 33.318 | 20.064 |
| hosted mod | 1054.53 | 1463.42 | 1.388x | 9.709 | 7.578 | 30.384 | 28.116 |
| hosted zip（1 MiB payload） | 499.24 | 522.66 | 1.047x | 12.355 | 12.264 | 78.218 | 75.588 |
| hosted latest | 1225.00 | 1115.48 | 0.911x | 10.517 | 10.010 | 23.396 | 39.399 |
| group list | 931.13 | 393.85 | 0.423x | 12.048 | 26.749 | 44.224 | 78.880 |
| group latest | 332.10 | 259.68 | 0.782x | 32.657 | 38.120 | 108.493 | 99.818 |
| group zip（1 MiB payload） | 360.52 | 533.50 | 1.480x | 18.829 | 12.753 | 70.481 | 63.180 |
| hosted publish | 28.11 | 32.72 | 1.164x | 181.239 | 226.761 | 352.670 | 309.939 |

## 优化后的 list 复测

hosted 查询改为只投影 version 字符串，group 改为直接消费 hosted 成员的结构化列表后，在同一组
12-version 仓库上重新测试 list 路径。稳态结果为并发 16、每轮 250 请求、共 3 轮的中位数。
自适应预热阶段，hosted list 分别完成 kkRepo 4,000 次与 Nexus 6,000 次请求；group list 分别
完成 kkRepo 8,000 次与 Nexus 10,750 次请求；每端预热时间均超过 5 秒。

| 阶段 | 场景 | Nexus req/s | kkRepo req/s | 吞吐比 | Nexus p50 | kkRepo p50 | Nexus p95 | kkRepo p95 |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| pre-warm | hosted list | 1486.55 | 397.21 | 0.267x | 8.047 | 27.861 | 20.166 | 76.367 |
| steady-state | hosted list | 1734.84 | 875.08 | 0.504x | 6.383 | 12.494 | 27.740 | 42.342 |
| pre-warm | group list | 1887.86 | 1672.62 | 0.886x | 5.757 | 6.731 | 24.462 | 27.202 |
| steady-state | group list | 1985.86 | 1553.17 | 0.782x | 6.055 | 7.346 | 17.874 | 25.659 |

新方法的预热强度高于原来的 32 请求，因此两张表不能直接作为严格的优化前后加速比。复测说明
持续负载下 group list 与 Nexus 的差距已明显缩小；hosted list 的主要开销仍在外部 MySQL 查询，
后续可单独设计具备多副本一致性语义的 version watermark/cache。

本次测试中，实际字节读取路径（`info`、`mod`、ZIP）和完整并发发布的吞吐达到或超过 Nexus。
kkRepo 当前未物化的 hosted/group list 聚合与 group latest 查询较慢；本文保留这些结果，不做
归一化掩盖，以便后续 metadata cache 或物化优化在不改变协议语义的前提下得到可量化对比。

正式数据生成前，基准还发现了一个真实并发发布缺陷：MySQL browse-node 死锁会回滚当前事务，
但被忽略的锁异常让后续 release asset 继续执行。最终实现会把该瞬时异常交给事务重试，完整重放
`.mod`/`.info`/`.zip` 三件套。修复后，12 版本并发准备与 8 路唯一 module 并发发布烟测均通过。

## 复现

分别在 Nexus 与 kkRepo 创建等价的 Go hosted 仓库和仅含该 hosted 成员的 group，然后执行：

```bash
python3 scripts/perf/compare-go-hosted-nexus.py \
  --nexus-hosted-url http://127.0.0.1:28090/repository/<nexus-hosted> \
  --nexus-group-url http://127.0.0.1:28090/repository/<nexus-group> \
  --kkrepo-hosted-url http://127.0.0.1:61090/repository/<kkrepo-hosted> \
  --kkrepo-group-url http://127.0.0.1:61090/repository/<kkrepo-group> \
  --nexus-auth "$NEXUS_USER:$NEXUS_PASSWORD" \
  --kkrepo-auth "$KKREPO_USER:$KKREPO_PASSWORD" \
  --requests 250 --concurrency 16 --prewarm-requests 250 \
  --warmups 2000 --warmup-seconds 5 --rounds 3 \
  --publish-requests 24 --publish-concurrency 8 \
  --output /tmp/go-hosted-performance.json
```

Nexus 兼容的 write policy 可能拒绝重复部署，因此每次准备数据时应使用新的 module 名或全新仓库。
凭据只用于 HTTP header，不会写入 JSON 报告。若要复用已准备好的仓库只复测读取路径，可增加
`--skip-prepare --skip-publish`。
