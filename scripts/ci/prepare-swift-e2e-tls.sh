#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TLS_DIR="${1:-}"

if [[ -z "$TLS_DIR" ]]; then
  echo "usage: $0 TLS_DIR" >&2
  exit 2
fi

"$SCRIPT_DIR/generate-swift-e2e-tls.sh" "$TLS_DIR" localhost
cat > "$TLS_DIR/nginx.conf" <<'EOF'
events {}
http {
  client_max_body_size 100m;
  proxy_request_buffering off;

  server {
    listen 18443 ssl;
    server_name localhost;
    ssl_certificate /etc/nginx/tls/server.crt;
    ssl_certificate_key /etc/nginx/tls/server.key;
    ssl_protocols TLSv1.2 TLSv1.3;
    location / {
      proxy_pass http://127.0.0.1:18090;
      proxy_http_version 1.1;
      proxy_set_header Host $http_host;
      proxy_set_header X-Forwarded-Host $http_host;
      proxy_set_header X-Forwarded-Proto https;
      proxy_set_header X-Forwarded-Port $server_port;
    }
  }

  server {
    listen 18444 ssl;
    server_name localhost;
    ssl_certificate /etc/nginx/tls/server.crt;
    ssl_certificate_key /etc/nginx/tls/server.key;
    ssl_protocols TLSv1.2 TLSv1.3;
    location / {
      proxy_pass http://127.0.0.1:18094;
      proxy_http_version 1.1;
      proxy_set_header Host $http_host;
      proxy_set_header X-Forwarded-Host $http_host;
      proxy_set_header X-Forwarded-Proto https;
      proxy_set_header X-Forwarded-Port $server_port;
    }
  }
}
EOF
