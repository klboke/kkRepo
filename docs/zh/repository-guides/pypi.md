# PyPI 仓库使用指南

kkRepo 支持 PyPI `hosted`、`proxy` 和 `group` 仓库。Hosted 接收 Python distribution，
proxy 缓存上游 package index，group 为私有包和公共包提供统一的 PEP 503 simple index。

## 创建仓库

| 用途 | Recipe | 推荐配置 |
| --- | --- | --- |
| 私有 distribution | `pypi-hosted` | Blob store、online、write policy、strict validation |
| PyPI 缓存 | `pypi-proxy` | Remote URL `https://pypi.org/`、Remote Index Path `/simple` 和缓存 TTL |
| 统一安装入口 | `pypi-group` | Hosted 排在 proxy 前面 |

下文使用 `pypi-hosted`、`pypi-proxy` 和 `pypi-group` 作为仓库名。

### 不使用 `/simple` 的上游 index

客户端地址始终保持 `/repository/<name>/simple`。`Remote Index Path` 只控制 kkRepo 回源时在
Remote URL 后追加的路径：

- PyPI 和常规 PEP 503 index 保持默认值 `/simple`。
- 当 Remote URL 本身就是 index 根路径时留空。例如代理 PyTorch CPU index 时，Remote URL
  填写 `https://download.pytorch.org/whl/cpu/`，Remote Index Path 留空。
- 也支持 `/api/simple` 之类的自定义路径，并始终在 Remote URL 下解析。

该设置与 Nexus PyPI proxy 的 `pypi.indexPath` 行为一致。升级后，没有保存该设置的旧仓库
会继续使用 `/simple`，行为不变。

## 配置 pip

在 `pip.conf` 中使用 group 的 simple-index URL：

```ini
[global]
index-url = https://nexus.example.com/repository/pypi-group/simple
```

应配置可信 CA，而不是依赖 `trusted-host`。私有仓库凭据通过环境变量、keyring 或其他
secret store 提供，不要提交到项目配置。

使用下列命令验证读取：

```bash
python -m pip install --index-url \
  https://nexus.example.com/repository/pypi-group/simple demo-package
```

## 构建与发布

在 `~/.pypirc` 中声明 hosted 地址并保护该文件，然后使用 Twine 发布：

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

CI 中优先使用 Twine 环境变量提供凭据；`.pypirc` 被共享或由源码配置管理时，不要写入真实
secret。

## 仓库行为

- Hosted 上传会校验 distribution metadata，并记录 package、version、filename 和 hash。
- Proxy 会获取配置的上游 index 和 distribution file，保持 package name 规范化，并把下载
  链接重写为 kkRepo 地址。
- Group 按成员顺序合并 simple-index entry，并从绑定的同一来源提供 hosted 或缓存文件。
- Browse 与 Search 使用解析后的 package metadata，不依赖抓取生成的 HTML。

## 运维与排障

只向 hosted 发布；proxy 与 group 只读。Simple index 返回 `404` 通常表示全部成员都没有
该 project。强制清理缓存前，先检查规范化名称中 `-`、`_`、`.` 的差异、客户端凭据和
proxy negative-cache TTL。

## 相关文档

- [PyPI 客户端配置示例](../client-recipes.md#pypi)
- [兼容性矩阵](../compatibility-matrix.md#仓库格式矩阵)
- [Python Packaging User Guide：`.pypirc`](https://packaging.python.org/en/latest/specifications/pypirc/)
- [Python Packaging User Guide：搭建 index](https://packaging.python.org/en/latest/guides/hosting-your-own-index/)
