# Native Image 与 JVM 选型指南

kkRepo 支持两种运行时打包方式：

- **JVM**：默认且推荐的生产模式，在 Java 25 上运行 Spring Boot 可执行 jar。
- **Native**：显式开启的实验性模式，先执行 Spring AOT，再通过 GraalVM Native Image 编译 kkRepo。

Native 打包不会被隐式开启。Docker 镜像和压缩发行包只有显式传入 `--native` 才会构建 Native 版本；默认始终构建 JVM 版本。

## 基准范围

以下基线于 2026-07-20 在本 feature worktree 中测得，该 worktree 基于提交 `7bf554581d0d01cd6fd222a8657d8fb94bc46d11`。两种候选运行时使用相同源码、MySQL、File blob store、应用配置、Hikari 连接池（`maximum-pool-size=8`、`minimum-idle=2`），并统一限制为 2 CPU 和 1 GiB 内存。

宿主环境为 x86_64 Intel Core i9-9880H，通过 OrbStack 运行 Linux 容器。JVM 使用 Liberica JRE 25.0.3，Native 使用 Liberica NIK 25.0.3。ApacheBench 开启 HTTP keep-alive。每种运行时分别执行 5 轮镜像层已热但进程全新的容器启动、端点预热、3 轮单并发、3 轮 32 并发，以及 15 秒 64 并发持续负载。正式计入统计的 408,495 个请求全部成功，没有失败请求。

这是一组受控本地对比数据，不是生产 SLA。测试未覆盖网络对象存储、大制品传输、远程 proxy 延迟、外部 Apollo 和多节点负载均衡。

## 启动、内存与包体积

| 指标 | Native | JVM | 说明 |
| --- | ---: | ---: | --- |
| 创建容器到 readiness 中位数 | 1.037 秒 | 11.888 秒 | Native 快 11.5 倍 |
| Spring 自报启动中位数 | 0.639 秒 | 9.562 秒 | Native 快 15.0 倍 |
| 5 轮 readiness 范围 | 1.031-1.793 秒 | 11.295-14.762 秒 | Native 重启窗口更短 |
| 空闲 Docker 内存中位数 | 170.4 MiB | 315.1 MiB | Native 少 45.9% |
| 空闲进程 RSS | 134.4 MiB | 327.0 MiB | Native 少 58.9% |
| 空闲 CPU 中位数 | 0.32% | 1.27% | 两者都很低，Native 后台开销更小 |
| 空闲线程数 | 40 | 50 | Native 少 20% |
| 负载期间进程 RSS 峰值 | 218.4 MiB | 389.4 MiB | Native 少 43.9% |
| Docker 镜像内容大小 | 238 MB | 388 MB | Native 小 38.7% |
| Docker 本地磁盘占用 | 494 MB | 796 MB | Native 小 37.9% |

在 1 GiB 容器限制下，Buildpacks 内存计算器为 JVM 配置了约 381 MiB 最大堆。不同 JVM 内存策略会改变内存对比结果。

## 请求性能

充分预热后，两种模式在单并发测试中的 P50 都约为 1 ms。差异主要出现在持续并发阶段：完成 JIT 预热后的 JVM 通常能提供更高吞吐。

| 64 并发持续负载端点 | Native 请求/秒 | JVM 请求/秒 | JVM 吞吐变化 | Native P95/P99 | JVM P95/P99 |
| --- | ---: | ---: | ---: | ---: | ---: |
| Actuator health | 765 | 911 | +19.1% | 65/1006 ms | 44/1032 ms |
| Browse 页面 | 2,071 | 2,293 | +10.7% | 81/209 ms | 86/147 ms |
| Repositories API | 2,084 | 3,005 | +44.2% | 91/102 ms | 74/185 ms |

3 轮 32 并发、每轮 10,000 请求的结果也呈现相同的稳态趋势：

- Browse 中位吞吐：Native 2,529 请求/秒，JVM 3,003 请求/秒，JVM 高 18.7%。
- Repositories API 中位吞吐：Native 1,942 请求/秒，JVM 2,438 请求/秒，JVM 高 25.5%。

Health 端点会经过共享 MySQL 健康检查路径，两种模式都出现过 1-7 秒偶发长尾，因此不能只用 health 吞吐代表业务性能。持续 Repositories 测试也表明，更高的 JVM 吞吐并不等于完全没有尾延迟：该轮 JVM P99 高于 Native。

## 构建成本

本次 Native 构建总耗时约 10 分 05 秒，其中 Native Image 编译约 8 分 08 秒；干净的 JVM 镜像构建约 2 分 14 秒。这些数据使用了本地构建缓存，不是完全冷构建基准，但足以说明 Native 会明显增加 CI 和发行构建成本。

最终的端到端压缩包验证还覆盖了一次 Buildpacks 工具缓存未命中：Native 镜像构建耗时 14 分 44 秒，随后两种压缩格式的 assembly 耗时 38 秒，其中 GraalVM image generation 本身耗时 6 分 29 秒。提取出的可执行文件随后在标准 Ubuntu 24.04 容器中连接 MySQL，readiness 达到 `UP`，bootstrap endpoint 返回 HTTP 200。该结果用于验证发行链路；由于复用了已初始化的测试数据库，不作为额外启动性能基准。

## 选型建议

以下情况使用默认 JVM 发行包：

- kkRepo 作为长期运行的仓库服务，充分预热后的吞吐优先级更高。
- 部署依赖外部 Apollo、OSS/S3 provider、低频协议路径，或其他尚未通过 Native client E2E 的集成。
- 更重视成熟运维、诊断、性能分析和已有 JVM 调优经验。

以下情况可以考虑显式选择 Native 发行包：

- 弹性扩缩、scale-to-zero、预览环境或故障副本快速替换对 readiness 时间敏感。
- 节点内存紧张，或需要运行较多小规格副本。
- 实际使用的数据库、存储 provider、认证集成和客户端协议已经通过 Native client E2E。

当前生产环境仍默认推荐 JVM。Native 在真实客户端、对象存储、外部配置、升级和代表性制品传输覆盖达到 JVM 路径之前保持实验性。

## 构建与 CI 命令

默认 JVM 产物不需要额外参数：

```bash
./scripts/build-docker-image.sh kkrepo:local
./scripts/build-dist.sh
```

Native 产物必须显式传入 `--native`：

```bash
./scripts/build-docker-image.sh --native kkrepo:native
./scripts/build-dist.sh --native
```

默认压缩包名保持 `kkrepo-<version>.tar.gz` 和 `kkrepo-<version>.zip`。Native 压缩包会包含目标平台，例如 `kkrepo-<version>-native-linux-amd64.tar.gz` 和 `.zip`。Native 包内运行文件为 `lib/kkrepo`，JVM 包内为 `lib/kkrepo.jar`；共用的 `bin/start.sh` 会自动识别运行时。

在 Pull Request 上添加 `run-native-client-e2e` 标签，会触发独立的 Native client E2E workflow：构建 Native 候选镜像，并分别对 MySQL 和 PostgreSQL 运行 Linux 真实客户端矩阵。原有 `run-client-e2e` 标签继续验证默认 JVM 候选镜像。
