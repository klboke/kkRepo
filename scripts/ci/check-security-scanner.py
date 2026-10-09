#!/usr/bin/env python3
"""Verify scanner JSON binding and the runtime toggle in a packaged JVM/Native image.

Uses a disposable PostgreSQL database and a local scanner HTTP fixture; no vulnerability
database download is required. Docker host networking is required, as in the other runtime probes.
"""
import argparse
import datetime
import http.server
import json
import socket
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
import uuid


CREDENTIAL = "scanner-runtime-probe-credential"
DIGEST = "a" * 64
DATABASE_TIME = datetime.datetime.now(datetime.timezone.utc).isoformat(timespec="seconds")


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


def health(port):
    try:
        with urllib.request.urlopen(f"http://127.0.0.1:{port}/actuator/health", timeout=3) as response:
            return json.load(response)
    except urllib.error.HTTPError as error:
        return json.load(error)
    except (OSError, urllib.error.URLError):
        return {}


class Scanner(http.server.BaseHTTPRequestHandler):
    requests = []
    failing = True

    def do_GET(self):
        type(self).requests.append(self.path)
        if self.headers.get("X-KKRepo-Scanner-Credential") != CREDENTIAL:
            self.reply(401, {"code": "SCANNER_UNAUTHORIZED", "retryable": False})
        elif type(self).failing:
            self.reply(503, {"code": "SCANNER_PROBE_UNAVAILABLE", "message": "Probe failure",
                             "retryable": True})
        elif self.path == "/v1/capabilities":
            self.reply(200, {
                "apiVersion": "v1", "adapterName": "runtime-probe", "adapterVersion": "1",
                "operations": ["CATALOG", "MATCH"], "targetClassifications": ["ARCHIVE"],
                "maxInputBytes": 1048576, "maxOutputBytes": 1048576, "capabilityDigest": DIGEST,
            })
        elif self.path == "/v1/readiness":
            self.reply(200, {
                "ready": True, "status": "READY", "engineName": "grype", "engineVersion": "1",
                "vulnerabilityDatabaseRevision": "probe-revision",
                "vulnerabilityDatabaseUpdatedAt": DATABASE_TIME, "observedAt": DATABASE_TIME,
                "details": {"probe": True},
            })
        else:
            self.reply(404, {})

    def reply(self, status, value):
        body = json.dumps(value).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *_):
        pass


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--image", required=True)
    args = parser.parse_args()
    prefix = "kkrepo-scanner-probe-" + uuid.uuid4().hex[:10]
    database = prefix + "-db"
    containers = []
    # OrbStack forwards host.docker.internal to macOS; Linux host networking shares loopback.
    fixture_host = "host.docker.internal" if sys.platform == "darwin" else "127.0.0.1"
    scanner = http.server.ThreadingHTTPServer(
        ("0.0.0.0" if sys.platform == "darwin" else "127.0.0.1", 0), Scanner)
    threading.Thread(target=scanner.serve_forever, daemon=True).start()

    def sql(query):
        return run("docker", "exec", database, "psql", "-U", "kkrepo", "-d", "kkrepo",
                   "-At", "-c", query)

    try:
        containers.append(database)
        run("docker", "run", "-d", "--name", database, "-p", "127.0.0.1::5432",
            "-e", "POSTGRES_DB=kkrepo", "-e", "POSTGRES_USER=kkrepo",
            "-e", "POSTGRES_PASSWORD=kkrepo", "postgres:17-alpine")
        db_port = run("docker", "port", database, "5432").rsplit(":", 1)[1]
        wait_for("PostgreSQL", lambda: subprocess.run(
            ["docker", "exec", database, "pg_isready", "-h", "127.0.0.1", "-U", "kkrepo"],
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode == 0)
        for enabled in (False, True):
            port, management = free_port(), free_port()
            name = prefix + ("-enabled" if enabled else "-disabled")
            containers.append(name)
            environment = {
                "SERVER_PORT": str(port), "KKREPO_MANAGEMENT_PORT": str(management),
                "MANAGEMENT_ENDPOINT_HEALTH_SHOW_DETAILS": "always",
                "KKREPO_DATABASE_TYPE": "postgresql",
                "SPRING_DATASOURCE_URL": f"jdbc:postgresql://127.0.0.1:{db_port}/kkrepo",
                "SPRING_DATASOURCE_USERNAME": "kkrepo", "SPRING_DATASOURCE_PASSWORD": "kkrepo",
                "KKREPO_CREDENTIAL_SECRET": "scanner-probe-credential-secret-0123456789",
                "KKREPO_API_KEY_PAYLOAD_SECRET": "scanner-probe-api-secret-0123456789012345",
                "KKREPO_FILE_BASE_DIR": "/tmp/scanner-probe-blobs",
                "KKREPO_SECURITY_SCANNING_ENABLED": str(enabled).lower(),
                "KKREPO_SECURITY_SCANNING_SERVICE_CREDENTIAL": CREDENTIAL,
                "KKREPO_SECURITY_SCANNING_ADAPTER_BASE_URL": f"http://{fixture_host}:{scanner.server_port}",
                "KKREPO_SECURITY_SCANNING_SNAPSHOT_WATCH_INITIAL_DELAY": "1s",
                "KKREPO_SECURITY_SCANNING_SNAPSHOT_WATCH_DELAY": "1s",
            }
            command = ["docker", "run", "-d", "--name", name, "--network", "host"]
            for key, value in environment.items():
                command += ["-e", key + "=" + value]
            run(*command, args.image)
            try:
                wait_for("application health", lambda: health(management).get("components"))
                if not enabled:
                    time.sleep(4)  # Cross several scheduled polling boundaries.
                    assert not Scanner.requests, Scanner.requests
                    assert sql("SELECT count(*) FROM security_scanner_snapshot") == "0"
                    assert health(management)["components"]["securityScanner"]["details"]["enabled"] is False
                    print("Disabled packaged scanner remains idle", flush=True)
                    continue
                wait_for("decoded scanner error", lambda: sql(
                    "SELECT details_json->>'reasonCode' FROM security_scanner_snapshot "
                    "ORDER BY observed_at DESC, id DESC LIMIT 1") == "SCANNER_PROBE_UNAVAILABLE")
                Scanner.failing = False
                wait_for("scanner recovery to UP", lambda: health(management).get("components", {})
                         .get("securityScanner", {}).get("status") == "UP")
                snapshot = json.loads(sql(
                    "SELECT row_to_json(s) FROM security_scanner_snapshot s "
                    "WHERE ready ORDER BY observed_at DESC, id DESC LIMIT 1"))
                assert snapshot["adapter_name"] == "runtime-probe", snapshot
                assert snapshot["vulnerability_database_revision"] == "probe-revision", snapshot
                assert snapshot["details_json"]["operations"] == ["CATALOG", "MATCH"], snapshot
                assert snapshot["details_json"]["probe"] is True, snapshot
                assert "/v1/readiness" in Scanner.requests, Scanner.requests
                print("Packaged scanner decodes errors, capabilities and readiness, then recovers to UP",
                      flush=True)
            except Exception:
                print(run("docker", "logs", name), flush=True)
                raise
            finally:
                run("docker", "rm", "-f", name)
    finally:
        scanner.shutdown()
        scanner.server_close()
        for name in reversed(containers):
            subprocess.run(["docker", "rm", "-f", "-v", name], stdout=subprocess.DEVNULL,
                           stderr=subprocess.DEVNULL)


if __name__ == "__main__":
    main()
