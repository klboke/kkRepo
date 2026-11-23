# Nginx 反向代理配置注意事项

本文说明一种常见部署形态：Nginx 在外层终止 HTTPS，再通过 HTTP 转发到 kkRepo 应用端口。

## 为什么需要 Forwarded Header

kkRepo 会基于当前请求生成部分客户端可见的绝对 URL。例如 npm package metadata 会重写 `dist.tarball`，Composer p2 metadata 会重写 `dist.url`，Ansible Galaxy 会生成 task/artifact link；Docker 会生成 token challenge 的 `realm`、`service` 以及上传响应的 `Location`。这些值都必须使用客户端可访问的公网 scheme、host 和 port。

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

把 `KKREPO_TRUSTED_PROXIES` 设置成逗号分隔的代理精确 IP 地址或 IPv4/IPv6 CIDR 网段列表。配置匹配直接连接 kkRepo 的代理地址 `request.getRemoteAddr()`，不会通过 `X-Forwarded-For` 中的地址建立信任。现有的主机名配置仍然可用，在启动时解析为一个地址。

示例：

```bash
# Nginx 和 kkRepo 在同一台机器。
KKREPO_TRUSTED_PROXIES=127.0.0.1

# 如果后端连接使用 IPv6 loopback。
KKREPO_TRUSTED_PROXIES=::1

# 两个代理实例转发到 kkRepo。
KKREPO_TRUSTED_PROXIES=10.0.12.34,10.0.12.35

# 负载均衡节点在专用代理网段内轮换，可与精确 IP 混用。
KKREPO_TRUSTED_PROXIES=127.0.0.1,10.0.0.0/16,2001:db8:1234::/48
```

应选择仅供可信代理使用的最小网段，并限制对 kkRepo 后端端口的直接访问。配置网段内的所有地址都可以提供可信的 `X-Forwarded-*` 头，影响生成的 URL、审计客户端地址和限流客户端标识。列表为空时不信任任何代理；不在配置地址或网段内的请求会忽略这些头。避免使用 `0.0.0.0/0` 和 `::/0`，它们分别信任对应地址族的所有地址。

CIDR 前缀必须是十进制长度：IPv4 为 `0` 到 `32`，IPv6 为 `0` 到 `128`。主机位会被忽略，例如 `10.0.12.34/16` 与 `10.0.0.0/16` 匹配同一网段。CIDR 必须使用不含作用域的 IP 字面量，不接受主机名、zone ID 或 IPv4 映射形式的 IPv6 CIDR。对于 `::ffff:10.0.12.34` 这样的 IPv4 映射来源地址，请配置对应的 IPv4 网段（`10.0.0.0/16`）。无效 CIDR 会导致启动失败并报告配置错误。

所有 kkRepo 副本应使用相同配置，修改后重启各副本。地址和网段匹配使用不可变的启动配置，不需要共享运行时状态，也不会逐请求进行 DNS 查询。

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

Ansible Galaxy 应通过公网 group URL 检查 version response：

```bash
curl -u alice:"$KKREPO_PASSWORD" \
  https://nexus.example.com/repository/ansible-group/api/v3/collections/acme/tools/versions/1.0.0/
```

其中 `href` 和 `download_url` 必须使用公网 HTTPS host，并保持在 `/repository/ansible-group/` 下。发布还要求 Nginx 保留 `Authorization`、multipart body 和长时间 import-task polling；应让 `client_max_body_size`/timeout 与应用限制一致，但不能关闭 Ansible archive inspector。

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

## 下载卸载

kkRepo 完成认证、下载授权和扫描策略检查后，可以让 Nginx 直接发送已存储的文件。卸载按 **File Blob Store** 启用。仓库组以实际命中的成员仓库制品所在的存储为准。

### 配置 kkRepo 和只读挂载

1. 在每个 kkRepo 副本上设置 `kkrepo.download-offload.trusted-proxies`，然后重启对应副本。环境变量为 `KKREPO_DOWNLOAD_OFFLOAD_TRUSTED_PROXIES`。填写 kkRepo 实际看到的直连代理 IP 或 CIDR，以逗号分隔。Nginx 通过 IPv4 回环连接 kkRepo 时可填 `127.0.0.1/32`。容器或负载均衡部署要填后端连接的实际对端地址。此配置用于授权文件交接，`KKREPO_TRUSTED_PROXIES` 则单独控制转发头的信任。限制后端端口的访问范围，并为支持卸载的代理使用专用的对端地址。
2. 打开 **Admin → System → Download Offload**，在 **Proxy preset** 选择 **NGINX**，勾选所需的 File Blob Store。点击 **View deployment example** 查看各存储的数字 ID。以下用存储 ID `2` 举例。
3. 将每个选中存储的现有根目录只读挂载到参与卸载的每个 Nginx 副本。例如，存储 `2` 使用相对路径 `releases`，宿主机上的实际目录为 `/srv/kkrepo-data/blobs/releases`，则把该目录挂载到 Nginx 的 `/srv/kkrepo-blobs/2`。挂载内要有 kkRepo 读取的同一份 `repositories/` 目录，Nginx worker 需要目录遍历权限和文件读权限。多副本部署时，任何可能收到该存储交接指令的代理都必须能读取同一份文件。

