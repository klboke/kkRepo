# Opt-in streaming of uncached proxy downloads

Every proxy repository form includes **Allow streaming downloads**, off by default. The information
icon explains the behavior on hover, focus, or click. The setting is persisted as
`proxy.streamingEnabled`; omitted values preserve the current setting on edit, and explicit `false`
disables it. Hosted/group forms do not have a separate setting. A group uses its selected proxy
member's setting, while both the source and entry group's download policies still apply.

This implements the eligible artifact paths of [issue #420](https://github.com/klboke/kkRepo/issues/420).
It does not make every response from every proxy recipe stream progressively. See the exact scope
below. The [Chinese guide](../../zh/dev/proxy-streaming-downloads.md) covers the same behavior.

## Delivery and cache lifecycle

With the setting off, the existing download, validation, Blob persistence, and response sequence is
unchanged. With it on, an eligible cold GET forwards upstream bytes as the client reads, while writing
a disposable local staging file and calculating SHA-256. This uses bounded buffers and lets a slow
client slow upstream consumption. It does not retain the whole artifact in Java heap.

An admitted progressive response has no MVC async **total-duration** deadline: the global ten-minute
deadline would otherwise truncate a large download even while bytes keep arriving. Existing upstream
I/O timeouts, disconnect cleanup and admission limits still apply. Buffered and unrelated requests
retain their configured MVC deadline.

The final block is held until the declared length, EOF, available expected checksum, and applicable
archive/coordinate checks succeed. A bounded background worker then invokes the existing protocol
writer to persist the Blob and transactionally publish its metadata. Client completion does **not**
wait for object storage. Cache publication is best effort: a storage failure leaves a cache miss and
logs `Progressive cache fill failed`; it cannot invalidate bytes already downloaded successfully.
The staging file and admission reservation are released after publication or failure.

Clients receive earlier bytes before whole-artifact validation completes. A late failure terminates
the response before its declared length; the client must retry. No retry or group fallback is appended
to a committed body. Closing the connection before complete upstream receipt cancels that fill and
removes its temporary file. A completed valid fill may finish publishing after the client disconnects.
This is unsuitable for a requirement to inspect the complete artifact before releasing any bytes:
repositories with enabled scanning on either the source or entry group keep buffered delivery.

No download-state table, durable job, shared partial file, or cross-request download coordination is
introduced. Each replica owns disposable staging/work only. The repository row lock, revision check,
asset uniqueness, and existing writer transaction are used when publishing: a changed/offline/deleted
repository or an already-published asset supersedes the pending fill. If an asset row already exists
at admission, including an incompatible old upstream entry, use synchronous replacement instead.
Network transfers occur before that transaction. Concurrent misses may download twice; only complete cache entries are reusable.
A crashed pod loses unfinished fills. Use ephemeral staging storage and normal temporary-directory
cleanup; staging files are not a resumable durable cache or a recovery journal.

## Supported paths and fallbacks

| Proxy format | Eligible cold bodies |
| --- | --- |
| Raw | File GETs; directory/index discovery remains buffered |
| APT, Yum | `.deb`, `.rpm`; known APT size/SHA-256 is checked and package projection follows cache publication |
| Maven | Artifact coordinates excluding POMs and checksum/signature sidecars |
| npm | Package tarballs on the ordinary artifact path |
| PyPI | Package files, excluding detached `.asc` signatures |
| Go | Module ZIPs |
| Helm | Chart packages; archive/chart metadata validation precedes the final block |
| Cargo | Crates, with the index SHA-256 |
| Dart/Pub | Archives, with expected SHA-256 when supplied and package/version validation |
| Docker/OCI | SHA-256 blobs; manifests and discovery remain buffered |
| NuGet, RubyGems | `.nupkg`/`.snupkg`, `.gem` |
| Conda | `.conda`/`.tar.bz2`; known repodata size/digests are checked before completion |
| Composer, Terraform, Ansible Galaxy, OHPM, Swift, Conan, Alpine, R, Hugging Face | Setting is stored, but their coordinated generation, signature, revision, or projection workflows remain buffered in this version |

Metadata rewriting/signing, internal prefetches, HEAD, refreshes of existing cache entries, responses
without a positive Content-Length, encoded responses, and unsupported paths use the established
buffered path. Admission exhaustion also falls back to that path. The option is permission to use a
supported fast path, not a command to bypass protocol validation.

Single cold Range requests are sent upstream with `Range`/`If-Range` and identity encoding. A valid
206 is passed through without creating a cache entry. An unsatisfied range remains 416; a changed
If-Range may correctly return the full 200. Partial ranges never become complete assets. Opted-in
artifact routes also enable local ranges on cache hits. Invalid/multiple ranges keep existing
handling. Existing upstream and cache validators can differ: after a cache fill, a mismatching
If-Range safely restarts with 200 rather than combining incompatible representations. There is no
cross-request retention/resume of a partial server-side fill.

The implementation follows [HTTP range and If-Range semantics](https://www.rfc-editor.org/rfc/rfc9110.html#section-14)
and [incomplete-message handling](https://www.rfc-editor.org/rfc/rfc9112.html#section-8). Protocol-specific
checks retain the existing [Cargo index checksum](https://doc.rust-lang.org/cargo/reference/registry-index.html),
[Pub archive contract](https://github.com/dart-lang/pub/blob/master/doc/repository-spec-v2.md),
[Helm chart contract](https://helm.sh/docs/topics/chart_repository/),
and [OCI blob digest contract](https://github.com/opencontainers/distribution-spec/blob/main/spec.md).

## Operational limits

- `KKREPO_PROXY_STREAMING_MAX_CONCURRENT`: 16 per process, including queued/running cache fills.
- `KKREPO_PROXY_STREAMING_MAX_STAGED_BYTES`: 21,474,836,480 bytes (20 GiB) per process for admitted
  progressive requests. Reserve twice Content-Length for normal writers and three times for Docker
  (progressive staging, Docker digest spool, and storage stream-upload spool). This is a progressive-path
  budget, not a global cap on existing buffered downloads.
- Cache publication uses two workers and a bounded queue. No network operation holds a repository
  metadata transaction open. Allow enough ephemeral disk for staging and other upload/download work.
- Disabling the setting affects new requests. Already-started clients may finish, but stale fills
  cannot publish after the repository revision changes. Normal cache freshness/revalidation remains.

## Measurements and reproduction

Measured results, environment, raw samples, and the exact runner command are recorded in
[the performance report](proxy-streaming-performance.md). First **body byte**, total client time,
and cache visibility after client completion are separate measurements. The switch primarily removes
the initial no-progress wait; it cannot increase upstream link throughput.

## Compatibility checks

The Nexus suite enables and verifies the setting before its Maven JAR, npm tarball, PyPI package,
Go ZIP, Helm chart, Cargo crate, Pub archive, NuGet, RubyGems, Yum RPM, APT package, Conda package,
probes. Ansible Galaxy and OHPM checks exercise the safe buffered fallback with the setting on. Public HTTPS acceptance compares Docker layer bytes/digests with Nexus
and exercises the real Go, Helm and Docker clients with streaming enabled. OHPM uses its dedicated
protocol/client suite because Nexus has no OHPM recipe.

The JVM and Native real-client matrices seed MySQL proxy fixtures with the default mode and
PostgreSQL fixtures with streaming enabled, including dynamically created APT proxies. Unit tests
also cover HTML rejection, conditional GET cleanup, incompatible-cache replacement, disk admission,
protocol validation before the final block, and projection only after successful publication.
