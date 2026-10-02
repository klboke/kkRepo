#!/usr/bin/env python3
"""Exercise a packaged JVM/Native runtime against HTTPS MinIO and a private-CA upstream.

Requires Docker, OpenSSL and curl. --image uses host networking on Linux CI runners.
All containers, certificates and data belong to this probe and are removed on exit.
"""
import argparse
import base64
import http.server
import json
import os
from pathlib import Path
import socket
import ssl
import subprocess
import tempfile
import threading
import time
import urllib.error
import urllib.request
import uuid

CONTENT = b"kkrepo custom CA runtime fixture\n"
PASSWORD = "CustomCaProbe-password-123!"
MINIO_IMAGE = "quay.io/minio/minio:RELEASE.2025-04-22T22-12-26Z"


def run(*args):
    return subprocess.check_output(args, text=True, stderr=subprocess.STDOUT).strip()


def certificate(directory, name):
    ca, key, cert = (directory / (name + suffix) for suffix in ("-ca.pem", ".key", ".crt"))
    commands = [
        ["req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "2", "-sha256",
         "-subj", "/CN=" + name + " CA", "-addext", "basicConstraints=critical,CA:TRUE",
         "-keyout", str(directory / (name + "-ca.key")), "-out", str(ca)],
        ["req", "-new", "-newkey", "rsa:2048", "-nodes", "-subj", "/CN=localhost",
         "-keyout", str(key), "-out", str(directory / (name + ".csr"))],
    ]
    for command in commands:
        run("openssl", *command)
    extensions = directory / (name + ".ext")
    extensions.write_text("basicConstraints=critical,CA:FALSE\nsubjectAltName=DNS:localhost\n"
                          "extendedKeyUsage=serverAuth\n")
    run("openssl", "x509", "-req", "-days", "2", "-sha256", "-in", str(directory / (name + ".csr")),
        "-CA", str(ca), "-CAkey", str(directory / (name + "-ca.key")), "-CAcreateserial",
        "-extfile", str(extensions), "-out", str(cert))
    return ca, key, cert


def request(url, method="GET", body=None, authenticated=False, tls=None):
    headers = {}
    if isinstance(body, dict):
        body = json.dumps(body).encode()
        headers["Content-Type"] = "application/json"
    elif body is not None:
        headers["Content-Type"] = "application/octet-stream"
    if authenticated:
        headers["Authorization"] = "Basic " + base64.b64encode(("admin:" + PASSWORD).encode()).decode()
    req = urllib.request.Request(url, data=body, method=method, headers=headers)
    try:
        with urllib.request.urlopen(req, context=tls, timeout=10) as response:
            return response.status, response.read()
    except urllib.error.HTTPError as error:
        return error.code, error.read()


def api(base, path, method="GET", body=None):
    status, result = request(base + path, method, body, authenticated=True)
    if status not in (200, 201, 204):
        raise RuntimeError(f"{method} {path}: HTTP {status}: {result[:1000]!r}")
    return json.loads(result) if result else None


def wait_ready(url, process=None, tls=None):
    deadline = time.monotonic() + 180
    while time.monotonic() < deadline:
        if process is not None and process.poll() is not None:
            raise RuntimeError("Packaged runtime exited before readiness")
        try:
            if request(url, tls=tls)[0] == 200:
                return
        except (OSError, urllib.error.URLError):
            pass
        time.sleep(0.5)
    raise RuntimeError("Readiness timeout: " + url)


def free_port():
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        return listener.getsockname()[1]


