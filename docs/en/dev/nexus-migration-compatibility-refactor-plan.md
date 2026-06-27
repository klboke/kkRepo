# Nexus Compatibility Migration Refactor Plan

This document refines the migration design for supporting multiple Nexus Repository generations. It is not limited to Cargo. Cargo is only the first strong signal that the existing migration path, which was built mainly around older OrientDB-backed Nexus instances, is not enough for current Nexus versions that use H2 or PostgreSQL datastore metadata.

The migration product must treat Nexus as the compatibility reference and must not infer behavior from version numbers alone. A source Nexus version is useful as a risk label, but the migration path should be selected by detected source capabilities, schema shape, repository recipes, API availability, and real source behavior.

## Problem Statement

kkrepo needs to migrate repositories, blobs, users, groups, roles, privileges, tokens, and validation state from Nexus instances across different storage eras:

- Older Nexus 3 versions backed by OrientDB.
- Newer Nexus versions backed by the datastore model, usually embedded H2 or external PostgreSQL.
- Format implementations that moved between Pro-only, Community, feature-flagged, or unstable states.
- Source instances whose public REST API, Script REST API, internal Java services, and database schema do not expose the same migration surface.

The current migration approach can work for the historical OrientDB-centered path, but it should not be expanded by adding fragile `if version >= X` branches. Newer Nexus versions can change schema, edition gates, script behavior, and repository format internals without changing the high-level repository URL shape.

## Current Evidence

The local Nexus 3.77.2 reference instance was probed through the Script REST API after enabling `nexus.scripts.allowCreation=true`.

Observed behavior:

- Script `/run` requires `Content-Type: text/plain`; an empty body is accepted, while missing body metadata can return `415 Unsupported Media Type`.
- Nexus reports version `3.77.2-02` Community Edition.
- The default datastore is named `nexus` and uses JDBC `H2 2.3.232` with URL `jdbc:h2:file:/nexus-data/db/nexus`.
- Repository table shape includes `ID`, `NAME`, `RECIPE_NAME`, `ONLINE`, `ROUTING_RULE_ID`, and `ATTRIBUTES`.
- Cargo metadata is stored in datastore tables such as `cargo_content_repository`, `cargo_component`, `cargo_asset`, `cargo_asset_blob`, and `cargo_browse_node`.
- `cargo_asset_blob.checksums` is JSON data, but H2 can return it as `byte[]` through Groovy `getObject()`, so probes must decode values defensively.
- Repository format and type must not be assumed as direct SQL columns on the `repository` table. Use `RECIPE_NAME`, `ATTRIBUTES`, or `RepositoryManager` through Script API.

Migration implication: a datastore-era source needs a different read strategy from the OrientDB path. The right abstraction is not "Cargo special handling" but a source profiling and adapter framework that can cover every repository format and every Nexus storage generation.

## Design Principles

1. Probe capabilities first.
   The migration should detect the real source surface before planning work. Version numbers can warn or choose default expectations, but they must not be the only router.

2. Make the migration plan explicit.
   Preflight should produce a deterministic `SourceProfile` and `MigrationPlan` that the user can review before execution.

3. Fail closed on unknown source shapes.
   If a repository format, security store, token store, blob reference model, or checksum model cannot be identified, mark that part unsupported or requiring manual action instead of guessing.

4. Keep OrientDB behavior stable.
   Existing OrientDB migration behavior should be preserved behind an adapter while the new framework is introduced.

5. Prefer source APIs over direct database reads when they are reliable.
   Script API and internal Nexus services can hide schema differences. Direct database reads are allowed only when the profile proves the schema and data model are understood.

6. Record evidence for resume.
   Store the profile hash, plan hash, probe timestamps, source version, datastore type, and adapter choices in kkrepo MySQL. Resume should re-run preflight or require confirmation if the source profile changed.

7. Treat migration as a product workflow.
   Every step needs dry-run, resume, checksum verification, audit, and user-visible reporting.

## Proposed Architecture

### SourceProfile

