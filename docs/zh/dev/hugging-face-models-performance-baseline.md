# Hugging Face Models 与 Nexus 本地性能基线

本文记录仅面向 Models 的 Hugging Face proxy 实现基线。它是同机协议路径对比，不是生产
容量结论；每个样本在计时前都会校验 commit、header、完整字节、SHA-256、Range body，以及
客户端响应中不存在 Xet/外部路由泄漏。

英文版见 [Local Hugging Face Models Performance Baseline Against Nexus](../../en/dev/hugging-face-models-performance-baseline.md)。

## 环境与方法

- 热路径确认时间：`2026-08-17T14:42:57Z`。
- 主机：Intel Core i9-9880H、64 GiB 内存、macOS 14.7.8 x86_64。
- 运行时：Docker 29.4.0（OrbStack）；两端容器均没有独立 CPU/内存限制。
- Reference：Sonatype Nexus Repository `3.94.0-12`，PostgreSQL datastore，File blob store。
- Candidate：kkRepo `0.8.0` 开发镜像
  `sha256:74e3ca5e645c0bed9c60241b82e77f8268924b441a17a55413d9215581a59b29`，
  PostgreSQL 17.10，File blob store。
- 两端 JVM 都使用 `-Xms512m -Xmx1536m`。
- Fixture：确定性的 4 MiB Xet-backed 文件，commit 为
  `0123456789abcdef0123456789abcdef01234567`，SHA-256 为
  `74a18e3f48369ee8c8e7cd03bd8b786591b0c19e2ee4df6ec97e74bef0c849d8`。

[`compare-huggingface-nexus.py`](../../../scripts/perf/compare-huggingface-nexus.py) 会验证 model
info GET/`304`、model file HEAD/完整 GET/64 KiB Range，并拒绝内容不一致或客户端响应泄漏
`Location`、`X-Xet-Hash`、Xet Link 与上游 host。每个热路径先预热 64 次，然后以并发 16
执行 500 请求，共 5 轮且交替目标顺序；表格取每轮统计量中位数，25,000 个计时请求全部成功。

Metadata 门禁为吞吐不低于 Nexus `0.80x`、p95 不高于 `1.25x`；file GET/Range 门禁为吞吐
不低于 `0.90x`、p95 不高于 `1.15x`。冷填充单独使用 5 组全新仓库，交替目标顺序，避免
一次本机抖动决定结果。

## 结果

热路径运行器的 `gate_failures` 为空。

| 场景 | Nexus req/s | kkRepo req/s | 吞吐比 | Nexus p95 | kkRepo p95 | p95 比 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| model info GET | 1827.02 | 2117.62 | 1.159x | 16.586 ms | 11.952 ms | 0.721x |
| model info 304 | 1462.52 | 2339.78 | 1.600x | 22.092 ms | 11.103 ms | 0.503x |
| model file HEAD | 1877.90 | 2687.45 | 1.431x | 16.308 ms | 11.241 ms | 0.689x |
| 4 MiB model file GET | 365.61 | 483.70 | 1.323x | 76.317 ms | 51.706 ms | 0.678x |
| model file Range 64 KiB | 1207.30 | 1748.69 | 1.448x | 26.346 ms | 17.531 ms | 0.665x |

完整文件 GET 的中位吞吐为 Nexus `1462.42 MiB/s`、kkRepo `1934.78 MiB/s`。

### 冷填充

每个样本使用全新仓库与 immutable commit 路径；fixture 对每个仓库只观察到一次 resolve
与一次 CDN 完整 body 请求。

| 目标 | 样本数 | 总耗时中位数 | TTFB 中位数 | 吞吐中位数 |
| --- | ---: | ---: | ---: | ---: |
| Nexus 3.94 | 5 | 213.796 ms | 207.789 ms | 18.709 MiB/s |
| kkRepo | 5 | 211.981 ms | 204.140 ms | 18.870 MiB/s |

kkRepo/Nexus 冷填充吞吐比为 `1.009x`，超过 `0.90x` 门禁；10 个响应均为 200，且 4 MiB
SHA-256 完全一致。

