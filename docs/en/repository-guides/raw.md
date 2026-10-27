# Raw Repository Guide

kkRepo supports generic HTTP `hosted`, `proxy`, and `group` repositories. Raw repositories preserve
the caller-defined path and are suitable for files that do not have a dedicated package protocol.

## Create The Repositories

| Purpose | Recipe | Recommended configuration |
| --- | --- | --- |
| Private files | `raw-hosted` | Blob store, write policy, content validation policy |
| Remote file cache | `raw-proxy` | Remote root and cache TTLs |
| Unified reads | `raw-group` | Hosted before proxy |

Choose a stable path convention before publication, for example
`<product>/<channel>/<version>/<filename>`. Raw has no package manager to normalize identities later.

## Upload And Download

Upload to hosted with PUT:

```bash
curl -u alice:"$KKREPO_PASSWORD" \
  --upload-file archive.tar.gz \
  https://nexus.example.com/repository/raw-hosted/releases/1.0.0/archive.tar.gz
```

Read from the group:

```bash
curl --fail --remote-name \
  https://nexus.example.com/repository/raw-group/releases/1.0.0/archive.tar.gz
curl --head \
  https://nexus.example.com/repository/raw-group/releases/1.0.0/archive.tar.gz
```

The Admin UI and component upload flow are useful when operators need to attach a file without
constructing an HTTP request manually.

## Proxying Directory Pages

Set the Raw proxy's remote URL to the directory you want to mirror, for example
`https://mirrors.tuna.tsinghua.edu.cn/anaconda/archive/`. Open the repository URL
with a trailing slash: `/repository/<name>/`.

kkRepo requests the upstream directory page first. When it is not found, it tries
`index.html` and then `index.htm`. Relative file links stay within the proxy;
HTML content and absolute links are not rewritten. Browse and Search show cached
content rather than crawling the upstream website.

In the repository editor, choose **Raw settings → Content disposition → Inline**
to display HTML in the browser. **Attachment** remains the default. Enable Inline
only for trusted upstream content. The API equivalent is
`"raw": {"contentDisposition": "INLINE"}`.

## Repository Behavior

- The normalized repository-relative path is the asset identity.
- Hosted write policy determines whether an existing path may be replaced.
- Proxy maps the requested relative path to the configured remote root and caches successful content,
  validators, and negative lookups.
- Group resolves members in order; the first member containing the path wins.
- Browse displays the path hierarchy, while Search uses stored asset metadata.

## Security And Operations

Path-level content selectors are especially useful for Raw because protocol coordinates do not add
another namespace. Use least-privilege read/add/edit/delete grants, scan eligible archives, and apply
cleanup policies to an intentional prefix or metadata condition. Do not place secrets in public Raw
repositories merely because their filenames are obscure.

## Troubleshooting

A group `404` means no readable member contains the path. Check exact case, URL encoding, member
order, negative-cache TTL, and permission filtering. A `409` or write-policy failure on PUT normally
means the path already exists and replacement is not allowed.

## Related Documentation

- [Raw client recipe](../client-recipes.md#raw)
- [Compatibility matrix](../compatibility-matrix.md#repository-format-matrix)
- [HTTP semantics](https://www.rfc-editor.org/rfc/rfc9110)
