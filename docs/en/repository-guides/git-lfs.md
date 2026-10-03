# Git LFS Repository Guide

Create a `gitlfs-hosted` repository in Admin, select an OSS/S3 blob store, and grant readers `read`
and publishers `add`. Keep Git commits and refs in your existing Git server. The LFS endpoint is:

```text
https://repo.example.com/repository/project-lfs/info/lfs
```

## Configure Git

Install Git LFS (the acceptance suite pins 3.8.0), then configure the working repository:

```sh
git lfs install --local
git config -f .lfsconfig lfs.url https://repo.example.com/repository/project-lfs/info/lfs
git config credential.https://repo.example.com.useHttpPath true
git lfs track '*.psd'
git add .lfsconfig .gitattributes design.psd
git commit -m "Store design assets in Git LFS"
git push origin HEAD
```

Provide Basic credentials through your Git credential helper. Keep passwords and tokens out of
`.lfsconfig` and URLs. An SSH Git remote does not grant access to the separate kkRepo endpoint.
Use `git lfs env` to check the effective URL when local or remote-specific overrides exist.

For CI, a `GenericToken` can be sent as a URL-scoped HTTP header through Git's environment config.
Supply `KKREPO_LFS_TOKEN` from the CI secret store and keep shell tracing disabled:

```sh
export GIT_CONFIG_COUNT=1
export GIT_CONFIG_KEY_0=http.https://repo.example.com/repository/project-lfs/.extraHeader
export GIT_CONFIG_VALUE_0="Authorization: Bearer $KKREPO_LFS_TOKEN"
git lfs push --all origin
unset GIT_CONFIG_COUNT GIT_CONFIG_KEY_0 GIT_CONFIG_VALUE_0
```

The owner must have the required repository permissions. This uses the existing GenericToken bearer
contract; arbitrary API tokens are not Basic passwords. Each action checks current credentials and
grants, so a revoked token cannot finish publication using an older Batch action.

## Downloads And History

```sh
git clone <your-git-remote>
cd <checkout>
git lfs pull
git lfs fetch --all
git checkout <historical-commit>
git lfs fsck
```

Each object is immutable and addressed by its SHA-256. Repeated pushes skip content already stored
in this repository. kkRepo validates the complete length and digest before publishing an asset.
Downloads support HEAD, single byte ranges and conditional requests; all use repository permissions
and the configured artifact download policy. Browse/Search display OIDs, sizes and checksums rather
than original filenames, which belong to Git history.

## Operations And Limits

- Hosted only; no proxy/group, Git server, file locks, tus, client-side resumable multipart, or direct S3 URLs.
- Upload through Git LFS. The generic component upload API/UI is unavailable for this format.
- Automatic Cleanup is disabled: kkRepo cannot determine which old objects Git commits still reference.
  Manual deletion can break historical checkouts. Back up both database and object storage.
- Opaque LFS binaries have unsupported scan coverage (`NOT_APPLICABLE`), not a clean vulnerability verdict.
- `KKREPO_GITLFS_MAX_OBJECT_BYTES` defaults to 32 GiB; `KKREPO_GITLFS_CONCURRENT_UPLOADS` defaults to 4 per pod.
  Batch accepts up to 1,000 objects and 1 MiB of JSON. A 429 means retry after the advertised delay;
  a stale upload action requires a new Batch request.
- Configure the reverse proxy's body limit and idle timeouts for your files and disable request buffering
  if it would spool whole uploads to disk. The storage credentials need multipart list/abort permissions
  as well as object read/write/delete. Failed attempts are recovered across replicas.

## Migrate Nexus Hosted Content

The migration preflight recognizes Nexus 3.94.x datastore Git LFS content only when its tables and
OID/size/checksum shape are proven. Unknown versions/shapes and OrientDB content require manual
handling. Create the matching target hosted repository and use Nexus Repository Data migration,
with checksum validation enabled. Dry-run, persistent progress, resume and reports follow the normal
migration workflow; every imported object passes the same upload verifier.

Stop source writes for final synchronization, then change the effective LFS URL (or switch the existing
repository URL to kkRepo). Keep the Git remote and pointers unchanged. Verify all refs with `fetch --all`,
`fsck`, and historical checkouts before retiring the read-only source. Preserve objects written after
cutover before rolling back.

See the [compatibility matrix](../compatibility-matrix.md),
[implementation design](../../zh/dev/git-lfs-repository-design.md), and
[acceptance evidence](../../zh/dev/git-lfs-acceptance.md).
