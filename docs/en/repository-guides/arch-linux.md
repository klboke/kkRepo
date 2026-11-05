# Arch Linux / Pacman Repository Guide

kkRepo implements the flat pacman repository layout: package files plus a generated package
database and file-list database. Hosted, proxy, and group repositories are supported.

## Repository types

| Use case | Recipe | Notes |
| --- | --- | --- |
| Private package repository | `arch-hosted` | Upload `.pkg.tar.zst`, `.pkg.tar.xz`, or `.pkg.tar.gz`; databases are generated |
| Mirror an Arch mirror | `arch-proxy` | Package, database, and signature paths are cached from the configured upstream |
| Merge several repositories | `arch-group` | Ordered members; the first online member wins each package identity |

The proxy remote URL must point at the directory that contains the package files and database,
for example:

```text
https://geo.mirror.pkgbuild.com/core/os/x86_64/
```

## Client configuration

Add the repository to `/etc/pacman.conf`:

```ini
[kkrepo-demo]
SigLevel = Required DatabaseRequired
Server = https://nexus.example.com/repository/arch-hosted
```

Install the repository public key first:

```bash
curl -fsSL https://nexus.example.com/repository/arch-hosted/public-key \
  -o /tmp/kkrepo-arch.asc
sudo pacman-key --add /tmp/kkrepo-arch.asc
sudo pacman-key --lsign-key "$(gpg --show-keys --with-colons /tmp/kkrepo-arch.asc \
  | awk -F: '/^fpr:/ { print $10; exit }')"
```

If no signing key is configured, use `SigLevel = Optional TrustAll` or omit `DatabaseRequired`.
The repository is flat: pacman requests `<repo>.db`, `<repo>.db.tar.gz`, `<repo>.files`, package
files, and optional `.sig` files at the repository root. kkrepo serves:

```text
/repository/arch-hosted/demo.db
/repository/arch-hosted/demo.db.tar.gz
/repository/arch-hosted/demo.files
/repository/arch-hosted/demo-1.0.0-1-x86_64.pkg.tar.zst
```

Repository database signatures are generated when an OpenPGP signing key is configured. The public
key is served at `/repository/<repo>/public-key` (and `/repository/<repo>/gpg.key`). Package
signatures are separate: upload the detached `<package>.pkg.tar.zst.sig` file and pacman downloads
it next to the package.

## Publish packages

Upload with `curl` or through the admin UI / Components API:

```bash
curl -u admin:password \
  -T demo-1.0.0-1-x86_64.pkg.tar.zst \
  https://nexus.example.com/repository/arch-hosted/demo-1.0.0-1-x86_64.pkg.tar.zst
```

The upload is validated against `.PKGINFO` inside the package. The filename must match
`<pkgname>-<pkgver>-<pkgrel>-<arch>.pkg.<compression>`, and the installed size, dependencies,
provides, conflicts, licenses, packager, and build date are projected into the generated database.

Supported compressions are `.pkg.tar.zst`, `.pkg.tar.xz`, `.pkg.tar.gz`, `.pkg.tar.bz2`, and
uncompressed `.pkg.tar`.

## Signing keys

Import an ASCII-armored OpenPGP private key, or generate one:

```http
PUT /internal/repositories/arch-hosted/arch/signing-key
Content-Type: application/json

{ "privateKey": "-----BEGIN PGP PRIVATE KEY BLOCK-----\n...", "passphrase": "optional" }
```

```json
{ "generate": true }
```

The private key is encrypted at rest with the shared credential secret and is never returned by the
API. Rotate the key through the same endpoint; the public key response retains the current and
previous key for an orderly client keyring update. `GET /internal/repositories/arch-hosted/arch/status`
returns the active revision and fingerprint, and `GET .../arch/public-key` downloads the key.

When a key is active:

- `<repo>.db.sig` and `<repo>.files.sig` are generated as detached OpenPGP signatures over the exact
  served database bytes;
- signatures use a deterministic timestamp (the key creation time for an empty repository), so the
  same database set produces a stable signature and ETag across replicas;
- group repositories sign their merged database with their own key;
- proxy repositories pass through upstream database and package signatures.

Package signature upload:

```bash
curl -u admin:password \
  -T demo-1.0.0-1-x86_64.pkg.tar.zst.sig \
  https://nexus.example.com/repository/arch-hosted/demo-1.0.0-1-x86_64.pkg.tar.zst.sig
```

kkrepo serves uploaded package `.sig` files unchanged. It does not verify them, and it does not
embed them in `%PGPSIG%`; pacman fetches the sibling `.sig` file normally.

## Generated databases

For hosted repositories kkrepo generates deterministic `<repo>.db`, `<repo>.db.tar.gz`,
`<repo>.files`, and `<repo>.files.tar.gz` responses from the durable package projection:

- entries are ordered by package name, version, and architecture;
- gzip timestamps are epoch and ownership is fixed, so replicas generate identical bytes and ETags;
- `%FILENAME%`, `%NAME%`, `%VERSION%`, `%DESC%`, `%CSIZE%`, `%ISIZE%`, `%MD5SUM%`, `%SHA256SUM%`,
  `%URL%`, `%LICENSE%`, `%ARCH%`, `%BUILDDATE%`, `%PACKAGER%`, dependency fields, `%PROVIDES%`,
  `%CONFLICTS%`, `%REPLACES%`, and `%GROUPS%` are populated from `.PKGINFO`;
- the file-list database currently emits an empty `%FILES%` list; package downloads and normal
  pacman operations do not depend on it.

## Proxy behavior

The proxy caches package files, database aliases, and signatures from the configured upstream. It
serves the upstream bytes unchanged; generated database rewriting is not performed in proxy mode.
A proxy that fronts an Arch mirror therefore preserves the mirror's own database signatures and
file lists.

## Group behavior

A group serving `<repo>.db` fetches the matching member database, parses its `desc` records, merges
package identities in member order (first member wins), and rebuilds one deterministic database.
Package files are routed to the first member that has the file. Nested groups are supported with
cycle detection.

## Browse, search, cleanup, and scanning

- Packages are exposed as components named after `pkgname` with `pkgver-pkgrel` and architecture
  attributes.
- Package archives are scan candidates; database and signature files are not.
- Cleanup removes whole package assets; the next database response reflects the changed set.

## Known limits

- OpenPGP database signing and key import/generation are implemented; package signatures are served
  but not verified or embedded in database `%PGPSIG%` fields.
- The `.files` database does not yet include package file lists.
- `.pkg.tar.lz4`, `.pkg.tar.lzo`, `.pkg.tar.lrz`, and `.pkg.tar.lz` uploads are not inspected; they
  are rejected. The same files can still be served through a proxy.
- Proxy repositories do not re-index cached packages into components until a protocol-aware proxy
  projection is added.
- Nexus does not expose an Arch Linux repository format, so repository-definition migration from
  Nexus does not apply.

## Related documentation

- [pacman repository database format](https://man.archlinux.org/man/repo-add.8)
- [Design notes](../dev/arch-repository-design.md)
