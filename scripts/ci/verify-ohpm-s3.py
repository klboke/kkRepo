#!/usr/bin/env python3
"""Exercise OHPM publication rollback against disposable, real RustFS S3 storage."""

import argparse
import base64
import datetime
import gzip
import hashlib
import hmac
import http.client
import http.server
import io
import json
import os
import pathlib
import secrets
import subprocess
import tarfile
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
import xml.etree.ElementTree as ET
import zipfile


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def request(method, url, body=None, headers=None):
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    outgoing = urllib.request.Request(url, data=body, headers=headers or {}, method=method)
    try:
        response = opener.open(outgoing, timeout=120)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        return response.status, response.read()


def docker(*arguments, environment=None):
    result = subprocess.run(["docker", *arguments], env=environment, capture_output=True,
                            text=True, timeout=90, check=False)
    require(result.returncode == 0, "Docker operation failed: " + result.stderr.strip())
    return result.stdout.strip()


class S3:
    def __init__(self, endpoint, access, secret, bucket):
        self.endpoint = endpoint
        self.access = access
        self.secret = secret
        self.bucket = bucket

    def call(self, method, key=None, query=""):
        path = "/" + self.bucket
        if key is not None:
            path += "/" + urllib.parse.quote(key, safe="/")
        now = datetime.datetime.now(datetime.timezone.utc)
        stamp = now.strftime("%Y%m%dT%H%M%SZ")
        day = stamp[:8]
        payload = hashlib.sha256(b"").hexdigest()
        host = urllib.parse.urlsplit(self.endpoint).netloc
        headers = {"host": host, "x-amz-content-sha256": payload, "x-amz-date": stamp}
        signed = ";".join(sorted(headers))
        canonical = "\n".join([method, path, query,
                               "".join(f"{name}:{headers[name]}\n" for name in sorted(headers)),
                               signed, payload])
        scope = f"{day}/us-east-1/s3/aws4_request"
        signing = "\n".join(["AWS4-HMAC-SHA256", stamp, scope,
                            hashlib.sha256(canonical.encode()).hexdigest()])
        material = ("AWS4" + self.secret).encode()
        for component in (day, "us-east-1", "s3", "aws4_request"):
            material = hmac.new(material, component.encode(), hashlib.sha256).digest()
        signature = hmac.new(material, signing.encode(), hashlib.sha256).hexdigest()
        headers["Authorization"] = (f"AWS4-HMAC-SHA256 Credential={self.access}/{scope}, "
                                    f"SignedHeaders={signed}, Signature={signature}")
        return request(method, self.endpoint + path + ("?" + query if query else ""),
                       b"" if method == "PUT" else None, headers)

    def objects(self):
        status, body = self.call("GET", query="list-type=2")
        require(status == 200, f"S3 ListObjectsV2 returned {status}")
        root = ET.fromstring(body)
        require(root.findtext("{*}IsTruncated") != "true", "Unexpectedly large dedicated bucket")
        return [{"key": entry.findtext("{*}Key"), "size": int(entry.findtext("{*}Size"))}
                for entry in root.findall("{*}Contents")]


class FaultGateway(http.server.ThreadingHTTPServer):
    daemon_threads = True

    def __init__(self, address, backend, hsp_digest):
        super().__init__(address, GatewayRequest)
        self.backend = backend
        self.hsp_digest = hsp_digest
        self.failing = False
        self.phase = "setup"
        self.events = []
        self.lock = threading.Lock()

    def phase_change(self, phase, failing=False):
        with self.lock:
            self.phase = phase
            self.failing = failing


