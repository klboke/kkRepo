# kkrepo compat-test

`compat-test` is part of the three main CI validation blocks:

- Nexus compatibility compares kkrepo with a disposable Nexus reference instance.
- Client E2E compatibility runs real package clients against a disposable kkrepo candidate.
- Migration E2E imports from supported Nexus source versions and validates migrated behavior.

It also contains unit-level and diagnostic compatibility checks that run during normal `mvn test`
or when explicitly enabled.

## Reference Nexus Endpoint

Use the Docker-resident Nexus service as the fixed reference endpoint:

```bash
NEXUS_COMPAT_BASE_URL=http://localhost:28090/
NEXUS_COMPAT_USERNAME=admin
NEXUS_COMPAT_PASSWORD=Admin1234
```

Current Maven compatibility checks and repository-format compatibility checks can compare against
this same long-running Nexus reference unless a test explicitly documents why it needs an isolated
throwaway Nexus instance. Cargo/Rust requires Nexus 3.77.x+, Terraform requires Nexus 3.90.0+ for
hosted/proxy/group coverage, Pub requires Nexus 3.92.0+, and the Swift reference matrix targets
Nexus 3.94.x so versioned-manifest fallback and `v`/`V` tag normalization include the latest fixes; the
datastore-era PostgreSQL compose file below defaults to Nexus 3.92.0 for the general newer-format
checks, while the Swift workflow explicitly overrides that image with Nexus 3.94.x.

## Default Test Run

```bash
mvn -pl compat-test -am test
```

The live black-box tests are skipped by default so the module stays deterministic in CI and local
builds without Nexus.

## Disposable Live Compatibility Environment

The GitHub `Live Compatibility` workflow uses the same commands below. It builds a candidate
kkrepo image, starts MySQL, a disposable Nexus reference, and the candidate service, then
bootstraps the admin user, default file blob store, and the fixture repositories used by
live compatibility and real client E2E runs.
The disposable defaults are `admin` / `Admin1234` for Nexus and `admin` / `12345678` for kkrepo.
The compose files set `KKREPO_NEXUS_LEGACY_UI_ENABLED=true` before the candidate service starts
because selected Nexus compatibility checks still compare legacy web UI routes. Normal kkrepo
deployments should leave this coarse-grained startup flag disabled. If you run a candidate service
outside these compose files, set the environment variable before starting or restarting that service;
setting it only before the Maven test command does not affect an already-running process.

```bash
scripts/build-docker-image.sh kkrepo:compat
docker compose -f docker-compose.compat.yml up -d mysql nexus kkrepo
scripts/ci/live-compat-setup.sh
scripts/ci/run-live-compat.sh smoke
docker compose -f docker-compose.compat.yml down -v
```

For datastore-era compatibility work, use the Nexus PostgreSQL compose file instead of the default
Nexus 3.29.2 OrientDB reference. It pins Nexus to 3.92.0 with PostgreSQL datastore enabled, which
covers Cargo/Rust, Dart/Pub, Terraform, and the other newer-format live checks:

```bash
scripts/build-docker-image.sh kkrepo:compat
export COMPOSE_FILE="$PWD/docker-compose.compat-postgres.yml"
export COMPOSE_PROJECT_NAME=kkrepo-postgres-compat
export NEXUS_COMPAT_PORT=38090
export KKREPO_COMPAT_PORT=18092
export KKREPO_MANAGEMENT_PORT=18093
docker compose -f "$COMPOSE_FILE" up -d --wait mysql nexus-postgres
docker compose -f "$COMPOSE_FILE" up -d nexus kkrepo
scripts/ci/live-compat-setup.sh
scripts/ci/run-live-compat.sh nexus
docker compose -f "$COMPOSE_FILE" down -v
```

Available suites:

