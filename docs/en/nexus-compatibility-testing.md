# Nexus Compatibility Testing

kkrepo is not intended to reinvent artifact repository behavior. Its goal is to stay compatible with Nexus in client protocols, the permission/authentication model, and the `/repository/<repo>/...` URL layout. CI validation is centered on three E2E blocks: Nexus compatibility, client E2E compatibility, and Migration E2E. Mirrored-traffic observation and production-scale validation remain additional release confidence signals.

## In-Project Compatibility Test Module

The project includes a complete compatibility test module:

```text
compat-test/
```

`compat-test` compares a real Nexus reference instance with a kkrepo instance through black-box tests. It covers protocol requests, write behavior, management API shape, permission behavior, and selected performance smoke tests. Tests do not depend on Nexus internals. Instead, they compare client-visible behavior, such as:

- HTTP status
- Key response headers
- Response body semantics
- Protocol metadata
- checksum
- hosted write, delete, and repeated-upload behavior
- client-visible proxy, group, browse/search behavior

The current module includes compatibility test classes for Maven, npm, PyPI, Go, Helm, Cargo/Rust, Dart/Pub, Docker/OCI, NuGet, RubyGems, Yum, Raw, component upload, security management APIs, and related areas.

Regular test command:

```bash
mvn -pl compat-test -am test
```

By default, live black-box tests that depend on real Nexus and kkrepo URLs are skipped, so local development and CI remain stable when reference instances are not available.

## Black-Box Comparison Tests

To run live black-box tests, provide both a Nexus reference instance and a kkrepo candidate instance:

```bash
NEXUS_COMPAT_BASE_URL=http://localhost:28090/ \
NEXUS_COMPAT_USERNAME=admin \
NEXUS_COMPAT_PASSWORD=Admin1234 \
KKREPO_COMPAT_BASE_URL=http://127.0.0.1:18090 \
KKREPO_COMPAT_USERNAME=admin \
KKREPO_COMPAT_PASSWORD=123456 \
mvn -pl compat-test -am \
  -DfailIfNoTests=false \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtest=MavenRepositoryBlackBoxCompatibilityTest,NpmRepositoryBlackBoxCompatibilityTest,PypiRepositoryBlackBoxCompatibilityTest \
  test
```

Write tests are disabled by default and must be explicitly enabled:

```bash
COMPAT_WRITE_ENABLED=true
```

This avoids accidentally writing test packages to a long-running Nexus reference instance. Write tests usually use one-off package names and paths, and cover delete, repeated upload, and metadata update behavior when feasible.

The unified Nexus compatibility matrix uses the datastore-era Nexus PostgreSQL reference for newer formats. Run `scripts/ci/run-live-compat.sh nexus` against the disposable compose environment to compare kkrepo with Nexus across Maven, npm, PyPI, Cargo/Rust, Dart/Pub, Raw, selected NuGet/RubyGems/Yum behavior, Go proxy endpoints, Helm hosted round trips, component upload specs, and selected security/admin contracts. This suite enables disposable write checks by default. The current PostgreSQL compose pins the reference to Nexus 3.92.0. In GitHub Actions, use the `Live Compatibility / Nexus compatibility` job through the `run-live-compat` label or the scheduled workflow.

Outside Dart / Pub, some component upload specs and security/admin contracts still compare Nexus legacy UI/internal API routes. kkrepo production and distribution configuration keeps `kkrepo.nexus.legacy-ui.enabled=false` by default to disable the Nexus Rapture, ExtDirect, Wonderland, internal UI upload, and related legacy UI compatibility surface. The disposable compatibility compose environment sets `KKREPO_NEXUS_LEGACY_UI_ENABLED=true` for the candidate service by default only for those Nexus legacy-route compatibility checks. When reproducing these live tests manually, set the environment variable before starting the candidate kkrepo service; setting it only before `mvn -pl compat-test ... test` does not affect an already-running service. Normal deployments should keep the default disabled, and the kkrepo Browse UI uses the native `/internal/security/permissions` endpoint instead of the legacy internal UI permissions route.

Dart / Pub compatibility uses the same Nexus Repository 3.92.0+ PostgreSQL reference because Pub repositories are first available there. The `nexus` suite includes Pub hosted/proxy/group metadata, archive, publish, `version.json`, checksum, and error-status coverage through `PubRepositoryBlackBoxCompatibilityTest`.

## Real Client E2E

The `client-e2e` suite validates the behavior of actual package clients against a disposable kkrepo candidate:

```bash
scripts/ci/run-live-compat.sh client-e2e
```

It runs publish/upload plus download/resolve flows for Maven, npm, PyPI, Helm, Cargo/Rust, Dart/Pub, NuGet, RubyGems, Yum, and Docker/OCI. Go is resolve-only through the Go module proxy because hosted Go publishing is not a supported repository mode. Docker image push/pull is always covered, and ORAS pushes/pulls a generic OCI artifact when the `oras` client is available.

