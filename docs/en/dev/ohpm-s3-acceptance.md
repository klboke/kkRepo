# OHPM acceptance with real S3 storage failures

`scripts/ci/verify-ohpm-s3.py` exercises a running disposable kkRepo candidate against a dedicated RustFS container. It uses Python 3's standard library and Docker, requires no Maven build, and never substitutes file storage for S3. See also [external acceptance](ohpm-acceptance.md).

## Run

The candidate must already be initialized and permit S3 access to the gateway address. Use a disposable database: normal API cleanup can leave registered blobs pending garbage collection. The default addresses assume the candidate and Python run on the same host.

```bash
docker pull docker.m.daocloud.io/rustfs/rustfs:1.0.0
export KKREPO_COMPAT_BASE_URL=http://127.0.0.1:18094
export KKREPO_COMPAT_USERNAME=admin
read -rs -p "Disposable candidate administrator password: " KKREPO_COMPAT_PASSWORD
echo
export KKREPO_COMPAT_PASSWORD
python3 scripts/ci/verify-ohpm-s3.py
unset KKREPO_COMPAT_PASSWORD
```

The password prompt uses Bash. No OHPM client or Python packages are needed. The script uses the existing candidate without bootstrapping, building or restarting it.

Each run creates a random `ohpm-s3-<id>` container, bucket, S3 blob store, hosted repository, temporary user and GenericToken. The user temporarily has the administrator role to avoid confusing storage failures with authorization failures; its credentials are used only for this run's package. Neither existing users/tokens nor other fixtures are modified. RustFS credentials are generated per run and passed to Docker through environment variables.

RustFS and the fault gateway receive independently allocated ports. RustFS binds only to host loopback. For a containerized candidate, specify a reachable gateway using `--gateway-bind 0.0.0.0 --gateway-host host.docker.internal`; firewall this temporary listener appropriately. `--gateway-port` pins its port when necessary. `--image` selects another locally available RustFS image; Docker runs with `--pull=never`. `--report /path/to/new-report.json` chooses an evidence file and refuses to overwrite an existing file.

## Assertions

1. Generate unique, valid HAR and HSP archives with SHA-512 metadata and register a real `s3` / `aws-s3` blob store.
2. Send ordinary OHPM JSON publication with both attachments. Observe the first attachment's successful S3 PUT before injecting failure into the second.
3. The gateway returns S3 XML `503 SlowDown` for **every HSP PUT while the fault is armed**, matching the HSP SHA-256 in the immutable object path. SDK retries and newly generated object IDs cannot escape the fault. HAR writes, reads and rollback deletes continue reaching RustFS.
4. Require publication to return `500`, package metadata and both public attachment paths to return `404`, and the dedicated bucket to be empty after rollback. S3 failures do not map to OHPM `598`.
5. Disarm the fault and retry the identical publication in the same `ALLOW_ONCE` repository. Require `200`, correct version/tag/checksums, and byte-for-byte matching HAR/HSP downloads.
6. Observe successful S3 GETs behind both candidate downloads. Independently list and download the real RustFS objects using SigV4 and compare their bytes with the original archives.

The gateway changes only the TCP destination. It forwards the original request path, headers and body, including the original `Host` used in SigV4 signing. Python's automatic Host generation is explicitly disabled. Authorization values and private credentials are not included in the evidence.

## Evidence and cleanup

Default reports are `artifacts/ohpm-s3/ohpm-s3-<id>.json`. They include image identity, fixture digests/sizes, publication outcomes, HSP retry count, object inventories after failure/recovery/API cleanup, gateway method/path/Host/status events, and cleanup results. Exit code zero means all storage/publication assertions passed; inspect `residuals` separately for incomplete API cleanup.

Cleanup runs even when an assertion fails: disarm the gate, unpublish the owned package, delete the owned repository, attempt blob-store deletion, and delete the temporary user and its token. Then stop the gateway and remove only the dedicated RustFS container and its anonymous volumes. No shared database edits, global garbage collection, or other fixture deletion is performed.

Blob-store deletion may return `409` while registered blobs or pending deletions remain. The report preserves that store ID. Removing the dedicated container destroys any remaining test objects but **does not prove application garbage collection completed**; the retained candidate records refer to the removed test endpoint. Dispose of the candidate database to finish cleanup. An interrupted/killed Python process may require manual removal of the container named by the run ID.

## Observed run

On 2026-11-06 (Asia/Shanghai), the final candidate rebuilt and restarted after the full regression and AOT processing passed on both databases:

| Database | Run | Local report |
| --- | --- | --- |
| PostgreSQL | `ohpm-s3-803cd11f256a` | `artifacts/ohpm-s3/stage8-final-postgres.json` |
| MySQL | `ohpm-s3-ee0d83e3c523` | `artifacts/ohpm-s3/stage8-final-mysql.json` |

Both runs produced these observations:

| Observation | Result |
| --- | --- |
| RustFS image | `1.0.0`, image ID `sha256:8cc9801755448b71a786705ce76692c77e14936cccd87cf2fc31842e58f4d1ff` |
| Initial HAR PUT | `200` from RustFS |
| HSP PUT attempts | Four, all injected `503` |
| Failed publication / metadata | `500` / `404`; both public attachment paths also `404` |
| Rollback | HAR DELETE `204`; bucket inventory empty after failure |
| Recovered publication | `200`; both candidate downloads and direct S3 downloads matched |
| API cleanup | Unpublish `200`, repository delete `204`, user/token delete `200` |
| Residual boundary | Blobstore delete returned `409`; two objects remained before dedicated RustFS teardown; IDs are recorded in each report |

Dedicated containers and volumes were removed. Reports are locally generated acceptance artifacts, not source-controlled files. Re-run after changing and rebuilding the candidate instead of carrying results forward from an older jar.

This test covers small, normal OHPM dual-attachment publication and actual S3 single-object PUT/GET failure recovery. It does not establish S3 multipart-upload interruption, process-crash recovery, production OSS/S3 behavior, eventual GC completion, multi-replica behavior, or execution of the HSP on a HarmonyOS device.
