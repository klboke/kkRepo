# Contributing to kkrepo

Thanks for helping improve kkrepo. The project aims to stay compatible with Nexus client-visible behavior while using a MySQL-first, OSS/S3-first, multi-replica-friendly architecture.

## Before You Start

- Use GitHub issues for reproducible bugs, compatibility differences, and feature requests.
- Do not open public issues for exploitable security vulnerabilities. Follow [SECURITY.md](SECURITY.md) instead.
- For protocol behavior, check the official protocol documentation and Nexus behavior before designing the change.
- For stateful behavior, assume distributed multi-replica deployment by default.

## Development Setup

Prerequisites:

- JDK 25
- Maven 3.9 or newer
- Docker, optional but useful for local MySQL and S3-compatible storage

Start local development dependencies when needed:

```bash
docker compose -f docker-compose.dev.yml up -d
```

Run focused server tests with the module dependency graph included:

```bash
mvn -pl server -am test
```

Run the non-live compatibility checks used by default PR CI:

```bash
mvn -pl compat-test -am \
  -Dtest=MavenMetadataMergeCompatibilityTest,MavenWritePolicyCompatibilityTest,NpmProtocolCompatibilityTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  test
```

Live black-box compatibility checks can start disposable Nexus and kkrepo services with:

```bash
scripts/build-docker-image.sh kkrepo:compat
docker compose -f docker-compose.compat.yml up -d mysql nexus kkrepo
scripts/ci/live-compat-setup.sh
scripts/ci/run-live-compat.sh smoke
docker compose -f docker-compose.compat.yml down -v
```

When a protocol change affects behavior that real package clients exercise directly, run the real client E2E suite against the same disposable kkrepo candidate:

```bash
scripts/ci/run-live-compat.sh client-e2e
```

It covers Maven, npm, PyPI, Go resolve, Helm, Cargo/Rust, Dart/Pub, Flutter Pub, Composer/PHP, NuGet, RubyGems, Yum, and Docker/OCI client flows. Logs and downloaded metadata are written under `artifacts/client-e2e/`.

See [compat-test/README.md](compat-test/README.md) for suite options, environment variables, and Maven properties.

## Compatibility Expectations

Protocol changes should follow this order:

1. Confirm the official protocol behavior and Nexus reference behavior.
2. Add or update a compatibility test under `compat-test` when the behavior is externally visible.
3. Implement the minimal compatible behavior in kkrepo.
4. Compare HTTP status, headers, response body semantics, generated metadata, checksum behavior, and real client behavior.
5. Normalize only protocol-allowed nondeterministic fields such as host, timestamp, ordering, or generated IDs.

Do not invent protocol behavior when Nexus or the official protocol already defines it.

## Distributed Deployment Expectations

Design stateful behavior for multi-replica deployments:

- MySQL should be the source of truth for metadata, users, permissions, tokens, migration state, sessions, locks, markers, and other coordinated state.
- OSS/S3 should hold large blob content.
- In-process caches are acceptable only as rebuildable hot caches with clear TTLs or invalidation rules.
- Background tasks, upload/delete flows, index rebuilds, metadata updates, negative cache, permission checks, and migration steps must not rely on a single JVM as the only source of truth.

## Pull Requests

Before opening a PR:

- Keep the change scoped to one behavior or feature.
- Add or update tests for behavior changes.
- Run the relevant validation commands and list them in the PR body.
- Update user-facing docs when behavior or operations change.
- Explain any compatibility or multi-replica tradeoffs.

The default PR CI runs:

```bash
docker compose -f docker-compose.dev.yml config
mvn -B -ntp -pl server -am -Dsurefire.failIfNoSpecifiedTests=false test
mvn -B -ntp -pl compat-test -am -Dtest=MavenMetadataMergeCompatibilityTest,MavenWritePolicyCompatibilityTest,NpmProtocolCompatibilityTest -Dsurefire.failIfNoSpecifiedTests=false test
```

The separate `Live Compatibility` workflow builds a candidate Docker image, starts a disposable
Nexus reference plus kkrepo, bootstraps both environments, and runs black-box compatibility or
real client E2E checks. It runs nightly, can be started manually, runs smoke/Cargo checks for PRs
after the `run-live-compat` label is added, and runs the real client matrix after the
`run-client-e2e` label is added.

## Documentation

- Keep the root README concise.
- Put English docs under `docs/en`.
- Put Chinese docs under `docs/zh`.
- Keep public docs free of private deployment domains, internal CI details, and organization-specific operational assumptions.
