# Artifact Scanning Guide

This guide is for kkRepo deployment operators, repository administrators, and security
administrators. It explains how to deploy the scanner, activate scanning per repository, inspect
SBOMs and vulnerabilities, configure policies, and manage waivers. See the
[artifact security scanning design](../zh/dev/security-scanning-design.md) for implementation,
data-model, and multi-replica details.

The Chinese version is available in the
[Artifact Scanning 使用指南](../zh/artifact-scanning-guide.md).

## Capability Boundaries

Artifact Scanning uses Syft to generate CycloneDX SBOMs and Grype to match known vulnerabilities.
Keep these boundaries in mind:

- Scanning is asynchronous. Upload and proxy-cache transactions only commit the artifact and a
  generic durable content-change event; they do not call the scanner in the upload request.
- `KKREPO_SECURITY_SCANNING_ENABLED=true` only enables kkRepo deployment capability,
  coordination workers, and download-policy integration. It does not activate any repository.
- A repository administrator must activate each repository under
  **Admin > Security > Artifact Scanning > Repositories**.
- The scanner runs as a separate container or Pod. kkRepo does not execute Syft/Grype inside its
  own JVM and does not require a Docker socket.
- `AUDIT` records policy decisions without blocking downloads. A repository must explicitly use
  `ENFORCE` before a blocking decision affects a download.
- The download hot path reads an already-materialized policy state from the relational database.
  It never calls the scanner or starts a scan synchronously.
- MySQL/PostgreSQL stores candidates, tasks, leases, results, policies, and waivers. The scanner
  volume contains only a rebuildable Grype vulnerability database.

## Recommended Rollout

Use this rollout order in production:

1. Deploy the scanner adapter and enable kkRepo deployment capability without activating a
   repository.
2. Confirm that the page reports **Scanner Ready** and that the Vulnerability DB value and
   observation time continue to update.
3. Select a small pilot set of repositories with `AUDIT` mode and all exceptional states set to
   `ALLOW`.
4. Let backfill finish, then review failed tasks, partial/stale states, findings, and SBOMs.
5. Establish vulnerability-remediation and waiver-approval procedures.
6. Enable `ENFORCE` only for repositories with complete coverage and stable operations.
7. Decide separately whether pending, failed, or partial results should fail closed.

Do not enable strict blocking at the same time as the first backfill. Database initialization,
scanner capacity, and a large existing artifact set can temporarily put many artifacts in a
pending state.

## Prerequisites

- Use matching release tags for the kkRepo and scanner adapter images.
- Run kkRepo with MySQL 8.0 or PostgreSQL. Existing Flyway migrations create the scan state.
- Production kkRepo should use a shared OSS/S3 blob store. The scanner does not connect to the
  kkRepo database or blob store; it receives inputs only through the protected internal API.
- The scanner needs a writable temporary directory and Grype database directory.
- When automatic database updates are enabled, the scanner must be able to reach the Grype
  database source.
- Configure the same high-entropy service credential in kkRepo and the scanner adapter.

The default Helm scanner resources request `500m CPU / 1 GiB`, limit `2 CPU / 4 GiB`, provide a
`5 GiB` ephemeral-storage limit, and use a `10 GiB` vulnerability-database PVC. Compose provides a
`4 GiB` `/tmp` tmpfs by default. Size these values from the largest artifacts, configured
concurrency, and observed scan duration.

## Docker Compose Deployment

MySQL quickstart:

```bash
export KKREPO_SECURITY_SCANNING_ENABLED=true
export KKREPO_SECURITY_SCANNING_SERVICE_CREDENTIAL="$(openssl rand -hex 32)"

docker compose \
  -f docker-compose.quickstart.yml \
  --profile security-scanning \
  up -d
```

For PostgreSQL, use the same environment values with the PostgreSQL Compose file:

```bash
docker compose \
  -f docker-compose.quickstart-postgresql.yml \
  --profile security-scanning \
  up -d
```

Both controls are required:

- `--profile security-scanning` starts the scanner adapter container.
- `KKREPO_SECURITY_SCANNING_ENABLED=true` starts kkRepo coordination workers and download-policy
  integration.

Store the generated credential in a protected `.env` file or secret manager. Rotate it by updating
both components and restarting kkRepo and the scanner adapter together.

