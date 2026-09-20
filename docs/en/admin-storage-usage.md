# Admin storage usage

The Blob Stores and Repositories views provide a current inventory overview for
capacity investigation. Start with the largest stores, inspect pending cleanup,
then use a store's **Repositories** action to see its configured repositories
sorted by logical size. The exact store filter can be cleared with **All blob
stores**. Summary cards follow the current filter rather than displaying hidden
or unrelated totals.

## Metrics and decisions

| Operator question | View and metric | Interpretation |
| --- | --- | --- |
| Where is registered storage concentrated? | Blob Stores: Blob Count, Stored size; sort by largest size | Each registered object counts once, including shared blobs and non-asset documents. |
| Why has deleting content not released space? | Blob Stores: Pending cleanup bytes and blob count | Soft-deleted objects waiting for physical GC; already included in Stored size. |
| Which repositories should I inspect? | Store's Repositories action; repository size sort | Filters by configured store; repositories can also refer to shared content in other stores. |
| How many files does a repository hold? | Repositories: Asset Count | Hosted uploads and proxy caches, including persisted metadata/checksums; groups display `—`. |
| Which repositories have the most logical content? | Repositories: Logical size | Sum of asset sizes, with shared content counted per asset. It is not physical occupied space. |
| Is the inventory current and complete? | Updated time, loading/error markers, size lower bound | Snapshots are cached for 30 seconds; `≥` indicates some asset sizes are unknown. |

Hosted repositories count stored uploads, proxy repositories count their local
cached content. Group repositories display `—` for Asset Count and logical size
and are excluded from usage totals and numeric sorting. They still count toward
Matching repositories; a group-only filter displays `—` in both usage summaries.
The tooltip identifies this as not applicable, rather than a statistics failure.
Some group protocols persist cache references or generated metadata: the raw
statistics endpoint retains these entries, and any registered blobs remain in
Blob Stores totals. Asset Count is not a package/version count. Pending cleanup
is an inventory backlog, not a claim that every byte can be reclaimed immediately.

Blob size comes from the database. It excludes unregistered objects, incomplete
uploads, temporary files and object-store version history. It does not query cloud
billing, bucket quota or remaining disk capacity. Repository-to-store filtering
uses repository configuration; shared/group references can cross that boundary,
so repository totals are never presented as a reconciliation of physical bytes.

## Freshness, failures and permissions

The first summary card shows the snapshot update time; its info icon explains the
cache and refresh behavior. Metric definitions appear in info popovers beside
the corresponding summary titles, without standalone explanation lines. Long storage paths and URLs are truncated in the table
with their full values available on hover. Expand the chevron to read and select
the complete value within the cell, or use the copy icon to copy it directly.
Copying also supports HTTP deployments; if the browser blocks copying, the UI
offers manual selection rather than reporting success.

The configuration list renders first and usage loads independently. Refresh reloads
the latest available snapshot; it does not bypass the 30-second cache. A failed or
missing statistic displays `—`, an in-flight statistic displays `…`, and an empty
entity displays `0`. A statistics failure does not prevent configuration editing.
No count is used to authorize a request or enforce a quota.

Each replica rebuilds its own short-lived cache from the shared database and
coalesces simultaneous cold requests. Authorization is re-evaluated on every
request before selecting repository statistics, including while a snapshot is
cached. Repository Browse endpoints and configuration lists do not gain aggregate
queries. Blob statistics require the existing blob-store read permission.

## Implementation and scale

Two batch aggregates replace per-row queries: one on `asset_blob` and one on
`asset`. The existing `(blob_store_id, deleted_at, size)` index covers blob inventory.
Migration V54 adds `(repository_id, size)` to cover repository count/size scans:
MySQL builds it online; PostgreSQL builds it concurrently. The index consumes disk
and adds index maintenance to asset writes; schedule upgrades with appropriate
I/O headroom. Statistics remain proportional to the inventory size on cache miss,
not constant-time. Cache hits perform no aggregate queries. Each active replica
can perform its own two aggregate queries per cache lifetime.

See [Nexus reference mapping](../../compat-test/admin-storage-usage-mapping.md) and
[the reproducible million-row benchmark](admin-storage-usage-performance.md).

Historical growth charts, forecasts, quota enforcement and automatic cleanup
recommendations need retained time-series data and are separate follow-up work.
This iteration provides current, clearly scoped measurements and navigation.
