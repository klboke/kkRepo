# Cargo / Rust 仓库使用指南

kkRepo 支持 Cargo sparse registry 的 `hosted`、`proxy` 和 `group` 仓库。Hosted 接收
crate 发布和 yank 状态变更，proxy 缓存上游 sparse registry，group 提供统一的有序读取入口。

## 创建仓库

| 用途 | Recipe | 推荐配置 |
| --- | --- | --- |
| 私有 crate | `cargo-hosted` | Blob store、write policy、strict validation |
| 公共 registry 缓存 | `cargo-proxy` | Sparse registry remote 和缓存 TTL |
| 统一读取入口 | `cargo-group` | Hosted 排在 proxy 前面 |

kkRepo 面向 sparse index 协议，不支持 Cargo git-index 仓库。

## 配置 Cargo

在 `.cargo/config.toml` 中定义 alternate registry：

```toml
[registries.kkrepo]
index = "sparse+https://nexus.example.com/repository/cargo-group/"

[registries.kkrepo_hosted]
index = "sparse+https://nexus.example.com/repository/cargo-hosted/"
```

创建 `CargoToken`，并在 CI 中通过环境变量提供：

```bash
export CARGO_REGISTRIES_KKREPO_TOKEN="$CARGO_TOKEN"
export CARGO_REGISTRIES_KKREPO_HOSTED_TOKEN="$CARGO_TOKEN"
```

Token 查找与规范化后的 registry 名称绑定。

## 发布、解析与 Yank

```bash
cargo login --registry kkrepo_hosted "$CARGO_TOKEN"
cargo publish --registry kkrepo_hosted
cargo search serde --registry kkrepo
cargo fetch
cargo yank demo-crate --version 1.0.0 --registry kkrepo_hosted
cargo yank demo-crate --version 1.0.0 --undo --registry kkrepo_hosted
```

Group 同时包含私有 crate 和公共 proxy 时使用 alternate registry。只有替代源与原始源确实
等价时才使用 source replacement。

## 仓库行为

- Hosted 会一起提交 `.crate` archive 与 sparse index record，已发布版本不会被静默覆盖。
- Yank/unyank 只更新 index 状态，不删除 crate archive。
- Proxy 使用 validator 缓存 `config.json`、sparse index record 和 crate download。
- Group source binding 保证 index metadata 与 crate download 来自选中的同一成员。

## 运维与排障

Publish/yank 权限只授予 hosted。Cargo 读取 index 时提示认证，检查 token 环境变量名和准确
registry URL。Search 可见但无法 fetch 时，先检查 group 成员顺序与 source binding，再考虑
清理缓存。

## 相关文档

- [Cargo / Rust 客户端配置示例](../client-recipes.md#cargo--rust)
- [兼容性矩阵](../compatibility-matrix.md#仓库格式矩阵)
- [Cargo registries](https://doc.rust-lang.org/cargo/reference/registries.html)
- [Cargo registry index 协议](https://doc.rust-lang.org/cargo/reference/registry-index.html)
