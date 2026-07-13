# Security Model

This document explains the current kkrepo security model: authentication, authorization, secrets, audit logs, and operational boundaries.

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

Repository permission checks include repository name, repository format, path/content selector information where applicable, and action.

Typical mapping:

| Operation | Required permission |
| --- | --- |
| List repository or browse metadata | `browse` |
| Download artifact content | `read` |
| Upload new artifact | `add` |
| Overwrite or mutate existing artifact metadata/content | `edit` |
| Delete artifact content | `delete` |
| Manage repository configuration | repository administration privilege |
| Manage users, roles, realms, blob stores | application/security/blob-store privileges |

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

Protocol-specific clients should keep using their native auth mechanisms and matching token domains. Current protocol-token domains include `NpmToken`, `CargoToken`, `PubToken`, `NuGetApiKey`, and `RubyGemsApiKey` where the corresponding client protocol uses tokens or API keys; Cargo, Pub, and RubyGems clients send their registry/API key token through the `Authorization` header. Private Composer repositories should normally use HTTP Basic through `COMPOSER_AUTH`/`auth.json`; Composer or CI callers that explicitly send bearer or custom API-key headers can use `GenericToken`. `GenericToken` is not a universal replacement for every package client token format.

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
