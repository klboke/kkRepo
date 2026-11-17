#!/usr/bin/env python3
"""Verify inbound PEM TLS, replica reconciliation and OCI transfers in JVM/Native images."""
import argparse
import base64
import hashlib
import http.cookiejar
import json
import pathlib
import ssl
import subprocess
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

PASSWORD = "docker-tls-probe-password"
AUTH = "Basic " + base64.b64encode(f"admin:{PASSWORD}".encode()).decode()


def run(*args):
    try:
        return subprocess.check_output(args, text=True, stderr=subprocess.STDOUT).strip()
    except subprocess.CalledProcessError as error:
        raise RuntimeError(error.output) from None


def wait_for(description, predicate, timeout=120):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            result = predicate()
            if result:
                return result
        except (OSError, urllib.error.URLError):
            pass
        time.sleep(0.5)
    raise RuntimeError("Timed out waiting for " + description)


def request(url, method="GET", payload=None, context=None, headers=None):
    data = None if payload is None else payload if isinstance(payload, bytes) else json.dumps(payload).encode()
    req = urllib.request.Request(url, data, {"Authorization": AUTH, "Content-Type": "application/json", **(headers or {})}, method=method)
    try:
        with urllib.request.urlopen(req, context=context, timeout=15) as response:
            return response.status, response.headers, response.read()
    except urllib.error.HTTPError as error:
        return error.code, error.headers, error.read()


def api(base, path, method="GET", payload=None):
    status, _, data = request(base + path, method, payload)
    assert 200 <= status < 300, (path, status, data)
    return json.loads(data) if data else None


