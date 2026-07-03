# Production Hardening Guide

This checklist summarizes recommended production settings for kkrepo. It does not replace environment-specific security review, backup planning, or load testing.

## Deployment Baseline

Recommended production baseline:

- At least two kkrepo replicas behind a load balancer.
- Independent MySQL 8.0 instance or managed MySQL service.
- OSS/S3-compatible blob storage.
- HTTPS termination at a load balancer or reverse proxy.
- Management port exposed only to trusted monitoring and operations networks.
- Regular MySQL and blob-storage backups.

Avoid using Docker Compose quickstart settings for externally reachable production deployments.

## Required Secrets

Set stable, strong secrets before writing production data:

```bash
KKREPO_CREDENTIAL_SECRET=<strong-random-string>
KKREPO_API_KEY_PAYLOAD_SECRET=<strong-random-string>
```

These secrets protect:

- Blob-store access keys.
- LDAP bind passwords.
- OIDC client secrets.
- User-facing API-key payloads.

Do not rotate them casually after data has been written. If rotation is needed, plan and test a controlled re-encryption process.

## MySQL

Use an external MySQL instance:

- MySQL 8.0 or compatible managed service.
- `utf8mb4` database character set.
- Dedicated database and least-privilege application account.
- Automated backups and tested restore.
- Sufficient connection capacity for every replica and background worker.
- Monitoring for CPU, IOPS, slow queries, replication lag, connection count, and deadlocks.

Tune Hikari when needed:

```bash
KKREPO_HIKARI_MAXIMUM_POOL_SIZE=50
KKREPO_HIKARI_MINIMUM_IDLE=10
KKREPO_HIKARI_CONNECTION_TIMEOUT_MS=5000
```

For large migrations, increase MySQL capacity before increasing migration concurrency.

## Blob Storage

Use OSS/S3-compatible storage for production:

- Dedicated bucket.
- Server-side encryption if required by your security policy.
- Versioning or retention policy when recovery requirements demand it.
- Lifecycle rules only after you understand blob GC behavior.
- Access keys with least privilege to the target bucket/prefix.
- Monitoring for latency, 4xx/5xx errors, throttling, and request volume.

Tune S3 client capacity when object-storage traffic is high:

```bash
KKREPO_S3_MAX_CONNECTIONS=512
KKREPO_S3_MULTIPART_THRESHOLD_BYTES=67108864
KKREPO_S3_MULTIPART_PART_SIZE_BYTES=16777216
KKREPO_S3_MULTIPART_CONCURRENCY=4
```

File blob storage is not recommended for normal production. If you must use it, every replica must mount the same strongly consistent shared filesystem and production file storage must be explicitly enabled:

```bash
KKREPO_FILE_PRODUCTION_ENABLED=true
KKREPO_FILE_SHARED_FILESYSTEM=true
```

## Network And Reverse Proxy

Expose the application port, usually `8080`, through HTTPS. Keep the management port, usually `8081`, private.

Reverse proxy checklist:

- Preserve the original path under `/repository/`, `/admin/`, and `/browse/`.
- Preserve `Authorization`, `Cookie`, and relevant protocol headers.
- Configure upload body size for large artifacts.
- Configure read/write timeouts for long uploads and downloads.
- Configure client abort logging so disconnected downloads do not look like server faults.
- Set `X-Forwarded-*` headers consistently.

For exact Nginx settings and generated repository URL verification, see the [Nginx Reverse Proxy Notes](nginx-reverse-proxy.md).

Set OIDC external URL and trusted proxy settings when required by your deployment:

```bash
KKREPO_EXTERNAL_BASE_URL=https://nexus.example.com
KKREPO_TRUSTED_PROXIES=10.0.12.34,10.0.12.35
```

## Session And Cookie Security

Use HTTPS in production and enable secure cookies:

```bash
KKREPO_SESSION_COOKIE_SECURE=true
KKREPO_CSRF_COOKIE_SECURE=true
KKREPO_HSTS_ENABLED=true
```

