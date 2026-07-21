# 监控观测指南

本文档说明 kkrepo 的健康检查、Prometheus 指标、Grafana 仪表盘和常用告警建议。

## 暴露端口

kkrepo 使用 Spring Boot Actuator 暴露健康检查和指标。

| 场景 | 服务端口 | 管理端口 | 说明 |
| --- | --- | --- | --- |
| 默认运行 | `8080` | `8081` | 管理端口由 `KKREPO_MANAGEMENT_PORT` 覆盖 |
| 本地 `dev` profile | `18090` | `18091` | `scripts/dev.sh` 使用的默认端口 |

常用端点：

```text
/actuator/health
/actuator/metrics
/actuator/prometheus
```

本地验证：

```bash
curl -sS http://127.0.0.1:8081/actuator/health
curl -sS http://127.0.0.1:8081/actuator/prometheus | head
```

如果使用开发脚本启动，把端口换成 `18091`。

## Prometheus 接入

Prometheus 抓取管理端口的 `/actuator/prometheus`：

```yaml
scrape_configs:
  - job_name: kkrepo
    metrics_path: /actuator/prometheus
    static_configs:
      - targets:
          - kkrepo:8081
```

Kubernetes 环境可以给 Pod 或 Service 增加 Prometheus 抓取注解：

```yaml
prometheus.io/scrape: "true"
prometheus.io/port: "8081"
prometheus.io/path: "/actuator/prometheus"
```

如果使用 Prometheus Operator，可以用 `ServiceMonitor` 抓取管理端口：

```yaml
apiVersion: monitoring.coreos.com/v1
kind: ServiceMonitor
metadata:
  name: kkrepo
spec:
  selector:
    matchLabels:
      app: kkrepo
  endpoints:
    - port: management
      path: /actuator/prometheus
      interval: 30s
```

指标默认带 `application` 标签，默认值来自 `spring.application.name`，也就是 `kkrepo`。如果部署多个环境，建议在 Prometheus 侧保留 `namespace`、`pod`、`instance` 等标签，便于按环境和副本排查。

## Grafana 仪表盘

内置 Grafana dashboard 文件：

```text
docs/resources/grafana/dashboard.json
```

导入方式：

1. 打开 Grafana。
2. 进入 `Dashboards` -> `New` -> `Import`。
3. 上传 `docs/resources/grafana/dashboard.json`。
4. 选择任意 Prometheus 数据源。
5. 在 dashboard 顶部选择 `application`，默认匹配 `kkrepo` 系列应用标签。

仪表盘已使用通用 Prometheus 数据源变量，不绑定特定的 `Prometheus-DEV` 数据源名称。

### 仪表盘预览

总体 HTTP 请求、QPS、延迟和错误概览：

![Grafana 总览](../img/grafna/img.png)

仓库请求、代理回源和 blob 存储指标：

![Grafana 仓库和存储指标](../img/grafna/img_1.png)

后台任务、重建队列、GC 和限流指标：

![Grafana 后台任务指标](../img/grafna/img_2.png)

## 关键指标

### HTTP 和仓库请求

| 指标 | 类型 | 说明 |
| --- | --- | --- |
| `http_server_requests_seconds_*` | Spring Boot timer | HTTP 请求延迟和数量 |
| `kkrepo_repository_requests_total` | counter | 仓库协议请求数 |
| `kkrepo_repository_request_duration_seconds_*` | timer | 仓库协议请求延迟 |
| `kkrepo_repository_upload_bytes_total` | counter | 上传请求体字节数 |
| `kkrepo_repository_response_bytes_total` | counter | 响应字节数 |

常用标签：

- `repo`：仓库名
- `format`：制品格式，例如 `maven2`、`npm`、`pypi`、`cargo`、`pub`、`composer`、`docker`
- `type`：仓库类型，例如 `hosted`、`proxy`、`group`
- `method`：HTTP method
- `operation`：协议操作
- `status`：HTTP 状态码
- `outcome`：请求结果，例如 `success`、`client_error`、`server_error`、`error`

