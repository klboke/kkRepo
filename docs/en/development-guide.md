# Development Guide

This document is for local development, testing, and debugging. Project overview and user-facing introduction are in the root `README.md`; the database model is documented in [Database Schema](database-schema.md) and [MySQL ER Design](mysql-er.md).

## Prerequisites

- JDK 25
- Maven 3.9 or a compatible version
- MySQL 8.0 or PostgreSQL 12+
- Optional: Docker, for building images or starting local dependencies

The service requires MySQL or PostgreSQL and blob storage at runtime. During development, you can use the local File blob store. Use S3-compatible object storage such as MinIO, RustFS, Aliyun OSS, or AWS S3 only when validating object storage behavior.

Short-TTL performance cache uses in-process memory by default. HTTP sessions, authentication tickets, catalog broadcast, and cross-replica cache tokens use the shared database.

## Local Dependencies

Local runtime requires either MySQL or PostgreSQL. MySQL remains the default Compose service; the `postgresql` profile starts the alternative backend. The blob store can use File blob store. If you need to validate S3-compatible object storage behavior, use these local defaults:

| Dependency | Default |
| --- | --- |
| MySQL address | `127.0.0.1:13306` |
| MySQL database | `kkrepo` |
| MySQL username/password | `kkrepo` / `kkrepo` |
| File blob store base directory | `blobs` |
| S3 endpoint | `http://127.0.0.1:9000` |
| S3 console | `http://127.0.0.1:9001` |
| S3 access key / secret key | `minioadmin` / `minioadmin` |
| Development bucket | `kkrepo` |

### One-Command Docker Dependencies

If your machine does not already have MySQL or S3-compatible object storage available, use the Docker Compose file in this repository to start the local development dependencies:

```bash
docker compose -f docker-compose.dev.yml up -d
docker compose -f docker-compose.dev.yml ps
```

To develop against PostgreSQL instead of the default MySQL service:

```bash
docker compose -f docker-compose.dev.yml --profile postgresql up -d postgresql rustfs
export KKREPO_DATABASE_TYPE=postgresql
export SPRING_DATASOURCE_URL='jdbc:postgresql://127.0.0.1:15432/kkrepo'
```

This starts:

| Service | Image | Local address | Description |
| --- | --- | --- | --- |
| MySQL | `mysql:8.0` | `127.0.0.1:13306` | Creates the `kkrepo` database and `kkrepo` user automatically |
| PostgreSQL | `postgres:16` (default development image; runtime minimum is 12) | `127.0.0.1:15432` | Optional `postgresql` profile; creates the same database and user |
| RustFS | `rustfs/rustfs:latest` | S3 API: `http://127.0.0.1:9000`; Console: `http://127.0.0.1:9001` | S3-compatible object storage for validating OSS/S3 blob store behavior |

Local data directories:

- MySQL: `.local/mysql`
- RustFS: `.local/rustfs/data`

The official RustFS image runs as a non-root user, so mounted host directories must be writable by UID `10001`. The `rustfs-perms` init service in `docker-compose.dev.yml` fixes the directory ownership before RustFS starts.

When you use an S3/OSS blob store for the first time, create the development bucket `kkrepo` in the RustFS console or with an S3 client.

Stop the dependencies:

```bash
docker compose -f docker-compose.dev.yml down
```

This keeps the data under `.local/mysql` and `.local/rustfs/data`. To reset local dependency data, stop the containers and then delete the corresponding directories manually.

## Local Development Flow

Helper scripts are under `scripts/`. They use the Spring `dev` profile. This profile:

- Binds the service to `KKREPO_PORT` when set; otherwise it uses port `18090`
- Uses management port `18091`; `/actuator/health`, `/actuator/metrics`, and `/actuator/prometheus` are exposed from that port
- Serves static UI resources directly from each module's `src/main/resources/META-INF/resources/`, so HTML/CSS/JS changes are visible after refreshing the browser
- Enables `spring-boot-devtools` for Java incremental reload
- Enables SQL DEBUG logging

| Script | Purpose |
| --- | --- |
| `scripts/dev.sh` | Incrementally compiles and starts the service in the background. PID is written to `logs/server.pid`, output to `logs/server.log`. It refuses to start again if the service or port is already alive |
| `scripts/stop.sh` | Stops the recorded process, child JVMs, leftover `spring-boot:run` processes in this repository, and processes still listening on development ports |
| `scripts/restart.sh` | Runs `stop.sh` first, then `dev.sh` |
| `scripts/logs.sh` | `tail -F logs/server.log` |
| `scripts/recompile.sh` | Run this in a second terminal after Java changes. Pass `-l`/`--loop` to enter an interactive recompile loop |

Common development loop:

```bash
./scripts/dev.sh
KKREPO_PORT=48092 ./scripts/dev.sh
./scripts/logs.sh
curl -sS http://127.0.0.1:18091/actuator/health
./scripts/recompile.sh
./scripts/restart.sh
./scripts/stop.sh
```

After startup, open:

- Admin console: `http://127.0.0.1:18090/admin/`
- User browser: `http://127.0.0.1:18090/browse/`
- Health check: `http://127.0.0.1:18091/actuator/health`

LiveReload is enabled on port `35729`. Installing a LiveReload browser extension can avoid manual refreshes.

## Hot Reload Scope

| Change | Operation | How it takes effect |
| --- | --- | --- |
| HTML / CSS / JS / static resources | Refresh browser | Read directly from source resource directories |
| Java method body | `scripts/recompile.sh` or IDE auto build | devtools restarts the Spring context |
| New Spring bean, schema/SQL, `application.properties` | `scripts/restart.sh` | Full restart |
| `pom.xml` | `scripts/restart.sh` | Maven re-resolves dependencies and fully restarts |

