# Conda 仓库使用指南

kkRepo 支持 Conda `hosted`、`proxy` 和 `group` 仓库，覆盖根或嵌套 channel、`.conda` 与
`.tar.bz2` package 以及生成式 channel metadata。Hosted 接收私有 package，proxy 缓存上游
channel，group 按顺序解析 channel。

## 创建仓库

| 用途 | Recipe | 推荐配置 |
| --- | --- | --- |
| 私有 package | `conda-hosted` | Blob store、write policy、strict validation |
| 上游 channel 缓存 | `conda-proxy` | Channel root，例如 `https://repo.anaconda.com/pkgs/main/` |
| 统一读取入口 | `conda-group` | Hosted 排在 proxy 前面 |

Proxy remote 应为 channel root，不要带平台 subdir；Conda 会自动追加 `linux-64`、`noarch`
等 subdir。

## 配置客户端

在 `~/.condarc` 中使用 group，并保持 strict priority：

```yaml
channels:
  - https://nexus.example.com/repository/conda-group
channel_priority: strict
show_channel_urls: true
```

私有仓库的 HTTP Basic 凭据应放在权限受控的 `~/.netrc`，不要写入 channel URL：

```text
machine nexus.example.com
  login alice
  password <password>
```

执行 `chmod 600 ~/.netrc`，再验证准确 endpoint：

```bash
conda search --override-channels \
  -c https://nexus.example.com/repository/conda-group demo
conda create -y -n demo-env --override-channels \
  -c https://nexus.example.com/repository/conda-group demo=1.0.0
```

## 发布 Package

Conda 没有标准远程 publish 命令。通过 Admin/Components API 上传，或使用 hosted PUT 路径
`<可选-channel>/<subdir>/<filename>`：

```bash
curl -u "alice:$KKREPO_PASSWORD" \
  --upload-file demo-1.0.0-py_0.conda \
  https://nexus.example.com/repository/conda-hosted/team/release/noarch/demo-1.0.0-py_0.conda
```

Filename、目标 subdir 与 `info/index.json` 中的 name/version/build/subdir 必须一致。

## 仓库行为

- Hosted 解析 package metadata，并重建 `repodata.json`、BZ2/ZSTD 版本、
  `current_repodata.json*` 和 `channeldata.json`；不要上传生成 metadata。
- Proxy 使用 validator 与 TTL 缓存上游 metadata 和 package file。
- Group source binding 保证 repodata 与 package download 位于选中的同一成员。
- Browse 与 Search 展示 channel、subdir、name、version、build 和 package 属性。

## 限制与排障

`current_repodata.json*` 当前表示完整兼容 snapshot，而不是 conda-index 裁剪后的子集；不暴露
CEP 16 sharded repodata 和 JLAP。求解失败时先用 `--override-channels` 复现，检查 strict
channel priority 与平台 subdir，最后再清理本地 index cache。

## 相关文档

- [Conda 客户端配置示例](../client-recipes.md#conda)
- [兼容性矩阵](../compatibility-matrix.md#仓库格式矩阵)
- [Conda channel 管理](https://docs.conda.io/projects/conda/en/latest/user-guide/tasks/manage-channels.html)
- [创建自定义 Conda channel](https://docs.conda.io/projects/conda/en/25.3.x/user-guide/tasks/create-custom-channels.html)
