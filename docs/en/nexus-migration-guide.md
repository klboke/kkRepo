# Nexus Migration Guide

This document describes prerequisites, execution order, incremental migration, and domain cutover when migrating from Nexus Repository to kkrepo.

kkrepo is compatible with Nexus's `/repository/<repo>/...` URL layout, client protocol behavior, and permission/authentication model. After migration, only point the original Nexus domain to kkrepo. Maven, npm, PyPI, Go, Helm, Cargo/Rust, Dart/Pub, Composer/PHP, Terraform, NuGet, RubyGems, Yum, and other migrated non-Docker client configurations do not need to change. Docker / OCI uses Registry HTTP API V2 `/v2/...` routes; preserve repository names and connector/path-based routing when cutting Docker clients over.

Cargo / Rust repositories also use `/repository/<repo>/...` sparse registry URLs in kkrepo. Hosted Cargo repository data participates in the same migration flow for datastore-era Nexus H2/PostgreSQL sources when preflight proves the Cargo content model fingerprint.

Dart / Pub repositories also use `/repository/<repo>/...` hosted URLs in kkrepo. Nexus 3.92.0 Pub hosted repository data participates in the same migration flow when preflight proves the Pub content model; explicitly selected Pub proxy cache data is migrated only when the plan is `FULL`.

Composer / PHP keeps the `/repository/<repo>/...` Composer 2 URL shape. Native Nexus Composer is a 3.75.0+ Pro proxy-only capability, so migration accepts only native Nexus Composer proxies and preserves proxy semantics. Configuration is migrated without cache content unless the administrator explicitly selects the repository under `Optional proxy repositories`. kkRepo-native Composer hosted/group repositories are not inferred from the Nexus source.

Terraform keeps the Nexus `/repository/<repo>/v1/modules/...` and `/v1/providers/...` service bases configured in Terraform CLI `host.services`. Metadata migration recognizes native `terraform-hosted`, `terraform-proxy`, and `terraform-group` recipes. Repository-data migration rebuilds hosted module/provider state, provider platforms, SHA256SUMS, and kkrepo signing metadata. Proxy/group configuration also migrates. When an administrator explicitly selects a native Nexus Terraform proxy and the source profile plans it as `FULL`, a separate cache writer restores protocol-recognized module/provider archives at their Nexus public paths. It does not create hosted publication or signing state; target metadata access reconstructs remote routes, validators, checksum manifests, and signature snapshots from the configured upstream.

## Migration Flow Overview

Migration runs in this order:

| Order | Admin entrypoint | Operation | Result |
| --- | --- | --- | --- |
| 1 | Source Nexus | Enable Script REST API script creation | kkrepo can read source data that regular REST APIs cannot directly provide |
| 2 | Nexus Metadata | Run `Run preflight` first, then run `Run migration` after blocking issues are resolved | Migrates system metadata such as users, roles, privileges, blob stores, and repository definitions |
| 3 | Nexus Repository Data | Run `Sync metadata` | Scans source Nexus hosted repository assets and creates repository data migration tasks |
| 4 | Nexus Repository Data | Run `Sync packages` | Migrates real blob/package data to kkrepo |
| 5 | Nexus Repository Data | Repeat incremental `Sync metadata` and `Sync packages` before go-live as needed | Migrates data added or updated during the cutover window |
| 6 | DNS or reverse proxy | Point the original Nexus domain to kkrepo | Clients switch to kkrepo transparently |

## Before Migration

- Confirm kkrepo is deployed and connected to production MySQL.
- Create a blob store named `default` in kkrepo first. OSS/S3 blob store is recommended for production.
- Confirm the source Nexus account has migration permissions. The source Nexus admin account is recommended.
- Enable script capability on the source Nexus, as described in "Source Script Capability Configuration" below.
- Keep source Nexus accessible during the migration window so multiple incremental sync rounds and follow-up checks can be performed.

## Metadata Migration

Open the `Nexus Metadata` page in the kkrepo `/admin/` console and fill in source Nexus URL, username, and password.

1. Click `Run preflight` to check script capability, account permissions, repository/blob store/security configuration, and unsupported items.
2. Resolve blocking issues based on preflight results, such as disabled script capability, insufficient account permissions, or local user password hashes that cannot be compensated.
3. Click `Run migration` to migrate users, roles, privileges, realms, anonymous configuration, blob stores, repository definitions, and other system metadata.

Repository data migration depends on target repositories and blob stores already existing, so complete `Nexus Metadata` migration before running `Nexus Repository Data` migration.

## Repository Data Migration

Repository data migration runs from the `Nexus Repository Data` page in the kkrepo `/admin/` console and has two steps:

