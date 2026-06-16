# Monitoring And Observability Guide

This document describes nexus-plus health checks, Prometheus metrics, Grafana dashboards, and common alerting recommendations.

## Exposed Ports

nexus-plus exposes health checks and metrics through Spring Boot Actuator.

| Scenario | Service port | Management port | Description |
| --- | --- | --- | --- |
| Default runtime | `8080` | `8081` | Management port can be overridden by `NEXUS_PLUS_MANAGEMENT_PORT` |
| Local `dev` profile | `18090` | `18091` | Default ports used by `scripts/dev.sh` |

Common endpoints:

```text
/actuator/health
/actuator/metrics
/actuator/prometheus
```

Local verification:

```bash
curl -sS http://127.0.0.1:8081/actuator/health
curl -sS http://127.0.0.1:8081/actuator/prometheus | head
```

If the development script is used, change the port to `18091`.

## Prometheus Integration

Prometheus scrapes `/actuator/prometheus` on the management port:

```yaml
scrape_configs:
  - job_name: nexus-plus
    metrics_path: /actuator/prometheus
    static_configs:
      - targets:
          - nexus-plus:8081
```

In Kubernetes, add Prometheus scrape annotations to the Pod or Service:

```yaml
prometheus.io/scrape: "true"
prometheus.io/port: "8081"
prometheus.io/path: "/actuator/prometheus"
```

If Prometheus Operator is used, scrape the management port with a `ServiceMonitor`:

```yaml
apiVersion: monitoring.coreos.com/v1
kind: ServiceMonitor
metadata:
  name: nexus-plus
spec:
  selector:
    matchLabels:
      app: nexus-plus
  endpoints:
    - port: management
      path: /actuator/prometheus
      interval: 30s
```

Metrics include the `application` label by default. Its value comes from `spring.application.name`, which is `nexus-plus` by default. For multi-environment deployments, keep labels such as `namespace`, `pod`, and `instance` on the Prometheus side so you can troubleshoot by environment and replica.

## Grafana Dashboard

Bundled Grafana dashboard:

```text
docs/resources/grafana/dashboard.json
```

Import steps:

1. Open Grafana.
2. Go to `Dashboards` -> `New` -> `Import`.
3. Upload `docs/resources/grafana/dashboard.json`.
4. Select any Prometheus data source.
5. Select `application` at the top of the dashboard. It matches `nexus-plus` application labels by default.

The dashboard uses a generic Prometheus data source variable and is not bound to a specific `Prometheus-DEV` data source name.

### Dashboard Preview

Overall HTTP requests, QPS, latency, and error overview:

![Grafana overview](../img/grafna/img.png)

Repository requests, proxy remote access, and blob storage metrics:

![Grafana repository and storage metrics](../img/grafna/img_1.png)

Background tasks, rebuild queues, GC, and rate-limit metrics:

![Grafana background task metrics](../img/grafna/img_2.png)

## Key Metrics

### HTTP And Repository Requests

| Metric | Type | Description |
| --- | --- | --- |
| `http_server_requests_seconds_*` | Spring Boot timer | HTTP request latency and count |
| `nexus_plus_repository_requests_total` | counter | Repository protocol request count |
| `nexus_plus_repository_request_duration_seconds_*` | timer | Repository protocol request latency |
| `nexus_plus_repository_upload_bytes_total` | counter | Uploaded request body bytes |
| `nexus_plus_repository_response_bytes_total` | counter | Response bytes |

Common labels:

- `repo`: repository name
- `format`: artifact format, such as `maven2`, `npm`, `pypi`
- `type`: repository type, such as `hosted`, `proxy`, `group`
- `method`: HTTP method
- `operation`: protocol operation
- `status`: HTTP status code
- `outcome`: request outcome, such as `success`, `client_error`, `server_error`, `error`

### Proxy Remote Access

| Metric | Type | Description |
| --- | --- | --- |
| `nexus_plus_proxy_remote_requests_total` | counter | Upstream request count for proxy repositories |
| `nexus_plus_proxy_remote_duration_seconds_*` | timer | Upstream request latency for proxy repositories |
| `nexus_plus_proxy_cache_events_total` | counter | Proxy cache hit, miss, negative cache, and similar events |

Focus on `remote_host`, `status`, and `outcome`. If package pulls from a proxy repository become slow, check upstream latency and upstream errors first.

### Blob Storage

| Metric | Type | Description |
| --- | --- | --- |
| `nexus_plus_blob_storage_operations_total` | counter | Blob storage operation count |
| `nexus_plus_blob_storage_operation_duration_seconds_*` | timer | Blob storage operation latency |
| `nexus_plus_blob_storage_bytes_total` | counter | Blob read/write bytes |

Common labels:

- `store`: blob store name
- `type`: blob store type
- `engine`: storage engine, such as `oss-native`, `s3`, `file`
- `op`: operation, such as `put`, `get`, `get_range`, `delete`
- `outcome`: `success`, `miss`, `error`

### Background Tasks And Queues