`SourceProfile` is the normalized source detection result. It should be persisted with each migration job.

Recommended fields:

| Field | Description |
| --- | --- |
| `nexusVersion` | Reported Nexus version, such as `3.77.2-02` |
| `edition` | OSS, Community, Pro, or unknown |
| `buildRevision` | Build or commit identifier when available |
| `scriptApi` | Disabled, creatable, runnable, content-type behavior, and cleanup behavior |
| `metadataEngine` | `ORIENTDB`, `DATASTORE_H2`, `DATASTORE_POSTGRESQL`, or `UNKNOWN` |
| `repositoryModel` | Orient bucket/asset model, datastore content model, or unknown |
| `securityModel` | Orient security, datastore security, `api_key`, `api_key_v2`, or unknown |
| `blobStoreTypes` | File, S3, Azure, Google, or unknown blob store types |
| `enabledFormats` | Formats and recipes discovered from Nexus |
| `repositoryRecipes` | Repository name, recipe, online state, attributes, and blob store link |
| `restCapabilities` | Available REST endpoints and observed status behavior |
| `schemaFingerprints` | Table, column, index, and selected metadata fingerprints |
| `formatCapabilities` | Format-specific capabilities, such as Maven metadata, npm tokens, Cargo sparse index, Docker connectors |
| `warnings` | Risk notes surfaced to the user |
| `unsupportedItems` | Items that must not be migrated automatically |

### Probe Pipeline

The probe pipeline should run in layers. Each layer can contribute facts and warnings to `SourceProfile`.

1. REST baseline probe
   - Read status and version endpoints.
   - List repositories, blob stores, realms, and capabilities when available.
   - Detect which REST endpoints are accessible with the supplied credentials.

2. Script capability probe
   - Check whether script creation is enabled.
   - Create, run, and delete a tiny smoke script.
   - Record required request headers, including `Content-Type: text/plain` for `/run` when observed.
   - Prefer read-only scripts for all later probes.

3. Metadata engine probe
   - Try Nexus datastore services first on newer versions.
   - Detect default datastore name, JDBC product, JDBC URL shape, and database major version.
   - Fall back to OrientDB service detection for older instances.
   - Never assume external PostgreSQL credentials are available outside Nexus.

4. Repository model probe
   - Read repository recipes and attributes through `RepositoryManager`.
   - Fingerprint repository tables and content tables.
   - Detect whether format/type is derived from `RECIPE_NAME`, attributes, or another model.

5. Security and token probe
   - Detect users, groups, roles, privileges, realms, API keys, user tokens, and CI tokens.
   - Separate readable security metadata from non-exportable secrets.
   - Mark password hashes, encrypted secrets, and token material according to whether they can be migrated, recreated, or require user action.

6. Blob and checksum probe
   - Detect blob store types, asset-to-blob references, checksum fields, size fields, soft-delete markers, and direct asset download fallback.
   - Verify at least one sample asset per supported format when available.

7. Format-specific probe
   - Maven: group/artifact/version layout, `maven-metadata.xml`, checksums, snapshots.
   - npm: package metadata, tarballs, dist-tags, scoped packages, token behavior.
   - PyPI: simple index, metadata, file hashes.
   - Docker: manifests, blobs, tag lists, connector behavior.
   - Cargo: sparse `config.json`, crate index files, `.crate` blobs, yanked state.
   - Other formats follow the same pattern before automatic migration is enabled.

### Adapter Layers

The migration implementation should be split into adapter contracts instead of version-condition blocks.

Recommended contracts:

- `NexusSourceProbe`: collects facts and builds `SourceProfile`.
- `CompatibilityRule`: evaluates whether a profile supports a migration capability.
- `MigrationPlanBuilder`: turns a profile and user-selected scope into a concrete plan.
- `NexusMigrationAdapter`: top-level adapter for a source family.
- `RepositoryConfigExporter`: exports repository configuration and format attributes.
- `RepositoryContentExporter`: exports components, assets, paths, blob references, and checksums.
- `SecurityExporter`: exports users, groups, roles, privileges, and token metadata.
- `BlobReader`: reads blob bytes from direct blob store access or Nexus HTTP fallback.
- `FormatMigrationAdapter`: format-specific mapper and validator.