### 配置 Nginx 虚拟主机

在 Nginx 的 `http` 块中加入以下 `map`。如果实例使用了自定义仓库 URL 前缀，把 `/repository/` 换成实际前缀。如果该虚拟主机也将 Docker/OCI 下载转发到 kkRepo，再加入 `~^/v2/ nginx;`。

```nginx
map $uri $kkrepo_offload_marker {
    default        "";
    ~^/repository/ nginx;
}
```

将下面两条指令加入转发到 kkRepo 的 `location`，与已有的 `proxy_set_header` 放在同一级。匹配的路径由 Nginx 覆盖客户端传入的卸载标记，其余路径会清除该头。共享同一可信后端对端地址的其他虚拟主机或转发区块，也要覆盖或显式清除这个头。

```nginx
proxy_set_header X-KkRepo-File-Offload $kkrepo_offload_marker;
proxy_cache off;
```

在同一个 `server` 块中，为每个选中的存储添加一个内部位置。用 **View deployment example** 生成的完整区块保留各仓库协议需要的响应头。下面以 Raw 仓库为例，演示路径映射和验证头。

```nginx
location ^~ /_kkrepo_file_offload/2/ {
    internal;
    alias /srv/kkrepo-blobs/2/;
    sendfile on;
    disable_symlinks on;
    etag off;
    if_modified_since off;
    max_ranges 0;
    add_header ETag $upstream_http_etag;
    add_header Last-Modified $upstream_http_last_modified;
    add_header X-KkRepo-File-Served $upstream_http_x_kkrepo_file_served;
}
```

Nginx 收到 **kkRepo response header** 中展示的 `X-Accel-Redirect: /_kkrepo_file_offload/<store-id>/<object-key>` 后，按内部 URI 找到对应 `alias` 下的文件。保留每个文件位置的 `internal` 和部署已有的安全响应头，私有下载继续禁用共享缓存。按 Nginx 默认的 `add_header` 继承规则，当前区块定义响应头后会替换继承的列表，需要时在内部位置补上原有的安全响应头。参见 Nginx 文档中的[代理请求头](https://nginx.org/en/docs/http/ngx_http_proxy_module.html#proxy_set_header)、[内部位置](https://nginx.org/en/docs/http/ngx_http_core_module.html#internal)和[响应头继承](https://nginx.org/en/docs/http/ngx_http_headers_module.html#add_header)。

在 Nginx 实例中检查并重载配置，然后在 kkRepo 勾选 **Enable download offload**，确认预设和存储后点击 **Save settings**。

```bash
nginx -t && nginx -s reload
```

### 验证和排查

通过同一个 Nginx 地址打开 kkRepo 管理页。点击 **Verify offload**，选择已保存的 **File Blob Store**，在 **Artifact path** 填入一个已存储、有下载权限且非空的小文件路径，例如 `/repository/raw-hosted/hello.txt`。点击 **Run verification**。浏览器会核对交接标记是否匹配，并检查文件是否完整接收。

| 弹窗结果 | 如何排查 |
| --- | --- |
| 绿色 · **Proxy delivery verified** | Nginx 已从所选存储发送完整文件。 |
| 黄色 · HTTP 404、401 或 403 | 核对仓库路径、制品是否存在、用户权限和下载策略。如果制品检查通过但下载返回 404，再检查 Nginx 的路径映射和挂载。 |
| 红色 · **Offload was not confirmed for this Blob Store** | 检查保存的预设是否为 **NGINX**、代理是否发送 `nginx`、直连对端是否受信任、文件是否在所选存储、以及 Nginx 是否保留 `X-KkRepo-File-Served`。此时常规下载仍可能成功。 |
| 红色 · 传输失败或文件不完整 | 查看 Nginx 访问日志和错误日志，检查收到交接指令的代理上的 `alias`、只读挂载和 worker 读权限。交接后的文件读取错误由代理返回。 |

保存的配置由各副本共享，在各副本检查配置版本时刷新。HEAD、Range 和条件请求、对象存储制品、尚未落盘的回源数据，以及没有匹配可信代理的请求，均走常规下载。需要停用卸载时，取消勾选 **Enable download offload** 并保存。
