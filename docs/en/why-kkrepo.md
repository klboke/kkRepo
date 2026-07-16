# Why We Built kkRepo

kkRepo was not created because we wanted to rebuild an artifact repository for its own sake. It was created because our existing Sonatype Nexus Repository deployment could no longer meet our scale requirements for stability, upgrades, cost, and migration effort.

Sonatype, Nexus, and Nexus Repository are trademarks of Sonatype, Inc. kkRepo is an independent open source project and is not affiliated with, endorsed by, sponsored by, or connected to Sonatype, Inc. References to Sonatype Nexus Repository are used only to describe compatibility, migration, or interoperability.

## The Incident That Started It

We had long been using Sonatype Nexus Repository `3.29.2-02`. It was deployed in Kubernetes, with ESSD mounted as a local disk, and metadata still depended on OrientDB at that time.

Back in 2023, we only adjusted a JVM memory setting and restarted the service, but that triggered OrientDB file corruption. Nexus then completely crashed and could not be brought up, directly affecting production build and release for all business lines.

That incident took about one day to recover. While investigating OrientDB-related errors online, we found that many similar reports came from Nexus operations scenarios. For us, this was not an ordinary incident. It was a clear signal: once an artifact repository becomes core CI/CD infrastructure for the whole company, embedded databases and local filesystem state become high-risk architecture choices.

## Scale Changed

By 2026, our artifact repository usage had grown to another level, and our requirements for stability, scalability, and operability had also increased.

We needed:

- Multi-replica deployment and rolling upgrades.
- Core state stored in an external database that can be governed, backed up, and restored.
- Blob data stored in object storage such as OSS/S3.
- Fast failover and recovery during incidents, instead of relying on a single local data directory.
- Client protocols, URLs, and permission/authentication model kept compatible where needed for migration.

The natural first reaction was to upgrade the existing Sonatype Nexus Repository deployment.

## The Reality Of Upgrades And Commercial Editions

After deploying a newer version, we found that the new Community Edition limits were not friendly to our scale.

According to Sonatype official documentation, Nexus Repository Community Edition is intended for individuals and small teams, supports up to `40,000` components and `100,000` requests per day. Sonatype's official pricing page lists Nexus Repository Pro Edition starting at `$1,620 / year + consumption`; larger scale or special deployment models require separate commercial discussion.

Public discussion in Sonatype's `nexus-public` repository reflects a similar concern. In [Issue #883](https://github.com/sonatype/nexus-public/issues/883), a user reported that after upgrading to `3.87.0`, Community Edition component and request limits had been reduced, then asked whether those limits might continue changing in later releases. For us, this kind of public feedback means the Community Edition boundary is not only about the current quota, but also about uncertainty for future upgrades and capacity planning.

We are willing to pay for infrastructure software, but at our artifact scale and traffic scale, the new Community Edition limits do not apply to us, and the commercial edition price and purchasing uncertainty exceeded expectations.

This means continuing along the existing upgrade path does not solve the problems we actually care about at low cost:

- OrientDB stability risk in old versions.
- Usage limits in newer Community Edition versions.
- Commercial cost and scale pricing uncertainty of the Pro version.
- Migration, verification, and rollback risk when upgrading from an old version to a new architecture.

## Why Not Use An Existing Alternative Directly

We also seriously evaluated other artifact repository solutions, but did not find one suitable enough.

The real cost is not just "deploying a new service"; it is a company-wide migration:

- Server operations engineers need to migrate all historical data to the new platform.
- Users, roles, permissions, tokens, repository configuration, and blob data need to be maintained again.
- Users need to modify CI, build scripts, Maven settings, npm registry, PyPI index-url, Go proxy, Helm repo, Cargo registry config, Pub hosted URL, Composer repository URL, Terraform `host.services`, and similar configuration one by one.
- In a large company, this kind of change affects many business lines, build tasks, and release flows.

If a new platform cannot stay compatible with the `/repository/<repo>/...` URL layout, client protocols, and permission/authentication model that existing clients depend on, migration cost becomes very high and transparent cutover becomes difficult.

## Why We Chose To Build kkRepo

With AI coding assistants available today, we chose to solve the problem ourselves.

The core ideas of kkRepo are:

- Compatible with client protocol behavior needed by existing deployments.
- Compatible with the `/repository/<repo>/...` URL layout used by existing clients.
- Compatible with the permission/authentication model where required for migration.
- Use MySQL to store metadata, permissions, tokens, audit logs, migration state, and cross-replica coordination state.
- Use OSS/S3 to store artifact blobs.
- Do not depend on OrientDB, embedded Elasticsearch, or a local persistent blob filesystem.
- Support migration from existing Sonatype Nexus Repository instances to kkRepo, making the migration as transparent as possible to users.

This project was born with the help of AI. The main code was completed by AI in about one week. Humans mainly handled product goals, architecture constraints, compatibility requirements, issue diagnosis, and a small amount of code review.

To control the risk of this development approach, we did not rely on the subjective judgment that "it looks implemented." Instead, we put correctness behind black-box tests and real traffic validation:

- The project includes an independent `compat-test` module for black-box compatibility tests against real Sonatype Nexus Repository reference instances.
- After migrating to kkRepo, we mirrored 100% of real production traffic to kkRepo through Istio and observed response status, errors, latency, and protocol behavior.
- Features, protocols, and boundary cases are covered as much as possible by black-box tests, mirrored traffic, and production metrics.

Thanks to Codex and Claude. Without AI, we would not have chosen this way to solve the problem.

## Migration Result

We have now migrated from the old deployment to kkRepo with zero downtime.

After migration:

- The original artifact repository domain points to kkRepo.
- Client configuration does not need to change.
- CI continues to use the original Maven settings, npm registry, PyPI index-url, Go proxy, Helm repo, Cargo registry config, Pub hosted URL, Composer repository URL, and Terraform `host.services` URL.
- Users did not perceive the migration process.
- No business-side failure feedback was received after migration.

More importantly, after migration we gained operational capabilities that were previously difficult to have:

- Rolling upgrades.
- Scaling to multiple instances.
- Automatic elastic scaling.
- Core state in MySQL, which is easier to back up, troubleshoot, and restore.
- Blob data in OSS/S3, no longer tied to a single local data directory.

This is the starting point of kkRepo: providing a simpler, controllable, and recoverable architecture for teams facing similar migration and operations constraints.

## Why Open Source

We chose to open source kkRepo because we hope teams facing similar problems can have one more open source option.

If you are also running an older Sonatype Nexus Repository deployment and facing embedded database stability problems, upgrade-path problems, Community Edition usage limits, commercial edition cost, migration workload, or insufficient multi-replica deployment capability, kkRepo aims to provide a smoother migration path.

The larger hope is that this project can continue growing in the open source community: keep improving compatibility, continuously strengthen migration and operations capabilities, and expand support for more artifact repository formats through community feedback and contributions.

## References

- Sonatype Community Edition Onboarding: https://help.sonatype.com/en/ce-onboarding.html
- Sonatype Platform Pricing: https://www.sonatype.com/products/pricing
- Sonatype nexus-public Issue #883: https://github.com/sonatype/nexus-public/issues/883