Check the containers and scanner readiness:

```bash
docker compose \
  -f docker-compose.quickstart.yml \
  --profile security-scanning \
  ps

docker compose \
  -f docker-compose.quickstart.yml \
  --profile security-scanning \
  exec scanner \
  wget -qO- http://127.0.0.1:8080/actuator/health/readiness
```

Default endpoints:

- Admin UI: `http://127.0.0.1:19090/admin/`
- kkRepo health: `http://127.0.0.1:19091/actuator/health`
- Prometheus: `http://127.0.0.1:19091/actuator/prometheus`

If ordinary quickstart is already running, set the environment values and rerun `docker compose
up -d` with the profile. Starting only the scanner container does not change the existing kkRepo
container's `KKREPO_SECURITY_SCANNING_ENABLED` value.

## Helm / Kubernetes Deployment

Create a dedicated scanner service credential:

```bash
kubectl create secret generic kkrepo-scanner \
  --from-literal=service-credential="$(openssl rand -hex 32)"
```

Make sure the database and encryption Secrets required by the
[Helm chart README](../../deploy/helm/kkrepo/README.md) already exist, then enable the chart:

```bash
helm upgrade --install kkrepo deploy/helm/kkrepo \
  --set database.type=postgresql \
  --set database.url='jdbc:postgresql://postgresql.example:5432/kkrepo' \
  --set database.username=kkrepo \
  --set securityScanning.enabled=true
```

`securityScanning.enabled=true` does both of the following:

- Deploys the scanner adapter StatefulSet, Services, probes, and optional NetworkPolicy.
- Enables the kkRepo deployment capability gate.

It still does not activate a repository. After deployment, check:

```bash
kubectl get pods
kubectl get statefulset
kubectl logs statefulset/kkrepo-scanner
```

For multiple scanner replicas:

- Each run is assigned to a stable StatefulSet ordinal, and cancellation is broadcast across the
  configured ordinals so it reaches the Pod that owns the process-local execution.
- Capability and readiness observation fails over across the configured ordinals and treats the
  deployment as ready when at least one adapter replica is ready. Run routing remains stable.
- If replicas share a persistent database cache, set
  `securityScanning.scannerDatabase.persistence.existingClaim` to a `ReadWriteMany` PVC.
- If shared storage is unavailable, disable scanner database persistence and let each Pod use its
  own ephemeral cache.
- Do not attempt to mount one cross-node `ReadWriteOnce` volume into several Pods.

See the [Helm chart README](../../deploy/helm/kkrepo/README.md) for all chart values.

## Admin UI And Permissions

Open **Admin > Security > Artifact Scanning**.

When deployment capability is disabled or its status cannot be loaded, the page remains visible
but all scanning controls are disabled. This distinguishes a deployment that does not provide the
capability from a repository that has not been activated.

Grant custom roles according to responsibility:

| Operation | Permission |
| --- | --- |
| View scan pages, tasks, results, and SBOMs | `nexus:security-scanning:read` plus browse permission for the target repository |
| Create or revise global policies | `nexus:security-scanning:update` |
| Configure a repository, rescan, retry, or cancel | `nexus:security-scanning:update` plus repository administration for the target repository |
| Create a waiver | `nexus:security-scanning-waivers:create` plus repository administration |
| Delete a waiver | `nexus:security-scanning-waivers:delete` plus repository administration |

Lists and details include only repositories the user may browse. SBOM downloads apply the same
repository-visibility check.

## Activate A Repository

1. Open **Repositories**.
2. Search for the repository and click **configure**.
3. Select **Enable scanning for this repository**.
4. Keep **Mode = Audit only** for the first validation period.
5. Choose result validity and the applicable content scope.
6. Review **Advanced exception handling**. Keep every option at **Allow download** for the first
   rollout.
7. Save the configuration.

Configuration fields:

| Field | Meaning |
| --- | --- |
| Repository | Current repository; read-only |
| Scan profile | Scanner capability binding; built-in value is `syft-grype-v1`; read-only |
| Vulnerability policy | Centrally assigned policy or built-in critical baseline; read-only |
| Mode | `AUDIT` records decisions; `ENFORCE` applies blocking |
| Result validity | Use the policy default or select 1/7/30 days; expired results are pending |
| Enable scanning | Business activation for this repository |
| Scan hosted content | Scan hosted artifacts |
| Scan proxy content | Scan cached proxy artifacts |

