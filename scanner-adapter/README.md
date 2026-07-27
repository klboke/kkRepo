# kkRepo Scanner Adapter

This service implements kkRepo's versioned internal `v1` scanner contract. It catalogs immutable
artifact content with Syft, persists CycloneDX through kkRepo, and matches the saved SBOM with
Grype. It owns no durable task state.

Security boundaries:

- the image runs as uid/gid `10001`, requires no Docker socket, and needs only an ephemeral
  workspace plus a rebuildable Grype database volume;
- artifact requests are streamed to a per-request directory, SHA-256 and size are checked before
  scanning, and archive paths, special files, expansion, entry count, size, and nesting are
  bounded;
- binaries are invoked directly without a shell, with a minimal environment, bounded output,
  timeout, forced process termination, and cleanup;
- OCI targets use an exact manifest digest and the request-scoped registry token only;
- database update is disabled by default. Set `KKREPO_SCANNER_DB_AUTO_UPDATE=true` only in a
  deployment whose egress policy permits the Grype database source.

Build from the repository root after packaging:

```bash
mvn -pl scanner-adapter -am package
docker build -f scanner-adapter/Dockerfile -t kkrepo-scanner:local .
```

The quickstart Compose files expose this as an opt-in profile:

```bash
COMPOSE_PROFILES=security-scanning \
KKREPO_SECURITY_SCANNING_ENABLED=true \
KKREPO_SECURITY_SCANNING_SERVICE_CREDENTIAL="$(openssl rand -hex 32)" \
docker compose -f docker-compose.quickstart.yml up -d
```

The Compose profile starts this adapter container, while
`KKREPO_SECURITY_SCANNING_ENABLED=true` enables kkRepo's coordination workers. Neither setting
activates a repository; select repositories later in **Admin > Security > Artifact Scanning**.

Use `docker-compose.quickstart-postgresql.yml` in the same way for PostgreSQL.