- `smoke`: diagnostic console API checks plus Maven proxy GET/HEAD/checksum read compatibility.
- `write-smoke`: Maven hosted release/snapshot write compatibility with `COMPAT_WRITE_ENABLED=true`.
- `nexus`: the disposable Nexus reference matrix. It enables write checks and compares kkrepo with
  Nexus across Maven, npm, PyPI, Cargo/Rust, Dart/Pub, Composer/PHP, Terraform, Raw, selected NuGet/RubyGems/Yum behavior,
  Go proxy endpoints, Helm hosted round trips, component upload specs, and selected security/admin
  contracts. Composer is required when enabled; a missing Nexus Composer endpoint fails instead of skipping.
- `extended`: diagnostic smoke coverage plus currently separated PyPI, Helm, Pub, NuGet, RubyGems, and Yum checks.
- `client-e2e`: starts from the disposable kkrepo service and uses real package clients to publish
  and then download/resolve through hosted and group/proxy repositories. It covers Maven, npm,
  PyPI, Helm, Cargo/Rust, Dart/Pub, Flutter Pub, Composer/PHP, Terraform 0.13/current, NuGet, RubyGems, Yum, and Docker/OCI. Go is
  resolve-only through the Go proxy because hosted Go publishing is not a supported repository mode.
  SwiftPM is included when `swift` or `SWIFT_E2E_BINS` is available; it publishes to Swift hosted,
  resolves and builds through group, checks immutable conflict and checksum replay, and exercises
  GitHub SCM-to-registry replacement through proxy.
  The Composer flow additionally validates a hosted-to-proxy transitive dependency, rejected Basic
  credentials, and lock replay from the server cache after clearing the client cache and detaching
  the Packagist upstream.
- `full`: all compat-test tests with live endpoint variables set; use this as a diagnostic suite
  when working through known protocol gaps.
- `swift`: the opt-in Nexus 3.94.x Swift Registry v1 matrix. It creates isolated hosted, proxy, and
  group fixtures and is skipped unless `SWIFT_COMPAT_ENABLED=true` is set by the wrapper.

In GitHub Actions, add the `run-live-compat` label to a PR to run the unified Nexus compatibility
matrix against the Nexus 3.92.0 PostgreSQL reference. The live compatibility workflow
uses `docker-compose.compat-postgres.yml` because Pub repositories require Nexus 3.92.0+ and the
newer Nexus generation is covered here through a PostgreSQL datastore reference. Add
`run-client-e2e` to run the real client matrix, or start the workflow manually and select a suite.

## Real Client E2E

The `client-e2e` suite validates actual client command behavior rather than only protocol HTTP
responses:

```bash
scripts/build-docker-image.sh kkrepo:compat
docker compose -f docker-compose.compat.yml up -d mysql nexus kkrepo
scripts/ci/live-compat-setup.sh
scripts/ci/run-live-compat.sh client-e2e
docker compose -f docker-compose.compat.yml down -v
```

The runner must have `mvn`, `npm`, `python3` with `build` and `twine`, `go`, `helm`, `cargo`,
`dart`, `composer`, `php`, Terraform 0.13 and a current stable Terraform binary, `dotnet`, `ruby`/`gem`, and Docker available. `flutter` is used for the Flutter Pub check
when installed; GitHub Actions installs it for the `client-e2e` workflow. ORAS is optional; when
present the Docker/OCI part also pushes and pulls a generic OCI artifact. Client logs, downloaded
metadata, and selected inspect outputs are written under `artifacts/client-e2e/`. Terraform URLs can
contain URL tokens, so the runner redacts those values before uploading captured metadata.

### Swift Registry compatibility and client matrix

The HTTP suite covers hosted/proxy/group, GET/HEAD/PUT, release list and metadata aliases,
default/versioned manifests and `303` fallback, archive checksum/ETag/conditional/Range behavior,
identifier normalization, Basic/Bearer/anonymous access, CMS-signed and unsigned archives,
problem JSON, immutable conflicts, 32-way concurrent publish/proxy reads, group source binding, and
optional cross-replica visibility. It does not silently contact a default local endpoint:

```bash
SWIFT_NEXUS_COMPAT_BASE_URL=http://127.0.0.1:39400 \
SWIFT_KKREPO_COMPAT_BASE_URL=http://127.0.0.1:18090 \
SWIFT_NEXUS_COMPAT_USERNAME=admin \
SWIFT_NEXUS_COMPAT_PASSWORD=Admin1234 \
SWIFT_KKREPO_COMPAT_USERNAME=admin \
SWIFT_KKREPO_COMPAT_PASSWORD=12345678 \
SWIFT_COMPAT_PROXY_ENABLED=true \
scripts/ci/run-live-compat.sh swift
```

Set `SWIFT_KKREPO_SECONDARY_BASE_URL` to a second replica sharing the same database and blob store
to enable publish-on-primary/read-on-secondary validation. The GitHub proxy fixture defaults to
`apple/swift-log` `1.6.3`; override `SWIFT_COMPAT_PROXY_SCOPE`, `SWIFT_COMPAT_PROXY_NAME`, and
`SWIFT_COMPAT_PROXY_VERSION` for a controlled tag fixture. To run the Nexus 3.93+ leading-tag
regression, set `SWIFT_COMPAT_PROXY_TAG_CASES` to comma-separated
`scope/name/rawTag/normalizedVersion` entries backed by controlled GitHub repositories; the test
requires at least one lowercase `v` and one uppercase `V` case and otherwise reports an explicit
skip.

The real client flow accepts a comma-separated executable matrix. Labels are recorded with the
actual reported version in `artifacts/client-e2e/swift-client-matrix.tsv`:

```bash
CLIENT_E2E_TESTS=swift \
SWIFT_E2E_BINS='5.7=/opt/swift-5.7/usr/bin/swift,5.9+=/opt/swift-5.10/usr/bin/swift,6.x=/opt/swift-6/usr/bin/swift' \
SWIFT_E2E_REQUIRE_5_7_5_9_6=true \
scripts/ci/run-client-e2e.sh
```

Without `SWIFT_E2E_BINS`, the installed `swift` is run as the `current` entry. If neither is
available, the script emits an explicit skip. `SWIFT_E2E_PROXY_ENABLED=false` explicitly skips only
the live GitHub SCM replacement sub-flow. On macOS, set `SWIFT_XCODE_E2E_PROJECT` (and optionally
`SWIFT_XCODE_E2E_SCHEME`) to run `xcodebuild -resolvePackageDependencies` against a prepared Xcode
registry dependency fixture. Publishing is intentionally not part of the Windows acceptance path.
On Windows/MSYS runners the script automatically skips hosted publish and runs only the documented
proxy `resolve`/`build --replace-scm-with-registry` flow.

SwiftPM registry login was introduced in Swift 5.8 and rejects plain HTTP by design; Swift 5.7 also
does not provide the registry publish subcommand. The client script detects those capabilities:
5.7 runs registry/proxy resolve and build, while 5.9+ and Swift 6 run publish as well. It runs the
real `package-registry login` command when the selected toolchain supports it and
`KKREPO_COMPAT_BASE_URL` is HTTPS. Older clients and local HTTP environments use Sonatype's
documented embedded-credentials registry configuration; the black-box suite still exercises
`POST /login` with valid/invalid Basic and Bearer credentials directly. The HTTPS client path runs
valid Basic login, rejected invalid credentials, and a real `--token` GenericToken login before
publishing. The proxy client fixture follows the selected toolchain: Swift 5.7/5.8 uses
`apple/swift-log` `1.5.4` (tools 5.6), while Swift 5.9+ uses `1.6.3` (tools 5.9). Set
`SWIFT_E2E_PROXY_VERSION` to override that selection explicitly.

The registry specification makes `POST /login` optional and allows `501 Not Implemented` when a
server omits it. kkRepo implements this endpoint, so candidate assertions expect `200` for valid
credentials and `401` for invalid credentials; `501` is a reference-only/N-A branch for kkrepo.

The scheduled S3-compatible resilience lane runs two kkrepo replicas with PostgreSQL and MinIO
through the AWS S3 adapter. It covers a multi-megabyte package, shared 429/5xx waterlines and stale
fallback, expired-lease takeover, restart, and destructive database/object backup-restore. Alibaba
OSS Native is covered by adapter contracts; this suite does not claim a live OSS Native endpoint.

