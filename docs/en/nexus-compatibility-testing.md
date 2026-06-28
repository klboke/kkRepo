# Nexus Compatibility Testing

kkrepo is not intended to reinvent artifact repository behavior. Its goal is to stay compatible with Nexus in client protocols, the permission/authentication model, and the `/repository/<repo>/...` URL layout. Compatibility validation is split into three layers: in-project black-box tests, mirrored-traffic observation after migration, and production-scale validation.

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

The current module includes compatibility test classes for Maven, npm, PyPI, Go, Helm, Cargo/Rust, Docker/OCI, NuGet, RubyGems, Yum, Raw, component upload, security management APIs, and related areas.

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
NEXUS_COMPAT_PASSWORD=123456 \
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

Cargo / Rust compatibility uses a Nexus Repository 3.77.x+ Community Edition reference because older default compatibility references do not expose Community Cargo repositories. Use the `cargo` suite in `scripts/ci/run-live-compat.sh`; it covers hosted, proxy, and group repositories, including write behavior when enabled.

## Traffic Mirroring Validation

In addition to in-project black-box compatibility tests, after migrating Nexus to kkrepo, we mirrored 100% of real production traffic to kkrepo through Istio to observe how kkrepo responds to real client requests.

This historical validation stage aims to:

- Confirm that real Maven, npm, PyPI, Go, Helm, Docker/OCI, and similar client requests are recognized correctly by kkrepo.
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

Nexus UI admin requests, ExtDirect polling, Script API requests, and other management-plane traffic are not the same as Maven/npm/PyPI/Go/Helm/Docker/OCI repository protocol traffic. When analyzing mirror anomalies, classify the request type first so management-plane requests are not mistaken for repository protocol compatibility issues.

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

Cargo / Rust is not included in the historical production-scale validation numbers above. Validate Cargo with the Nexus 3.77.x+ compatibility suite and real Cargo clients before production cutover.

## Compatibility Issue Handling Flow

When a compatibility difference is found, handle it in this order:

1. Identify the request type first: repository protocol request, admin UI request, Script API request, or health check.
2. If it is a repository protocol request, first add a reproducible Nexus comparison case in `compat-test`.
3. Compare Nexus and kkrepo status, headers, response body, metadata, checksum, and real client behavior.
4. Implement the minimal compatible fix in kkrepo.
5. Re-run the corresponding `compat-test`; if needed, observe mirrored traffic to verify real requests recover.

Only non-deterministic fields allowed by the protocol should be normalized, such as host, timestamp, ordering, or generated IDs. For checksum, metadata semantics, permission decisions, and client-visible status codes, align with Nexus as much as possible.

## Related Documents

- [Monitoring And Observability Guide](monitoring-observability-guide.md)
- [Nexus Migration Guide](nexus-migration-guide.md)
- [Development Guide](development-guide.md)
- [compat-test README](../../compat-test/README.md)