A hosted repository shows only the hosted switch, a proxy shows only the proxy switch, and a
group shows both. A group configuration applies to content resolved from its hosted/proxy members;
it does not scan an additional synthetic group-file copy.

Repository administrators cannot type IDs or arbitrarily switch the scan profile and policy
binding. Creating a new policy does not change a repository automatically. Editing a policy that
is already assigned creates a revision and moves those repositories to the new revision while
historical decisions retain the old revision.

### Advanced Exception Handling

Advanced exception handling decides what to do when there is no complete, current result:

| State | Setting | Download result when set to `BLOCK` |
| --- | --- | --- |
| PENDING, RUNNING, STALE, or no materialized result | Scan pending | HTTP `503` with `Retry-After: 30` |
| FAILED, CANCELLED, or unavailable profile | Scan failed | HTTP `503` with `Retry-After: 30` |
| PARTIAL or incomplete inventory | Partial result | HTTP `503` with `Retry-After: 30` |
| Complete result matches an unwaived policy violation | Vulnerability policy | HTTP `403` |

These settings affect downloads only in `ENFORCE`. In `AUDIT`, kkRepo records a shadow decision
and still serves the artifact. Docker/OCI paths return Registry-shaped `UNAVAILABLE` or `DENIED`
errors.

When both repository and policy validity are configured, kkRepo uses the shorter period. Without
a result-age limit, time alone does not make the result stale; a vulnerability-database change can
still trigger a rematch.

## Scanned Content

The scanner receives only protocol-recognized packages, archives, or OCI manifests. Checksums,
signatures, indexes, and ordinary protocol metadata are excluded.

| Repository format | Current targets |
| --- | --- |
| Maven | `.jar`, `.war`, `.ear`, `.zip` |
| npm | Package `.tgz` |
| PyPI | `.whl`, `.tar.gz`, `.zip` |
| Go | Module `.zip`; excludes `.info`/`.mod` |
| Helm | Chart `.tgz`; excludes `.prov` |
| Cargo | `.crate` |
| Pub | Package `.tar.gz`/`.tgz` |
| Composer | Supported package archives |
| Terraform | Module/provider archives; excludes checksums/signatures |
| Swift | Source archives; excludes manifests/metadata |
| Ansible Galaxy | Collection `.tar.gz` |
| Docker/OCI | Images resolved from manifests/indexes; standalone layers/metadata are not treated as artifacts |
| NuGet | `.nupkg`; excludes `.snupkg` |
| RubyGems | `.gem` |
| Yum | `.rpm`; excludes `repodata` |
| Raw | `.zip`, `.tar`, `.tar.gz`, `.tgz`, `.jar`, `.war`, `.ear`, `.whl`, `.crate`, `.gem`, `.nupkg`, `.rpm` |

An artifact above the profile limit is marked failed rather than clean. The built-in profile limit
is `1 GiB`, while the adapter hard limit defaults to `2 GiB`; the stricter limit wins.

The built-in OCI profile requires `linux/amd64` by default. A multi-platform image is complete only
when required platforms are covered; a missing platform can produce a partial result.

## Scan Triggers

- After an artifact is created or replaced, the committed durable content event automatically
  creates a candidate and task. Workers poll at about one-second intervals by default; this is not
  a once-per-minute full-database scan.
- Activating a repository or expanding hosted/proxy scope creates a durable backfill for existing
  artifacts.
- **rescan** creates a high-priority task with reason `MANUAL`.
- When the scanner engine or vulnerability-database revision changes, saved SBOMs can be rematched
  without cataloging unchanged artifact bytes again.
- A policy revision, result-validity change, or waiver change causes policy-only reconciliation;
  it does not rescan artifact bytes.
- Vulnerability-database rematching and policy reconciliation keep row-locked shared cursors in
  the database. Bounded passes therefore continue beyond slow early tasks and remain fair across
  repository contexts on multi-replica deployments.
- Tasks, leases, and retry state live in the shared database, so another kkRepo replica can take
  over safely.

