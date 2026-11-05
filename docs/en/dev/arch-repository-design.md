# Arch Linux (pacman) Repository Design

Status: v1 implemented (`RepositoryFormat.ARCH`, `protocol-arch`, `server.arch`), including
OpenPGP database signing and package signature serving.

This document records the flat pacman protocol, database generation, storage model, multi-replica
semantics, integration points, and remaining work.

## Scope

Supported recipes:

- `arch-hosted`
- `arch-proxy`
- `arch-group`

The repository layout is flat, matching Arch mirrors: package files, `<repo>.db`,
`<repo>.db.tar.gz`, `<repo>.files`, `<repo>.files.tar.gz`, and optional `.sig` files at the
repository root. There is no per-package path hierarchy on the wire.

## Official protocol mapping

| Client path | kkrepo behavior |
| --- | --- |
| `<repo>.db` | Generated gzip-compressed tar database for hosted; cached upstream bytes for proxy; merged database for group |
| `<repo>.db.tar.gz` | Same database bytes with an explicit archive name |
| `<repo>.db.tar.xz`, `<repo>.db.tar.zst` | Recognized aliases; hosted returns the generated gzip database, proxy caches the upstream representation |
| `<repo>.files`, `<repo>.files.tar.gz` | Generated file-list database (currently empty `%FILES%`) for hosted; cached for proxy; merged for group |
| `<package>-<pkgver>-<pkgrel>-<arch>.pkg.tar.{zst,xz,gz,bz2}` | Package bytes |
| `*.sig` | Package signatures uploaded by maintainers are served for hosted; database signatures are generated from the repository key; proxy paths pass through upstream bytes |

`repo-add` documents the database as an optionally compressed tar; the `desc` records use the
`%FIELD%` format and the `files` database uses the same layout with `%FILES%`.

## Path parsing and identity

`protocol-arch` provides:

- `ArchPathParser` — one-segment, strict parser for database/files/signature/package names;
- `ArchPackageFilename` — right-to-left identity parsing so package names may contain hyphens;
- `ArchDesc` — deterministic `%FIELD%` renderer/parser;
- `ArchVersions` — approximate pacman epoch/pkgver/pkgrel ordering for cleanup and diagnostics.

Package identity is `(pkgname, pkgver-pkgrel, arch)`. The component version key is
`<pkgver-pkgrel>@<arch>` to keep architecture variants distinct in the generic component table;
the rendered database always uses the real `%VERSION%`.

## Hosted publication

1. PUT `<filename>` on a hosted repository.
2. Buffer to a temporary file and compute MD5/SHA-1/SHA-256/SHA-512.
3. Inspect the archive stream and read exactly one `.PKGINFO` (bounded).
4. Validate `pkgname`, `pkgver`, and `arch` against the filename.
5. Store the package through the shared blob/asset/component transaction.
6. The next `<repo>.db` request regenerates the database from component rows.

Supported compression streams: zstd, xz, gzip, bzip2, and uncompressed tar. Unsupported compressors
are rejected rather than silently imported.

## Database generation

`ArchDatabase` builds a deterministic gzip tar:

- records are sorted by name, version descending, then architecture;
- mtimes, ownership, and gzip timestamps are fixed;
- entry names are `<pkgname>-<pkgver>-<pkgrel>/desc` or `/files`;
- all metadata comes from the stored `archDesc` projection (or a bounded fallback from component
  attributes).

Because the output is a pure function of the package set, replicas produce identical bytes and
ETags. No generated database blob is persisted, so there is no publication watermark to coordinate
in v1.

## Signing and trust

Signing identities live in `arch_signing_key` and are repository-scoped:

- `encrypted_private_key` is encrypted with `SecretCipher` and
  `EncryptionSecrets.credentialSecret()`;
- only `public_key`, `key_id`, `fingerprint`, revision, and timestamps are returned by the API;
- one row is active per repository (unique partial index in PostgreSQL, generated active slot in
  MySQL); rotation inserts a new revision and deactivates the previous one;
- `GET /repository/<repo>/public-key` and `gpg.key` return the active and previous public keys.

Detached signatures are produced with BouncyCastle:

- `ArchSigningService.sign` uses `PGPSignature.BINARY_DOCUMENT` and SHA-256;
- signature creation time is deterministic for a database set (key creation time for an empty
  repository), so replicas serve identical `.db.sig`/`.files.sig` bytes and ETags;
- hosted and group repositories sign their generated databases; proxy repositories pass through
  upstream signatures;
- package `.sig` files are accepted by PUT and served next to packages, but are not verified and
  are not embedded in `%PGPSIG%` in v1.

Clients install the public key with `pacman-key --add` and then use
`SigLevel = Required DatabaseRequired` (or `PackageRequired` as appropriate).

## Proxy behavior

The proxy uses the shared `RawProxyService` for all recognized paths. It preserves upstream bytes,
including upstream database signatures and file lists. Users must point the remote URL at the
directory that contains the database/package files.

## Group behavior

For `<repo>.db`/`.files`:

1. Resolve members in order; use `<repo>.db` for a `.files` request so descriptions can be parsed.
2. Hosted members are read from components; proxy members fetch and parse the cached database;
   nested groups recurse with a cycle guard.
3. Merge by `(pkgname, arch)` with first-member-wins.
4. Rebuild one deterministic database.

For package/signature files: try members in order and return the first successful response.

## Multi-replica semantics

- Package publication uses the existing blob/asset/component transaction.
- No new registry tables or leases are required in v1.
- Databases are rebuilt from the durable component projection on every request, so any replica can
  serve any request after a committed package write.
- Group merging is stateless and deterministic for the configured membership.
- If database generation becomes a CPU bottleneck, add a `cache` module-backed node-local cache keyed
  by `(repositoryId, max(lastUpdatedAt), count, filesFlag)`; correctness must not depend on it.

## Integration points added

- `RepositoryFormat.ARCH` and recipes.
- `protocol-arch` module (path/identity/version/desc).
- `server.arch` (`ArchService`, `ArchAssetSupport`, `ArchPackageInspector`, `ArchDatabase`).
- `server.routing.arch.ArchProtocolHandler`.
- `arch_signing_key` table and `ArchSigningKeyDao` (MySQL/PostgreSQL migrations V60).
- `server.arch.ArchSigningService` and admin endpoints for import/generate/status/public key.
- Security filter: PUT ADD/EDIT and delete support.
- Upload definition and Components API upload.
- Admin/Browse icon and label maps, welcome card, search option, remote default.
- Metrics operation taxonomy, scan classification, Browse visibility.
- Migration: repository definitions migrate; content discovery is intentionally disabled until a
  protocol-aware writer exists.

## Remaining work

1. Persist package file lists and emit a real `.files` database.
2. Verify uploaded package `.sig` files and optionally embed them in `%PGPSIG%`
   (`repo-add --include-sigs` semantics).
3. Add `.pkg.tar.lz4`/`.lzo`/`.lrz`/`.lz` decompression.
4. Project proxy-cached packages into components so Browse/Search and scanning work without a
   separate index.
5. Validate with a real `pacman`/`pacman-key` matrix (signed and unsigned, multiple mirrors,
   multi-arch).
6. Add a deterministic performance baseline for large package sets and database generation.
