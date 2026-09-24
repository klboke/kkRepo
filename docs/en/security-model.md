# Security Model

This document explains the current kkrepo security model: authentication, authorization, secrets, audit logs, and operational boundaries.

See [Content Selectors](content-selectors.md) for path expressions, the management UI, preview limits, and additive grant behavior.

For vulnerability reporting, use [SECURITY.md](../../SECURITY.md).

## Goals

kkrepo security aims to:

- Preserve Nexus-like users, roles, privileges, repository permissions, and common client behavior.
- Support local users, LDAP, OIDC, API keys, and sessions.
- Keep security state in the shared MySQL/PostgreSQL database so multi-replica deployments behave consistently.
- Encrypt reusable credentials and user-facing API-key payloads at rest.
- Record security-sensitive administrative actions in audit logs.

## Authentication Sources

Supported authentication sources include:

| Source | Purpose |
| --- | --- |
| Local users | Built-in users stored in the shared database with password hashes |
| LDAP realm | External directory authentication and optional group-to-role mapping |
| OIDC realm | Bearer/auth-code based identity integration using issuer/JWKS/client/scope/claim settings |
| API keys and protocol tokens | Client and CI authentication for repository protocols |
| HTTP session | Browser UI sessions stored through Spring Session JDBC |
| Anonymous subject | Optional unauthenticated read subject when explicitly enabled |

Authentication order and exact behavior depend on the request type. Protocol clients commonly use Basic auth, API keys, or protocol-native token flows. Browser users use sessions after login.

## Shared Database State

Security state is stored in the selected MySQL or PostgreSQL database:

- `security_user`
- `security_role`
- `security_privilege`
- role inheritance and role membership tables
- `security_realm`
- `security_anonymous_config`
- `api_key`
- `auth_ticket`
- `SPRING_SESSION`
- `security_audit_log`

This lets multiple replicas share sessions, authentication tickets, user state, and permission changes.

Database selection does not change authorization semantics. See [Database Backends](database-backends.md).

## Authorization Model

kkrepo uses Nexus-style privileges. Repository privileges include:

- `browse`
- `read`
- `add`
- `edit`
- `delete`

Repository permission checks include repository name, repository format, path/content selector information where applicable, and action. The action is
defined by the protocol route and repository operation. It is not a universal
"asset does not exist means `add`, asset exists means `edit`" classifier.

For the Nexus-compatible repository entrypoints, the baseline mapping is:

| Operation | Required permission |
| --- | --- |
| List repository or browse metadata | `browse` |
| Download artifact content | `read` |
| Generic repository `GET` or `HEAD` | `read` |
| Generic repository `POST`, `PATCH`, or `MKCOL` | `add` |
| Generic repository `PUT` | `edit` |
| Generic repository `DELETE` | `delete` |
| Nexus Components API `POST /service/rest/v1/components` | repository `edit` |
| Manage repository configuration | repository administration privilege |
| Manage users, roles, realms, blob stores | application/security/blob-store privileges |

The generic `PUT -> edit` mapping also applies when the target path is new.
This matches Nexus client-visible behavior; changing it to an existence lookup
would make ordinary Maven, Raw, Helm, Yum, and similar PUT clients
incompatible. Protocol-specific publish routes may intentionally use different
actions. For example, Cargo, Pub, Swift, and Ansible Galaxy collection publish
flows use `add`, while
Terraform distinguishes a new module/provider coordinate or platform from a
redeployment, and Docker push scopes may combine `add` and `edit` for different
parts of the push. Conda package `PUT` resolves the canonical package path and
requires `add` for a new package or `edit` for an existing package; generated
channel metadata paths are not package publication endpoints. Ansible collection
versions remain immutable regardless of `edit`, and a durable import task is
readable only by its requester or an administrator who still has access to the
target repository.

Repository permissions and hosted write policy are separate checks. Having
`edit` permission does not override a repository write policy such as
`ALLOW_ONCE` or `DENY`; the protocol service still applies its duplicate and
overwrite rules after authorization. Content selectors continue to restrict
the requested path, but asset existence must not be used as a generic
replacement for the protocol-specific permission mapping above.

