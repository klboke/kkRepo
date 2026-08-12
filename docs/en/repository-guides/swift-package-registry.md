# Swift Package Registry Guide

kkRepo supports Swift Package Registry v1 `hosted`, GitHub-backed `proxy`, and `group` repositories.
Hosted publishes immutable source archives, proxy projects GitHub releases into registry identities,
and group provides one ordered resolution endpoint.

## Create The Repositories

| Purpose | Recipe | Recommended configuration |
| --- | --- | --- |
| Private releases | `swift-hosted` | Blob store, write policy, signature policy |
| GitHub-backed cache | `swift-proxy` | GitHub credentials, cache TTLs, request waterlines |
| Unified reads | `swift-group` | Hosted before proxy |

Production SwiftPM registry access requires HTTPS. Configure a trusted certificate before client
login or publication.

## Configure And Authenticate SwiftPM

Set the group as the registry endpoint and log in:

```bash
swift package-registry set \
  https://nexus.example.com/repository/swift-group/

swift package-registry login \
  https://nexus.example.com/repository/swift-group/login \
  --username alice \
  --password "$KKREPO_PASSWORD" \
  --no-confirm
```

CI can use a `GenericToken` with `swift package-registry login --token <token>`. kkRepo implements
the optional `/login` endpoint and returns `401` for invalid credentials.

## Publish And Consume A Release

The source archive must contain one top-level package root and a valid `Package.swift`:

```bash
swift package-registry publish acme.demo 1.2.3 \
  --url https://nexus.example.com/repository/swift-hosted/ \
  --metadata-path package-metadata.json
```

Consume the immutable identity from `Package.swift`:

```swift
dependencies: [
    .package(id: "acme.demo", exact: "1.2.3")
]
```

Then run `swift package resolve` and `swift build`. The `/identifiers` mapping supports
`--replace-scm-with-registry` for known SCM URLs.

## Repository Behavior

- Hosted validates scope, package, SemVer, manifest, archive checksum, and optional CMS signature
  before committing a release.
- A published identity/version is immutable, including versioned `Package@swift-X.Y.swift` manifests.
- The GitHub-backed proxy pins the first observed tag commit and archive checksum; a moved tag does
  not rewrite a cached release.
- Group source binding keeps release metadata, manifests, signatures, and archive on one member.
- Range requests, cache validators, problem details, and Registry v1 media types are preserved.

## Limits And Troubleshooting

Proxy mode targets GitHub source-to-registry behavior rather than arbitrary registry chaining. The
optional `/availability` endpoint is not exposed. If login works but resolution fails, verify package
identity, group member order, and read permission for every manifest/archive request.

## Related Documentation

- [Swift client recipe](../client-recipes.md#swift-package-registry)
- [Compatibility matrix](../compatibility-matrix.md#repository-format-matrix)
- [Swift Package Registry Service Specification](https://github.com/swiftlang/swift-package-manager/blob/main/Documentation/PackageRegistry/Registry.md)
- [SwiftPM registry usage](https://github.com/swiftlang/swift-package-manager/blob/main/Documentation/PackageRegistry/PackageRegistryUsage.md)
