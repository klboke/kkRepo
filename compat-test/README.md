# kkrepo compat-test

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
kkrepo image, starts MySQL, a disposable Nexus reference, and the candidate service, then
bootstraps the admin user, default file blob store, and Maven/npm fixture repositories.
The disposable defaults are `admin` / `123456` for Nexus and `admin` / `12345678` for kkrepo.

```bash
scripts/build-docker-image.sh kkrepo:compat
docker compose -f docker-compose.compat.yml up -d mysql nexus kkrepo
scripts/ci/live-compat-setup.sh
scripts/ci/run-live-compat.sh smoke
docker compose -f docker-compose.compat.yml down -v
```

For Cargo/Rust compatibility work, use the Nexus 3.77.x Community Edition
reference compose file instead of the default Nexus 3.29.2 reference. Nexus
3.73.0 contains the Cargo plugin, but exposes it only in Pro:

```bash
scripts/build-docker-image.sh kkrepo:compat
export COMPOSE_FILE="$PWD/docker-compose.compat-rust.yml"
export COMPOSE_PROJECT_NAME=kkrepo-rust-compat
export NEXUS_COMPAT_PORT=38090
export KKREPO_COMPAT_PORT=18092
export KKREPO_MANAGEMENT_PORT=18093
docker compose -f "$COMPOSE_FILE" up -d mysql nexus kkrepo
scripts/ci/live-compat-setup.sh
scripts/ci/run-live-compat.sh cargo
docker compose -f "$COMPOSE_FILE" down -v
```

Available suites:

- `smoke`: console API checks plus Maven proxy GET/HEAD/checksum read compatibility.
- `write-smoke`: Maven hosted release/snapshot write compatibility with `COMPAT_WRITE_ENABLED=true`.
- `cargo`: Cargo hosted/proxy/group compatibility against Nexus 3.77.x+. Proxy and group checks
  force both sides to use the same upstream sparse index (`CARGO_COMPAT_REMOTE_URL`, default
  `https://index.crates.io/`) and the same crate/version (`CARGO_COMPAT_CRATE`, default `itoa`;
  `CARGO_COMPAT_VERSION`, default `1.0.15`).
- `extended`: smoke coverage plus currently separated PyPI, Helm, NuGet, RubyGems, and Yum checks.
- `full`: all compat-test tests with live endpoint variables set; use this as a diagnostic suite
  when working through known protocol gaps.

In GitHub Actions, add the `run-live-compat` label to a PR to run the smoke suite plus Cargo
compatibility against the Nexus 3.77.x reference, or start the workflow manually and select a suite.

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
NEXUS_COMPAT_PASSWORD=123456 \
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
with the local kkrepo dev server. The default credentials for both sides are `admin` / `123456`.

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
NEXUS_COMPAT_PASSWORD=123456 \
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
it targets the local Nexus at `http://localhost:28090` with `admin` / `123456`, and kkrepo at
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
NEXUS_COMPAT_PASSWORD=123456 \
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
NEXUS_COMPAT_PASSWORD=123456 \
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
NEXUS_COMPAT_PASSWORD=123456 \
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
NEXUS_COMPAT_PASSWORD=123456 \
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