## Using The Tabs

Every list supports search, previous/next navigation, and page sizes of 10/15/25/50/100. The
default is 10 rows.

Every tab has a shareable hash route. Overview uses
`/#admin/security/artifact-scanning`; the other tabs append `/tasks`, `/findings`,
`/repositories`, `/policies`, or `/waivers`. Opening a link directly, refreshing, or using browser
back/forward restores the selected tab.

### Overview

The summary shows:

- Scanner: `Ready`, `Degraded`, or `Disabled`.
- Vulnerability DB: current database version used for vulnerability matching.
- Candidate backlog, Pending, Running, and Failed.
- Complete assets, Partial/stale, and Policy blocks.
- Critical/high finding counts.

The scanner adapter obtains the Vulnerability DB value from
`grype db status --output json`. It prefers a checksum, digest, or revision and falls back to the
built timestamp or schema version, so the UI may show an ISO timestamp. This is not the
schema/Flyway version of the kkRepo relational database.

The Runs table shows completed scan runs, completeness, finding counts, and completion time. Click
**download** to retrieve the access-controlled CycloneDX JSON SBOM.

### Tasks

Tasks show stage, trigger reason, status, attempts, lease, and error code.

- `PENDING` / `RETRY_WAIT` / `RUNNING`: cancel is available.
- `FAILED` / `CANCELLED`: retry is available.
- Tasks with an asset: rescan is available.

Capacity errors, temporary network failures, and scanner `429/502/503/504` responses use bounded
automatic retries. The default is five attempts with a maximum 30-minute backoff. Manual retry is
available only for failed or cancelled tasks.

### Findings

Findings show severity, Advisory, repositories, package, installed/fixed versions, and waiver
status.

- Click Advisory to open the primary advisory URL in a new tab.
- Click **view** for title, PURL, CVSS, aliases, source, locations, scan run, and waiver coverage.
- Click **waive** to approve the finding for one associated repository artifact.
- When every target is covered, the button becomes disabled **waived**. Partial coverage shows
  **waive remaining**.

A finding is a match from the scanner database used by that run. Interpret `0 findings` as “no
known vulnerabilities matched” only when the run is complete, the database is current, and the
target is applicable.

### Repositories

Repositories shows Enabled/Disabled, format, type, profile, policy, and mode for every visible
repository. This is the only UI location for repository scan activation.

### Policies

Policies creates and revises centrally managed vulnerability policies:

| Field | Meaning |
| --- | --- |
| Block severity | Block unwaived findings at this severity or higher |
| Result validity | No expiry or 1/7/30 days |
| Only block fixable findings | Only findings with a fixed version can block |
| Block unknown severity | Include UNKNOWN findings |
| Require complete inventory | Treat incomplete SBOM inventory as partial |

Editing does not overwrite the existing row; it creates a new revision. Historical runs and
decisions keep the old revision, while repositories already assigned to that policy move to the
new revision.

### Waivers

Create a waiver from **waive** on a Finding:

1. Select an associated Repository artifact.
2. Select 1, 7, 30, 90 days, or **Never expires**.
3. Enter the required Reason.
4. Create the waiver; the Findings state refreshes immediately.

The Waivers tab lists Active/Expired state, scope, repository, artifact, exception, approver,
reason, and expiry. It also provides waiver deletion. Review non-expiring waivers periodically.
After deletion or expiry, kkRepo recalculates the associated policy state.

The service rejects a duplicate active waiver for the same repository artifact; correctness does
not depend on a disabled browser button.

## Common Configuration

### kkRepo

