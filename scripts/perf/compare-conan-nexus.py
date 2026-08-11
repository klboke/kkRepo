#!/usr/bin/env python3
"""Compare warmed Conan 2 HTTP paths on Nexus and kkRepo.

Both hosted repositories must contain the same immutable RREV/PREV and package
contents. The runner validates response semantics and the archive's logical file
tree before timing, then alternates target order and reports the median of
independent rounds. Archive container metadata may differ across upload times.
"""

from __future__ import annotations

import argparse
import base64
import concurrent.futures
import datetime as dt
import hashlib
import http.client
import io
import json
import math
import pathlib
import platform
import ssl
import statistics
import tarfile
import threading
import time
import urllib.parse
from dataclasses import asdict, dataclass
from typing import Any


@dataclass(frozen=True)
class Target:
    name: str
    base_url: str
    authorization: str | None


@dataclass(frozen=True)
class Scenario:
    name: str
    route: str
    validation: str
    method: str = "GET"
    range_header: str | None = None
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
    minimum_ms: float
    p50_ms: float
    p95_ms: float
    p99_ms: float
    maximum_ms: float
    mean_ms: float


class RequestFailure(RuntimeError):
    pass


def authorization(value: str | None) -> str | None:
    if not value:
        return None
    return "Basic " + base64.b64encode(value.encode("utf-8")).decode("ascii")


def connection(
    parts: urllib.parse.SplitResult, timeout: float
) -> http.client.HTTPConnection:
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


def request_route(
    target: Target, relative: str
) -> tuple[urllib.parse.SplitResult, str]:
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
) -> tuple[float, bytes]:
    headers = {
        "Accept": "*/*",
        "User-Agent": "kkrepo-conan-performance-comparison/1",
    }
    if target.authorization:
        headers["Authorization"] = target.authorization
    if scenario.range_header:
        headers["Range"] = scenario.range_header
    started = time.perf_counter_ns()
    client.request(scenario.method, route, headers=headers)
    response = client.getresponse()
    body = response.read()
    elapsed_ms = (time.perf_counter_ns() - started) / 1_000_000
    if response.status != scenario.expected_status:
        raise RequestFailure(
            f"{target.name} {scenario.method} {route} returned HTTP {response.status}: "
            f"{body[:300]!r}"
        )
    if scenario.validation not in {"empty", "status"} and not body:
        raise RequestFailure(f"{target.name} {scenario.name} returned an empty body")
    return elapsed_ms, body


def canonical_json(body: bytes, scenario: Scenario) -> Any:
    try:
        value = json.loads(body)
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise RequestFailure(f"{scenario.name} returned invalid JSON") from error
    if scenario.validation == "search":
        return sorted(value.get("results", []))
    if scenario.validation == "revision":
        return value.get("revision")
    if scenario.validation == "revisions":
        return sorted(item.get("revision") for item in value.get("revisions", []))
    if scenario.validation == "files":
        files = value.get("files", {})
        return sorted(files if isinstance(files, list) else files.keys())
    if scenario.validation == "packages":
        return sorted(
            (package_id, details.get("content"))
            for package_id, details in value.items()
        )
    raise RequestFailure(f"unknown JSON validation mode: {scenario.validation}")


def canonical(body: bytes, scenario: Scenario) -> Any:
    if scenario.validation == "empty":
        if body:
            raise RequestFailure(f"{scenario.name} should have an empty response body")
        return "empty"
    if scenario.validation == "status":
        return scenario.expected_status
    if scenario.validation in {"search", "revision", "revisions", "files", "packages"}:
        return canonical_json(body, scenario)
    if scenario.validation == "range":
        return {"size": len(body)}
    if scenario.validation == "archive":
        return canonical_archive(body, scenario)
    if scenario.validation == "bytes":
        return {"size": len(body), "sha256": hashlib.sha256(body).hexdigest()}
    raise RequestFailure(f"unknown validation mode: {scenario.validation}")


