# APT / Debian 与 Nexus 本地性能基线

本文记录 APT 功能落地时对 kkRepo 与 Sonatype Nexus Repository 的同机定向基线。它用于发现协议热路径差异，不等同于生产容量结论；跨机网络、TLS、反向代理、OSS/S3、数据库高可用和混合读写负载仍需在目标部署环境单独压测。

英文版见 [Local APT / Debian Performance Baseline Against Nexus](../../en/dev/apt-performance-baseline.md)。

## 测试环境

- 时间：`2026-08-08T17:46:11Z`。
- 主机：Intel Core i9-9880H、64 GiB 内存、macOS 14.7.8 x86_64。
- 容器运行时：Docker 29.4.0（OrbStack），被测容器未设置独立 CPU/内存上限。
- Reference：Sonatype Nexus Repository `3.94.0`。
- Candidate：当前分支的 kkRepo `0.7.0` 开发构建，MySQL 8.0；两端均使用本机文件型 blob 存储。
- 两端上传完全相同的 `4,196,374` byte `.deb`，SHA-256 为 `b596e8368630f8befe2ea2079f929aab744d9fa48258e02370709df7cd93e975`。

## 方法

使用 [`scripts/perf/compare-apt-nexus.py`](../../../scripts/perf/compare-apt-nexus.py) 对同一 hosted 仓库和同一 package path 测量五个客户端可见读取场景。每个场景先预热 32 次，然后在并发 16 下请求 250 次；共执行 3 轮，并在相邻轮次交替 Nexus/kkRepo 的执行顺序。下表取每个目标三轮结果的中位数。

运行器会在压测前校验 HTTP 状态、完整 package bytes、64 KiB Range bytes 和对应 SHA-256，避免把错误页或不同制品计入吞吐。

## 结果

| 场景 | Nexus req/s | kkRepo req/s | kkRepo / Nexus | Nexus p50 | kkRepo p50 | Nexus p95 | kkRepo p95 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| `InRelease` | 817.43 | 507.61 | 0.621x | 16.565 ms | 27.968 ms | 30.747 ms | 56.202 ms |
| `Packages.gz` | 1041.93 | 754.30 | 0.724x | 12.454 ms | 18.859 ms | 21.958 ms | 32.902 ms |
| 4 MiB package GET | 266.71 | 376.00 | 1.410x | 22.622 ms | 18.591 ms | 82.187 ms | 42.089 ms |
| package Range 64 KiB | 1166.84 | 1357.51 | 1.163x | 10.148 ms | 8.720 ms | 31.664 ms | 21.639 ms |
| package HEAD | 1506.32 | 1290.01 | 0.856x | 8.295 ms | 9.380 ms | 17.042 ms | 24.028 ms |

完整 package GET 的中位吞吐为 Nexus `1067.37 MiB/s`、kkRepo `1504.75 MiB/s`。

## 结论

- kkRepo 的 package body 热读表现更好：完整 GET 吞吐高约 41%，p95 低约 49%；64 KiB Range 吞吐高约 16%，p95 低约 32%。
- Nexus 的小 metadata/HEAD 请求更快：kkRepo 的 `InRelease`、`Packages.gz` 和 HEAD 吞吐分别约为 Nexus 的 62%、72% 和 86%。这说明 kkRepo 后续性能优化应优先检查小对象读取的数据库投影、鉴权/过滤器和响应组装固定开销，而不是先改 package streaming。
- 两端生成的 `InRelease` 大小和签名字节本就不同，因此这里只比较各自合法响应的请求成本；package GET 和 Range 使用相同制品字节做直接对比。
- 结果仅代表单机、预热、读取型基线。不能据此推断公网延迟、并发写入、metadata rebuild、proxy 回源或对象存储环境下的相对表现。

## 优化后复测（2026-08-09）

针对首轮基线暴露的小对象固定开销，本轮完成了四组热路径优化：

- 已发布 APT snapshot 使用节点本地类型化缓存，并以 MySQL version watermark 和 TTL 保证多副本失效；稳定 metadata 读取不再查询或锁定 suite。
- repository record、runtime 和 APT settings 在过滤器与 controller 间复用，并在仓库配置广播后失效，移除重复 SELECT 和 JSON 解析。
- asset metadata 与 Basic Auth 在共享缓存前增加可重建的节点本地类型化热缓存，减少热命中的共享缓存读取和反序列化。
- 无凭据匿名请求与已预热的权限目录判定不再开启请求级事务；需要读取数据库的鉴权回退路径仍保留显式事务边界。

复测沿用相同容器、制品、请求数、并发、预热和三轮交替顺序。下表记录第二次独立确认运行的三轮中位数：

| 场景 | Nexus req/s | kkRepo req/s | kkRepo / Nexus | Nexus p50 | kkRepo p50 | Nexus p95 | kkRepo p95 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| `InRelease` | 2087.93 | 2740.20 | 1.312x | 6.512 ms | 3.910 ms | 18.992 ms | 12.611 ms |
| `Packages.gz` | 1705.69 | 2552.21 | 1.496x | 7.705 ms | 4.502 ms | 16.901 ms | 14.008 ms |
| 4 MiB package GET | 336.13 | 405.03 | 1.205x | 17.028 ms | 14.959 ms | 41.621 ms | 30.514 ms |
| package Range 64 KiB | 1557.66 | 1814.40 | 1.165x | 7.513 ms | 6.024 ms | 19.835 ms | 19.743 ms |
| package HEAD | 2301.23 | 2755.66 | 1.197x | 5.443 ms | 4.165 ms | 14.921 ms | 11.650 ms |

