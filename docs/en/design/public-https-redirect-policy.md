# Public HTTPS content redirect policy

Tracking: [issue #384](https://github.com/klboke/kkRepo/issues/384).

## Configuration

Proxy repositories expose a repository-owned `proxy.redirectPolicy` through
the repository API and the admin form:

```json
{
  "proxy": {
    "remoteUrl": "https://ghcr.io",
    "redirectPolicy": "PUBLIC_HTTPS",
    "allowedRedirectHosts": []
  }
}
```

| Policy | Behavior |
| --- | --- |
| `ALLOWLIST` | Existing behavior; default for new and existing repositories. |
| `PUBLIC_HTTPS` | Opt-in to public HTTPS content GET/HEAD downloads without maintaining destination hosts. |

Unknown values are rejected. Omitting the field in a partial update preserves
the existing policy. Select `ALLOWLIST` to disable the opt-in; existing host rules
are retained. No database migration or global switch is required: the field
lives in existing shared repository attributes and runtime snapshots, using the
existing cache invalidation and TTL mechanisms across replicas.

## Trust boundary

`PUBLIC_HTTPS` explicitly trusts the configured upstream to choose **any public
HTTPS destination**, including one outside the upstream organization. This is
broader than an exact allowlist and does not prove destination ownership.

- The configured upstream and every download hop must use HTTPS. Downgrades
  remain denied even if a host is allowlisted.
- Certificate and hostname verification remain enabled in the existing pinned
  transport. There is no insecure retry.
- A separate public-target check validates all DNS answers and connects through
  the approved pinned addresses. It does not inherit global private-address or
  allowed-host exceptions. Private, local, metadata, multicast, unspecified,
  shared-address, documentation, benchmark and IPv6 transition destinations are
  excluded.
- Cross-origin Basic/Bearer/NTLM credentials are stripped. Credentials remain
  scoped to the configured origin under existing origin rules.
- Cookie management is disabled for opt-in requests, so pooled clients cannot
  add cookies learned during previous requests. Strict-mode behavior is unchanged.
- Existing redirect-depth, connection, response and pool-acquisition limits
  remain in force. A denial remains a failed request, not successful absence.
- OCI integrity checks remain unchanged. A digest verifies bytes, not network
  destination authorization.

Explicit HTTP/SOCKS outbound proxies are incompatible with this opt-in. Their
DNS resolution cannot be inspected and pinned locally. Repository validation
rejects that combination rather than claiming protection it cannot enforce.
Use `ALLOWLIST` for those repositories.

The shared HTTP fetcher also applies this policy to metadata-selected content
URLs, supporting cross-origin Helm archive downloads. The first connection to
the advertised URL receives the same public-address check and no upstream
credential unless it is the configured origin. Body-bearing POST requests keep
the existing strict policy; adding a body after public HTTPS authorization is
rejected rather than reusing that permission for POST. The opt-in does not authorize credential discovery
or authentication exchanges. Docker token exchange is unchanged.

The new public-target URI validation diagnostic does not retain a malformed
signed URL. Existing request-boundary reporting remains responsible for safe
failure observability; no raw provider response or signed query is added by this
feature.

## Why a header is not sufficient

[HTTP redirection](https://www.rfc-editor.org/rfc/rfc9110.html#name-redirection-3xx)
provides a destination, not a general cross-origin trust certificate. TLS
authenticates each server separately. A storage presigned URL permits object
access; it does not automatically authorize server-side network access to an
arbitrary host.

## Verification and compatibility

Tests cover opt-in Docker and shared HTTP redirects, credential stripping,
downgrade rejection, bounded redirect cycles, forbidden and mixed DNS answers,
global private-address exception isolation, repository persistence and partial
updates, explicit disable, invalid settings, runtime JSON binding, independent
runtime readers, and the admin form contract.

The default allowlist behavior is covered by existing tests. This is an opt-in
extension, not a claim that Nexus has an equivalent security-policy setting.
Live Nexus comparison and real OCI/Go/Helm client E2E have not been run for this
candidate. No deployed infrastructure is changed by the upstream PR.
