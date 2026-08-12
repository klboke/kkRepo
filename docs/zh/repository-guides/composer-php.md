# Composer / PHP 仓库使用指南

kkRepo 支持 Composer 2 `hosted`、`proxy` 和 `group` 仓库。Hosted 存储私有 package
archive，proxy 缓存其他 Composer repository，group 通过统一 `packages.json` endpoint 按顺序
解析 package。

## 创建仓库

| 用途 | Recipe | 推荐配置 |
| --- | --- | --- |
| 私有 package | `composer-hosted` | Blob store、write policy、strict validation |
| Packagist 缓存 | `composer-proxy` | Remote URL `https://repo.packagist.org/` 和缓存 TTL |
| 统一读取入口 | `composer-group` | Hosted 排在 proxy 前面 |

Composer repository 使用根 URL，客户端会在其下发现 `packages.json` 与 package-specific p2
metadata。

## 配置项目

在 `composer.json` 中把 group 声明为 canonical repository：

```json
{
  "repositories": [
    {"type": "composer", "url": "https://nexus.example.com/repository/composer-group", "canonical": true},
    {"packagist.org": false}
  ],
  "require": {
    "acme/demo": "^1.0"
  }
}
```

关闭默认 Packagist source 可以确保解析过程不会绕过 kkRepo。

## 认证与安装

使用 Composer `auth.json` 或 `COMPOSER_AUTH` 提供 HTTP Basic，凭据不得提交到源码：

```bash
export COMPOSER_AUTH='{"http-basic":{"nexus.example.com":{"username":"alice","password":"'"$KKREPO_PASSWORD"'"}}}'
composer install --prefer-dist --no-interaction
composer show acme/demo --locked
```

认证配置的 host key 必须与 repository URL 实际使用的 host 和 port 完全一致。

## 发布私有 Package

Composer 没有标准 repository publish 命令。通过 Admin UI 或 Nexus 兼容 Components API
上传包含有效 `composer.json` 的 zip/tar archive：

```bash
curl -u alice:"$KKREPO_PASSWORD" \
  -F "composer.asset=@acme-demo-1.0.0.zip;type=application/zip" \
  -F "composer.name=acme/demo" \
  -F "composer.version=1.0.0" \
  "https://nexus.example.com/service/rest/v1/components?repository=composer-hosted"
```

## 仓库行为

- Hosted 校验 archive metadata，并提供 `packages.json`、stable/dev p2 metadata 和 dist。
- Proxy 使用 conditional request 和 negative cache 缓存 metadata 与 distribution archive。
- Group 使用 canonical first-match 解析，并把 metadata 与 dist 绑定到同一成员。
- Browse、Search、Usage 和 HTML View 使用解析后的 package/version metadata。

## 运维与排障

只向 hosted 发布。排查客户端状态时运行 `composer diagnose` 和 `composer clear-cache`。
Package 意外从公共源解析，通常表示没有关闭 Packagist，或 repository priority/canonical 配置
与预期策略不一致。

## 相关文档

- [Composer / PHP 客户端配置示例](../client-recipes.md#composer--php)
- [兼容性矩阵](../compatibility-matrix.md#仓库格式矩阵)
- [Composer repository 文档](https://getcomposer.org/doc/05-repositories.md)
- [Composer 私有仓库认证](https://getcomposer.org/doc/articles/authentication-for-private-packages.md)