### Proxy 回源

| 指标 | 类型 | 说明 |
| --- | --- | --- |
| `kkrepo_proxy_remote_requests_total` | counter | proxy 仓库回源请求数 |
| `kkrepo_proxy_remote_duration_seconds_*` | timer | proxy 仓库回源延迟 |
| `kkrepo_proxy_cache_events_total` | counter | proxy cache 命中、未命中、negative cache 等事件 |

重点关注 `remote_host`、`status` 和 `outcome`。如果 proxy 仓库拉包变慢，优先看回源延迟和回源错误。

### Cargo / Rust

Cargo / Rust 通过 `format="cargo"` 暴露仓库请求和 blob 存储指标。常见 Cargo operation 标签包括：

- `cargo_config`
- `cargo_index`
- `cargo_crate`
- `cargo_api`
- `cargo_publish`
- `cargo_yank`
- `cargo_unyank`

Cargo proxy 上游请求会通过 `kkrepo_proxy_remote_*` 记录，并带 `format="cargo"`。

### Dart / Pub

Dart / Pub 通过 `format="pub"` 暴露仓库请求和 blob 存储指标。常见 Pub operation 标签包括：

- `pub_package_metadata`
- `pub_version_metadata`
- `pub_archive`
- `pub_publish_init`
- `pub_publish_upload`
- `pub_publish_finalize`
- `pub_request`

Pub proxy 上游请求会通过 `kkrepo_proxy_remote_*` 记录，并带 `format="pub"`。

### Composer / PHP

Composer / PHP 通过 `format="composer"` 暴露仓库请求、proxy 回源和 blob 存储指标。常见 operation 标签包括：

- `composer_metadata`
- `composer_dist`
- `composer_repository`

排查安装缓慢时，先区分 metadata 与 dist；metadata 回源异常通常影响 dependency resolution，dist 回源或 blob 延迟影响 `--prefer-dist` 下载。Composer proxy 上游请求通过 `kkrepo_proxy_remote_*` 记录。

### Terraform Provider / Module Registry

Terraform 通过 `format="terraform"` 暴露 repository、proxy 回源和 blob storage 指标。常见 operation label 包括：

- `terraform_module_versions`
- `terraform_module_download_metadata`
- `terraform_module_upload`
- `terraform_module_archive`
- `terraform_provider_versions`
- `terraform_provider_download_metadata`
- `terraform_provider_upload`
- `terraform_provider_archive`
- `terraform_provider_shasums`
- `terraform_provider_signature`

监控时应把 Provider metadata、archive、SHA256SUMS 和 signature 视为同一个流程。Metadata 成功但 checksum/signature 失败，通常表示上游快照过期或不一致。Terraform URL token path segment 会在进入日志和 metric label 前脱敏；不要把包含 token 的原始 service URL 或 metadata 放入 dashboard/CI artifact。

### Ansible Galaxy

Ansible Galaxy 通过 `format="ansiblegalaxy"` 暴露 repository、proxy 回源和 blob storage 指标。Repository operation label 固定为以下有界集合：

- `ansible_discovery`
- `ansible_collection`
- `ansible_version_list`
- `ansible_version_detail`
- `ansible_publish`
- `ansible_import_task`
- `ansible_artifact_upload`
- `ansible_artifact_download`
- `ansible_repository`

应把 publish 与 import task outcome 联合监控：`ansible_publish` 成功后持续出现 task error，通常表示 archive/manifest/checksum 校验或 worker takeover 失败。孤儿清理通过 `worker="ansible_staging_cleanup"` 报告 batch outcome，并通过 `worker="ansible_cleanup", item="staging_asset", outcome="deleted"` 报告清理数量；持续增长通常表示副本在 staging 与持久清理之间反复终止。排查 proxy/group 时，把 version-detail 延迟与 `kkrepo_proxy_remote_*` 关联，并把 artifact 读取与 blob-storage 指标关联。Namespace、collection name、version、task UUID 和 token material 不能成为 metric label；coordinate 级排查应使用受控 audit/task detail。大上游 JSON 与完整 manifest/files 文档保留为 blob 内容，因此数据库增长应来自有上限的投影与 task/state 行，而不是 payload 大小。

