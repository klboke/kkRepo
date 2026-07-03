# 生产加固指南

本文汇总 kkrepo 生产部署建议。它不能替代你所在环境自己的安全评审、备份规划和压测。

## 部署基线

推荐生产基线：

- 至少两个 kkrepo 副本，前面有负载均衡。
- 独立 MySQL 8.0 实例或托管 MySQL 服务。
- OSS/S3 兼容 blob 存储。
- 在负载均衡或反向代理层终止 HTTPS。
- 管理端口只暴露给可信监控和运维网络。
- 定期备份 MySQL 和 blob storage。

不要把 Docker Compose quickstart 配置直接用于公网可访问的生产部署。

## 必需密钥

写入生产数据前，先设置稳定且足够强的密钥：

```bash
KKREPO_CREDENTIAL_SECRET=<strong-random-string>
KKREPO_API_KEY_PAYLOAD_SECRET=<strong-random-string>
```

这些密钥保护：

- Blob-store access key。
- LDAP bind password。
- OIDC client secret。
- 用户可见 API-key payload。

写入数据后不要随意轮换这些密钥。如确需轮换，应设计并测试受控的重新加密流程。

## MySQL

使用外部 MySQL 实例：

- MySQL 8.0 或兼容托管服务。
- 数据库字符集使用 `utf8mb4`。
- 独立数据库和最小权限应用账号。
- 自动备份，并演练过恢复。
- 为所有副本和后台 worker 预留足够连接数。
- 监控 CPU、IOPS、慢查询、复制延迟、连接数和死锁。

需要时调整 Hikari：

```bash
KKREPO_HIKARI_MAXIMUM_POOL_SIZE=50
KKREPO_HIKARI_MINIMUM_IDLE=10
KKREPO_HIKARI_CONNECTION_TIMEOUT_MS=5000
```

大规模迁移场景下，先提升 MySQL 能力，再提高迁移并发。

## Blob 存储

生产环境使用 OSS/S3 兼容存储：

- 独立 bucket。
- 如安全策略要求，启用服务端加密。
- 如果恢复要求需要，启用 versioning 或保留策略。
- 在理解 blob GC 行为后再配置 lifecycle 规则。
- Access key 只授予目标 bucket/prefix 的最小权限。
- 监控延迟、4xx/5xx、限流和请求量。

对象存储流量较高时，可调整 S3 client：

```bash
KKREPO_S3_MAX_CONNECTIONS=512
KKREPO_S3_MULTIPART_THRESHOLD_BYTES=67108864
KKREPO_S3_MULTIPART_PART_SIZE_BYTES=16777216
KKREPO_S3_MULTIPART_CONCURRENCY=4
```

普通生产环境不建议使用 File blob storage。如果必须使用，所有副本必须挂载同一个强一致共享文件系统，并显式打开生产 File 存储开关：

```bash
KKREPO_FILE_PRODUCTION_ENABLED=true
KKREPO_FILE_SHARED_FILESYSTEM=true
```

## 网络和反向代理

通过 HTTPS 暴露应用端口，通常是 `8080`。管理端口通常是 `8081`，应保持内网可见。

反向代理 checklist：

- 保留 `/repository/`、`/admin/`、`/browse/` 下的完整 path。
- 保留 `Authorization`、`Cookie` 和相关协议 header。
- 为大制品上传配置 body size。
- 为长时间上传和下载配置读写 timeout。
- 对客户端主动断开下载做合理日志分类，避免误判为服务端故障。
- 一致设置 `X-Forwarded-*` header。

详细 Nginx 配置和生成仓库 URL 验证方式见 [Nginx 反向代理配置注意事项](nginx-reverse-proxy.md)。

需要时设置 OIDC 外部 URL 和可信代理：

```bash
KKREPO_EXTERNAL_BASE_URL=https://nexus.example.com
KKREPO_TRUSTED_PROXIES=10.0.12.34,10.0.12.35
```

## Session 和 Cookie 安全

生产环境使用 HTTPS，并启用 secure cookie：

```bash
KKREPO_SESSION_COOKIE_SECURE=true
KKREPO_CSRF_COOKIE_SECURE=true
KKREPO_HSTS_ENABLED=true
```

