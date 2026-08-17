#!/usr/bin/env python3
"""Deterministic Models-only Hub/LFS/Xet-bridge fixture for compatibility and perf runs."""

from __future__ import annotations

import argparse
import hashlib
import json
import threading
import urllib.parse
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any


REPO_ID = "kkrepo/hf-benchmark"
COMMIT = "0123456789abcdef0123456789abcdef01234567"
CONFIG = b'{"architectures":["FixtureModel"],"model_type":"fixture"}\n'
MODEL = b"K" * (4 * 1024 * 1024)


def sha256(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def git_oid(value: bytes) -> str:
    return hashlib.sha1(b"blob " + str(len(value)).encode() + b"\0" + value).hexdigest()


FILES = {
    "config.json": {
        "body": CONFIG,
        "oid": git_oid(CONFIG),
        "content_type": "application/json",
        "xet": False,
    },
    "model.safetensors": {
        "body": MODEL,
        "oid": "b" * 40,
        "content_type": "application/octet-stream",
        "xet": True,
    },
}


class FixtureServer(ThreadingHTTPServer):
    def __init__(self, address: tuple[str, int]):
        super().__init__(address, FixtureHandler)
        self.counts: dict[str, int] = {}
        self.lock = threading.Lock()

    def count(self, key: str) -> None:
        with self.lock:
            self.counts[key] = self.counts.get(key, 0) + 1


class FixtureHandler(BaseHTTPRequestHandler):
    server_version = "kkrepo-huggingface-fixture/1"
    protocol_version = "HTTP/1.1"

    def do_HEAD(self) -> None:
        self._dispatch(head=True)

    def do_GET(self) -> None:
        self._dispatch(head=False)

    def do_POST(self) -> None:
        self._dispatch(head=False)

    def log_message(self, pattern: str, *args: Any) -> None:
        if getattr(self.server, "verbose", False):
            super().log_message(pattern, *args)

    def _dispatch(self, head: bool) -> None:
        parsed = urllib.parse.urlsplit(self.path)
        path = urllib.parse.unquote(parsed.path).lstrip("/")
        if path == "__stats":
            self._json(dict(sorted(self.server.counts.items())), head=head)
            return
        if path == "__health":
            self._json({"status": "ok", "repo": REPO_ID, "commit": COMMIT}, head=head)
            return
        if path.startswith("api/models/"):
            self._api(path, parsed.query, head)
            return
        prefix = REPO_ID + "/resolve/"
        cdn_prefix = "cdn/" + REPO_ID + "/"
        if path.startswith(prefix):
            tail = path[len(prefix):]
            revision, separator, filename = tail.partition("/")
            if not separator or revision not in {"main", COMMIT} or filename not in FILES:
                self._error(HTTPStatus.NOT_FOUND, "Entry not found", head)
                return
            self.server.count("resolve:" + filename)
            file = FILES[filename]
            if file["xet"]:
                location = self._absolute("/" + cdn_prefix + urllib.parse.quote(filename))
                headers = self._identity_headers(filename, file)
                headers.update({
                    "Location": location + "?signature=fixture",
                    "X-Xet-Hash": "xet-fixture-reconstruction-id",
                    "Link": "<" + self._absolute(
                        "/api/models/" + REPO_ID + "/xet-read-token/" + COMMIT
                    ) + ">; rel=\"xet-auth\"",
                })
                self._send(HTTPStatus.FOUND, b"", headers, head=True)
                return
            self._file(filename, file, head)
            return
        if path.startswith(cdn_prefix):
            filename = urllib.parse.unquote(path[len(cdn_prefix):])
            if filename not in FILES:
                self._error(HTTPStatus.NOT_FOUND, "Entry not found", head)
                return
            self.server.count("cdn:" + filename)
            self._file(filename, FILES[filename], head)
            return
        self._error(HTTPStatus.NOT_FOUND, "Route not found", head)

    def _api(self, path: str, query: str, head: bool) -> None:
        base = "api/models/" + REPO_ID
        if not path.startswith(base):
            self._error(HTTPStatus.NOT_FOUND, "Model not found", head)
            return
        suffix = path[len(base):]
        if "/xet-read-token/" in suffix:
            self.server.count("xet-token")
            self._error(HTTPStatus.FORBIDDEN, "Client-side Xet is disabled", head)
            return
        if suffix in {"", "/revision/main", "/revision/" + COMMIT}:
            self.server.count("model-info")
            self._json(self._model_info(), head=head, headers={"X-Repo-Commit": COMMIT})
            return
        if suffix in {"/tree/main", "/tree/" + COMMIT}:
            self.server.count("tree")
            self._json(self._tree(), head=head, headers={"X-Repo-Commit": COMMIT})
            return
        if suffix in {"/paths-info/main", "/paths-info/" + COMMIT}:
            if self.command != "POST":
                self._error(HTTPStatus.METHOD_NOT_ALLOWED, "POST required", head)
                return
            length = int(self.headers.get("Content-Length", "0"))
            try:
                request = json.loads(self.rfile.read(length))
                names = request["paths"]
                if not isinstance(names, list):
                    raise ValueError("paths")
            except (KeyError, ValueError, json.JSONDecodeError):
                self._error(HTTPStatus.BAD_REQUEST, "Invalid paths", head)
                return
            self.server.count("paths-info")
            entries = [entry for entry in self._tree() if entry["path"] in names]
            self._json(entries, head=head, headers={"X-Repo-Commit": COMMIT})
            return
        if suffix == "/refs":
            self.server.count("refs")
            self._json(
                {"branches": [{"name": "main", "ref": "refs/heads/main",
                                "targetCommit": COMMIT}], "tags": [], "pullRequests": []},
                head=head,
            )
            return
        self._error(HTTPStatus.NOT_FOUND, "API route not found", head)

    def _model_info(self) -> dict[str, Any]:
        return {
            "id": REPO_ID,
            "modelId": REPO_ID,
            "sha": COMMIT,
            "private": False,
            "gated": False,
            "library_name": "transformers",
            "pipeline_tag": "text-classification",
            "tags": ["license:apache-2.0"],
            "siblings": [
                {
                    "rfilename": name,
                    "path": name,
                    "type": "file",
                    "size": len(file["body"]),
                    "oid": file["oid"],
                    **({
                        "lfs": {
                            "oid": sha256(file["body"]),
                            "sha256": sha256(file["body"]),
                            "pointerSize": 132,
                            "size": len(file["body"]),
                        },
                        "xetHash": "xet-fixture-reconstruction-id",
                    } if file["xet"] else {}),
                }
                for name, file in FILES.items()
            ],
        }

    def _tree(self) -> list[dict[str, Any]]:
        return self._model_info()["siblings"]

    def _file(self, filename: str, file: dict[str, Any], head: bool) -> None:
        body = file["body"]
        headers = self._identity_headers(filename, file)
        headers["Content-Type"] = file["content_type"]
        headers["Accept-Ranges"] = "bytes"
        status = HTTPStatus.OK
        range_value = self.headers.get("Range")
        if range_value:
            try:
                unit, values = range_value.split("=", 1)
                start_text, end_text = values.split("-", 1)
                if unit != "bytes" or "," in values:
                    raise ValueError("range")
                start = int(start_text)
                end = min(len(body) - 1, int(end_text) if end_text else len(body) - 1)
                if start < 0 or start > end:
                    raise ValueError("range")
            except ValueError:
                self._send(HTTPStatus.REQUESTED_RANGE_NOT_SATISFIABLE, b"", {
                    "Content-Range": "bytes */" + str(len(body))
                }, head=True)
                return
            body = body[start:end + 1]
            status = HTTPStatus.PARTIAL_CONTENT
            headers["Content-Range"] = f"bytes {start}-{end}/{len(file['body'])}"
        self._send(status, body, headers, head)

    def _identity_headers(self, filename: str, file: dict[str, Any]) -> dict[str, str]:
        headers = {
            "ETag": '"' + (sha256(file["body"]) if file["xet"] else file["oid"]) + '"',
            "X-Repo-Commit": COMMIT,
        }
        if file["xet"]:
            headers["X-Linked-Etag"] = '"' + sha256(file["body"]) + '"'
            headers["X-Linked-Size"] = str(len(file["body"]))
        return headers

    def _json(
        self, value: Any, *, head: bool, headers: dict[str, str] | None = None
    ) -> None:
        body = json.dumps(value, separators=(",", ":"), sort_keys=True).encode()
        merged = {"Content-Type": "application/json", "ETag": '"' + sha256(body) + '"'}
        if headers:
            merged.update(headers)
        self._send(HTTPStatus.OK, body, merged, head)

    def _error(self, status: HTTPStatus, message: str, head: bool) -> None:
        body = json.dumps({"error": message}, separators=(",", ":")).encode()
        self._send(status, body, {"Content-Type": "application/json"}, head)

    def _send(
        self, status: HTTPStatus, body: bytes, headers: dict[str, str], head: bool
    ) -> None:
        self.send_response(status)
        for name, value in headers.items():
            self.send_header(name, value)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Connection", "keep-alive")
        self.end_headers()
        if not head and body:
            self.wfile.write(body)

    def _absolute(self, path: str) -> str:
        host = self.headers.get("Host", f"127.0.0.1:{self.server.server_port}")
        return "http://" + host + path


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--port", type=int, default=48770)
    parser.add_argument("--verbose", action="store_true")
    args = parser.parse_args()
    server = FixtureServer((args.host, args.port))
    server.verbose = args.verbose
    print(json.dumps({
        "url": f"http://127.0.0.1:{server.server_port}",
        "repo_id": REPO_ID,
        "commit": COMMIT,
        "model_sha256": sha256(MODEL),
        "model_size": len(MODEL),
    }), flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
