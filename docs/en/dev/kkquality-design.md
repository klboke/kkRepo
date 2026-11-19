# kkQuality — SonarQube-compatible code quality service

kkQuality is kkRepo's code-quality and static-analysis service. It replaces SonarQube for teams
that want a self-hosted quality gate without a separate Elasticsearch/OrientDB stack, while
exposing a SonarQube-compatible read API so existing dashboards and CI checks can migrate.

Naming: the service is **kkQuality** everywhere (`kkq_*` tables, `server.kkquality` package in the
next increment). "SonarQube" is referenced only as the API compatibility target.

## Architecture

```text
CI / kkq-scanner ── SARIF + metadata ──> kkQuality (server/kkquality)
                                            │
                              ┌─────────────┼──────────────┐
                              │             │              │
                         kkq_* tables   SARIF blob     quality gates
                       (MySQL/PG)       (OSS/S3)       (local evaluation)
```

- Analyzers run in CI, not on the server: **Semgrep**, **PMD**, **Checkstyle**, **SpotBugs**,
  **ESLint**, **Ruff**, **golangci-lint**, **Clippy**, **detekt**, **ShellCheck**, **Hadolint**.
- Every analyzer emits **SARIF 2.1.0**; kkQuality normalizes rules, results, locations and
  severities from SARIF. No analyzer-specific server code is required to add a tool.
- SARIF documents are immutable blobs in OSS/S3 keyed by SHA-256; MySQL stores project, branch,
  analysis, rule, issue, measure and quality-gate rows.
- Ingestion and gate evaluation are fenced with row locks so multiple kkRepo replicas can process
  analyses safely.

## Data model

| Table | Purpose |
| --- | --- |
| `kkq_project` | SonarQube project key, name, visibility, external UUID |
| `kkq_branch` | main/feature branches per project |
| `kkq_analysis` | one SARIF submission: status, revision, SARIF digest, issue count, new-issue count |
| `kkq_rule` | normalized analyzer rule metadata |
| `kkq_issue` | current state per `(project, branch, fingerprint)`: file, lines, rule, severity, type, status, resolution, assignee |
| `kkq_issue_occurrence` | every `(issue, analysis)` observation, used for "new issues" and history |
| `kkq_quality_gate` | gate conditions (metric/operator/threshold), optional project scope |
| `kkq_measure` | per-analysis metric values (bugs, vulnerabilities, code smells, coverage, duplication) |

Issues are stored as current state, not per analysis: `kkq_issue` holds one row per
`(project, branch, fingerprint)` and `kkq_issue_occurrence` records each analysis that observed it.
Re-running an analysis on an unchanged tree therefore never duplicates an issue, while "new issues
versus the previous analysis" is a query over occurrences. An issue the next analysis does not
report is closed with resolution `REMOVED` and reopens automatically if it comes back.

## Permissions and tenancy

`kkq_project.repository_id` optionally binds a project to a kkRepo repository. The binding is what
makes the read API multi-tenant:

| Caller | Read | Ingest |
| --- | --- | --- |
| Holds `nx-kkquality-read` | every project | — |
| Holds `nx-kkquality-update` | — | every project |
| Repository `read` on the bound repository | projects bound to it | — |
| Repository `edit` on the bound repository | — | projects bound to it |

The authorization filter is applied inside the SQL query (`listProjectsInRepositories`,
`countProjectsInRepositories`) so a caller cannot receive a page it is not allowed to see, and the
decision always goes through the shared, durable `SecurityManagementService` grants rather than a
replica-local cache. Projects without a repository binding are only visible through the
application-wide privilege.

Several projects may share one repository: the binding is a tenancy scope (one repository can host
several modules, each with its own Sonar project), not a 1:1 mapping. What is fixed is the binding
itself: a project is bound once, and ingestion may not move it.

Ingestion resolves the project **before** authorizing. A request naming repository B for a project
already bound to repository A is refused (`403`) instead of being authorized against B and then
writing into A; when the payload omits the repository, the existing binding decides. An unbound
project is bound to the repository named by the caller, and a caller with no repository and no
binding must hold `nx-kkquality-update`. The ingestion service re-checks the binding inside the
transaction, so a concurrent binding by another replica cannot be overwritten.

## API compatibility

Read compatibility with the SonarQube Web API subset used by dashboards and CI:

| Area | Endpoints |
| --- | --- |
| Projects | `GET /api/projects/search`, `POST /api/projects/create`, `DELETE /api/projects/delete` |
| Issues | `GET /api/issues/search`, `POST /api/issues/do_transition`, `POST /api/issues/assign` |
| Measures | `GET /api/measures/component` |
| Quality gates | `GET /api/qualitygates/list`, `GET /api/qualitygates/project_status` |
| Rules | `GET /api/rules/search` |

Analysis ingestion is a kkRepo extension for the first release:
`POST /api/kkquality/analyses` accepts `{"project","projectName","branch","revision","repository",
"sarif"}` where `sarif` is a SARIF 2.1.0 document (object or JSON string). The response is the
analysis status; `GET /api/kkquality/analyses/{analysisId}` and
`GET /api/kkquality/analyses?project=...` expose status and history. Resubmitting a document whose
digest matches the latest analysis of the branch is idempotent. A later phase can add the Sonar
scanner submission protocol (`/api/ce/submit`) for unmodified `sonar-scanner` clients.

Authentication uses kkRepo API keys/tokens. The compatibility layer reuses the kkRepo privilege
model (`nx-kkquality-read` / `nx-kkquality-update`) and repository-scoped permissions instead of
introducing a parallel `sonar:*` namespace.

Implemented read surface (SonarQube 8/9 shapes): `GET /api/projects/search`,
`GET /api/issues/search`, `GET /api/measures/component`, `GET /api/qualitygates/project_status`,
`GET /api/rules/search`. Project creation/deletion and issue transitions are not exposed yet;
projects are created by ingestion.

## Quality gates

Gate conditions are local and deterministic:

```json
{"metric": "bugs", "operator": "GREATER_THAN", "value": "0", "severity": "BLOCKER"}
```

Metrics are computed during ingestion: open issues by severity/type, new issues versus the previous
analysis on the same branch, coverage and duplication when the scanner supplies them. A failed gate
returns `status: "ERROR"` with the failing conditions in the Sonar-compatible project status
document.

## Delivery phases

1. **Foundation** (done): `kkq_*` schema, `KkQualityDao`, contract tests.
2. **SARIF ingestion** (done): analyzer-normalized rules/issues, blob storage, analysis status,
   idempotent re-analysis, issue deduplication by fingerprint, current-state issues with occurrences.
3. **Sonar-compatible read API** (done): projects, issues, measures, rules and gate status, with
   repository-scoped authorization pushed into the query.
4. **kkq-scanner**: container image/CLI that runs the open-source analyzers and uploads SARIF.
5. **Operations**: admin UI, project history, retention, black-box compatibility tests.

## Non-goals

- Running analyzers on the kkRepo server by default (CI execution keeps resource use and attack
  surface bounded; a server-side adapter can be added later).
- SonarQube Java plugin API and the full scanner protocol in the first release.
- Elasticsearch, OrientDB or any external search cluster.