def exercise_registry(registry, pull_registry, context, authorization=AUTH):
    def probe_request(url, method="GET", payload=None, context=None, headers=None):
        return request(url, method, payload, context, {"Authorization": authorization, **(headers or {})})
    def blob(data):
        digest = "sha256:" + hashlib.sha256(data).hexdigest()
        status, headers, _ = probe_request(registry + "/v2/tls/probe/blobs/uploads/", "POST", b"", context)
        assert status == 202
        location = urllib.parse.urljoin(registry, headers["Location"])
        assert location.startswith("https://"), location
        status, _, result = probe_request(location + ("&" if "?" in location else "?") + "digest=" + digest,
            "PUT", data, context, {"Content-Type": "application/octet-stream"})
        assert status == 201, result
        assert probe_request(pull_registry + "/v2/tls/probe/blobs/" + digest, context=context)[2] == data
        return {"digest": digest, "size": len(data)}
    config = blob(b'{"architecture":"amd64","os":"linux","rootfs":{"type":"layers","diff_ids":[]}}')
    config["mediaType"] = "application/vnd.oci.image.config.v1+json"
    manifest = json.dumps({"schemaVersion": 2, "mediaType": "application/vnd.oci.image.manifest.v1+json",
                           "config": config, "layers": []}).encode()
    assert probe_request(registry + "/v2/tls/probe/manifests/latest", "PUT", manifest, context,
        {"Content-Type": "application/vnd.oci.image.manifest.v1+json"})[0] == 201
    assert probe_request(pull_registry + "/v2/tls/probe/manifests/latest", context=context)[2] == manifest
    return hashlib.sha256(manifest).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--image", required=True)
    parser.add_argument("--subnet", help="Optional isolated Docker network subnet for local hosts with exhausted pools")
    parser.add_argument("--jar", help="Run a locally packaged executable jar in a Java image")
    parser.add_argument("--nexus-url", help="Optional HTTPS URL of an isolated writable Nexus Docker connector")
    parser.add_argument("--nexus-ca", help="PEM CA for the Nexus reference")
    parser.add_argument("--nexus-username", default="admin")
    parser.add_argument("--nexus-password", default="admin123")
    args = parser.parse_args()
    prefix = "kkrepo-tls-" + uuid.uuid4().hex[:10]
    db, nodes, volume = prefix + "-db", [prefix + "-a", prefix + "-b"], prefix + "-blobs"
    try:
        with tempfile.TemporaryDirectory(prefix=prefix) as directory:
            root = pathlib.Path(directory)
            root.chmod(0o755)
            run("openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "2",
                "-keyout", str(root / "server.key"), "-out", str(root / "ca.crt"), "-subj", "/CN=localhost",
                "-addext", "subjectAltName=DNS:localhost,DNS:app-a,DNS:app-b,IP:127.0.0.1")
            (root / "server.key").chmod(0o644)  # Disposable test identity, readable by non-root image user.
            context = ssl.create_default_context(cafile=str(root / "ca.crt"))
            run("docker", "network", "create", *(["--subnet", args.subnet] if args.subnet else []), prefix)
            run("docker", "volume", "create", volume)
            run("docker", "run", "--rm", "-v", volume + ":/blobs", "alpine:3.22", "chmod", "777", "/blobs")
            run("docker", "run", "-d", "--name", db, "--network", prefix, "--network-alias", "db",
                "-e", "POSTGRES_DB=kkrepo", "-e", "POSTGRES_USER=kkrepo", "-e", "POSTGRES_PASSWORD=kkrepo", "postgres:17-alpine")
            wait_for("database", lambda: subprocess.run(["docker", "exec", db, "pg_isready", "-U", "kkrepo"],
                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode == 0)
            environment = {
                "SERVER_PORT": "8080", "KKREPO_MANAGEMENT_PORT": "8081",
                "KKREPO_DATABASE_TYPE": "postgresql", "SPRING_DATASOURCE_URL": "jdbc:postgresql://db:5432/kkrepo",
                "SPRING_DATASOURCE_USERNAME": "kkrepo", "SPRING_DATASOURCE_PASSWORD": "kkrepo",
                "KKREPO_CREDENTIAL_SECRET": "tls-probe-credential-secret-012345678901",
                "KKREPO_API_KEY_PAYLOAD_SECRET": "tls-probe-api-secret-012345678901234567",
                "KKREPO_FILE_BASE_DIR": "/blobs", "KKREPO_TLS_DEFAULT_BUNDLE": "registry",
                "SPRING_SSL_BUNDLE_PEM_REGISTRY_KEYSTORE_CERTIFICATE": "file:/tls/ca.crt",
                "SPRING_SSL_BUNDLE_PEM_REGISTRY_KEYSTORE_PRIVATE_KEY": "file:/tls/server.key",
                "KKREPO_DOCKER_CONNECTOR_REFRESH_INTERVAL_MS": "500",
            }
            bases, registries, http_registries = [], [], []
            for index, node in enumerate(nodes):
                command = ["docker", "run", "-d", "--name", node, "--network", prefix,
                    "--network-alias", "app-" + ("a" if index == 0 else "b"),
                    "-v", directory + ":/tls:ro", "-v", volume + ":/blobs"]
                for port in (8080, 5000, 5443):
                    command += ["-p", "127.0.0.1::" + str(port)]
                for key, value in environment.items():
                    command += ["-e", key + "=" + value]
                if args.jar:
                    command += ["-v", str(pathlib.Path(args.jar).resolve()) + ":/app.jar:ro", "--entrypoint", "java"]
                command += [args.image]
                if args.jar:
                    command += ["-jar", "/app.jar"]
                run(*command)
                port = lambda internal: run("docker", "port", node, str(internal)).rsplit(":", 1)[1]
                bases.append("http://127.0.0.1:" + port(8080))
                registries.append("https://localhost:" + port(5443))
                http_registries.append("http://localhost:" + port(5000))
                wait_for("application " + node, lambda: request(bases[-1] + "/internal/security/bootstrap")[0] == 200)
                if index == 0:
                    client = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
                    try:
                        session = client.open(bases[0] + "/internal/security/session")
                    except urllib.error.HTTPError as error:
                        session = error
                    with session:
                        csrf = session.headers["X-Nexus-Plus-CSRF-Token"]
                    with client.open(urllib.request.Request(bases[0] + "/internal/security/bootstrap/admin",
                        json.dumps({"password": PASSWORD, "passwordConfirm": PASSWORD, "anonymousAccessEnabled": False}).encode(),
                        {"Content-Type": "application/json", "X-Nexus-Plus-CSRF-Token": csrf})):
                        pass
            api(bases[0], "/internal/blob-stores", "POST", {"name": "default", "type": "file", "path": "/blobs"})
            settings = {"connectorEnabled": True, "connectorPort": 5000,
                        "httpsConnectorEnabled": True, "httpsConnectorPort": 5443}
            api(bases[0], "/internal/repositories", "POST", {"name": "tls-registry", "recipe": "docker-hosted",
                "online": True, "blobStoreName": "default", "hosted": {"writePolicy": "ALLOW"}, "docker": settings})
            for base, registry, plain in zip(bases, registries, http_registries):
                wait_for("both listeners on " + base, lambda: sum(item["active"] for item in
                    api(base, "/internal/docker/connectors")["connector"]["connectors"]) == 2)
                assert request(registry + "/v2/", context=context)[0] == 200
                assert request(plain + "/v2/")[0] == 200
                status, headers, _ = request(registry + "/v2/", context=context, headers={"Authorization": ""})
                assert status == 401
                challenge = headers["WWW-Authenticate"]
                assert 'realm="https://' in challenge, challenge
                try:
                    request(registry + "/v2/")
                    raise AssertionError("Untrusted server certificate was accepted")
                except urllib.error.URLError:
                    pass
            metadata = api(bases[0], "/internal/docker/tls-bundles")
            assert metadata["defaultBundle"] == "registry" and metadata["bundles"][0]["usable"]
            digest = exercise_registry(registries[0], registries[1], context)
            if args.nexus_url:
                nexus_context = ssl.create_default_context(cafile=args.nexus_ca)
                nexus_auth = "Basic " + base64.b64encode(f"{args.nexus_username}:{args.nexus_password}".encode()).decode()
                assert exercise_registry(args.nexus_url, args.nexus_url, nexus_context, nexus_auth) == digest
                print("Nexus HTTPS comparison passed: upload statuses, secure Location, bytes and manifest digest", flush=True)
            run("docker", "run", "--rm", "--network", prefix, "-v", str(root / "ca.crt") + ":/certs/ca.crt:ro",
                "quay.io/skopeo/stable:v1.21.0", "copy", "--src-cert-dir", "/certs", "--dest-cert-dir", "/certs",
                "--src-creds", "admin:" + PASSWORD, "--dest-creds", "admin:" + PASSWORD,
                "docker://app-a:5443/tls/probe:latest", "docker://app-b:5443/tls/probe:copied")
            # Updating through A must disable B's listener too, without refresh calls to B.
            api(bases[0], "/internal/repositories/tls-registry", "PUT", {"docker": {"httpsConnectorEnabled": False}})
            for base in bases:
                wait_for("HTTPS removal", lambda: all(item["scheme"] != "https" for item in
                    api(base, "/internal/docker/connectors")["connector"]["connectors"]))
            api(bases[0], "/internal/repositories/tls-registry", "DELETE")
            print("Docker TLS passed: trusted PEM handshake, secure realm/Location, independent HTTP/HTTPS, "
                  "replica convergence, cross-replica OCI push/pull and Skopeo copy", flush=True)
    except Exception:
        for node in nodes:
            subprocess.run(["docker", "logs", "--tail", "120", node], check=False)
        raise
    finally:
        for name in [*nodes, db]:
            subprocess.run(["docker", "rm", "-f", "-v", name], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        subprocess.run(["docker", "volume", "rm", volume], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        subprocess.run(["docker", "network", "rm", prefix], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


if __name__ == "__main__":
    main()
