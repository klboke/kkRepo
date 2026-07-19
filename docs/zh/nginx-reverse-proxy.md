# Nginx 反向代理配置注意事项

本文说明一种常见部署形态：Nginx 在外层终止 HTTPS，再通过 HTTP 转发到 kkRepo 应用端口。

## 为什么需要 Forwarded Header

kkRepo 会基于当前请求生成部分客户端可见的绝对 URL。例如 npm package metadata 会重写 `dist.tarball`，Composer p2 metadata 会重写 `dist.url`；Docker 会生成 token challenge 的 `realm`、`service` 以及上传响应的 `Location`。这些值都必须使用客户端可访问的公网 scheme、host 和 port。

如果 Nginx 对外接收的是 `https://nexus.example.com`，但转发到 kkRepo 时变成后端 HTTP 请求，kkRepo 必须收到可信的 forwarded header。否则生成的 URL 可能会使用后端看到的请求信息，例如 `http://...` 或后端端口。

`KKREPO_EXTERNAL_BASE_URL` 用于生成 OIDC redirect URL。它不能替代 repository metadata URL 所需的 forwarded header 配置。

## Nginx 示例

```nginx
upstream kkrepo_app {
    server 127.0.0.1:8080;
}

server {
    listen 443 ssl http2;
    server_name nexus.example.com;

    ssl_certificate /etc/nginx/tls/nexus.example.com.crt;
    ssl_certificate_key /etc/nginx/tls/nexus.example.com.key;

    client_max_body_size 0;
    proxy_connect_timeout 30s;
    proxy_send_timeout 600s;
    proxy_read_timeout 600s;

    location / {
        proxy_pass http://kkrepo_app;
        proxy_http_version 1.1;

        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-Host $host;
        proxy_set_header X-Forwarded-Proto https;
        proxy_set_header X-Forwarded-Port 443;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header Authorization $http_authorization;
    }
}
```

需要保留原始 path。除非 kkRepo 侧也一致配置了 application context path，否则不要改写 `/repository/<repo>/...`、`/admin/`、`/browse/`、`/service/rest/` 或 Docker `/v2/...` 路径。

管理端口通常是 `8081`，应保持内网可见。公网反向代理应转发到应用端口，通常是 `8080`。

## kkRepo 配置

把 `KKREPO_TRUSTED_PROXIES` 设置成 kkRepo 看到的 Nginx 来源地址。该值必须匹配代理连接上的 `request.getRemoteAddr()`。请使用精确 IP，或能解析到实际代理地址的名称；这个配置不会匹配 CIDR 网段或客户端 IP 网段。

示例：

```bash
# Nginx 和 kkRepo 在同一台机器。
KKREPO_TRUSTED_PROXIES=127.0.0.1

# 如果后端连接使用 IPv6 loopback。
KKREPO_TRUSTED_PROXIES=::1

# 两个代理实例转发到 kkRepo。
KKREPO_TRUSTED_PROXIES=10.0.12.34,10.0.12.35
```

HTTPS 部署建议同时启用浏览器 secure cookie 和 HSTS：

```bash
KKREPO_SESSION_COOKIE_SECURE=true
KKREPO_CSRF_COOKIE_SECURE=true
KKREPO_HSTS_ENABLED=true
```

如果启用了 OIDC，并且身份提供方需要稳定的公网 callback URL，可设置：

```bash
KKREPO_EXTERNAL_BASE_URL=https://nexus.example.com
```

## 验证方式

kkRepo 通过 Nginx 启动后，用真实客户端可见 URL 验证：

```bash
npm view --registry=https://nexus.example.com/repository/npm-group/ is-number dist.tarball
```

返回的 tarball URL 应该以如下地址开头：

```text
https://nexus.example.com/repository/npm-group/
```

Composer 还应验证 p2 metadata 中的 `dist.url`：

```bash
curl -u alice:"$KKREPO_PASSWORD" \
  https://nexus.example.com/repository/composer-group/p2/psr/log.json
```

其中 `dist.url` 应以 `https://nexus.example.com/repository/composer-group/` 开头。

Docker 应先验证 registry challenge：

```bash
curl -sS -D - -o /dev/null https://nexus.example.com/v2/
```

`WWW-Authenticate` 中的 `realm` 应以 `https://nexus.example.com/` 开头，`service` 不应包含后端 host 或 `:8080`。随后使用相同公网地址执行 `docker login` 和 `docker push`；上传响应的 `Location` 也应保持公网 HTTPS 地址。

如果返回值以 `http://` 开头、包含 `:8080`，或使用了内部 host，检查：

- Nginx 是否发送了 `X-Forwarded-Proto`、`X-Forwarded-Host` 和 `X-Forwarded-Port`。
- `KKREPO_TRUSTED_PROXIES` 是否匹配 kkRepo 看到的代理来源地址。
- 公网请求是否转发到了应用端口，而不是管理端口。
- Nginx 是否保留了原始 repository path。