### Blob 存储

| 指标 | 类型 | 说明 |
| --- | --- | --- |
| `kkrepo_blob_storage_operations_total` | counter | blob 存储操作数 |
| `kkrepo_blob_storage_operation_duration_seconds_*` | timer | blob 存储操作延迟 |
| `kkrepo_blob_storage_bytes_total` | counter | blob 读写字节数 |

常用标签：

- `store`：blob store 名称
- `type`：blob store 类型
- `engine`：存储引擎，例如 `oss-native`、`s3`、`file`
- `op`：操作，例如 `put`、`get`、`get_range`、`delete`
- `outcome`：`success`、`miss`、`error`

### Docker / OCI

Docker / OCI 也会通过 `format="docker"` 暴露仓库请求和 blob 存储指标。额外的 Docker 专项指标包括：

| 指标 | 类型 | 说明 |
| --- | --- | --- |
| `kkrepo_docker_upload_sessions_total` | counter | Docker upload session 操作数，按 action 和 outcome 区分 |
| `kkrepo_docker_blob_mount_total` | counter | 跨仓库 blob mount 尝试次数 |
| `kkrepo_docker_cache_events_total` | counter | Docker manifest/blob/tag/group/negative-cache 事件 |
| `kkrepo_docker_digest_verifications_total` | counter | upload/proxy/blob digest 校验结果 |
| `kkrepo_docker_cleanup_items_total` | counter | Docker cleanup policy 删除或处理的 item 数 |
| `kkrepo_docker_referrers_total` | counter | OCI referrers API 响应次数 |
| `kkrepo_docker_referrer_descriptors_total` | counter | 返回的 OCI referrer descriptor 数 |
| `kkrepo_docker_uploads_active` | gauge | 当前活跃 Docker 上传请求 |
| `kkrepo_docker_downloads_active` | gauge | 当前活跃 Docker blob 下载请求 |
| `kkrepo_docker_uploads_limit` | gauge | Docker 上传并发限制配置值 |
| `kkrepo_docker_downloads_limit` | gauge | Docker blob 下载并发限制配置值 |

Docker proxy 仓库的上游 registry 请求会通过 `kkrepo_proxy_remote_*` 记录，
并带 `format="docker"`，其中包括 token 请求和 redirect hop。

### 后台任务和队列

| 指标 | 类型 | 说明 |
| --- | --- | --- |
| `kkrepo_worker_items_total` | counter | 后台 worker 处理的 item 数 |
| `kkrepo_worker_item_duration_seconds_*` | timer | 单个 item 处理耗时 |
| `kkrepo_worker_batch_duration_seconds_*` | timer | worker batch 处理耗时 |
| `kkrepo_metadata_rebuild_backlog` | gauge | Maven metadata 重建积压 |
| `kkrepo_metadata_rebuild_oldest_age_seconds` | gauge | 最老 Maven metadata 重建任务等待时长 |
| `kkrepo_metadata_rebuild_failures` | gauge | Maven metadata 重建失败积压 |
| `kkrepo_repository_index_rebuild_backlog` | gauge | 仓库索引重建积压 |
| `kkrepo_repository_index_rebuild_oldest_age_seconds` | gauge | 最老仓库索引重建任务等待时长 |
| `kkrepo_repository_index_rebuild_failures` | gauge | 仓库索引重建失败积压 |

如果 browse/search 或协议 metadata 长时间不更新，优先查看 backlog、oldest age 和 failures。

### Blob GC

