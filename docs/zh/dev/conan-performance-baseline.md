# Conan 2 与 Nexus 本地性能基线

本文记录 Conan 2 功能落地时 kkRepo 与 Sonatype Nexus Repository 的同机定向对比。它是协议热路径门禁，不等同于生产容量结论；TLS、反向代理、远端 OSS/S3、数据库高可用、多副本负载均衡和混合读写负载仍需在目标部署环境单独验证。

英文版见 [Local Conan 2 Performance Baseline Against Nexus](../../en/dev/conan-performance-baseline.md)。

## 测试环境

- 时间：MySQL `2026-08-11T15:41:19Z`，PostgreSQL `2026-08-11T16:38:54Z`。
- 主机：Intel Core i9-9880H、64 GiB RAM、macOS 14.7.8 x86_64；Docker 29.4.0。
- Reference：Sonatype Nexus Repository `3.94.0`，使用 PostgreSQL 16。
- Candidate：当前分支的 kkRepo `0.7.0` 开发构建，分别连接 MySQL 8.0 与 PostgreSQL 17；被测进程/容器未设置独立 CPU 或内存上限。
- 两端均使用本地文件型 blob storage 和相同 Conan 2.31.2 逻辑 fixture：`kkrepo-conan-performance/1.0.0@kkrepo/stable`，包含一个 RREV、一个 PREV，以及 `4,195,879` byte `conan_package.tgz` 内的 `4,194,304` byte payload。

## 方法与正确性预检

[`scripts/perf/compare-conan-nexus.py`](../../../scripts/perf/compare-conan-nexus.py) 覆盖 ping、recipe search、RREV/PREV latest/list、recipe/package file list、package search、完整 package GET、64 KiB Range 和 Nexus 对齐 HEAD 行为。每个场景先预热 32 次，然后在并发 16 下请求 250 次；共执行 3 轮并交替目标顺序，报告三轮中位数。

计时前必须满足两端 status 与规范化语义一致：

- 精确 recipe search，以及 package search 的 `(package ID, content)`；
- latest RREV/PREV、revision list 和 file list；
- Package archive 的逻辑 tar tree、member size 和 member SHA-256；
- Range 正好返回 65,536 bytes；
- HEAD 返回 Nexus 3.94 同样的 HTTP `404`，不合成 GET 响应。

独立上传后 tar container metadata 可以不同，因此预检比较逻辑树，不强制 gzip header 字节相同。计时前还分别在 kkRepo 两种数据库后端用真实 Conan 2.31.2 完成 login、upload、list、清空客户端 cache、download/install，以及 group/proxy install。

Metadata 门禁要求 kkRepo 吞吐不低于 Nexus `0.80x`，p95 不高于 `1.25x`；完整 GET/Range 要求吞吐不低于 `0.90x`，p95 不高于 `1.15x`。HEAD 只做兼容性断言，不属于成功文件读取性能门禁。

## PostgreSQL 结果

| 场景 | Nexus req/s | kkRepo req/s | 吞吐比 | Nexus p95 | kkRepo p95 | p95 比 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| ping | 2431.90 | 2804.03 | 1.153x | 14.272 ms | 9.523 ms | 0.667x |
| recipe search | 1836.92 | 2553.25 | 1.390x | 17.553 ms | 10.319 ms | 0.588x |
| recipe latest | 1923.89 | 1720.92 | 0.895x | 15.081 ms | 15.160 ms | 1.005x |
| recipe revisions | 1848.03 | 2270.21 | 1.228x | 17.102 ms | 9.685 ms | 0.566x |
| recipe files | 1265.82 | 2208.11 | 1.744x | 23.579 ms | 10.587 ms | 0.449x |
| package search | 1899.59 | 1999.74 | 1.053x | 18.674 ms | 14.537 ms | 0.778x |
| package latest | 994.52 | 1537.61 | 1.546x | 34.377 ms | 19.094 ms | 0.555x |
| package revisions | 1047.89 | 1869.82 | 1.784x | 32.311 ms | 13.189 ms | 0.408x |
| package files | 1017.28 | 2108.30 | 2.072x | 30.852 ms | 11.055 ms | 0.358x |
| package GET | 269.02 | 453.86 | 1.687x | 151.584 ms | 51.426 ms | 0.339x |
| package Range 64 KiB | 1068.67 | 1246.14 | 1.166x | 25.873 ms | 20.711 ms | 0.800x |
| HEAD 兼容（`404`） | 104.66 | 2201.72 | 21.037x | 197.649 ms | 13.900 ms | 0.070x |

PostgreSQL 全部场景过门禁。吞吐余量最小的是 recipe latest（`0.895x`），p95 仍在 metadata 门禁内（`1.005x`）。完整 package GET 吞吐为 Nexus 的 `1.687x`，p95 为 `0.339x`。

