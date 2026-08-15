# Alpine / APK 与 Nexus 本地性能基线

本文记录 Alpine APK v2 功能落地时 kkRepo 与 Sonatype Nexus Repository 的同机定向基线。它用于比较协议热路径，不等同于生产容量结论；TLS、反向代理、远端 OSS/S3、数据库高可用、读写混合负载以及 1k/10k/100k package namespace 仍需按目标环境单独测试。

英文版见 [Local Alpine / APK Performance Baseline Against Nexus](../../en/dev/alpine-performance-baseline.md)。

## 测试环境

- 时间：`2026-08-15T06:23:26Z`。
- 主机：Intel Core i9-9880H、64 GiB 内存、macOS 14.7.8 x86_64。
- 容器运行时：Docker 29.4.0（OrbStack），两个仓库容器均未设置独立 CPU 或内存限制。
- Reference：Sonatype Nexus Repository `3.94.0`。
- Candidate：当前分支的 kkRepo `0.8.0` 开发构建，使用 MySQL 8.0.46；另一个 kkRepo 副本共享同一数据库和本机文件型 blob volume，基准请求指向主副本。Nexus 同样使用本机文件型 blob 存储。
- 两端 hosted 仓库上传完全相同的 `4,196,328` byte APK，SHA-256 为 `ae4c61a2d34fa1e99962b38e138cd0ef5477097d0344833ffd9a077ad7e69da3`。
- 两端使用同一 RSA 私钥签名 v2 index；真实客户端按各自 index 声明的签名文件名信任同一公钥。

## 方法

使用 [`scripts/perf/compare-alpine-nexus.py`](../../../scripts/perf/compare-alpine-nexus.py) 测量 signed `APKINDEX.tar.gz` 的 GET、HEAD、条件 `304`，以及完整 APK GET、64 KiB Range 和 HEAD。计时前会拒绝 package bytes 不同、Range bytes 不同、signed index 结构无效或逻辑 `P/V/S/I` package 集合不同的目标。`C:` 必须是 Q1 identity，但不在两端直接比较，因为 Nexus 3.94 对 unsigned direct upload 可能生成不符合 apk-tools 的 Q1 值。

每个 HTTP 场景先预热 32 次，再以并发 16 请求 250 次；共运行 3 轮，在相邻轮次交替 Nexus/kkRepo 顺序，表格取三轮中位数。两个 index 均可见且后台仓库任务稳定后，先执行了一轮短预热。Alpine 3.23 客户端流程执行 `apk update`、exact `apk search` 和 `apk policy` 共 3 轮，同样交替目标顺序。

运行器强制执行以下发布门禁：

- metadata 路径：kkRepo 吞吐不低于 Nexus 的 `0.80x`，p95 不高于 Nexus 的 `1.25x`；
- package GET/Range：kkRepo 吞吐不低于 Nexus 的 `0.90x`，p95 不高于 Nexus 的 `1.15x`；
- 真实 `apk` 客户端流程：kkRepo p95 不高于 Nexus 的 `1.25x`。

## 结果

| 场景 | Nexus req/s | kkRepo req/s | 吞吐比 | Nexus p95 | kkRepo p95 | p95 比 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| signed index GET | 1785.99 | 2313.67 | 1.295x | 13.419 ms | 14.752 ms | 1.099x |
| signed index HEAD | 1771.19 | 1988.80 | 1.123x | 14.331 ms | 14.063 ms | 0.981x |
| signed index 304 | 1165.28 | 2106.58 | 1.808x | 37.765 ms | 15.122 ms | 0.400x |
| 4 MiB package GET | 298.50 | 348.02 | 1.166x | 72.028 ms | 71.143 ms | 0.988x |
| package Range 64 KiB | 970.00 | 1092.03 | 1.126x | 44.059 ms | 31.153 ms | 0.707x |
| package HEAD | 1335.40 | 2034.07 | 1.523x | 18.223 ms | 15.631 ms | 0.858x |

完整 package GET 的中位吞吐为 Nexus `1194.56 MiB/s`、kkRepo `1392.76 MiB/s`。六条 HTTP 门禁全部通过。

| Alpine 3.23 客户端流程 | p50 | p95 | kkRepo / Nexus p95 |
| --- | ---: | ---: | ---: |
| Nexus | 1148.923 ms | 1739.615 ms | — |
| kkRepo | 1094.289 ms | 1096.176 ms | 0.630x |

真实客户端门禁同样通过。两端均计入容器启动时间，因此绝对耗时不如同机相对结果有意义。

## 结论与边界

- immutable snapshot 与 asset cache 使预热后的 index 读取不访问 Alpine 业务表；MySQL 仍是跨副本失效所需的持久真相和 version watermark。
- MySQL/PostgreSQL 集成门禁会写入 2,048 条干扰 package/relation 数据，并断言 exact coordinate、有界 namespace keyset 与 relation lookup 的优化器实际选中索引。Namespace 发布沿 `(repository_id, distribution_name, component_name, architecture, package_name, id)` 以 2,048 行 keyset 分页读取，不使用无界排序或 `OFFSET`。
- kkRepo 六条 HTTP 路径的吞吐均达到门禁，完整 package 与 Range 也通过更严格的 package latency 门禁。
- 刻意在上传后立即执行的首轮出现了明显本机抖动，来源包括仓库后台任务和 JVM 路径预热。本文记录的确认轮等待 index 可见和任务稳定，但它仍只是方向性的本机结果，不是 SLA。
- 本轮单 package 基准验证固定读取开销和 4 MiB payload；在给出生产容量上限前，仍需分别测量 1k/10k/100k package 下的发布耗时、heap 上界、index 大小和 solver 行为。

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

使用 `--nexus-apk-command` 和 `--kkrepo-apk-command` 可加入成对的真实客户端命令；运行器会在轮次间交替顺序，并把客户端 p95 比纳入门禁。凭据和生成的结果文件必须留在仓库外。
