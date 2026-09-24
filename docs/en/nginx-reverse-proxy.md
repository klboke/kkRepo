# Nginx Reverse Proxy Notes

This note covers the common deployment shape where Nginx terminates HTTPS and forwards traffic to the kkRepo application port over HTTP.

## Why Forwarded Headers Matter

kkRepo generates some client-visible absolute URLs from the incoming request. For example, npm package metadata rewrites `dist.tarball`, Composer p2 metadata rewrites `dist.url`, Ansible Galaxy renders task/artifact links, and Docker generates the token challenge `realm` and `service` plus upload response `Location` headers. These values must use the client-visible public scheme, host, and port.

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

Set `KKREPO_TRUSTED_PROXIES` to a comma-separated list of exact proxy IP addresses or IPv4/IPv6 CIDR ranges. Entries match the immediate proxy connection's `request.getRemoteAddr()`, not addresses supplied in `X-Forwarded-For`. Existing hostname entries remain supported and resolve to one address at startup.

Examples:

```bash
# Nginx and kkRepo on the same host.
KKREPO_TRUSTED_PROXIES=127.0.0.1

# If the backend connection uses IPv6 loopback.
KKREPO_TRUSTED_PROXIES=::1

# Two proxy instances in front of kkRepo.
KKREPO_TRUSTED_PROXIES=10.0.12.34,10.0.12.35

# Rotating load-balancer nodes in dedicated proxy subnets, mixed with an exact IP.
KKREPO_TRUSTED_PROXIES=127.0.0.1,10.0.0.0/16,2001:db8:1234::/48
```

Use the narrowest subnets dedicated to trusted proxies, and restrict direct access to kkRepo's backend port. Every address in a configured range can supply trusted `X-Forwarded-*` headers, affecting generated URLs, audit client addresses, and rate-limit client identities. An empty list trusts no proxy; requests from outside the configured addresses/ranges ignore these headers. Avoid `0.0.0.0/0` and `::/0`, which trust every address in their respective address family.

CIDR prefixes must be decimal lengths from `0` to `32` for IPv4 or `0` to `128` for IPv6. Host bits are ignored (for example, `10.0.12.34/16` matches `10.0.0.0/16`). Use literal, unscoped addresses in CIDR entries; hostnames, zone IDs, and IPv4-mapped IPv6 CIDRs are rejected. For IPv4-mapped peers such as `::ffff:10.0.12.34`, configure the corresponding IPv4 range (`10.0.0.0/16`). Invalid CIDR entries prevent startup with a configuration error.

Apply the same configuration to every kkRepo replica and restart each replica after changes. Address/range matching uses immutable startup configuration and requires no shared runtime state or per-request DNS lookup.

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

For Composer, also inspect `dist.url` in p2 metadata:

```bash
curl -u alice:"$KKREPO_PASSWORD" \
  https://nexus.example.com/repository/composer-group/p2/psr/log.json
```

The `dist.url` value should start with `https://nexus.example.com/repository/composer-group/`.

For Ansible Galaxy, inspect a version response through the public group URL:

```bash
curl -u alice:"$KKREPO_PASSWORD" \
  https://nexus.example.com/repository/ansible-group/api/v3/collections/acme/tools/versions/1.0.0/
```

Its `href` and `download_url` must use the public HTTPS host and remain under `/repository/ansible-group/`. Publishing also requires Nginx to preserve `Authorization`, multipart bodies, and long-running import-task polling; align `client_max_body_size` and timeouts with the application limits without disabling the Ansible archive inspector.

For Docker, first inspect the registry challenge:

```bash
curl -sS -D - -o /dev/null https://nexus.example.com/v2/
```

The `realm` in `WWW-Authenticate` should start with `https://nexus.example.com/`, and `service` must not contain a backend host or `:8080`. Then run `docker login` and `docker push` against the same public address; upload response `Location` headers should also remain on the public HTTPS address.

If it starts with `http://`, contains `:8080`, or uses an internal host name, check:

- Nginx sends `X-Forwarded-Proto`, `X-Forwarded-Host`, and `X-Forwarded-Port`.
- `KKREPO_TRUSTED_PROXIES` matches the proxy address seen by kkRepo.
- The public request is routed to the application port, not the management port.
- Nginx preserves the original repository path.
