# Backup And Restore Guide

kkrepo stores metadata and coordination state in MySQL or PostgreSQL, while artifact bytes live in blob storage. A recoverable production deployment must back up both.

## What Must Be Backed Up

Back up the selected relational database:

- Repository definitions and group membership.
- Components, assets, browse nodes, search indexes, and blob references.
- Users, roles, privileges, realms, API keys, sessions, and audit logs.
- Migration jobs, migration checkpoints, and retry state.
- Cache watermarks, marker tables, and background worker cursors.

Back up blob storage:

- Hosted package content.
- Cached proxy content that you choose to preserve.
- Migrated blob objects.
- Generated metadata assets stored as blobs.

The database contains references and checksums. Blob storage contains the actual bytes. Restoring only one side is not a complete recovery.

## Backup Strategy

Recommended strategy:

- Use automated database backups with point-in-time recovery when available.
- Use OSS/S3 versioning or provider snapshots when recovery requirements demand it.
- Keep backups in a separate account, bucket, region, or failure domain when possible.
- Encrypt backups at rest.
- Test restore regularly.
- Document RPO and RTO expectations.

For small deployments, daily database backups plus blob-storage versioning may be enough. For heavy CI/CD infrastructure, use shorter backup intervals and explicit restore drills.

## Consistency Model

A clean backup point should include:

- Database snapshot at time `T`.
- Blob storage state containing all objects referenced by the database at time `T`.

Blob storage may contain extra unreferenced objects. That is usually acceptable because the database controls visible repository content. Missing referenced blob objects are not acceptable and will cause download failures.

If your object storage supports versioning, restoring the database to time `T` while keeping blob versions at or after `T` is usually safer than restoring blob storage to an earlier point.

## MySQL Backup Example

Logical backup:

```bash
mysqldump \
  --single-transaction \
  --routines \
  --triggers \
  --databases kkrepo \
  > kkrepo-$(date +%Y%m%d%H%M%S).sql
```

Managed MySQL services often provide snapshot and point-in-time recovery. Prefer managed PITR for production when available.

## PostgreSQL Backup Example

Logical backup in custom format:

```bash
pg_dump --format=custom --dbname='postgresql://kkrepo@db:5432/kkrepo' \
  --file="kkrepo-$(date +%Y%m%d%H%M%S).dump"
```

Restore it into an empty target with `pg_restore --clean --if-exists --dbname=<target> <dump>`. Managed PostgreSQL snapshots/PITR are preferred for production when available.

## Blob Storage Backup Options

Choose one or more:

- Provider bucket versioning.
- Provider backup or replication.
- Cross-region replication.
- Scheduled object copy to another bucket/account.
- Lifecycle policy that preserves deleted versions long enough for recovery.

Do not configure lifecycle deletion so aggressively that recently referenced objects can disappear before database backup retention expires.

## Restore Order

Recommended restore sequence:

1. Stop kkrepo replicas or block repository write traffic.
2. Restore the selected database to the recovery point.
3. Restore or verify blob storage so all referenced objects exist.
4. Start one kkrepo replica.
5. Verify `/actuator/health`.
6. Verify admin UI and browse UI.
7. Pull representative artifacts from key repositories.
8. Run protocol-specific smoke checks.
9. Start remaining replicas.

If object storage already contains a superset of needed objects, you may only need to restore the database and verify blob availability.

## Validation After Restore

Check:

- `/actuator/health` is `UP`.
- Admin login works.
- Repository list and blob store list load.
- Key Maven/npm/PyPI/Go/Helm/Cargo/Pub/Composer/Terraform/Docker/OCI/NuGet/RubyGems/Yum/Raw packages or images can be downloaded; for Composer, verify `packages.json`, p2 metadata, and the dist archive; for Terraform, verify module/provider metadata, the platform archive, SHA256SUMS, and detached signature through the group.
- Hosted upload works on a test repository.
- Browse/search returns expected assets.
- Migration pages do not show stuck running jobs from the old environment unless intentionally resumed.
- Audit log and security settings are visible.

If browse or search looks stale but direct artifact download works, use rebuild or maintenance flows when available rather than manually editing the database.

## Disaster Recovery Drill

At least periodically:

1. Create a fresh environment.
2. Restore a production-like MySQL or PostgreSQL backup.
3. Point it to a restored or replicated blob store.
4. Start kkrepo with the same encryption secrets.
5. Verify representative client operations.
6. Record time taken and gaps found.

The encryption secrets must match the source environment. Without them, encrypted blob-store credentials, realm secrets, and API key payloads cannot be decrypted.

## Backup During Migration

Before a Nexus migration:

- Back up target kkrepo MySQL.
- Back up or snapshot the target blob store if it already contains important data.
- Export or record source Nexus version and repository list.
- Keep source Nexus unchanged until the final cutover is accepted.

During migration:

- Use `Run preflight` before metadata migration.
- Use `Sync metadata` before `Sync packages`.
- Keep checksum validation enabled unless you have a specific reason to disable it.
- Monitor failed assets and retry after fixing root causes.

After migration:

- Run a final incremental sync before cutover.
- Back up kkrepo after acceptance.
- Keep the source Nexus available for a rollback window when practical.

## Common Restore Problems

| Symptom | Likely cause | Action |
| --- | --- | --- |
| Artifact metadata exists but download fails | Blob object missing from restored storage | Restore missing object versions or restore to a later blob backup |
| Admin login fails after restore | Wrong database, wrong realm state, or missing encryption secret | Verify datasource and secrets |
| Blob store credentials cannot be read | `KKREPO_CREDENTIAL_SECRET` changed | Restore the original secret or reconfigure blob stores |
| API keys no longer work | `KKREPO_API_KEY_PAYLOAD_SECRET` changed or database restored to old state | Restore the original secret or reissue API keys |
| New uploads fail | Blob store permissions or bucket policy changed | Run blob store health probes and verify IAM policy |
| Migration job appears stuck | Database restored while a job was running | Review migration state and retry failed or unfinished work from the UI |

## What Not To Do

- Do not restore only blob storage and assume repository metadata follows.
- Do not restore only MySQL if referenced blob objects were deleted.
- Do not rotate encryption secrets as part of an emergency restore.
- Do not manually delete rows from repository, asset, or blob tables without understanding foreign keys and rebuild behavior.
- Do not publish full backup dumps in public issues.

For security-sensitive restore incidents, follow [SECURITY.md](../../SECURITY.md).
