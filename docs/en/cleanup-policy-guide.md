# Cleanup Policy Guide

This guide is for kkRepo repository administrators. It explains how to create a cleanup policy,
preview its decisions with a bounded Try Run, execute it manually, enable an independent schedule,
and understand when storage is actually reclaimed. Implementation and multi-replica details are in
the [Cleanup Policy design notes](../zh/dev/cleanup-policy-design.md).

The Chinese version is available in the
[Cleanup Policy 使用指南](../zh/cleanup-policy-guide.md).

## Capability Boundaries

- Cleanup policies support every current repository format and can target hosted and proxy
  repositories. Group repositories are not selectable because they do not own an independent set
  of cleanup subjects; select their hosted or proxy members instead.
- One policy can target up to 100 repositories of one format. A repository can belong to multiple
  policies, and every policy can use its own rules and schedule.
- Cleanup operates on a complete logical artifact version, not an arbitrary blob or file. The
  format-specific delete path also updates generated metadata, indexes, and caches.
- Try Run, manual execution, and scheduled execution are available for all current formats. The
  **Keep newest versions** rule is available only where kkRepo has a validated protocol version
  comparator: Maven, Cargo/Rust, Dart/Pub, Terraform, Swift Package Registry, Ansible Galaxy, and Conda.
- Actual execution is irreversible. kkRepo does not currently provide a cleanup-specific restore
  window, so keep a tested database and blob-store backup before enabling deletion in production.

For Conda, one cleanup subject is one complete channel/subdir/name/version/build package. **Keep newest versions** uses Conda VersionOrder within each `(channel, subdir, name)` family and preserves every build of a retained version. Hosted deletion writes a tombstone and advances the channel revision so repodata, channeldata, Browse, Search, and group bindings rebuild consistently. Proxy cleanup removes only the local cached package asset while retaining validated upstream inventory for the next fetch.

## How Rules Combine

| Rule | Meaning |
| --- | --- |
| **Name/path pattern** | Optional scope filter. **Wildcard** matches the whole name or displayed coordinate/path and supports `*` and `?`; **Regular expression** uses kkRepo's validated regular-expression matcher. A pattern does not make an artifact eligible by itself. |
| **Published older than (days)** | Selects a subject only when its format-specific publication/update watermark is older than the configured age at the start of the run. |
| **Last downloaded older than (days)** | Selects a subject only when it has a recorded download watermark older than the configured age. Artifacts without a download timestamp are skipped. |
| **Keep newest versions** | Protects the newest `N` versions inside each package or module family using that format's protocol comparator. `0` protects no versions. The field is disabled for formats without a validated comparator. |

At least one deletion criterion is required: publication age, last-download age, or version
retention. A name/path pattern only narrows that result. Every configured age rule must match, and
the newest retained versions remain protected even when the pattern and age rules match.

For example, a policy with `jackson-*`, published age `30`, last-download age `14`, and **Keep
newest versions** `3` selects only matching families whose version is outside the newest three and
whose publication and last-download watermarks are both old enough.

### Last-Download Watermarks

Last-download retention is available for every current format and follows these rules:

- A successful, authorized external `GET` records usage. `HEAD`, rejected requests, and internal
  scanner reads do not update the watermark.
- A download through a group is attributed to the hosted or proxy repository that actually served
  the artifact, so a policy on that member sees the correct usage.
- When a subject contains several assets, the newest asset watermark protects the whole logical
  subject.
- Tracking starts automatically when a repository is targeted by a policy with a last-download
  rule. Until the configured observation period plus the safety lag has elapsed, runs report the
  usage tracker as warming up and do not select subjects from that repository. This avoids deleting
  old content before kkRepo has observed a complete usage window.

## Create a Policy

1. Sign in with an administrator account and open **Admin > Repository > Cleanup Policies**.
2. On the **Policies** tab, select **Create policy**.
3. Enter a unique name and choose a format.
4. Select one or more repositories. The picker shows only hosted and proxy repositories of the
   selected format.
