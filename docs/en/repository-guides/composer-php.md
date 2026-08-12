# Composer / PHP Repository Guide

kkRepo supports Composer 2 `hosted`, `proxy`, and `group` repositories. Hosted stores private package
archives, proxy caches another Composer repository, and group provides ordered package resolution
through one `packages.json` endpoint.

## Create The Repositories

| Purpose | Recipe | Recommended configuration |
| --- | --- | --- |
| Private packages | `composer-hosted` | Blob store, write policy, strict validation |
| Packagist cache | `composer-proxy` | Remote URL `https://repo.packagist.org/` and cache TTLs |
| Unified reads | `composer-group` | Hosted before proxy |

Composer repositories are addressed by their root URL; the client discovers `packages.json` and
package-specific p2 metadata below it.

## Configure A Project

Declare the group as a canonical repository in `composer.json`:

```json
{
  "repositories": [
    {"type": "composer", "url": "https://nexus.example.com/repository/composer-group", "canonical": true},
    {"packagist.org": false}
  ],
  "require": {
    "acme/demo": "^1.0"
  }
}
```

Disabling the default Packagist source ensures resolution does not bypass kkRepo.

## Authenticate And Install

Use Composer's `auth.json` or `COMPOSER_AUTH` support for HTTP Basic, and never commit credentials:

```bash
export COMPOSER_AUTH='{"http-basic":{"nexus.example.com":{"username":"alice","password":"'"$KKREPO_PASSWORD"'"}}}'
composer install --prefer-dist --no-interaction
composer show acme/demo --locked
```

The authentication host key must match the actual host and port used in the repository URL.

## Publish A Private Package

Composer has no standard repository publish command. Upload a zip or tar archive containing a valid
`composer.json` through the Admin UI or Nexus-compatible Components API:

```bash
curl -u alice:"$KKREPO_PASSWORD" \
  -F "composer.asset=@acme-demo-1.0.0.zip;type=application/zip" \
  -F "composer.name=acme/demo" \
  -F "composer.version=1.0.0" \
  "https://nexus.example.com/service/rest/v1/components?repository=composer-hosted"
```

## Repository Behavior

- Hosted validates archive metadata and exposes `packages.json`, stable/dev p2 metadata, and dist.
- Proxy caches metadata and distribution archives with conditional requests and negative caching.
- Group uses canonical first-match package resolution and binds metadata and dist to the same member.
- Browse, Search, Usage, and HTML View operate on parsed package/version metadata.

## Operations And Troubleshooting

Publish only to hosted. Run `composer diagnose` and `composer clear-cache` when debugging client-side
state. A package unexpectedly resolved from the public source usually means Packagist was not disabled
or repository priority/canonical settings differ from the intended policy.

## Related Documentation

- [Composer / PHP client recipe](../client-recipes.md#composer--php)
- [Compatibility matrix](../compatibility-matrix.md#repository-format-matrix)
- [Composer repository documentation](https://getcomposer.org/doc/05-repositories.md)
- [Composer private-repository authentication](https://getcomposer.org/doc/articles/authentication-for-private-packages.md)
