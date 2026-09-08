# Content Selectors

Content selectors grant access to matching paths within a repository. A selector defines the content; a **repository-content-selector privilege** combines it with a repository scope and actions. Assign that privilege to a role, then assign the role to users. CI credentials use the permissions of their owner and any additional token restrictions.

## Configure in the administration UI

1. Open **Repository → Content Selectors** and choose **Create selector**.
2. Give it a stable name and enter a CSEL expression, for example `format == "raw" and path =^ "/team/"`.
3. Select a repository and choose **Preview**. The preview reads stored assets; it does not fetch uncached proxy content.
4. Save the selector. Choose **Create privilege** on its row, choose the repository scope, and select actions. The default actions are `browse` and `read`.
5. Open **Security → Roles**, add the new privilege to a role, and assign that role to the intended users.

The list shows which privileges reference a selector. Referenced selectors cannot be deleted until those privileges are changed or removed. Names and selector types are immutable in the editor. Invalid new or changed expressions return HTTP 400 and leave the saved expression unchanged.

A preview is an administrative operation: it requires authentication and either `nexus:selectors:create` or `nexus:selectors:update`. Repository read access alone does not permit previewing another repository's content. CRUD uses `nexus:selectors:read/create/update/delete`; creating the privilege also requires `nexus:privileges:create`.

## Expressions

The portable CSEL profile follows the `path` and `format` syntax validated against Nexus Repository 3.94.0. Paths are relative to the repository and should start with `/`. Maven's format is `maven2`.

| Syntax | Meaning | Example |
| --- | --- | --- |
| `==` / `!=` | Equality / inequality | `format == "npm"` |
| `=^` | Literal prefix, not a glob | `path =^ "/@team/"` |
| `=~` | Regular expression over the entire path | `path =~ "^/org/example/.*"` |
| `and` / `&&` | Both conditions | `format == "raw" and path =^ "/team/"` |
| `or` / `\|\|` | Either condition | `path == "/README" or path =^ "/team/"` |
| `( ... )` | Group conditions | `format == "maven2" and (path =^ "/org/example/" or path == "/")` |

Both single and double quoted strings are accepted. Backslashes in string literals must be escaped, for example `path =~ "^/org/example/.*\\.pom$"`. Embedded quotes, arbitrary functions, unknown fields, numeric/boolean expressions, and unary `not`/`!` are rejected for new CSEL expressions. `coordinate.*` and `maven.groupId` are not part of this profile. An expression is limited to 8,192 characters, 1,024 tokens, and 64 nested groups.

kkrepo uses RE2/J to avoid backtracking regex execution on repository requests. Common regular expressions and the existing simple leading negative-lookahead form, such as `(?!.*-sources.*).*`, are supported. Java-specific backreferences, lookbehind, and other unsupported lookarounds are rejected when saving. This is an explicit difference from Nexus's Java regex engine; arbitrary JEXL execution and all Nexus regex extensions are not supported.

Existing imported selectors continue using the previously supported legacy subset, including path/format aliases, repository aliases, Maven/npm coordinates, and boolean negation. Updating only their description preserves the existing expression. Changing a legacy selector marked `csel` requires a portable CSEL expression; selectors marked `jexl` retain the supported legacy subset. Unsupported stored expressions fail closed at evaluation time. Generic migrated repository targets keep their separate behavior.

## Authorization behavior

**Grants are additive.** A selector is not a deny rule. If a user already has `nx-all`, a matching wildcard privilege, or a broad repository read privilege through another role, the selector cannot remove that access.

By default, kkrepo adds `nx-anonymous` to authenticated subjects through `KKREPO_DEFAULT_AUTHENTICATED_ROLE_ID`. Its initial privileges include broad repository browse/read. For an installation that needs strict path isolation, configure the default role appropriately, or set this variable to an empty value on every replica and grant the necessary roles explicitly. Re-authenticate users after changing authentication configuration. Merely disabling anonymous access does not remove the default role from authenticated users.

For a group repository, privileges scoped to that group apply to requests through the group URL. They do not grant direct access to a member's URL. Preview the stored assets in a member repository. Browsing parent directories and fetching protocol metadata can require additional allowed paths; permitting only a binary's path may be insufficient for a package manager. Selectors do not rewrite aggregate protocol metadata into a per-user document. Test the real client workflow as well as direct downloads.

Actions follow the existing protocol mappings, including generic `PUT → edit` even when the asset is new. See [Security Model](security-model.md) for the complete action mapping. Exercise care with root-targeted upload APIs: a path check cannot infer package coordinates that have not yet been parsed from a request body.

## API and preview limits

Selector CRUD uses the Nexus REST routes:

- `GET/POST /service/rest/v1/security/content-selectors`
- `GET/PUT/DELETE /service/rest/v1/security/content-selectors/{name}`
- `POST /service/rest/v1/security/privileges/repository-content-selector`

The UI preview mapping is `POST /service/rest/internal/ui/content-selectors/preview`:

```json
{"repository":"*-raw","type":"csel","expression":"path =^ '/team/'"}
```

`repository` accepts a repository name, `*`, or `*-<format>`. The response contains `total` and up to 10 `results`, using the Nexus UI asset fields `name` (leading slash), `repositoryName`, `containingRepositoryName`, `format`, `id`, and available asset metadata.

kkrepo adds `truncated`, `totalExact`, and `scanned`. At most 10,000 SQL candidates are evaluated per preview. `totalExact: false` means the scan limit was reached and `total` is a **lower bound**, not the complete match count. `truncated: true` means either more than 10 assets matched or the scan was incomplete. Narrow the expression or repository when this occurs. Storage references and arbitrary internal attributes are not exposed in preview results.

## Search and multi-replica behavior

Exact paths, literal prefixes, fixed repository/format conditions, and conservative `and`/`or` combinations are applied to SQL candidates before component pagination. SQL parameters escape `%`, `_`, and the escape character, so a literal prefix never becomes a wildcard pattern accidentally. Both MySQL and PostgreSQL use the shared predicate builder and existing asset indexes.

Every returned asset still passes the full selector authorization check. Database collation can broaden SQL candidates; it cannot grant access. Regex, negation, and legacy coordinate conditions remain residual checks unless another branch provides a safe path constraint. The existing 1,000-component residual search budget remains in place and the search response reports truncation. The optimization prevents unrelated paths from consuming this budget for common exact/prefix selectors; it does not promise unbounded searches for arbitrary expressions.

Definitions and grants remain in the shared database. Concurrent creation of the same selector name uses a unique insert; the losing request cannot overwrite the saved expression. Writes use the existing post-commit security catalog/authorization invalidation and database-backed version watermarks. Each replica maintains a bounded, rebuildable cache of compiled expressions (512 entries, 10-minute TTL) through `LocalCacheFactory`, keyed by the complete expression. This cache contains no users or access decisions. A changed expression gets a different cache key; permission convergence follows the existing catalog/version refresh intervals, not the expression cache TTL.

Reference behavior and boundaries were checked against [Sonatype's Content Selectors guide](https://help.sonatype.com/en/content-selectors.html), [privilege documentation](https://help.sonatype.com/en/privileges.html), and the [Nexus 3.94.0 source](https://github.com/sonatype/nexus-public/tree/release-3.94.0-12).
