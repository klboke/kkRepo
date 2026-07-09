# Troubleshooting Guide

This guide covers common setup, runtime, migration, and compatibility problems. Redact secrets, tokens, passwords, private repository names, and private package contents before sharing logs publicly.

## Quickstart Fails Before Starting

Run the quickstart in a separate local directory:

```bash
curl -fsSLO https://raw.githubusercontent.com/klboke/kkrepo/main/scripts/quickstart.sh
bash quickstart.sh
```

Check these basics:

- Docker is installed and running: `docker info`.
- Docker Compose is available: `docker compose version`.
- `curl` is installed.
- Ports `19090` and `19091` are free.

If ports are in use, override them:

```bash
KKREPO_HTTP_PORT=19190 KKREPO_MANAGEMENT_PORT=19191 bash quickstart.sh
```

Inspect quickstart state:

```bash
cd kkrepo-quickstart
docker compose -f docker-compose.quickstart.yml ps
docker compose -f docker-compose.quickstart.yml logs -f kkrepo
```

Stop the trial without deleting data:

```bash
docker compose -f docker-compose.quickstart.yml down
```

Remove all trial data:

```bash
docker compose -f docker-compose.quickstart.yml down -v
```

## Service Does Not Become Healthy

Check the management endpoint:

```bash
curl -i http://127.0.0.1:19091/actuator/health
```

Common causes:

- MySQL is not healthy or credentials are wrong.
- Flyway migration failed.
- The application cannot write to the configured file blob directory.
- Encryption secrets are missing or too short for a production-like configuration.
- The wrong jar was copied into the container or VM. Build with `spring-boot:repackage` before deployment.

Useful logs:

```bash
docker compose -f docker-compose.quickstart.yml logs --tail 200 mysql
docker compose -f docker-compose.quickstart.yml logs --tail 200 kkrepo
```

For source runs:

```bash
mvn -pl server -am -DskipTests package spring-boot:repackage
java -jar server/target/kkrepo-server-*.jar
```

## Cannot Open `/admin/` Or `/browse/`

Confirm that you are using the application port, not the management port:

- Application: `http://127.0.0.1:19090/admin/`
- Browse UI: `http://127.0.0.1:19090/browse/`
- Health: `http://127.0.0.1:19091/actuator/health`

If using a reverse proxy, verify:

- The proxy forwards the full path.
- Large upload body size is allowed.
- Read and write timeouts are long enough for artifact uploads.
- The proxy does not strip authentication headers required by Maven/npm/pip/Helm/Cargo/Pub/NuGet/gem/yum clients.

## Initial Admin Setup Problems

On the first visit, create the initial `Local/admin` administrator password in the UI.

If the setup page does not appear:

- Verify the database is the expected database.
- Confirm whether an admin user already exists in this environment.
- Check server logs for security initialization errors.

Do not reuse quickstart secrets or trial passwords in production.

## Blob Store Problems

After the first login, create a blob store named `default` unless your repository definitions use another blob store name.

Common symptoms:

- Upload fails because the repository references a missing blob store.
- File blob store cannot write because the directory owner or permissions are wrong.
- S3/OSS blob store health check fails because endpoint, region, bucket, access key, secret key, or path-style access settings are wrong.

For production:

- Prefer OSS/S3-compatible storage.
- Keep bucket lifecycle, versioning, backup, and retention policies aligned with your recovery requirements.
- Do not change encryption secrets casually after credentials or API key payloads have been written.

## MySQL Problems

The service requires MySQL. Core metadata, identities, permissions, sessions, audit logs, migration state, and cross-replica coordination state live in MySQL.

Check connectivity:

```bash
mysql -h127.0.0.1 -P13306 -ukkrepo -pkkrepo kkrepo
```

For source local startup, override the datasource:

```bash
export SPRING_DATASOURCE_URL='jdbc:mysql://127.0.0.1:3306/kkrepo?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai'
export SPRING_DATASOURCE_USERNAME=kkrepo
export SPRING_DATASOURCE_PASSWORD=kkrepo
```

Common causes:

- MySQL port differs from the local default.
- User lacks privileges on the `kkrepo` database.
- The database character set is not `utf8mb4`.
- MySQL timezone or SSL parameters need adjustment for your environment.

## Client Receives 401 Or 403

Check:

- The repository is online.
- Anonymous access is configured as expected.
- The user has repository permissions for browse/read/add/edit/delete as needed.
- The client is using the right credential type for the protocol.
- Reverse proxies preserve the `Authorization` header.

For npm, Cargo, Pub, NuGet, RubyGems, and other token-based clients, regenerate the relevant token or API key after changing user or realm settings.

If the issue is a Nexus compatibility difference, include the same request against Nexus and kkrepo when opening an issue.

## Upload Fails

Check:

- The target repository is `hosted`, not `proxy` or `group`.
- The repository write policy allows the operation.
- The blob store exists and is writable.
- Reverse proxy body-size and timeout limits are high enough.
- The client is publishing to `/repository/<repo>/...`.
- The user has the required repository add/edit permission.

For duplicate upload failures, verify the hosted repository write policy. Some repositories intentionally reject redeploys.

## Migration Problems

Recommended order:

1. Run `Run preflight` on the `Nexus Metadata` page.
2. Resolve blocking issues.
3. Run metadata migration.
4. Run `Sync metadata` on the `Nexus Repository Data` page.
5. Run `Sync packages`.

Common causes:

- Source Nexus Script REST API is disabled.
- Source credentials lack sufficient permissions.
- Source Nexus cannot expose local user password hashes; those users must reset passwords.
- Proxy repositories were expected but not listed in `Optional proxy repositories`.
- Cargo / Rust migration was blocked because preflight did not prove a supported datastore Cargo content model; review the Source Profile and plan item status.
- Blob migration is slow because concurrency is too low, source Nexus is overloaded, or object storage is throttling.

See [Nexus Migration Guide](nexus-migration-guide.md).

## Compatibility Test Problems

Default tests do not require a live Nexus instance:

```bash
mvn -pl compat-test -am test
```

Live tests need both a Nexus reference and kkrepo:

```bash
scripts/build-docker-image.sh kkrepo:compat
docker compose -f docker-compose.compat.yml up -d mysql nexus kkrepo
scripts/ci/live-compat-setup.sh
scripts/ci/run-live-compat.sh smoke
docker compose -f docker-compose.compat.yml down -v
```

Nexus compatibility for newer repository formats uses the datastore-era Nexus PostgreSQL reference compose file and the `nexus` suite. See [compat-test README](../../compat-test/README.md) before running live read/write checks.

For real package client coverage, run the client E2E suite against the same disposable kkrepo candidate:

```bash
scripts/ci/run-live-compat.sh client-e2e
```

It requires the real package clients and SDKs listed in [compat-test README](../../compat-test/README.md). Logs, downloaded metadata, and inspect outputs are stored under `artifacts/client-e2e/`.

If live checks fail:

- Confirm the selected suite.
- Check Docker Compose service health.
- Inspect `nexus` and `kkrepo` logs.
- For `client-e2e`, inspect the matching `artifacts/client-e2e/*.log` file first; it contains the exact sanitized client command and output.
- Verify the configured base URLs and credentials.
- Make sure write tests are intentionally enabled before running write suites.

## Logs To Attach To Issues

Helpful information:

- kkrepo version or commit.
- Deployment mode and replica count.
- Repository format and repository type.
- Client command or HTTP request.
- Sanitized response status, headers, and body snippet.
- Sanitized application logs around the failure.

Do not post:

- Passwords, tokens, API keys, or cookies.
- Private package contents.
- Private hostnames if they reveal sensitive topology.
- Full migration dumps containing user or credential data.

## When To Report Privately

Report privately through [SECURITY.md](../../SECURITY.md) if the issue could cause:

- Authentication bypass.
- Authorization bypass.
- Credential, token, or cookie exposure.
- Repository content disclosure.
- Privilege escalation.
- Remote code execution.
- Migration data leakage from the source Nexus.