Swift migration is `FULL` only for Nexus 3.92.x-3.94.x sources whose datastore asset shape is
verified. The live Nexus 3.94 matrix covers H2 to MySQL and PostgreSQL source to MySQL/PostgreSQL
targets, restart/resume, exact-row-count idempotency, and masked/missing proxy-secret fail-closed
behavior. An affected target proxy remains offline with no placeholder credential until an
administrator supplies the secret explicitly.

## Live Console And Maven Read Checks

Start kkrepo locally first:

```bash
scripts/restart.sh
```

Then run the live checks against a running Nexus reference and kkrepo:

```bash
mvn -pl compat-test -am \
  -DfailIfNoTests=false \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dcompat.nexus.baseUrl=http://localhost:28090/ \
  -Dcompat.nexusPlus.baseUrl=http://127.0.0.1:18090 \
  -Dcompat.nexus.readRepository=maven-public \
  -Dcompat.nexusPlus.readRepository=maven-public \
  -Dtest=KkRepoConsoleBlackBoxCompatibilityTest,MavenRepositoryBlackBoxCompatibilityTest#proxyReadRoundTripMatchesNexusWhenConfigured \
  test
```

## Live Security Admin Compatibility

The security admin checks compare Nexus `#admin/security` ExtDirect contracts against kkrepo.
They are disabled by default and use the fixed Docker Nexus reference endpoint.

```bash
COMPAT_SECURITY_ENABLED=true \
NEXUS_COMPAT_BASE_URL=http://localhost:28090/ \
NEXUS_COMPAT_USERNAME=admin \
NEXUS_COMPAT_PASSWORD=Admin1234 \
KKREPO_COMPAT_BASE_URL=http://127.0.0.1:18090 \
KKREPO_COMPAT_USERNAME=admin \
KKREPO_COMPAT_PASSWORD=admin123 \
mvn -pl compat-test -am \
  -DfailIfNoTests=false \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtest=SecurityAdminBlackBoxCompatibilityTest \
  test
```

The current checks cover user read shape, Nexus password placeholders, built-in role rows, core
privilege rows, privilege form store APIs, repository reference rows including `*` and `*-maven2`,
supported realm type names, a temporary non-admin repository-view role/user, and a temporary
non-admin repository-content-selector role/user. The non-admin checks prove repository root and REST
browse access for `maven-public`, security-management denial for
`/service/rest/v1/security/users`, kkrepo upload repository filtering when the user lacks
`nexus:component:create`, and content-selector allow/deny status parity for selected Maven asset
paths. NuGet API key UI endpoints are intentionally not covered by this security suite; repository
protocol compatibility for NuGet, RubyGems, and Yum is covered separately from the security-admin
checks. The kkrepo password above is only the local dev admin used for compatibility validation;
migrated environments should use their migrated admin credential.

Realm protocol behavior is covered in the server test suite rather than the live Nexus comparison:
`SecurityAuthenticationServiceTest` starts an in-memory LDAP server for real bind/search/group-role
mapping and a local JWKS HTTP endpoint for signed OIDC bearer JWT validation. Basic local/LDAP
authentication follows enabled realm priority; OIDC bearer/auth-code, API key, and session subjects
use their own token/session entry points instead of the Basic realm order.

## Live NuGet, RubyGems, And Yum Checks

The NuGet, RubyGems, and Yum repository checks compare the fixed Nexus reference endpoint above
with the local kkrepo dev server. The default credentials are `admin` / `Admin1234` for Nexus and
`admin` / `123456` for kkrepo.

```bash
mvn -pl compat-test -am \
  -DfailIfNoTests=false \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dcompat.nexus.baseUrl=http://localhost:28090/ \
  -Dcompat.nexus.username=admin \
  -Dcompat.nexus.password=Admin1234 \
  -Dcompat.nexusPlus.baseUrl=http://127.0.0.1:18090 \
  -Dcompat.nexusPlus.username=admin \
  -Dcompat.nexusPlus.password=123456 \
  -Dtest=NugetRubygemsYumRepositoryBlackBoxCompatibilityTest \
  test
```