Initial source-family adapters:

| Adapter | Source profile match | Responsibility |
| --- | --- | --- |
| `OrientDbNexusAdapter` | `metadataEngine=ORIENTDB` | Preserve existing OrientDB migration behavior |
| `DatastoreH2NexusAdapter` | `metadataEngine=DATASTORE_H2` | Use Script/Internal services and H2 schema fingerprints |
| `DatastorePostgresqlNexusAdapter` | `metadataEngine=DATASTORE_POSTGRESQL` | Use Script/Internal services and PostgreSQL schema fingerprints |
| `RestOnlyNexusAdapter` | Script disabled and no DB access | Config and content migration through public REST or repository HTTP only |

Format adapters should be composed with source-family adapters. For example, Cargo on H2 is `DatastoreH2NexusAdapter + CargoMigrationAdapter`, while Maven on OrientDB is `OrientDbNexusAdapter + MavenMigrationAdapter`.

## Migration Plan Model

Preflight should produce a plan with one status per migration area.

Recommended states:

| State | Meaning |
| --- | --- |
| `FULL` | Config, metadata, blobs, permissions, validation, and cutover checks are supported |
| `CONFIG_ONLY` | Repository config can be migrated, but content cannot be safely exported |
| `DATA_ONLY` | Content can be imported, but some permissions or tokens need manual recreation |
| `UNSUPPORTED` | Automatic migration is blocked by unknown or unsupported source behavior |
| `NEEDS_MANUAL_ACTION` | The migration can continue only after an explicit user step |

Each plan item should include:

- Source repository or security area.
- Selected source adapter and format adapter.
- Read method: Script service, direct DB, public REST, repository HTTP, or blob store.
- Write method into kkrepo.
- Checksum and validation method.
- Resume key.
- Known unsupported fields.
- Operational risk and user-facing warning.

## User Interaction Model

The normal user flow should not ask users to choose whether the source is above or below a version threshold. Instead:

1. User provides source URL, credentials, and optional blob-store access.
2. kkrepo runs preflight probes.
3. UI shows detected facts: version, edition, datastore, script status, repository recipes, security/token support, blob store support, and warnings.
4. UI shows the proposed migration plan and support state for each repository.
5. User confirms scope, dry-run, skip policies, and any manual actions.
6. Execution records progress and validation results in MySQL.

Advanced overrides can exist for support and emergency cases, but they should be explicit and audited:

- Force source family adapter.
- Disable a specific repository or token migration area.
- Allow config-only migration for unsupported content.
- Use repository HTTP download fallback instead of direct blob reads.

Version-based presets can help initialize expectations, but they must remain advisory:

- `<= 3.70.x`: expect OrientDB, verify through probe.
- `>= 3.71.x`: expect datastore, verify H2 or PostgreSQL through probe.
- `>= 3.77.x`: expect Community access to previously Pro-only formats, verify actual recipes and UI/API exposure.

## Execution Semantics

Migration execution must stay idempotent and safe for multi-replica kkrepo deployments.

- Store job, profile, plan, phase, checkpoint, and validation state in MySQL.
- Use stable source identity keys for repositories, components, assets, and privileges.
- Use explicit unique constraints and upserts for imported objects.
- Store blob bytes in OSS/S3 or File blob store only, never in MySQL.
- Treat in-process caches as rebuildable accelerators only.
- Every phase supports dry-run, retry, resume, and audit reporting.
- Resume validates profile hash and plan hash before continuing.
- Cutover validation compares source and target counts, selected metadata, blob sizes, checksums, and client-visible URLs.

## Development Phases

### Phase 1: Inventory and Boundaries

