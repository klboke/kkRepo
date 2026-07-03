# Nginx Reverse Proxy Notes

This note covers the common deployment shape where Nginx terminates HTTPS and forwards traffic to the kkRepo application port over HTTP.

## Why Forwarded Headers Matter

kkRepo generates some client-visible absolute URLs from the incoming request. For example, npm package metadata rewrites `dist.tarball` to the public repository URL returned to `npm view` and `npm install`.

If Nginx receives `https://nexus.example.com` but forwards to kkRepo as plain HTTP, kkRepo must receive trusted forwarded headers. Otherwise generated URLs may use the backend view of the request, such as `http://...` or a backend port.

`KKREPO_EXTERNAL_BASE_URL` is used for OIDC redirect URL generation. It does not replace forwarded-header configuration for repository metadata URLs.

## Nginx Example

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

Preserve the original path. Do not rewrite `/repository/<repo>/...`, `/admin/`, `/browse/`, `/service/rest/`, or Docker `/v2/...` paths unless the application context path is configured consistently on the kkRepo side.

Keep the management port, usually `8081`, private. The public reverse proxy should route to the application port, usually `8080`.

## kkRepo Settings

Set `KKREPO_TRUSTED_PROXIES` to the Nginx address as seen by kkRepo. The value must match `request.getRemoteAddr()` for the proxy connection. Use exact IP addresses or names that resolve to the actual proxy address; CIDR ranges and client IP ranges are not matched by this setting.

Examples:

```bash
# Nginx and kkRepo on the same host.
KKREPO_TRUSTED_PROXIES=127.0.0.1

# If the backend connection uses IPv6 loopback.
KKREPO_TRUSTED_PROXIES=::1

# Two proxy instances in front of kkRepo.
KKREPO_TRUSTED_PROXIES=10.0.12.34,10.0.12.35
```

For HTTPS deployments, also enable secure browser cookies and HSTS when appropriate:

```bash
KKREPO_SESSION_COOKIE_SECURE=true
KKREPO_CSRF_COOKIE_SECURE=true
KKREPO_HSTS_ENABLED=true
```

If OIDC is enabled and the provider needs a stable public callback URL, set:

```bash
KKREPO_EXTERNAL_BASE_URL=https://nexus.example.com
```

## Verification

After starting kkRepo behind Nginx, verify a real client-visible URL:

```bash
npm view --registry=https://nexus.example.com/repository/npm-group/ is-number dist.tarball
```

The returned tarball URL should start with:

```text
https://nexus.example.com/repository/npm-group/
```

If it starts with `http://`, contains `:8080`, or uses an internal host name, check:

- Nginx sends `X-Forwarded-Proto`, `X-Forwarded-Host`, and `X-Forwarded-Port`.
- `KKREPO_TRUSTED_PROXIES` matches the proxy address seen by kkRepo.
- The public request is routed to the application port, not the management port.
- Nginx preserves the original repository path.