### S3-Compatible 与多副本证据

同一 candidate 镜像还使用 PostgreSQL 17.10 与通过 AWS S3-compatible adapter 访问的 MinIO
`RELEASE.2025-04-22T22-12-26Z` 运行：

- 单副本冷填充 `313.645 ms`，TTFB `302.145 ms`，`13.370 MiB/s`；
- 后续热 GET `29.051 ms`；
- 两副本同时 miss 同一 4 MiB 文件：两端均返回 200 与精确 SHA-256，fixture 只收到一次
  resolve 和一次完整 body；持久行以 fencing token `1` 发布为 `READY`。

这验证了生产存储路径和数据库 singleflight 语义；S3 数据是本地 MinIO 绝对值，不是 Nexus 对比。

## 真实客户端确认

Candidate 还通过以下非计时验证：

- `huggingface_hub` 1.27.0 与 0.34.6：`hf_hub_download`、`snapshot_download`；
- `hf` CLI 1.27.0：单文件与过滤后的 snapshot 下载；
- Transformers 5.15.0：`AutoConfig` 与 `AutoTokenizer`；
- Transformers 4.49.0 + PyTorch 2.2.2：`AutoModel.from_pretrained`，得到 87,929 参数的
  `BertModel`；
- Diffusers 0.35.2：完整 15 文件 snapshot 与 `StableDiffusionPipeline.from_pretrained`；
- 已安装 Xet 且未设置 `HF_HUB_DISABLE_XET`：客户端仍只访问 kkRepo，可控 upstream 没有
  收到客户端侧 Xet token 请求。

公网 smoke 使用 `hf-internal-testing/tiny-random-bert` 与
`hf-internal-testing/tiny-stable-diffusion-pipe`。Nexus 3.94 可通过 legacy 0.34.6 snapshot，
但对当前 1.27.0 的 tree/paths 流程返回 400；kkRepo 同时支持两条版本线。

## 原始数据与边界

包含 50 组逐轮测量与正确性 preflight 的完整热路径结果见
[`huggingface-models-nexus-warm-2026-08-17.json`](../../perf-data/huggingface-models-nexus-warm-2026-08-17.json)，
SHA-256 为 `b271d188dfb57b4ba3483e20e49dcfd96cb1e88f6db8b2d76fe75ece6941cd93`。
冷填充、S3 与多副本样本见
[`huggingface-models-cold-s3-2026-08-17.json`](../../perf-data/huggingface-models-cold-s3-2026-08-17.json)，
SHA-256 为 `74389375dc1f2b8192971b164077974044dd74789da5d72610c799bbaf93aa6f`。

这些数据用于协议回归门禁，不是生产 SLA。TLS、反向代理、远端云对象存储、跨可用区数据库、
Cleanup/扫描混合负载、256 MiB/5 GiB 文件与百万行容量，需要在目标部署环境中另行测试后再
设定容量上限。

## 复现

启动确定性 fixture，把全新的 Nexus 与 kkRepo `huggingface-proxy` 指向它；热路径先填充
immutable 文件，然后执行：

```bash
python3 scripts/perf/compare-huggingface-nexus.py \
  --nexus-base-url http://127.0.0.1:48090/repository/<repo> \
  --kkrepo-base-url http://127.0.0.1:59090/repository/<repo> \
  --nexus-auth "$NEXUS_USER:$NEXUS_PASSWORD" \
  --kkrepo-auth "$KKREPO_USER:$KKREPO_PASSWORD" \
  --file-sha256 74a18e3f48369ee8c8e7cd03bd8b786591b0c19e2ee4df6ec97e74bef0c849d8 \
  --file-size 4194304 \
  --requests 500 --concurrency 16 --warmups 64 --rounds 5 \
  --skip-cold --enforce-gates \
  --output /tmp/huggingface-performance.json
```

冷样本使用全新仓库对；凭据必须留在仓库外，只提交经过检查且不包含授权信息的结果。
