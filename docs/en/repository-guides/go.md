# Go Repository Guide

kkRepo supports Go module `hosted`, `proxy`, and `group` repositories. Reads implement the official
`GOPROXY` protocol. Hosted publication follows the Nexus 3.93+ upload contract: PUT one canonical
module ZIP to `<version>.zip`; the module coordinate is read from the archive root.

## Create The Repositories

| Purpose | Recipe | Recommended configuration |
| --- | --- | --- |
| Private modules | `go-hosted` | `ALLOW_ONCE` for immutable versions; enable Cleanup/scanning as required |
| Public module cache | `go-proxy` | Remote URL `https://proxy.golang.org/` and cache TTLs |
| Unified reads | `go-group` | Hosted first, then one or more proxy members |

Go groups accept Go hosted and proxy members. Nested Go groups are rejected. Concrete `.info`,
`.mod`, and `.zip` requests use configured member order. Version lists are merged, deduplicated,
filtered according to the Go proxy rules, and sorted with Go SemVer precedence. `@latest` is selected
across members, preferring a release over a prerelease and a pseudo-version.

## Publish A Private Module

The ZIP must contain files below one `<module>@<version>/` root. The root uses the module path
directly; the `!` case escaping used in proxy URLs does not appear inside the ZIP.

```text
git.example.com/acme/payments@v1.2.3/go.mod
git.example.com/acme/payments@v1.2.3/payments.go
git.example.com/acme/payments@v1.2.3/LICENSE
```

Upload the archive with the version as the repository-root filename:

```bash
curl --fail-with-body \
  -u "$KKREPO_USER:$KKREPO_PASSWORD" \
  --upload-file v1.2.3.zip \
  https://nexus.example.com/repository/go-hosted/v1.2.3.zip
```

The Admin UI and Components API expose the same single-ZIP upload and reuse the same validator and
transactional writer. Publication creates the canonical `.mod`, `.info`, and `.zip` assets. Their
metadata bindings become visible in one database transaction, so another replica cannot observe a
partially published version.

The validator enforces the official module path, canonical Go version, path-major suffix, ZIP root,
case-fold collision, regular-file, and size rules. The compressed archive and expanded files are
limited to 500 MiB; a root `go.mod` and `LICENSE` are each limited to 16 MiB. Symbolic links, unsafe
paths, nested or incorrectly-cased `go.mod` files, and mismatched module directives are rejected. If
the root `go.mod` is absent, kkRepo generates the minimal `module <path>` document required by the
`.mod` endpoint.

Hosted write policies apply to the complete module version:

- `DENY` rejects publication.
- `ALLOW_ONCE` rejects a coordinate that already has any published release asset.
- `ALLOW` replaces all three release assets atomically and retires unreferenced prior blobs.

## Configure The Go Client

Point `GOPROXY` at the group:

```bash
go env -w GOPROXY=https://nexus.example.com/repository/go-group/,direct
```

The comma permits fallback to `direct` only after a proxy returns `404` or `410`. Remove `,direct`
when builds must never bypass kkRepo. For private module namespaces, configure checksum behavior
explicitly:

```bash
go env -w GOPRIVATE=git.example.com/acme/*
go env -w GONOSUMDB=git.example.com/acme/*
```

Do not set `GONOPROXY` for namespaces that must pass through kkRepo: it bypasses the configured
proxy. Supply private repository credentials through a protected `.netrc` or another credential
mechanism supported by the Go client; never commit reusable credentials in `go.mod` or repository
URLs.

Resolve and verify modules normally:

```bash
go list -m git.example.com/acme/payments@latest
go mod download git.example.com/acme/payments@v1.2.3
go mod verify
```

## Cleanup And Artifact Scanning

Each module version is one component containing its `.mod`, `.info`, and `.zip` assets. Cleanup
`retainCount` uses Go SemVer ordering and treats older migrated `package` rows and native
`go-module` rows as one family. Published-age and last-downloaded conditions use the existing shared
policy engine; deleting a selected component removes all three assets. Downloads through a group
record usage against the concrete hosted or proxy member.

The release `.zip` is an Artifact Scanning candidate; generated `.mod` and `.info` metadata are not.
Publication emits the standard transactional asset-change events, so scanning remains asynchronous
and multi-replica safe. When download enforcement is enabled, policy is evaluated for the concrete
ZIP blob before its object-store body is opened, including reads through a group.

## Migration And Operations

Nexus Go hosted definitions and data participate in the normal preflight, resumable metadata/blob,
checksum, and report workflow when the source content model is proven. Proxy cache migration remains
explicit opt-in. Group member order is preserved, and migrated legacy component kinds remain
compatible with native hosted lists and Cleanup.

Proxy cache and negative-cache TTLs control upstream refresh; the Go client's local module cache is
a separate layer. Use `go env GOPROXY GONOPROXY GONOSUMDB GOPRIVATE` when troubleshooting. An
authentication, authorization, scanning, or write-policy denial is not converted into “not found”
group fallthrough.

## Related Documentation

- [Go client recipe](../client-recipes.md#go)
- [Compatibility matrix](../compatibility-matrix.md#repository-format-matrix)
- [Cleanup Policy Guide](../cleanup-policy-guide.md)
- [Artifact Scanning Guide](../artifact-scanning-guide.md)
- [Go hosted performance baseline](../dev/go-hosted-performance-baseline.md)
- [Go Modules Reference and GOPROXY protocol](https://go.dev/ref/mod#goproxy-protocol)
