#!/usr/bin/env bash
set -euo pipefail

: "${OHPM_INSTALL_DIR:?Choose a new directory for the pinned OHPM toolchain}"
if [[ -e "$OHPM_INSTALL_DIR" ]]; then
  echo "OHPM_INSTALL_DIR already exists; refusing to overwrite it" >&2
  exit 1
fi
ARCHIVE="$(mktemp)"
trap 'rm -f "$ARCHIVE"' EXIT
curl --fail --location --retry 3 --max-time 180 \
  https://repo.huaweicloud.com/harmonyos/ohpm/5.0.2/oh-command-line-tools-20240715.zip -o "$ARCHIVE"
python3 - "$ARCHIVE" "$OHPM_INSTALL_DIR" <<'PY'
import hashlib
import pathlib
import sys
import zipfile

archive = pathlib.Path(sys.argv[1])
expected = "a91888b3c1623d29fd0e387eee4f5e9523cc8f6242de9cb4be3ef8c46eab7e4a"
with archive.open("rb") as source:
    actual = hashlib.file_digest(source, "sha256").hexdigest()
if actual != expected:
    raise SystemExit("Official OHPM archive SHA-256 mismatch")
destination = pathlib.Path(sys.argv[2])
with zipfile.ZipFile(archive) as source:
    for entry in source.infolist():
        path = pathlib.PurePosixPath(entry.filename)
        if path.is_absolute() or ".." in path.parts:
            raise SystemExit("Unsafe archive path")
        if entry.filename.startswith("oh-command-line-tools/ohpm/"):
            source.extract(entry, destination)
launcher = destination / "oh-command-line-tools/ohpm/bin/ohpm"
launcher.chmod(0o755)
print(launcher.resolve())
PY
"$OHPM_INSTALL_DIR/oh-command-line-tools/ohpm/bin/ohpm" --version
