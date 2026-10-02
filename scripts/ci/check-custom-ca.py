#!/usr/bin/env python3
"""Exercise a packaged JVM/Native runtime against HTTPS S3 storage and a private-CA upstream.

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
S3_IMAGE = "rustfs/rustfs:1.0.0"


def run(*args):
    try:
        return subprocess.check_output(args, text=True, stderr=subprocess.STDOUT).strip()
    except subprocess.CalledProcessError as error:
        raise RuntimeError(f"{args[0]} failed: {error.output}") from error


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
    parser.add_argument("--minio-image", help="Use an available MinIO image instead of the default RustFS fixture")
    args = parser.parse_args()
    storage_label = "MinIO" if args.minio_image else "RustFS"
    prefix = "kkrepo-custom-ca-" + uuid.uuid4().hex[:10]
    containers = []
    with tempfile.TemporaryDirectory(prefix=prefix) as temporary:
        work = Path(temporary)
        work.chmod(0o755)
        ca, key, cert = certificate(work, "s3")
        upstream_ca, upstream_key, upstream_cert = certificate(work, "upstream")
        bundle = work / "bundle.pem"
        bundle.write_bytes(ca.read_bytes() + upstream_ca.read_bytes())
        s3_certs = work / "s3"
        s3_certs.mkdir()
        (s3_certs / "public.crt").write_bytes(cert.read_bytes())
        (s3_certs / "private.key").write_bytes(key.read_bytes())
        (s3_certs / "rustfs_cert.pem").write_bytes(cert.read_bytes())
        (s3_certs / "rustfs_key.pem").write_bytes(key.read_bytes())
        upstream = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Upstream)
        tls = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        tls.minimum_version = ssl.TLSVersion.TLSv1_2
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
            # Wait for the final TCP listener, not the temporary initdb Unix-socket server.
            for _ in range(180):
                ready = subprocess.run(
                    ["docker", "exec", postgres, "pg_isready", "-h", "127.0.0.1", "-U", "kkrepo", "-d", "kkrepo"],
                    stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
                if ready.returncode == 0:
                    break
                time.sleep(0.5)
            else:
                raise RuntimeError("PostgreSQL readiness timeout")
            storage = prefix + "-s3"
            containers.append(storage)
            command = ["docker", "run", "-d", "--name", storage, "-p", "127.0.0.1::9000",
                       "-v", str(s3_certs) + ":/certs:ro"]
            if args.minio_image:
                command += ["-e", "MINIO_ROOT_USER=probe-access", "-e", "MINIO_ROOT_PASSWORD=probe-secret",
                            args.minio_image, "server", "/data", "--certs-dir", "/certs"]
                health_path = "/minio/health/ready"
            else:
                command += ["-e", "RUSTFS_ACCESS_KEY=probe-access", "-e", "RUSTFS_SECRET_KEY=probe-secret",
                            "-e", "RUSTFS_VOLUMES=/data", "-e", "RUSTFS_ADDRESS=0.0.0.0:9000",
                            "-e", "RUSTFS_CONSOLE_ENABLE=false", "-e", "RUSTFS_TLS_PATH=/certs", S3_IMAGE]
                health_path = "/health/ready"
            run(*command)
            storage_port = run("docker", "port", storage, "9000").rsplit(":", 1)[1]
            endpoint = "https://localhost:" + storage_port
            wait_ready(endpoint + health_path, tls=ssl.create_default_context(cafile=str(ca)))
            run("curl", "--fail", "--silent", "--show-error", "--cacert", str(ca),
                "--aws-sigv4", "aws:amz:us-east-1:s3", "--user", "probe-access:probe-secret",
                "-X", "PUT", endpoint + "/ca-bucket")
            port = free_port()
            base = "http://127.0.0.1:" + str(port)
            environment = {
                "SERVER_PORT": str(port), "KKREPO_MANAGEMENT_PORT": "0",
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
                                "name": "ca-s3", "type": "s3", "engine": "aws-s3",
                                "endpoint": endpoint, "region": "us-east-1", "bucket": "ca-bucket",
                                "accessKey": "probe-access", "secretKey": "probe-secret",
                                "pathStyleAccess": True})
                            store_id = store["id"]
                            api(base, "/internal/repositories", "POST", {
                                "name": "ca-hosted", "recipe": "raw-hosted", "online": True,
                                "blobStoreName": "ca-s3", "hosted": {"writePolicy": "ALLOW"}})
                            api(base, "/internal/repositories", "POST", {
                                "name": "ca-proxy", "recipe": "raw-proxy", "online": True,
                                "blobStoreName": "ca-s3", "proxy": {
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
                              f"for {storage_label} and upstream HTTPS", flush=True)
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
