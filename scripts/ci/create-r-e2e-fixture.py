#!/usr/bin/env python3
"""Create a deterministic, installable R source-package fixture."""

from __future__ import annotations

import argparse
import gzip
import hashlib
import io
import json
import pathlib
import re
import tarfile


PACKAGE_RE = re.compile(r"[A-Za-z][A-Za-z0-9.]+(?<!\.)\Z")
VERSION_RE = re.compile(r"[0-9]+(?:[.-][0-9]+)+\Z")
MAX_PAYLOAD_SIZE = 64 * 1024 * 1024


def deterministic_payload(seed: bytes, size: int) -> bytes:
    """Return incompressible-looking deterministic bytes without relying on random state."""
    output = bytearray()
    counter = 0
    while len(output) < size:
        output.extend(hashlib.sha256(seed + counter.to_bytes(8, "big")).digest())
        counter += 1
    return bytes(output[:size])


def package_bytes(
    name: str,
    version: str,
    message: str,
    imports: list[str],
    exported_function: str,
    payload_size: int = 0,
) -> bytes:
    imports_field = f"Imports: {', '.join(imports)}\n" if imports else ""
    description = (
        f"Package: {name}\n"
        f"Version: {version}\n"
        "Title: kkRepo R Client E2E Fixture\n"
        "Description: A deterministic source package used to validate CRAN-style repositories.\n"
        "License: MIT\n"
        "Author: kkRepo E2E\n"
        "Maintainer: kkRepo E2E <e2e@kkrepo.invalid>\n"
        f"{imports_field}"
        "NeedsCompilation: no\n"
        "Encoding: UTF-8\n"
    ).encode()
    if imports:
        escaped = message.replace("\\", "\\\\").replace('"', '\\"')
        implementation = (
            f'{exported_function} <- function() {{ '
            f'{imports[0]}::{exported_function}(); "{escaped}" }}\n'
        ).encode()
    else:
        escaped = message.replace("\\", "\\\\").replace('"', '\\"')
        implementation = f'{exported_function} <- function() "{escaped}"\n'.encode()

    raw = io.BytesIO()
    with tarfile.open(fileobj=raw, mode="w", format=tarfile.PAX_FORMAT) as archive:
        entries = [
            (f"{name}/", b"", 0o755),
            (f"{name}/DESCRIPTION", description, 0o644),
            (f"{name}/NAMESPACE", f"export({exported_function})\n".encode(), 0o644),
            (f"{name}/R/", b"", 0o755),
            (f"{name}/R/{exported_function}.R", implementation, 0o644),
        ]
        if payload_size:
            entries.extend([
                (f"{name}/inst/", b"", 0o755),
                (f"{name}/inst/extdata/", b"", 0o755),
                (
                    f"{name}/inst/extdata/payload.bin",
                    deterministic_payload(f"{name}:{version}".encode(), payload_size),
                    0o644,
                ),
            ])
        for path, data, mode in entries:
            entry = tarfile.TarInfo(path)
            entry.type = tarfile.DIRTYPE if path.endswith("/") else tarfile.REGTYPE
            entry.size = 0 if path.endswith("/") else len(data)
            entry.mode = mode
            entry.uid = 0
            entry.gid = 0
            entry.uname = "root"
            entry.gname = "root"
            entry.mtime = 0
            archive.addfile(entry, None if path.endswith("/") else io.BytesIO(data))
    compressed = io.BytesIO()
    with gzip.GzipFile(filename="", mode="wb", fileobj=compressed, mtime=0) as output:
        output.write(raw.getvalue())
    return compressed.getvalue()


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", required=True, type=pathlib.Path)
    parser.add_argument("--package", required=True)
    parser.add_argument("--version", required=True)
    parser.add_argument("--message", default="kkRepo R client E2E")
    parser.add_argument("--imports", action="append", default=[])
    parser.add_argument("--function", default="kkrepo_marker")
    parser.add_argument(
        "--payload-size",
        type=int,
        default=0,
        help="deterministic binary payload size in bytes (maximum 64 MiB)",
    )
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    if not PACKAGE_RE.fullmatch(args.package):
        raise SystemExit(f"invalid R package name: {args.package}")
    if not VERSION_RE.fullmatch(args.version):
        raise SystemExit(f"invalid R package version: {args.version}")
    for dependency in args.imports:
        if not PACKAGE_RE.fullmatch(dependency):
            raise SystemExit(f"invalid R dependency package name: {dependency}")
    if not re.fullmatch(r"[A-Za-z][A-Za-z0-9._]*", args.function):
        raise SystemExit(f"invalid R function name: {args.function}")
    if args.payload_size < 0 or args.payload_size > MAX_PAYLOAD_SIZE:
        raise SystemExit("payload size must be between 0 and 67108864 bytes")
    payload = package_bytes(
        args.package,
        args.version,
        args.message,
        args.imports,
        args.function,
        args.payload_size,
    )
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_bytes(payload)
    print(
        json.dumps(
            {
                "filename": args.output.name,
                "package": args.package,
                "version": args.version,
                "imports": args.imports,
                "payload_size": args.payload_size,
                "size": len(payload),
                "md5": hashlib.md5(payload).hexdigest(),  # noqa: S324 - CRAN protocol checksum
                "sha256": hashlib.sha256(payload).hexdigest(),
            },
            sort_keys=True,
        )
    )


if __name__ == "__main__":
    main()
