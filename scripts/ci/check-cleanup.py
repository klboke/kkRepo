#!/usr/bin/env python3
"""Exercise Quartz and SHA256-only APT retention in the packaged JVM/Native image."""
import argparse
import base64
import hashlib
import http.cookiejar
import http.server
import json
import pathlib
import runpy
import socket
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
import uuid


PASSWORD = "cleanup-runtime-probe-password"


def run(*args):
    return subprocess.check_output(args, text=True, stderr=subprocess.STDOUT).strip()


def wait_for(description, predicate, timeout=90):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        result = predicate()
        if result:
            return result
        time.sleep(0.5)
    raise RuntimeError("Timed out waiting for " + description)


def free_port():
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        return listener.getsockname()[1]


def fixtures():
    build = runpy.run_path(str(pathlib.Path(__file__).with_name("create-apt-e2e-fixture.py")))["build_package"]
    bodies, stanzas = {}, []
    for name, versions in (("cleanup-b", ("1.0",)), ("cleanup-a", ("1.0", "2.0", "3.0"))):
        for version in versions:
            data = build(name, version, "amd64", None, "cleanup probe")
            path = f"pool/main/c/{name}/{name}_{version}_amd64.deb"
            bodies[path] = data
            stanza = (f"Package: {name}\nVersion: {version}\nArchitecture: amd64\n"
                      f"Maintainer: Test <test@example.invalid>\nDescription: cleanup probe\n"
                      f"Filename: {path}\nSize: {len(data)}\nSHA256: {hashlib.sha256(data).hexdigest()}\n")
            # Exercise neither, either and both optional legacy hashes in one index.
            if version == "2.0" or name == "cleanup-b":
                stanza += f"MD5sum: {hashlib.md5(data).hexdigest()}\n"
            if version == "3.0" or name == "cleanup-b":
                stanza += f"SHA1: {hashlib.sha1(data).hexdigest()}\n"
            stanzas.append(stanza)
    index = "\n".join(stanzas).encode()
    bodies["dists/stable/main/binary-amd64/Packages"] = index
    bodies["dists/stable/Release"] = (
        "Suite: stable\nCodename: stable\nArchitectures: amd64\nComponents: main\n"
        f"SHA256:\n {hashlib.sha256(index).hexdigest()} {len(index)} main/binary-amd64/Packages\n"
    ).encode()
    return bodies


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--image", required=True)
    args = parser.parse_args()
    prefix = "kkrepo-cleanup-probe-" + uuid.uuid4().hex[:10]
    database, app = prefix + "-db", prefix + "-app"
    fixture_host = "host.docker.internal" if sys.platform == "darwin" else "127.0.0.1"
    bodies = fixtures()
    upstream_requests = {}

    class Upstream(http.server.BaseHTTPRequestHandler):
        def do_GET(self):
            upstream_requests[self.path] = upstream_requests.get(self.path, 0) + 1
            body = bodies.get(self.path.lstrip("/"))
            self.send_response(200 if body is not None else 404)
            self.send_header("Content-Length", str(len(body) if body else 0))
            self.end_headers()
            if body is not None:
                self.wfile.write(body)

        def log_message(self, *_):
            pass

    upstream = http.server.ThreadingHTTPServer(("0.0.0.0", 0), Upstream)
    threading.Thread(target=upstream.serve_forever, daemon=True).start()
    port, management = free_port(), free_port()
    base = f"http://127.0.0.1:{port}"
    authorization = "Basic " + base64.b64encode(f"admin:{PASSWORD}".encode()).decode()

    def api(method, path, payload=None):
        request = urllib.request.Request(base + path,
            None if payload is None else json.dumps(payload).encode(),
            {"Authorization": authorization, "Content-Type": "application/json"}, method=method)
        try:
            with urllib.request.urlopen(request, timeout=15) as response:
                raw = response.read()
                return json.loads(raw) if raw else None
        except urllib.error.HTTPError as error:
            raise RuntimeError(f"{method} {path}: {error.code} {error.read().decode()}") from error

    def sql(query):
        return run("docker", "exec", database, "psql", "-U", "kkrepo", "-d", "kkrepo", "-At", "-c", query)

    def ready():
        try:
            with urllib.request.urlopen(base + "/internal/security/bootstrap", timeout=3) as response:
                return json.load(response).get("required")
        except (OSError, urllib.error.URLError):
            return False

    try:
        run("docker", "run", "-d", "--name", database, "-p", "127.0.0.1::5432",
            "-e", "POSTGRES_DB=kkrepo", "-e", "POSTGRES_USER=kkrepo",
            "-e", "POSTGRES_PASSWORD=kkrepo", "postgres:17-alpine")
        db_port = run("docker", "port", database, "5432").rsplit(":", 1)[1]
        wait_for("PostgreSQL", lambda: subprocess.run(
            ["docker", "exec", database, "pg_isready", "-h", "127.0.0.1", "-U", "kkrepo"],
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode == 0)
        environment = {
            "SERVER_PORT": str(port), "KKREPO_MANAGEMENT_PORT": str(management),
            "KKREPO_DATABASE_TYPE": "postgresql",
            "SPRING_DATASOURCE_URL": f"jdbc:postgresql://{fixture_host}:{db_port}/kkrepo",
            "SPRING_DATASOURCE_USERNAME": "kkrepo", "SPRING_DATASOURCE_PASSWORD": "kkrepo",
            "KKREPO_CREDENTIAL_SECRET": "cleanup-probe-credential-secret-0123456789",
            "KKREPO_API_KEY_PAYLOAD_SECRET": "cleanup-probe-api-secret-0123456789012345",
            "KKREPO_FILE_BASE_DIR": "/tmp/cleanup-probe-blobs",
            "KKREPO_OUTBOUND_ALLOWED_HOSTS": fixture_host,
            "KKREPO_CLEANUP_WORKER_IDLE_MAX_DELAY": "500ms",
        }
        command = ["docker", "run", "-d", "--name", app, "--network", "host"]
        for key, value in environment.items():
            command += ["-e", key + "=" + value]
        run(*command, args.image)
        wait_for("application bootstrap", ready)
        client = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
        try:
            session = client.open(base + "/internal/security/session", timeout=10)
        except urllib.error.HTTPError as error:
            if error.code != 401:
                raise
            session = error
        with session:
            csrf = session.headers["X-Nexus-Plus-CSRF-Token"]
        with client.open(urllib.request.Request(base + "/internal/security/bootstrap/admin",
            json.dumps({"password": PASSWORD, "passwordConfirm": PASSWORD,
                        "anonymousAccessEnabled": False}).encode(),
            {"Content-Type": "application/json", "X-Nexus-Plus-CSRF-Token": csrf}), timeout=15):
            pass
        api("POST", "/internal/blob-stores", {"name": "default", "type": "file", "path": "/tmp/cleanup-probe-blobs"})
        repository = api("POST", "/internal/repositories", {
            "name": "cleanup-apt", "recipe": "apt-proxy", "online": True, "blobStoreName": "default",
            "proxy": {"remoteUrl": f"http://{fixture_host}:{upstream.server_port}", "autoBlock": False},
            "apt": {"distribution": "stable", "component": "main", "architectures": ["amd64"],
                    "flat": False, "metadataMode": "PASSTHROUGH"},
        })
        paths = ["dists/stable/Release", "dists/stable/main/binary-amd64/Packages"]
        paths += [path for path in bodies if path.endswith(".deb")]
        def fetch_all():
            for path in paths:
                with urllib.request.urlopen(urllib.request.Request(base + "/repository/cleanup-apt/" + path,
                    headers={"Authorization": authorization}), timeout=15) as response:
                    assert response.read() == bodies[path], path

        # Exercise the actual upgrade DDL on populated legacy data, then repair previously
        # unindexed cache hits without invalidating the cache or refetching package bytes.
        sql("ALTER TABLE apt_package_record ALTER COLUMN md5 SET NOT NULL, "
            "ALTER COLUMN sha1 SET NOT NULL")
        fetch_all()
        assert sql("SELECT count(*) FROM component") == "1"
        cached_requests = dict(upstream_requests)
        legacy_hashes = sql("SELECT md5 || ':' || sha1 || ':' || sha256 FROM apt_package_record")
        migration = pathlib.Path(__file__).resolve().parents[2] / (
            "persistence-postgresql/src/main/resources/db/migration/postgresql/"
            "V65__apt_optional_legacy_checksums.sql")
        sql(migration.read_text())
        assert sql("SELECT md5 || ':' || sha1 || ':' || sha256 FROM apt_package_record") == legacy_hashes
        fetch_all()
        assert upstream_requests == cached_requests, upstream_requests
        assert sql("SELECT count(*) FROM component WHERE repository_id = " + str(repository["id"])) == "4"
        command = {"name": "scheduled-apt-retain", "format": "apt", "criteria": {"retainCount": 1},
                   "repositoryIds": [repository["id"]], "scanLimitPerRepository": 10000,
                   "deleteLimitPerRepository": 1000,
                   "schedule": {"cronExpression": "0/2 * * * * ?", "timeZone": "UTC", "enabled": True}}
        policy = api("POST", "/internal/cleanup/policies", command)["policy"]
        assert policy["state"] == "PAUSED", policy
        command["revision"] = policy["revision"]
        policy_path = f"/internal/cleanup/policies/{policy['id']}"
        active = api("PUT", policy_path, command)["policy"]
        assert active["state"] == "ACTIVE", active
        # No manual execute call: this must pass through the real Quartz JobFactory.
        wait_for("scheduled APT deletion", lambda: sql(
            "SELECT count(*) FROM cleanup_run WHERE trigger_kind = 'SCHEDULED' "
            "AND state = 'SUCCEEDED' AND deleted_subjects = 2 AND failed_subjects = 0") == "1")
        remaining = sql("SELECT name || ':' || version FROM component ORDER BY name, version").splitlines()
        assert remaining == ["cleanup-a:3.0", "cleanup-b:1.0"], remaining
        assert api("GET", policy_path)["policy"]["state"] == "ACTIVE"
        assert "CleanupQuartzJob.executeInternal" not in run("docker", "logs", app)
        print("Packaged Quartz scheduled cleanup deleted two old APT versions with optional legacy hashes; "
              "each family retains its newest version and the policy stays ACTIVE", flush=True)
    except Exception:
        subprocess.run(["docker", "logs", app], check=False)
        raise
    finally:
        upstream.shutdown()
        upstream.server_close()
        for name in (app, database):
            subprocess.run(["docker", "rm", "-f", "-v", name], stdout=subprocess.DEVNULL,
                           stderr=subprocess.DEVNULL)


if __name__ == "__main__":
    main()