- Inventory current migration code paths, script templates, OrientDB assumptions, direct SQL assumptions, token assumptions, and checksum validation.
- Split reusable script snippets into named probe/exporter templates.
- Document the exact current OrientDB-supported behavior before refactoring.
- Add test fixtures for existing OrientDB migration behavior so the refactor does not regress it.

### Phase 2: SourceProfile and Probe Framework

- Add `NexusSourceProfile` model, persistence, and JSON report output.
- Add REST baseline and Script API smoke probes.
- Add metadata engine probes for OrientDB, H2, and PostgreSQL.
- Add repository model, blob model, and security model fingerprint probes.
- Expose preflight output in migration CLI/API/UI before any import starts.

### Phase 3: Compatibility Rules and Plan Builder

- Add compatibility rules that map `SourceProfile` to supported migration capabilities.
- Produce a deterministic `MigrationPlan`.
- Show `FULL`, `CONFIG_ONLY`, `DATA_ONLY`, `UNSUPPORTED`, and `NEEDS_MANUAL_ACTION` per repository and security area.
- Persist profile hash and plan hash.

### Phase 4: Adapter Refactor for Existing Migration

- Move current OrientDB logic behind `OrientDbNexusAdapter`.
- Keep the existing successful migration path behaviorally unchanged.
- Route all migration execution through the new plan model.
- Add regression tests around OrientDB repository, asset, permission, and checksum migration.

### Phase 5: Datastore Source Adapters

- Implement `DatastoreH2NexusAdapter` using Script/Internal services first and schema fingerprints second.
- Implement `DatastorePostgresqlNexusAdapter` with the same contract.
- Add safe JSON/byte decoding for datastore columns such as checksum metadata.
- Avoid relying on table columns that are not proven by profile fingerprints.

### Phase 6: Format-Specific Expansion

- Enable Maven, npm, PyPI, Go, Helm, NuGet, RubyGems, Yum, Docker, and Cargo only after each format has probe evidence and validation logic.
- Start with config migration where content export is not yet safe.
- Add content migration once asset/blob/checksum mapping is proven.
- For Cargo specifically, keep migration disabled until Nexus 3.77.x+ H2 and PostgreSQL reference instances prove sparse index, crate blob, token, permission, checksum, and cutover behavior.

### Phase 7: Reference Matrix and Automation

Maintain a compatibility matrix with disposable Nexus reference instances:

| Reference | Purpose |
| --- | --- |
| Nexus 3.29.2 OrientDB | Existing historical migration baseline |
| Nexus 3.73.0 datastore/H2 | Datastore-era reference where Cargo may not be exposed in Community |
| Nexus 3.77.x Community/H2 | Current Community reference for previously Pro-only formats such as Cargo |
| Nexus 3.77.x or newer PostgreSQL | External datastore behavior |
| Optional Pro or feature-flagged instance | Edition-gated behavior when available |

Automated checks should include:

- Probe output snapshot tests.
- Migration plan snapshot tests.
- Dry-run tests.
- Small real import tests per format.
- Checksum and client-visible URL validation.
- Resume and profile-drift tests.

## Acceptance Criteria

- Preflight produces a deterministic source profile without importing data.
- The same profile and user scope always produce the same migration plan.
- Existing OrientDB-supported migrations continue to work through the new adapter path.
- H2 and PostgreSQL source detection works through Nexus Script API without requiring direct external database credentials.
- Every repository and security area receives a clear support state and reason.
- Unknown schemas, unknown token stores, and unsupported formats fail closed.
- Resume refuses to continue silently when the source profile changed.
- Migration reports include counts, skipped items, warnings, checksum results, and manual actions.
- Documentation warns that Nexus Script API should be enabled only during the migration window and disabled after probing/export.

## Open Questions

- Which Nexus versions should be part of the minimum automated reference matrix for every release?
- Which token types can be migrated as reusable secret material, and which must be recreated in kkrepo?
- Should direct blob-store reads be required for large migrations, or should repository HTTP fallback be the default for safety?
- How much datastore schema fingerprint drift should be tolerated before blocking migration?
- Which migration areas need product UI first, and which can start as CLI/admin API only?
