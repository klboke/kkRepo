# Proposal: opt-in public HTTPS redirects for proxy repositories

Status: design proposal, not an implemented runtime setting.

Tracking: [issue #384](https://github.com/klboke/kkRepo/issues/384).

## Problem

Registries and chart repositories routinely serve content through cross-origin
CDN downloads. Configuring an upstream alone does not authorize those destinations
in kkRepo. A cold pull can fail with `remote redirect URL host is not allowed`,
even when the configured upstream and the CDN are working correctly.

Observed destinations include Docker Hub's `production.cloudfront.docker.com`,
ECR Public's changing CloudFront distribution hostname, GHCR's
`pkg-containers.githubusercontent.com`, and Go's `storage.googleapis.com`.
Allowing the destination on the appropriate repository resolved the observed
Docker and Go fetches. Downloaded OCI blob bytes matched their expected digests.

[Subdomain patterns](https://github.com/klboke/kkRepo/pull/358) reduce hostname
maintenance, but still require discovering destination domains and can authorize
an entire shared CDN namespace. Operators need a supported alternative to a
custom runtime patch or global network-security exceptions.

## Proposed API

Add a repository-owned `proxy.redirectPolicy` enum:

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
| `PUBLIC_HTTPS` | Explicit opt-in to unauthenticated cross-origin HTTPS GET/HEAD content downloads, without a destination-host allowlist. |

This example is a proposed contract, not a configuration operators can use yet.
Unknown policy values must fail repository validation. Omitting the field in an
update must preserve the existing policy; explicitly selecting `ALLOWLIST` must
disable the opt-in. Existing host rules must remain stored so switching back is
predictable. There must be no global enable switch or automatic policy change on
upgrade.

The API and admin UI should explain that an administrator is trusting the
configured upstream to choose **any public HTTPS destination**, including a host
outside the upstream's organization. This is deliberately broader than an exact
allowlist and does not prove destination ownership.

## Boundary conditions

1. Require an HTTPS configured upstream for `PUBLIC_HTTPS`.
2. Require HTTPS at every download hop. Deny HTTPS-to-HTTP downgrade even if a
   destination is in `allowedRedirectHosts`.
3. Retain certificate-chain and hostname verification on every hop. Signed URLs
   are not an exemption from TLS verification.
4. Resolve and validate all destination addresses, then connect using the
   approved, pinned addresses. Deny loopback, private, link-local, multicast,
   unspecified, cloud-metadata and other non-public addresses. Cover IPv4, IPv6
   and mapped forms. Hostname checks alone are insufficient.
5. The new policy must not use an existing private-address exception to label an
   internal destination public. How this composes with
   `KKREPO_OUTBOUND_ALLOW_PRIVATE_ADDRESSES` and `KKREPO_OUTBOUND_ALLOWED_HOSTS`
   needs an explicit decision: use a dedicated public-target check or reject the
   incompatible repository policy. Do not silently relax it.
6. Strip Basic, Bearer and NTLM credentials across origins. Do not delegate
   cookies, authorization headers or proxy credentials to the destination.
   Credentials may remain usable only for their configured origin under existing
   origin checks, including a later return to that origin.
7. Limit this permission to content GET/HEAD requests. Do not replay POST/PUT
   bodies, credential exchange or authentication discovery using this policy.
8. Preserve existing redirect-depth, connection, response and pool-acquisition
   limits. Do not introduce unlimited retries or treat a denied redirect as
   missing content or success.
9. Preserve protocol integrity checks. OCI digests help verify downloaded bytes;
   they do not authorize a network destination. Protocols without equivalent
   digest guarantees still need the network and credential boundaries.
10. Report failures at the owning request boundary with safe operational context.
    Do not log signed URL query strings, credentials or raw sensitive responses.

### Explicit outbound proxies

kkRepo can delegate DNS resolution to a configured HTTP/SOCKS proxy. In that
configuration it cannot inspect the final DNS answers itself. Therefore this
proposal must not claim local address pinning guarantees for proxy-resolved DNS.

For the first implementation, reject `PUBLIC_HTTPS` with an explicit outbound
proxy unless the maintainers define and document an enforceable trusted-egress
contract. Keep existing `ALLOWLIST` behavior unchanged. A future extension could
delegate public-address enforcement to an explicitly trusted egress proxy, but
that is a separate security decision, not implicit behavior.

### Metadata-selected download URLs

Go ZIPs and Docker blobs exercise redirect chains. Helm and other protocols can
also advertise a cross-origin archive URL directly in metadata. The maintainers
should decide whether `PUBLIC_HTTPS` includes those URLs or initially applies
only to actual HTTP redirects.

If included, preserve the configured repository origin as the credential trust
root, permit only GET/HEAD content retrieval, and apply the same public-address,
TLS and credential checks before the **first** connection to the advertised URL.
Do not reuse this permission for OIDC, token realms, arbitrary external APIs or
credential-bearing protocol discovery.

## Implementation outline

- Add the field to `RepositoryCommands.ProxySettings`; preserve it through
  normalization, partial updates, redacted readback and stored proxy attributes.
- Use the shared database-backed repository attributes as the source of truth.
  No per-JVM policy state or environment-only override; use existing repository
  update/runtime invalidation semantics across replicas.
- Carry the policy through `RepositoryRuntimeRegistry` and `RepositoryRuntime`.
- Share policy evaluation between `DockerRemoteRegistryClient` and
  `HttpRemoteFetcher`. Keep authorization decisions separate from address
  validation and credential forwarding.
- Update the admin UI with the policy selector and explicit trust warning; keep
  the host editor available for strict mode.
- Add API, persistence, runtime, transport and compatibility tests before making
  the selector available. This document does not implement those changes.

## Acceptance-test plan

| Scenario | Required result |
| --- | --- |
| Omitted policy, existing repository and new repository | Existing strict behavior. |
| Strict mode, unlisted public cross-origin destination | Rejected as today. |
| Opt-in, trusted HTTPS upstream to unlisted public HTTPS CDN | GET/HEAD succeeds with identical bytes. |
| Opt-in, Basic/Bearer/NTLM upstream credential | No cross-origin credential exposure. |
| Opt-in, private/loopback/link-local/metadata target | Rejected before destination connection. |
| Public hostname resolves to public and forbidden addresses | Fail closed; transport cannot select forbidden addresses. |
| DNS changes after validation | Connection uses approved pinned address. |
| HTTPS-to-HTTP hop, including explicitly allowed host | Rejected under opt-in. |
| Invalid certificate or hostname | Rejected; no insecure retry. |
| Redirect cycle or excessive depth | Bounded explicit failure. |
| POST/PUT or authentication discovery | No new permission from this content policy. |
| Metadata-selected URL | Defined behavior, no original credential leakage. |
| Explicit DNS-resolving outbound proxy | Rejected unless an approved enforcement contract is implemented. |
| Partial update, disable, normalization, redacted readback | Policy preserved correctly; explicit disable effective. |
| Two replicas after repository update | Same effective policy through existing invalidation semantics. |
| Error handling and observability | Denial is observable; signed queries and credentials remain redacted. |

Add live Nexus comparison cases for normal redirected GET/HEAD downloads and
document any intentional opt-in differences. Run actual OCI pulls and Go/Helm
downloads against the candidate. No full Nexus behavior comparison has been
performed for this proposal, and no executable acceptance tests are included yet.

## Why a header is not sufficient

[HTTP redirection](https://www.rfc-editor.org/rfc/rfc9110.html#name-redirection-3xx)
provides a destination, not a general cross-origin trust certificate. TLS
authenticates the responding upstream and the separate destination connection.
A storage presigned URL authorizes access to a specific object; it does not
automatically authorize server-side network access to any destination host.

The proposed setting is therefore an explicit administrator trust decision, not
an attempt to infer security from `Location` or from the presence of a signature
parameter.

## Alternatives

- Continue repository-scoped exact hosts and wildcard patterns: supported now,
  and appropriate where minimal destination trust is required.
- Official upstream/CDN profiles: could reduce operator work, but still need
  maintenance and a versioned update mechanism.
- Automatically learn destinations from failures: rejected; an untrusted upstream
  response must not silently mutate security policy.
- Disable outbound checks globally or forward credentials across origins:
  rejected; unnecessary for content delivery and materially broader than this
  request.

Maintainer decisions requested: policy naming, metadata URL scope, composition
with private-address exceptions and explicit outbound proxies, and whether this
opt-in is acceptable alongside the current strict default.