| Environment variable | Default | Purpose |
| --- | ---: | --- |
| `KKREPO_SECURITY_SCANNING_ENABLED` | `false` | Deployment capability gate |
| `KKREPO_SECURITY_SCANNING_ADAPTER_BASE_URL` | `http://scanner:8080` | Single internal adapter URL, used by Compose and as the fallback |
| `KKREPO_SECURITY_SCANNING_ADAPTER_BASE_URLS` | Empty | Comma-separated stable adapter URLs; when present, overrides the single URL and enables deterministic per-run routing plus cancellation broadcast |
| `KKREPO_SECURITY_SCANNING_SERVICE_CREDENTIAL` | Required when scanning is enabled | Shared credential used by kkRepo; kkRepo refuses to start with scanning enabled when this is empty |
| `KKREPO_SECURITY_SCANNING_OCI_REGISTRY_URL` | `http://kkrepo:8080` | kkRepo URL used by the scanner for exact OCI digests |
| `KKREPO_SECURITY_SCANNING_DATABASE_MAX_AGE` | `48h` | Maximum operational vulnerability-database age |
| `KKREPO_SECURITY_SCANNING_OBSERVATION_MAX_AGE` | `2m` | Maximum scanner snapshot observation age |
| `KKREPO_SECURITY_SCANNING_MAX_RESPONSE_BYTES` | `335544320` | Maximum adapter JSON response accepted by kkRepo, including raw-document Base64, JSON fields, and projections |
| `KKREPO_SECURITY_SCANNING_WORKER_BATCH_SIZE` | `4` | Tasks claimed per worker cycle |
| `KKREPO_SECURITY_SCANNING_WORKER_MAX_ATTEMPTS` | `5` | Automatic attempt limit |
| `KKREPO_SECURITY_SCANNING_METRICS_COUNT_LIMIT` | `10000` | Gauge saturation limit that avoids unbounded counts |
| `KKREPO_SECURITY_SCANNING_TERMINAL_TASK_RETENTION_DAYS` | `30` | Terminal-task retention |
| `KKREPO_SECURITY_SCANNING_RESULT_RETENTION_DAYS` | `90` | Unreferenced historical-result retention |

Every kkRepo replica must use the same enabled value, ordered adapter URL list, service credential,
and OCI registry URL.

### Scanner Adapter

| Environment variable | Application default | Purpose |
| --- | ---: | --- |
| `KKREPO_SCANNER_SERVICE_CREDENTIAL` | Required | Must match the kkRepo credential; the adapter refuses to start when it is empty |
| `KKREPO_SCANNER_DB_AUTO_UPDATE` | `false` | Automatic database update; Compose/Helm templates set `true` |
| `KKREPO_SCANNER_DB_DIRECTORY` | `/var/lib/kkrepo-scanner/grype` | Grype database directory |
| `KKREPO_SCANNER_DB_UPDATE_INTERVAL` | `6h` | Target update interval |
| `KKREPO_SCANNER_DB_UPDATE_CHECK_INTERVAL` | `1m` | Update-eligibility check interval |
| `KKREPO_SCANNER_MAX_CONCURRENT_SCANS` | `2` | Active scans per Pod |
| `KKREPO_SCANNER_MAX_QUEUED_SCANS` | `4` | Waiting requests per Pod |
| `KKREPO_SCANNER_ADMISSION_TIMEOUT` | `1s` | Wait for scanner capacity |
| `KKREPO_SCANNER_RETRY_AFTER_SECONDS` | `5` | Retry hint after capacity rejection |
| `KKREPO_SCANNER_MAX_INPUT_BYTES` | `2147483648` | Adapter input hard limit |
| `KKREPO_SCANNER_MAX_OUTPUT_BYTES` | `67108864` | Per raw SBOM/report limit; OCI also applies it to aggregate platform inputs and the merged SBOM |

See the
[scanner adapter application.yml](../../scanner-adapter/src/main/resources/application.yml) for
all low-level values.

`MAX_RESPONSE_BYTES` bounds the transport JSON envelope and is intentionally separate from the
scanner's raw-output limit. If `KKREPO_SCANNER_MAX_OUTPUT_BYTES` is increased, raise the former to
account for Base64 expansion and projected fields; the 320 MiB default leaves explicit headroom
for the default 64 MiB raw limit.

## Monitoring And Alerts

kkRepo exposes these important metrics from `/actuator/prometheus` on the management port:

| Metric | Suggested use |
| --- | --- |
| `kkrepo_security_scan_scanner_ready` | Scanner readiness |
| `kkrepo_security_scan_database_age_seconds` | Vulnerability-database age |
| `kkrepo_security_scan_artifact_event_backlog` | Unprocessed content events |
| `kkrepo_security_scan_artifact_event_oldest_age_seconds` | Oldest content-event age |
| `kkrepo_security_scan_backlog` | Ready/retrying tasks |
| `kkrepo_security_scan_oldest_age_seconds` | Oldest pending-task age |
| `kkrepo_security_scan_running` | Active task leases |
| `kkrepo_security_scan_failures` | Terminal task failures |
| `kkrepo_security_scan_partial` | Partial assets |
| `kkrepo_security_scan_findings` | Critical/high finding aggregate |
| `kkrepo_security_scan_tasks_total` | Task outcomes by format/stage/reason |
| `kkrepo_security_policy_decisions_total` | Allow/block/shadow decisions |
| `kkrepo_security_policy_evaluation_duration_seconds` | Download-policy database latency |

The scanner adapter also exposes active, queued, admission-rejected, and database-update metrics.

At minimum, alert on:

- A scanner that remains degraded.
- Database age above `KKREPO_SECURITY_SCANNING_DATABASE_MAX_AGE`.
- Continuously increasing oldest event/task age.
- Increasing terminal failures.
- Large persistent pending/partial block populations in enforce repositories.

## Troubleshooting

| Symptom | Check |
| --- | --- |
| The whole page is disabled | Ensure the scanner profile is running, `KKREPO_SECURITY_SCANNING_ENABLED=true`, and every kkRepo replica was restarted |
| Scanner is Degraded | Check adapter logs, service credential, API version, Syft/Grype, database update time, and network |
| Repository is Enabled but has no tasks | Check content scope, supported target type, backfill/candidate backlog, and repository permissions |
| Many tasks are in `RETRY_WAIT` | Check scanner concurrency/queue, CPU, memory, temporary space, and network; capacity exhaustion returns retryable `429` |
| A task is terminal FAILED | Inspect the Tasks error code/summary, fix the cause, then retry or rescan the asset |
| Findings exist but downloads are allowed | Check `AUDIT` versus `ENFORCE`, policy threshold, fixable-only behavior, and active waivers |
| Download returns `503` | Pending/failed/partial is configured to `BLOCK`; wait for completion or temporarily restore `ALLOW` |
| Download returns `403` | A complete result matches an unwaived policy; upgrade, revise policy, or approve a waiver |
| OCI scan fails | Ensure `KKREPO_SECURITY_SCANNING_OCI_REGISTRY_URL` is reachable from the scanner, credentials match, and required platforms exist |
| Vulnerability DB is stale | Check automatic updates, scanner HTTPS egress, volume permissions, and free space |
| SBOM download fails | Check browse/read permission, SBOM blob references, and the backing blob store |

Do not log service credentials, temporary registry tokens, signed artifact URLs, or complete
sensitive paths while troubleshooting.

## Disable Or Roll Back

- Disabling one repository stops future scan requirements and policy application for that
  repository. Historical runs, findings, and waivers remain until retention permits cleanup.
- Set `KKREPO_SECURITY_SCANNING_ENABLED=false` and restart every kkRepo replica to stop coordination
  workers, make the download policy allow directly, and disable the Admin UI controls. Existing
  repository configuration and historical results are preserved.
- Disable the global gate before stopping the scanner adapter so active workers do not keep
  producing retryable failures.
- Terminal tasks are retained for 30 days by default, and unreferenced historical results for 90
  days. Do not delete scan tables or SBOM blobs manually; built-in retention and generic blob
  reference/GC own their lifecycle.

## Security Recommendations

- Expose the scanner adapter only on an internal network.
- Generate a random service credential and inject it through a Secret; never bake it into an image,
  repository, or log.
- Do not mount the Docker socket or grant additional Linux capabilities.
- Keep a read-only root filesystem, non-root user, bounded temporary storage, and resource limits.
- Allow only the HTTPS egress required for database updates; remove unnecessary egress when updates
  are disabled.
- Start with Audit, then enable Enforce per repository. Review non-expiring waivers regularly.
- Back up the kkRepo relational database and blob store. The scanner database volume is a
  rebuildable cache, not a business backup.

Related documentation:

- [Artifact security scanning design](../zh/dev/security-scanning-design.md)
- [Security model](security-model.md)
- [Monitoring and observability](monitoring-observability-guide.md)
- [Production hardening](production-hardening.md)
- [Backup and restore](backup-restore.md)
