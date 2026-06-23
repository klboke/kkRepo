---
applyTo: "server/**,core/**,persistence-mysql/**,cache/**,storage-*/**,migration-nexus/**"
---

# Java Server Instructions

- Use Java 25 language and library assumptions.
- Keep transactional metadata in MySQL and blob bytes in the configured blob storage.
- Use explicit unique constraints and transactions for path, package, version, asset, token, permission, and upload identities.
- Prefer shared DAO/service abstractions already present in the repository.
- Do not introduce node-local correctness dependencies. JVM-local state must be a hot cache only and must be reconstructable.
- For request handling, preserve Nexus-compatible status codes, headers, and response body semantics.
- Add focused tests for concurrency, duplicate writes, rollback cleanup, permission checks, and cache invalidation when touching shared behavior.
- Run server checks with `mvn -pl server -am ...`, not `mvn -pl server ...` alone.
