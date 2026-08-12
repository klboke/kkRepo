# Dart / Pub Repository Guide

kkRepo supports Hosted Pub Repository v2 `hosted`, `proxy`, and `group` repositories for Dart and
Flutter. Hosted accepts private package publication, proxy caches another Pub server, and group
provides a single ordered read endpoint.

## Create The Repositories

| Purpose | Recipe | Recommended configuration |
| --- | --- | --- |
| Private packages | `pub-hosted` | Blob store, write policy, strict validation |
| pub.dev cache | `pub-proxy` | Remote URL `https://pub.dev/` and cache TTLs |
| Unified reads | `pub-group` | Hosted before proxy |

Use the exact repository URL consistently because Pub token storage is keyed by hosted URL.

## Authenticate And Resolve

Create a `PubToken`, then register it for each private endpoint the client accesses:

```bash
dart pub token add https://nexus.example.com/repository/pub-group
dart pub token add https://nexus.example.com/repository/pub-hosted
```

CI can use `dart pub token add <url> --env-var KKREPO_PUB_TOKEN`. Resolve all dependencies through
the group:

```bash
PUB_HOSTED_URL=https://nexus.example.com/repository/pub-group dart pub get
PUB_HOSTED_URL=https://nexus.example.com/repository/pub-group flutter pub get
```

A single dependency can instead use the `hosted` entry in `pubspec.yaml`.

## Publish A Package

Set the hosted destination before publishing private code:

```yaml
name: demo_package
version: 1.0.0
publish_to: https://nexus.example.com/repository/pub-hosted
```

```bash
dart pub publish --dry-run
dart pub publish
```

Published package versions are immutable. Increment the semantic version for a new release.

## Repository Behavior

- Hosted stores the package archive and parsed `pubspec.yaml` metadata as one publication.
- Metadata responses include the archive URL and `archive_sha256` used by current clients.
- Proxy caches package metadata and archives from the configured upstream.
- Group source binding keeps package metadata and archive downloads on the same selected member.
- Browse and Search expose package/version metadata and archive attributes.

## Operations And Troubleshooting

Only hosted accepts publication. A token is scoped to the exact URL passed to `dart pub token add`;
scheme, host, port, and repository path must match. If Flutter behaves differently from Dart, inspect
the effective `PUB_HOSTED_URL` in the same process environment.

## Related Documentation

- [Dart / Pub client recipe](../client-recipes.md#dart--pub)
- [Compatibility matrix](../compatibility-matrix.md#repository-format-matrix)
- [Dart custom package repositories](https://dart.dev/tools/pub/custom-package-repositories)
