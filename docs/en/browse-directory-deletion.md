# Delete a directory from Browse

Administrators can delete directory contents from hosted Maven, npm, PyPI,
NuGet, Yum, RubyGems and Raw repositories. Open a directory in Browse,
select **Delete**, review the repository, full path, component/file counts and
all affected paths, then type the exact directory path to enable confirmation.
Cancel or Escape closes the preview without changing the repository.

The action requires the existing Browse deletion permission (`nexus:*`). It is
not available on repository roots, proxy/group repositories, or other formats.
Helm is deferred because its Nexus virtual package folders are not yet mapped by kkRepo Browse.
Existing individual component and asset actions remain available. Directory deletion
is permanent; this feature does not add an undo or restore mechanism. Blob
storage space is reclaimed separately through reference-aware garbage collection.

A preview includes at most 500 files and 1,000 tree nodes. Larger selections are
rejected without deleting anything: choose a smaller directory. The server does
not execute a truncated preview. For package-based formats, select a directory
containing complete packages; a partial package selection cannot silently delete
files outside that directory. Generated metadata is listed separately, managed
by the package protocol, and may remain or be regenerated after deletion.

If contents change before execution, the server rejects the operation and asks
for a new preview. The preview can be created on one replica and executed on
another. Database locks and a single transaction protect the confirmed asset
identities; new uploads committed after final validation are retained. A failed
transaction rolls back the deletion and its metadata rebuild markers together.
The management audit log includes the actor, repository, directory, result and
deleted file count. Downloads and index updates follow the existing protocol
behavior; other replicas observe download-cache invalidation on the existing
watermark polling interval (two seconds by default), and some index rebuilds
complete in the background.

After deletion, Browse retains the surviving expanded directories and tree
scroll position, clamped only when the remaining content is shorter. The nearest
surviving parent is selected if the previous selection was removed.