When adding or changing a protocol route, document its action mapping in the
format design document and validate it against a real Nexus reference instance.

Use least-privilege roles for CI users. Avoid granting broad `*` privileges to automation unless the automation is truly administrative.

## Anonymous Access

Anonymous read is persisted in the shared database and disabled by default for new installations. During initial administrator setup, choose whether unauthenticated users may browse and download repository content.

After setup, manage anonymous access through **Security > Anonymous** in the administration UI or the security REST APIs. There is no application property that overrides the persisted setting. Enable anonymous access only when public read behavior is intentional, and review which repositories are readable before exposing the service externally.

## API Keys And Tokens

kkrepo stores API-key compatibility data in the shared database. Raw user-facing tokens are protected as encrypted payloads, and lookup uses hashed material rather than plaintext tokens.

Operational guidance:

- Prefer API keys or CI tokens over shared passwords.
- Rotate tokens when users change roles or leave the organization.
- Do not log tokens.
- Do not paste tokens in public issues.
- Reissue tokens if the API-key payload secret is lost or intentionally rotated.

The custom API-key header is:

```text
X-Nexus-Plus-Token
```

Protocol-specific clients should keep using their native auth mechanisms and matching token domains. Current protocol-token domains include `NpmToken`, `CargoToken`, `PubToken`, `NuGetApiKey`, and `RubyGemsApiKey` where the corresponding client protocol uses tokens or API keys; Cargo, Pub, and RubyGems clients send their registry/API key token through the `Authorization` header. Private Composer repositories should normally use HTTP Basic through `COMPOSER_AUTH`/`auth.json`; Composer or CI callers that explicitly send bearer or custom API-key headers can use `GenericToken`. Terraform CLI can use a `GenericToken` embedded only in the configured `modules.v1`/`providers.v1` service URL; generated archive/checksum/signature URLs preserve that credential segment, while logs, metrics, and uploaded CI diagnostics must redact it. Ansible Galaxy clients may send a `GenericToken` through the current ansible-core Bearer scheme or the Ansible 2.9 Token scheme. On Ansible routes only, kkrepo also accepts Nexus-compatible Base64 `username:password`; it is password transport rather than encryption and should not be logged or preferred over a scoped token. An explicit invalid Ansible credential must return `401` instead of falling back to anonymous access. Private Conda channels normally use HTTP Basic credentials from `.netrc`; callers that explicitly send bearer or the custom API-key header can use `GenericToken`. Do not embed reusable secrets in `.condarc`. `GenericToken` is not a universal replacement for every package client token format.

## Encryption Secrets

Two stable deployment secrets are required outside dev/test-style usage:

```bash
KKREPO_CREDENTIAL_SECRET=<strong-random-string>
KKREPO_API_KEY_PAYLOAD_SECRET=<strong-random-string>
```

`KKREPO_CREDENTIAL_SECRET` protects reusable credentials, including:

- Blob-store S3/OSS keys.
- LDAP bind passwords.
- OIDC client secrets.

`KKREPO_API_KEY_PAYLOAD_SECRET` protects user-facing API-key payloads.

Losing these secrets can make existing encrypted data unreadable. Changing them without a migration/re-encryption process can break blob-store credentials, realm credentials, and API keys.

## LDAP

LDAP realm configuration can include:

- LDAP URL/protocol/host/port.
- Bind DN and bind password.
- User base DN and user search filter.
- Group base DN and group search filter.
- Whether LDAP groups are treated as roles.

Test bind, user mapping, and group mapping before enabling LDAP for production users. Store LDAP bind credentials through the normal encrypted realm settings rather than plaintext files.

## OIDC

OIDC configuration can include:

- Issuer.
- JWKS URI.
- Client ID and client secret.
- Authorization and token endpoints.
- Redirect URI.
- Scope.
- Claim mapping.

Use HTTPS endpoints and validate that issuer, audience/client, and JWKS settings match your identity provider. Treat OIDC client secrets as production credentials.

