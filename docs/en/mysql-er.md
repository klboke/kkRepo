# kkrepo MySQL ER Design

The historical MySQL schema starts at `persistence-mysql/src/main/resources/db/migration/mysql/V1__init_schema.sql`; MySQL and PostgreSQL then advance through paired migrations, currently V35. Flyway executes the applicable history during service startup. MySQL 8 uses InnoDB. PostgreSQL has an equivalent V29 baseline plus the same V30+ logical changes; this document remains the detailed logical ER reference for both engines. See [Database Schema](database-schema.md).

The schema uses a "unified content table + format field" model for shared asset/blob data. Cargo / Rust, Dart / Pub, Composer / PHP, Terraform, Swift, and Ansible use this shared model with protocol metadata stored in component/asset attributes. Composer package/version/dist data does not add a dedicated business table, and proxy routes are stored as rebuildable internal assets. Pub adds `pub_upload_session` for the official multi-step publish flow. Terraform adds signing-key, provider revision/platform, group source-binding, and publish-lease side tables. Ansible V35 adds collection version/signature, durable import task, proxy state, group binding, and fenced lease tables because those relationships must remain consistent across replicas. Formats with other protocol-specific relationships, such as Docker/OCI manifests, tags, upload sessions, auth tokens, and referrers, also add dedicated side tables. This is more suitable for migration from Nexus and unified admin-console queries. If a specific format becomes significantly larger later, it can be optimized with partitioning or additional dedicated tables.

## Repository And Content ER

```mermaid
erDiagram
  BLOB_STORE ||--o{ REPOSITORY : stores
  ROUTING_RULE ||--o{ REPOSITORY : filters
  REPOSITORY ||--o{ REPOSITORY_MEMBER : group
  REPOSITORY ||--o{ REPOSITORY_MEMBER : member
  CLEANUP_POLICY ||--o{ REPOSITORY_CLEANUP_POLICY : attached
  REPOSITORY ||--o{ REPOSITORY_CLEANUP_POLICY : uses
  REPOSITORY ||--o{ COMPONENT : owns
  REPOSITORY ||--o{ ASSET : owns
  COMPONENT ||--o{ ASSET : contains
  BLOB_STORE ||--o{ ASSET_BLOB : stores
  ASSET_BLOB ||--o{ ASSET : referenced_by
  COMPONENT ||--o| COMPONENT_SEARCH : indexed_as
  REPOSITORY ||--o{ BROWSE_NODE : browses
  COMPONENT ||--o{ BROWSE_NODE : shown_as
  ASSET ||--o| BROWSE_NODE : shown_as
```

## Security And Audit ER

```mermaid
erDiagram
  SECURITY_USER ||--o{ SECURITY_USER_ROLE : has
  SECURITY_ROLE ||--o{ SECURITY_USER_ROLE : assigned
  SECURITY_ROLE ||--o{ SECURITY_ROLE_PRIVILEGE : grants
  SECURITY_PRIVILEGE ||--o{ SECURITY_ROLE_PRIVILEGE : granted_by
  SECURITY_ROLE ||--o{ SECURITY_ROLE_INHERITANCE : parent
  SECURITY_ROLE ||--o{ SECURITY_ROLE_INHERITANCE : child
  SECURITY_REALM ||--o{ SECURITY_USER : authenticates
  SECURITY_REPOSITORY_TARGET ||--o{ SECURITY_PRIVILEGE : referenced_by
  SECURITY_USER ||--o{ API_KEY : owns
  API_KEY ||--o{ SECURITY_AUDIT_LOG : actor
```

Except for role/privilege mapping tables, the lines involving `security_realm`, `security_repository_target`, `api_key`, and `security_audit_log` describe runtime business relationships. Actual hard foreign keys are defined by Flyway migrations. The audit table does not create foreign keys to actor/token data, so audit records are not affected by account or token lifecycle cascades.

## Runtime Coordination And Migration ER