class GatewayRequest(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *arguments):
        pass

    def body(self):
        if self.headers.get("Transfer-Encoding", "").lower() == "chunked":
            result = bytearray()
            while True:
                line = self.rfile.readline(8192)
                require(line.endswith(b"\r\n"), "Invalid chunk framing")
                result.extend(line)
                size = int(line.split(b";", 1)[0], 16)
                if not size:
                    while True:
                        trailer = self.rfile.readline(8192)
                        require(trailer.endswith(b"\r\n"), "Invalid chunk trailer")
                        result.extend(trailer)
                        if trailer == b"\r\n":
                            return bytes(result)
                result.extend(self.rfile.read(size + 2))
        return self.rfile.read(int(self.headers.get("Content-Length", "0")))

    def forward(self):
        self.connection.settimeout(120)
        with self.server.lock:
            phase = self.server.phase
            injected = (self.server.failing and self.command == "PUT"
                        and self.server.hsp_digest in self.path)
        status = 502
        response_body = b""
        response_headers = []
        try:
            payload = self.body()
            if injected:
                status = 503
                response_body = b"<Error><Code>SlowDown</Code><Message>Injected HSP PUT failure</Message></Error>"
                response_headers = [("Content-Type", "application/xml")]
            else:
                connection = http.client.HTTPConnection("127.0.0.1", self.server.backend, timeout=120)
                try:
                    connection.putrequest(self.command, self.path, skip_host=True, skip_accept_encoding=True)
                    for name, value in self.headers.items():
                        connection.putheader(name, value)
                    connection.endheaders(payload)
                    response = connection.getresponse()
                    status = response.status
                    response_headers = response.getheaders()
                    response_body = response.read()
                finally:
                    connection.close()
        except Exception as error:
            response_body = ("Gateway transport failure: " + type(error).__name__).encode()
        finally:
            with self.server.lock:
                self.server.events.append({"phase": phase, "method": self.command, "path": self.path,
                                           "host": self.headers.get("Host"), "status": status,
                                           "injected": injected})
        self.send_response(status)
        for name, value in response_headers:
            if name.lower() not in {"content-length", "transfer-encoding", "connection", "server", "date"}:
                self.send_header(name, value)
        response_length = next((value for name, value in response_headers
                                if name.lower() == "content-length"), "0")
        self.send_header("Content-Length", response_length if self.command == "HEAD" else str(len(response_body)))
        self.send_header("Connection", "close")
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(response_body)
        self.close_connection = True

    do_GET = do_HEAD = do_PUT = do_POST = do_DELETE = forward


def fixtures(package, run_id):
    manifest = {"name": package, "version": "1.0.0", "main": "Index.ets",
                "packageType": "InterfaceHar", "types": "Index.d.ets",
                "description": "Isolated S3 failure acceptance " + run_id,
                "license": "Apache-2.0", "dependencies": {}}
    tar_bytes = io.BytesIO()
    with tarfile.open(fileobj=tar_bytes, mode="w", format=tarfile.USTAR_FORMAT) as archive:
        for name, content in {
            "package/oh-package.json5": json.dumps(manifest).encode(),
            "package/Index.ets": b"export const value = 42;\n",
            "package/Index.d.ets": b"export declare const value: number;\n",
        }.items():
            entry = tarfile.TarInfo(name)
            entry.size = len(content)
            archive.addfile(entry, io.BytesIO(content))
    har = gzip.compress(tar_bytes.getvalue(), mtime=0)
    hsp_bytes = io.BytesIO()
    with zipfile.ZipFile(hsp_bytes, "w") as archive:
        archive.writestr(zipfile.ZipInfo("module.json"), json.dumps({
            "module": {"name": "fixture", "type": "shared"}, "acceptance": run_id}).encode())
    hsp = hsp_bytes.getvalue()
    dist = {"integrity": integrity(har), "integrity_hsp": integrity(hsp)}
    publication = {"name": package, "_id": package, "packageType": "InterfaceHar",
                   "hspType": "bundle_app", "dist-tags": {"latest": "1.0.0"},
                   "versions": {"1.0.0": {**manifest, "dist": dist}},
                   "_attachments": {
                       package + "-1.0.0.har": {"length": len(har), "data": base64.b64encode(har).decode()},
                       package + "-1.0.0.hsp": {"length": len(hsp), "data": base64.b64encode(hsp).decode()}}}
    return har, hsp, json.dumps(publication).encode()