| Metric | Type | Description |
| --- | --- | --- |
| `nexus_plus_worker_items_total` | counter | Number of items processed by background workers |
| `nexus_plus_worker_item_duration_seconds_*` | timer | Time spent processing a single item |
| `nexus_plus_worker_batch_duration_seconds_*` | timer | Time spent processing a worker batch |
| `nexus_plus_metadata_rebuild_backlog` | gauge | Maven metadata rebuild backlog |
| `nexus_plus_metadata_rebuild_oldest_age_seconds` | gauge | Wait time of the oldest Maven metadata rebuild task |
| `nexus_plus_metadata_rebuild_failures` | gauge | Maven metadata rebuild failure backlog |
| `nexus_plus_repository_index_rebuild_backlog` | gauge | Repository index rebuild backlog |
| `nexus_plus_repository_index_rebuild_oldest_age_seconds` | gauge | Wait time of the oldest repository index rebuild task |
| `nexus_plus_repository_index_rebuild_failures` | gauge | Repository index rebuild failure backlog |

If browse/search or protocol metadata is not updated for a long time, check backlog, oldest age, and failures first.

### Blob GC

| Metric | Type | Description |
| --- | --- | --- |
| `nexus_plus_blob_gc_backlog` | gauge | Number of soft-deleted blobs waiting for GC |
| `nexus_plus_blob_gc_deleted_bytes_total` | counter | Blob bytes deleted by GC |
| `nexus_plus_blob_gc_reconcile_scanned_total` | counter | Rows scanned by orphan blob reconcile |
| `nexus_plus_blob_gc_reconcile_marked_total` | counter | Orphan blobs marked by reconcile |
| `nexus_plus_blob_unreferenced_reconcile_cursor` | gauge | Orphan blob reconcile scan cursor |

### Shared Cache And Rate Limiting

| Metric | Type | Description |
| --- | --- | --- |
| `nexus_plus_cache_requests_total` | counter | Shared TTL cache request count |
| `nexus_plus_cache_operation_duration_seconds_*` | timer | Cache operation latency |
| `nexus_plus_cache_scan_deleted_keys_total` | counter | Number of cache keys deleted by prefix scan |
| `nexus_plus_rate_limit_blocked_total` | counter | Number of requests blocked by rate limiting |

## Alert Recommendations

The following PromQL rules are starting points only. Production thresholds should be adjusted based on business traffic and repository scale.

### Instance Not Scrapeable

```promql
up{job="nexus-plus"} == 0
```

### Repository Request 5xx

```promql
sum(rate(nexus_plus_repository_requests_total{outcome="server_error"}[5m])) > 0
```

### Repository Request Latency Too High

```promql
histogram_quantile(
  0.95,
  sum(rate(nexus_plus_repository_request_duration_seconds_bucket[5m])) by (le, repo, method, operation)
) > 2
```

### Proxy Upstream Errors

```promql
sum(rate(nexus_plus_proxy_remote_requests_total{outcome=~"server_error|error"}[5m])) by (repo, remote_host) > 0
```

### Blob Storage Errors

```promql
sum(rate(nexus_plus_blob_storage_operations_total{outcome="error"}[5m])) by (store, engine, op) > 0
```

### Background Queue Backlog

```promql
nexus_plus_metadata_rebuild_oldest_age_seconds > 300
or
nexus_plus_repository_index_rebuild_oldest_age_seconds > 300
```

### Rate Limit Blocks

```promql
sum(rate(nexus_plus_rate_limit_blocked_total[5m])) by (type) > 0
```

## Log Troubleshooting

Repository requests with non-success status are logged by default. Control this with:

```properties
nexus-plus.repository.log-non-success-requests=true
nexus-plus.repository.log-non-success-request-excluded-statuses=477,488
```

Corresponding environment variables:

```bash
NEXUS_PLUS_REPOSITORY_LOG_NON_SUCCESS_REQUESTS=true
NEXUS_PLUS_REPOSITORY_LOG_NON_SUCCESS_REQUEST_EXCLUDED_STATUSES=477,488
```

Troubleshooting suggestions:

- Slow package pulls: check `Repository Request Latency`, then `Proxy Remote Latency` and `Blob Storage Latency`.
- Proxy repository failures: check `remote_host`, `status`, and `outcome` on `nexus_plus_proxy_remote_requests_total`.
- Slow or failed uploads: check `nexus_plus_repository_upload_bytes_total`, blob `put` latency, and blob `error`.
- browse/search not updating: check metadata/index rebuild backlog and failures.
- Many 401/403 responses: investigate with security audit logs and repository permission configuration.

## FAQ

### `/actuator/prometheus` Returns 404

Make sure you are accessing the management port, not the service port. The default management port is `8081`; the local `dev` profile uses `18091`.

### Grafana Has No Data

First confirm the Prometheus target is `UP`, then check the dashboard data source and `application` variable. nexus-plus uses `nexus-plus` as the default `application` label.

### Only Partial Data Is Visible In Multi-Replica Deployment

Make sure Prometheus scrapes the management port of every replica and keeps `instance`, `pod`, or equivalent labels. Aggregation queries usually need to aggregate by business labels while preserving replica dimensions for single-replica troubleshooting.
