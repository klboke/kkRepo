#!/usr/bin/env python3
"""Compare warmed Alpine v2 repository read paths on Nexus and kkRepo.

Both hosted repositories must contain the same package bytes and logical package set.
The runner validates signed-index shape and payload identity before timing, alternates
target order, reports round medians, and can enforce the Alpine release gates.
"""

from __future__ import annotations

import argparse
import base64
import concurrent.futures
import datetime as dt
import gzip
import hashlib
import http.client
import io
import json
import math
import pathlib
import platform
import shlex
import ssl
import statistics
import subprocess
import tarfile
import threading
import time
import urllib.parse
from dataclasses import asdict, dataclass
from typing import Any


@dataclass
class Target:
    name: str
    base_url: str
    authorization: str | None
    index_etag: str | None = None


@dataclass(frozen=True)
class Scenario:
    name: str
    route: str
    validation: str
    method: str = "GET"
    range_header: str | None = None
    conditional: bool = False
    expected_status: int = 200
    gate: str = "metadata"


@dataclass(frozen=True)
class Measurement:
    requests: int
    concurrency: int
    response_bytes: int
    wall_seconds: float
    requests_per_second: float
    mebibytes_per_second: float
    p50_ms: float
    p95_ms: float
    p99_ms: float
    maximum_ms: float


class RequestFailure(RuntimeError):
    pass


def authorization(value: str | None) -> str | None:
    if not value:
        return None
    return "Basic " + base64.b64encode(value.encode()).decode("ascii")


def connection(parts: urllib.parse.SplitResult, timeout: float) -> http.client.HTTPConnection:
    if parts.scheme == "https":
        return http.client.HTTPSConnection(
            parts.hostname,
            parts.port or 443,
            timeout=timeout,
            context=ssl.create_default_context(),
        )
    if parts.scheme == "http":
        return http.client.HTTPConnection(parts.hostname, parts.port or 80, timeout=timeout)
    raise ValueError(f"unsupported URL scheme: {parts.scheme}")


def request_route(target: Target, relative: str) -> tuple[urllib.parse.SplitResult, str]:
    base = urllib.parse.urlsplit(target.base_url.rstrip("/") + "/")
    relative_parts = urllib.parse.urlsplit(relative.lstrip("/"))
    route = base.path.rstrip("/") + "/" + relative_parts.path
    if relative_parts.query:
        route += "?" + relative_parts.query
    return base, route


def one_request(
    client: http.client.HTTPConnection,
    target: Target,
    route: str,
    scenario: Scenario,
) -> tuple[float, bytes, dict[str, str]]:
    headers = {"Accept": "*/*", "User-Agent": "kkrepo-alpine-performance/1"}
    if target.authorization:
        headers["Authorization"] = target.authorization
    if scenario.range_header:
        headers["Range"] = scenario.range_header
    if scenario.conditional:
        if not target.index_etag:
            raise RequestFailure(f"{target.name} did not expose an ETag for conditional GET")
        headers["If-None-Match"] = target.index_etag
    started = time.perf_counter_ns()
    client.request(scenario.method, route, headers=headers)
    response = client.getresponse()
    body = response.read()
    elapsed = (time.perf_counter_ns() - started) / 1_000_000
    if response.status != scenario.expected_status:
        raise RequestFailure(
            f"{target.name} {scenario.method} {route} returned HTTP {response.status}: "
            f"{body[:300]!r}"
        )
    response_headers = {name.lower(): value for name, value in response.getheaders()}
    if scenario.validation not in {"empty", "status"} and not body:
        raise RequestFailure(f"{target.name} {scenario.name} returned an empty body")
    return elapsed, body, response_headers


def tar_entries(compressed: bytes) -> dict[str, bytes]:
    try:
        expanded = gzip.decompress(compressed)
        with tarfile.open(fileobj=io.BytesIO(expanded), mode="r:") as archive:
            result: dict[str, bytes] = {}
            for member in archive:
                if member.isfile():
                    source = archive.extractfile(member)
                    if source is None:
                        raise RequestFailure(f"could not read tar member {member.name}")
                    result[member.name] = source.read()
            return result
    except (gzip.BadGzipFile, EOFError, tarfile.TarError, OSError) as error:
        raise RequestFailure("response is not a valid gzip/tar archive") from error