def integrity(content):
    return "sha512-" + base64.b64encode(hashlib.sha512(content).digest()).decode()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default=os.environ.get("KKREPO_COMPAT_BASE_URL"))
    parser.add_argument("--image", default="docker.m.daocloud.io/rustfs/rustfs:1.0.0")
    parser.add_argument("--gateway-bind", default="127.0.0.1")
    parser.add_argument("--gateway-host", default="127.0.0.1")
    parser.add_argument("--gateway-port", type=int, default=0)
    parser.add_argument("--report", type=pathlib.Path)
    arguments = parser.parse_args()
    require(arguments.base_url, "Set KKREPO_COMPAT_BASE_URL or --base-url")
    password = os.environ.get("KKREPO_COMPAT_PASSWORD")
    require(password, "Set KKREPO_COMPAT_PASSWORD for the disposable candidate")
    run_id = "ohpm-s3-" + uuid.uuid4().hex[:12]
    report_path = arguments.report or pathlib.Path("artifacts/ohpm-s3") / (run_id + ".json")
    report_path.parent.mkdir(parents=True, exist_ok=True)
    require(not report_path.exists(), "Refusing to overwrite an existing evidence report")
    base = arguments.base_url.rstrip("/")
    admin = "Basic " + base64.b64encode(
        (os.environ.get("KKREPO_COMPAT_USERNAME", "admin") + ":" + password).encode()).decode()
    report = {"run": run_id, "candidate": base, "image": arguments.image,
              "started_at": datetime.datetime.now(datetime.timezone.utc).isoformat(),
              "passed": False, "cleanup": [], "residuals": []}
    access = "test" + secrets.token_hex(8)
    secret = secrets.token_hex(24)
    bucket = run_id
    repository = run_id
    package = "@kkrepo/" + run_id
    har, hsp, publication = fixtures(package, run_id)
    digests = {"har": hashlib.sha256(har).hexdigest(), "hsp": hashlib.sha256(hsp).hexdigest()}
    report["fixtures"] = {kind: {"sha256": digests[kind], "size": len(content)}
                          for kind, content in (("har", har), ("hsp", hsp))}
    gateway = None
    backend = None
    container_started = False
    store_id = None
    user_created = False
    repo_created = False
    token = None

    def api(method, path, value=None, expected=(200,)):
        body = None if value is None else json.dumps(value).encode()
        status, result = request(method, base + path, body,
                                 {"Authorization": admin, "Content-Type": "application/json"})
        require(status in expected, f"{method} {path} returned {status}")
        return json.loads(result) if result else None

    def protocol(method, url, body=None):
        return request(method, url, body, {"Authorization": token, "Content-Type": "application/json"})

    def cleanup_api(label, path, method="DELETE", body=None, credential=None):
        try:
            status, result = request(method, base + path, body,
                                     {"Authorization": credential or admin, "Content-Type": "application/json"})
            report["cleanup"].append({"resource": label, "path": path, "status": status})
            if status not in (200, 204, 404):
                report["residuals"].append({"resource": label, "path": path, "status": status})
        except Exception as error:
            report["residuals"].append({"resource": label, "path": path, "error": type(error).__name__})

    try:
        environment = {**os.environ, "RUSTFS_ACCESS_KEY": access, "RUSTFS_SECRET_KEY": secret}
        docker("run", "--detach", "--name", run_id, "--pull=never",
               "--publish", "127.0.0.1::9000", "--env", "RUSTFS_ACCESS_KEY",
               "--env", "RUSTFS_SECRET_KEY", "--env", "RUSTFS_CONSOLE_ENABLE=false",
               arguments.image, "/data", environment=environment)
        container_started = True
        backend_port = int(docker("port", run_id, "9000/tcp").rsplit(":", 1)[1])
        backend = S3(f"http://127.0.0.1:{backend_port}", access, secret, bucket)
        deadline = time.monotonic() + 90
        while True:
            try:
                status, unused = backend.call("PUT")
                if status == 200:
                    break
            except (OSError, urllib.error.URLError):
                pass
            require(time.monotonic() < deadline, "RustFS did not become ready within 90 seconds")
            time.sleep(1)
        gateway = FaultGateway((arguments.gateway_bind, arguments.gateway_port), backend_port, digests["hsp"])
        threading.Thread(target=gateway.serve_forever, daemon=True).start()
        endpoint = f"http://{arguments.gateway_host}:{gateway.server_port}"
        report.update({"bucket": bucket, "repository": repository, "user": run_id,
                       "gateway": endpoint, "rustfs": backend.endpoint,
                       "image_id": docker("inspect", "--format", "{{.Image}}", run_id)})
        store = api("POST", "/internal/blob-stores", {
            "name": run_id, "type": "s3", "engine": "aws-s3", "endpoint": endpoint,
            "region": "us-east-1", "bucket": bucket, "prefix": run_id,
            "accessKey": access, "secretKey": secret, "pathStyleAccess": True,
            "multipartThresholdBytes": 67108864})
        store_id = store["id"]
        report["blobstore_id"] = store_id
        require(store["type"] == "s3", "Candidate did not create an actual S3 blob store")
        api("POST", "/internal/repositories", {"name": repository, "recipe": "ohpm-hosted",
            "online": True, "blobStoreName": run_id, "strictContentTypeValidation": False,
            "hosted": {"writePolicy": "ALLOW_ONCE"}}, expected=(201,))
        repo_created = True
        api("POST", "/internal/security/users", {"source": "Local", "userId": run_id,
            "firstName": "S3", "lastName": "Acceptance", "password": secrets.token_urlsafe(32),
            "status": "ACTIVE", "roles": ["nx-admin"]})
        user_created = True
        issued = api("POST", "/internal/security/api-keys", {"domain": "GenericToken",
            "ownerSource": "Local", "ownerUserId": run_id, "displayName": run_id,
            "status": "ACTIVE", "scopes": []})
        token = issued["token"]
        require(bool(token), "Candidate did not issue a fixture token")
        package_url = base + "/repository/" + repository + "/" + package
        gateway.phase_change("failure", failing=True)
        status, unused = protocol("PUT", package_url, publication)
        report["failed_publish_status"] = status
        require(status == 500, f"Injected S3 failure returned {status}, expected OHPM server error 500")
        events = list(gateway.events)
        failed_hsp = [event for event in events if event["phase"] == "failure" and event["injected"]]
        accepted_har = [event for event in events if event["phase"] == "failure"
                        and event["method"] == "PUT" and digests["har"] in event["path"]
                        and 200 <= event["status"] < 300]
        require(accepted_har and failed_hsp, "Must observe real HAR persistence followed by injected HSP failure")
        require(events.index(accepted_har[0]) < events.index(failed_hsp[0]), "HSP failed before HAR persisted")
        report["blocked_hsp_attempts"] = len(failed_hsp)
        require(not any(event["phase"] == "failure" and event["method"] == "PUT"
                        and digests["hsp"] in event["path"] and not event["injected"]
                        for event in events), "An HSP retry escaped the fault gate")
        status, unused = protocol("GET", package_url)
        report["metadata_after_failure"] = status
        require(status == 404, "Failed publication exposed package metadata")
        report["downloads_after_failure"] = {}
        for extension in ("har", "hsp"):
            status, unused = protocol("GET", package_url + "/-/" + package + "-1.0.0." + extension)
            report["downloads_after_failure"][extension] = status
            require(status == 404, "Failed publication exposed a downloadable " + extension)
        report["objects_after_failure"] = backend.objects()
        require(not report["objects_after_failure"], "Failed publication left objects in the dedicated S3 bucket")
        print(f"{run_id}: failure HTTP {report['failed_publish_status']}, "
              f"{len(failed_hsp)} HSP attempts blocked, metadata 404", flush=True)
        gateway.phase_change("recovery")
        status, unused = protocol("PUT", package_url, publication)
        report["retry_publish_status"] = status
        require(status == 200, f"Publication retry after recovery returned {status}")
        status, content = protocol("GET", package_url)
        require(status == 200, f"Recovered metadata returned {status}")
        metadata = json.loads(content)
        dist = metadata["versions"]["1.0.0"]["dist"]
        require(metadata["dist-tags"]["latest"] == "1.0.0", "Recovered latest tag is incorrect")
        for kind, expected, link, checksum in (
                ("har", har, "tarball", "integrity"), ("hsp", hsp, "resolved_hsp", "integrity_hsp")):
            require(urllib.parse.urlsplit(dist[link]).netloc == urllib.parse.urlsplit(base).netloc,
                    "Download URL left the candidate origin")
            status, actual = protocol("GET", dist[link])
            require(status == 200 and actual == expected, "Recovered " + kind + " download differs")
            require(dist[checksum] == integrity(expected), "Metadata integrity differs for " + kind)
            require(any(event["phase"] == "recovery" and event["method"] == "GET"
                        and digests[kind] in event["path"] and event["status"] == 200
                        for event in gateway.events), "Download did not read " + kind + " from real S3")
        report["objects_after_recovery"] = backend.objects()
        for kind, expected in (("har", har), ("hsp", hsp)):
            stored = [item for item in report["objects_after_recovery"] if digests[kind] in item["key"]]
            require(stored, "No recovered " + kind + " in RustFS")
            for item in stored:
                status, actual = backend.call("GET", item["key"])
                require(status == 200 and actual == expected, "Direct S3 bytes differ for " + kind)
        report["download_bytes_match"] = True
        expected_host = urllib.parse.urlsplit(endpoint).netloc
        require(all(event["host"] == expected_host for event in gateway.events),
                "Candidate S3 requests did not preserve the configured endpoint Host")
        report["original_host_preserved"] = True
        report["passed"] = True
        print(f"{run_id}: recovery HTTP 200; HAR/HSP candidate and direct S3 bytes match", flush=True)
    except Exception as error:
        report["error"] = str(error).replace(password, "<redacted>").replace(secret, "<redacted>")
        print(f"{run_id}: FAILED: {report['error']}", flush=True)
    finally:
        if gateway:
            gateway.phase_change("cleanup")
        if repo_created and token:
            cleanup_api("publication", "/repository/" + repository + "/" + package,
                        body=b'{"version":""}', credential=token)
        if repo_created:
            cleanup_api("repository", "/internal/repositories/" + repository)
        if store_id is not None:
            cleanup_api("blobstore", "/internal/blob-stores/" + str(store_id))
        if user_created:
            cleanup_api("user-and-token", "/internal/security/users/Local/" + run_id)
        if backend:
            try:
                report["objects_after_api_cleanup"] = backend.objects()
            except Exception as error:
                report["residuals"].append({"resource": "bucket-inventory", "error": type(error).__name__})
        if gateway:
            gateway.shutdown()
            gateway.server_close()
            report["gateway_events"] = gateway.events
        if container_started:
            try:
                docker("rm", "--force", "--volumes", run_id)
                report["cleanup"].append({"resource": "dedicated-rustfs-container-and-volumes", "removed": True})
            except Exception as error:
                report["residuals"].append({"resource": "rustfs-container", "name": run_id,
                                           "error": str(error)})
        report["cleanup_boundary"] = (
            "Only this run's resources are touched. API cleanup may retain registered blobs, deletion markers "
            "and the blobstore until normal GC; no shared database mutation or global GC is attempted. "
            "The dedicated RustFS container/anonymous volumes are removed, including orphan objects. "
            "Remaining candidate records require disposable database teardown; this is not proof of GC completion.")
        report["finished_at"] = datetime.datetime.now(datetime.timezone.utc).isoformat()
        report_path.write_text(json.dumps(report, indent=2) + "\n")
        print(f"Evidence: {report_path.resolve()}; residual records: {len(report['residuals'])}", flush=True)
    return 0 if report["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
