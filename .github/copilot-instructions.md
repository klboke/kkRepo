# kkRepo Copilot Instructions

kkRepo is a Nexus-compatible, self-hosted artifact repository. Treat Nexus and the official package protocols as compatibility references. Do not invent protocol behavior from memory.

## Engineering Rules

- Use Java 25.
- Prefer the existing module boundaries and local helper APIs.
- Keep server-side protocol logic out of controllers; controllers should delegate to protocol services/adapters.
- Large blobs belong in OSS/S3-compatible storage. MySQL stores metadata, indexes, references, users, permissions, tokens, audit data, and migration state.
- Any stateful feature must work in distributed multi-replica deployments. Do not rely on a single JVM as the source of truth for sessions, uploads, deletes, locks, background jobs, cache invalidation, metadata rebuilds, negative cache, or permission decisions.
- In-process cache is allowed only as rebuildable hot cache with a clear TTL or invalidation path.
- Migration is a product feature, not a one-off script. Prefer idempotent dry-run, resume, checksum validation, and reporting.

## Build and Test

- When compiling or testing `server`, always use `-am`, for example `mvn -pl server -am test`.
- When selecting one server test with `-am`, include `-Dsurefire.failIfNoSpecifiedTests=false`.
- Use Java 25 explicitly if the local shell may not be configured:
  `JAVA_HOME=/opt/homebrew/Cellar/openjdk@25/25.0.3/libexec/openjdk.jdk/Contents/Home`.
- Before replacing a runtime jar, build an executable Spring Boot jar with:
  `mvn -pl server -am -DskipTests package spring-boot:repackage`.

## Compatibility Workflow

- Add or update compatibility tests under `compat-test` for protocol behavior changes.
- Compare HTTP status, headers, body semantics, generated metadata, checksums, and real client behavior.
- Normalize host, timestamps, ordering, or generated IDs only when the protocol allows it.

## Review Focus

- Look for Nexus compatibility regressions, multi-replica state bugs, metadata/index consistency issues, unsafe upload/delete behavior, permission bypasses, SSRF risk in proxy repositories, and missing tests.
- For docs, keep `docs/en` and `docs/zh` aligned when both locales cover the same behavior.
