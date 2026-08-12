# Go Repository Guide

kkRepo supports Go module `proxy` and `group` repositories. The format implements the official
`GOPROXY` read protocol; a Go hosted repository and direct module publication are not currently
exposed.

## Create The Repositories

| Purpose | Recipe | Recommended configuration |
| --- | --- | --- |
| Public module cache | `go-proxy` | Remote URL `https://proxy.golang.org/` and cache TTLs |
| Unified reads | `go-group` | Ordered Go proxy/group members |

A group is useful when several upstream module proxies must be searched in a controlled order. It
does not turn the repository into a publication endpoint.

## Configure The Go Client

Point `GOPROXY` at the group:

```bash
go env -w GOPROXY=https://nexus.example.com/repository/go-group/,direct
```

The comma permits fallback to `direct` only after a proxy returns `404` or `410`. Remove `,direct`
when builds must never bypass kkRepo. For private module namespaces, configure checksum behavior
explicitly:

```bash
go env -w GONOSUMDB=git.example.com/acme/*
```

Do not set `GONOPROXY` for namespaces that are intended to pass through kkRepo, because it tells the
Go client to bypass the configured proxy.

## Resolve And Verify Modules

```bash
go list -m github.com/pkg/errors@latest
go mod download github.com/pkg/errors
go mod verify
```

Private proxy credentials can be supplied through the Go client's supported HTTP credential
mechanisms, such as a protected `.netrc` file. Never embed reusable credentials in `go.mod` or a
repository URL committed to source control.

## Repository Behavior

- The proxy serves module version lists, `.info`, `.mod`, `.zip`, and `@latest` endpoints.
- Cached module versions are content-addressed and reused across replicas through shared metadata
  and blob storage.
- Group resolution respects member order and only falls through when the prior member reports the
  module/version as absent.
- Proxy cache and negative-cache TTLs control refresh behavior; Go's local module cache is separate.

## Troubleshooting

Use `go env GOPROXY GONOPROXY GONOSUMDB GOPRIVATE` to inspect effective client settings. If a module
remains stale after an upstream change, distinguish the local Go module cache from the kkRepo proxy
cache before invalidating either one. Authentication failures and policy denials must not be treated
as “not found” fallthrough.

## Related Documentation

- [Go client recipe](../client-recipes.md#go)
- [Compatibility matrix](../compatibility-matrix.md#repository-format-matrix)
- [Go Modules Reference and GOPROXY protocol](https://go.dev/ref/mod#goproxy-protocol)