```mermaid
erDiagram
  REPOSITORY ||--o| PROXY_REMOTE_STATE : remote_health
  REPOSITORY ||--o{ METADATA_REBUILD_MARKER : maven_metadata_queue
  REPOSITORY ||--o{ REPOSITORY_INDEX_REBUILD_MARKER : index_queue
  REPOSITORY ||--o{ PUB_UPLOAD_SESSION : pub_upload
  REPOSITORY ||--o{ TERRAFORM_SIGNING_KEY : signs
  REPOSITORY ||--o{ TERRAFORM_PROVIDER_SIGNING_STATE : provider_revision
  REPOSITORY ||--o{ TERRAFORM_PROVIDER_PLATFORM : provider_platform
  REPOSITORY ||--o{ TERRAFORM_SOURCE_BINDING : group_source
  REPOSITORY ||--o{ ANSIBLE_COLLECTION_VERSION : collection_version
  ANSIBLE_COLLECTION_VERSION ||--o{ ANSIBLE_COLLECTION_SIGNATURE : signature
  REPOSITORY ||--o{ ANSIBLE_IMPORT_TASK : import_task
  REPOSITORY ||--o{ ANSIBLE_PROXY_VERSION_STATE : proxy_state
  REPOSITORY ||--o{ ANSIBLE_GROUP_BINDING : group_source
  REPOSITORY ||--o{ ANSIBLE_REGISTRY_LEASE : fenced_lease
  SPRING_SESSION ||--o{ SPRING_SESSION_ATTRIBUTES : has
  MIGRATION_JOB ||--o{ MIGRATION_CHECKPOINT : records
  MIGRATION_JOB ||--o{ MIGRATION_VALIDATION_RESULT : validates
  MIGRATION_JOB ||--o{ REPOSITORY_DATA_MIGRATION_REPOSITORY : migrates
  REPOSITORY ||--o{ REPOSITORY_DATA_MIGRATION_REPOSITORY : target
  REPOSITORY_DATA_MIGRATION_REPOSITORY ||--o{ REPOSITORY_DATA_MIGRATION_ASSET : contains
  COMPONENT ||--o{ REPOSITORY_DATA_MIGRATION_ASSET : target_component
  ASSET ||--o{ REPOSITORY_DATA_MIGRATION_ASSET : target_asset
  ASSET_BLOB ||--o{ REPOSITORY_DATA_MIGRATION_ASSET : target_blob
```

`cache_version`, `auth_ticket`, and `maintenance_cursor` are runtime coordination tables read and written independently by primary key, so they do not attach business foreign keys.

## Table Layers

### Repository Configuration Layer

| Table | Responsibility |
| --- | --- |
| `blob_store` | Logical definition of OSS/S3/File blob stores. Keeps raw attributes JSON and extracts common fields such as endpoint/bucket/prefix |
| `repository` | hosted/proxy/group repository definition. Keeps Nexus recipe and attributes JSON, and explicitly stores format, type, online, remote URL, and similar fields |
| `repository_member` | Member order for group repositories. Nexus group semantics depend on order, so it must be preserved. Group repositories also have a blob store |
| `routing_rule` | proxy routing rule |
| `cleanup_policy` | raw cleanup policy conditions |
| `repository_cleanup_policy` | Binding between repository and cleanup policy |
| `content_selector` | Early content selector configuration table. Runtime authorization currently uses CSEL/target definitions in `security_repository_target` |

### Content Metadata Layer

| Table | Responsibility |
| --- | --- |
| `component` | Package-level metadata. All artifact formats are unified here, and `format` stores the Nexus protocol format name |
| `asset` | Downloadable object or protocol metadata file, such as jar, pom, tarball, simple index, or index.yaml |
| `asset_blob` | Physical blob reference and checksum. `deleted_at/delete_reason/delete_claimed_at` support soft delete, GC claim, and blob store usage statistics |
| `browse_node` | Tree view for the Browse page. It can be rebuilt from `asset`, but keeping a copy improves UI query experience. `has_asset_subtree` helps large repository directory queries |
| `component_search` | Denormalized SQL Search index table. It replaces the old Nexus local Elasticsearch approach |

### Runtime Coordination Layer