| 指标 | 类型 | 说明 |
| --- | --- | --- |
| `kkrepo_blob_gc_backlog` | gauge | 等待 GC 的软删除 blob 数 |
| `kkrepo_blob_gc_deleted_bytes_total` | counter | GC 删除的 blob 字节数 |
| `kkrepo_blob_gc_reconcile_scanned_total` | counter | 孤儿 blob reconcile 扫描行数 |
| `kkrepo_blob_gc_reconcile_marked_total` | counter | reconcile 标记的孤儿 blob 数 |
| `kkrepo_blob_unreferenced_reconcile_cursor` | gauge | 孤儿 blob reconcile 扫描游标 |

### 共享缓存和限流

| 指标 | 类型 | 说明 |
| --- | --- | --- |
| `kkrepo_cache_requests_total` | counter | 共享 TTL cache 请求数 |
| `kkrepo_cache_operation_duration_seconds_*` | timer | cache 操作延迟 |
| `kkrepo_cache_scan_deleted_keys_total` | counter | prefix scan 删除的 cache key 数 |
| `kkrepo_rate_limit_blocked_total` | counter | 被限流拦截的请求数 |

## 告警建议

以下 PromQL 仅作为起点，生产阈值需要按业务流量和仓库规模调整。

### 实例不可抓取

```promql
up{job="kkrepo"} == 0
```

### 仓库请求 5xx

```promql
sum(rate(kkrepo_repository_requests_total{outcome="server_error"}[5m])) > 0
```

### 仓库请求延迟过高

```promql
histogram_quantile(
  0.95,
  sum(rate(kkrepo_repository_request_duration_seconds_bucket[5m])) by (le, repo, method, operation)
) > 2
```

### Proxy 回源异常

```promql
sum(rate(kkrepo_proxy_remote_requests_total{outcome=~"server_error|error"}[5m])) by (repo, remote_host) > 0
```

### Blob 存储错误

```promql
sum(rate(kkrepo_blob_storage_operations_total{outcome="error"}[5m])) by (store, engine, op) > 0
```

### 后台队列积压

```promql
kkrepo_metadata_rebuild_oldest_age_seconds > 300
or
kkrepo_repository_index_rebuild_oldest_age_seconds > 300
```

### 限流拦截

```promql
sum(rate(kkrepo_rate_limit_blocked_total[5m])) by (type) > 0
```

## 日志排查

仓库请求的非成功状态默认会记录日志，可通过以下配置控制：

```properties
kkrepo.repository.log-non-success-requests=true
kkrepo.repository.log-non-success-request-excluded-statuses=477,488
```

对应环境变量：

```bash
KKREPO_REPOSITORY_LOG_NON_SUCCESS_REQUESTS=true
KKREPO_REPOSITORY_LOG_NON_SUCCESS_REQUEST_EXCLUDED_STATUSES=477,488
```

排查建议：

- 客户端拉包慢：先看 `Repository Request Latency`，再看 `Proxy Remote Latency` 和 `Blob Storage Latency`。
- proxy 仓库失败：看 `kkrepo_proxy_remote_requests_total` 的 `remote_host`、`status`、`outcome`。
- 上传慢或失败：看 `kkrepo_repository_upload_bytes_total`、blob `put` 延迟和 blob `error`。
- browse/search 不更新：看 metadata/index rebuild backlog 和 failures。
- 大量 401/403：结合安全审计和仓库权限配置排查。

## 常见问题

### `/actuator/prometheus` 返回 404

确认访问的是管理端口，不是服务端口。默认管理端口是 `8081`，本地 `dev` profile 是 `18091`。

### Grafana 没有数据

先确认 Prometheus target 是 `UP`，再检查 dashboard 顶部的数据源和 `application` 变量。kkrepo 默认 `application` 标签是 `kkrepo`。

### 多副本只看到部分数据

确认 Prometheus 抓取了所有副本的管理端口，并保留 `instance`、`pod` 或等价标签。聚合查询通常需要按业务标签聚合，同时保留副本维度用于排查单点异常。
