#!/usr/bin/env bash
set -euo pipefail

OUTPUT_DIR="${1:-}"
SERVER_NAME="${2:-localhost}"
KEYSTORE_PASSWORD="${SWIFT_E2E_TLS_KEYSTORE_PASSWORD:-changeit}"

if [[ -z "$OUTPUT_DIR" ]]; then
  echo "usage: $0 OUTPUT_DIR [SERVER_NAME]" >&2
  exit 2
fi
if ! command -v openssl >/dev/null 2>&1; then
  echo "openssl is required to generate the Swift E2E TLS fixture" >&2
  exit 2
fi

mkdir -p "$OUTPUT_DIR"
umask 077

cat >"$OUTPUT_DIR/ca.cnf" <<EOF
[req]
prompt = no
distinguished_name = distinguished_name
x509_extensions = v3_ca

[distinguished_name]
CN = kkrepo Swift E2E Root CA

[v3_ca]
basicConstraints = critical, CA:true
keyUsage = critical, keyCertSign, cRLSign
subjectKeyIdentifier = hash
authorityKeyIdentifier = keyid:always,issuer
EOF

cat >"$OUTPUT_DIR/server.cnf" <<EOF
[req]
prompt = no
distinguished_name = distinguished_name
req_extensions = request_extensions

[distinguished_name]
CN = $SERVER_NAME

[request_extensions]
subjectAltName = @subject_alt_names

[server_extensions]
basicConstraints = critical, CA:false
keyUsage = critical, digitalSignature, keyEncipherment
extendedKeyUsage = serverAuth
subjectAltName = @subject_alt_names
subjectKeyIdentifier = hash
authorityKeyIdentifier = keyid,issuer

[subject_alt_names]
DNS.1 = $SERVER_NAME
IP.1 = 127.0.0.1
EOF

openssl req -x509 -newkey rsa:2048 -nodes -sha256 -days 2 \
  -config "$OUTPUT_DIR/ca.cnf" \
  -keyout "$OUTPUT_DIR/ca.key" \
  -out "$OUTPUT_DIR/ca.crt"
openssl req -newkey rsa:2048 -nodes -sha256 \
  -config "$OUTPUT_DIR/server.cnf" \
  -keyout "$OUTPUT_DIR/server.key" \
  -out "$OUTPUT_DIR/server.csr"
openssl x509 -req -sha256 -days 2 \
  -in "$OUTPUT_DIR/server.csr" \
  -CA "$OUTPUT_DIR/ca.crt" \
  -CAkey "$OUTPUT_DIR/ca.key" \
  -CAcreateserial \
  -extfile "$OUTPUT_DIR/server.cnf" \
  -extensions server_extensions \
  -out "$OUTPUT_DIR/server.crt"
openssl pkcs12 -export \
  -name kkrepo-swift-e2e \
  -inkey "$OUTPUT_DIR/server.key" \
  -in "$OUTPUT_DIR/server.crt" \
  -certfile "$OUTPUT_DIR/ca.crt" \
  -passout "pass:$KEYSTORE_PASSWORD" \
  -out "$OUTPUT_DIR/server.p12"

if [[ -r /etc/ssl/certs/ca-certificates.crt ]]; then
  cp /etc/ssl/certs/ca-certificates.crt "$OUTPUT_DIR/ca-bundle.pem"
  printf '\n' >>"$OUTPUT_DIR/ca-bundle.pem"
  cat "$OUTPUT_DIR/ca.crt" >>"$OUTPUT_DIR/ca-bundle.pem"
else
  cp "$OUTPUT_DIR/ca.crt" "$OUTPUT_DIR/ca-bundle.pem"
fi

chmod 0600 "$OUTPUT_DIR/ca.key" "$OUTPUT_DIR/server.key" "$OUTPUT_DIR/server.p12"
chmod 0644 "$OUTPUT_DIR/ca.crt" "$OUTPUT_DIR/server.crt" "$OUTPUT_DIR/ca-bundle.pem"
