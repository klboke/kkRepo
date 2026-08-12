# Maven Repository Guide

kkRepo supports Maven `hosted`, `proxy`, and `group` repositories through the Nexus-compatible
`/repository/<repo>/...` URL layout. Use hosted repositories for internal releases and snapshots, a
proxy for Maven Central or another upstream, and a group as the single dependency-resolution URL.

## Create The Repositories

| Purpose | Recipe | Recommended configuration |
| --- | --- | --- |
| Internal releases | `maven2-hosted` | Release version policy and an appropriate write policy |
| Internal snapshots | `maven2-hosted` | Snapshot version policy and an appropriate write policy |
| Maven Central cache | `maven2-proxy` | Remote URL `https://repo1.maven.org/maven2/` |
| Unified dependency reads | `maven2-group` | Hosted repositories before the proxy |

Repository names are deployment choices. The examples below use `maven-releases`,
`maven-snapshots`, `maven-central`, and `maven-public`.

## Configure Dependency Resolution

Add the group as a mirror in `~/.m2/settings.xml`:

```xml
<mirrors>
  <mirror>
    <id>kkrepo</id>
    <mirrorOf>*</mirrorOf>
    <url>https://nexus.example.com/repository/maven-public/</url>
  </mirror>
</mirrors>
```

If the group is private, add a matching `<server>` entry without committing the password to the
project. Maven matches credentials by server ID.

## Publish Releases And Snapshots

Declare the hosted endpoints in the project's `distributionManagement`, and use the same IDs in
the user's `settings.xml` server entries:

```xml
<distributionManagement>
  <repository>
    <id>maven-releases</id>
    <url>https://nexus.example.com/repository/maven-releases/</url>
  </repository>
  <snapshotRepository>
    <id>maven-snapshots</id>
    <url>https://nexus.example.com/repository/maven-snapshots/</url>
  </snapshotRepository>
</distributionManagement>
```

Publish with `mvn deploy`. Low-level CI flows may also PUT artifacts directly to canonical Maven
paths, but they must upload the POM, artifact, and required checksums consistently.

## Repository Behavior

- Hosted writes validate the Maven path and update release or snapshot metadata transactionally.
- Proxy repositories cache artifacts, metadata, checksums, validators, and negative lookups from
  the configured upstream.
- Group repositories are read-only and resolve members in order. Put private hosted members before
  public proxies when internal coordinates must win.
- Browse and Search expose coordinates and assets without making generated metadata the source of
  truth.

## Operations And Security

Grant read/browse permissions to consumers and add/edit permissions only to publication identities.
Use release-oriented write policies to prevent accidental replacement. Cleanup and security scanning
operate on stored components and their assets; preview cleanup before applying it to release data.

## Related Documentation

- [Maven client recipe](../client-recipes.md#maven)
- [Compatibility matrix](../compatibility-matrix.md#repository-format-matrix)
- [Apache Maven repository introduction](https://maven.apache.org/guides/introduction/introduction-to-repositories.html)
- [Apache Maven deployment security settings](https://maven.apache.org/guides/mini/guide-deployment-security-settings.html)
