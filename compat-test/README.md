# nexus-plus compat-test

`compat-test` contains compatibility checks that can run in three modes:

- Unit-level Maven checks run during normal `mvn test`.
- Live black-box checks run when endpoint URLs are supplied.
- Performance smoke checks run only when explicitly enabled.

## Reference Nexus Endpoint

Use the Docker-resident Nexus service as the fixed reference endpoint:

```bash
NEXUS_COMPAT_BASE_URL=http://localhost:28090/
NEXUS_COMPAT_USERNAME=admin
NEXUS_COMPAT_PASSWORD=123456
```

All current Maven compatibility checks and future repository-format compatibility checks
(npm, PyPI, Go, Helm, and others) should compare against this same long-running Nexus reference
unless a test explicitly documents why it needs an isolated throwaway Nexus instance.

## Default Test Run

```bash
mvn -pl compat-test -am test
```

The live black-box tests are skipped by default so the module stays deterministic in CI and local
builds without Nexus.

## Disposable Live Compatibility Environment

The GitHub `Live Compatibility` workflow uses the same commands below. It builds a candidate
nexus-plus image, starts MySQL, a disposable Nexus reference, and the candidate service, then
bootstraps the admin user, default file blob store, and Maven/npm fixture repositories.
The disposable defaults are `admin` / `123456` for Nexus and `admin` / `12345678` for nexus-plus.

```bash
scripts/build-docker-image.sh nexus-plus:compat
docker compose -f docker-compose.compat.yml up -d mysql nexus nexus-plus
scripts/ci/live-compat-setup.sh
scripts/ci/run-live-compat.sh smoke
docker compose -f docker-compose.compat.yml down -v
```

Available suites:

- `smoke`: console API checks plus Maven proxy GET/HEAD/checksum read compatibility.
- `write-smoke`: Maven hosted release/snapshot write compatibility with `COMPAT_WRITE_ENABLED=true`.
- `extended`: smoke coverage plus currently separated PyPI, Helm, NuGet, RubyGems, and Yum checks.
- `full`: all compat-test tests with live endpoint variables set; use this as a diagnostic suite
  when working through known protocol gaps.

In GitHub Actions, add the `run-live-compat` label to a PR to run the smoke suite, or start the
workflow manually and select a suite.

## Live Console And Maven Read Checks

Start nexus-plus locally first:

```bash
scripts/restart.sh
```

Then run the live checks against a running Nexus reference and nexus-plus:

```bash
mvn -pl compat-test -am \
  -DfailIfNoTests=false \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dcompat.nexus.baseUrl=http://localhost:28090/ \
  -Dcompat.nexusPlus.baseUrl=http://127.0.0.1:18090 \
  -Dcompat.nexus.readRepository=maven-public \
  -Dcompat.nexusPlus.readRepository=maven-public \
  -Dtest=NexusPlusConsoleBlackBoxCompatibilityTest,MavenRepositoryBlackBoxCompatibilityTest#proxyReadRoundTripMatchesNexusWhenConfigured \
  test
```

## Live Security Admin Compatibility

The security admin checks compare Nexus `#admin/security` ExtDirect contracts against nexus-plus.
They are disabled by default and use the fixed Docker Nexus reference endpoint.

```bash
COMPAT_SECURITY_ENABLED=true \
NEXUS_COMPAT_BASE_URL=http://localhost:28090/ \
NEXUS_COMPAT_USERNAME=admin \
NEXUS_COMPAT_PASSWORD=123456 \
NEXUS_PLUS_COMPAT_BASE_URL=http://127.0.0.1:18090 \
NEXUS_PLUS_COMPAT_USERNAME=admin \
NEXUS_PLUS_COMPAT_PASSWORD=admin123 \
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
`/service/rest/v1/security/users`, nexus-plus upload repository filtering when the user lacks
`nexus:component:create`, and content-selector allow/deny status parity for selected Maven asset
paths. NuGet API key UI endpoints are intentionally not covered by this security suite; repository
protocol compatibility for NuGet, RubyGems, and Yum is covered separately from the security-admin
checks. The nexus-plus password above is only the local dev admin used for compatibility validation;
migrated environments should use their migrated admin credential.

Realm protocol behavior is covered in the server test suite rather than the live Nexus comparison:
`SecurityAuthenticationServiceTest` starts an in-memory LDAP server for real bind/search/group-role
mapping and a local JWKS HTTP endpoint for signed OIDC bearer JWT validation. Basic local/LDAP
authentication follows enabled realm priority; OIDC bearer/auth-code, API key, and session subjects
use their own token/session entry points instead of the Basic realm order.

## Live NuGet, RubyGems, And Yum Checks

The NuGet, RubyGems, and Yum repository checks compare the fixed Nexus reference endpoint above
with the local nexus-plus dev server. The default credentials for both sides are `admin` / `123456`.

```bash
mvn -pl compat-test -am \
  -DfailIfNoTests=false \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dcompat.nexus.baseUrl=http://localhost:28090/ \
  -Dcompat.nexus.username=admin \
  -Dcompat.nexus.password=123456 \
  -Dcompat.nexusPlus.baseUrl=http://127.0.0.1:18090 \
  -Dcompat.nexusPlus.username=admin \
  -Dcompat.nexusPlus.password=123456 \
  -Dtest=NugetRubygemsYumRepositoryBlackBoxCompatibilityTest \
  test