Hosted NuGet multipart push and Yum RPM PUT are opt-in because they write packages into the
comparison repositories. To target the Browse repository from local dev, set
`COMPAT_YUM_HOSTED_REPOSITORY=yum-compat-hosted`; the Yum test uploads under
`Packages/kkrepo-compat-yum-<timestamp>/`.

```bash
COMPAT_WRITE_ENABLED=true \
COMPAT_YUM_HOSTED_REPOSITORY=yum-compat-hosted \
mvn -pl compat-test -am \
  -DfailIfNoTests=false \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtest=NugetRubygemsYumRepositoryBlackBoxCompatibilityTest#nugetHostedMultipartPushMatchesNexusWhenWriteEnabled+yumHostedRpmPutMatchesNexusWhenWriteEnabled \
  test
```

Override the RPM fixture with `COMPAT_YUM_FIXTURE_URL` or `-Dcompat.yum.fixtureUrl=...` when the
default EPEL fixture is unavailable.

## Hosted Write Compatibility

For release and snapshot deploy/delete behavior, run against the fixed Docker Nexus reference.
Set the reference credentials through environment variables so the password does not appear in the
Maven command line:

```bash
NEXUS_COMPAT_BASE_URL=http://localhost:28090/ \
NEXUS_COMPAT_USERNAME=admin \
NEXUS_COMPAT_PASSWORD=Admin1234 \
KKREPO_COMPAT_BASE_URL=http://127.0.0.1:18090 \
COMPAT_WRITE_ENABLED=true \
mvn -pl compat-test -am \
  -DfailIfNoTests=false \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtest=MavenRepositoryBlackBoxCompatibilityTest#hostedReleaseDeployRoundTripMatchesNexusWhenConfigured+hostedSnapshotDeployRoundTripMatchesNexusWhenConfigured \
  test
```

## Live npm Hosted, Proxy, And Group Checks

The npm compatibility test class uses the same fixed Nexus reference endpoint as the Maven checks
and is skipped unless both endpoints are supplied. Hosted publish/delete also requires
`COMPAT_WRITE_ENABLED=true`.

```bash
mvn -pl compat-test -am \
  -DfailIfNoTests=false \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dcompat.nexus.baseUrl=http://127.0.0.1:28090 \
  -Dcompat.nexus.username=admin \
  -Dcompat.nexus.password=Admin1234 \
  -Dcompat.nexusPlus.baseUrl=http://127.0.0.1:18090 \
  -Dcompat.write.enabled=true \
  -Dcompat.npm.readPackage=is-number \
  -Dcompat.npm.concurrentPackage=@sentry/core \
  -Dcompat.npm.concurrentRequests=50 \
  -Dcompat.npm.concurrentThreads=16 \
  -Dcompat.npm.concurrentPublishes=24 \
  -Dcompat.npm.concurrentPublishThreads=12 \
  -Dcompat.nexus.npm.hostedRepository=npm-hosted \
  -Dcompat.nexusPlus.npm.hostedRepository=npm-hosted \
  -Dcompat.nexus.npm.proxyRepository=npm-proxy \
  -Dcompat.nexusPlus.npm.proxyRepository=npm-proxy \
  -Dcompat.nexus.npm.groupRepository=npm-group \
  -Dcompat.nexusPlus.npm.groupRepository=npm-group \
  -Dtest=NpmRepositoryBlackBoxCompatibilityTest \
  test
```

## PyPI Compatibility

The PyPI black-box test auto-creates these repositories when they are missing from both endpoints:

- `pypi-hosted`
- `pypi-proxy`
- `pypi-group`

Run the PyPI suite against the fixed reference Nexus:

```bash
NEXUS_COMPAT_BASE_URL=http://localhost:28090/ \
NEXUS_COMPAT_USERNAME=admin \
NEXUS_COMPAT_PASSWORD=Admin1234 \
KKREPO_COMPAT_BASE_URL=http://127.0.0.1:18090 \
mvn -pl compat-test -am \
  -DfailIfNoTests=false \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtest=PypiRepositoryBlackBoxCompatibilityTest \
  test
```

