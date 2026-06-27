## Summary

-

## Validation

- [ ] `mvn -pl server -am test`
- [ ] `mvn -pl compat-test -am -Dtest=MavenMetadataMergeCompatibilityTest,MavenWritePolicyCompatibilityTest,NpmProtocolCompatibilityTest -Dsurefire.failIfNoSpecifiedTests=false test`
- [ ] Live compatibility workflow with the `run-live-compat` label, or `scripts/ci/run-live-compat.sh smoke` for protocol/admin API changes, or not applicable.
- [ ] Other:

## Compatibility and design checklist

- [ ] Protocol behavior changes were compared with the official protocol and Nexus behavior, or this PR does not change protocol behavior.
- [ ] Protocol behavior changes include or update compatibility tests under `compat-test`, or the reason is explained.
- [ ] State, cache, locking, session, background task, upload/delete, metadata, and permission changes describe their multi-replica behavior, or this PR does not touch those areas.
- [ ] Migration changes are idempotent and consider dry-run, resume, checksum validation, and reporting, or this PR does not touch migration.

## Notes for reviewers

-
