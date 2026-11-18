# OHPM external acceptance and CI

`OhpmRepositoryBlackBoxCompatibilityTest` accesses a separately started kkRepo over HTTP. It does not call server services or query the database. Huawei's protocol and real OHPM clients are the reference; Nexus is not required.

## Coverage

| Case | Assertions |
| --- | --- |
| HAR/HSP × normal/stream publication | Authentication, duplicate rejection, metadata, SHA-512, both downloaded files, ETag, tags and unpublish |
| Proxy and Group | Upstream credentials, rewritten links, consistent file origins, member ordering, rejected writes and cached reads after upstream unpublish |
| Management and import | HAR/HSP upload, browse/checksum details, dry-run without publication, apply/resume and Group downloads |
| Optional Login | Configuration assertion; disabled 404; enabled signature verification, replay rejection, management isolation and key disabling; client mode also publishes with an encrypted signing key and installs the result |
| Real client | Small/large HAR/HSP publication, hosted/proxy/group installation, tag changes and unpublish |
| Cleanup boundary | Automatic cleanup capability and policy rejection; explicit OHPM deletion removes HAR/HSP and hosted/group metadata |

Client installation removes the previous modules directory and lock file so a pre-existing installation cannot count as a successful download. Separate assertions compare HAR/HSP bytes and SHA-512.

Real S3-compatible storage has a separate second-attachment failure, rollback and retry script; see [S3 fault acceptance](ohpm-s3-acceptance.md). The HTTP/client blackbox uses file test storage and does not replace that storage verification.

## Transaction and download regressions

`OhpmHostedRuntimeTest` pauses the real file storage adapter inside `putFile`, asserts no database transaction is active there, and uses another connection to acquire the existing package lock. The import variant also acquires the job and item locks while storage remains paused. Both database runtimes retain atomic HAR/HSP rollback, transient retry and shared-blob preservation checks.

`OhpmProxyRuntimeTest` sends eight concurrent cold file requests across two replicas to a deliberately delayed upstream. It requires one upstream file GET and eight byte-identical successes. A corrupt first owner must fail, release its lease, and allow one successor fetch to satisfy the seven waiters. DAO contracts cover competing lease acquisition, expiry takeover and rejected stale owners on both databases; service tests prevent publication after lease loss or source changes.

The compact-version DAO contract stores 1,000 versions and verifies that only the requested manifest and root type/cache fields are returned, including safely bound dotted/prerelease JSON keys. Group regression tests prohibit full member metadata calls during repeated warm file downloads while retaining source-fingerprint and HAR/HSP selection checks. These tests establish coordination and data-access behavior, not production throughput, S3 latency or allocation benchmark results.

## Candidate setup

Prerequisites are JDK 25, Maven 3.9+, Python 3, and MySQL/PostgreSQL. Client mode also requires OHPM and its supported Node.js runtime.

```bash
mvn -pl server -am -DskipTests package spring-boot:repackage
```

Start the executable jar with a disposable database, storage and encryption secrets. Use the `test` profile only for disposable acceptance environments. Allow the test upstream, for example with `KKREPO_OUTBOUND_ALLOWED_HOSTS=127.0.0.1`, and set `KKREPO_LOGIN_RATE_LIMIT_PER_MINUTE=200` to accommodate fixture identity creation.

Initialize a fresh instance with:

```bash
export KKREPO_COMPAT_BASE_URL=http://127.0.0.1:18090
export KKREPO_COMPAT_USERNAME=admin
export KKREPO_COMPAT_PASSWORD=<disposable administrator passphrase>
export OHPM_COMPAT_BLOB_PATH=/absolute/path/to/disposable/blobs
python3 scripts/ci/setup-ohpm-candidate.py
```

The helper refuses an already initialized instance. Tests create dedicated users and randomly named repositories, unpublish hosted packages and remove deletable repositories/users afterward. The generic repository API refuses deletion of proxies that still contain cached components; tests explicitly log those retained proxy names. Destroy the disposable database and blob storage to finish cleanup. These write tests must target a disposable candidate.

## Commands