class Upstream(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        self.send_response(200)
        self.send_header("Content-Length", str(len(CONTENT)))
        self.send_header("Content-Type", "application/octet-stream")
        self.end_headers()
        self.wfile.write(CONTENT)

    def log_message(self, *_):
        pass


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    target = parser.add_mutually_exclusive_group(required=True)
    target.add_argument("--jar")
    target.add_argument("--image")
    args = parser.parse_args()
    prefix = "kkrepo-custom-ca-" + uuid.uuid4().hex[:10]
    containers = []
    with tempfile.TemporaryDirectory(prefix=prefix) as temporary:
        work = Path(temporary)
        work.chmod(0o755)
        ca, key, cert = certificate(work, "minio")
        upstream_ca, upstream_key, upstream_cert = certificate(work, "upstream")
        bundle = work / "bundle.pem"
        bundle.write_bytes(ca.read_bytes() + upstream_ca.read_bytes())
        minio_certs = work / "minio"
        minio_certs.mkdir()
        (minio_certs / "public.crt").write_bytes(cert.read_bytes())
        (minio_certs / "private.key").write_bytes(key.read_bytes())
        upstream = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Upstream)
        tls = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        tls.load_cert_chain(upstream_cert, upstream_key)
        upstream.socket = tls.wrap_socket(upstream.socket, server_side=True)
        threading.Thread(target=upstream.serve_forever, daemon=True).start()
        try:
            postgres = prefix + "-db"
            containers.append(postgres)
            run("docker", "run", "-d", "--name", postgres, "-p", "127.0.0.1::5432",
                "-e", "POSTGRES_DB=kkrepo", "-e", "POSTGRES_USER=kkrepo",
                "-e", "POSTGRES_PASSWORD=kkrepo", "postgres:17-alpine")
            db_port = run("docker", "port", postgres, "5432").rsplit(":", 1)[1]
            minio = prefix + "-minio"
            containers.append(minio)
            run("docker", "run", "-d", "--name", minio, "-p", "127.0.0.1::9000",
                "-v", str(minio_certs) + ":/certs:ro", "-e", "MINIO_ROOT_USER=probe-access",
                "-e", "MINIO_ROOT_PASSWORD=probe-secret", MINIO_IMAGE,
                "server", "/data", "--certs-dir", "/certs")
            minio_port = run("docker", "port", minio, "9000").rsplit(":", 1)[1]
            endpoint = "https://localhost:" + minio_port
            wait_ready(endpoint + "/minio/health/ready", tls=ssl.create_default_context(cafile=str(ca)))
            run("curl", "--fail", "--silent", "--show-error", "--cacert", str(ca),
                "--aws-sigv4", "aws:amz:us-east-1:s3", "--user", "probe-access:probe-secret",
                "-X", "PUT", endpoint + "/ca-bucket")
            port = free_port()
            base = "http://127.0.0.1:" + str(port)
            environment = {
                "SERVER_PORT": str(port), "KKREPO_MANAGEMENT_PORT": str(free_port()),
                "KKREPO_DATABASE_TYPE": "postgresql",
                "SPRING_DATASOURCE_URL": f"jdbc:postgresql://127.0.0.1:{db_port}/kkrepo",
                "SPRING_DATASOURCE_USERNAME": "kkrepo", "SPRING_DATASOURCE_PASSWORD": "kkrepo",
                "KKREPO_CREDENTIAL_SECRET": "custom-ca-probe-credential-secret-1234567890",
                "KKREPO_API_KEY_PAYLOAD_SECRET": "custom-ca-probe-api-key-secret-1234567890",
                "KKREPO_OUTBOUND_ALLOW_PRIVATE_ADDRESSES": "true",
                "KKREPO_STORAGE_S3_ENDPOINT": endpoint, "KKREPO_STORAGE_S3_BUCKET": "ca-bucket",
                "KKREPO_STORAGE_S3_REGION": "us-east-1",
                "KKREPO_STORAGE_S3_ACCESS_KEY": "probe-access", "KKREPO_STORAGE_S3_SECRET_KEY": "probe-secret",
            }
            for trusted in (True, False):
                environment["KKREPO_TLS_CA_CERTIFICATES"] = (
                    ("/custom-ca/bundle.pem" if args.image else str(bundle)) if trusted else "")
                name = prefix + ("-trusted" if trusted else "-default")
                log = work / (name + ".log")
                if args.image:
                    containers.append(name)
                    command = ["docker", "run", "--rm", "--name", name, "--network", "host",
                               "-v", str(work) + ":/custom-ca:ro"]
                    for variable, value in environment.items():
                        command += ["-e", variable + "=" + value]
                    command += [args.image]
                    process_environment = os.environ.copy()
                else:
                    command = ["java", "-jar", str(Path(args.jar).resolve())]
                    process_environment = {**os.environ, **environment}
                with log.open("w") as output:
                    process = subprocess.Popen(command, stdout=output, stderr=subprocess.STDOUT,
                                               env=process_environment, cwd=work)
                    try:
                        wait_ready(base + "/internal/security/bootstrap", process)
                        if trusted:
                            api(base, "/internal/security/bootstrap/admin", "POST", {
                                "password": PASSWORD, "passwordConfirm": PASSWORD,
                                "anonymousAccessEnabled": False})
                            store = api(base, "/internal/blob-stores", "POST", {
                                "name": "ca-minio", "type": "s3", "engine": "aws-s3",
                                "endpoint": endpoint, "region": "us-east-1", "bucket": "ca-bucket",
                                "accessKey": "probe-access", "secretKey": "probe-secret",
                                "pathStyleAccess": True})
                            store_id = store["id"]
                            api(base, "/internal/repositories", "POST", {
                                "name": "ca-hosted", "recipe": "raw-hosted", "online": True,
                                "blobStoreName": "ca-minio", "hosted": {"writePolicy": "ALLOW"}})
                            api(base, "/internal/repositories", "POST", {
                                "name": "ca-proxy", "recipe": "raw-proxy", "online": True,
                                "blobStoreName": "ca-minio", "proxy": {
                                    "remoteUrl": "https://localhost:" + str(upstream.server_port),
                                    "contentMaxAgeMinutes": 1440, "metadataMaxAgeMinutes": 1440,
                                    "autoBlock": False}})
                            status, _ = request(base + "/repository/ca-hosted/artifact.bin", "PUT",
                                                CONTENT, authenticated=True)
                            assert status == 201, status
                            downloaded = request(base + "/repository/ca-hosted/artifact.bin", authenticated=True)
                            assert downloaded == (200, CONTENT), downloaded
                        probe = api(base, f"/internal/blob-stores/{store_id}/check", "POST")
                        assert probe["ok"] is trusted, probe
                        # Different paths prevent the successful run's proxy cache masking TLS rejection.
                        status, body = request(base + f"/repository/ca-proxy/{trusted}.bin", authenticated=True)
                        if trusted:
                            assert (status, body) == (200, CONTENT), (status, body)
                        else:
                            assert status >= 400, (status, body)
                        print(f"Packaged runtime: custom CA {'accepted' if trusted else 'absent and rejected'} "
                              "for MinIO and upstream HTTPS", flush=True)
                    except Exception:
                        print(log.read_text()[-15000:])
                        raise
                    finally:
                        if args.image:
                            subprocess.run(["docker", "stop", "-t", "3", name], stdout=subprocess.DEVNULL,
                                           stderr=subprocess.DEVNULL)
                        process.terminate()
                        try:
                            process.wait(timeout=15)
                        except subprocess.TimeoutExpired:
                            process.kill()
                            process.wait()
        finally:
            upstream.shutdown()
            upstream.server_close()
            for name in reversed(containers):
                subprocess.run(["docker", "rm", "-fv", name], stdout=subprocess.DEVNULL,
                               stderr=subprocess.DEVNULL)


if __name__ == "__main__":
    main()
