# Nexus Migration Playbook

This playbook is a practical checklist for moving from an existing Nexus instance to kkrepo. It complements the [Nexus Migration Guide](nexus-migration-guide.md), which documents the product flow in more detail.

## Migration Strategy

Recommended strategy:

1. Prepare kkrepo in parallel with the source Nexus.
2. Run metadata preflight.
3. Migrate metadata.
4. Sync repository metadata.
5. Sync package blobs.
6. Repeat incremental syncs during the cutover window.
7. Switch DNS/load balancer/client traffic.
8. Verify and keep rollback options for a defined window.

The best migration is boring: preflight catches surprises, incremental sync reduces cutover time, and client configuration can stay pointed at the same `/repository/<repo>/...` paths after the domain moves.

## Phase 0: Scope And Inventory

Collect:

- Source Nexus version.
- Repository list, formats, recipes, and names.
- Blob store list and approximate sizes.
- Hosted repositories that must be migrated.
- Proxy repositories that should be migrated as historical cache or backup data.
- User/role/privilege model.
- LDAP/OIDC/anonymous settings.
- API key and token usage.
- Critical CI/CD clients and their configuration.
- Current domain names and reverse proxy rules.
- Expected cutover window and rollback window.

Decide:

- Which repositories are in scope.
- Which unsupported formats are out of scope.
- Whether proxy repositories should be migrated or allowed to refill from upstream.
- Which users may need password reset after migration.
- Which validation clients represent success.

## Phase 1: Prepare Target kkrepo

Set up kkrepo with production-like settings:

- External MySQL.
- OSS/S3-compatible blob storage.
- Stable `KKREPO_CREDENTIAL_SECRET`.
- Stable `KKREPO_API_KEY_PAYLOAD_SECRET`.
- HTTPS reverse proxy.
- Monitoring on the management port.
- Backups for MySQL and blob storage.

Before migrating, verify:

```bash
curl -i http://<target-management-host>:8081/actuator/health
```

Log in to `/admin/` and make sure the target instance is healthy.

## Phase 2: Source Nexus Preparation

On the source Nexus:

- Confirm administrator credentials for migration.
- Enable Script REST API script creation when possible.
- Confirm source repositories are online.
- Stop or schedule heavy maintenance tasks during migration windows if they interfere with reads.
- Keep source Nexus available until rollback window closes.

Script REST API is important because some Nexus data is not available through regular REST APIs. If script capability is disabled, migration can still report what cannot be compensated, but local password hash compensation and repository asset discovery may be limited.

## Phase 3: Metadata Preflight

In kkrepo `/admin/`:

1. Open `Nexus Metadata`.
2. Fill in source URL, username, and password.
3. Run `Run preflight`.
4. Review blocking issues and warnings.

Resolve:

- Source URL or credential errors.
- Missing source permissions.
- Script API disabled errors.
- Unsupported repository formats.
- Blob store or repository configuration that cannot be mapped.
- Users that require password reset.

Do not proceed to cutover until preflight output is understood.

## Phase 4: Metadata Migration

Run `Run migration` on the `Nexus Metadata` page.

Expected migrated categories:

- Users.
- Roles.
- Privileges.
- Blob stores.
- Repository definitions.
- Security compatibility data that can be read from source Nexus.

After metadata migration:

- Check repository definitions in Admin UI.
- Check users and roles.
- Check anonymous access settings.
- Check LDAP/OIDC realm settings and update secrets if necessary.
- Create or verify the target blob stores.

Some secrets may need to be re-entered because source Nexus may not expose plaintext credentials.

## Phase 5: Repository Data Metadata Sync

Open `Nexus Repository Data`.

For the first scan:

- Leave `Metadata since` empty.
- Set page size and concurrency conservatively.
- Specify proxy repositories only if you intentionally want to migrate proxy cache data.
- Run `Sync metadata`.

`Sync metadata` records what needs to be migrated. It does not download all blobs.

Review:

- Total assets discovered.
- Repositories included.
- Unsupported or failed items.
- Estimated migration size.

## Phase 6: Package Blob Sync

Run `Sync packages`.

Recommendations:

- Keep checksum validation enabled.
- Start with moderate concurrency, such as `8`.
- Increase concurrency only after checking source Nexus, network, MySQL, and object-storage pressure.
- Monitor failed assets and retry after fixing root causes.

Common causes of failures:

- Source Nexus read timeout.
- Source permission missing.
- Object storage throttling.
- Target blob store misconfiguration.
- Artifact deleted or changed during migration.

Use `Retry failed` after fixing the cause.

## Phase 7: Incremental Sync

During the cutover window, repeat:

1. Set `Metadata since` to a time before the previous sync started.
2. Run `Sync metadata`.
3. Run `Sync packages`.
4. Review failures.

Use overlap in `Metadata since` to avoid missing updates during clock skew or long-running writes.

If the source Nexus is still receiving writes, keep the final incremental sync close to the DNS/load-balancer cutover.

## Phase 8: Cutover

Before cutover:

- Announce a short freeze window if possible.
- Stop writes to source Nexus.
- Run a final incremental `Sync metadata`.
- Run a final `Sync packages`.
- Verify no failed assets remain for critical repositories.
- Verify representative clients against kkrepo directly.

Cutover options:

- Move the original Nexus domain to kkrepo.
- Change load balancer upstreams.
- Update client config if domain preservation is not possible.

After cutover:

- Watch repository request metrics.
- Watch application logs.
- Run representative CI pipelines.
- Pull and push test packages for key formats.
- Keep source Nexus read-only or standby during rollback window.

## Validation Checklist

Validate:

- Maven dependency resolve and deploy.
- npm install and publish.
- PyPI pip install and twine upload.
- Helm repo update and chart upload.
- Dart / Pub `dart pub get`, `flutter pub get`, and `dart pub publish`.
- NuGet restore and package push.
- RubyGems install and gem push.
- Yum install and RPM upload.
- Raw upload/download.
- Cargo / Rust sparse registry config, index entry, `.crate` download, and checksum after datastore H2/PostgreSQL Cargo migration.
- Dart / Pub package metadata, `version.json`, archive download, `archive_sha256`, and publish token behavior after Nexus 3.92.0 Pub migration.
- Admin login.
- Browse/search.
- User roles and repository permissions.
- CI token/API key behavior.
- Audit log visibility.

Use [Client Recipes](client-recipes.md) for command examples.

## Rollback Plan

Define rollback before cutover:

- DNS/load-balancer rollback target.
- Source Nexus write policy during rollback window.
- Whether writes to kkrepo after cutover must be replayed to Nexus.
- How long source Nexus remains available.
- Who approves rollback.

Simple rollback is easiest when the cutover window is short and source Nexus remains unchanged or read-only.

If users publish new packages to kkrepo after cutover, rollback becomes a data reconciliation problem. Decide whether that is acceptable before opening writes.

## Post-Migration Cleanup

After the rollback window:

- Back up kkrepo MySQL and blob storage.
- Confirm source Nexus is no longer receiving traffic.
- Archive source Nexus configuration and migration reports.
- Decommission source Nexus only after business sign-off.
- Review users, roles, and tokens.
- Document known compatibility differences or follow-up issues.

## When To Stop And Investigate

Stop the migration and investigate if:

- Preflight reports unsupported critical repositories.
- Source credentials cannot read required metadata.
- Blob-store health checks fail.
- Checksum validation repeatedly fails for the same repository.
- Security roles or anonymous settings do not match expectations.
- Critical CI clients cannot authenticate or resolve packages in staging.

Do not force cutover through unexplained permission or checksum failures.