1. Migrate repository metadata first: scan source Nexus hosted repository component, asset, path, size, content-type, timestamp, blob reference, and related metadata, then create migration tasks in kkrepo MySQL.
2. Migrate real blob data: download source Nexus asset content according to migration tasks and write it to the target blob store in kkrepo.

Cargo / Rust hosted repository data is migrated by this flow for Nexus 3.77.x+ datastore-era H2/PostgreSQL sources whose source profile reports a supported Cargo content model. Dart / Pub hosted repository data is migrated for Nexus 3.92.0+ datastore sources whose source profile reports a supported Pub content model, and Pub proxy cache data is migrated only when explicitly selected and planned `FULL`. Native Nexus Composer and Terraform proxy caches also require explicit administrator selection and a source profile that proves the native content model. Terraform imports only protocol-recognized archive assets and reconstructs route/checksum/signature state from the target upstream. Missing product capability, unknown schema, community-plugin sources, unsupported repository types, or plans below `FULL` fail closed. Old OrientDB-era sources do not enable datastore-only content export.

### First Migration

For the first repository data migration, leave `Metadata since` empty. This scans all migratable hosted repository data in the source Nexus.

Steps:

1. Fill in `Source URL`, `Source username`, and `Source password`.
2. Tune `Page size` and `Concurrency` based on source Nexus scale. If repository data is large, increase `Concurrency` from the default `8` to `32`.
3. Leave `Metadata since` empty.
4. Click `Sync metadata` to scan all repository metadata.
5. After metadata scanning completes, click `Sync packages` to migrate real blob data.
6. If failures occur, fix source permission, network, or storage issues, click `Retry failed`, then continue `Sync packages`.

`Sync metadata` only discovers and records assets to migrate. It does not download real blobs. Real package files are migrated by `Sync packages`.

### Docker End-To-End Validation

Docker / OCI repository migration uses the same two-step `Nexus Repository Data` flow. For local validation, pass `repositories` or `repositoryNames` so the job only scans the Docker hosted repository under test instead of every hosted repository.

The local reference Nexus defaults to `http://localhost:28090/`:

```bash
scripts/docker-compat/migration-e2e.sh
```

The script pushes a fixture image to the source `docker-hosted` repository, migrates only that Docker hosted repository's metadata and blob data, then pulls the same image from the kkrepo Docker connector and verifies the digest.

### Incremental Migration

For the second and later migrations, set `Metadata since` for incremental migration. `Metadata since` filters by source Nexus asset/blob update time and scans only assets added or updated after that time.

Before cutover, run at least one more incremental sync:

1. Set `Metadata since` to the previous migration start time, or slightly earlier than the previous sync completion time, keeping a small overlap window.
2. Click `Sync metadata` to scan incremental repository metadata.
3. Click `Sync packages` to migrate incremental real blob data.

Already migrated paths are detected and skipped. Assets already existing on the target side are also marked as migrated.

### Interruption And Resume

Migration tasks, repository scan cursors, asset states, and failure information are stored in the shared database. If kkrepo restarts, the network is interrupted, source Nexus is temporarily unavailable, or the page is closed during migration, continue with:

- If repository metadata scanning is interrupted, click `Continue metadata`.
- If real blob data migration is interrupted, click `Sync packages` again to continue unfinished items.
- After fixing failed assets, click `Retry failed` to enqueue them again, then run `Sync packages`.

Migration is designed to be interruptible and resumable. Completed data remains completed, and later migrations skip those parts.

## Domain Cutover

After full migration and the final incremental migration are complete, check browse/search, package downloads, checksums, and common client pull behavior for key repositories.

After confirming there are no issues, point the original Nexus domain to kkrepo through DNS or a reverse proxy. Because kkrepo is compatible with Nexus's `/repository/<repo>/...` URL layout, client protocols, and permission/authentication model, clients do not need to modify Maven settings, npm registry, PyPI index-url, Go GOPROXY, Helm repo, Cargo sparse registry URL, Pub hosted URL, Composer repository URL, Terraform `host.services` URL, or similar configuration for migrated repository formats.

After cutover, keep the source Nexus for an observation period, and disable source script capability after confirming no further compensation migration is needed.

## Source Script Capability Configuration

Before migrating from Nexus to kkrepo, the source Nexus must allow temporary Groovy scripts to be created and executed through the Script REST API.

The kkrepo migration flow temporarily creates scripts on the source Nexus to compensate for data that regular Nexus REST APIs cannot directly read, such as local user password hashes, API key compatibility information, and hosted repository asset pagination discovery. After script execution completes, kkrepo attempts to delete the temporary scripts.

### Applicability

- Nexus Repository 3.21.2 and later: Groovy scripting is disabled by default and must be explicitly enabled.
- Nexus Repository 3.21.1 and earlier: scripting is enabled by default, but the migration account still needs the required permissions.

