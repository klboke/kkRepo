# Dev heterogeneous runtime deployment

The `kkrepo.cn` dev deployment runs two application processes built from the same Git commit:

- JVM: application `127.0.0.1:8080`, management `127.0.0.1:8081`
- Native: application `127.0.0.1:8090`, management `127.0.0.1:8091`

Nginx uses equal round-robin weights, so new requests are split 50/50. Both processes use the
same PostgreSQL database, encryption secrets, scanner configuration, and File blob root. They have
separate release, PID, console-log, and management-port paths. Do not deploy different Git commits
as the two long-running replicas: short rolling overlap is supported, but runtime comparisons must
not be confounded by application-version drift.

## Automatic deployment

`Deploy Dev` builds the JVM jar and Native executable from the exact `main` workflow SHA. It deploys
Native first and JVM second, verifies both direct readiness endpoints, and checks that both active
release symlinks resolve to that SHA. If the second runtime or the public probe fails, the workflow
reactivates both previous releases so the pair cannot remain on different revisions.

The deployment stages the systemd and Nginx files under:

```text
/opt/kkrepo/runtime/deploy-config/
```

The ordinary deploy SSH account intentionally cannot modify systemd or signal the Nginx container.

## One-time privileged activation

After the first dual-runtime deployment, run the staged bootstrap once as root:

```bash
/opt/kkrepo/runtime/deploy-config/bootstrap-dual-runtime.sh
```

The bootstrap is fail-closed: it starts and checks both runtimes before installing and reloading the
50/50 Nginx configuration. It saves the previous Nginx configuration under
`/opt/kkrepo/runtime/deploy-config/backups/` and restores it if validation or reload fails.

## Verification

```bash
KKREPO_DEPLOY_ROOT=/opt/kkrepo/runtime \
  KKREPO_RUNTIME=jvm \
  /opt/kkrepo/bin/kkrepo-runtime-deploy.sh status

KKREPO_DEPLOY_ROOT=/opt/kkrepo/runtime/native \
  KKREPO_ENV_FILE=/opt/kkrepo/runtime/config/kkrepo.env \
  KKREPO_RUNTIME=native \
  KKREPO_HEALTH_URL=http://127.0.0.1:8091/actuator/health/readiness \
  /opt/kkrepo/bin/kkrepo-runtime-deploy.sh status

systemctl is-enabled kkrepo.service kkrepo-native.service
curl -fsS http://127.0.0.1:8081/actuator/health/readiness
curl -fsS http://127.0.0.1:8091/actuator/health/readiness
docker exec kkrepo-nginx nginx -T | grep -E '127[.]0[.]0[.]1:(8080|8090)'
```

Access logs include `upstream`, `upstream_status`, request time, and upstream response time so JVM
and Native failures can be compared without exposing an instance-identifying response header.

This same-host pair detects runtime-specific and cross-replica problems, but it is not host-level
high availability. Moving either replica to another host requires OSS/S3 or a strong-consistency
shared filesystem instead of the current local File blob path.
