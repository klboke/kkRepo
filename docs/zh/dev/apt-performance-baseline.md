# APT / Debian 与 Nexus 本地性能基线

本文记录 APT 功能落地时对 kkRepo 与 Sonatype Nexus Repository 的同机定向基线。它用于发现协议热路径差异，不等同于生产容量结论；跨机网络、TLS、反向代理、OSS/S3、数据库高可用和混合读写负载仍需在目标部署环境单独压测。

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
