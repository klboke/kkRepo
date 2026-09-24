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

## AWS RDS / Aurora IAM Authentication

Set `kkrepo.database.auth=iam` (`KKREPO_DATABASE_AUTH=iam`) to use IAM authentication
for an RDS/Aurora MySQL or PostgreSQL backend. The default is `password`.
The server uses the existing HikariCP credential-provider hook and AWS SDK v2
`RdsUtilities`; no AWS JDBC wrapper, alternative JDBC URL scheme, or external jar is needed.

```bash
export KKREPO_DATABASE_AUTH=iam
export KKREPO_DATABASE_IAM_REGION=us-east-1
export SPRING_DATASOURCE_USERNAME=kkrepo
unset SPRING_DATASOURCE_PASSWORD

# PostgreSQL / Aurora PostgreSQL; mount the RDS CA bundle at this path.
export KKREPO_DATABASE_TYPE=postgresql
export SPRING_DATASOURCE_URL='jdbc:postgresql://mydb.abcdefghijkl.us-east-1.rds.amazonaws.com:5432/kkrepo?sslmode=verify-full&sslrootcert=/etc/rds/global-bundle.pem'

# MySQL / Aurora MySQL alternative; import the RDS CA into the runtime's truststore.
# Connector/J also accepts trustCertificateKeyStoreUrl and trustCertificateKeyStorePassword
# in this URL when using a separately mounted truststore.
# export KKREPO_DATABASE_TYPE=mysql
# export SPRING_DATASOURCE_URL='jdbc:mysql://mydb.abcdefghijkl.us-east-1.rds.amazonaws.com:3306/kkrepo?sslMode=VERIFY_IDENTITY&serverTimezone=UTC'
```

`kkrepo.database.iam.region` is optional; when empty, the AWS SDK region provider chain
is used (for example, `AWS_REGION`). Use the database's region. AWS credentials come
from the SDK default credential chain, including EC2 instance roles, ECS task roles,
EKS Pod Identity and web identity/IRSA. Supply a workload role with `rds-db:connect`;
database IAM authentication does not use the S3 blob-store access-key settings.
Do not configure long-lived AWS keys for a workload that already has a role.

Enable IAM database authentication on the RDS instance or Aurora cluster, create the
database account, and grant it kkRepo's normal schema/migration privileges. For MySQL,
use `AWSAuthenticationPlugin`; for PostgreSQL, grant `rds_iam`. The role policy must
authorize the exact, case-sensitive database username, for example:

```json
{
  "Version": "2012-10-17",
  "Statement": [{
    "Effect": "Allow",
    "Action": "rds-db:connect",
    "Resource": "arn:aws:rds-db:us-east-1:123456789012:dbuser:db-RESOURCE_ID/kkrepo"
  }]
}
```

Use the DB **resource ID**, not the instance name; Aurora uses the cluster resource ID
(`cluster-...`). See AWS's [IAM database authentication guide](https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/UsingWithRDS.IAMDBAuth.html),
[database account setup](https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/UsingWithRDS.IAMDBAuth.DBAccounts.html),
[policy examples](https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/UsingWithRDS.IAMDBAuth.IAMPolicy.html),
and [SDK credential chain](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/credentials-chain.html).

Connection and lifecycle behavior:

- A fresh token is signed for each new **physical** connection, including pool replacement
  after eviction or failure. Borrowing an existing pooled connection does not sign a token.
  Tokens expire after 15 minutes for authentication; existing sessions are unaffected, so
  Hikari `max-lifetime` does not need to be shortened just to match token expiry.
- The AWS credential provider refreshes temporary workload credentials. Each replica signs
  independently; kkRepo stores no IAM token in MySQL/PostgreSQL, S3, a local cache, or a
  background refresh job. Credential/signing failures fail the new connection without
  falling back to `spring.datasource.password`.
- The application's Hikari pool also serves Flyway, JDBC sessions and Quartz. Leave
  `spring.flyway.url`, `spring.flyway.user`, and `spring.flyway.password` unset in IAM mode.
  This option applies to the server; standalone migration-tool connection settings remain
  separate.
- Use a single actual RDS/Aurora endpoint in the ordinary JDBC URL. Custom DNS aliases,
  multi-host/load-balancing URLs and embedded URL credentials are not supported by this
  integration. Host and port for signing come from the JDBC URL; defaults are 3306/5432.
- Verified TLS is required: MySQL `sslMode=VERIFY_IDENTITY`, PostgreSQL
  `sslmode=verify-full`, with the RDS CA trusted by the driver. Keep JDBC `autoReconnect`
  and `autoReconnectForPools` disabled so Hikari can obtain a fresh token for replacement
  connections. Avoid duplicate URL/driver options and credential/endpoint overrides.
- IAM mode rejects custom socket factories, PostgreSQL `sslfactory`/`sslhostnameverifier`
  and `service`, MySQL `useConfigs`/`propertiesTransform`, and enabled MySQL `dnsSrv`.
  These options can override the validated TLS or connection settings. Configure CA trust
  with PostgreSQL `sslrootcert` or MySQL's truststore options instead.
- The same runtime switch is available in JVM and Native packages. CI checks signing,
  credential rotation, Hikari replacement and a packaged-runtime PostgreSQL TLS wire probe
  with synthetic AWS credentials. These tests do not validate an actual AWS role policy or
  RDS account; verify those in your AWS environment before production cutover.

For Helm, set `database.auth: iam` and optionally `database.iam.region`. The chart then
omits the database password Secret reference. See the [Helm IAM example](../../deploy/helm/kkrepo/README.md#aws-rds--aurora-iam-authentication)
for IRSA and CA mounting. The application's encryption Secret is still required.

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

The Helm chart under `deploy/helm/kkrepo` uses an external database password Secret in `password` mode; `iam` mode omits that Secret. Its values validate the backend and authentication types. Use at least two application replicas in production, rolling updates, and OSS/S3 blob storage.

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