It covers hosted multipart wheel upload, hosted simple index/package reads, group index/package
reads, and proxy `sampleproject` index/package reads. Override repository names with
`COMPAT_PYPI_HOSTED_REPOSITORY`, `COMPAT_PYPI_PROXY_REPOSITORY`, and
`COMPAT_PYPI_GROUP_REPOSITORY`. Set `COMPAT_PYPI_SETUP_ENABLED=false` to require repositories to
already exist.

`compat-test/run-local-write-compat.sh` is retained only for debugging cases that need a fresh,
isolated Nexus data directory:

```bash
compat-test/run-local-write-compat.sh
```

Defaults:

- `NEXUS_HOME=/private/tmp/nexus-3292-source/nexus-base-template-3.29.2-02`
- reference Nexus port: `58083`
- kkrepo URL: `http://127.0.0.1:18090`
- disposable data dir: a new `/private/tmp/kkrepo-compat-nexus.*` directory

Override with:

```bash
NEXUS_COMPAT_PORT=58084 \
KKREPO_COMPAT_BASE_URL=http://127.0.0.1:18090 \
compat-test/run-local-write-compat.sh
```

## Performance Smoke Checks

These are not load tests. They only catch obvious latency regressions on warmed single-request
paths.

```bash
COMPAT_PERF_ENABLED=true \
KKREPO_COMPAT_BASE_URL=http://127.0.0.1:18090 \
NEXUS_COMPAT_BASE_URL=http://localhost:28090/ \
mvn -pl compat-test -am \
  -DfailIfNoTests=false \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtest=MavenPerformanceSmokeCompatibilityTest \
  test
```

Useful thresholds:

- `COMPAT_PERF_MAVEN_MAX_MILLIS`, default `1000`
- `COMPAT_PERF_SEARCH_MAX_MILLIS`, default `500`
- `COMPAT_PERF_MAX_NEXUS_RATIO`, default `5.0`
- `COMPAT_PERF_ABSOLUTE_SLACK_MILLIS`, default `150`
- `COMPAT_PERF_WARMUPS`, default `2`
- `COMPAT_PERF_SAMPLES`, default `8`

## Go Proxy And Group Compatibility

The Go black-box test compares kkrepo against Nexus Go proxy and group repositories. By default
it targets the local Nexus at `http://localhost:28090` with `admin` / `Admin1234`, and kkrepo at
`http://127.0.0.1:18090`. Override `GO_KKREPO_COMPAT_BASE_URL` when testing a non-default
kkrepo port.

Because the test creates or updates kkrepo repositories during setup, provide
`GO_KKREPO_COMPAT_USERNAME` and `GO_KKREPO_COMPAT_PASSWORD` when repository management
security is enabled. Without those credentials the setup-oriented Go test is skipped.

```bash
GO_KKREPO_COMPAT_BASE_URL=http://127.0.0.1:48092 \
GO_KKREPO_COMPAT_USERNAME=admin \
GO_KKREPO_COMPAT_PASSWORD=... \
mvn -pl compat-test -am \
  -DfailIfNoTests=false \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtest=GoProxyBlackBoxCompatibilityTest \
  test
```

The test creates/updates `go-proxy-compat`, `go-group-compat-miss`,
`go-group-compat-hit`, and `go-group-compat` on both sides. Override with
`GO_NEXUS_COMPAT_BASE_URL`, `GO_KKREPO_COMPAT_BASE_URL`,
`GO_NEXUS_COMPAT_PASSWORD`, `GO_GROUP_COMPAT_REPOSITORY`, and related
`GO_GROUP_COMPAT_*` settings when needed.

## Helm Hosted And Proxy Compatibility

The Helm black-box test auto-creates `helm-hosted` and `helm-proxy` on both endpoints when missing.
It covers hosted multipart chart push, hosted `index.yaml` and chart reads, hosted `HEAD`, hosted
delete cleanup, plus proxy `index.yaml` URL rewriting and proxied chart download.