| Table | Responsibility |
| --- | --- |
| `proxy_remote_state` | Remote health state and failure count for proxy repositories, used as shared circuit-breaker state across replicas |
| `metadata_rebuild_marker` | Maven metadata asynchronous rebuild queue, deduplicated by `(repository_id, scope_key)`. Workers claim tasks concurrently with `FOR UPDATE SKIP LOCKED` |
| `repository_index_rebuild_marker` | Repository-level or scoped index rebuild queue for Helm/PyPI/Yum/RubyGems and similar formats, with failure count and error summary |
| `cache_version` | MySQL version watermark. Node-local cache is only hot cache. After changes, bump the version so other replicas poll, invalidate, and reload |
| `SPRING_SESSION` | Spring Session JDBC main table for cross-replica HTTP sessions |
| `SPRING_SESSION_ATTRIBUTES` | Spring Session attribute table |
| `auth_ticket` | Short-lived authentication ticket, stored by token hash and cleaned by expiration time |
| `maintenance_cursor` | Shared cursor for background maintenance tasks, such as blob reconcile scan watermarks |
| `ui_settings` | Singleton UI preference table, currently used for default language selection |
| `pub_upload_session` | Dart / Pub publish upload session state, including session/field tokens, principal, expiration, temporary blob reference, parsed package/version, checksums, size, error, and finalized time |
| `terraform_signing_key` | Encrypted hosted Provider signing key revisions and public key material; one active revision is selected per repository |
| `terraform_provider_signing_state` | Ready Provider-version revision and the matching SHA256SUMS/signature paths and signing-key revision |
| `terraform_provider_platform` | Provider platform identity and archive path/checksum, unique by repository/namespace/type/version/os/arch |
| `terraform_source_binding` | Expiring group coordinate-to-member binding that keeps metadata and archive reads on the same member/revision |
| `terraform_publish_lease` | Database-backed expiring lease used to serialize module/provider publication across replicas |
| `ansible_collection_version` | Canonical collection coordinate, component/asset reference, archive SHA-256, bounded query metadata/dependencies, state, source, and revision; complete `MANIFEST.json`/`FILES.json` remain in the artifact blob |
| `ansible_collection_signature` | Optional detached collection signatures bound to one immutable version and stored by blob/hash reference |
| `ansible_import_task` | Durable Galaxy publish task with staging asset, requester, validation result, claim/lease, fencing token, attempts, and timestamps |
| `ansible_proxy_version_state` | Upstream discovery/detail identity, validators, expected artifact checksum, cache/negative state, and integrity status |
| `ansible_group_binding` | Group collection-version binding to one ordered source member/revision/filename/checksum; the version reference is nullable until the bound proxy artifact is materialized, so metadata and artifact cannot diverge without forcing metadata reads to download blobs |
| `ansible_registry_lease` | Shared expiring lease and fencing token for publication, proxy materialization, revalidation, and takeover across replicas |

### Permission Layer

| Table | Responsibility |
| --- | --- |
| `security_user` | User and password hash. `(source, user_id)` preserves Nexus realm/source semantics. Local source is currently normalized to `Local` |
| `security_role` | Role definition. `source` preserves UI/migration semantics. Local roles are currently normalized to `Local` |
| `security_privilege` | Privilege definition. `properties_json` preserves Nexus privilege attributes. `read_only` marks built-in contributor privileges |
| `security_role_privilege` | Role-to-privilege mapping |
| `security_role_inheritance` | Role inheritance |
| `security_user_role` | User-to-role mapping |
| `security_anonymous_config` | Anonymous access configuration. Preserves Nexus enabled, userId, realmName, and stores mapped `user_source` |
| `security_realm_config` | Nexus realm order configuration |
| `security_realm` | Enabled state, name, priority, and provider attributes for local, LDAP, and OIDC realms |
| `security_repository_target` | Content selector / repository target compatibility definition, used at runtime for CSEL expression matching in repository-content-selector privileges |
| `api_key` | API key compatibility data. Uses `domain + api_key_hash` to align with Nexus uniqueness semantics. Raw tokens are stored only as encrypted payloads |
| `security_audit_log` | Audit log for admin API and security-related operations, indexed by time, actor, path, outcome/status, and method |

### Migration Layer

