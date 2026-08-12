# Conda Repository Guide

kkRepo supports Conda `hosted`, `proxy`, and `group` repositories with root or nested channels,
`.conda` and `.tar.bz2` packages, and generated channel metadata. Hosted accepts private packages,
proxy caches an upstream channel, and group provides ordered channel resolution.

## Create The Repositories

| Purpose | Recipe | Recommended configuration |
| --- | --- | --- |
| Private packages | `conda-hosted` | Blob store, write policy, strict validation |
| Upstream channel cache | `conda-proxy` | Channel root such as `https://repo.anaconda.com/pkgs/main/` |
| Unified reads | `conda-group` | Hosted before proxy |

The proxy remote is the channel root, not a platform subdirectory. Conda adds `linux-64`, `noarch`,
and other subdirectories automatically.

## Configure The Client

Use the group in `~/.condarc` and keep priority strict:

```yaml
channels:
  - https://nexus.example.com/repository/conda-group
channel_priority: strict
show_channel_urls: true
```

For private repositories, put HTTP Basic credentials in a protected `~/.netrc` rather than the
channel URL:

```text
machine nexus.example.com
  login alice
  password <password>
```

Run `chmod 600 ~/.netrc`, then verify the exact endpoint:

```bash
conda search --override-channels \
  -c https://nexus.example.com/repository/conda-group demo
conda create -y -n demo-env --override-channels \
  -c https://nexus.example.com/repository/conda-group demo=1.0.0
```

## Publish A Package

Conda has no standard remote publish command. Upload through Admin/Components API or use the hosted
PUT path `<optional-channel>/<subdir>/<filename>`:

```bash
curl -u "alice:$KKREPO_PASSWORD" \
  --upload-file demo-1.0.0-py_0.conda \
  https://nexus.example.com/repository/conda-hosted/team/release/noarch/demo-1.0.0-py_0.conda
```

The filename, target subdir, and `info/index.json` name/version/build/subdir must agree.

## Repository Behavior

- Hosted parses package metadata and rebuilds `repodata.json`, BZ2/ZSTD variants,
  `current_repodata.json*`, and `channeldata.json`; do not upload generated metadata.
- Proxy uses validators and TTLs for upstream metadata and package files.
- Group source binding keeps repodata and package downloads on one selected member.
- Browse and Search expose channel, subdir, name, version, build, and package attributes.

## Limits And Troubleshooting

`current_repodata.json*` currently represents the complete compatible snapshot rather than a pruned
conda-index subset. CEP 16 sharded repodata and JLAP are not exposed. When solving fails, reproduce
with `--override-channels`, inspect strict channel priority and platform subdirs, and only then clear
the local index cache.

## Related Documentation

- [Conda client recipe](../client-recipes.md#conda)
- [Compatibility matrix](../compatibility-matrix.md#repository-format-matrix)
- [Conda channel management](https://docs.conda.io/projects/conda/en/latest/user-guide/tasks/manage-channels.html)
- [Creating custom Conda channels](https://docs.conda.io/projects/conda/en/25.3.x/user-guide/tasks/create-custom-channels.html)
