#!/usr/bin/env python3
"""Create a small, deterministic Debian binary package without external tools."""

from __future__ import annotations

import argparse
import gzip
import hashlib
import io
import json
import pathlib
import tarfile


def tar_bytes(entries: list[tuple[str, bytes | None, int]]) -> bytes:
    output = io.BytesIO()
    with tarfile.open(fileobj=output, mode="w", format=tarfile.GNU_FORMAT) as archive:
        for name, content, mode in entries:
            info = tarfile.TarInfo(name)
            info.mode = mode
            info.mtime = 0
            info.uid = 0
            info.gid = 0
            info.uname = "root"
            info.gname = "root"
            if content is None:
                info.type = tarfile.DIRTYPE
                archive.addfile(info)
            else:
                info.size = len(content)
                archive.addfile(info, io.BytesIO(content))
    return output.getvalue()


def ar_member(name: str, content: bytes) -> bytes:
    if len(name) > 15:
        raise ValueError(f"ar member name is too long: {name}")
    header = (
        f"{name + '/':<16}"
        f"{0:<12}"
        f"{0:<6}"
        f"{0:<6}"
        f"{0o100644:<8o}"
        f"{len(content):<10}`\n"
    ).encode("ascii")
    return header + content + (b"\n" if len(content) % 2 else b"")


def build_package(
    package: str,
    version: str,
    architecture: str,
    depends: str | None,
    message: str,
    payload_bytes: int = 0,
) -> bytes:
    fields = [
        f"Package: {package}",
        f"Version: {version}",
        f"Architecture: {architecture}",
        "Maintainer: kkRepo CI <ci@kkrepo.invalid>",
        "Section: utils",
        "Priority: optional",
    ]
    if depends:
        fields.append(f"Depends: {depends}")
    fields.extend([
        "Description: kkRepo APT real-client fixture",
        " generated without invoking maintainer scripts",
        "",
    ])
    control = "\n".join(fields).encode("utf-8")
    control_tar = tar_bytes([("./control", control, 0o644)])
    data_path = f"./usr/share/{package}/message.txt"
    data_entries: list[tuple[str, bytes | None, int]] = [
        ("./usr", None, 0o755),
        ("./usr/share", None, 0o755),
        (f"./usr/share/{package}", None, 0o755),
        (data_path, (message + "\n").encode("utf-8"), 0o644),
    ]
    if payload_bytes:
        seed = f"{package}\0{version}\0{architecture}\0{message}".encode("utf-8")
        deterministic = bytearray()
        counter = 0
        while len(deterministic) < payload_bytes:
            deterministic.extend(hashlib.sha256(seed + counter.to_bytes(8, "big")).digest())
            counter += 1
        data_entries.append((
            f"./usr/share/{package}/payload.bin",
            bytes(deterministic[:payload_bytes]),
            0o644,
        ))
    data_tar = tar_bytes(data_entries)
    return b"".join([
        b"!<arch>\n",
        ar_member("debian-binary", b"2.0\n"),
        ar_member("control.tar.gz", gzip.compress(control_tar, compresslevel=9, mtime=0)),
        ar_member("data.tar.gz", gzip.compress(data_tar, compresslevel=9, mtime=0)),
    ])


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", required=True, type=pathlib.Path)
    parser.add_argument("--package", required=True)
    parser.add_argument("--version", required=True)
    parser.add_argument("--architecture", required=True)
    parser.add_argument("--depends")
    parser.add_argument("--message", default="kkRepo APT client E2E")
    parser.add_argument(
        "--payload-bytes",
        type=int,
        default=0,
        help="deterministic incompressible payload size for throughput fixtures",
    )
    args = parser.parse_args()
    if args.payload_bytes < 0:
        parser.error("--payload-bytes must be non-negative")

    payload = build_package(
        args.package,
        args.version,
        args.architecture,
        args.depends,
        args.message,
        args.payload_bytes,
    )
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_bytes(payload)
    print(json.dumps({
        "path": str(args.output),
        "package": args.package,
        "version": args.version,
        "architecture": args.architecture,
        "payloadBytes": args.payload_bytes,
        "size": len(payload),
        "sha256": hashlib.sha256(payload).hexdigest(),
    }, sort_keys=True))


if __name__ == "__main__":
    main()