| Table | Responsibility |
| --- | --- |
| `migration_job` | Input, options, state, and summary for one migration job |
| `migration_checkpoint` | Mapping from OrientDB RID to target table primary key, used for resume and idempotent import |
| `migration_validation_result` | Count, checksum, and sampled protocol validation result |
| `repository_data_migration_repository` | Repository-level progress, cursor, statistics, claim state, and options for repository data migration |
| `repository_data_migration_asset` | Asset-level task, retry state, source metadata, and target component/asset/blob references for repository data migration |

## Key Design Decisions

1. `component.coordinate_hash` is calculated by the application as SHA-256 over `repository + namespace + name + version`, solving MySQL long-string composite unique index limits.
2. `asset.path_hash` is calculated by the application from the protocol path inside the repository, expressing Nexus asset path uniqueness.
3. `asset_blob.blob_ref_hash` and `object_key_hash` are calculated by the application to avoid unique indexes on very long URLs/keys. `idx_asset_blob_reusable` supports blob reuse by sha256/size, and `idx_asset_blob_usage` supports blob store usage statistics.
4. `attributes_json` preserves raw Nexus attributes, while explicit columns support high-frequency queries. During migration, the first goal is to avoid losing information, then gradually converge the model.
5. `browse_node` and `component_search` are rebuildable tables. Migration failure in these tables does not affect protocol download correctness.
6. Metadata and repository index rebuild must be coordinated through MySQL marker queues, not a single JVM in-memory queue. Multi-replica workers claim tasks concurrently with `FOR UPDATE SKIP LOCKED`, and tasks remain idempotent.
7. Node-local catalog caches for permissions, repository directory data, blob stores, and similar state are only rebuildable hot caches. The shared source of truth is MySQL, and cross-replica invalidation uses the `cache_version` watermark.
8. HTTP sessions use Spring Session JDBC. `auth_ticket` only carries short-lived login/authentication flow state and does not replace session or token data.
9. The permission model is compatible with Nexus user/role/privilege semantics. Runtime repository authorization evaluates `nexus:repository-view:<format>:<repository>:<actions>`, `nexus:repository-admin:<format>:<repository>:<actions>`, `nexus:repository-content-selector:<selector>:<format>:<repository>:<actions>`, and repository target rules.
10. Maven repository format is stored as Nexus-native `maven2` in JSON, database, permissions, and CSEL. Historical `maven` values are normalized by V11.
11. Local security source currently uses `Local`; old `default` and Nexus realm names are normalized in migration and REST/UI compatibility layers.
12. Empty databases are seeded by V7/V9/V10 with Nexus 3.29.x built-in security contributor facts, including application management privileges, repository view/admin wildcard privileges, per-format/per-repository dynamic privileges, blob store privileges, the default anonymous user, and anonymous configuration. Idempotent inserts do not overwrite migrated or manually modified privileges with the same name.
13. `security_realm.attributes_json` carries external provider configuration: LDAP URL, bind DN, user/group search configuration, and OIDC issuer, JWKS, audience, clientId/clientSecret, authorization/token endpoint, redirect URI, scope, and claim mapping. After external realm authentication succeeds, external users are upserted by source, and LDAP/OIDC group names or token roles participate in permission evaluation as role names.
14. Anonymous access uses the identity and roles specified by `security_anonymous_config`; it does not bypass the permission model with a global read-only allow. Nexus default `NexusAuthorizingRealm/anonymous` maps to local `Local/anonymous`.
15. Migration is treated as a recoverable product feature: `migration_checkpoint` handles idempotent import of configuration/security objects, and `repository_data_migration_*` handles discover, claim, retry, resume, and progress statistics for repository asset data migration.
16. V24 normalizes historical `jindo` / `jindo-oss` blob store engines to `oss-native`. The source of truth for large blobs remains OSS/S3/File blob store; MySQL stores only metadata, state, indexes, and references.
17. V28 adds `pub_upload_session` because Pub publish is a multi-request protocol. Session state, temporary blob references, parsed metadata, and finalization status must survive replica switches and restarts; archive bytes still live in blob storage and are not stored in MySQL.
18. V35 adds Ansible Galaxy protocol state. Immutable collection identity is protected by explicit unique constraints, tasks and leases survive process loss, group bindings keep metadata/checksum/artifact on one member, and archive/signature bytes remain in blob storage.