def canonical_index(body: bytes) -> dict[str, Any]:
    entries = tar_entries(body)
    signatures = sorted(name for name in entries if name.startswith(".SIGN."))
    if not signatures or "APKINDEX" not in entries:
        raise RequestFailure("APKINDEX must contain a signature and APKINDEX payload")
    records: list[tuple[str, str, str, str]] = []
    for stanza in entries["APKINDEX"].decode("utf-8").strip().split("\n\n"):
        fields = dict(line.split(":", 1) for line in stanza.splitlines())
        required = {"P", "V", "S", "I", "C"}
        if not required.issubset(fields):
            raise RequestFailure(f"APKINDEX record is missing {required - fields.keys()}")
        if not fields["C"].startswith("Q1"):
            raise RequestFailure("APKINDEX package identity is not Q1")
        # Nexus 3.94 rewrites noarch to the repository path architecture and may emit a
        # non-apk-tools Q1 for unsigned uploads. Compare the stable logical fields only.
        records.append((fields["P"], fields["V"], fields["S"], fields["I"]))
    return {
        "records": sorted(records),
        "signature_types": sorted(name.split(".", 3)[2] for name in signatures),
    }


def canonical(body: bytes, scenario: Scenario) -> Any:
    if scenario.validation == "index":
        return canonical_index(body)
    if scenario.validation == "bytes":
        return {"size": len(body), "sha256": hashlib.sha256(body).hexdigest()}
    if scenario.validation == "range":
        if len(body) != 65536:
            raise RequestFailure(f"Range returned {len(body)} bytes instead of 65536")
        return {"size": len(body), "sha256": hashlib.sha256(body).hexdigest()}
    if scenario.validation in {"empty", "status"}:
        if body:
            raise RequestFailure(f"{scenario.name} should have an empty response body")
        return scenario.expected_status
    raise RequestFailure(f"unknown validation mode: {scenario.validation}")


def preflight(target: Target, scenario: Scenario, timeout: float) -> Any:
    parts, route = request_route(target, scenario.route)
    client = connection(parts, timeout)
    try:
        _, body, headers = one_request(client, target, route, scenario)
        if scenario.validation == "index":
            target.index_etag = headers.get("etag")
            if not target.index_etag:
                raise RequestFailure(f"{target.name} APKINDEX did not expose an ETag")
        return canonical(body, scenario)
    finally:
        client.close()


def percentile(values: list[float], fraction: float) -> float:
    ordered = sorted(values)
    return ordered[max(0, math.ceil(len(ordered) * fraction) - 1)]


