#!/usr/bin/env python3
"""Regenerate docs/en/dev/nexus-rest-api-inventory.md from the Nexus API client endpoint list.

The reference list is the README of sonatype-nexus-community/nexus-repo-api-client-go, which is
generated from a Nexus /service/rest/swagger.json. The default URL is pinned to ``CLIENT_REF`` so a
regeneration does not silently follow a newer Nexus version; override it with NEXUS_API_CLIENT_URL
or --client-url.

Usage:
    python3 scripts/nexus-rest-api-inventory.py --git-ref upstream/main
    python3 scripts/nexus-rest-api-inventory.py --git-ref ef7132e --diff-ref upstream/main \
        --output /tmp/inventory-455.md

``--git-ref`` reads the kkRepo sources from a git ref (no checkout needed), which is how the
committed inventory is produced. Without it, the script walks the working tree, which is only
correct when the checkout is at the baseline being documented.

The match is an exact, normalized method/path match derived from Spring annotations. It says
nothing about request/response schemas or wire behavior; see nexus-rest-api-alignment.md.
"""
import argparse
import collections
import os
import re
import subprocess
import sys
import urllib.request

CLIENT_REF = "17645c8ed520893e2515e8bd76202b537b1acc24"
DEFAULT_URL = (
    "https://raw.githubusercontent.com/sonatype-nexus-community/"
    f"nexus-repo-api-client-go/{CLIENT_REF}/README.md"
)
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUTPUT = os.path.join(ROOT, "docs/en/dev/nexus-rest-api-inventory.md")
SOURCE = os.environ.get("NEXUS_INVENTORY_SOURCE", os.path.join(ROOT, "server/src/main/java"))
SOURCE_REL = "server/src/main/java"


def nexus_endpoints(url):
    body = urllib.request.urlopen(url, timeout=60).read().decode("utf-8", "replace")
    found = re.findall(r"\| \*\*(Get|Post|Put|Delete)\*\* (/v1/[^ |]+)", body)
    return sorted({(method.upper(), path) for method, path in found})


def annotations_to_endpoints(texts):
    """Map (file name, java source) pairs to declared controller method/path pairs."""
    result = set()
    for name, text in texts:
        if not name.endswith(".java") or "@RestController" not in text:
            continue
        class_mapping = re.search(
            r'@RequestMapping\(\s*(?:value\s*=\s*)?(\{[^}]*\}|"[^"]*")\s*\)', text)
        prefix = ""
        if class_mapping:
            values = re.findall(r'"([^"]+)"', class_mapping.group(1))
            prefix = values[0] if values else ""
        for kind, body in re.findall(
                r"@(Get|Post|Put|Delete|Patch)Mapping(?:\(\s*(?:value\s*=\s*)?"
                r"(\{[^}]*\}|\"[^\"]*\"|[^)]*)\))?", text, re.S):
            values = re.findall(r'"([^"]+)"', body or "") or [""]
            for value in values:
                path = prefix.rstrip("/") + "/" + value.lstrip("/") if prefix else value
                result.add((kind.upper(), path))
    return result


def kkrepo_endpoints_in_tree(source):
    texts = []
    for dirpath, _, files in os.walk(source):
        for name in files:
            path = os.path.join(dirpath, name)
            with open(path, encoding="utf-8", errors="ignore") as handle:
                texts.append((name, handle.read()))
    return annotations_to_endpoints(texts)


def git(*args):
    return subprocess.run(
        ["git", "-C", ROOT, *args], capture_output=True, text=True, check=True).stdout


def kkrepo_endpoints_from_git_ref(ref):
    files = [name for name in git("ls-tree", "-r", "--name-only", ref, "--", SOURCE_REL).split()
             if name.endswith(".java")]
    texts = [(name, git("show", f"{ref}:{name}")) for name in files]
    return annotations_to_endpoints(texts)


def git_sha(ref="HEAD"):
    try:
        return git("rev-parse", ref).strip()
    except (subprocess.CalledProcessError, FileNotFoundError):
        return None


def normalize(path):
    return re.sub(r"\{[^}]+\}", "{}", path).rstrip("/")


def as_v1(path):
    if path.startswith("/v1/"):
        return path
    if path.startswith("/service/rest/v1/"):
        return path[len("/service/rest"):]
    return None


