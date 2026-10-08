# Public HTTPS content redirects

Proxy repositories can opt into arbitrary public HTTPS content destinations with the existing
`proxy.allowedRedirectHosts` field:

```json
{
  "proxy": {
    "remoteUrl": "https://registry.example.com/",
    "allowedRedirectHosts": ["*"]
  }
}
```

The existing admin allowlist editor accepts a standalone `*`. Exact host names and patterns such
as `*.example.com` remain supported, and may be retained alongside `*`. Remove only `*` to return
to those strict rules. An empty allowlist keeps the existing default behavior. There is no separate
`proxy.redirectPolicy` API field or admin selector.

## Trust boundary

A standalone `*` trusts the upstream to choose any **public HTTPS** destination for content GET/HEAD
downloads, including metadata-selected Helm chart URLs. It is an explicit administrator opt-in,
not a host-matcher bypass. Every initial and redirected content destination is independently
validated and DNS-pinned, ignoring global allowed-host and private-address exceptions. TLS
verification and redirect limits remain enabled. Private/local/metadata addresses, mixed DNS
answers, reserved/transition ranges and HTTPS downgrades are rejected.

The configured upstream must use HTTPS and transport must be direct. The option cannot be combined
with DNS-resolving HTTP/SOCKS outbound proxies, whose final resolved addresses cannot be inspected
by this local enforcement boundary. These checks occur in repository configuration and content
transport, not source builds or unrelated deployments.

Basic, Bearer and NTLM credentials are retained only at the configured origin and removed on
cross-origin hops. Cookies are neither inherited nor stored in the pooled client for opted-in
content requests. Redirect-limit diagnostics exclude signed URLs and their query strings.

POST requests, Conan authentication/discovery, and Docker token exchanges retain their strict
rules. Hugging Face paths-info POST remains supported. Replacing Conan Basic credentials with an
exchanged bearer token preserves the content allowlist, while token requests exclude `*`.

## Replicas and operations

The allowlist is stored in existing repository attributes, preserved by partial updates and
redacted readback, and propagated through the existing runtime/cache invalidation lifecycle.
No extra policy field, database migration or cache is needed. Helm provenance fingerprints already
include sorted redirect rules, so adding or removing `*` changes the content configuration identity.

Use explicit hosts when that trust boundary is sufficient. Enable `*` only when the upstream must
select changing public CDN hosts, and treat control of that upstream as control of its public
content destination selection. This is a kkRepo opt-in extension, not a claim that Nexus exposes
the same wildcard configuration.
