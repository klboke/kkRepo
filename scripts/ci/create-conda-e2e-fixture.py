#!/usr/bin/env python3
"""Create a deterministic, installable legacy Conda package for existing E2E suites."""

from __future__ import annotations

import argparse
import hashlib
import io
import json
import pathlib
import re
import tarfile


SAFE_TOKEN = re.compile(r"^[A-Za-z0-9_][A-Za-z0-9_.-]*$")


def require_token(label: str, value: str) -> str:
    if not value or len(value) > 64 or not SAFE_TOKEN.fullmatch(value):
        raise SystemExit(f"invalid Conda {label}: {value!r}")
    return value


def json_bytes(value: object) -> bytes:
    return (json.dumps(value, sort_keys=True, separators=(",", ":")) + "\n").encode()


def add_file(archive: tarfile.TarFile, name: str, body: bytes) -> None:
    entry = tarfile.TarInfo(name)
    entry.size = len(body)
    entry.mode = 0o644
    entry.mtime = 0
    entry.uid = 0
    entry.gid = 0
    entry.uname = ""
    entry.gname = ""
    archive.addfile(entry, io.BytesIO(body))


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--name", required=True)
    parser.add_argument("--version", required=True)
    parser.add_argument("--build", required=True)
    parser.add_argument("--subdir", default="noarch")
    parser.add_argument("--marker", required=True)
    parser.add_argument("--output", required=True, type=pathlib.Path)
    args = parser.parse_args()

    name = require_token("name", args.name)
    version = require_token("version", args.version)
    build = require_token("build", args.build)
    subdir = require_token("subdir", args.subdir)
    filename = f"{name}-{version}-{build}.tar.bz2"
    if args.output.name != filename:
        raise SystemExit(
            f"output filename must match the Conda coordinate: expected {filename!r}"
        )

    marker_path = f"share/kkrepo-conda-e2e/{name}.txt"
    marker = (args.marker + "\n").encode()
    marker_sha256 = hashlib.sha256(marker).hexdigest()
    index = {
        "name": name,
        "version": version,
        "build": build,
        "build_number": 0,
        "subdir": subdir,
        "depends": [],
        "license": "Apache-2.0",
        "timestamp": 1767225600000,
    }
    if subdir == "noarch":
        index["noarch"] = "generic"
    paths = {
        "paths_version": 1,
        "paths": [
            {
                "_path": marker_path,
                "path_type": "hardlink",
                "sha256": marker_sha256,
                "size_in_bytes": len(marker),
            }
        ],
    }
    about = {
        "summary": "kkRepo Conda client and migration E2E fixture",
        "license": "Apache-2.0",
    }

    args.output.parent.mkdir(parents=True, exist_ok=True)
    with tarfile.open(args.output, "w:bz2", format=tarfile.USTAR_FORMAT) as archive:
        add_file(archive, "info/index.json", json_bytes(index))
        add_file(archive, "info/files", (marker_path + "\n").encode())
        add_file(archive, "info/paths.json", json_bytes(paths))
        add_file(archive, "info/about.json", json_bytes(about))
        add_file(archive, marker_path, marker)

    package = args.output.read_bytes()
    print(
        json.dumps(
            {
                "filename": filename,
                "markerPath": marker_path,
                "sha256": hashlib.sha256(package).hexdigest(),
                "size": len(package),
            },
            sort_keys=True,
            separators=(",", ":"),
        )
    )


if __name__ == "__main__":
    main()