def local_matches(nexus, endpoints):
    local = collections.defaultdict(set)
    for method, path in endpoints:
        value = as_v1(path)
        if value:
            local[method].add(normalize(value))
    return {(method, path) for method, path in nexus if normalize(path) in local.get(method, set())}


def area_of(path):
    parts = [part for part in path.split("/") if part]
    return parts[1] if len(parts) > 1 else path


def command_line(git_ref, output, client_url):
    parts = ["python3", "scripts/nexus-rest-api-inventory.py"]
    if git_ref:
        parts += ["--git-ref", git_ref]
    if os.path.abspath(output) != os.path.abspath(OUTPUT):
        parts += ["--output", output]
    if client_url != DEFAULT_URL:
        parts += ["--client-url", client_url]
    return " ".join(parts)


def main(argv=None):
    parser = argparse.ArgumentParser(description=(__doc__ or "").splitlines()[0])
    parser.add_argument("--git-ref", help="read server sources from this git ref instead of the tree")
    parser.add_argument("--source", default=SOURCE, help="Java source root for the working-tree mode")
    parser.add_argument("--output", default=OUTPUT, help="markdown output path")
    parser.add_argument("--client-url", default=os.environ.get("NEXUS_API_CLIENT_URL", DEFAULT_URL),
                        help="reference endpoint list (default: pinned client %s)" % CLIENT_REF)
    parser.add_argument("--diff-ref", help="also list matches present here but absent at this ref")
    args = parser.parse_args(argv)

    nexus = [(method, path) for method, path in nexus_endpoints(args.client_url)
             if path.startswith("/v1/")]
    endpoints = (kkrepo_endpoints_from_git_ref(args.git_ref) if args.git_ref
                 else kkrepo_endpoints_in_tree(args.source))
    matches = local_matches(nexus, endpoints)
    rows = []
    for method, path in nexus:
        rows.append((area_of(path), method, path,
                     "yes" if (method, path) in matches else "no"))
    rows.sort(key=lambda row: (row[0], row[2], row[1]))

    if args.git_ref:
        resolved = git_sha(args.git_ref)
        baseline = (f"`{resolved}`" if resolved and resolved.lower() == args.git_ref.lower()
                    else f"`{args.git_ref}` at `{resolved}`")
    else:
        baseline = f"working tree at `{git_sha('HEAD')}` (not a pinned baseline)"

    lines = [
        "# Nexus Repository REST API inventory",
        "",
        f"Reference endpoint list: `sonatype-nexus-community/nexus-repo-api-client-go` at "
        f"`{CLIENT_REF}` ({len(nexus)} `/v1` method/path pairs).",
        "",
        f"kkRepo baseline: {baseline} -- {len(matches)}/{len(nexus)} exact method/path matches.",
        "",
        "`yes` means an exact method/path match exists under `/service/rest/v1`, derived from Spring "
        "`@RestController` annotations; `no` means the Nexus-shaped path is absent (functional "
        "equivalents may exist under `/internal`). A `yes` is not evidence of wire or behavioral "
        "compatibility: schemas, status codes, headers and paging still need a `compat-test` case.",
        "",
        "Regenerate with:",
        "",
        f"    {command_line(git_sha(args.git_ref) if args.git_ref else None, args.output, args.client_url)}",
        "",
        "| Area | Method | Path | kkRepo |",
        "| --- | --- | --- | --- |",
    ]
    lines += [f"| {area} | {method} | `{path}` | {status} |" for area, method, path, status in rows]
    with open(args.output, "w", encoding="utf-8") as output:
        output.write("\n".join(lines) + "\n")
    print(f"{len(matches)}/{len(nexus)} Nexus endpoints matched; wrote {args.output}")

    areas = collections.defaultdict(lambda: [0, 0])
    for area, _, _, status in rows:
        areas[area][0] += 1
        if status == "yes":
            areas[area][1] += 1
    for area in sorted(areas, key=lambda name: (-areas[name][0], name)):
        total, hit = areas[area]
        if total >= 2:
            print(f"  {hit}/{total} {area}")

    if args.diff_ref:
        base_matches = local_matches(nexus, kkrepo_endpoints_from_git_ref(args.diff_ref))
        added = sorted(matches - base_matches)
        removed = sorted(base_matches - matches)
        print(f"diff {args.git_ref} vs {args.diff_ref}: +{len(added)} -{len(removed)}")
        for method, path in added:
            print(f"  + {method} {path}")
        for method, path in removed:
            print(f"  - {method} {path}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
