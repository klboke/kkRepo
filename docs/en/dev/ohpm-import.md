# Independent OHPM import

Use **Admin → Migration → OHPM Import** to import packages from a directory or an existing ohpm-repo into an OHPM hosted repository. This uses independent import jobs, not the Nexus migration content model.

Management endpoints under `/internal/ohpm/imports` accept a session or Bearer token and require current `nexus:*` permission. Authorization is checked again during publication. Jobs and version results are shared between replicas.

## Sources

For a directory, select one of these combinations for each version:

```text
packages/
  ordinary.har
  ordinary.metadata.json
  shared.har
  shared.hsp
  shared.metadata.json
  complete.tgz
  complete.metadata.json
```

HAR identity comes from `package/oh-package.json5`. An interface HAR and HSP must share a filename stem, or be supplied as an official complete `.tgz`/`.tar.gz` bundle. Optional matching `.metadata.json` files contain the **complete package metadata** returned by official Fetch Metadata, including `name`, `versions`, `dist-tags` and SHA-512 integrity. The importer selects the archive's version and tags referring to it, and verifies both attachments against that metadata.

Without a metadata file, the archive manifest is retained and checksums are calculated, but **no default tag is invented**. Install such versions explicitly or configure tags with OHPM dist-tags afterward. Unpaired HSP files, duplicate paths, extra HSP files beside bundles, and unmatched metadata files are rejected by the directory picker workflow.

The parser reuses the bounded JSON5 and archive validation described in [management integration](ohpm-management.md). Temporary files are removed after each request. Dry-run writes no target package, component, asset or blob; it may persist the plan and report.

For an existing registry, first configure an OHPM proxy with its source URL, credentials, redirect allowlist and outbound policy. Select that proxy as the import source and supply explicit package names. The importer reads official metadata and verifies every selected version's HAR/HSP directly, without populating proxy caches. There is no assumed whole-registry inventory API or dependency on ohpm-repo's internal database/filesystem layout.

Source authentication uses the existing raw OHPM Authorization behavior. Credentials remain in the proxy configuration, not the import plan; archive download URLs are removed from persisted publication metadata. External storage-plugin URLs are permitted only within the configured outbound/redirect boundaries. Imported bytes are stored through the target's normal hosted blob storage.

## Plan, execute and resume

1. **New dry run** creates a `PLANNING` job. Each verified item pins its identity, manifest, tags, sizes and SHA-512. A job is limited to 1,000 items and 16 MiB of plan metadata, with at most 2 MiB per item.
2. A finished plan enters `READY` and becomes immutable. Its report may include `MANUAL` blockers.
3. **Import / resume** enters `APPLYING`, re-reads sources, compares them with the plan and publishes one version at a time. Only all-successful jobs enter `COMPLETED`.
4. **Stop after current item** stops scheduling after the current request. Closing the page does not lose committed checkpoints. Select the saved job to resume.
5. Directory jobs require selecting the same source directory and metadata again. Registry jobs reconnect using the current proxy configuration. Neither requires files left on the original server replica.

Dry-run checks target state as observed at that time. Execution repeats those checks under the package lock. Source content or metadata/tag changes after sealing produce `MANUAL`, rather than silently modifying the plan. A previously successful item returns its saved result; create a new dry-run job to revalidate a target that was subsequently changed or deleted.

| Item state | Meaning |
| --- | --- |
| `PLANNED` | Source verified; awaiting execution |
| `IMPORTED` | New publication and its checkpoint committed together |
| `SKIPPED` | Existing metadata, SHA-512 and actual HAR/HSP bytes match; only missing, nonconflicting tags may be added |
| `MANUAL` | Invalid/missing/changed source, different target content, or conflicting tag |
| `FAILED` | Read or execution failure; retry the same job without repeating successful items |

Inputs that cannot identify a valid version during planning remain `MANUAL`; create a corrected plan after fixing them. Users, tokens, permissions and storage-plugin configuration are not automatically imported and are listed separately as `MANUAL` configuration actions in every report.

Versions are never overwritten. Matching archive checksums with different publication manifests also block import; comparison excludes separately verified `dist`, derived `_id`, and client provenance fields `_nodeVersion` and `_ohpmVersion`. A target tag referring to a different version blocks the entire item before publication. Existing target tags absent from the source are retained.

## Replica and transaction behavior

MySQL and PostgreSQL store `ohpm_import_job` and `ohpm_import_item`. Execution locks the job before entering the shared hosted package transaction. Files, version metadata, tags, blob references and the successful checkpoint commit together. A checkpoint failure rolls back publication and uses the existing blob rollback compensation.

Network reads, archive parsing and source validation happen before the write transaction. Concurrent resumes serialize through database row locks and terminal-result checks. Processing is request-driven: there is no background worker, JVM-only queue, persistent local staging dependency or task lease.

## API

| Method and path | Operation |
| --- | --- |
| `POST /internal/ohpm/imports` | Create with `{repository, source: "DIRECTORY" \| "REGISTRY", sourceRepository?}` |
| `GET /internal/ohpm/imports?repository=<name>` | Most recent 100 jobs |
| `GET /internal/ohpm/imports/<id>` | Downloadable JSON job, item plans/results and manual configuration actions |
| `POST /internal/ohpm/imports/<id>/directory` | Plan multipart `sourceKey`, `asset`, optional `hsp`, `metadata`, `packageName` |
| `GET /internal/ohpm/imports/<id>/source-versions?name=<package>` | Versions of an explicitly named source package |
| `POST /internal/ohpm/imports/<id>/registry` | Plan a remote version with `{name, version}` |
| `POST /internal/ohpm/imports/<id>/state` | Valid transition using `{state: "READY" \| "APPLYING" \| "COMPLETED" \| "CANCELLED"}` |
| `POST /internal/ohpm/imports/<id>/items/<key>/directory` | Apply/resume with the original archives and optional metadata |
| `POST /internal/ohpm/imports/<id>/items/<key>/registry` | Apply/resume by reading the remote source again |

A metadata file requires `packageName` for identity validation. Use item keys returned by the report. Always inspect the returned item state: an HTTP success means the request was processed, not necessarily that publication succeeded.

Verification entry points:

```bash
mvn -pl persistence-mysql,persistence-postgresql -am \
  -Dtest='OhpmImport*Test' -Dsurefire.failIfNoSpecifiedTests=false test
mvn -pl server -am -Dtest='Ohpm*Test' \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dkkrepo.ohpm.client=true -Dkkrepo.ohpm.expectedVersion=6.0.1 test
node --test admin-ui/src/test/js/ohpm-import.test.js
```