Even for migration preflight only, it is recommended to enable script creation first. Otherwise local user password hashes cannot be automatically compensated, and the migration result will list users who need password reset. Repository data migration also cannot create the temporary script used to read source asset metadata.

### Enable Script Creation

Add this configuration to the source Nexus data directory configuration file:

```properties
nexus.scripts.allowCreation=true
```

Configuration file location:

```text
$data-dir/etc/nexus.properties
```

Common deployment paths:

| Deployment type | Common configuration path |
| --- | --- |
| Docker | `/nexus-data/etc/nexus.properties` |
| tar/zip or service deployment | `<nexus-data>/etc/nexus.properties` |

If the file does not exist, create it. If it already exists, append the configuration. Restart the source Nexus after modification for the setting to take effect.

### Docker Example

Enter the source Nexus container:

```bash
docker exec -it <nexus-container> sh
```

Append the configuration:

```bash
printf '\nnexus.scripts.allowCreation=true\n' >> /nexus-data/etc/nexus.properties
```

Exit the container and restart Nexus:

```bash
docker restart <nexus-container>
```

### Kubernetes Environment Variable Example

If Nexus is deployed on Kubernetes, you can also add the JVM system property through `INSTALL4J_ADD_VM_PARAMS`:

```yaml
- name: INSTALL4J_ADD_VM_PARAMS
  value: '-Xms8G -Xmx8G -XX:MaxDirectMemorySize=8192M -Djava.util.prefs.userRoot=/nexus-data/javaprefs -Dnexus.scripts.allowCreation=true'
```

After modifying the Deployment/StatefulSet, restart or rolling-update the source Nexus Pod so the new JVM parameters take effect.

### Non-Container Deployment Example

After finding the Nexus data directory, edit:

```bash
vi <nexus-data>/etc/nexus.properties
```

Add:

```properties
nexus.scripts.allowCreation=true
```

Then restart the Nexus service according to your deployment method, for example:

```bash
systemctl restart nexus
```

### Verification

Use an account with administrator permissions to access the source Nexus Script REST API:

```bash
curl -u '<admin-user>:<admin-password>' \
  -H 'Content-Type: application/json' \
  -X POST 'http://<nexus-host>:8081/service/rest/v1/script' \
  -d '{
    "name": "kkrepo-script-check",
    "type": "groovy",
    "content": "return \"ok\""
  }'
```

If the response is a success status such as 204, 200, or 201, script creation is available. Then remove the test script:

```bash
curl -u '<admin-user>:<admin-password>' \
  -X DELETE 'http://<nexus-host>:8081/service/rest/v1/script/kkrepo-script-check'
```

You can also run Nexus Metadata preflight directly in the kkrepo admin page. If script capability is disabled, the source usually returns `410 Gone` when creating a script and reports that script creation or update is disabled.

## Migration Account Permissions

The source Nexus account used for migration should be an admin account. At minimum, it needs:

- Permissions to read blob store, repository, security, content selector, role, privilege, user, and related configuration.
- Permissions to create, run, read, and delete Script REST API scripts.
- Permissions to read hosted repository asset content that needs to be migrated.

When permissions are insufficient, migration may show `401 Unauthorized`, `403 Forbidden`, or preflight errors indicating certain source objects cannot be read.

## Disable Script Capability After Migration

Script capability is high risk and should only be enabled during the migration window. After migration is complete and no further compensation migration is needed, disable it:

1. Delete or comment out this configuration in `$data-dir/etc/nexus.properties`:

   ```properties
   # nexus.scripts.allowCreation=true
   ```

2. Restart source Nexus.
3. Confirm there are no remaining `kkrepo-*` temporary scripts. If any remain, delete them through the Script REST API.

## FAQ

### `410 Gone`

Source Nexus forbids script creation or update. Confirm `$data-dir/etc/nexus.properties` contains:

```properties
nexus.scripts.allowCreation=true
```

and that source Nexus has been restarted.

### `401 Unauthorized` Or `403 Forbidden`

The migration account failed authentication or lacks permissions. Use the source Nexus admin account for migration, or grant Script REST API and related management-object read permissions.

### `404 Not Found`

Confirm you are accessing a Nexus Repository 3 address and port, and that the path is:

```text
/service/rest/v1/script
```

If Nexus is behind a reverse proxy, confirm the proxy does not block `/service/rest/v1/script`.

### Some Users Still Need Password Reset After Migration

Having source Script API available does not guarantee every local user password hash can be read or written to the target. Preflight and migration results list users who need manual password reset; handle them according to the result.

## Official References

- Sonatype Script API: https://help.sonatype.com/en/script-api.html
- Sonatype Scripting Nexus Repository 3: https://support.sonatype.com/hc/en-us/articles/360045220393-Scripting-Nexus-Repository-3