## Sessions And CSRF

Browser sessions use Spring Session JDBC and are shared across replicas.

Production recommendations:

```bash
KKREPO_SESSION_STORE_TYPE=jdbc
KKREPO_SESSION_COOKIE_SECURE=true
KKREPO_CSRF_COOKIE_SECURE=true
KKREPO_HSTS_ENABLED=true
```

Set secure cookies only behind HTTPS. Make sure reverse proxies pass cookies and forwarded headers correctly.

## Rate Limits

Login and bootstrap flows have rate-limit settings:

```bash
KKREPO_LOGIN_RATE_LIMIT_PER_MINUTE=20
KKREPO_BOOTSTRAP_RATE_LIMIT_PER_MINUTE=5
```

These limits reduce accidental or basic abusive traffic. They do not replace network-level rate limiting, WAF policy, or identity-provider controls.

## Outbound Request Policy

Proxy repositories fetch upstream content. By default, private-address outbound access is disabled:

```bash
KKREPO_OUTBOUND_ALLOW_PRIVATE_ADDRESSES=false
KKREPO_OUTBOUND_ALLOWED_HOSTS=
```

Only allow internal upstream hosts when required. This helps reduce SSRF-style risk from misconfigured proxy repositories.

Direct outbound requests are resolved locally and pinned to policy-approved IP addresses. When a
repository has an explicit HTTP or SOCKS5 outbound proxy, the upstream hostname is instead sent to
that proxy for resolution so proxy DNS rules and hostname-based routing remain effective. Treat the
configured proxy as a trusted egress boundary: by default kkrepo still rejects explicit private IP
targets and local-only names, but it cannot inspect the IP address returned by DNS inside the proxy.
The proxy should deny loopback, private-network, link-local, and cloud-metadata destinations unless
that access is intentionally required.

## Audit Logs

Security-sensitive actions should be recorded in `security_audit_log`, including administrative changes such as user, role, privilege, realm, and token operations.

Operational guidance:

- Keep audit logs long enough for your compliance and incident response needs.
- Export or scrape audit data if central retention is required.
- Do not rely on application logs alone for security history.

## Reverse Proxy Boundary

The reverse proxy must:

- Terminate HTTPS or pass through TLS according to your deployment model.
- Preserve `Authorization` headers.
- Preserve cookies for browser sessions.
- Set `X-Forwarded-*` headers consistently.
- Restrict management endpoints to trusted networks.
- Apply body-size and timeout settings suitable for artifact traffic.

Incorrect proxy settings can cause false authentication failures, broken redirects, or insecure cookies.

## Security Reporting

Use public issues for ordinary bugs, compatibility differences, and documentation problems.

Report privately if the issue could cause:

- Authentication bypass.
- Authorization bypass.
- Token, credential, or cookie exposure.
- Repository-content disclosure.
- Privilege escalation.
- Remote code execution.
- Migration data leakage.

See [SECURITY.md](../../SECURITY.md).

## NuGet upstream NTLM authentication