Keep session state in JDBC, which is the default:

```bash
KKREPO_SESSION_STORE_TYPE=jdbc
```

## Authentication And Authorization

Production checklist:

- Disable anonymous access unless public read access is intentional.
- Use least-privilege roles for CI and human users.
- Prefer CI tokens or API keys over shared user passwords.
- Periodically review roles, repository privileges, and stale API keys.
- Enable LDAP or OIDC only after testing group/claim mapping.
- Keep local admin credentials in a secure password manager.

Record administrative changes through the audit log and keep audit retention aligned with your compliance needs.

## Outbound Proxy Safety

Proxy repositories fetch remote content. Protect outbound access:

- Restrict remote URLs to expected public or internal hosts.
- Keep private-address outbound access disabled unless your deployment needs internal upstream repositories.
- If enabling private-address access, set explicit allowed hosts.

Relevant settings:

```bash
KKREPO_OUTBOUND_ALLOW_PRIVATE_ADDRESSES=false
KKREPO_OUTBOUND_ALLOWED_HOSTS=
```

## Resource Sizing

Starting point:

- kkrepo replica: at least 2 CPU / 4 GB memory.
- MySQL: at least 2 CPU / 4 GB memory, scaled by repository count, package count, and migration workload.
- Blob storage: capacity and request throughput sized for both daily traffic and migration bursts.

Tune Tomcat when concurrency grows:

```bash
KKREPO_TOMCAT_THREADS_MAX=100
KKREPO_TOMCAT_MAX_CONNECTIONS=2000
KKREPO_TOMCAT_ACCEPT_COUNT=200
KKREPO_TOMCAT_CONNECTION_TIMEOUT=30s
```

## Upload Limits

Defaults allow large uploads, but reverse proxies often need separate tuning:

```bash
KKREPO_MULTIPART_MAX_FILE_SIZE=1024MB
KKREPO_MULTIPART_MAX_REQUEST_SIZE=1024MB
KKREPO_UPLOAD_MAX_REQUEST_BYTES=1073741824
```

Make sure proxy limits, application limits, and object-storage multipart settings are aligned.

## Caches

Node-local caches are rebuildable. For correctness, do not rely on local memory as the only state.

Useful cache settings:

```bash
KKREPO_CACHE_BACKEND=memory
KKREPO_CACHE_MEMORY_MAXIMUM_SIZE=500000
KKREPO_SECURITY_AUTHORIZATION_CACHE_TTL_MINUTES=10
KKREPO_CATALOG_CACHE_BROADCAST_BACKEND=mysql
```

If cache issues are suspected, restart one replica at a time and verify behavior through MySQL-backed state.

## Monitoring

Scrape the management port:

```text
/actuator/health
/actuator/metrics
/actuator/prometheus
```

Monitor:

- Application health.
- Repository request rate, status, and latency.
- Upload/download errors.
- MySQL pool usage and slow queries.
- Blob-storage latency and errors.
- Migration queue size, failed assets, and throughput.
- JVM memory, GC, threads, and file descriptors.

See [Monitoring And Observability Guide](monitoring-observability-guide.md).

## Upgrade And Rollback

Before upgrading:

- Read `CHANGELOG.md`.
- Back up MySQL.
- Confirm blob-storage backup or versioning posture.
- Test the new version in a staging environment using production-like data shape.
- Verify Flyway migrations.
- Run representative client pulls and pushes.

Recommended rollout:

1. Deploy one new replica.
2. Verify health, admin UI, browse UI, and key repositories.
3. Roll through remaining replicas.
4. Keep the previous artifact/image available for rollback.

If database migrations have run, rollback may require database restore rather than only redeploying the old image.

## Security Disclosure And Public Logs

Never post credentials, tokens, cookies, private package contents, or migration dumps in public issues.

Report exploitable security issues through [SECURITY.md](../../SECURITY.md).
