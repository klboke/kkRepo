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

- Group 索引包含尚未通过 proxy 下载的版本。同名 gem 会合并各成员的版本；相同版本和
  platform 的元数据以排在前面的成员为准。
- 若成员不提供可选的 dependency API 或 compact versions 索引，group 对该接口返回 404，
  让客户端回退到完整的 legacy 索引。升级后无需预先下载上游 gem，也无需重建 group 索引。
- Group 合并过程中最多保留 300 万条不同记录；包含编码开销估算的数据量上限为
  legacy/dependency 索引 256 MiB、compact 索引 64 MiB。超过限制返回 502，需要将成员拆成
  更小的 group。所有 group metadata 请求（包括 dependency 查询和单个 gem 的 info）共享
  进程内处理容量，繁忙时最多等待 30 秒，随后返回带 `Retry-After: 1` 的 503。合并冲突版本时，
  若提供版本的成员 info 不可用，则返回 404 供客户端回退，避免发布不完整的合并索引。

## 运维与排障

保护 `~/.gem/credentials`，并让 token 与用户密码独立轮换。配置变更后客户端仍访问旧
source 时，检查 `gem sources --list` 和 Bundler mirror 配置。Push/yank 使用 hosted，安装
使用 group。

## 相关文档

- [RubyGems 客户端配置示例](../client-recipes.md#rubygems)
- [兼容性矩阵](../compatibility-matrix.md#仓库格式矩阵)
- [RubyGems 命令参考](https://guides.rubygems.org/command-reference/)
- [RubyGems 私有 server 指南](https://guides.rubygems.org/run-your-own-gem-server/)
