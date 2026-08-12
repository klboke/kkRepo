# PyPI Repository Guide

kkRepo supports PyPI `hosted`, `proxy`, and `group` repositories. Hosted repositories accept Python
distributions, proxies cache an upstream package index, and groups expose one PEP 503-style simple
index for private and public packages.

## Create The Repositories

| Purpose | Recipe | Recommended configuration |
| --- | --- | --- |
| Private distributions | `pypi-hosted` | Blob store, online state, write policy, strict validation |
| PyPI cache | `pypi-proxy` | Remote URL `https://pypi.org/` and cache TTLs |
| Unified installs | `pypi-group` | Hosted before proxy |

The examples use `pypi-hosted`, `pypi-proxy`, and `pypi-group` as repository names.

## Configure pip

Use the group simple-index URL in `pip.conf`:

```ini
[global]
index-url = https://nexus.example.com/repository/pypi-group/simple
```

Prefer a trusted CA over `trusted-host`. For private repositories, provide credentials through the
environment, keyring integration, or another secret store rather than committing them in project
configuration.

Verify reads with:

```bash
python -m pip install --index-url \
  https://nexus.example.com/repository/pypi-group/simple demo-package
```

## Build And Publish

Define the hosted endpoint in `~/.pypirc`, protect the file, and publish with Twine:

```ini
[distutils]
index-servers =
    kkrepo

[kkrepo]
repository = https://nexus.example.com/repository/pypi-hosted/
username = alice
password = <password-or-token>
```

```bash
python -m build
twine check dist/*
twine upload -r kkrepo dist/*
```

Environment-backed Twine credentials are preferable in CI. Keep real secrets out of `.pypirc` when
the file is shared or managed with source-controlled configuration.

## Repository Behavior

- Hosted upload validates distribution metadata and records package, version, filename, and hashes.
- Proxy repositories fetch the upstream simple index and distribution files, preserving package-name
  normalization and rewriting download links to kkRepo.
- Groups merge simple-index entries in member order and serve cached or hosted files from the bound
  source.
- Browse and Search operate on parsed package metadata rather than scraping generated HTML.

## Operations And Troubleshooting

Publish only to hosted; proxy and group are read-only. A simple-index `404` usually means the project
name was not found in any member. Check normalized names (`-`, `_`, and `.`), client credentials, and
proxy negative-cache TTL before forcing a cache reset.

## Related Documentation

- [PyPI client recipe](../client-recipes.md#pypi)
- [Compatibility matrix](../compatibility-matrix.md#repository-format-matrix)
- [Python Packaging User Guide: `.pypirc`](https://packaging.python.org/en/latest/specifications/pypirc/)
- [Python Packaging User Guide: hosting an index](https://packaging.python.org/en/latest/guides/hosting-your-own-index/)
