# kkGuard — Nexus IQ-compatible application analysis

kkGuard is kkRepo's application composition analysis service. It exposes a Nexus IQ-compatible
REST surface so existing CI integrations can move from Nexus IQ to kkRepo, but it is **not** an IQ
reimplementation and must never be named or branded IQ. The product name is **kkGuard**; code,
tables and configuration use the `kkg` prefix.

Vulnerability intelligence and component analysis are delegated to **Dependency-Track**, the
open-source SCA platform. kkRepo owns the compatibility API, organizations/applications, policy
evaluation and durable state; Dependency-Track owns SBOM ingestion, component inventory,
vulnerability matching and its own policy engine.

## Why Dependency-Track

- Open source (Apache-2.0), actively maintained, CycloneDX-native.
- Ingests SBOMs (`PUT /api/v1/bom`), exposes project inventory, findings, violations and metrics.
- Avoids rebuilding and operating a vulnerability database or matching engine.
- Can run self-hosted next to kkRepo; kkRepo stores only references and normalized snapshots.

The existing kkRepo scanner stack (Syft/Grype through `scanner-adapter`) stays available as a
fallback backend for deployments that do not run Dependency-Track.

## Architecture

```text
CI client ── Nexus-IQ-compatible API ──> kkGuard (server/kkguard)
                                          │
                       ┌──────────────────┼───────────────────┐
                       │                  │                   │
                 kkg_* tables       SBOM blob (S3)      Dependency-Track
              (MySQL/PostgreSQL)     via BlobStorage     (REST, X-Api-Key)
```

- **Durable state** lives in MySQL/PostgreSQL (`kkg_organization`, `kkg_application`, `kkg_scan`,
  `kkg_scan_component`, `kkg_scan_finding`, `kkg_policy`, `kkg_violation`). Every scan transition
  is fenced by a row lock, so multiple kkRepo replicas can run workers safely.
- **SBOM documents** are immutable blobs in OSS/S3, addressed by SHA-256; only the digest and the
  storage reference are stored in the database.
- **Dependency-Track** is configured globally, with one Dependency-Track project per kkGuard
  application (and optionally one version per scan source/stage).

## Dependency-Track integration

Configuration (environment or `application.yml`):

| Property | Meaning |
| --- | --- |
| `kkrepo.kkg.dependency-track.url` | Base URL, for example `https://dtrack.example` |
| `kkrepo.kkg.dependency-track.api-key` | API key with BOM upload, project and view permissions |
| `kkrepo.kkg.dependency-track.auto-create-projects` | Create a project when an application has no `dt_project_uuid` |
| `kkrepo.kkg.dependency-track.project-version-template` | Version label for uploaded BOMs, default `{sourceId}` |
| `kkrepo.kkg.dependency-track.poll-interval` | Finding/violation polling interval |
| `kkrepo.kkg.dependency-track.poll-timeout` | Maximum time before a scan is marked `FAILED` |
| `kkrepo.kkg.dependency-track.verify-tls` | Relax certificate-chain trust **for the kkGuard client only**; hostname verification always stays on |

Endpoints used:

| Purpose | Dependency-Track API |
| --- | --- |
| Create/lookup project | `GET/POST /api/v1/project` |
| Upload SBOM | `PUT /api/v1/bom` (multipart or base64 JSON, CycloneDX) |
| Analysis state | `GET /api/v1/bom/cyclonedx/project/{uuid}` / project metrics |
| Findings | `GET /api/v1/finding/project/{uuid}` |
| Violations | `GET /api/v1/violation/project/{uuid}` |

TLS notes: `verify-tls=false` installs a trust-all `SSLContext` on the kkGuard `HttpClient` only.
The JDK has no per-client way to disable HTTPS endpoint identification, so hostname verification
stays enabled: a certificate whose SAN does not match the configured host is still rejected. The
client never sets `jdk.internal.httpclient.disableHostnameVerification`, because that switch is
JVM-wide and would also disarm the Maven proxy and the Nexus migration client in the same process.
Redirects are not followed, so the `X-Api-Key` header can never be replayed to another origin.

Scan flow:

1. `POST /api/v2/scan/applications/{applicationId}/sources/{sourceId}` accepts a CycloneDX SBOM and
   stores it as an immutable blob. The scan row is inserted as `PENDING`.
2. A worker (or the request thread for small synchronous scans) submits the SBOM to the
   application's Dependency-Track project, creating it when configured, and records
   `dt_project_uuid` on `kkg_application`.
3. The worker waits for Dependency-Track analysis to complete, then fetches findings and
   violations and replaces the scan snapshot (`kkg_scan_component`, `kkg_scan_finding`).
4. kkGuard evaluates its own policies (`kkg_policy`) against the normalized findings and stores
   `kkg_violation` rows. Policy evaluation stays local so behavior is deterministic and independent
   of the Dependency-Track policy configuration; Dependency-Track violations are retained as
   upstream evidence.
5. `GET /api/v2/scan/applications/{applicationId}/sources/{sourceId}/status` reports
   `PENDING`/`SCANNING`/`COMPLETE`/`FAILED`; reports become readable when the scan is complete.

Failure handling:

- Dependency-Track unreachable or non-2xx → retry with bounded backoff, then `FAILED` with a
  sanitized error. The SBOM blob and scan row are retained for diagnosis and retry.