```

Hosted NuGet multipart push and Yum RPM PUT are opt-in because they write packages into the
comparison repositories. To target the Browse repository from local dev, set
`COMPAT_YUM_HOSTED_REPOSITORY=yum-compat-hosted`; the Yum test uploads under
`Packages/nexus-plus-compat-yum-<timestamp>/`.

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
NEXUS_COMPAT_PASSWORD=123456 \
NEXUS_PLUS_COMPAT_BASE_URL=http://127.0.0.1:18090 \
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
  -Dcompat.nexus.password=123456 \
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
NEXUS_COMPAT_PASSWORD=123456 \
NEXUS_PLUS_COMPAT_BASE_URL=http://127.0.0.1:18090 \
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
- nexus-plus URL: `http://127.0.0.1:18090`
- disposable data dir: a new `/private/tmp/nexus-plus-compat-nexus.*` directory

Override with:

```bash
NEXUS_COMPAT_PORT=58084 \
NEXUS_PLUS_COMPAT_BASE_URL=http://127.0.0.1:18090 \
compat-test/run-local-write-compat.sh
```

## Performance Smoke Checks

These are not load tests. They only catch obvious latency regressions on warmed single-request
paths.

```bash
COMPAT_PERF_ENABLED=true \
NEXUS_PLUS_COMPAT_BASE_URL=http://127.0.0.1:18090 \
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

The Go black-box test compares nexus-plus against Nexus Go proxy and group repositories. By default
it targets the local Nexus at `http://localhost:28090` with `admin` / `123456`, and nexus-plus at
`http://127.0.0.1:18090`. Override `GO_NEXUS_PLUS_COMPAT_BASE_URL` when testing a non-default
nexus-plus port.

Because the test creates or updates nexus-plus repositories during setup, provide
`GO_NEXUS_PLUS_COMPAT_USERNAME` and `GO_NEXUS_PLUS_COMPAT_PASSWORD` when repository management
security is enabled. Without those credentials the setup-oriented Go test is skipped.

```bash
GO_NEXUS_PLUS_COMPAT_BASE_URL=http://127.0.0.1:48092 \
GO_NEXUS_PLUS_COMPAT_USERNAME=admin \
GO_NEXUS_PLUS_COMPAT_PASSWORD=... \
mvn -pl compat-test -am \
  -DfailIfNoTests=false \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtest=GoProxyBlackBoxCompatibilityTest \
  test
```

The test creates/updates `go-proxy-compat`, `go-group-compat-miss`,
`go-group-compat-hit`, and `go-group-compat` on both sides. Override with
`GO_NEXUS_COMPAT_BASE_URL`, `GO_NEXUS_PLUS_COMPAT_BASE_URL`,
`GO_NEXUS_COMPAT_PASSWORD`, `GO_GROUP_COMPAT_REPOSITORY`, and related
`GO_GROUP_COMPAT_*` settings when needed.

## Helm Hosted And Proxy Compatibility

The Helm black-box test auto-creates `helm-hosted` and `helm-proxy` on both endpoints when missing.
It covers hosted multipart chart push, hosted `index.yaml` and chart reads, hosted `HEAD`, hosted
delete cleanup, plus proxy `index.yaml` URL rewriting and proxied chart download.

```bash
NEXUS_COMPAT_BASE_URL=http://localhost:28090/ \
NEXUS_COMPAT_USERNAME=admin \
NEXUS_COMPAT_PASSWORD=123456 \
NEXUS_PLUS_COMPAT_BASE_URL=http://127.0.0.1:18090 \
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
NEXUS_COMPAT_PASSWORD=123456 \
NEXUS_PLUS_COMPAT_BASE_URL=http://127.0.0.1:18090 \
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