完整 package GET 的中位吞吐为 Nexus `1345.17 MiB/s`、kkRepo `1620.92 MiB/s`。另一轮独立运行中，五个场景的吞吐比分别为 `1.453x`、`1.197x`、`1.085x`、`1.348x` 和 `1.034x`，方向与确认运行一致。

预热后另以 1000 次、并发 16 的 `InRelease` 请求检查 MySQL statement digest：请求期间没有 APT suite、snapshot 或 repository 业务查询，也没有请求级事务；观测到的 14 次事务全部来自同期后台轮询任务。说明本轮提升来自固定数据库与序列化开销的实质移除，而不是绕过协议校验或响应体传输。

复测后五个客户端可见读取场景的吞吐均高于同机 Nexus；首轮最明显的 metadata 和 HEAD 差距已经消除。结果仍是单机方向性数据，生产环境需继续覆盖 TLS、远端 OSS/S3、数据库高可用和多副本负载均衡。

## 异步发布与流式索引落地后复测（2026-08-09）

在两端同一性能仓库各增加 100 个完全相同的小型 `.deb`，使仓库从 3 个 package 增长到 103 个 package；随后继续使用相同的 250 请求、并发 16、预热 32、3 轮交替顺序测试。结果如下：

| 场景 | Nexus req/s | kkRepo req/s | kkRepo / Nexus | Nexus p50 | kkRepo p50 | Nexus p95 | kkRepo p95 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| `InRelease` | 1383.36 | 1985.02 | 1.435x | 8.677 ms | 5.119 ms | 22.275 ms | 12.934 ms |
| `Packages.gz` | 1572.25 | 2366.74 | 1.505x | 7.792 ms | 4.877 ms | 17.334 ms | 10.430 ms |
| 4 MiB package GET | 283.54 | 390.54 | 1.377x | 17.447 ms | 17.525 ms | 62.682 ms | 34.791 ms |
| package Range 64 KiB | 1644.48 | 2044.32 | 1.243x | 6.719 ms | 5.956 ms | 15.293 ms | 14.300 ms |
| package HEAD | 2275.14 | 3177.13 | 1.396x | 5.286 ms | 3.925 ms | 13.495 ms | 9.515 ms |

同一组 100 package、并发 16 的突发写入中，kkRepo 的 100 个 durable desired revision 被合并为 1 个新 snapshot；上传结束后 `0.777 s` 可见新 metadata。Nexus 的对应时间为 `0.582 s`。kkRepo 上传响应仍慢于 Nexus：本轮 wall time 为 `2.738 s` 对 `2.124 s`，p50 为 `354.06 ms` 对 `108.10 ms`。异步发布已经把全量索引生成移出写请求，但写路径仍包含 `.deb` 解包/identity/checksum 校验、blob/asset/component 持久化、审计与安全扫描 outbox；后续写吞吐优化应基于 profile 缩减这些固定开销，不能把写入正确性推迟到 metadata 投影阶段。

本轮说明 package 数增加后没有出现读取热路径突降；五个读取场景的吞吐仍全部高于同机 Nexus。发布侧的单次工作量仍会随 Packages 大小近似线性增长，流式生成解决的是堆内存随 N 增长的问题，debounce 解决的是突发写入重复重建问题，并不把完整索引生成变成 O(1)。

最终 V44 双副本镜像部署后又完整执行三次相同基准。各次结果本身仍取 3 轮中位数，再对三次吞吐比取中位数，`InRelease`、`Packages.gz`、完整 GET、Range 和 HEAD 分别为 `1.564x`、`1.251x`、`1.258x`、`1.176x` 和 `0.988x`。其中 metadata 的单次结果抖动较大（`InRelease` 为 `0.639x`–`1.582x`，`Packages.gz` 为 `0.924x`–`1.461x`），HEAD 基本持平；大包 GET/Range 三次均领先。应把本机结果视为方向性证据，而不是稳定 SLA。

## 复现

先向两端 hosted 仓库上传同一个 package，再执行：

```bash
python3 scripts/perf/compare-apt-nexus.py \
  --nexus-base-url http://127.0.0.1:48090/repository/<repo> \
  --kkrepo-base-url http://127.0.0.1:58090/repository/<repo> \
  --nexus-auth "$NEXUS_USER:$NEXUS_PASSWORD" \
  --kkrepo-auth "$KKREPO_USER:$KKREPO_PASSWORD" \
  --package-path pool/k/kkrepo-apt-benchmark/kkrepo-apt-benchmark_1.0.0_amd64.deb \
  --requests 250 \
  --concurrency 16 \
  --warmups 32 \
  --rounds 3 \
  --output /tmp/apt-performance.json
```

凭据只通过参数传给本地进程，不应把带真实密码的命令或结果文件提交到仓库。
