# NuGet Repository Guide

kkRepo supports NuGet `hosted`, `proxy`, and `group` repositories with a NuGet V3 service index.
Hosted accepts private `.nupkg` publication, proxy caches an upstream service, and group provides one
restore/search endpoint.

## Create The Repositories

| Purpose | Recipe | Recommended configuration |
| --- | --- | --- |
| Private packages | `nuget-hosted` | Blob store, write policy, strict validation |
| nuget.org cache | `nuget-proxy` | Remote URL `https://api.nuget.org/v3/index.json` |
| Unified restores | `nuget-group` | Hosted before proxy |

Configure the proxy with the upstream V3 service index, not an individual flat-container resource.

## Configure A Package Source

Add the group for restore:

```bash
dotnet nuget add source \
  https://nexus.example.com/repository/nuget-group/v3/index.json \
  --name kkrepo
```

For a private source, use the platform-appropriate credential provider when possible. The following
portable example stores the password in clear text and should only be used with a protected user
configuration file:

```bash
dotnet nuget add source \
  https://nexus.example.com/repository/nuget-hosted/v3/index.json \
  --name kkrepo-hosted \
  --username alice \
  --password "$KKREPO_PASSWORD" \
  --store-password-in-clear-text
```

## Pack, Publish, And Restore

Create a `NuGetApiKey` token for publication:

```bash
dotnet pack --configuration Release
dotnet nuget push bin/Release/Demo.1.0.0.nupkg \
  --source https://nexus.example.com/repository/nuget-hosted/ \
  --api-key "$KKREPO_API_KEY"
dotnet restore \
  --source https://nexus.example.com/repository/nuget-group/v3/index.json
```

Publish only to hosted. Proxy and group service indexes advertise read/search resources and do not
become writable because the client has an API key.

## Repository Behavior

- Hosted parses package identity/version and exposes package content, registration, and search data.
- Proxy discovers upstream resources from the configured service index and caches metadata/content.
- Group service-index resources point back to the group and preserve member priority for registration,
  flat-container, search, and autocomplete requests.
- Browse and Search expose package coordinates and stored assets.

## Operations And Troubleshooting

The API key is sent through `X-NuGet-ApiKey`; HTTP source credentials are a separate concern. A push
`401` usually indicates an invalid key or source credential, while `403` indicates missing add/edit
permission. Use `dotnet nuget list source` and a verbose restore to confirm the effective V3 endpoint.

## Related Documentation

- [NuGet client recipe](../client-recipes.md#nuget)
- [Compatibility matrix](../compatibility-matrix.md#repository-format-matrix)
- [NuGet V3 Server API](https://learn.microsoft.com/en-us/nuget/api/overview)
- [NuGet package publish resource](https://learn.microsoft.com/en-us/nuget/api/package-publish-resource)
