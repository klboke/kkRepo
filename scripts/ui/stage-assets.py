"""Stage versioned CSS/JS from a kkRepo executable JAR for a rolling upgrade."""

import argparse
from html.parser import HTMLParser
from io import BytesIO
import os
from pathlib import Path
import re
import tempfile
from urllib.parse import parse_qs, urljoin, urlsplit
from zipfile import ZipFile


class Assets(HTMLParser):
    def __init__(self, workspace):
        super().__init__()
        self.workspace = workspace
        self.entries = set()
        self.dictionary_version = None

    def handle_starttag(self, tag, attributes):
        attrs = dict(attributes)
        if tag == "html":
            self.dictionary_version = attrs.get("data-dictionary-version")
        url = attrs.get("src") if tag == "script" else attrs.get("href") if tag == "link" else None
        if not url:
            return
        resolved = urlsplit(urljoin("/" + self.workspace + "/", url))
        if resolved.netloc or ".." in Path(resolved.path).parts or not re.fullmatch(r"/(?:admin|browse|login)/assets/[\w./-]+\.(?:js|css)", resolved.path):
            return
        version = parse_qs(resolved.query).get("v", [])
        if len(version) == 1 and re.fullmatch(r"[\w-]+", version[0]):
            self.entries.add((resolved.path, version[0]))


def publish(target, content):
    target.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(dir=target.parent, delete=False) as stage:
        stage.write(content)
    try:
        os.chmod(stage.name, 0o644)
        try:
            os.link(stage.name, target)
        except FileExistsError:
            if target.read_bytes() != content:
                raise ValueError(f"Existing URL has different content: {target}")
    finally:
        os.unlink(stage.name)


def stage(jar, destination):
    resources = {}
    with ZipFile(jar) as application:
        for name in application.namelist():
            if re.fullmatch(r"BOOT-INF/lib/kkrepo-(?:admin|browse)-ui-[^/]+\.jar", name):
                with ZipFile(BytesIO(application.read(name))) as module:
                    prefix = "META-INF/resources"
                    for entry in module.namelist():
                        if entry.startswith(prefix + "/") and entry.endswith((".html", ".css", ".js")):
                            resources[entry[len(prefix):]] = module.read(entry)
    entries = set()
    for workspace in ("admin", "browse"):
        assets = Assets(workspace)
        assets.feed(resources[f"/{workspace}/index.html"].decode())
        entries.update(assets.entries)
        default_theme = "/browse/assets/themes/default.css"
        versions = {version for path, version in assets.entries if path == default_theme}
        for version in versions:
            entries.update((path, version) for path in resources if path.startswith("/browse/assets/themes/") and path.endswith(".css"))
        dictionary_version = assets.dictionary_version
        if not dictionary_version:
            legacy = re.search(rb'const DICTIONARY_VERSION = "([\w-]+)"', resources["/login/assets/ui-i18n.js"])
            dictionary_version = legacy[1].decode() if legacy else None
        if dictionary_version:
            if not re.fullmatch(r"[\w-]+", dictionary_version):
                raise ValueError("Invalid dictionary version")
            entries.update((path, dictionary_version) for path in resources if path.startswith("/login/assets/i18n/") and path.endswith(".js"))
    for path, version in sorted(entries):
        publish(destination / version / path.lstrip("/"), resources[path])
    print(f"Staged {len(entries)} versioned assets from {jar.name}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("jar", type=Path)
    parser.add_argument("destination", type=Path)
    args = parser.parse_args()
    stage(args.jar, args.destination)