```bash
NEXUS_COMPAT_BASE_URL=http://localhost:28090/ \
NEXUS_COMPAT_USERNAME=admin \
NEXUS_COMPAT_PASSWORD=Admin1234 \
KKREPO_COMPAT_BASE_URL=http://127.0.0.1:18090 \
mvn -pl compat-test -am \
  -DfailIfNoTests=false \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtest=HelmRepositoryBlackBoxCompatibilityTest \
  test
```

Override repository names with `COMPAT_HELM_HOSTED_REPOSITORY` and
`COMPAT_HELM_PROXY_REPOSITORY`. Override the proxy upstream with `COMPAT_HELM_REMOTE_URL`; default
is `https://charts.bitnami.com/bitnami`.

## Raw Hosted, Proxy, And Group Compatibility

The raw black-box test auto-creates `raw-hosted`, `raw-proxy`, and `raw-group` on both endpoints.
It covers hosted `PUT`/`GET`/`HEAD`/`DELETE`, raw directory `index.html` forwarding, group
first-match reads, and proxy file download/`HEAD` against a static upstream.

```bash
NEXUS_COMPAT_BASE_URL=http://localhost:28090/ \
NEXUS_COMPAT_USERNAME=admin \
NEXUS_COMPAT_PASSWORD=Admin1234 \
KKREPO_COMPAT_BASE_URL=http://127.0.0.1:18090 \
mvn -pl compat-test -am \
  -DfailIfNoTests=false \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtest=RawRepositoryBlackBoxCompatibilityTest \
  test
```

Override repository names with `COMPAT_RAW_HOSTED_REPOSITORY`,
`COMPAT_RAW_PROXY_REPOSITORY`, and `COMPAT_RAW_GROUP_REPOSITORY`. Override the proxy upstream
with `COMPAT_RAW_REMOTE_URL` and `COMPAT_RAW_PROXY_PROBE_PATH`; defaults are
`https://raw.githubusercontent.com/github/gitignore/main` and `Java.gitignore`.

## Docker Registry V2 Compatibility

The Docker Registry V2 checks are disabled by default. They compare a Nexus Docker
connector with a kkrepo Docker connector and can optionally cover path-based routing,
proxy, group, and hosted write-policy repositories.

Minimal hosted connector check:

```bash
COMPAT_DOCKER_ENABLED=true \
DOCKER_NEXUS_COMPAT_BASE_URL=http://192.168.215.6:28091 \
DOCKER_NEXUS_PLUS_COMPAT_BASE_URL=http://127.0.0.1:18183 \
NEXUS_COMPAT_USERNAME=admin \
NEXUS_COMPAT_PASSWORD=Admin1234 \
KKREPO_COMPAT_USERNAME=admin \
KKREPO_COMPAT_PASSWORD=123456 \
mvn -pl compat-test -am \
  -DfailIfNoTests=false \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dcompat.docker.pathBased=false \
  -Dcompat.docker.writeEnabled=true \
  -Dtest=DockerRegistryBlackBoxCompatibilityTest \
  test
```

Extended Docker matrix:

```bash
COMPAT_DOCKER_ENABLED=true \
DOCKER_NEXUS_COMPAT_BASE_URL=http://192.168.215.6:28091 \
DOCKER_NEXUS_PLUS_COMPAT_BASE_URL=http://127.0.0.1:18183 \
DOCKER_NEXUS_PLUS_PATH_BASED_COMPAT_BASE_URL=http://127.0.0.1:18090 \
DOCKER_NEXUS_PROXY_COMPAT_BASE_URL=http://192.168.215.6:28092 \
DOCKER_NEXUS_PLUS_PROXY_COMPAT_BASE_URL=http://127.0.0.1:18181 \
DOCKER_NEXUS_GROUP_COMPAT_BASE_URL=http://192.168.215.6:28093 \
DOCKER_NEXUS_PLUS_GROUP_COMPAT_BASE_URL=http://127.0.0.1:18182 \
DOCKER_NEXUS_ALLOW_ONCE_COMPAT_BASE_URL=http://192.168.215.6:28094 \
DOCKER_NEXUS_PLUS_ALLOW_ONCE_COMPAT_BASE_URL=http://127.0.0.1:18184 \
DOCKER_NEXUS_DENY_COMPAT_BASE_URL=http://192.168.215.6:28095 \
DOCKER_NEXUS_PLUS_DENY_COMPAT_BASE_URL=http://127.0.0.1:18185 \
NEXUS_COMPAT_USERNAME=admin \
NEXUS_COMPAT_PASSWORD=Admin1234 \
KKREPO_COMPAT_USERNAME=admin \
KKREPO_COMPAT_PASSWORD=123456 \
mvn -pl compat-test -am \
  -DfailIfNoTests=false \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dcompat.docker.pathBased=false \
  -Dcompat.docker.writeEnabled=true \
  -Dcompat.docker.repository=kkrepo-docker-hosted \
  -Dcompat.docker.proxyImage=library/alpine \
  -Dcompat.docker.proxyReference=latest \
  -Dcompat.docker.groupImage=kkrepo-compat/upload-probe \
  -Dcompat.docker.groupReference=latest \
  -Dtest=DockerRegistryBlackBoxCompatibilityTest \
  test
```

