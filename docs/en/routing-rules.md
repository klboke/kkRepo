# Routing Rules

Routing rules control which paths a **proxy repository** can serve. They are useful for keeping internal namespaces out of public upstreams or allowing only approved paths.

1. Open **Repository → Routing Rules** and create a rule.
2. Choose **Block matching paths** or **Allow only matching paths**.
3. Enter one Java regular expression per line. Expressions match the entire repository-relative path, including the leading `/` and query parameters. Any matching expression activates the rule.
4. Use **Test path** to check the result before saving.
5. Edit a proxy repository and select the rule under **Proxy settings → Routing Rule**.

For example, a BLOCK rule with `/com/company/.*` prevents a Maven proxy from serving that namespace. A rule can be shared by multiple proxies; each proxy can use one rule. Changes apply to every assigned repository. Unassign a rule from all repositories before deleting it.

Blocked direct proxy reads return HTTP 403, including already cached content. A group skips blocked proxy members and continues in member order. Hosted repositories are unaffected. Rules match request paths; they do not inspect package metadata for vulnerabilities or remove matching entries from otherwise allowed index documents. Use artifact scanning policies for vulnerability decisions and content selectors for user-specific access grants.

Matching uses the path identity consumed by the protocol. For example, PyPI `/simple/PRIVATE` matches as `/simple/private/`, npm package aliases use the parsed package name, and Terraform URL credentials are excluded. Swift scope/package names, Cargo crate names and NuGet metadata IDs use their canonical case; Pub accepts canonical names and removes trailing-slash aliases. Other package protocols also remove trailing-slash aliases where their readers do. Docker matches its parsed `/v2/` resource and canonical blob digest. Raw filenames retain case and literal semicolons. The rule tester evaluates the path you enter; use the canonical protocol path when testing these cases.

The management API follows Nexus:

- `GET /service/rest/v1/routing-rules`
- `POST /service/rest/v1/routing-rules`
- `GET`, `PUT`, `DELETE /service/rest/v1/routing-rules/{name}`

```json
{"name":"block-company","description":"Keep internal namespaces private","mode":"BLOCK","matchers":["/com/company/.*"]}
```

Creation, update and deletion return 204. Invalid expressions and deletion of assigned rules return 400. Management requires the `nexus:*` administrative permission. Repository configuration uses `routingRuleName` in `/internal/repositories` (the older Nexus spelling `routingRule` is also accepted). An empty string removes the assignment; omission preserves the assignment on a partial repository update.

Rules and bindings live in the shared database. Every replica loads a complete immutable snapshot at startup, including precompiled regular expressions and empty results. A background task replaces it after each successful refresh, by default with a **30-second fixed delay** between refreshes. Configure `KKREPO_ROUTING_RULES_REFRESH_INTERVAL` (Spring property `kkrepo.routing-rules.refresh-interval`) with a positive duration such as `10s` or `1m`. This is a refresh interval, not a TTL: snapshots do not expire, and requests never query routing-rule tables or wait for a refresh, including when no rule is bound. Hosted reads bypass policy evaluation.

Consistency is eventual: creating, changing, binding, unbinding or migrating a rule is observed by each replica on its next successful background refresh, even without traffic. A refresh compiles and validates the entire replacement before publishing it atomically; each request retains its original snapshot and group-cache fingerprint until completion. Under healthy conditions convergence depends on the refresh interval and database/compilation time. Startup fails if the first snapshot cannot load. Later failures log a warning, retain the last successful snapshot and retry on the next scheduled run. During an outage, both newly added restrictions and rule removals remain unapplied until refresh succeeds; existing rules continue to run from memory.

Group cache generations incorporate the applicable policy and selected members. Once a replica refreshes its snapshot, it cannot reuse group content selected by the old policy. The rule tester and management API read current configuration; serving nodes may still use their previous snapshot during the convergence window.

See [compatibility mapping and test instructions](../../compat-test/routing-rules-mapping.md). Nexus 3 configuration migration imports routing rules and restores bindings for supported proxy repositories. Preflight lists the rules, bindings, blocking errors and a SHA-256 checksum of the source policy. Both `routingRule` and `routingRuleName` source fields are accepted.

Rules with the same name and identical content are reused; conflicting definitions, missing rules, invalid expressions or an unreadable source rule API block configuration migration. Existing target repository assignments are not silently replaced or removed. Resolve the reported conflict explicitly and retry. Rules and bindings are written in the same database transaction as repository configuration, and the result report validates both. Repeating a completed migration reuses rule identities. Concurrent creation conflicts roll back and can be retried; row locks protect existing rules and repository bindings across replicas.

The source account needs access to the routing-rule API. This migration copies configuration, not cached artifact bytes; repository data migration remains a separate step.
