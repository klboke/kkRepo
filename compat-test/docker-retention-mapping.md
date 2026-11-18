# Docker cleanup retention

Reference: Nexus Cleanup Policies > Docker > Retain Select Versions. The REST
cleanup-policy `retain` field maps to kkRepo's `criteria.retainCount`. Nexus
orders Docker versions by manifest age, not tag spelling or semantic version.
The reference feature requires Nexus Pro with PostgreSQL.

- https://help.sonatype.com/en/cleanup-policies.html#retain-select-versions
- https://help.sonatype.com/en/sonatype-nexus-repository-3-65-0-release-notes.html
- https://github.com/opencontainers/distribution-spec/blob/main/spec.md#content-management

Compare separate image names, deliberately reversed lexical tag order, two tags
on one digest, republishing, age plus minimum-count rules, preview, and scheduled
deletion. Compare retained tag/digest HTTP reads and bytes, rather than generated
IDs or task response shapes. Reference licensing limitations must be reported as
unsupported, not a passing comparison.

kkRepo counts distinct tagged manifests per (repository, image name). It protects
the newest N by manifest update time, with manifest ID breaking timestamp ties.
This count is evaluated over the full image, independently of the scan page and
before applying age/name filters. Existing age criteria still have to match.
Untagged manifests do not consume retention slots and are not deleted by count
alone; an explicit age rule is required for them. A live parent manifest always
protects its dependencies, including multi-platform images. Such holds and manual
protections can leave more than N artifacts. Physical blob reclamation stays in GC.

Preview is advisory. Execution re-evaluates retention, references and protection
under a database repository lock, coordinated with manifest/tag publication and
deletion on all replicas. Deploy all replicas before enabling this new policy.

Run `compat-test/scripts/docker-retention.py --base-url URL --backend nexus`
against a licensed reference, or `--backend kkrepo` against the candidate. Supply
the administrator password with `KKREPO_PROBE_PASSWORD`. The probe owns a unique
repository/policy, exercises registry HTTP writes and reads, and removes its
fixtures. kkRepo additionally checks the real Quartz schedule and age-based
untagged cleanup. `scripts/ci/check-cleanup.py` runs this probe in both packaged
JVM and Native CI builds.

Reference observation (Nexus 3.94.0, PostgreSQL, 2026-11-16): policy creation with
`retain: 2` and `sortBy: date` returned HTTP 400; the server logged that Docker did
not support the Retain components criterion. The license endpoint returned HTTP
402 (missing/invalid license). This is an unsupported reference feature, not a
passing differential retention test. The probe reports it with exit status 77.
