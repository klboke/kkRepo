# Admin storage usage: Nexus mapping and counting contract

Reference: Nexus Repository 3.94.0, `GET /service/rest/v1/blobstores`, and
[Sonatype's Blob Stores documentation](https://help.sonatype.com/en/blob-stores.html).
The Blob Stores grid exposes Blob Count and Used Size; the REST response calls
these `blobCount` and `totalSizeInBytes`. kkRepo's previous `objectCount` and
`totalSize` fields are unimplemented health-summary placeholders and are not used
as inventory data by the new UI.

## Reference observation

On 2026-09-19 an isolated File store and Raw hosted repository were created on the
local Nexus 3.94 reference. Two different 3-byte files produced `blobCount=2` and
`totalSizeInBytes=6`. Deleting one Asset reduced the repository's asset listing to
one entry while both blob metrics stayed unchanged. The fixture repository and
store were removed afterward; no shared store was compacted.

Reproduce with:

```sh
mvn -B -ntp -pl compat-test -am -Dtest=BlobStoreUsageReferenceTest \
  -Dsurefire.failIfNoSpecifiedTests=false -Dcompat.storageUsage.enabled=true test
```

## kkRepo mapping

| Admin view | Endpoint | Response metric |
| --- | --- | --- |
| Blob Stores / Blob Count | `GET /internal/blob-stores/statistics/usage` | `usage[id].blobCount` |
| Blob Stores / Stored size | Same | `usage[id].totalBytes` |
| Blob Stores / Pending cleanup | Same | `pendingDeletionCount`, `pendingDeletionBytes` |
| Repositories / Asset Count | `GET /internal/repositories/statistics/usage` | `usage[id].assetCount` |
| Repositories / Logical size | Same | `totalBytes`, `unknownSizeCount` |

Blob metrics count database-registered objects, including soft-deleted objects
awaiting physical GC. Shared references count once. Temporary uploads,
unregistered objects and bucket version history are excluded. Non-asset objects
registered in `asset_blob` (such as scan documents) are included. A GC backlog is
inventory awaiting reclamation, not a promise of immediately reclaimable bytes.

The raw repository endpoint counts each repository's own asset rows, including
metadata, checksums and group caches. Members are not summed. The admin view
shows group Asset Count and logical size as `—` (not applicable), excludes groups
from usage summaries and sorts them after numeric values in either direction.
Matching repositories still includes groups; group-only usage summaries show `—`.
Registered group blobs remain included in Blob Stores totals. Logical size counts shared
content once per asset, so adding repository sizes is not a physical storage
estimate. Unknown asset sizes produce a lower-bound size indicator. These
repository metrics and pending cleanup breakdown are kkRepo admin extensions,
not claims of identical Nexus repository-list fields.

Endpoints return `{usage: {"<id>": {...}}, calculatedAt: <timestamp>, maxAgeSeconds: 30}`.
Every count and byte field inside `usage` is a decimal JSON string, including
zero (`"0"`). JDBC reads aggregates as `BigInteger`, preserving MySQL DECIMAL and
PostgreSQL NUMERIC sums even when their value exceeds `Long.MAX_VALUE`; the
response therefore survives JSON parsing without precision loss.
The UI uses `BigInt` for parsing, sorting and filtered sums, including totals
beyond the range of an individual Java `long`. Byte display is rounded to one
decimal using integer arithmetic; `calculatedAt` and `maxAgeSeconds` are unchanged.
The UI loads them separately from configuration lists. Missing/failed values
render as unavailable, never fabricated zero. Each replica uses a rebuildable
30-second cache and coalesces concurrent loads. Authorization is checked on every
request; only permitted repository IDs are selected from the cached snapshot.
Empty entities receive explicit zero metrics. Browse/uploadable-repository and
configuration lists do not query inventory aggregates. Multi-segment routes avoid
shadowing a repository named `statistics` or `counts`.
