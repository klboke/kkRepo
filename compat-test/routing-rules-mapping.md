# Routing rules (Issue #452)

Reference: Nexus Repository 3.68.1 runtime behavior and 3.94.0 API schema and [Sonatype routing rules documentation](https://help.sonatype.com/en/routing-rules.html).

| Console operation | Nexus endpoint / representation | kkRepo endpoint |
| --- | --- | --- |
| Settings → Repository → Routing Rules: list/create | GET/POST `/service/rest/v1/routing-rules` | Same |
| Read/update/delete a rule | GET/PUT/DELETE `/service/rest/v1/routing-rules/{name}` | Same |
| Proxy repository: Routing Rule selector | `routingRule` (3.68) / `routingRuleName` (3.94) in repository REST configuration | `routingRuleName` in `/internal/repositories` configuration |
| Rule tester | Internal UI routing-rule preview resource | `/internal/routing-rules/preview` |

A rule has `name`, non-null `description`, `mode` (`ALLOW` or `BLOCK`), and a non-empty list of Java regular expressions (`matchers`). Each matcher matches the entire repository-relative path (leading slash), including query parameters. ANY matching expression allows an ALLOW rule or rejects a BLOCK rule. Rules attach to proxies only. Assigned rules cannot be deleted. They apply before cached content is served; a blocked direct proxy read returns 403, and groups continue to the next available member.

`RoutingRulesBlackBoxCompatibilityTest` creates disposable rules and Raw and Maven repositories, warms proxy and group caches, changes rules, and verifies HTTP status and body semantics. It removes its fixtures in `finally`. Enable with `-Dcompat.routingRules=true`; use `-Dcompat.routingRules.referenceOnly=true` to establish the Nexus reference before deploying kkRepo. Repository administration uses each product's existing API; the routing-rule CRUD contract is shared.

The Nexus reference must permit its controlled loopback upstream (`http://localhost:8081`). The CI setup adds `localhost` to the Nexus SSRF domain allowlist; on a shared reference instance use an already permitted fixture endpoint or configure this prerequisite explicitly.

The kkRepo fixture upstream defaults to its client-facing base URL. When kkRepo runs in a container, set `ROUTING_RULES_KKREPO_UPSTREAM_BASE_URL` (or `-Dcompat.routingRules.kkrepoUpstreamBaseUrl`) to an address reachable from inside that container. CI uses `http://localhost:8080`, independently of the host-published client port `18092`.

Matcher input canonicalizes repeated slashes and dot segments before evaluating policy, preventing alternate URLs from reaching denied proxy content. The black-box checks cover direct GET/HEAD denial for both forms and group fallback for repeated slashes. Hosted dot-segment resolution remains a separate compatibility difference: Nexus can resolve a group fallback through such a URL while kkRepo can return 404; the denied proxy must never be restored.

Rule matching follows the consuming handler's segment parsing. PyPI MVC routes remove literal matrix parameters, while encoded semicolons remain part of the segment. `PypiRoutingRuleTest` verifies that GET and HEAD project-index requests with matrix parameters are rejected before the proxy service can read a cache or contact upstream. Manual protocol handlers retain literal semicolons as asset data; the Raw black-box regression warms `/allowed;private/item.jar` before enabling ALLOW `/allowed/.*`, then verifies GET/HEAD rejection, group fallback, and successful reads of `/allowed/item;public.jar`. Nexus 3.68 can dispatch matrix aliases to its repository browser instead of the PyPI index, so that browser response is not treated as a protocol-equivalent index response.

npm rules reuse `NpmPathParser` package identities, including its whitespace normalization and encoded scope separator support. Dispatcher tests cover metadata, versions, tarballs, revisions and dist-tags, direct GET/HEAD denial and group member exclusion. The live npm case warms metadata through a controlled Raw upstream and compares direct-proxy blocking with Nexus, then verifies kkRepo rejects whitespace and trailing-slash aliases from cache. Nexus 3.68 can retain a warmed npm group packument after a rule change; kkRepo instead excludes denied members before serving cached group metadata. These stronger group-cache and alias checks are kkRepo regressions, not assertions of identical Nexus behavior.

PyPI project-index decisions use `PypiPaths.indexPath()`, so case, separator normalization and the optional trailing slash cannot select a different policy than the cached index. Live kkRepo checks cover these aliases and MVC matrix parameters. Dispatcher decisions reuse authentication's normalized paths without decoding again, including Terraform paths after URL-token removal. Terraform archive bindings are usable only while their source remains in the projected group; otherwise retrieval continues to allowed members. Swift likewise treats a policy-denied pinned release as unavailable and resolves an allowed group member.

Swift scope/package keys and Cargo download crate names use their protocol parsers' canonical identities. Pub valid trailing-slash aliases and NuGet flat-container/registration index package IDs likewise match the resource actually read. Dispatcher tests cover GET/HEAD and group exclusion; live kkRepo cases require denial before upstream access. Pub rejects uppercase package names at parsing time. Raw asset case and opaque NuGet package/leaf URLs remain unchanged. These alias checks extend kkRepo regression coverage and do not claim additional Nexus format support.

R, APT, Alpine and Conda use their parser-returned resource paths; Ansible, Yum and RubyGems also remove supported trailing-slash aliases before matching. Live GET/HEAD regressions cover exact file/index matchers as well as namespace patterns. Docker decisions use the already parsed target for both connector and path-based routing, retaining `/v2/` in matcher input while canonicalizing blob/referrer digests and endpoint aliases.

Repository updates read the existing binding under a row lock, after acquiring any explicitly requested rule lock. This keeps the rule-before-repository order used by migration and prevents partial updates from overwriting concurrent assignments or removals. MySQL and PostgreSQL contract tests establish a stale transaction snapshot while another transaction assigns a rule, then verify that the locking read sees and preserves the committed binding.

## Cache and convergence

kkRepo intentionally uses eventual consistency for routing policy. Each replica keeps one immutable snapshot in `LocalCacheFactory` with a configurable expire-after-write TTL (default 30 seconds), deduplicated compiled patterns per rule and cached empty results. Warm requests perform no routing-rule SQL; concurrent cold requests share one load. Hosted requests bypass loading. Snapshot expiry is independent of access frequency, and a request pins its snapshot until completion. A failed load propagates rather than caching an empty policy or serving expired rules.

Policy tests verify warm-load counts, concurrent single loading, empty-policy caching, expiry under traffic, independent replica convergence, binding removal, group cache-token changes and in-flight snapshot stability. Live tests poll only configuration transition boundaries (45 seconds by default, configurable with `ROUTING_RULES_CONVERGENCE_TIMEOUT_SECONDS`), then assert aliases and group results without retries. Migration verification also waits for the target replica to observe the imported binding. This is a deliberate kkRepo consistency choice, not a claim about Nexus cache timing.

## Configuration migration

`NexusRestClient` reads `GET /service/rest/v1/routing-rules` together with repository details. The target `/internal/migration/nexus/preflight` exposes `routingRules.rules`, `bindings`, `errors` and `checksum`; `/run` imports rules before binding repositories inside the existing configuration transaction. An inaccessible policy API is a blocking error, never an empty rule set.

`compat-test/scripts/routing-rules-migration.py prepare|verify|cleanup` provisions a dedicated Raw proxy and policy, then compares rule content, binding, unique name, HTTP 403 (including encoded paths), and the SHA-256 of an allowed Maven Central fixture. `scripts/docker-compat/migration-e2e.sh` runs this before and after configuration migration, including its repeat run and secondary replica verification where enabled. The full configuration import also migrates security settings: use a disposable target, not a shared acceptance instance. Unit tests cover dry-run, old/new binding field names, idempotency, malformed regex, missing inventory, conflicting policies and concurrent policy changes.
