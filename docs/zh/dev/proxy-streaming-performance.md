# 代理流式下载开关性能实测

这是 **Allow streaming downloads** 开关的本地受控对比，不是生产容量测试。
[行为及适用范围](proxy-streaming-downloads.md)说明哪些路径会启用流式模式；
[英文报告](../../en/dev/proxy-streaming-performance.md)记录相同的数据。

## 环境与方法

- 日期：2026-11-16，Asia/Shanghai。
- 实现提交：`1b3b96de7b0bd9bcc035adb43b81c68fa541fa93`，kkRepo 1.4.0 开发版 JVM。
- 可执行 JAR SHA-256：`a8bc13070bbdc95fe5980ca7366b2c61b3972b2c25d77c16c18114b30f8467f3`。
- macOS 14.7.8、Intel i9-9880H、64 GiB 内存；OpenJDK 25.0.3，`-Xms512m -Xmx2g`。
- PostgreSQL 16 与 MinIO `RELEASE.2025-04-22T22-12-26Z` 运行在本机 OrbStack/Docker 容器。
  应用使用 dev profile，关闭应用与 JDBC 的 debug 日志。
- Raw proxy、带认证的 HTTP、单并发，每个文件 64 MiB；本地上游按 64 KiB 分块，限速 **4 MiB/s**，
  最后一块也计入限速时间。
- 每种模式三轮，交替测试顺序，每个仓库先用 64 KiB 文件预热。每轮生成不同内容，两种模式使用
  相同字节；使用不同 Blob store 和前缀，避免跨模式摘要去重影响冷下载。
- 校验完整下载与续传内容的长度、SHA-256。每 50 ms 通过 Browse API 观察缓存发布，不用制品 GET/HEAD
  探测缓存；随后命中缓存的下载必须没有新增上游请求。

首字节指**首个响应体字节**，不是收到响应头。下表为三轮中位数。
[原始样本](../../perf-data/proxy-streaming-2026-11-16.json)记录每次耗时、摘要、状态、回源次数、
缓存延迟以及缓存 Range 的结果。

| 指标 | 关闭 | 开启 |
| --- | ---: | ---: |
| 首次下载首字节 | 16.3078 s | 0.0281 s |
| 首次下载总耗时 | 16.4543 s | 16.0134 s |
| 缓存命中首字节 | 9.34 ms | 9.37 ms |
| 缓存命中总耗时 | 168.11 ms | 169.60 ms |
| 客户端下载结束后缓存可见延迟 | 结束前已发布 | 0.9540 s |

主要收益是消除最初长时间没有下载进度的等待。完整下载仍需要约 16 秒上游传输时间，并不会提升
数百倍。缓存命中耗时不能据此断言吞吐提升。开启后客户端完成与缓存发布分开，不能把客户端结束
当作缓存已经可见。本数据不代表公网 OSS/S3、并发容量、Native、真实 APT/DNF 安装或全部仓库格式。

## 复现

准备独立测试实例及数据库、对象存储，创建两个不同的 S3 Blob store，例如
`streaming-bench-off`、`streaming-bench-on`，使用不同前缀，并允许访问测试上游地址。
通过环境变量提供测试管理员凭据，脚本不会把凭据写入结果。

```sh
export KKREPO_BENCH_USER=admin
# 在本地环境设置 KKREPO_BENCH_PASSWORD。
python3 scripts/perf/compare-proxy-streaming.py \
  --base-url http://127.0.0.1:38540 \
  --blob-store-off streaming-bench-off --blob-store-on streaming-bench-on \
  --upstream-host 127.0.0.1 --upstream-port 38541 \
  --size-mib 64 --rate-mib 4 --repeats 3 \
  --build-label 1b3b96de7b0bd9bcc035adb43b81c68fa541fa93 \
  --output /tmp/proxy-streaming.json
```

应用在容器中时，替换成容器可达的上游地址。脚本创建唯一命名的 `stream-<run>-off/on` 仓库并保留
样本供检查；结果会列出仓库名。结束后用正常管理 API 清理对应制品与仓库，或销毁整个测试环境。

## 同时完成的正确性验证

- 757 项 Java/reactor 回归检查通过，包括 15 项流式下载测试、安全扫描入口策略、协议回归、配置、
  APT/Conda、Docker、重定向边界和 UI 测试入口。JavaScript 用例覆盖全部 **23 种 proxy 配方**。
- 与 Nexus 3.94 实例对照：完整 GET、单段/后缀 Range、If-Range 不匹配、416、缓存命中及 HEAD 通过。
  开启后 Raw Range 与 Nexus 对齐；关闭时保留 kkRepo 原有的完整 200 行为。对应测试：
  `RawRepositoryBlackBoxCompatibilityTest#streamingTogglePreservesFullAndRangeHttpSemanticsAgainstNexus`。
- 两个 JVM 共用 PostgreSQL 和 MinIO，同时开始旧文件的冷下载。暂停两个上游响应体后，关闭再开启
  开关，下载并发布新内容；随后恢复旧请求。旧客户端正确完成，晚到的填充没有覆盖新缓存，两个节点
  最后均返回新内容摘要，Browse 只出现一个 asset。使用上面记录的实现提交。

多进程检查可单独复现，避免混入性能计时：

```sh
python3 scripts/perf/verify-proxy-streaming-coordination.py \
  --base-url http://127.0.0.1:38540 --peer-url http://127.0.0.1:38545 \
  --blob-store streaming-bench-on --upstream-port 38546
```

两个实例必须共用仓库元数据、Blob 存储和加密配置。检查只修改自身创建的仓库，不代表滚动部署或
崩溃恢复测试。本次浏览器可视化自动化不可用；表单行为和信息提示标记通过自动化 UI 测试验证。