- Duplicate submissions with the same SBOM SHA-256 and source return the existing completed scan
  instead of uploading twice.
- Deleting an application deletes its kkGuard scans; the Dependency-Track project is left intact
  unless `kkrepo.kkg.dependency-track.delete-projects` is enabled.

## Multi-replica scan ownership

Every scan transition is fenced by a database lease, so any replica may run the worker and no scan
is submitted or completed twice:

1. A replica claims a scan with a conditional update
   (`status IN ('PENDING','SCANNING') AND (lease_owner IS NULL OR lease_expires_at < now)`).
   The update only succeeds for one replica; a claim that loses the race is skipped.
2. `PENDING` scans are submitted: the SBOM blob is read back from object storage, its SHA-256 is
   re-verified, the application's Dependency-Track project is looked up or created when
   `auto-create-projects` is enabled, and `markScanSubmitted` stores the processing token.
3. `SCANNING` scans are polled; `isProcessing` false triggers normalization into
   `kkg_scan_component`/`kkg_scan_finding`, local policy evaluation into `kkg_violation` and a
   fenced `COMPLETE` transition with component and violation counts.
4. `markScanSubmitted`, `markScanPolled`, `finishScan` and `releaseScan` are all conditioned on the
   lease owner, so a replica that lost its lease (crash, pause, GC) cannot overwrite a newer
   attempt. Leases expire after `kkrepo.kkg.worker.lease-duration`, which also recovers scans held
   by a replica that disappeared mid-pass.
5. The snapshot writes (`replaceScanComponents`, `replaceScanFindings`, `replaceViolations`) are
   fenced the same way and run in one transaction with the `COMPLETE` transition. Each takes the
   `kkg_scan` row lock with `SELECT ... FOR UPDATE` only while `lease_owner` matches, and a mismatch
   aborts the transaction, so a slow poll whose lease expired cannot replace the findings of the
   replica that took over: the status and the snapshot always come from the same owner.
6. Submission and analysis are bounded by `poll-timeout`; an expired scan is failed with a
   sanitized error and keeps its SBOM blob for diagnosis. A `404` from a findings/violations
   endpoint is an error, never "zero findings": the project UUID came from Dependency-Track, so a
   missing project means the snapshot is unknown and the scan must fail rather than report a clean
   result.

Policy evaluation is local and deterministic. Supported `kkg_policy.constraints_json` keys are
`severity` (minimum), `cvssScore` (minimum), `vulnerabilityId` (exact) and `componentRef` (prefix);
all present constraints must match. A policy with no recognized constraint, or with an
unparseable severity, never produces a violation so a malformed policy cannot fail every build.

## Nexus IQ-compatible API surface

The compatibility target is the Nexus IQ `/api/v2` surface. kkGuard does not use the IQ name in
its own identifiers.

| Area | Endpoints (kkGuard) | State |
| --- | --- | --- |
| Organizations | `GET/POST /api/v2/organizations`, `GET/DELETE /api/v2/organizations/{id}` | implemented |
| Applications | `GET/POST /api/v2/applications`, `GET/PUT/DELETE /api/v2/applications/{id}`, `GET /api/v2/applications/organization/{orgId}` | implemented |
| Scans | `POST /api/v2/scan/applications/{appId}/sources/{sourceId}`, `GET .../status` | implemented |
| Reports | `GET /api/v2/applications/{appPublicId}/reports/{scanId}` | minimal report |
| Component details | `POST /api/v2/components/details` | next |
| Policies / violations | `GET /api/v2/policies`, `GET /api/v2/policyViolations` | local evaluation implemented, read API next |
| Waivers | `policyWaivers`/`policyWaiverRequests` | later |

Response DTOs follow the Nexus IQ JSON models: organizations expose `id` (UUID) and `name`;
applications expose `id`, `publicId`, `name`, `organizationId`, `contactUserName` and
`applicationTags`; reports expose component, vulnerability, license and policy-violation sections.

Internal kkRepo extensions (not part of the compatibility contract) live under
`/internal/kkguard/**` for administration and diagnostics.

## Security and permissions

- Compatibility endpoints require authentication and the `nexus:lifecycle:read` /
  `nexus:lifecycle:update` privileges (new application privileges seeded through a migration, with
  `nx-admin` receiving them through `nx-all`).
- Mutating scan submissions additionally require repository-level access when the application is
  bound to a repository.
- Dependency-Track API keys are never returned by the API and are stored through the existing
  secret/encryption mechanism.

## Delivery phases

1. **Foundation** (this branch): `kkg_*` schema, `KkGuardDao`, organization/application/policy
   persistence and contract tests.
2. **Dependency-Track client**: HTTP client, configuration, project mapping, readiness diagnostics.
3. **Scan pipeline**: SBOM storage, submission worker, findings/violations normalization, status
   endpoint.
4. **Reports and policies**: IQ-compatible report documents, local policy evaluation, component
   details, waivers.
5. **Operations**: admin UI, metrics, retention, black-box compatibility tests for the supported
   IQ client flows.

## Non-goals

- Rebuilding a vulnerability database or matching engine.
- Operating a Nexus IQ clone under the IQ name.
- Full parity with IQ's advanced search, license-legal reports, Firewall and source-control
  integrations in the first release.
