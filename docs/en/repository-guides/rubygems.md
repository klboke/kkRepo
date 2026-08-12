# RubyGems Repository Guide

kkRepo supports RubyGems `hosted`, `proxy`, and `group` repositories. Hosted accepts private gem
publication, proxy caches an upstream gem server, and group provides one source for private and
public gems.

## Create The Repositories

| Purpose | Recipe | Recommended configuration |
| --- | --- | --- |
| Private gems | `rubygems-hosted` | Blob store, write policy, strict validation |
| rubygems.org cache | `rubygems-proxy` | Remote URL `https://rubygems.org/` and cache TTLs |
| Unified installs | `rubygems-group` | Hosted before proxy |

The same group URL can be used by the `gem` command and Bundler.

## Configure A Source

```bash
gem sources --add \
  https://nexus.example.com/repository/rubygems-group/ \
  --remove https://rubygems.org/
gem sources --list
```

For Bundler, use the group as the `source` in the Gemfile, or configure it as the intended mirror.
Do not embed reusable credentials in a committed Gemfile.

## Build And Publish

Build the gem, then publish directly to hosted. A `RubyGemsApiKey` can be stored in the protected
credentials file:

```yaml
# ~/.gem/credentials
:kkrepo: RubyGemsApiKey.REDACTED
```

```bash
chmod 0600 ~/.gem/credentials
gem build demo.gemspec
gem push demo-1.0.0.gem \
  --host https://nexus.example.com/repository/rubygems-hosted/ \
  --key kkrepo
```

Basic authentication is also supported for normal users. CI tools not coupled to RubyGems token
format can send a `GenericToken` through the configured API-key header.

## Install And Yank

```bash
gem install demo \
  --source https://nexus.example.com/repository/rubygems-group/
gem yank demo -v 1.0.0 \
  --host https://nexus.example.com/repository/rubygems-hosted/ \
  --key kkrepo
```

Yank updates availability metadata; it is not a substitute for a cleanup policy.

## Repository Behavior

- Hosted stores gem metadata and archive content and updates compact/legacy index assets.
- Proxy caches dependency/index responses and gem downloads from the upstream.
- Group resolves members in order and keeps index/dependency metadata aligned with downloads.
- Browse and Search expose gem name, version, platform, and assets.

## Operations And Troubleshooting

Protect `~/.gem/credentials` and rotate tokens independently from user passwords. If the client sees
an old source after configuration changes, inspect `gem sources --list` and Bundler mirror settings.
Use hosted for push/yank and group for install.

## Related Documentation

- [RubyGems client recipe](../client-recipes.md#rubygems)
- [Compatibility matrix](../compatibility-matrix.md#repository-format-matrix)
- [RubyGems command reference](https://guides.rubygems.org/command-reference/)
- [RubyGems private server guide](https://guides.rubygems.org/run-your-own-gem-server/)
