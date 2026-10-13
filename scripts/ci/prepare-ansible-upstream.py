#!/usr/bin/env python3
"""Seed a real Nexus Galaxy hosted upstream for isolated compatibility runs."""

import base64
import gzip
import hashlib
import io
import ipaddress
import json
import os
import re
import tarfile
import time
from urllib import error, parse, request


def json_bytes(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":")).encode()


def collection_archive(namespace, name, version):
    for value in (namespace, name):
        if not re.fullmatch(r"[a-z][a-z0-9_]*", value):
            raise ValueError("invalid Ansible fixture namespace/name")
    if not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", version):
        raise ValueError("invalid Ansible fixture version")
    # Incompressible, deterministic content exceeds Nexus's 1 KiB minimum.
    files = {
        "README.md": f"# {namespace}.{name}\nControlled Galaxy upstream fixture.\n".encode(),
        "payload.bin": b"".join(hashlib.sha256(str(i).encode()).digest() for i in range(128)),
    }
    inventory = [{"name": ".", "ftype": "dir", "chksum_type": None,
                  "chksum_sha256": None, "format": 1}]
    inventory.extend({"name": path, "ftype": "file", "chksum_type": "sha256",
                      "chksum_sha256": hashlib.sha256(body).hexdigest(), "format": 1}
                     for path, body in files.items())
    files_json = json_bytes({"files": inventory, "format": 1})
    manifest = json_bytes({
        "collection_info": {"namespace": namespace, "name": name, "version": version,
                            "authors": ["kkRepo"], "readme": "README.md", "tags": [],
                            "description": "Controlled Galaxy upstream fixture",
                            "license": ["Apache-2.0"], "dependencies": {}},
        "file_manifest_file": {"name": "FILES.json", "ftype": "file", "format": 1,
                               "chksum_type": "sha256",
                               "chksum_sha256": hashlib.sha256(files_json).hexdigest()},
        "format": 1,
    })
    output = io.BytesIO()
    with gzip.GzipFile(fileobj=output, mode="wb", mtime=0) as gz:
        with tarfile.open(fileobj=gz, mode="w", format=tarfile.USTAR_FORMAT) as tar:
            for path, body in {"MANIFEST.json": manifest, "FILES.json": files_json, **files}.items():
                entry = tarfile.TarInfo(path)
                entry.size = len(body)
                entry.mode = 0o644
                tar.addfile(entry, io.BytesIO(body))
    return output.getvalue()


def main():
    base = os.environ["NEXUS_URL"].rstrip("/")
    auth = "Basic " + base64.b64encode(os.environ["NEXUS_AUTH"].encode()).decode()
    repository = os.environ["ANSIBLE_COMPAT_UPSTREAM_REPOSITORY"]
    remote = os.environ["ANSIBLE_COMPAT_UPSTREAM_URL"]
    namespace = os.environ["ANSIBLE_COMPAT_PROXY_NAMESPACE"]
    name = os.environ["ANSIBLE_COMPAT_PROXY_COLLECTION"]
    version = os.environ["ANSIBLE_COMPAT_PROXY_VERSION"]
    if not re.fullmatch(r"[a-zA-Z0-9_-]+", repository):
        raise ValueError("invalid controlled upstream repository")
    remote_uri = parse.urlsplit(remote)
    if remote_uri.path.rstrip("/") != "/repository/" + repository or not remote_uri.hostname:
        raise ValueError("controlled upstream URL must point to its Nexus hosted repository")
    archive = collection_archive(namespace, name, version)
    digest = hashlib.sha256(archive).hexdigest()

    def http(path, method="GET", body=None, content_type="application/json", allow_missing=False):
        data = json_bytes(body) if isinstance(body, dict) else body
        req = request.Request(base + path, method=method, data=data,
                              headers={"Authorization": auth, "Content-Type": content_type})
        try:
            with request.urlopen(req, timeout=30) as response:
                value = response.read()
                return json.loads(value) if value else None
        except error.HTTPError as exc:
            if allow_missing and exc.code == 404:
                return None
            raise RuntimeError(f"Nexus fixture request failed: {method} {path}: HTTP {exc.code}") from exc

    # Only the CI fixture hostname is allowlisted; keep SSRF protection enabled.
    ssrf = http("/service/rest/v1/security/ssrf-protection")
    try:
        ipaddress.ip_address(remote_uri.hostname)
        allowlist = ssrf.setdefault("allowedIPs", [])
    except ValueError:
        allowlist = ssrf.setdefault("allowedDomains", [])
    if remote_uri.hostname not in allowlist:
        allowlist.append(remote_uri.hostname)
        http("/service/rest/v1/security/ssrf-protection", "PUT", ssrf)

    repositories = http("/service/rest/v1/repositories")
    if not any(repo["name"] == repository for repo in repositories):
        http("/service/rest/v1/repositories/ansiblegalaxy/hosted", "POST", {
            "name": repository, "online": True,
            "storage": {"blobStoreName": "default", "strictContentTypeValidation": True,
                        "writePolicy": "ALLOW_ONCE"},
        })
    repo_path = "/repository/" + repository
    detail_path = f"{repo_path}/api/v3/collections/{namespace}/{name}/versions/{version}/"
    detail = http(detail_path, allow_missing=True)
    if detail is None:
        filename = f"{namespace}-{name}-{version}.tar.gz"
        boundary = "kkrepo-ansible-upstream-" + digest
        body = (f'--{boundary}\r\nContent-Disposition: form-data; name="sha256"\r\n\r\n'
                f'{digest}\r\n--{boundary}\r\nContent-Disposition: form-data; name="file"; '
                f'filename="{filename}"\r\nContent-Type: application/octet-stream\r\n\r\n').encode()
        body += archive + f"\r\n--{boundary}--\r\n".encode()
        result = http(repo_path + "/api/v3/artifacts/collections/", "POST", body,
                      "multipart/form-data; boundary=" + boundary)
        task = parse.urlsplit(parse.urljoin(base + repo_path + "/", result["task"]))
        if not task.path.startswith(repo_path + "/api/v3/imports/collections/") or task.query:
            raise RuntimeError("unexpected Nexus fixture import task URL")
        for _ in range(120):
            state = http(task.path)
            if state.get("finished_at"):
                if state.get("state") != "completed":
                    raise RuntimeError(f"Nexus fixture import failed: {state}")
                break
            time.sleep(0.5)
        else:
            raise RuntimeError("Nexus fixture import timed out")
        detail = http(detail_path)
    if detail["artifact"]["sha256"] != digest:
        raise RuntimeError("existing Nexus upstream fixture differs; use a fresh compatibility environment")
    print(f"[compat] controlled Galaxy upstream ready: {namespace}.{name}:{version} sha256={digest}")


if __name__ == "__main__":
    main()