def measure(
    target: Target,
    scenario: Scenario,
    requests: int,
    concurrency: int,
    timeout: float,
) -> Measurement:
    workers = max(1, min(concurrency, requests))
    assignments = [requests // workers] * workers
    for index in range(requests % workers):
        assignments[index] += 1
    barrier = threading.Barrier(workers)

    def worker(count: int) -> tuple[list[float], int]:
        parts, route = request_route(target, scenario.route)
        client = connection(parts, timeout)
        timings: list[float] = []
        transferred = 0
        try:
            barrier.wait(timeout=timeout)
            for _ in range(count):
                elapsed, body, _ = one_request(client, target, route, scenario)
                timings.append(elapsed)
                transferred += len(body)
            return timings, transferred
        finally:
            client.close()

    started = time.perf_counter()
    timings: list[float] = []
    transferred = 0
    with concurrent.futures.ThreadPoolExecutor(max_workers=workers) as executor:
        for future in [executor.submit(worker, count) for count in assignments]:
            worker_timings, worker_bytes = future.result()
            timings.extend(worker_timings)
            transferred += worker_bytes
    wall = time.perf_counter() - started
    return Measurement(
        requests=len(timings),
        concurrency=workers,
        response_bytes=transferred,
        wall_seconds=round(wall, 6),
        requests_per_second=round(len(timings) / wall, 2),
        mebibytes_per_second=round(transferred / wall / 1024 / 1024, 2),
        p50_ms=round(percentile(timings, 0.50), 3),
        p95_ms=round(percentile(timings, 0.95), 3),
        p99_ms=round(percentile(timings, 0.99), 3),
        maximum_ms=round(max(timings), 3),
    )


def median_measurement(values: list[Measurement]) -> Measurement:
    def median(name: str) -> float:
        return round(statistics.median(float(getattr(value, name)) for value in values), 3)

    return Measurement(
        requests=values[0].requests,
        concurrency=values[0].concurrency,
        response_bytes=int(statistics.median(value.response_bytes for value in values)),
        wall_seconds=median("wall_seconds"),
        requests_per_second=median("requests_per_second"),
        mebibytes_per_second=median("mebibytes_per_second"),
        p50_ms=median("p50_ms"),
        p95_ms=median("p95_ms"),
        p99_ms=median("p99_ms"),
        maximum_ms=median("maximum_ms"),
    )


def run_client_once(command: str, timeout: float) -> float:
    arguments = shlex.split(command)
    if not arguments:
        raise RequestFailure("apk client command is empty")
    started = time.perf_counter()
    subprocess.run(arguments, check=True, timeout=timeout)
    return (time.perf_counter() - started) * 1000


def run_client_pair(
    nexus_command: str,
    candidate_command: str,
    rounds: int,
    timeout: float,
) -> dict[str, Any]:
    commands = [("Nexus", nexus_command), ("kkRepo", candidate_command)]
    samples: dict[str, list[float]] = {"Nexus": [], "kkRepo": []}
    for round_index in range(rounds):
        order = commands if round_index % 2 == 0 else list(reversed(commands))
        for name, command in order:
            samples[name].append(run_client_once(command, timeout))

    def summary(values: list[float]) -> dict[str, float]:
        return {
            "p50_ms": round(statistics.median(values), 3),
            "p95_ms": round(percentile(values, 0.95), 3),
        }

    nexus = summary(samples["Nexus"])
    candidate = summary(samples["kkRepo"])
    return {
        "Nexus": nexus,
        "kkRepo": candidate,
        "p95_ratio": round(candidate["p95_ms"] / nexus["p95_ms"], 3),
    }


def markdown(report: dict[str, Any]) -> str:
    lines = [
        "| Scenario | Nexus req/s | kkRepo req/s | Throughput | Nexus p95 | kkRepo p95 | p95 ratio |",
        "| --- | ---: | ---: | ---: | ---: | ---: | ---: |",
    ]
    for item in report["comparisons"]:
        lines.append(
            f"| {item['scenario']} | {item['nexus_rps']:.2f} | {item['kkrepo_rps']:.2f} | "
            f"{item['throughput_ratio']:.3f}x | {item['nexus_p95_ms']:.3f} ms | "
            f"{item['kkrepo_p95_ms']:.3f} ms | {item['p95_ratio']:.3f}x |"
        )
    return "\n".join(lines) + "\n"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--nexus-base-url", required=True)
    parser.add_argument("--kkrepo-base-url", required=True)
    parser.add_argument("--nexus-auth")
    parser.add_argument("--kkrepo-auth")
    parser.add_argument("--distribution", default="v3.23")
    parser.add_argument("--channel", default="main")
    parser.add_argument("--architecture", default="x86_64")
    parser.add_argument("--package-path", required=True)
    parser.add_argument("--requests", type=int, default=250)
    parser.add_argument("--concurrency", type=int, default=16)
    parser.add_argument("--warmups", type=int, default=32)
    parser.add_argument("--rounds", type=int, default=3)
    parser.add_argument("--timeout", type=float, default=30.0)
    parser.add_argument("--nexus-apk-command")
    parser.add_argument("--kkrepo-apk-command")
    parser.add_argument("--enforce-gates", action="store_true")
    parser.add_argument("--output", type=pathlib.Path)
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    if args.requests < 1 or args.concurrency < 1 or args.warmups < 0 or args.rounds < 1:
        raise SystemExit("requests/concurrency/rounds must be positive and warmups non-negative")
    targets = [
        Target("Nexus", args.nexus_base_url, authorization(args.nexus_auth)),
        Target("kkRepo", args.kkrepo_base_url, authorization(args.kkrepo_auth)),
    ]
    index = f"{args.distribution}/{args.channel}/{args.architecture}/APKINDEX.tar.gz"
    scenarios = [
        Scenario("signed index GET", index, "index"),
        Scenario("signed index HEAD", index, "empty", method="HEAD"),
        Scenario("signed index 304", index, "empty", conditional=True, expected_status=304),
        Scenario("package GET", args.package_path, "bytes", gate="package"),
        Scenario(
            "package Range 64KiB",
            args.package_path,
            "range",
            range_header="bytes=0-65535",
            expected_status=206,
            gate="package",
        ),
        Scenario("package HEAD", args.package_path, "empty", method="HEAD"),
    ]

    preflights: dict[str, dict[str, Any]] = {}
    for scenario in scenarios:
        preflights[scenario.name] = {
            target.name: preflight(target, scenario, args.timeout) for target in targets
        }
    if preflights["signed index GET"]["Nexus"] != preflights["signed index GET"]["kkRepo"]:
        raise SystemExit("logical APKINDEX package sets differ between targets")
    if preflights["package GET"]["Nexus"] != preflights["package GET"]["kkRepo"]:
        raise SystemExit("package fixture bytes differ between targets")
    if preflights["package Range 64KiB"]["Nexus"] != preflights["package Range 64KiB"]["kkRepo"]:
        raise SystemExit("package Range bytes differ between targets")

    samples = {
        scenario.name: {target.name: [] for target in targets} for scenario in scenarios
    }
    raw_results: list[dict[str, Any]] = []
    for scenario_index, scenario in enumerate(scenarios):
        for round_index in range(args.rounds):
            order = targets if (scenario_index + round_index) % 2 == 0 else list(reversed(targets))
            for target in order:
                if args.warmups:
                    measure(target, scenario, args.warmups, min(args.concurrency, args.warmups), args.timeout)
                value = measure(target, scenario, args.requests, args.concurrency, args.timeout)
                samples[scenario.name][target.name].append(value)
                raw_results.append({
                    "round": round_index + 1,
                    "scenario": scenario.name,
                    "target": target.name,
                    "measurement": asdict(value),
                })

    results: list[dict[str, Any]] = []
    comparisons: list[dict[str, Any]] = []
    gate_failures: list[str] = []
    for scenario in scenarios:
        nexus = median_measurement(samples[scenario.name]["Nexus"])
        candidate = median_measurement(samples[scenario.name]["kkRepo"])
        results.extend([
            {"scenario": scenario.name, "target": "Nexus", "measurement": asdict(nexus)},
            {"scenario": scenario.name, "target": "kkRepo", "measurement": asdict(candidate)},
        ])
        throughput = candidate.requests_per_second / nexus.requests_per_second
        p95 = candidate.p95_ms / nexus.p95_ms
        comparisons.append({
            "scenario": scenario.name,
            "nexus_rps": nexus.requests_per_second,
            "kkrepo_rps": candidate.requests_per_second,
            "throughput_ratio": round(throughput, 3),
            "nexus_p95_ms": nexus.p95_ms,
            "kkrepo_p95_ms": candidate.p95_ms,
            "p95_ratio": round(p95, 3),
        })
        minimum_throughput = 0.90 if scenario.gate == "package" else 0.80
        maximum_p95 = 1.15 if scenario.gate == "package" else 1.25
        if throughput < minimum_throughput or p95 > maximum_p95:
            gate_failures.append(
                f"{scenario.name}: throughput={throughput:.3f}x, p95={p95:.3f}x"
            )

    client_results = None
    if bool(args.nexus_apk_command) != bool(args.kkrepo_apk_command):
        raise SystemExit("both --nexus-apk-command and --kkrepo-apk-command are required")
    if args.nexus_apk_command and args.kkrepo_apk_command:
        client_results = run_client_pair(
            args.nexus_apk_command, args.kkrepo_apk_command, args.rounds, args.timeout
        )
        if client_results["p95_ratio"] > 1.25:
            gate_failures.append(
                f"apk client flow: p95={client_results['p95_ratio']:.3f}x"
            )

    report: dict[str, Any] = {
        "generated_at": dt.datetime.now(dt.timezone.utc).isoformat(),
        "host": {"platform": platform.platform(), "processor": platform.processor(),
                 "python": platform.python_version()},
        "configuration": {
            "requests": args.requests,
            "concurrency": args.concurrency,
            "warmups": args.warmups,
            "rounds": args.rounds,
            "nexus_base_url": args.nexus_base_url,
            "kkrepo_base_url": args.kkrepo_base_url,
            "package_path": args.package_path,
        },
        "preflights": preflights,
        "results": results,
        "raw_results": raw_results,
        "comparisons": comparisons,
        "apk_client": client_results,
        "gate_failures": gate_failures,
    }
    rendered = markdown(report)
    print(rendered, end="")
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        if args.output.suffix.lower() == ".json":
            args.output.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
        else:
            args.output.write_text(rendered, encoding="utf-8")
    if args.enforce_gates and gate_failures:
        raise SystemExit("Alpine performance gates failed:\n- " + "\n- ".join(gate_failures))


if __name__ == "__main__":
    main()
