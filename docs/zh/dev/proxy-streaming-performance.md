# 代理流式下载开关性能实测

本报告对比 **Allow streaming downloads** 开关，包含真实公网 PyPI 包实测和本地受控基线，不是生产容量测试。
[行为及适用范围](proxy-streaming-downloads.md)说明哪些路径会启用流式模式；
[英文报告](../../en/dev/proxy-streaming-performance.md)记录相同的数据。

## 真实 PyPI 包：906.5 MB

2026-11-16，使用 JVM 实现 `767c924bda69082ccfb4dd9cb1fb3d3c04b2bb19`，经上游为
`https://pypi.org/` 的真实 **PyPI proxy** 仓库下载
[`torch-2.5.1-cp311-cp311-manylinux1_x86_64.whl`](https://files.pythonhosted.org/packages/d1/35/e8b2daf02ce933e4518e6f5682c72fd0ed66c15910ea1fb4168f442b71c4/torch-2.5.1-cp311-cp311-manylinux1_x86_64.whl)。
[官方发布元数据](https://pypi.org/pypi/torch/2.5.1/json)中的大小为
**906,474,467 字节（906.5 MB / 864.5 MiB）**，SHA-256 为
`de5b7d6740c4b636ef4db92be922f0edc425b65ed78c5076c43c42d362a45457`。

可执行 JAR SHA-256：`80ea33b9ed62cfabdfcca90742dc2a34a62d36556f36d5a93238c6aec7fc187e`。硬件、JVM 参数及本地 PostgreSQL/MinIO 环境
与下文受控基线一致。较早的受控基线使用响应校验修复构建；这里的真实包表格中，两种模式都使用
后续修复异步超时后的构建。

- 按关闭 → 开启顺序完成一组对比，串行下载，**不人为限速**。下表是每种模式的单次实测，
  不是三轮中位数；公网带宽限制了重复次数。
  正式计时前用 4,661 字节的 `sampleproject` wheel 单独完成了脚本验证。
- 两种模式各使用全新仓库、独立 Blob store 和 S3 前缀。先获取真实 Simple API 索引，跟随
  kkRepo 改写后的包链接下载；wheel 本身未缓存。独立上游 HEAD 显示 PyPI CDN `HIT, HIT`，
  因此这里的冷下载指 **kkRepo 缓存为空**，不代表 CDN 未缓存。
- 客户端 socket 超时为 1,800 秒，计时包含客户端增量 SHA-256 计算。两种模式的冷下载及缓存
  命中共四次完整响应均为 HTTP 200，大小与摘要全部匹配官方元数据。
- 每 100 ms 通过 Browse API 观察缓存发布，再开始缓存命中下载。按仓库统计的
  `kkrepo_proxy_remote_requests_total` 显示冷下载回源两次（索引解析 + wheel），
  **两次缓存命中下载均未新增回源请求**。

| 指标（每种模式单次样本） | 关闭 | 开启 |
| --- | ---: | ---: |
| 首次下载首字节 | 629.681 s | 0.418 s |
| 首次下载总耗时 | 631.631 s | 883.207 s |
| 缓存命中首字节 | 10.68 ms | 12.60 ms |
| 缓存命中总耗时 | 2.146 s | 2.145 s |
| 客户端结束后缓存可见延迟 | 结束前已发布 | 14.243 s |
| 缓存命中回源次数 | 0 | 0 |

本次直接观察到更早的下载进度；开启后的总耗时实际比关闭 **慢 251.576 秒**。
这是一组串行、未控制公网/CDN 带宽的对比，无法区分网络波动与实现开销，不能声称稳定的吞吐提升。
缓存命中样本也不能说明开关带来吞吐差异。开启模式在客户端结束后才完成缓存发布，表中的缓存
命中测速在发布后开始。这是一个真实包的传输测试，不是完整 `pip install`、并发容量或公网
对象存储测试。

最初一次关闭模式探测使用 600 秒客户端超时，超时前没有收到响应头或响应体；后台随后完成
缓存填充。这条失败记录单独保留在[原始数据](../../perf-data/proxy-streaming-real-pypi-2026-11-16.json)中，
**不计入上面的完整下载表格**。表格中的关闭样本使用全新仓库/存储和更长超时，没有复用该缓存。

第一次开启模式还发现了产品缺陷：MVC 全局十分钟的**总时长**超时截断了仍在传输的响应，客户端
只收到 **741,959,347 / 906,474,467 字节**，没有发布包缓存。
[修复前数据](../../perf-data/proxy-streaming-real-pypi-before-timeout-fix-2026-11-16.json)保留了这次
失败及此前成功的关闭模式样本。修复仅取消已进入流式交付请求的总时长截止，保留上游 I/O 超时、
断连清理和资源限制。真实 Tomcat 回归验证慢响应能超过 100 ms MVC 截止后完整完成，另有用例验证
缓冲回退保留原配置。上表已用修复后的构建和全新存储重测两种模式，不将失败传输当作完整下载。

### 复现真实包测试

准备独立 kkRepo 实例及可访问的管理 metrics endpoint。将 S3 `BlobStoreRequest` 模板
（`POST /internal/blob-stores` 接受的 JSON body）保存在仓库外的私有文件中，包含
`type: "s3"`、endpoint/region/bucket/prefix、凭据和存储选项，权限设为 `0600`。
本次使用 `engine: "aws-s3"`、path-style、64 MiB 分段阈值、16 MiB 分段及并发数 4。
脚本会生成唯一的 Blob store 名称、前缀及仓库名，不将凭据写入结果。

```sh
curl --fail --silent --show-error https://pypi.org/pypi/torch/2.5.1/json \
  -o /tmp/torch-2.5.1.json
export KKREPO_BENCH_USER=admin
# Set KKREPO_BENCH_PASSWORD in the local environment.
python3 scripts/perf/compare-real-pypi-streaming.py \
  --base-url http://127.0.0.1:38540 --management-url http://127.0.0.1:38544 \
  --metadata-file /tmp/torch-2.5.1.json \
  --filename torch-2.5.1-cp311-cp311-manylinux1_x86_64.whl \
  --blob-store-template /tmp/private-benchmark-s3.json --repeats 1 \
  --build-label 767c924bda69082ccfb4dd9cb1fb3d3c04b2bb19 \
  --output /tmp/proxy-streaming-real-pypi.json
```

默认只测一组；每增加一组，会从 PyPI 多下载 **1.81 GB**，并进行两次本地缓存下载。
脚本保留样本供检查，结果会列出准确的仓库、Blob store 和前缀名。检查后通过管理 API 清理
这些样本，或销毁独立测试环境。计时期间不要并行运行构建或其他下载。

## 64 MiB 受控基线

### 环境与方法

- 日期：2026-11-16，Asia/Shanghai。
- 实现提交：`f114fdb26cb215b7cae749806fb53f47d1a4e0ca`，kkRepo 1.4.0 开发版 JVM。
- 可执行 JAR SHA-256：`9d4d527dd3e82dfc6d8abea34b5ee9b6ace9f176f7bcd34291f4a836e00d340b`。
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
| 首次下载首字节 | 16.2989 s | 0.0303 s |
| 首次下载总耗时 | 16.4598 s | 16.0148 s |
| 缓存命中首字节 | 9.75 ms | 12.24 ms |
| 缓存命中总耗时 | 174.08 ms | 169.74 ms |
| 客户端下载结束后缓存可见延迟 | 结束前已发布 | 0.8841 s |

主要收益是消除最初长时间没有下载进度的等待。完整下载仍需要约 16 秒上游传输时间，并不会提升
数百倍。缓存命中耗时不能据此断言吞吐提升。开启后客户端完成与缓存发布分开，不能把客户端结束
当作缓存已经可见。本数据不代表公网 OSS/S3、并发容量、Native、真实 APT/DNF 安装或全部仓库格式。

### 复现受控基线

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
  --build-label f114fdb26cb215b7cae749806fb53f47d1a4e0ca \
  --output /tmp/proxy-streaming.json
```

应用在容器中时，替换成容器可达的上游地址。脚本创建唯一命名的 `stream-<run>-off/on` 仓库并保留
样本供检查；结果会列出仓库名。结束后用正常管理 API 清理对应制品与仓库，或销毁整个测试环境。

## 同时完成的正确性验证

- Java/reactor 定向回归检查通过，覆盖代理和协议、安全扫描入口策略、配置、APT/Conda、Docker、
  重定向边界和 UI。最终响应校验修复构建通过 28 项流式下载和 50 项 Raw proxy 测试，包括条件请求
  和支持的制品路径判断。JavaScript 用例覆盖全部 **23 种 proxy 配方**。
- 每次冷下载、缓存命中和续传响应最多包含一个 ETag 字段；性能脚本会拒绝重复的响应校验字段。
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