## Build And Test

Full build:

```bash
mvn -DskipTests package
```

During development, it is more common to compile or test `server` and its dependent modules only:

```bash
mvn -pl server -am compile
mvn -pl server -am test
```

When running a single `server` test, include `-am` and add `-Dsurefire.failIfNoSpecifiedTests=false` so upstream modules with no matching test class do not fail the reactor early:

```bash
mvn -pl server -am \
  -Dtest=RepositorySecurityFilterTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  test
```

Package a Spring Boot executable jar:

```bash
mvn -pl server -am -DskipTests package spring-boot:repackage
java -jar server/target/kkrepo-server-*.jar
```

Note: a normal `server` module jar does not contain a Spring Boot executable entrypoint. Before copying or deploying `server/target/kkrepo-server-*.jar`, run `spring-boot:repackage`.

Docker images, archive artifacts, and production deployment are documented in the [Build And Deployment Guide](build-deployment-guide.md).

## Compatibility Testing

Protocol features must align with official protocol behavior and Nexus behavior first. When adding or changing protocol behavior, add or update black-box compatibility tests under `compat-test` against a Nexus reference instance before implementing the minimal compatible behavior in kkrepo.

Default compatibility tests:

```bash
mvn -pl compat-test -am test
```

Live black-box tests are skipped by default and require explicit Nexus reference and kkrepo URLs. Commands are documented in [compat-test/README.md](../../compat-test/README.md).

For protocol paths that real package managers exercise directly, run the real client E2E suite after the disposable compatibility environment is prepared:

```bash
scripts/ci/run-live-compat.sh client-e2e
```

This suite publishes/uploads and then downloads/resolves through Maven, npm, PyPI, Helm, Cargo/Rust, Dart/Pub, Composer/PHP, Terraform 0.13/current, SwiftPM/Xcode, NuGet, RubyGems, Yum, and Docker/OCI clients. Composer additionally covers a hosted-to-proxy transitive dependency, rejected Basic credentials, and lock replay after clearing the client cache and detaching the upstream; Terraform covers hosted and registry.terraform.io proxy modules/providers through a group, including provider checksum/signature metadata; Swift 5.7 covers registry/proxy resolve/build, while 5.10/6.x additionally cover HTTPS login, immutable publication, hosted/group flows, SCM replacement, and cross-replica reads; dedicated lanes cover macOS Xcode and Windows proxy resolution. Go is resolve-only through the Go module proxy. Client diagnostics are written under `artifacts/client-e2e/`, with credential-bearing metadata redacted before artifact upload.

On pull requests, `run-client-e2e` validates the default JVM candidate. Use the independent `run-native-client-e2e` label for Spring AOT, runtime-hint, or Native packaging changes; that workflow builds a Native candidate and runs the Linux real-client matrix against both MySQL and PostgreSQL.

Swift production-hardening evidence is split into separate lanes: the scheduled S3-compatible resilience job uses two replicas, PostgreSQL, and MinIO through the AWS S3 adapter for lease takeover, 429/5xx stale behavior, restart, large-package, and destructive backup/restore checks; Alibaba OSS Native remains adapter-contract coverage rather than a live endpoint run. The migration workflow uses Nexus 3.94 H2 to MySQL and PostgreSQL source to MySQL/PostgreSQL target lanes, and verifies fail-closed source profiles and proxy credentials, restart/resume, checksums, and idempotency.

## Development Design Documents

Detailed design and implementation plans for repository formats and migration work are kept under `docs/en/dev/` and `docs/zh/dev/`:

- [Docker Repository Implementation Notes](dev/docker-repository-implementation-plan.md)
- [Cargo / Rust Repository Design Notes](dev/cargo-rust-repository-design.md)
- [Dart / Pub Repository Design Notes](../zh/dev/dart-pub-repository-design.md) (Chinese)
- [Composer / PHP Repository Design Notes](../zh/dev/composer-php-repository-design.md) (Chinese)
- [Terraform Provider / Module Registry Design Notes](../zh/dev/terraform-repository-design.md) (Chinese)
- [Swift Package Registry Design Notes](../zh/dev/swift-package-registry-design.md) (Chinese)
- [Ansible Galaxy Repository Design Notes](../zh/dev/ansible-galaxy-repository-design.md) (Chinese)
- [Nexus Compatibility Migration Refactor Plan](dev/nexus-migration-compatibility-refactor-plan.md)

## Configuration Center

The service integrates Apollo ConfigData, but Apollo is not enabled by default when Apollo meta is not configured, so local startup does not try to access a missing configuration center. When Apollo meta is configured but Apollo is temporarily unavailable, the service still starts with local configuration.

Production or integration environments can specify Apollo meta through runtime parameters:

```bash
KKREPO_APOLLO_META=http://apollo-config:8080 java -jar server/target/kkrepo-server-*.jar
java -Dapollo.meta=http://apollo-config:8080 -jar server/target/kkrepo-server-*.jar
```

## Implementation Constraints

- Design and implementation must assume multi-replica deployment by default. State, cache, locks, background tasks, sessions, upload/delete, index rebuild, metadata, negative cache, and permission decisions must not rely only on a single JVM's in-process state.
- In-process cache can only be node-local hot cache. It must be rebuildable, have TTL or clear invalidation conditions, and use the shared database, OSS/S3, shared TTL cache, marker queues, or other coordination mechanisms as the correctness source.
- Before implementing any repository format, check the official protocol and Nexus reference behavior.
- Do not put protocol logic in controllers. Controllers should delegate to protocol adapters or service layers.
- Large blobs are stored only in OSS/S3/File blob store. The selected relational database stores metadata, state, indexes, and references only.
