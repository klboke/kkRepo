# Maven snapshot build cleanup

Reference: Nexus `Maven - Delete SNAPSHOT` (`repository.maven.remove-snapshots`).
The task form maps repositoryName, minimumRetained, snapshotRetentionDays,
removeIfReleased and gracePeriodInDays to the task configuration. kkRepo maps
minimumRetained and snapshotRetentionDays to the existing cleanup policy API,
with an explicit `MAVEN_SNAPSHOT_BUILDS` criteria mode. Release-triggered deletion
and proxy/group targets are outside this first implementation.

The reference task evaluates the most recent asset/blob update within each
build, not the age of its enclosing base-version directory. Timestamp and numeric
build number identify/order builds; classifiers, signatures and checksums are
members of the same build. Test the HTTP artifact/checksum outcomes, metadata
resolution, minimum count and age boundaries against a disposable Nexus repository.
kkRepo additionally preserves builds referenced by current metadata, the latest
build for each classifier/extension, and recently mutated builds. These safety
holds can retain more than the configured minimum and must be visible in preview.

Reference sources:
- https://help.sonatype.com/en/maven-snapshot-tasks.html
- https://maven.apache.org/ref/3-LATEST/maven-repository-metadata/repository-metadata.html
- Nexus RemoveSnapshotsTaskDescriptor / RemoveSnapshotsFacetImpl
