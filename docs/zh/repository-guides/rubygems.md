# RubyGems 仓库使用指南

kkRepo 支持 RubyGems `hosted`、`proxy` 和 `group` 仓库。Hosted 接收私有 gem 发布，proxy
缓存上游 gem server，group 为私有与公共 gem 提供统一 source。

## 创建仓库

| 用途 | Recipe | 推荐配置 |
| --- | --- | --- |
| 私有 gem | `rubygems-hosted` | Blob store、write policy、strict validation |
| rubygems.org 缓存 | `rubygems-proxy` | Remote URL `https://rubygems.org/` 和缓存 TTL |
| 统一安装入口 | `rubygems-group` | Hosted 排在 proxy 前面 |

`gem` 命令与 Bundler 可以使用同一个 group URL。

## 配置 Source

```bash
gem sources --add \
  https://nexus.example.com/repository/rubygems-group/ \
  --remove https://rubygems.org/
gem sources --list
```

Bundler 可在 Gemfile 中把 group 设为 `source`，或配置为目标 mirror。不要把可复用凭据写入
提交的 Gemfile。

## 构建与发布

构建 gem 后直接发布到 hosted。`RubyGemsApiKey` 可以存入权限受控的 credentials 文件：

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

普通用户也可以使用 Basic authentication。未绑定 RubyGems token 格式的 CI 工具可以通过
配置的 API-key header 发送 `GenericToken`。

## 安装与 Yank

```bash
gem install demo \
  --source https://nexus.example.com/repository/rubygems-group/
gem yank demo -v 1.0.0 \
  --host https://nexus.example.com/repository/rubygems-hosted/ \
  --key kkrepo
```

Yank 更新可用性 metadata，不能替代 cleanup policy。

## 仓库行为

- Hosted 存储 gem metadata 与 archive content，并更新 compact/legacy index asset。
- Proxy 缓存上游 dependency/index 响应和 gem download。
- Group 按成员顺序解析，并保持 index/dependency metadata 与 download 对齐。
- Browse 与 Search 展示 gem name、version、platform 和 asset。

## 运维与排障

保护 `~/.gem/credentials`，并让 token 与用户密码独立轮换。配置变更后客户端仍访问旧
source 时，检查 `gem sources --list` 和 Bundler mirror 配置。Push/yank 使用 hosted，安装
使用 group。

## 相关文档

- [RubyGems 客户端配置示例](../client-recipes.md#rubygems)
- [兼容性矩阵](../compatibility-matrix.md#仓库格式矩阵)
- [RubyGems 命令参考](https://guides.rubygems.org/command-reference/)
- [RubyGems 私有 server 指南](https://guides.rubygems.org/run-your-own-gem-server/)
