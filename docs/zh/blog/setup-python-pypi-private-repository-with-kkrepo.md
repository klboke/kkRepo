# 使用 kkRepo 搭建 Python 的 PyPI 私服

## 前言

在 Python 项目中，PyPI 私服通常承担两个角色：一是托管公司内部的 Python 包，二是代理和缓存 PyPI 官方仓库的第三方依赖。这样既能让 CI/CD 发布内部包有统一入口，也能减少公网网络波动对 `pip install` 的影响。

kkRepo 是一个兼容 Nexus 客户端访问习惯的自托管制品仓库，支持 Maven、npm、PyPI、Go、Helm、Cargo/Rust、Dart/Pub、Docker/OCI 等多种制品格式。对于 PyPI 场景，它支持 hosted、proxy 和 group 仓库，并保留常见的 `/repository/<repo>/...` URL 结构，方便从 Nexus 迁移或替换。

- https://github.com/klboke/kkrepo
- https://gitee.com/kailing/kkRepo

## 一、快速启动 kkRepo

本地体验可以直接使用官方 quickstart 脚本，它会拉起 kkRepo 和 MySQL：

```bash
curl -fsSL https://raw.githubusercontent.com/klboke/kkrepo/main/scripts/quickstart.sh | bash
```

启动完成后访问：

```text
管理控制台：http://127.0.0.1:19090/admin/
用户侧浏览器：http://127.0.0.1:19090/browse/
健康检查：http://127.0.0.1:19091/actuator/health
```

首次进入管理控制台时，需要创建初始 `Local/admin` 管理员密码。

本地 quickstart 默认使用 File blob storage，适合试用和开发验证。生产环境建议使用独立 MySQL，并将 blob 存储切换为 OSS/S3。

## 二、创建 PyPI 仓库

进入 `/admin/` 后，建议按 PyPI 常见用法创建三类仓库：

```text
pypi-hosted    hosted，用于发布公司内部 Python 包
pypi-proxy     proxy，用于代理 PyPI 官方仓库
pypi-group     group，用于 pip 安装依赖的统一入口
```

创建 `pypi-proxy` proxy 仓库时，上游地址可以填写 PyPI 官方 simple index 地址：

```text
https://pypi.org/simple/
```

如果公司已有内网 PyPI 镜像，也可以把 proxy 的上游地址替换成内部镜像地址。

创建 `pypi-group` group 仓库时，把下面几个成员仓库加入 group：

```text
pypi-hosted
pypi-proxy
```

这样客户端只需要从 `pypi-group` 一个地址安装依赖：公司内部发布到私服的包会从 `pypi-hosted` 命中，第三方开源依赖会通过 `pypi-proxy` 回源并缓存。发布时仍然写入 `pypi-hosted`。

## 三、配置 pip 安装依赖

编辑本机或 CI 环境中的 `pip.conf`。

Linux 和 macOS 常见路径：

```text
~/.pip/pip.conf
```

Windows 常见路径：

```text
%APPDATA%\pip\pip.ini
```

配置内容如下：

```ini
[global]
index-url = http://127.0.0.1:19090/repository/pypi-group/simple
trusted-host = 127.0.0.1
```

生产环境中建议使用 HTTPS，例如：

```ini
[global]
index-url = https://repo.example.com/repository/pypi-group/simple
trusted-host = repo.example.com
```

配置完成后，普通安装命令会自动走 kkRepo：

```bash
pip install requests
```

也可以临时指定 group 仓库地址：

```bash
pip install --index-url http://127.0.0.1:19090/repository/pypi-group/simple demo-package
```

## 四、配置 Python 包发布

Python 包发布通常使用 `build` 生成分发包，再用 `twine` 上传到 hosted 仓库。

先安装发布工具：

```bash
python -m pip install --upgrade build twine
```

在项目根目录准备 `pyproject.toml`。下面是一个最小示例：

```toml
[build-system]
requires = ["setuptools>=68", "wheel"]
build-backend = "setuptools.build_meta"

[project]
name = "demo-package"
version = "1.0.0"
description = "Demo package published to kkRepo"
requires-python = ">=3.8"
```

然后配置 `~/.pypirc`：

```ini
[distutils]
index-servers =
    kkrepo

[kkrepo]
repository = http://127.0.0.1:19090/repository/pypi-hosted/
username = alice
password = ${KKREPO_PASSWORD}
```

设置密码或 token 后构建并上传：

```bash
export KKREPO_PASSWORD='your-password-or-token'
python -m build
twine upload -r kkrepo dist/*
```

如果不想把账号写入 `~/.pypirc`，也可以通过环境变量传给 twine：

```bash
export TWINE_USERNAME=alice
export TWINE_PASSWORD='your-password-or-token'
export TWINE_REPOSITORY_URL=http://127.0.0.1:19090/repository/pypi-hosted/
python -m build
twine upload dist/*
```

## 五、验证安装私服包

上传完成后，可以通过 `pypi-group` 安装刚发布的包：

```bash
pip install --index-url http://127.0.0.1:19090/repository/pypi-group/simple demo-package
```

如果同一个环境里已经安装过旧版本，可以加上 `--upgrade`：

```bash
pip install --upgrade --index-url http://127.0.0.1:19090/repository/pypi-group/simple demo-package
```

也可以打开用户侧浏览器查看仓库内容：

```text
http://127.0.0.1:19090/browse/
```

在浏览器中可以查看仓库、包、版本、文件、checksum 和更新时间等信息。

## 六、生产环境建议

本地 quickstart 可以帮助我们快速跑通流程，但生产环境还需要注意这些点：

1. 使用独立 MySQL 8.0 存储元数据、用户、权限、token 和运行状态。
2. 使用 OSS/S3 作为 blob store，不建议把 Python 包长期放在单机本地磁盘。
3. 开启 HTTPS，避免 pip 和 twine 的密码或 token 明文传输。
4. 使用用户 token 或 CI token，避免把管理员密码写入流水线。
5. 将 `KKREPO_CREDENTIAL_SECRET` 和 `KKREPO_API_KEY_PAYLOAD_SECRET` 换成稳定的强随机字符串。
6. 对外提供统一域名，例如 `https://repo.example.com`，方便后续迁移和扩容。

kkRepo 的一个重要设计点是面向多副本部署：session、权限、token、迁移状态等共享状态存储在 MySQL 中，进程内缓存只作为可重建的本地热缓存。这对生产环境的滚动升级和横向扩容比较友好。

## 七、总结

使用 kkRepo 搭建 Python 的 PyPI 私服，核心流程可以概括为：

```text
启动 kkRepo
创建 blob store
创建 pypi-hosted / pypi-proxy / pypi-group
把 hosted 和 proxy 仓库加入 pypi-group
配置 pip.conf 使用 pypi-group 安装依赖
配置 ~/.pypirc 或 twine 环境变量
执行 python -m build 和 twine upload
```

这样，开发者和 CI 只需要使用 `pypi-group` 一个安装入口，就可以同时拉取公司内部 Python 包和 PyPI 官方第三方包。对于希望使用 MySQL、OSS/S3，并且需要更适合多副本部署的制品仓库场景，kkRepo 是一个值得尝试的选择。
