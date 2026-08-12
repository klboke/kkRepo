# Cargo / Rust Repository Guide

kkRepo supports Cargo sparse registry `hosted`, `proxy`, and `group` repositories. Hosted accepts
crate publication and yank state changes, proxy caches an upstream sparse registry, and group gives
clients one ordered read endpoint.

## Create The Repositories

| Purpose | Recipe | Recommended configuration |
| --- | --- | --- |
| Private crates | `cargo-hosted` | Blob store, write policy, strict validation |
| Public registry cache | `cargo-proxy` | Sparse registry remote and cache TTLs |
| Unified reads | `cargo-group` | Hosted before proxy |

kkRepo targets the sparse index protocol. A Cargo git-index repository is not part of the supported
surface.

## Configure Cargo

Define alternate registries in `.cargo/config.toml`:

```toml
[registries.kkrepo]
index = "sparse+https://nexus.example.com/repository/cargo-group/"

[registries.kkrepo_hosted]
index = "sparse+https://nexus.example.com/repository/cargo-hosted/"
```

Create a `CargoToken` and provide it through an environment variable in CI:

```bash
export CARGO_REGISTRIES_KKREPO_TOKEN="$CARGO_TOKEN"
export CARGO_REGISTRIES_KKREPO_HOSTED_TOKEN="$CARGO_TOKEN"
```

Token lookup is tied to the registry name after normalization.

## Publish, Resolve, And Yank

```bash
cargo login --registry kkrepo_hosted "$CARGO_TOKEN"
cargo publish --registry kkrepo_hosted
cargo search serde --registry kkrepo
cargo fetch
cargo yank demo-crate --version 1.0.0 --registry kkrepo_hosted
cargo yank demo-crate --version 1.0.0 --undo --registry kkrepo_hosted
```

Use alternate registries for groups that mix private crates with a public proxy. Source replacement
should only be used when the replacement is intentionally equivalent to the original source.

## Repository Behavior

- Hosted commits the `.crate` archive and sparse index record together; published versions are not
  silently replaced.
- Yank and unyank update index state without deleting the crate archive.
- Proxy caches `config.json`, sparse index records, and crate downloads with validators.
- Group source binding keeps index metadata and crate download on the same selected member.

## Operations And Troubleshooting

Grant publish/yank rights only on hosted. If Cargo reports an authentication requirement while
reading the index, verify the token environment variable name and exact registry URL. If a crate is
visible in search but cannot be fetched, check group member ordering and source binding before
clearing caches.

## Related Documentation

- [Cargo / Rust client recipe](../client-recipes.md#cargo--rust)
- [Compatibility matrix](../compatibility-matrix.md#repository-format-matrix)
- [Cargo registries](https://doc.rust-lang.org/cargo/reference/registries.html)
- [Cargo registry index protocols](https://doc.rust-lang.org/cargo/reference/registry-index.html)