def canonical_archive(body: bytes, scenario: Scenario) -> list[dict[str, Any]]:
    entries: list[dict[str, Any]] = []
    try:
        with tarfile.open(fileobj=io.BytesIO(body), mode="r:*") as archive:
            for member in archive:
                entry: dict[str, Any] = {"name": member.name, "type": member.type.hex()}
                if member.isfile():
                    source = archive.extractfile(member)
                    if source is None:
                        raise RequestFailure(
                            f"{scenario.name} could not read archive member {member.name!r}"
                        )
                    digest = hashlib.sha256()
                    size = 0
                    while chunk := source.read(1024 * 1024):
                        digest.update(chunk)
                        size += len(chunk)
                    entry.update({"size": size, "sha256": digest.hexdigest()})
                elif member.issym() or member.islnk():
                    entry["linkname"] = member.linkname
                entries.append(entry)
    except (tarfile.TarError, OSError) as error:
        raise RequestFailure(f"{scenario.name} returned an invalid tar archive") from error
    return sorted(entries, key=lambda entry: (entry["name"], entry["type"]))


def preflight(target: Target, scenario: Scenario, timeout: float) -> Any:
    parts, route = request_route(target, scenario.route)
    client = connection(parts, timeout)
    try:
        _, body = one_request(client, target, route, scenario)
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
    safe_concurrency = max(1, min(concurrency, requests))
    assignments = [requests // safe_concurrency] * safe_concurrency
    for index in range(requests % safe_concurrency):
        assignments[index] += 1
    barrier = threading.Barrier(safe_concurrency)

    def worker(count: int) -> tuple[list[float], int]:
        parts, route = request_route(target, scenario.route)
        client = connection(parts, timeout)
        timings: list[float] = []
        response_bytes = 0
        try:
            barrier.wait(timeout=timeout)
            for _ in range(count):
                elapsed, body = one_request(client, target, route, scenario)
                timings.append(elapsed)
                response_bytes += len(body)
            return timings, response_bytes
        finally:
            client.close()

    started = time.perf_counter()
    timings: list[float] = []
    response_bytes = 0
    with concurrent.futures.ThreadPoolExecutor(max_workers=safe_concurrency) as executor:
        futures = [executor.submit(worker, count) for count in assignments]
        for future in futures:
            worker_timings, worker_bytes = future.result()
            timings.extend(worker_timings)
            response_bytes += worker_bytes
    wall = time.perf_counter() - started
    return Measurement(
        requests=len(timings),
        concurrency=safe_concurrency,
        response_bytes=response_bytes,
        wall_seconds=round(wall, 6),
        requests_per_second=round(len(timings) / wall, 2),
        mebibytes_per_second=round(response_bytes / wall / 1024 / 1024, 2),
        minimum_ms=round(min(timings), 3),
        p50_ms=round(percentile(timings, 0.50), 3),
        p95_ms=round(percentile(timings, 0.95), 3),
        p99_ms=round(percentile(timings, 0.99), 3),
        maximum_ms=round(max(timings), 3),
        mean_ms=round(statistics.fmean(timings), 3),
    )


def median_measurement(measurements: list[Measurement]) -> Measurement:
    def median(field: str) -> float:
        return round(
            statistics.median(float(getattr(item, field)) for item in measurements), 3
        )

    first = measurements[0]
    return Measurement(
        requests=first.requests,
        concurrency=first.concurrency,
        response_bytes=int(statistics.median(item.response_bytes for item in measurements)),
        wall_seconds=median("wall_seconds"),
        requests_per_second=round(
            statistics.median(item.requests_per_second for item in measurements), 2
        ),
        mebibytes_per_second=round(
            statistics.median(item.mebibytes_per_second for item in measurements), 2
        ),
        minimum_ms=median("minimum_ms"),
        p50_ms=median("p50_ms"),
        p95_ms=median("p95_ms"),
        p99_ms=median("p99_ms"),
        maximum_ms=median("maximum_ms"),
        mean_ms=median("mean_ms"),
    )


def quote(value: str) -> str:
    return urllib.parse.quote(value, safe="")


def scenarios(args: argparse.Namespace) -> list[Scenario]:
    user = args.user or "_"
    channel = args.channel or "_"
    recipe = "/".join(map(quote, [args.name, args.version, user, channel]))
    rrev = f"v2/conans/{recipe}/revisions/{quote(args.recipe_revision)}"
    package = f"{rrev}/packages/{quote(args.package_id)}"
    prev = f"{package}/revisions/{quote(args.package_revision)}"
    reference = f"{args.name}/{args.version}"
    if args.user:
        reference += f"@{args.user}"
    if args.channel:
        reference += f"/{args.channel}"
    search = urllib.parse.urlencode({"q": reference})
    return [
        Scenario("ping", "v1/ping", "empty"),
        Scenario("recipe search", f"v2/conans/search?{search}", "search"),
        Scenario("recipe latest", f"v2/conans/{recipe}/latest", "revision"),
        Scenario("recipe revisions", f"v2/conans/{recipe}/revisions", "revisions"),
        Scenario("recipe files", f"{rrev}/files", "files"),
        Scenario("package search", f"{rrev}/search?list_only=True", "packages"),
        Scenario("package latest", f"{package}/latest", "revision"),
        Scenario("package revisions", f"{package}/revisions", "revisions"),
        Scenario("package files", f"{prev}/files", "files"),
        Scenario(
            "package GET", f"{prev}/files/{quote(args.package_file)}", "archive", gate="blob"
        ),
        Scenario(
            "package Range 64KiB",
            f"{prev}/files/{quote(args.package_file)}",
            "range",
            range_header="bytes=0-65535",
            expected_status=206,
            gate="blob",
        ),
        Scenario(
            "HEAD compatibility",
            f"{prev}/files/{quote(args.package_file)}",
            "status",
            method="HEAD",
            expected_status=404,
            gate="compatibility",
        ),
    ]


def gate(scenario: Scenario, throughput: float, p95: float) -> bool:
    if scenario.gate == "metadata":
        return throughput >= 0.80 and p95 <= 1.25
    if scenario.gate == "blob":
        return throughput >= 0.90 and p95 <= 1.15
    return True


def markdown(report: dict[str, Any]) -> str:
    lines = [
        "# Conan Nexus / kkRepo performance comparison",
        "",
        f"Generated: `{report['generated_at']}`",
        f"Each row is the median of `{report['configuration']['rounds']}` rounds.",
        "",
        "| Scenario | Target | req/s | MiB/s | p50 ms | p95 ms | p99 ms |",
        "| --- | --- | ---: | ---: | ---: | ---: | ---: |",
    ]
    for result in report["results"]:
        measurement = result["measurement"]
        lines.append(
            "| {scenario} | {target} | {requests_per_second:.2f} | "
            "{mebibytes_per_second:.2f} | {p50_ms:.3f} | {p95_ms:.3f} | "
            "{p99_ms:.3f} |".format(**result, **measurement)
        )
    lines.extend(
        [
            "",
            "| Scenario | throughput ratio | p95 latency ratio | Gate |",
            "| --- | ---: | ---: | --- |",
        ]
    )
    for item in report["comparisons"]:
        lines.append(
            f"| {item['scenario']} | {item['throughput_ratio']:.3f} | "
            f"{item['p95_latency_ratio']:.3f} | "
            f"{'PASS' if item['passed'] else 'FAIL'} |"
        )
    lines.extend(
        [
            "",
            "> Directional same-host result only. Preflight requires identical revision/file-list "
            "semantics and an identical logical package tree before timing; tar container metadata "
            "may differ across upload times.",
            "",
        ]
    )
    return "\n".join(lines)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--nexus-base-url", required=True)
    parser.add_argument("--kkrepo-base-url", required=True)
    parser.add_argument("--nexus-auth", help="username:password")
    parser.add_argument("--kkrepo-auth", help="username:password")
    parser.add_argument("--name", required=True)
    parser.add_argument("--version", required=True)
    parser.add_argument("--user")
    parser.add_argument("--channel")
    parser.add_argument("--recipe-revision", required=True)
    parser.add_argument("--package-id", required=True)
    parser.add_argument("--package-revision", required=True)
    parser.add_argument("--package-file", default="conan_package.tgz")
    parser.add_argument("--requests", type=int, default=250)
    parser.add_argument("--concurrency", type=int, default=16)
    parser.add_argument("--warmups", type=int, default=32)
    parser.add_argument("--rounds", type=int, default=3)
    parser.add_argument("--timeout", type=float, default=30.0)
    parser.add_argument("--output", type=pathlib.Path)
    parser.add_argument("--enforce-gates", action="store_true")
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    if args.requests < 1 or args.concurrency < 1 or args.warmups < 0 or args.rounds < 1:
        raise SystemExit(
            "requests/concurrency/rounds must be positive and warmups must be non-negative"
        )
    targets = [
        Target("Nexus", args.nexus_base_url, authorization(args.nexus_auth)),
        Target("kkRepo", args.kkrepo_base_url, authorization(args.kkrepo_auth)),
    ]
    benchmark_scenarios = scenarios(args)
    preflights: dict[str, dict[str, Any]] = {}
    for scenario in benchmark_scenarios:
        values = {
            target.name: preflight(target, scenario, args.timeout) for target in targets
        }
        if values["Nexus"] != values["kkRepo"]:
            raise SystemExit(
                f"preflight differs for {scenario.name}: "
                f"Nexus={values['Nexus']!r}, kkRepo={values['kkRepo']!r}"
            )
        preflights[scenario.name] = values

    samples: dict[str, dict[str, list[Measurement]]] = {
        scenario.name: {target.name: [] for target in targets}
        for scenario in benchmark_scenarios
    }
    raw_results: list[dict[str, Any]] = []
    for scenario_index, scenario in enumerate(benchmark_scenarios):
        for round_index in range(args.rounds):
            ordered = (
                targets
                if (scenario_index + round_index) % 2 == 0
                else list(reversed(targets))
            )
            for target in ordered:
                if args.warmups:
                    measure(
                        target,
                        scenario,
                        args.warmups,
                        min(args.concurrency, args.warmups),
                        args.timeout,
                    )
                measured = measure(
                    target, scenario, args.requests, args.concurrency, args.timeout
                )
                samples[scenario.name][target.name].append(measured)
                raw_results.append(
                    {
                        "round": round_index + 1,
                        "scenario": scenario.name,
                        "target": target.name,
                        "measurement": asdict(measured),
                    }
                )

    results: list[dict[str, Any]] = []
    comparisons: list[dict[str, Any]] = []
    for scenario in benchmark_scenarios:
        aggregate = {
            target.name: median_measurement(samples[scenario.name][target.name])
            for target in targets
        }
        for target in targets:
            results.append(
                {
                    "scenario": scenario.name,
                    "target": target.name,
                    "measurement": asdict(aggregate[target.name]),
                }
            )
        nexus = aggregate["Nexus"]
        candidate = aggregate["kkRepo"]
        throughput = candidate.requests_per_second / nexus.requests_per_second
        p95 = candidate.p95_ms / nexus.p95_ms
        comparisons.append(
            {
                "scenario": scenario.name,
                "gate": scenario.gate,
                "throughput_ratio": round(throughput, 3),
                "p50_latency_ratio": round(candidate.p50_ms / nexus.p50_ms, 3),
                "p95_latency_ratio": round(p95, 3),
                "passed": gate(scenario, throughput, p95),
            }
        )

    report: dict[str, Any] = {
        "generated_at": dt.datetime.now(dt.timezone.utc).isoformat(),
        "host": {
            "platform": platform.platform(),
            "processor": platform.processor(),
            "python": platform.python_version(),
        },
        "configuration": {
            "requests": args.requests,
            "concurrency": args.concurrency,
            "warmups": args.warmups,
            "rounds": args.rounds,
            "nexus_base_url": args.nexus_base_url,
            "kkrepo_base_url": args.kkrepo_base_url,
            "reference": {
                "name": args.name,
                "version": args.version,
                "user": args.user,
                "channel": args.channel,
                "recipe_revision": args.recipe_revision,
                "package_id": args.package_id,
                "package_revision": args.package_revision,
            },
        },
        "preflights": preflights,
        "results": results,
        "raw_results": raw_results,
        "comparisons": comparisons,
    }
    rendered = markdown(report)
    print(rendered)
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        if args.output.suffix.lower() == ".json":
            args.output.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
        else:
            args.output.write_text(rendered, encoding="utf-8")
    if args.enforce_gates and not all(item["passed"] for item in comparisons):
        raise SystemExit("one or more Conan performance gates failed")


if __name__ == "__main__":
    main()