5. Configure at least one deletion criterion and, if needed, a wildcard or regular expression to
   narrow the scope.
6. Optionally enter a **Run schedule** and IANA **Time zone**. The form validates the Quartz Cron
   expression and previews the next two runs.
7. Add operational notes and select **Create policy**.

New policies always start in **PAUSED** state, even when a schedule is present. Creating a policy
never starts deletion automatically.

Common six-field Quartz Cron examples are:

| Run time | Expression |
| --- | --- |
| Every day at 02:00 | `0 0 2 * * ?` |
| Every Sunday at 03:00 | `0 0 3 ? * SUN` |
| First day of every month at 01:30 | `0 30 1 1 * ?` |

The time zone is part of the schedule rather than a display preference. It keeps the intended
local execution time stable when application replicas run in another zone and applies daylight
saving transitions according to the selected IANA zone.

## Preview with Try Run

Use a Try Run before every new policy and after every material rule or repository change:

1. In the policy row, open the more-actions menu and select **Try Run**.
2. Choose the **Scan limit per repository** and select **Start Try Run**.
3. The UI switches to the **Runs** tab. Wait for a terminal status, then select **View**.
4. Review **Run summary**, **Would delete**, truncation warnings, repository results, and individual
   decisions. Run details show the exact policy revision and repository snapshot used by that run.

A Try Run never deletes artifacts. It evaluates current repository state from a stable starting
point and records `WOULD_DELETE` or `KEEP_PROTECTED` decisions. The details dialog shows up to 50
decisions per repository; the summary counters cover the complete bounded scan.

New policies have a stored scan ceiling of 1,000 subjects per repository. The Try Run dialog can
lower that value; the server uses the smaller of the requested value and the policy ceiling.
Policies configured through the management API can use a ceiling up to 10,000. Independently, one
Try Run can inspect at most 50,000 subjects across all selected repositories.

If a scan boundary splits a version family, kkRepo excludes that incomplete family from deletion
decisions and reports the run as truncated. Do not treat `SUCCEEDED_TRUNCATED` as proof that the
whole repository was evaluated.

## Execute Manually

After reviewing a Try Run:

1. Open the policy's more-actions menu and select **Run now**.
2. Read the deletion warning and confirm the operation.
3. Follow progress on the **Runs** tab and open **View** for repository and decision details.

New policies use server-side safeguards of 1,000 scanned subjects and 100 deleted subjects per
repository per execution. The delete limit is independent for every selected repository. Before
each deletion, kkRepo rechecks the current content identity, download usage, protection state, and
repository ownership; a Try Run result is never reused as a deletion list.

If a run reaches its deletion limit, it finishes as `PARTIAL_LIMIT_REACHED`. Run the policy again
or let its schedule continue until the eligible backlog is drained.

You can request cancellation for a non-terminal run. Cancellation stops future work after workers
observe the request; deletions already committed are not rolled back.

## Enable or Disable a Schedule

A saved Cron expression does not run while the policy is paused. To start automatic execution:

1. Complete and inspect a representative Try Run.
2. Open the policy's more-actions menu and select **Enable schedule**.
3. Confirm that the policy status is **ACTIVE** and that the policy row shows the expected next run.

Use **Disable schedule** to stop future scheduled runs. Disabling a schedule does not cancel a run
that is already queued or running.

Changing cleanup rules, the repository format, target repositories, or execution limits
automatically pauses the policy. Review another Try Run, then enable the schedule again. This
prevents an already-approved schedule from silently applying materially different deletion rules.

## Understand Run Results

Common run states are:

| State | Meaning |
| --- | --- |
| `PENDING` / `RUNNING` | Durable repository work is waiting for or owned by a cleanup worker. |
| `SUCCEEDED` | Every selected repository completed within the configured bounds. |
| `SUCCEEDED_TRUNCATED` | The scan limit was reached. Results cover only the bounded range, and an incomplete version family was excluded. |
| `PARTIAL_LIMIT_REACHED` | Execution reached at least one repository's delete limit. Remaining eligible content can be handled by later runs. |
| `PARTIAL` | Some repository or subject operations failed while other work completed. Inspect the detail error and retry after fixing the cause. |
| `FAILED` | The run could not complete successfully. |
| `CANCELLED` | Cancellation was requested; already committed deletions remain committed. |

Common item decisions include `WOULD_DELETE` for a Try Run match, `DELETED` for a committed
deletion, `KEEP_PROTECTED` for an active protection, `SKIPPED_STALE` or `SKIPPED_MISSING` when
content changed or disappeared before execution, and `FAILED` when the application-level delete
path returned an error.

Policy edits create a new revision. Existing runs retain their criteria, limits, and repository
snapshot, so historical results remain explainable even after the policy changes or is deleted.

## When Blob Storage Is Reclaimed

A successful execution removes the logical artifact and updates protocol metadata immediately,
but it does not synchronously delete an OSS/S3/File object inside the cleanup transaction.

The normal Blob GC worker rechecks references and deletes an object only after no live asset or
other durable reference needs it. By default, Blob GC polls every 30 seconds and waits for a
one-hour soft-delete grace period. Backlog and batch limits can add more delay, so repository
content can disappear before the blob-store size decreases. Shared blobs remain until their final
reference is gone.

Do not use a bucket lifecycle rule as a substitute for cleanup; lifecycle deletion can remove an
object that kkRepo still references.

## Recommended Production Rollout

1. Verify database and blob-store backups and recovery before the first real deletion.
2. Start with a narrow repository set and pattern.
3. Run a bounded Try Run and inspect both summary counters and representative decision rows.
4. Use **Run now** once and verify package-manager reads, Browse/Search, and generated metadata.
5. Enable a conservative schedule only after the manual result is understood.
6. Review the first several scheduled runs for truncation, limit-reached states, failures, and Blob
   GC backlog before widening the policy.

If several policies target the same repository, their runs are serialized for execution, but the
first policy can remove content that a later policy would otherwise match. Keep overlapping policy
ownership intentional and use run snapshots when investigating results.

## Troubleshooting

| Symptom | What to check |
| --- | --- |
| No repositories appear in the picker | Confirm that the selected format matches and that the repository is hosted or proxy, not group. |
| Try Run reports zero matches | Check that all configured age rules match, the pattern matches the artifact name or displayed path, retained versions are outside the newest `N`, and last-download tracking is no longer warming up. Artifacts without a download timestamp are skipped by a last-download rule. |
| Policy remains `PAUSED` | New policies and materially edited policies require **Enable schedule** from the actions menu. A Cron expression alone does not activate execution. |
| Run is `SUCCEEDED_TRUNCATED` | The scan limit bounded the result. Increase the policy scan ceiling through the management API if appropriate, or narrow the target/pattern before drawing conclusions. |
| Run is `PARTIAL_LIMIT_REACHED` | The delete safeguard was reached. Review the committed decisions, then run again or wait for the next schedule. |
| Run is `PARTIAL` or `FAILED` | Open **View** and inspect each repository error. Common causes include an offline target repository, blob-store failure, or a format metadata update failure. |
| Repository content is gone but storage usage has not dropped | Wait for the Blob GC grace period and check the Blob GC backlog and errors in the [Monitoring and Observability Guide](monitoring-observability-guide.md). |

Operators can use `KKREPO_CLEANUP_ENABLED=false` as a global cleanup worker and schedule kill
switch. `KKREPO_CLEANUP_SCHEDULER_ENABLED=false` disables new Cron execution while leaving manual
runs and workers available. `KKREPO_BLOB_GC_ENABLED=false` stops physical blob reclamation without
restoring metadata already deleted by cleanup. Durable policies and queued run records remain in
the database while these controls are disabled.