Reference: [Microsoft NTLM connection-oriented flow](https://learn.microsoft.com/en-us/openspecs/windows_protocols/ms-nlmp/1fbf5c3b-04c1-4591-a4be-9dc232c4744b).

NuGet proxy repositories can authenticate to NTLM upstreams, including on-premises feeds that require Windows credentials. In Admin → Repositories, choose **NTLM** under **Upstream authentication**, enter the remote username/password, and optionally provide the **NTLM domain** and **NTLM workstation**. A `DOMAIN\username` username is accepted; an explicitly supplied domain takes precedence. Clear any saved Bearer token before selecting NTLM.

The equivalent `/internal/repositories` proxy settings are:

```json
{
  "remoteAuthenticationType": "ntlm",
  "remoteUsername": "service-user",
  "remotePassword": "<password>",
  "remoteNtlmDomain": "DOMAIN",
  "remoteNtlmHost": "KKREPO"
}
```

The default `auto` mode preserves existing Basic/Bearer behavior. NTLM is explicitly selected and responds to an upstream NTLM challenge; it does not send a preemptive Basic password or fall back to Basic. This adds upstream NTLM authentication for NuGet proxy repositories, not Kerberos/SPNEGO or Windows login to kkRepo. Private upstream hosts still require the outbound allowlist described above. Availability of NTLM does not imply that every Azure DevOps-specific NuGet API behavior has been verified.

Passwords use the existing encrypted `remotePassword` storage and redacted API responses. Nexus NuGet migration preserves NTLM type/domain/workstation when exported, and existing missing/masked-password checks still require the operator to re-enter unavailable secrets. Authenticated connections are isolated by repository, origin, outbound proxy and credential fingerprint. Each replica builds a disposable local pool from database-backed configuration; credential changes select a new pool, local update/delete evicts the old pool, and idle pools expire after `kkrepo.outbound-proxy.idle-ttl-ms`. Same-origin redirects retain authentication; cross-origin redirects require the usual allowlist and never carry NTLM credentials.

NuGet service-index discovery does not delegate repository credentials to other origins. Discovered resources use the same origin check as other outbound requests; neither the private-host allowlist nor `allowedRedirectHosts` authorizes sharing Basic/Bearer/NTLM credentials. For an authenticated resource advertised under a canonical hostname, configure that origin as the upstream when it also serves the service index. Feeds requiring shared credentials across multiple origins need a separately configured credential-delegation feature, which is not currently supported.

The current Apache HttpClient dependency retains NTLM as a deprecated, opt-in scheme. The transport registers it explicitly for NTLM requests without changing the default authentication registry. Keep the NTLMv2 handshake tests when upgrading HttpClient. Reference comparison: `python3 compat-test/scripts/ntlm-upstream.py --nexus http://localhost:28090 --kkrepo http://localhost:18090` against disposable instances, with `--upstream-host` set to a hostname/IP reachable from both servers. Add `--dotnet` to verify a real .NET 8 restore using isolated package caches. The fixture verifies the NTLMv2 password proof, GET/HEAD results and exact package bytes, and removes its repositories and temporary Nexus SSRF exception.

## Scan activity ordering

Admin → Security Scanning → Tasks / Overview (scan runs) defaults to newest first by the actual completion time. Click the **Finished** (tasks) or **Completed** (runs) column header to toggle ascending/descending order; the arrow shows the current direction, matching the repository list. Tasks also display their finished timestamp. Unfinished tasks appear last in either direction; IDs break timestamp ties in the selected direction. Search and repository visibility are applied before pagination.

The management API supports the same ordering:

- `GET /internal/security/scanning/tasks?sort=finished_at&direction=desc&limit=25` (optionally add `status=FAILED`, `repositoryId`, or `q`).
- `GET /internal/security/scanning/runs?sort=completed_at&direction=asc&limit=25` (optionally add `repositoryId` or `q`).

Completion sorting defaults to `direction=desc` when direction is omitted. Pass the response's opaque `nextCursor` as `cursor` for the next page, keeping the same sort, direction, and filters; a null cursor means the end. Start a new page sequence after changing filters or ordering. Completion sorting does not use `after`/`nextAfter`. Omitting `sort` (or using `sort=id&direction=asc`) preserves the existing ascending ID API and `after`/`nextAfter` pagination for older clients. Unsupported sort fields, directions, malformed/mismatched cursors, and timestamps outside the JDBC connection's supported range return HTTP 400.

The cursor carries the completion timestamp and ID, so any replica can continue the page without looking up the previous boundary row. Pages reflect live data rather than a frozen snapshot: retries, newly completed tasks, or retention deletions can change the list while browsing. Refresh from the first page for the latest activity.

Completion pages use timestamp/ID indexes added by migration V55, with status-leading variants for filtered task lists (including repository-scoped lists). Tasks read completed and unfinished ranges separately, with at most the remaining page size fetched from each range; the server reads both in one read-only transaction. This avoids a history-wide null-rank sort while keeping unfinished tasks last. MySQL builds the indexes online; PostgreSQL builds them concurrently. The migrations support retry after interruption.
