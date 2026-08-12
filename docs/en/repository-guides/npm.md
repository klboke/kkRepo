# npm Repository Guide

kkRepo supports npm `hosted`, `proxy`, and `group` repositories. Hosted repositories accept private
package publication, proxies cache an upstream registry, and groups provide one read endpoint for
private and upstream packages.

## Create The Repositories

| Purpose | Recipe | Recommended configuration |
| --- | --- | --- |
| Private packages | `npm-hosted` | Blob store, online state, write policy, strict validation |
| Public registry cache | `npm-proxy` | Remote URL `https://registry.npmjs.org/` and cache TTLs |
| Unified installs | `npm-group` | Hosted before proxy |

The examples use `npm-hosted`, `npm-proxy`, and `npm-group` as repository names.

## Configure Installs

Set the group in a user or project `.npmrc`:

```ini
registry=https://nexus.example.com/repository/npm-group/
always-auth=true
```

For a private scope, keep the scope and token bound to the same repository root:

```ini
@acme:registry=https://nexus.example.com/repository/npm-group/
//nexus.example.com/repository/npm-group/:_authToken=${NPM_TOKEN}
```

Use an `NpmToken` or another credential explicitly supported by the deployment. Keep user-level
credentials outside the project repository.

## Publish A Package

Authenticate and publish directly to hosted:

```bash
npm login --registry=https://nexus.example.com/repository/npm-hosted/
npm publish --registry=https://nexus.example.com/repository/npm-hosted/
```

Use `publishConfig.registry` in `package.json` when a package must never be sent to the public
registry. Run `npm pack --dry-run` before publication to inspect the tarball contents, and use
`npm whoami --registry=...` to verify credentials.

## Repository Behavior

- Hosted publication stores package metadata, tarballs, integrity values, and dist-tags together.
- Proxy responses rewrite upstream tarball URLs to the kkRepo endpoint and cache metadata and
  content independently.
- Group reads preserve member priority and keep metadata/tarball resolution on the selected source.
- npm audit compatibility is exposed for supported client workflows; policy enforcement remains a
  separate kkRepo security-scanning concern.

## Operations And Troubleshooting

Grant publication rights only on hosted. A `401` normally indicates invalid or missing credentials;
a `403` means the authenticated identity lacks repository permission. After changing `.npmrc`, clear
stale client state with `npm cache clean --force` only when normal retry and metadata refresh do not
resolve the problem.

## Related Documentation

- [npm client recipe](../client-recipes.md#npm)
- [Compatibility matrix](../compatibility-matrix.md#repository-format-matrix)
- [npm registry configuration](https://docs.npmjs.com/misc/registry/)
- [`npm publish` reference](https://docs.npmjs.com/cli/publish/)