Session 状态保持 JDBC 存储，这是默认配置：

```bash
KKREPO_SESSION_STORE_TYPE=jdbc
```

## 认证和授权

生产 checklist：

- 除非明确需要公开读取，否则关闭 anonymous access。
- 人和 CI 都使用最小权限角色。
- 优先使用 CI token 或 API key，而不是共享用户密码。
- 定期审查角色、repository privilege 和长期未使用 API key。
- LDAP 或 OIDC 上线前，先验证 group/claim 映射。
- Local admin 凭据放在安全密码管理器中。

管理变更会记录审计日志，审计保留周期应符合你的合规要求。

## 出站代理安全

Proxy 仓库会访问远端内容。请保护出站访问：

- 将 remote URL 限制到预期公网或内网 host。
- 除非需要访问内网上游仓库，否则保持 private-address 出站访问关闭。
- 如需启用 private-address 访问，请设置明确 allowed hosts。

相关配置：

```bash
KKREPO_OUTBOUND_ALLOW_PRIVATE_ADDRESSES=false
KKREPO_OUTBOUND_ALLOWED_HOSTS=
```

## 资源规格

起步建议：

- kkrepo 副本：至少 2 CPU / 4 GB 内存。
- MySQL：至少 2 CPU / 4 GB 内存，根据仓库数、包数量和迁移负载扩容。
- Blob storage：容量和请求吞吐同时覆盖日常流量和迁移峰值。

并发提升时调整 Tomcat：

```bash
KKREPO_TOMCAT_THREADS_MAX=100
KKREPO_TOMCAT_MAX_CONNECTIONS=2000
KKREPO_TOMCAT_ACCEPT_COUNT=200
KKREPO_TOMCAT_CONNECTION_TIMEOUT=30s
```

## 上传限制

默认允许较大上传，但反向代理通常需要单独调整：

```bash
KKREPO_MULTIPART_MAX_FILE_SIZE=1024MB
KKREPO_MULTIPART_MAX_REQUEST_SIZE=1024MB
KKREPO_UPLOAD_MAX_REQUEST_BYTES=1073741824
```

确保代理限制、应用限制和对象存储 multipart 参数一致。

## 缓存

节点本地 cache 都是可重建缓存。正确性不能依赖本地内存作为唯一状态。

常用 cache 配置：

```bash
KKREPO_CACHE_BACKEND=memory
KKREPO_CACHE_MEMORY_MAXIMUM_SIZE=500000
KKREPO_SECURITY_AUTHORIZATION_CACHE_TTL_MINUTES=10
KKREPO_CATALOG_CACHE_BROADCAST_BACKEND=mysql
```

如果怀疑缓存问题，逐个重启副本，并通过 MySQL 支撑的状态验证行为。

## 监控

抓取管理端口：

```text
/actuator/health
/actuator/metrics
/actuator/prometheus
```

建议监控：

- 应用健康状态。
- 仓库请求量、状态码和延迟。
- 上传/下载错误。
- MySQL 连接池和慢查询。
- Blob-storage 延迟和错误。
- 迁移队列、失败 asset 和吞吐。
- JVM 内存、GC、线程和文件句柄。

详见 [监控观测指南](monitoring-observability-guide.md)。

## 升级和回滚

升级前：

- 阅读 `CHANGELOG.md`。
- 备份 MySQL。
- 确认 blob-storage 备份或 versioning 策略。
- 在 staging 环境使用接近生产的数据形态测试新版本。
- 确认 Flyway migration 符合预期。
- 执行代表性的客户端 pull 和 push。

推荐发布流程：

1. 先部署一个新副本。
2. 验证 health、admin UI、browse UI 和核心仓库。
3. 滚动升级剩余副本。
4. 保留上一版本 artifact/image 以便回滚。

如果已经执行数据库 migration，回滚可能需要恢复数据库，而不仅仅是重新部署旧镜像。

## 安全披露和公开日志

不要在公开 issue 中发布凭据、token、cookie、私有包内容或迁移 dump。

可利用安全问题请按 [SECURITY.md](../../SECURITY.md) 私下报告。