Use this suite when a change affects repository protocol behavior that real clients exercise: authentication headers or API keys, publish/upload paths, generated metadata, package index shape, checksum/download behavior, Docker connector ports, or group/proxy resolution. In GitHub Actions, run it manually by selecting `client-e2e` in the `Live Compatibility` workflow, or add the `run-client-e2e` label to a PR.

Client command logs, downloaded metadata, selected inspect output, and other diagnostics are written under `artifacts/client-e2e/`. The suite depends on the real tools listed in [compat-test README](../../compat-test/README.md), so a local workstation may need extra SDKs or package managers before it can run the full matrix.

## Migration E2E

The `Migration E2E` workflow imports configuration, security metadata, and repository data from supported Nexus generations, including the historical 3.29.x embedded/OrientDB reference and datastore-era H2/PostgreSQL references. Add the `run-migration-e2e` label to a PR when a change affects source detection, migration adapters, repository-data import, blob/checksum validation, permissions, or post-migration protocol behavior.

## Traffic Mirroring Validation

In addition to in-project black-box compatibility tests and real client E2E checks, after migrating Nexus to kkrepo, we mirrored 100% of real production traffic to kkrepo through Istio to observe how kkrepo responds to real client requests.

This historical validation stage aims to:

- Confirm that real Maven, npm, PyPI, Go, Helm, Cargo/Rust, Dart/Pub, Docker/OCI, and similar client requests are recognized correctly by kkrepo.
- Compare HTTP status, error types, and key response behavior between the Nexus main path and the kkrepo mirrored path.
- Observe proxy upstream access, blob storage, permission/authentication, and metadata/index rebuild stability under real traffic.
- Discover edge requests not covered by `compat-test`, such as special client headers, old client behavior, CI plugin probe requests, and occasional proxy requests.

Istio traffic mirroring only copies requests to kkrepo. Clients still receive responses from the main path, so kkrepo compatibility can be observed without affecting clients. During mirror validation, the following information is used to determine whether a compatibility problem exists:

- kkrepo application logs
- Istio access logs
- Prometheus metrics
- Grafana dashboards
- Repository request 4xx/5xx distribution
- Proxy upstream errors and latency
- Blob storage read/write errors

Nexus UI admin requests, ExtDirect polling, Script API requests, and other management-plane traffic are not the same as Maven/npm/PyPI/Go/Helm/Cargo/Pub/Docker/OCI repository protocol traffic. When analyzing mirror anomalies, classify the request type first so management-plane requests are not mistaken for repository protocol compatibility issues.

## Production-Scale Validation

kkrepo has already gone through one round of real production-scale validation. The validation scenario mainly uses these 5 repository types:

- Maven
- npm
- PyPI
- Go
- Helm

Overall scale and observations:

| Dimension | Scale or result |
| --- | --- |
| Total packages | About `1.8 million` packages |
| Peak QPS | About `200` |
| Average RT when local cache is hit | Below `50ms` |
| RT during occasional proxy upstream access | Can spike above `600ms` |
| hosted repository migration scale | About `500,000` packages |
| hosted repository migration time | Completed overnight |

These numbers show kkrepo validation results under real business traffic and migration scale. They do not represent a fixed SLA. Actual throughput and latency are affected by MySQL sizing, OSS/S3 performance, network, proxy upstream quality, repository count, package size, and replica count.

Cargo / Rust and Dart / Pub are not included in the historical production-scale validation numbers above. Validate Cargo with the Nexus 3.77.x+ compatibility suite and real Cargo clients, and validate Pub with the Nexus 3.92.0+ compatibility suite plus real `dart pub` / `flutter pub` clients before production cutover.

## Compatibility Issue Handling Flow

When a compatibility difference is found, handle it in this order:

1. Identify the request type first: repository protocol request, admin UI request, Script API request, or health check.
2. If it is a repository protocol request, first add a reproducible Nexus comparison case in `compat-test`.
3. Compare Nexus and kkrepo status, headers, response body, metadata, checksum, and real client behavior. Run `client-e2e` when the changed path is exercised by package clients directly.
4. Implement the minimal compatible fix in kkrepo.
5. Re-run the corresponding `compat-test`; if needed, observe mirrored traffic to verify real requests recover.

Only non-deterministic fields allowed by the protocol should be normalized, such as host, timestamp, ordering, or generated IDs. For checksum, metadata semantics, permission decisions, and client-visible status codes, align with Nexus as much as possible.

## Related Documents

- [Monitoring And Observability Guide](monitoring-observability-guide.md)
- [Nexus Migration Guide](nexus-migration-guide.md)
- [Development Guide](development-guide.md)
- [compat-test README](../../compat-test/README.md)