Run eight HTTP acceptance cases without installing OHPM:

```bash
export OHPM_COMPAT_LOGIN_ENABLED=true
bash scripts/ci/run-ohpm-compat.sh blackbox
```

The expected Login setting must match the server. Explicitly setting it prevents an intended signing check from accidentally exercising only the disabled branch. Enable server-side signing with `kkrepo.ohpm.login.enabled=true`.

Run all nine cases, including real client publication and installation:

```bash
export OHPM_COMMAND=/absolute/path/to/ohpm
export OHPM_EXPECTED_VERSION=6.0.1
bash scripts/ci/run-ohpm-compat.sh client-e2e
```

Both commands check Surefire XML counts and reject missing, failed, errored or skipped acceptance cases. Reports are under `compat-test/target/surefire-reports/*OhpmRepositoryBlackBoxCompatibilityTest*`. The shared entry points are `bash scripts/ci/run-live-compat.sh ohpm` and `ohpm-client-e2e`.

Set `OHPM_COMPAT_UPSTREAM_BASE_URL` when the candidate cannot access its public `KKREPO_COMPAT_BASE_URL` directly. For example, a host client may use a mapped Docker port while the candidate must reach the upstream at `http://kkrepo:8080`. Client download links must still use the public candidate origin.

## CI and versions

Full E2E includes `ohpm` in the existing `core` client shard on JVM/Native × MySQL/PostgreSQL. `live-compat.yml` reuses the shared candidate image artifacts, SDK image, fixture setup and log upload. It configures the existing secondary replica with OHPM Login enabled using a Compose override of the same candidate image; the primary retains Login disabled. Only the OHPM runner exercises both variants, so Login does not multiply the Full E2E matrix.

`run-client-e2e.sh` dispatches OHPM to `ohpm-client-e2e.sh`, which requires both candidate URLs and checks their Login configuration before testing. Each variant retains its Surefire XML, acceptance log and cleanup report under `artifacts/client-e2e/ohpm/login-{false,true}/`. The combined `cleanup-ohpm.json` requires automatic-cleanup rejection and verified protocol deletion for both variants. The Full E2E completeness gate rejects missing OHPM duration entries, missing Login variants or incomplete cleanup proofs.

For targeted execution, dispatch `.github/workflows/ohpm-compat.yml`; it calls the same reusable workflow with `client_protocol: ohpm` for both runtimes. It has no separate pull-request trigger or per-Login candidate build. Locally, use initialized disposable candidates sharing the same database/blob store and built from the same artifact:

```bash
export KKREPO_COMPAT_BASE_URL=http://127.0.0.1:18090
export OHPM_LOGIN_BASE_URL=http://127.0.0.1:18094
CLIENT_E2E_TESTS=ohpm bash scripts/ci/run-client-e2e.sh
```

`OhpmProxyRuntimeTest` additionally stores valid HAR bytes through Docker before reusing them in both OHPM hosted and proxy repositories, then checks Group downloads across replicas on both databases. The shared writer merges verified SHA-512 with the attributes returned by the DAO's transaction-locked blob reuse/insert path, preserving `dockerDigest` and concurrent attributes. Linking a blob without reading its content does not invent a missing checksum.

CI installs OHPM 5.0.2 from Huawei Cloud's official mirror, pinned to SHA-256:

```text
a91888b3c1623d29fd0e387eee4f5e9523cc8f6242de9cb4be3ef8c46eab7e4a
```

The installer verifies the archive, extracts only the OHPM subtree and refuses to overwrite an existing installation:

```bash
OHPM_INSTALL_DIR=/absolute/path/to/new/toolchain bash scripts/ci/setup-ohpm-legacy.sh
```

Enabled Login client checks publish with an encrypted signing key and install the result; `OhpmHostedRuntimeTest` separately supports client acceptance across two instances. Results apply only to actually executed versions and runtimes. HSP fixtures prove registry protocol and file transfer behavior, not execution on a HarmonyOS device. Wiring Native into Full E2E does not establish a successful Native run; consult the matching workflow reports. Candidate-build reuse is structural, with no measured CI resource-savings claim.
