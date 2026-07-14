# Database Backends

kkrepo supports MySQL 8 and PostgreSQL 12+ as interchangeable shared-state backends. MySQL remains the default for existing installations. The same executable jar and container image contain both drivers; select one backend explicitly before first startup. PostgreSQL 12 is the SQL-compatibility floor, while production deployments should use a PostgreSQL release that is still maintained by the PostgreSQL project or the managed-service vendor.

## Select A Backend

Set the database type, JDBC URL, and credentials together:

```bash
# MySQL (default)
export KKREPO_DATABASE_TYPE=mysql
export SPRING_DATASOURCE_URL='jdbc:mysql://db:3306/kkrepo?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=UTC'
export SPRING_DATASOURCE_USERNAME=kkrepo
export SPRING_DATASOURCE_PASSWORD='<password>'
```

```bash
# PostgreSQL
export KKREPO_DATABASE_TYPE=postgresql
export SPRING_DATASOURCE_URL='jdbc:postgresql://db:5432/kkrepo'
export SPRING_DATASOURCE_USERNAME=kkrepo
export SPRING_DATASOURCE_PASSWORD='<password>'
```

`kkrepo.database.type` is the Spring property behind `KKREPO_DATABASE_TYPE`. Startup validates that the declared type matches JDBC metadata before Flyway runs, so an accidental MySQL/PostgreSQL mismatch fails without mutating the database.

Do not change a running installation from one database engine to the other by changing the URL. Provision a new database, migrate the data with a tested database migration process, validate counts/checksums and application behavior, then cut over.

## Quickstart

MySQL is the default:

```bash
curl -fsSL https://raw.githubusercontent.com/klboke/kkrepo/main/scripts/quickstart.sh | bash
```

Select PostgreSQL with the same script:

```bash
curl -fsSL https://raw.githubusercontent.com/klboke/kkrepo/main/scripts/quickstart.sh \
  | KKREPO_DATABASE_TYPE=postgresql bash
```

The script selects `docker-compose.quickstart.yml` for MySQL or `docker-compose.quickstart-postgresql.yml` for PostgreSQL. The PostgreSQL quickstart currently defaults to the `postgres:16` image; that default is not the minimum supported runtime version. Both expose the same application, management, Admin UI, Browse UI, and repository URLs.

For repository development, the PostgreSQL profile can be started with:

```bash
docker compose -f docker-compose.dev.yml --profile postgresql up -d postgresql
```

## Schema Migrations

Flyway locations are backend-specific:

- MySQL: `classpath:db/migration/mysql`
- PostgreSQL: `classpath:db/migration/postgresql`

The MySQL V1-V29 history is immutable and retains its existing checksums. PostgreSQL starts with an equivalent V29 baseline. Starting with V30, every schema change must use the same version and description in both directories and preserve the same logical result.

CI verifies MySQL V1-V29 hashes, repeat startup/validation, PostgreSQL 12 minimum-version fresh/repeat startup, PostgreSQL 16 end-to-end compatibility, and migration version parity. A migration must be safe for every application replica because multiple replicas can start against the same database concurrently.

## Multi-Replica Semantics

The selected relational database is the shared correctness boundary for:

- repository, component, asset, identity, permission, token, and audit metadata;
- Spring Session and short-lived authentication tickets;
- migration jobs, checkpoints, claims, retries, and maintenance cursors;
- cache version watermarks and background-work markers.

In-process TTL caches are rebuildable hot caches only. A replica restart or local-cache loss may add database reads but must not change correctness. All replicas in one deployment must use the same database engine, schema, blob stores, and encryption secrets.

## Deployment

The packaged jar and container image need no backend-specific rebuild. Set the three database variables above in a VM, Compose, Kubernetes, or Helm deployment.

The Helm chart under `deploy/helm/kkrepo` requires an external database secret. Its values validate the backend type and support either direct credentials or existing Kubernetes Secrets. Use at least two application replicas in production, rolling updates, and OSS/S3 blob storage.

## Backup And Restore

Back up the relational database and blob store as one recovery set. Prefer a database snapshot boundary followed by versioned/object-storage backup, or pause writes while capturing both. Keep the two application encryption secrets with the recovery material.

- MySQL: use a consistent `mysqldump` or managed snapshot and restore into MySQL 8.
- PostgreSQL: use `pg_dump`/`pg_restore` or a managed snapshot and restore into a compatible PostgreSQL release.

After restore, point one kkrepo replica at the recovered database first, let Flyway validate, check `/actuator/health`, sample assets and permissions, then scale out.

## Troubleshooting

- **Declared type does not match the JDBC database:** fix `KKREPO_DATABASE_TYPE` or the URL. Do not bypass validation.
- **Flyway validation fails:** restore the original migration file; never edit an applied migration. Add a new version instead.
- **JSON query or binding errors:** confirm the configured database type and driver match the server. PostgreSQL uses `jsonb`; MySQL uses `JSON`.
- **Timezone differences:** run application nodes in UTC and use an explicit JDBC/server timezone. Public instant-based timestamps are absolute instants; tests cover UTC, Asia/Shanghai, and daylight-saving transitions. Legacy API-key and security-audit `LocalDateTime` fields intentionally use wall-clock timestamps without a time zone to preserve MySQL behavior.
- **Only one replica sees a change:** verify every replica shares the same database and cache version table, then inspect database connectivity and polling metrics.

See [Database Schema](database-schema.md), [Production Hardening](production-hardening.md), and [Backup And Restore](backup-restore.md).
