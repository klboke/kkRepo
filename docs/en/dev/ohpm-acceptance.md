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

Client installation removes the previous modules directory and lock file so a pre-existing installation cannot count as a successful download. Separate assertions compare HAR/HSP bytes and SHA-512.

Real S3-compatible storage has a separate second-attachment failure, rollback and retry script; see [S3 fault acceptance](ohpm-s3-acceptance.md). The HTTP/client blackbox uses file test storage and does not replace that storage verification.

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

Run seven HTTP acceptance cases without installing OHPM:

```bash
export OHPM_COMPAT_LOGIN_ENABLED=true
bash scripts/ci/run-ohpm-compat.sh blackbox
```

The expected Login setting must match the server. Explicitly setting it prevents an intended signing check from accidentally exercising only the disabled branch. Enable server-side signing with `kkrepo.ohpm.login.enabled=true`.

Run all eight cases, including real client publication and installation:

```bash
export OHPM_COMMAND=/absolute/path/to/ohpm
export OHPM_EXPECTED_VERSION=6.0.1
bash scripts/ci/run-ohpm-compat.sh client-e2e
```

Both commands check Surefire XML counts and reject missing, failed, errored or skipped acceptance cases. Reports are under `compat-test/target/surefire-reports/*OhpmRepositoryBlackBoxCompatibilityTest*`. The shared entry points are `bash scripts/ci/run-live-compat.sh ohpm` and `ohpm-client-e2e`.

Set `OHPM_COMPAT_UPSTREAM_BASE_URL` when the candidate cannot access its public `KKREPO_COMPAT_BASE_URL` directly. For example, a host client may use a mapped Docker port while the candidate must reach the upstream at `http://kkrepo:8080`. Client download links must still use the public candidate origin.

## CI and versions

`.github/workflows/ohpm-compat.yml` runs a MySQL/PostgreSQL × Login disabled/enabled matrix for relevant pull requests and manual dispatch. Each job builds and starts an independent executable jar, initializes a disposable candidate, runs the complete client suite and uploads reports.

CI installs OHPM 5.0.2 from Huawei Cloud's official mirror, pinned to SHA-256:

```text
a91888b3c1623d29fd0e387eee4f5e9523cc8f6242de9cb4be3ef8c46eab7e4a
```

The installer verifies the archive, extracts only the OHPM subtree and refuses to overwrite an existing installation:

```bash
OHPM_INSTALL_DIR=/absolute/path/to/new/toolchain bash scripts/ci/setup-ohpm-legacy.sh
```

Final local external-candidate runs verified OHPM 6.0.1 on PostgreSQL with Login enabled, plus all four MySQL/PostgreSQL × Login disabled/enabled combinations with OHPM 5.0.2: five runs of eight cases each, with no skips. Enabled Login client checks publish with an encrypted signing key and install the result; `OhpmHostedRuntimeTest` separately covers 6.0.1 across two instances. Results apply only to tested versions. HSP fixtures prove registry protocol and file transfer behavior, not execution on a HarmonyOS device. Upstream Actions require maintainer approval; passing local acceptance does not establish remote CI execution.