## MySQL 结果

| 场景 | Nexus req/s | kkRepo req/s | 吞吐比 | Nexus p95 | kkRepo p95 | p95 比 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| ping | 3173.70 | 3110.18 | 0.980x | 9.553 ms | 8.516 ms | 0.891x |
| recipe search | 1485.89 | 2493.93 | 1.678x | 38.354 ms | 9.163 ms | 0.239x |
| recipe latest | 2292.72 | 2344.56 | 1.023x | 12.181 ms | 9.647 ms | 0.792x |
| recipe revisions | 2409.73 | 2430.94 | 1.009x | 14.474 ms | 9.091 ms | 0.628x |
| recipe files | 2366.45 | 2095.00 | 0.885x | 13.047 ms | 11.332 ms | 0.869x |
| package search | 2273.58 | 2471.49 | 1.087x | 13.077 ms | 9.029 ms | 0.690x |
| package latest | 1814.53 | 2513.84 | 1.385x | 19.117 ms | 8.529 ms | 0.446x |
| package revisions | 1936.61 | 2541.28 | 1.312x | 14.048 ms | 8.393 ms | 0.597x |
| package files | 1789.46 | 2276.05 | 1.272x | 15.190 ms | 9.947 ms | 0.655x |
| package GET | 357.13 | 599.98 | 1.680x | 59.550 ms | 34.652 ms | 0.582x |
| package Range 64 KiB | 1497.42 | 1768.92 | 1.181x | 25.667 ms | 13.614 ms | 0.530x |
| HEAD 兼容（`404`） | 188.88 | 3500.74 | 18.534x | 103.716 ms | 8.712 ms | 0.084x |

MySQL 全部场景过门禁。吞吐余量最小的是 recipe file list（`0.885x`），p95 仍更低（`0.869x`）。完整 package GET 吞吐为 Nexus 的 `1.680x`，p95 为 `0.582x`。

## 数据库访问路径门禁

Conan 状态按类型规范化，每个请求/worker lookup 都有前导高选择性索引。MySQL/PostgreSQL integration contract 同时断言关键 exact/prefix/file lookup 的索引定义和 optimizer plan。主要访问形状如下：

| 访问形状 | 索引 |
| --- | --- |
| 精确 recipe 与前缀 search | `uk_conan_recipe_coordinate`；`idx_conan_recipe_name_page` |
| RREV exact/list/latest | `uk_conan_rrev`；`idx_conan_rrev_page`；latest FK 指向 revision PK |
| Package ID exact/list | `uk_conan_package`；`idx_conan_package_list` |
| PREV exact/list/latest | `uk_conan_prev`；`idx_conan_prev_page`；latest FK 指向 revision PK |
| Revision file exact/list | `uk_conan_revision_file`；`idx_conan_file_list` |
| Upload resume 与过期 session claim | `uk_conan_upload_session`；`idx_conan_upload_claim` |
| 分布式 coordinate lease | `PRIMARY(repository_id, coordinate_hash)`；`idx_conan_lease_expiry` |
| Group source binding/失效 | `uk_conan_group_binding`；`idx_conan_group_member` |
| Bearer 查询/过期清理 | Token 主键；`idx_conan_token_expiry` |
| Browse exact/root/child | `uk_browse_node_path`；`idx_browse_node_root`；`idx_browse_node_parent` |

Hash 索引命中后始终复核已存 canonical value，固定宽度索引不会把 hash collision 当成 identity 命中。有序多段数据库 key 使用带版本、长度前缀的编码，区分 null/empty 且永不写入 NUL，因此同一 identity 可同时用于 MySQL 与 PostgreSQL。分页全部有界并使用 keyset；Browse 只读取写入时持久化的投影，不扫描或解析 Conan asset path。

## 复现

先向两端 hosted 仓库上传相同的完整 RREV/PREV，再执行：

```bash
python3 scripts/perf/compare-conan-nexus.py \
  --nexus-base-url http://127.0.0.1:48090/repository/<repo> \
  --kkrepo-base-url http://127.0.0.1:19090/repository/<repo> \
  --nexus-auth "$NEXUS_USER:$NEXUS_PASSWORD" \
  --kkrepo-auth "$KKREPO_USER:$KKREPO_PASSWORD" \
  --name kkrepo-conan-performance \
  --version 1.0.0 \
  --user kkrepo \
  --channel stable \
  --recipe-revision <rrev> \
  --package-id <package-id> \
  --package-revision <prev> \
  --requests 250 \
  --concurrency 16 \
  --warmups 32 \
  --rounds 3 \
  --enforce-gates \
  --output /tmp/conan-performance.json
```

凭据只通过参数传给本地进程；不要提交包含真实密码的命令或结果文件。