Notes:

- Docker connector URLs should point at the registry connector port, not the Nexus UI/API port.
- Use `-Dcompat.docker.pathBased=false` when the base URLs are repository-specific connector ports.
- `DOCKER_NEXUS_PLUS_PATH_BASED_COMPAT_BASE_URL` should point at the shared kkrepo service port
  that serves `/v2/<repo>/<image>...`.
- Proxy/group/write-policy scenarios are skipped unless all endpoints for that scenario are set.

Real Docker client matrix:

```bash
scripts/docker-compat/client-compat.sh
```

By default the script checks the local dev Docker set: hosted `127.0.0.1:18180`,
proxy `127.0.0.1:18181`, and group `127.0.0.1:18182` with `admin/123456`.
Override registries and images
with `DOCKER_COMPAT_HOSTED_REGISTRY`, `DOCKER_COMPAT_PROXY_REGISTRY`,
`DOCKER_COMPAT_GROUP_REGISTRY`, `DOCKER_COMPAT_SOURCE_IMAGE`,
`DOCKER_COMPAT_PROXY_IMAGE`, and `DOCKER_COMPAT_HOSTED_IMAGE`. `oras` and
`skopeo` are optional in `auto` mode; set `DOCKER_COMPAT_RUN_ORAS=true` or
`DOCKER_COMPAT_RUN_SKOPEO=true` to require them.

OCI Distribution conformance check:

```bash
OCI_ROOT_URL=http://127.0.0.1:18180 \
OCI_NAMESPACE=kkrepo-conformance \
OCI_USERNAME=admin \
OCI_PASSWORD=123456 \
OCI_TEST_PULL=1 \
OCI_TEST_PUSH=1 \
OCI_TEST_CONTENT_DISCOVERY=1 \
OCI_TEST_CONTENT_MANAGEMENT=1 \
DOCKER_OCI_CONFORMANCE_USE_DOCKER=1 \
scripts/docker-compat/oci-conformance.sh
```

The wrapper uses a local `oci-conformance` binary when available, or the
`ghcr.io/opencontainers/distribution-spec/conformance:v1.1.1` image when Docker mode is enabled.
Docker mode uses the host network by default so the conformance container can
reach a registry bound to `127.0.0.1`; set `DOCKER_OCI_CONFORMANCE_NETWORK=default`
when a different Docker network layout is needed.
Reports are written under `target/oci-conformance/docker` by default.
GitHub Actions also includes a manual/label-gated `Docker OCI Conformance`
workflow. Add the `run-docker-oci-conformance` label to a PR, or start it with
`workflow_dispatch`, to build kkrepo, create a Docker hosted repository, refresh
the connector, and run the same wrapper.

Docker migration end-to-end check:

```bash
scripts/docker-compat/migration-e2e.sh
```

The migration script uses the local Nexus REST endpoint `http://localhost:28090/`,
pushes a fixture image to `docker-hosted`, starts repository-data metadata and
package migration for only that Docker repository, then pulls the image from the
kkrepo Docker connector `127.0.0.1:18183`.
