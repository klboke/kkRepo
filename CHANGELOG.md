# Changelog

All notable public changes to kkrepo are documented in this file.

This project follows a pragmatic early-stage release process. Until a stable `1.0.0` release is announced, minor versions may include behavior changes, but releases should call out migration impact, compatibility changes, and operational notes.

## Unreleased

### Added

- Cargo / Rust repository support for hosted, proxy, and group repositories using Cargo sparse registries, including `cargo publish`, fetch/download, yank/unyank, Cargo search, `CargoToken` authentication, and UI/API `.crate` upload.
- Cargo / Rust live compatibility coverage against a Nexus Repository 3.77.x+ reference instance, including hosted, proxy, and group checks through the `run-live-compat` workflow path.
- Docker / OCI Registry implementation planning docs in English and Chinese.
- English documentation now lives under `docs/en`.
- Community files for open source collaboration: Code of Conduct, Support policy, Dependabot configuration, compatibility matrix, and troubleshooting guide.

### Changed

- README, compatibility, client recipe, and operations docs now list Docker / OCI and Cargo / Rust as implemented repository capabilities. Cargo Nexus repository migration remains explicitly TBD because Nexus Community Cargo support starts from the 3.77.x datastore-era H2/PostgreSQL shape.

## 0.1.0 - 2026-06-15

### Added

- First public release of kkrepo.
- Public Docker image on GitHub Container Registry.
- Archive distributions as `.zip` and `.tar.gz` with SHA-256 checksums.
- Quickstart script for local trials with Docker Compose and MySQL.
- Nexus-compatible repository entrypoint under `/repository/<repo>/...`.
- Initial support for Maven, npm, PyPI, Go, Helm, NuGet, RubyGems, Yum, and Raw repositories.
- Admin console under `/admin/` and user repository browser under `/browse/`.
- MySQL-backed metadata, identity, permissions, token, audit, migration, and coordination state.
- OSS/S3/File blob storage support.
- Nexus migration tooling for metadata and repository data migration.
- Compatibility test module for Nexus reference behavior checks.

### Notes

- Production deployments should use external MySQL and OSS/S3-compatible blob storage.
- File blob storage is intended for local trials, development, and specific deployments with carefully managed shared storage.
- Security fixes currently target the latest `main` branch unless a release branch is explicitly announced.
