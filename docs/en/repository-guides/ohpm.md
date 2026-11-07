# OHPM / HarmonyOS Repository Guide

kkRepo supports OHPM hosted, proxy and group repositories for HAR/HSP packages. OHPM uses Huawei's repository protocol; it is a separate format from npm. Client URLs retain `/repository/<name>/`.

## Create repositories

In **Admin → Repositories**, create the following repositories using an appropriate blob store for hosted/proxy:

| Example name | Recipe | Purpose |
| --- | --- | --- |
| `ohpm-hosted` | `ohpm-hosted` | Publish private HAR/HSP packages; versions cannot be overwritten |
| `ohpm-proxy` | `ohpm-proxy` | Cache an upstream registry, such as `https://ohpm.openharmony.cn/ohpm` |
| `ohpm-all` | `ohpm-group` | Read from ordered members, for example hosted first, then proxy |

Proxy and group repositories reject publication, tag changes and unpublish. A group accepts only OHPM hosted/proxy members, not nested groups. Publishing goes directly to hosted; installation can use the group.

For public proxy downloads, configure the upstream's actual CDN hosts in the redirect allowlist; the observed baseline uses `contentcenter-drcn.dbankcdn.cn`. For a private upstream, configure its credential separately from client credentials. See [Proxy configuration and cache behavior](../dev/ohpm-proxy.md) and [Group precedence and download consistency](../dev/ohpm-group.md).

## Authenticate and use the client

1. Create or choose an existing local, LDAP or OIDC user, including a dedicated CI account where appropriate.
2. Assign repository permissions to that user through roles. Downloads require READ; first publication requires ADD; another version of an existing package also requires EDIT; unpublish requires DELETE. Grant READ on the repository endpoint used for installation, including the group when clients use it.
3. Open **Admin → Security → Keys & Tokens → API Tokens → Create API key**. Choose `GenericToken`, select the source and user, and save the returned token securely. The searchable user picker requires permission to read users. API key permissions come from the owner; stored `scopes` metadata does not independently restrict a token.
4. Configure the project using the [OHPM client recipe](../client-recipes.md#ohpm--harmonyos). OHPM sends the raw token in `Authorization`, not `Bearer <token>`.

```ini
registry=https://repo.example.com/repository/ohpm-all/
publish_registry=https://repo.example.com/repository/ohpm-hosted/
//repo.example.com/repository/ohpm-all/:_auth=<GenericToken>
//repo.example.com/repository/ohpm-hosted/:_auth=<GenericToken>
```

```bash
ohpm publish ./example.har
ohpm install @scope/example@1.0.0
ohpm dist-tags add @scope/example@1.0.0 beta
ohpm unpublish @scope/example@1.0.0
```

Replace the archive and package coordinates with your own. Use HTTPS and keep tokens out of source control. The CLI chooses normal or streaming publication according to its configuration and package size. HAR/HSP contents and SHA-512 are verified before publication. Existing versions are immutable; after explicit unpublish, a version can be published again. See [Hosted HAR/HSP behavior](../dev/ohpm-hosted.md) for paired HSP files, size limits and retries.

## Optional public-key login

GenericToken supports the publish/install workflow without public-key registration. If you need RSA signed login, enable `kkrepo.ohpm.login.enabled=true` on every replica and restart the affected processes. It is disabled by default.

Open **Admin → Security → Keys & Tokens → OHPM Public Keys → Register public key**. In the dialog, select an existing user's source and ID and paste its X.509 PEM RSA public key (2048–8192 bits). New registrations are enabled immediately; private keys stay on the publishing machine. Use **Rotate** for replacement and **Disable** to stop use. The page has the standard page-size selector and pagination controls.

While signed login is disabled, the UI blocks registration, rotation and enabling, while retaining viewing, disabling and deletion. The availability notice disappears when signed login is enabled.

The public key identifies a **user**, not a repository. Successful login issues a short-lived token that still uses that user's current repository permissions. To restrict automation to one repository, use a dedicated user with only the required repository grants. This key is not a repository metadata-signing key or a package signature. Each user has one registered public key; rotation preserves the publish ID and current enabled state and revokes old signed-login tokens.

Configure `publish_id` and `key_path` using the [signed-login guide](../dev/ohpm-login.md). Signed login is available only on hosted repositories. It does not enable `login_pss`; the tested 6.0.1 client falls back to classic Login.

## Browse, upload and import

- Browse/Search exposes stored packages, versions, tags, checksums, source information and download links.
- UI/Components API upload accepts HAR, interface HAR plus HSP, or a complete HSP bundle. Package/version deletion uses OHPM-aware unpublish. See [Management and browsing](../dev/ohpm-management.md).
- **Admin → OHPM Import** imports from a directory or an existing OHPM registry with dry-run, checksum verification, durable progress and resume. It is independent of Nexus migration; see [Import](../dev/ohpm-import.md).

## Boundaries and validation

- Automatic cleanup is disabled for OHPM. Use explicit package/version unpublish.
- Security scanning reports `NOT_APPLICABLE`; it does not establish HAR/HSP vulnerability coverage.
- Client contracts use OHPM 6.0.1. Full E2E installs pinned OHPM 5.0.2 and covers hosted/proxy/group publication/installation with signed login both disabled and enabled, within the existing core shard on JVM/Native × MySQL/PostgreSQL.
- HSP tests establish protocol behavior and file integrity, not execution on HarmonyOS devices. Support for arbitrary client versions is not implied.
- For commands, per-run reports and separate real S3 fault coverage, see [Acceptance and CI](../dev/ohpm-acceptance.md) and the [Compatibility Matrix](../compatibility-matrix.md).
