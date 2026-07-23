# Database Schema

kkrepo owns one logical relational schema implemented for MySQL and PostgreSQL. Large artifact bytes remain in OSS/S3; the database stores metadata, indexes, references, security state, sessions, audit data, migration progress, and cross-replica coordination state.

## Migration Layout

```text
persistence-mysql/src/main/resources/db/migration/mysql/
  V1 ... V29       immutable MySQL history

persistence-postgresql/src/main/resources/db/migration/postgresql/
  V29              PostgreSQL baseline with the same logical schema
```

From V30 onward, add a migration with the same version and description to both directories. Database-specific syntax is allowed, but table/column meaning, constraints, indexes, defaults, and application-visible behavior must stay equivalent. CI rejects version drift and validates both histories against real database containers.

## Logical Groups

| Group | Representative tables | Purpose |
| --- | --- | --- |
| Repository catalog | `blob_store`, `repository`, `repository_member`, `routing_rule`, `cleanup_policy` | Repository configuration and ordered group membership |
| Content | `component`, `asset`, `asset_blob`, `browse_node`, `component_search` | Package metadata, blob references, browsing, and SQL search |
| Security | `security_user`, `security_role`, `security_privilege`, mapping tables, `api_key` | Identity and Nexus-compatible authorization |
| Audit | `security_audit_log` | Searchable security and administration history |
| Sessions | `SPRING_SESSION`, `SPRING_SESSION_ATTRIBUTES`, `auth_ticket` | Cross-replica login and short-lived authentication state |
| Coordination | `cache_version`, rebuild markers, `maintenance_cursor`, proxy, Docker, Terraform, Swift, and Ansible lease/binding state | Cache invalidation, claim/retry work, and shared runtime state |
| Migration | `migration_job`, checkpoints, validation, repository/asset migration tables | Resumable Nexus migration and reporting |
| Protocol side tables | Docker/OCI, Pub, Terraform, Swift, Ansible Galaxy, and other protocol-specific relations | State that cannot be represented only by common asset attributes; Terraform adds signing keys, provider revisions/platforms, group source bindings, and publish leases in V30; Swift V31 adds releases/manifests/SCM URLs, GitHub source pins, group bindings, leases, tombstones, negative cache, and repository revisions; Ansible V35 adds collection versions/signatures, durable import tasks, proxy version state, group source bindings, and fenced registry leases |

Natural keys and explicit unique constraints protect repository names, paths, package/version identities, blob hashes, token identities, and claim markers across concurrent replicas. Workers claim persistent rows rather than relying on a JVM-local queue.

## Type Mapping

The JDBC persistence layer owns semantic mappings instead of exposing vendor types to protocol code:

| Meaning | MySQL | PostgreSQL |
| --- | --- | --- |
| Structured attributes | `JSON` | `jsonb` |
| Boolean | `BOOLEAN`/numeric driver mapping | `boolean` |
| Absolute timestamp | timezone-normalized timestamp | `timestamptz` |
| Wall-clock timestamp (`LocalDateTime`) | `datetime` | `timestamp without time zone` |
| Generated identity | auto increment | identity/sequence-backed |
| Upsert/claim syntax | MySQL dialect | `ON CONFLICT`, `RETURNING`, PostgreSQL locking syntax |

Application services use `DatabaseDialect`; vendor SQL belongs in `persistence-mysql` or `persistence-postgresql`. Shared DAOs must not infer semantics from vendor-specific JDBC objects.

## Schema Change Checklist

1. Define the logical change and multi-replica behavior.
2. Add the same Flyway version/description for both engines.
3. Preserve MySQL V1-V29 exactly; never edit an applied migration.
4. Update shared persistence contracts and migration compatibility tests.
5. Compare constraints, indexes, defaults, JSON behavior, generated keys, claims, and timestamps on real MySQL and PostgreSQL.
6. Run Flyway parity, package-boundary, two-instance server smoke, and clean reactor verification.
7. Update this document and the detailed ER reference when ownership changes.

The detailed entity relationships remain documented in [MySQL ER Design](mysql-er.md); despite its historical name, those logical relationships apply to both supported backends. Backend operation is documented in [Database Backends](database-backends.md).
